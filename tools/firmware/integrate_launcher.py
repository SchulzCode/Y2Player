#!/usr/bin/env python3
"""
Install Y2Player into the Y2 system image.

System-partition-only integration: boot, recovery, preloader, NVRAM and every
PROTECTED region are never touched. The image is edited offline with debugfs
(no root, no loop mount), reconciled with e2fsck, then re-encoded as a sparse
image byte-compatible with the stock one.

Requires: e2fsprogs (debugfs, e2fsck, dumpe2fs). Run on Linux or WSL.

    python3 integrate_launcher.py \
        --system   OriginalFirmware/system.img \
        --apk      app/build/outputs/apk/release/app-release.apk \
        --native-lib app/src/main/jniLibs/armeabi-v7a/liby2audio.so \
        --out      dist/firmware/system.img \
        [--report  dist/firmware/integration-report.txt]

Layout note: Android 4.4 scans /system/priv-app for APK files *directly*. The
per-package subdirectory layout (priv-app/Name/Name.apk) is Android 5.0+ and
would be silently ignored here, leaving the device with no launcher.

"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

import sparse
import patch_mtk_keylayout as keylayout_patch
import patch_primary_audio_hal as hal_patch
import system_package_policy as package_policy

STOCK_LAUNCHER = "/priv-app/MyLauncher.apk"
STOCK_LAUNCHER_ODEX = "/priv-app/MyLauncher.odex"
TARGET_APK = "/priv-app/Y2Player.apk"
TARGET_NATIVE_LIBRARY = "/lib/liby2audio.so"
TARGET_PRIMARY_AUDIO_HAL = hal_patch.HAL_SYSTEM_PATH
TARGET_KEY_LAYOUT = keylayout_patch.KEYLAYOUT_SYSTEM_PATH
APK_NATIVE_ENTRY = "lib/armeabi-v7a/liby2audio.so"
# Matches stock files in both /system/priv-app and /system/lib.
SELINUX_CONTEXT = b"u:object_r:system_file:s0\x00"
APK_MODE = "0100644"  # regular file, rw-r--r--
NATIVE_LIBRARY_MODE = "0100644"
KEY_LAYOUT_MODE = "0100644"

def run(cmd, **kwargs):
    result = subprocess.run(cmd, capture_output=True, text=True, **kwargs)
    return result


def debugfs(image, commands, write=False):
    """Execute a debugfs command script against the image."""
    with tempfile.NamedTemporaryFile("w", suffix=".debugfs", delete=False) as handle:
        handle.write("\n".join(commands) + "\n")
        script = handle.name
    try:
        cmd = ["debugfs"]
        if write:
            cmd.append("-w")
        cmd += ["-f", script, image]
        return run(cmd)
    finally:
        os.unlink(script)


def query(image, command):
    return debugfs(image, [command]).stdout


def require_tools():
    missing = [t for t in ("debugfs", "e2fsck", "dumpe2fs") if shutil.which(t) is None]
    if missing:
        raise SystemExit(f"missing required tool(s): {', '.join(missing)} — install e2fsprogs")


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def sha256_stream(handle):
    digest = hashlib.sha256()
    for block in iter(lambda: handle.read(1 << 20), b""):
        digest.update(block)
    return digest.hexdigest()


def apk_native_library(apk):
    with zipfile.ZipFile(apk) as archive:
        candidates = [
            info for info in archive.infolist()
            if info.filename.endswith("/liby2audio.so")
        ]
        if [info.filename for info in candidates] != [APK_NATIVE_ENTRY]:
            found = ", ".join(info.filename for info in candidates) or "none"
            raise SystemExit(
                f"APK must contain exactly {APK_NATIVE_ENTRY}; found: {found}"
            )
        info = candidates[0]
        with archive.open(info) as handle:
            return info.file_size, sha256_stream(handle)


def elf_description(path):
    with open(path, "rb") as handle:
        header = handle.read(52)
    if len(header) < 52 or header[:4] != b"\x7fELF":
        raise SystemExit(f"native library is not an ELF file: {path}")
    if header[4] != 1 or header[5] != 1:
        raise SystemExit("native library must be 32-bit little-endian ELF")
    machine = int.from_bytes(header[18:20], "little")
    flags = int.from_bytes(header[36:40], "little")
    eabi = (flags >> 24) & 0xFF
    if machine != 40 or eabi != 5:
        raise SystemExit(
            f"native library must be ARM EABI5 (machine={machine}, EABI={eabi})"
        )
    return f"ELF32 little-endian ARM EABI{eabi} (e_flags=0x{flags:08x})"


def dump(image, source, destination):
    result = debugfs(image, [f"dump {source} {destination}"])
    return result.returncode == 0 and os.path.isfile(destination)


def free_bytes(image):
    out = run(["dumpe2fs", "-h", image]).stdout
    free = block = 0
    for line in out.splitlines():
        if line.startswith("Free blocks:"):
            free = int(line.split(":")[1])
        elif line.startswith("Block size:"):
            block = int(line.split(":")[1])
    return free * block


def stat_size(stat_output):
    match = re.search(r"\bSize:\s*(\d+)", stat_output)
    return int(match.group(1)) if match else None


def zero_free_blocks(image):
    """Overwrite every block marked free by ext4 so sparse.pack can omit it."""
    header = run(["dumpe2fs", "-h", image])
    report = run(["dumpe2fs", image])
    if header.returncode != 0 or report.returncode != 0:
        raise SystemExit("dumpe2fs could not enumerate ext4 free blocks")

    values = {}
    for line in header.stdout.splitlines():
        match = re.match(r"^(Block count|Free blocks|Block size):\s*(\d+)$", line)
        if match:
            values[match.group(1)] = int(match.group(2))
    if set(values) != {"Block count", "Free blocks", "Block size"}:
        raise SystemExit("dumpe2fs header is missing block-count information")

    ranges = []
    for line in report.stdout.splitlines():
        # Group entries are indented; the superblock's aggregate value is not.
        match = re.match(r"^\s+Free blocks:\s*(.*)$", line)
        if not match or not match.group(1).strip() or match.group(1).strip() == "(none)":
            continue
        for token in match.group(1).split(","):
            bounds = token.strip().split("-", 1)
            start = int(bounds[0])
            end = int(bounds[-1])
            if start > end or start < 0 or end >= values["Block count"]:
                raise SystemExit(f"invalid ext4 free-block range: {token.strip()}")
            ranges.append((start, end))

    ranges.sort()
    for previous, current in zip(ranges, ranges[1:]):
        if current[0] <= previous[1]:
            raise SystemExit(
                f"overlapping ext4 free-block ranges: {previous} and {current}"
            )
    parsed_count = sum(end - start + 1 for start, end in ranges)
    if parsed_count != values["Free blocks"]:
        raise SystemExit(
            "ext4 free-block map does not match superblock: "
            f"map={parsed_count}, superblock={values['Free blocks']}"
        )

    block_size = values["Block size"]
    zero_chunk = b"\x00" * (8 << 20)
    with open(image, "r+b", buffering=0) as handle:
        for start, end in ranges:
            remaining = (end - start + 1) * block_size
            handle.seek(start * block_size)
            while remaining:
                piece_size = min(remaining, len(zero_chunk))
                handle.write(zero_chunk[:piece_size])
                remaining -= piece_size
        handle.flush()
        os.fsync(handle.fileno())
    return parsed_count, parsed_count * block_size, len(ranges)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--system", required=True, help="stock sparse system.img (never modified)")
    parser.add_argument("--apk", required=True, help="signed release Y2Player APK")
    parser.add_argument(
        "--native-lib", required=True,
        help="compiled armeabi-v7a liby2audio.so embedded in the APK",
    )
    parser.add_argument("--out", required=True, help="output sparse system.img")
    parser.add_argument("--report", help="write an integration report here")
    parser.add_argument("--keep-raw", action="store_true", help="keep the intermediate raw image")
    args = parser.parse_args()

    require_tools()
    required = [args.system, args.apk, args.native_lib]
    for path in required:
        if not os.path.isfile(path):
            raise SystemExit(f"not found: {path}")

    lines = []

    def log(message):
        print(message)
        lines.append(message)

    log("=== Y2Player firmware integration (system partition only) ===")
    log(f"stock system.img : {args.system}")
    log(f"  sha256         : {sha256(args.system)}")
    log(f"payload APK      : {args.apk} ({os.path.getsize(args.apk)} bytes)")
    log(f"  sha256         : {sha256(args.apk)}")
    native_size = os.path.getsize(args.native_lib)
    native_sha = sha256(args.native_lib)
    native_elf = elf_description(args.native_lib)
    apk_native_size, apk_native_sha = apk_native_library(args.apk)
    if (apk_native_size, apk_native_sha) != (native_size, native_sha):
        raise SystemExit(
            "APK native library differs from compiled liby2audio.so: "
            f"compiled={native_size} bytes/{native_sha}, "
            f"APK={apk_native_size} bytes/{apk_native_sha}"
        )
    log(f"native library   : {args.native_lib} ({native_size} bytes)")
    log(f"  sha256         : {native_sha}")
    log(f"  ELF/ABI        : {native_elf}")
    log(f"  APK entry      : {APK_NATIVE_ENTRY} (byte-identical)")

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    raw = os.path.abspath(args.out) + ".raw"

    log("\n[1/6] expanding sparse image")
    written, block_size, blocks = sparse.unpack(args.system, raw)
    log(f"      {written} bytes ({blocks} blocks x {block_size})")

    log("\n[2/6] verifying stock contents and preparing guarded system patches")
    stock_stat = query(raw, f"stat {STOCK_LAUNCHER}")
    if "Inode" not in stock_stat:
        raise SystemExit(f"{STOCK_LAUNCHER} not found — is this the stock Y2 system image?")
    if "Inode" not in query(raw, "stat /lib"):
        raise SystemExit("/system/lib is missing from the stock system image")
    if "Inode" in query(raw, f"stat {TARGET_NATIVE_LIBRARY}"):
        raise SystemExit(f"stock image unexpectedly already contains {TARGET_NATIVE_LIBRARY}")
    log(f"      found {STOCK_LAUNCHER}")
    for required_apk in package_policy.REQUIRED_APKS:
        if "Inode" not in query(raw, f"stat {required_apk}"):
            raise SystemExit(
                f"required stock package is missing: {required_apk} — refusing to minimize"
            )

    prune_paths = []
    prune_bytes = 0
    for directory, stem in package_policy.pruned_packages():
        apk_path, odex_path = package_policy.package_files(directory, stem)
        apk_stat = query(raw, f"stat {apk_path}")
        if "Inode" not in apk_stat:
            raise SystemExit(
                f"expected removable stock package is missing: {apk_path} — "
                "is this the audited stock Y2 image?"
            )
        prune_paths.append(apk_path)
        prune_bytes += stat_size(apk_stat) or 0
        odex_stat = query(raw, f"stat {odex_path}")
        if "Inode" in odex_stat:
            prune_paths.append(odex_path)
            prune_bytes += stat_size(odex_stat) or 0
    for support_path in package_policy.PRUNED_SUPPORT_FILES:
        support_stat = query(raw, f"stat {support_path}")
        if "Inode" not in support_stat:
            raise SystemExit(
                f"expected removable support file is missing: {support_path} — "
                "is this the audited stock Y2 image?"
            )
        prune_paths.append(support_path)
        prune_bytes += stat_size(support_stat) or 0

    log(
        f"      package profile: {package_policy.PROFILE_NAME} "
        f"({len(package_policy.pruned_packages())} optional packages)"
    )
    for group, packages in package_policy.PRUNED_PACKAGE_GROUPS.items():
        log(f"        prune {group}: {len(packages)}")
    log(f"      removable payload: {len(prune_paths)} files, {prune_bytes} bytes")
    log(f"      required package guard: {len(package_policy.REQUIRED_APKS)} APKs")
    stock_hal = raw + ".stock-primary-hal.so"
    patched_hal = raw + ".patched-primary-hal.so"
    if not dump(raw, TARGET_PRIMARY_AUDIO_HAL, stock_hal):
        raise SystemExit(f"could not read stock {TARGET_PRIMARY_AUDIO_HAL}")
    try:
        with open(stock_hal, "rb") as handle:
            patched_hal_bytes = hal_patch.patch_hal(handle.read())
        hal_patch.write_atomic(patched_hal, patched_hal_bytes)
    except hal_patch.PatchError as error:
        raise SystemExit(f"stock primary-audio HAL rejected: {error}") from error
    log(
        f"      stock HAL: {hal_patch.STOCK_SIZE} bytes, "
        f"SHA-256 {hal_patch.STOCK_SHA256}"
    )
    log(
        f"      patched HAL: {len(patched_hal_bytes)} bytes, "
        f"SHA-256 {hal_patch.PATCHED_SHA256}"
    )
    log("      DAC frequency hook: guarded numeric 44100/48000-Hz ioctl")
    stock_keylayout = raw + ".stock-mtk-kpd.kl"
    patched_keylayout = raw + ".patched-mtk-kpd.kl"
    if not dump(raw, TARGET_KEY_LAYOUT, stock_keylayout):
        raise SystemExit(f"could not read stock {TARGET_KEY_LAYOUT}")
    try:
        with open(stock_keylayout, "rb") as handle:
            patched_keylayout_bytes = keylayout_patch.patch_keylayout(handle.read())
        keylayout_patch.write_atomic(patched_keylayout, patched_keylayout_bytes)
    except keylayout_patch.PatchError as error:
        raise SystemExit(f"stock keypad layout rejected: {error}") from error
    log(
        f"      stock keypad: {keylayout_patch.STOCK_SIZE} bytes, "
        f"SHA-256 {keylayout_patch.STOCK_SHA256}"
    )
    log(
        f"      patched keypad: {len(patched_keylayout_bytes)} bytes, "
        f"SHA-256 {keylayout_patch.PATCHED_SHA256}"
    )
    log("      physical volume scan codes 115/114: app-owned FF/REW surrogates")
    free_before = free_bytes(raw)
    log(f"      free space before: {free_before} bytes")

    log("\n[3/6] pruning optional packages and installing Y2Player runtime")
    context_file = raw + ".selinux"
    with open(context_file, "wb") as handle:
        handle.write(SELINUX_CONTEXT)
    commands = [f"rm {path}" for path in prune_paths]
    commands += [
        f"rm {STOCK_LAUNCHER}",
        "cd /priv-app",
        f"write {os.path.abspath(args.apk)} Y2Player.apk",
        f"sif Y2Player.apk mode {APK_MODE}",
        "sif Y2Player.apk uid 0",
        "sif Y2Player.apk gid 0",
        f"ea_set -f {context_file} Y2Player.apk security.selinux",
        "cd /lib",
        f"write {os.path.abspath(args.native_lib)} liby2audio.so",
        f"sif liby2audio.so mode {NATIVE_LIBRARY_MODE}",
        "sif liby2audio.so uid 0",
        "sif liby2audio.so gid 0",
        f"ea_set -f {context_file} liby2audio.so security.selinux",
        f"rm {os.path.basename(TARGET_PRIMARY_AUDIO_HAL)}",
        f"write {os.path.abspath(patched_hal)} {os.path.basename(TARGET_PRIMARY_AUDIO_HAL)}",
        f"sif {os.path.basename(TARGET_PRIMARY_AUDIO_HAL)} mode {NATIVE_LIBRARY_MODE}",
        f"sif {os.path.basename(TARGET_PRIMARY_AUDIO_HAL)} uid 0",
        f"sif {os.path.basename(TARGET_PRIMARY_AUDIO_HAL)} gid 0",
        (
            f"ea_set -f {context_file} {os.path.basename(TARGET_PRIMARY_AUDIO_HAL)} "
            "security.selinux"
        ),
        "cd /usr/keylayout",
        f"rm {os.path.basename(TARGET_KEY_LAYOUT)}",
        f"write {os.path.abspath(patched_keylayout)} {os.path.basename(TARGET_KEY_LAYOUT)}",
        f"sif {os.path.basename(TARGET_KEY_LAYOUT)} mode {KEY_LAYOUT_MODE}",
        f"sif {os.path.basename(TARGET_KEY_LAYOUT)} uid 0",
        f"sif {os.path.basename(TARGET_KEY_LAYOUT)} gid 0",
        (
            f"ea_set -f {context_file} {os.path.basename(TARGET_KEY_LAYOUT)} "
            "security.selinux"
        ),
    ]
    if "Inode" in query(raw, f"stat {STOCK_LAUNCHER_ODEX}"):
        commands.insert(len(prune_paths) + 1, f"rm {STOCK_LAUNCHER_ODEX}")
        log(f"      removing stale {STOCK_LAUNCHER_ODEX}")
    log(
        f"      removing {len(package_policy.pruned_packages())} optional packages "
        f"and {len(package_policy.PRUNED_SUPPORT_FILES)} private support libraries"
    )
    result = debugfs(raw, commands, write=True)
    if result.returncode != 0:
        for temporary in (
            context_file, stock_hal, patched_hal, stock_keylayout, patched_keylayout
        ):
            if os.path.exists(temporary):
                os.unlink(temporary)
        raise SystemExit(f"debugfs failed:\n{result.stdout}\n{result.stderr}")
    for noise in ("File not found", "Invalid", "error"):
        if noise.lower() in result.stdout.lower():
            log(f"      debugfs output: {result.stdout.strip()}")

    os.unlink(context_file)
    os.unlink(stock_hal)
    os.unlink(patched_hal)
    os.unlink(stock_keylayout)
    os.unlink(patched_keylayout)

    log("\n[4/6] reconciling filesystem and clearing freed blocks")
    # First reconcile metadata. Discard may punch holes on capable hosts, but
    # drvfs-backed WSL files do not reliably honor it, so the audited free-block
    # map is also zeroed explicitly below before sparse.pack().
    check = run(["e2fsck", "-fy", "-E", "discard", raw])
    # 0 = clean, 1 = errors corrected. Anything higher is uncorrected damage.
    if check.returncode >= 4:
        raise SystemExit(f"e2fsck reported uncorrected errors ({check.returncode}):\n{check.stdout}")
    log(
        f"      e2fsck -E discard exit {check.returncode} "
        f"({'clean' if check.returncode == 0 else 'corrected'})"
    )
    zeroed_blocks, zeroed_bytes, zeroed_ranges = zero_free_blocks(raw)
    log(
        f"      zeroed {zeroed_blocks} free blocks ({zeroed_bytes} bytes) "
        f"across {zeroed_ranges} ext4 ranges"
    )
    post_zero_check = run(["e2fsck", "-fn", raw])
    if post_zero_check.returncode >= 4:
        raise SystemExit(
            "zeroing free blocks damaged ext4 metadata: "
            f"e2fsck exit {post_zero_check.returncode}\n{post_zero_check.stdout}"
        )
    log(f"      post-zero e2fsck -fn exit {post_zero_check.returncode}")

    log("\n[5/6] verifying result")
    problems = []
    if "Inode" in query(raw, f"stat {STOCK_LAUNCHER}"):
        problems.append("stock MyLauncher.apk is still present")
    new_stat = query(raw, f"stat {TARGET_APK}")
    if "Inode" not in new_stat:
        problems.append("Y2Player.apk was not created")
    else:
        mode_line = next((l for l in new_stat.splitlines() if "Mode:" in l), "")
        if "0644" not in mode_line:
            problems.append(f"unexpected mode: {mode_line.strip()}")
        if "User:     0" not in new_stat or "Group:     0" not in new_stat:
            problems.append(f"unexpected ownership: {mode_line.strip()}")
        size = next((l for l in new_stat.splitlines() if "Size:" in l), "")
        log(f"      {TARGET_APK}: {mode_line.strip()} {size.strip()}")
    context = query(raw, f"ea_get {TARGET_APK} security.selinux")
    if "system_file" not in context:
        problems.append("SELinux context missing or wrong")
    else:
        log(f"      selinux: {context.strip().splitlines()[-1]}")
    native_stat = query(raw, f"stat {TARGET_NATIVE_LIBRARY}")
    if "Inode" not in native_stat:
        problems.append("liby2audio.so was not created under /system/lib")
    else:
        mode_line = next((line for line in native_stat.splitlines() if "Mode:" in line), "")
        if "0644" not in mode_line:
            problems.append(f"unexpected native-library mode: {mode_line.strip()}")
        if "User:     0" not in native_stat or "Group:     0" not in native_stat:
            problems.append("unexpected native-library ownership (expected root:root)")
        installed = raw + ".liby2audio.so"
        if not dump(raw, TARGET_NATIVE_LIBRARY, installed):
            problems.append("installed native library could not be read back")
        else:
            installed_sha = sha256(installed)
            installed_size = os.path.getsize(installed)
            if (installed_size, installed_sha) != (native_size, native_sha):
                problems.append("installed native library differs from compiled artifact")
            log(
                f"      {TARGET_NATIVE_LIBRARY}: {mode_line.strip()} "
                f"Size: {installed_size} SHA-256: {installed_sha}"
            )
            os.unlink(installed)
    native_context = query(raw, f"ea_get {TARGET_NATIVE_LIBRARY} security.selinux")
    if "system_file" not in native_context:
        problems.append("native-library SELinux context missing or wrong")
    else:
        log(f"      native selinux: {native_context.strip().splitlines()[-1]}")
    hal_stat = query(raw, f"stat {TARGET_PRIMARY_AUDIO_HAL}")
    if "Inode" not in hal_stat:
        problems.append("patched primary-audio HAL was not installed")
    else:
        mode_line = next((line for line in hal_stat.splitlines() if "Mode:" in line), "")
        if "0644" not in mode_line:
            problems.append(f"unexpected primary-audio HAL mode: {mode_line.strip()}")
        if "User:     0" not in hal_stat or "Group:     0" not in hal_stat:
            problems.append("unexpected primary-audio HAL ownership (expected root:root)")
        installed_hal = raw + ".installed-primary-hal.so"
        if not dump(raw, TARGET_PRIMARY_AUDIO_HAL, installed_hal):
            problems.append("installed primary-audio HAL could not be read back")
        else:
            try:
                with open(installed_hal, "rb") as handle:
                    installed_hal_sha = hal_patch.verify_patched_hal(handle.read())
                log(
                    f"      {TARGET_PRIMARY_AUDIO_HAL}: {mode_line.strip()} "
                    f"Size: {os.path.getsize(installed_hal)} SHA-256: {installed_hal_sha}"
                )
            except hal_patch.PatchError as error:
                problems.append(f"installed primary-audio HAL is invalid: {error}")
            finally:
                os.unlink(installed_hal)
    hal_context = query(raw, f"ea_get {TARGET_PRIMARY_AUDIO_HAL} security.selinux")
    if "system_file" not in hal_context:
        problems.append("primary-audio HAL SELinux context missing or wrong")
    else:
        log(f"      HAL selinux: {hal_context.strip().splitlines()[-1]}")
    keylayout_stat = query(raw, f"stat {TARGET_KEY_LAYOUT}")
    if "Inode" not in keylayout_stat:
        problems.append("patched keypad layout was not installed")
    else:
        mode_line = next((line for line in keylayout_stat.splitlines() if "Mode:" in line), "")
        if "0644" not in mode_line:
            problems.append(f"unexpected keypad-layout mode: {mode_line.strip()}")
        if "User:     0" not in keylayout_stat or "Group:     0" not in keylayout_stat:
            problems.append("unexpected keypad-layout ownership (expected root:root)")
        installed_keylayout = raw + ".installed-mtk-kpd.kl"
        if not dump(raw, TARGET_KEY_LAYOUT, installed_keylayout):
            problems.append("installed keypad layout could not be read back")
        else:
            try:
                with open(installed_keylayout, "rb") as handle:
                    installed_keylayout_sha = keylayout_patch.verify_patched_keylayout(handle.read())
                log(
                    f"      {TARGET_KEY_LAYOUT}: {mode_line.strip()} "
                    f"Size: {os.path.getsize(installed_keylayout)} SHA-256: {installed_keylayout_sha}"
                )
            except keylayout_patch.PatchError as error:
                problems.append(f"installed keypad layout is invalid: {error}")
            finally:
                os.unlink(installed_keylayout)
    keylayout_context = query(raw, f"ea_get {TARGET_KEY_LAYOUT} security.selinux")
    if "system_file" not in keylayout_context:
        problems.append("keypad-layout SELinux context missing or wrong")
    else:
        log(f"      keypad selinux: {keylayout_context.strip().splitlines()[-1]}")
    # Nothing else in priv-app may have changed.
    listing = query(raw, "ls /priv-app")
    if "MyLauncher" in listing:
        problems.append("MyLauncher still referenced in /priv-app")
    if "Y2Player.apk" not in listing:
        problems.append("Y2Player.apk not listed in /priv-app")
    # A second copy under /system/app would install the package twice.
    if "Y2Player" in query(raw, "ls /app"):
        problems.append("a duplicate Y2Player is present in /system/app")
    for directory, stem in package_policy.pruned_packages():
        for path in package_policy.package_files(directory, stem):
            if "Inode" in query(raw, f"stat {path}"):
                problems.append(f"pruned package file is still present: {path}")
    for path in package_policy.PRUNED_SUPPORT_FILES:
        if "Inode" in query(raw, f"stat {path}"):
            problems.append(f"pruned support file is still present: {path}")
    for path in package_policy.REQUIRED_APKS:
        if "Inode" not in query(raw, f"stat {path}"):
            problems.append(f"required package was removed: {path}")
    if query(raw, "ls -p /lib").count("liby2audio.so") != 1:
        problems.append("liby2audio.so must exist exactly once under /system/lib")
    for directory in ("/vendor/lib", "/lib64", "/vendor/lib64"):
        if "liby2audio.so" in query(raw, f"ls -p {directory}"):
            problems.append(f"conflicting runtime library found under /system{directory}")

    free_after = free_bytes(raw)
    log(f"      free space after: {free_after} bytes")
    log(f"      filesystem space recovered: {free_after - free_before} bytes")
    if problems:
        raise SystemExit("INTEGRATION FAILED:\n  - " + "\n  - ".join(problems))
    log("      all checks passed")

    log("\n[6/6] re-encoding sparse image")
    packed, chunks = sparse.pack(raw, args.out)
    log(f"      {args.out}: {packed} bytes, {chunks} chunks")
    log(f"      sha256: {sha256(args.out)}")
    if not args.keep_raw:
        os.unlink(raw)

    log("\nRESULT: system image ready to flash (ANDROID partition only).")

    if args.report:
        with open(args.report, "w") as handle:
            handle.write("\n".join(lines) + "\n")
        print(f"\nreport written to {args.report}")


if __name__ == "__main__":
    sys.exit(main())
