#!/usr/bin/env python3
"""Independently reopen and verify the produced Y2Player system.img."""
import argparse
import hashlib
import os
import re
import subprocess
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sparse
import system_package_policy as package_policy

APK_PATH = "/priv-app/Y2Player.apk"
NATIVE_LIBRARY_PATH = "/lib/liby2audio.so"
PRIMARY_AUDIO_HAL_PATH = "/lib/libaudio.primary.default.so"
PRIMARY_AUDIO_HAL_SIZE = 753072
PRIMARY_AUDIO_HAL_SHA256 = "c155e239c8d13bc83bc4016ebdcbd1724114d728df86beb4d42c112150ffe216"
KEY_LAYOUT_PATH = "/usr/keylayout/mtk-kpd.kl"
KEY_LAYOUT_SIZE = 1684
KEY_LAYOUT_SHA256 = "b85deb46f0ac9ebeb49f22a442ef442fc7adc612dbf075e52ddf178695969ec4"
BATTERY_WARNING_SUPPRESSION_MARKER = "/media/audio/ui/battery_y2player_suppressed"
BATTERY_WARNING_SUPPRESSION_SHA256 = "bfd10cd32fa8869b18e8bcc4f153e005dc0b290969186017fc8268e68d3d7c51"
SYSTEM_UI_PATH = "/priv-app/SystemUI.apk"
SYSTEM_UI_SIZE = 2122282
SYSTEM_UI_SHA256 = "e2a3e4676dafb6b2e6f551ea7fafd89862adbfd8c4b5ebf5162f1a661e7ea1c3"
KEY_LAYOUT_MAPPINGS = (
    b"key 115   MEDIA_FAST_FORWARD  WAKE_DROPPED",
    b"key 114   MEDIA_REWIND        WAKE_DROPPED",
)
APK_NATIVE_ENTRY = "lib/armeabi-v7a/liby2audio.so"
STOCK_LAUNCHER = "/priv-app/MyLauncher.apk"
STOCK_LAUNCHER_ODEX = "/priv-app/MyLauncher.odex"
# Absence checks intentionally retain these historical filenames so a stale
# privileged payload in a contaminated base/output image fails the build.
FORBIDDEN_PRIVILEGED_FILES = ("/bin/y2bridged", "/bin/y2powerd")


def sha256_file(path):
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
            return None, None, [info.filename for info in candidates]
        with archive.open(candidates[0]) as handle:
            return candidates[0].file_size, sha256_stream(handle), [APK_NATIVE_ENTRY]


def elf_description(path):
    with open(path, "rb") as handle:
        header = handle.read(52)
    if len(header) < 52 or header[:4] != b"\x7fELF":
        return None
    elf_class = header[4]
    byte_order = header[5]
    if byte_order != 1:
        return None
    machine = int.from_bytes(header[18:20], "little")
    flags = int.from_bytes(header[36:40], "little")
    eabi = (flags >> 24) & 0xFF
    return {
        "class": elf_class,
        "byte_order": byte_order,
        "machine": machine,
        "eabi": eabi,
        "flags": flags,
        "description": (
            f"ELF{32 if elf_class == 1 else '?'} little-endian "
            f"{'ARM' if machine == 40 else 'machine-' + str(machine)} "
            f"EABI{eabi} (e_flags=0x{flags:08x})"
        ),
    }


def query(image, command):
    return subprocess.run(
        ["debugfs", "-R", command, image], capture_output=True, text=True
    ).stdout


def dump(image, source, destination):
    result = subprocess.run(
        ["debugfs", "-R", "dump %s %s" % (source, destination), image],
        capture_output=True, text=True,
    )
    return result.returncode == 0 and os.path.isfile(destination)


def stat_size(output):
    match = re.search(r"\bSize:\s*(\d+)", output)
    return int(match.group(1)) if match else None


def zeroed_free_block_summary(image):
    """Independently verify that every block marked free by ext4 is zero."""
    header = subprocess.run(
        ["dumpe2fs", "-h", image], capture_output=True, text=True
    )
    report = subprocess.run(
        ["dumpe2fs", image], capture_output=True, text=True
    )
    if header.returncode != 0 or report.returncode != 0:
        return False, "dumpe2fs could not enumerate the free-block map"

    values = {}
    for line in header.stdout.splitlines():
        match = re.match(r"^(Block count|Free blocks|Block size):\s*(\d+)$", line)
        if match:
            values[match.group(1)] = int(match.group(2))
    if set(values) != {"Block count", "Free blocks", "Block size"}:
        return False, "dumpe2fs header is missing block-count information"

    ranges = []
    for line in report.stdout.splitlines():
        match = re.match(r"^\s+Free blocks:\s*(.*)$", line)
        if not match:
            continue
        value = match.group(1).strip()
        if not value or value == "(none)":
            continue
        for token in value.split(","):
            bounds = token.strip().split("-", 1)
            start = int(bounds[0])
            end = int(bounds[-1])
            if start > end or start < 0 or end >= values["Block count"]:
                return False, "invalid ext4 free-block range: %s" % token.strip()
            ranges.append((start, end))

    ranges.sort()
    for previous, current in zip(ranges, ranges[1:]):
        if current[0] <= previous[1]:
            return False, "overlapping ext4 free-block ranges"
    block_count = sum(end - start + 1 for start, end in ranges)
    if block_count != values["Free blocks"]:
        return False, (
            "free-block map count %d differs from superblock %d"
            % (block_count, values["Free blocks"])
        )

    block_size = values["Block size"]
    with open(image, "rb") as handle:
        for start, end in ranges:
            handle.seek(start * block_size)
            remaining = (end - start + 1) * block_size
            while remaining:
                piece = handle.read(min(remaining, 8 << 20))
                if not piece or piece != b"\x00" * len(piece):
                    return False, "non-zero data remains in a free ext4 block"
                remaining -= len(piece)
    return True, "%d blocks / %d bytes" % (block_count, block_count * block_size)


def scatter_sizes(path):
    sizes = {}
    current = None
    with open(path, "r", errors="replace") as handle:
        for line in handle:
            name = re.match(r"\s*partition_name:\s*(\S+)", line)
            if name:
                current = name.group(1)
                continue
            size = re.match(r"\s*partition_size:\s*(0x[0-9a-fA-F]+|\d+)", line)
            if size and current:
                sizes[current] = int(size.group(1), 0)
                current = None
    return sizes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--system", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--native-lib", required=True)
    parser.add_argument("--scatter", required=True)
    parser.add_argument("--report")
    args = parser.parse_args()

    lines = []
    problems = []

    def log(message):
        print(message)
        lines.append(message)

    def check(condition, description):
        log("      %s %s" % ("ok  " if condition else "FAIL", description))
        if not condition:
            problems.append(description)

    log("=== Independent system.img verification ===")
    source_size = os.path.getsize(args.native_lib)
    source_sha = sha256_file(args.native_lib)
    source_elf = elf_description(args.native_lib)
    check(source_elf is not None, "compiled native library is ELF")
    check(
        source_elf is not None
        and source_elf["class"] == 1
        and source_elf["byte_order"] == 1
        and source_elf["machine"] == 40
        and source_elf["eabi"] == 5,
        "compiled native library ABI is ELF32 little-endian ARM EABI5",
    )
    if source_elf:
        log("      native ELF : %s" % source_elf["description"])
    log("      native size: %d bytes" % source_size)
    log("      native sha : %s" % source_sha)
    apk_native_size, apk_native_sha, apk_native_entries = apk_native_library(args.apk)
    check(apk_native_entries == [APK_NATIVE_ENTRY],
          "APK contains exactly %s" % APK_NATIVE_ENTRY)
    check((apk_native_size, apk_native_sha) == (source_size, source_sha),
          "APK native library size and SHA-256 match compiled artifact")
    partitions = scatter_sizes(args.scatter)
    check("ANDROID" in partitions, "scatter declares the ANDROID partition size")
    log("      file    : %s (%d bytes)" % (args.system, os.path.getsize(args.system)))
    log("      sha256  : %s" % sha256_file(args.system))

    with tempfile.TemporaryDirectory() as work:
        raw = os.path.join(work, "system.raw")
        written, block_size, blocks = sparse.unpack(args.system, raw)
        log("      expands : %d bytes (%d x %d); sparse format valid" %
            (written, blocks, block_size))
        if "ANDROID" in partitions:
            check(written == partitions["ANDROID"],
                  "ext4 size matches ANDROID partition (%d bytes)" % partitions["ANDROID"])

        fsck = subprocess.run(["e2fsck", "-fn", raw], capture_output=True, text=True)
        check(fsck.returncode < 4, "ext4 structure is sound (e2fsck -fn exit %d)" % fsck.returncode)
        free_zeroed, free_summary = zeroed_free_block_summary(raw)
        check(free_zeroed, "all ext4 free blocks are zero (%s)" % free_summary)

        apk_stat = query(raw, "stat %s" % APK_PATH)
        check("Inode" in apk_stat, "%s exists" % APK_PATH)
        expected_size = os.path.getsize(args.apk)
        check(stat_size(apk_stat) == expected_size,
              "embedded APK size matches output APK")
        embedded = os.path.join(work, "Y2Player.apk")
        check(dump(raw, APK_PATH, embedded), "embedded APK can be read back")
        if os.path.isfile(embedded):
            check(sha256_file(embedded) == sha256_file(args.apk),
                  "embedded APK SHA-256 matches output APK")
        mode = next((line for line in apk_stat.splitlines() if "Mode:" in line), "")
        check("0644" in mode, "APK mode is 0644")
        check("User:     0" in apk_stat and "Group:     0" in apk_stat,
              "APK ownership is root:root")
        check("system_file" in query(raw, "ea_get %s security.selinux" % APK_PATH),
              "APK SELinux context is system_file")

        native_stat = query(raw, "stat %s" % NATIVE_LIBRARY_PATH)
        check("Inode" in native_stat, "%s exists" % NATIVE_LIBRARY_PATH)
        check(stat_size(native_stat) == source_size,
              "runtime library size matches compiled artifact (%d bytes)" % source_size)
        runtime = os.path.join(work, "liby2audio.so")
        check(dump(raw, NATIVE_LIBRARY_PATH, runtime), "runtime library can be read back")
        if os.path.isfile(runtime):
            runtime_sha = sha256_file(runtime)
            check(runtime_sha == source_sha,
                  "runtime library SHA-256 matches compiled and APK copies")
            runtime_elf = elf_description(runtime)
            check(runtime_elf is not None and runtime_elf["class"] == 1
                  and runtime_elf["byte_order"] == 1
                  and runtime_elf["machine"] == 40
                  and runtime_elf["eabi"] == 5,
                  "runtime library ABI is ELF32 little-endian ARM EABI5")
            log("      runtime sha: %s" % runtime_sha)
            if runtime_elf:
                log("      runtime ELF: %s" % runtime_elf["description"])
        native_mode = next((line for line in native_stat.splitlines() if "Mode:" in line), "")
        check("0644" in native_mode, "runtime library mode is 0644")
        check("User:     0" in native_stat and "Group:     0" in native_stat,
              "runtime library ownership is root:root")
        check("system_file" in query(
            raw, "ea_get %s security.selinux" % NATIVE_LIBRARY_PATH
        ), "runtime library SELinux context is system_file")

        hal_stat = query(raw, "stat %s" % PRIMARY_AUDIO_HAL_PATH)
        check("Inode" in hal_stat, "%s exists" % PRIMARY_AUDIO_HAL_PATH)
        check(stat_size(hal_stat) == PRIMARY_AUDIO_HAL_SIZE,
              "patched primary-audio HAL size is %d bytes" % PRIMARY_AUDIO_HAL_SIZE)
        hal_mode = next((line for line in hal_stat.splitlines() if "Mode:" in line), "")
        check("0644" in hal_mode, "patched primary-audio HAL mode is 0644")
        check("User:     0" in hal_stat and "Group:     0" in hal_stat,
              "patched primary-audio HAL ownership is root:root")
        check("system_file" in query(
            raw, "ea_get %s security.selinux" % PRIMARY_AUDIO_HAL_PATH
        ), "patched primary-audio HAL SELinux context is system_file")
        embedded_hal = os.path.join(work, "libaudio.primary.default.so")
        check(dump(raw, PRIMARY_AUDIO_HAL_PATH, embedded_hal),
              "patched primary-audio HAL can be read back")
        if os.path.isfile(embedded_hal):
            embedded_hal_sha = sha256_file(embedded_hal)
            check(embedded_hal_sha == PRIMARY_AUDIO_HAL_SHA256,
                  "patched primary-audio HAL SHA-256 matches the audited patch")
            embedded_hal_elf = elf_description(embedded_hal)
            check(embedded_hal_elf is not None
                  and embedded_hal_elf["class"] == 1
                  and embedded_hal_elf["byte_order"] == 1
                  and embedded_hal_elf["machine"] == 40
                  and embedded_hal_elf["eabi"] == 5,
                  "patched primary-audio HAL ABI is ELF32 little-endian ARM EABI5")
            log("      HAL sha    : %s" % embedded_hal_sha)
            if embedded_hal_elf:
                log("      HAL ELF    : %s" % embedded_hal_elf["description"])

        keylayout_stat = query(raw, "stat %s" % KEY_LAYOUT_PATH)
        check("Inode" in keylayout_stat, "%s exists" % KEY_LAYOUT_PATH)
        check(stat_size(keylayout_stat) == KEY_LAYOUT_SIZE,
              "patched keypad layout size is %d bytes" % KEY_LAYOUT_SIZE)
        keylayout_mode = next(
            (line for line in keylayout_stat.splitlines() if "Mode:" in line), ""
        )
        check("0644" in keylayout_mode, "patched keypad layout mode is 0644")
        check("User:     0" in keylayout_stat and "Group:     0" in keylayout_stat,
              "patched keypad layout ownership is root:root")
        check("system_file" in query(
            raw, "ea_get %s security.selinux" % KEY_LAYOUT_PATH
        ), "patched keypad layout SELinux context is system_file")
        embedded_keylayout = os.path.join(work, "mtk-kpd.kl")
        check(dump(raw, KEY_LAYOUT_PATH, embedded_keylayout),
              "patched keypad layout can be read back")
        if os.path.isfile(embedded_keylayout):
            with open(embedded_keylayout, "rb") as handle:
                keylayout_data = handle.read()
            check(sha256_file(embedded_keylayout) == KEY_LAYOUT_SHA256,
                  "patched keypad layout SHA-256 matches the audited patch")
            check(all(keylayout_data.count(mapping) == 1 for mapping in KEY_LAYOUT_MAPPINGS),
                  "physical volume scan codes map to app-owned media surrogates")
            check(b"key 115   VOLUME_UP" not in keylayout_data
                  and b"key 114   VOLUME_DOWN" not in keylayout_data,
                  "framework volume mappings are absent for physical scan codes 115/114")
            log("      keypad sha : %s" % sha256_file(embedded_keylayout))

        marker_stat = query(raw, "stat %s" % BATTERY_WARNING_SUPPRESSION_MARKER)
        check("Inode" in marker_stat,
              "%s exists" % BATTERY_WARNING_SUPPRESSION_MARKER)
        check(stat_size(marker_stat) == 2,
              "battery-warning suppression marker is 2 bytes")
        embedded_marker = os.path.join(work, "battery_y2player_suppressed")
        check(dump(raw, BATTERY_WARNING_SUPPRESSION_MARKER, embedded_marker),
              "battery-warning suppression marker can be read back")
        if os.path.isfile(embedded_marker):
            check(sha256_file(embedded_marker) == BATTERY_WARNING_SUPPRESSION_SHA256,
                  "battery-warning suppression marker matches the audited payload")

        system_ui_stat = query(raw, "stat %s" % SYSTEM_UI_PATH)
        check(stat_size(system_ui_stat) == SYSTEM_UI_SIZE,
              "stock SystemUI size remains unchanged")
        embedded_system_ui = os.path.join(work, "SystemUI.apk")
        check(dump(raw, SYSTEM_UI_PATH, embedded_system_ui),
              "stock SystemUI can be read back")
        if os.path.isfile(embedded_system_ui):
            check(sha256_file(embedded_system_ui) == SYSTEM_UI_SHA256,
                  "stock SystemUI matches the audited battery-warning implementation")

        check(query(raw, "ls -p /lib").count("liby2audio.so") == 1,
              "liby2audio.so exists exactly once under /system/lib")
        for directory in ("/vendor/lib", "/lib64", "/vendor/lib64"):
            check("liby2audio.so" not in query(raw, "ls -p %s" % directory),
                  "no conflicting runtime library exists under /system%s" % directory)

        check("Inode" not in query(raw, "stat %s" % STOCK_LAUNCHER),
              "stock MyLauncher.apk is absent")
        check("Inode" not in query(raw, "stat %s" % STOCK_LAUNCHER_ODEX),
              "stale MyLauncher.odex is absent")
        for directory, stem in package_policy.pruned_packages():
            apk_path, odex_path = package_policy.package_files(directory, stem)
            check(
                "Inode" not in query(raw, "stat %s" % apk_path)
                and "Inode" not in query(raw, "stat %s" % odex_path),
                "optional package %s and its ODEX are absent" % stem,
            )
        check(
            all(
                "Inode" not in query(raw, "stat %s" % path)
                for path in package_policy.PRUNED_SUPPORT_FILES
            ),
            "private VideoEditor support libraries are absent",
        )
        for path in package_policy.REQUIRED_APKS:
            check("Inode" in query(raw, "stat %s" % path),
                  "required package %s is retained" % path)
        listing = query(raw, "ls -p /priv-app")
        check(listing.count("Y2Player.apk") == 1,
              "Y2Player.apk exists exactly once under /system/priv-app")
        check("Y2Player" not in query(raw, "ls -p /app"),
              "no duplicate Y2Player exists under /system/app")
        for path in FORBIDDEN_PRIVILEGED_FILES:
            check("Inode" not in query(raw, "stat %s" % path),
                  "%s is absent" % path)

    log("\n=== Result ===")
    if problems:
        log("VERIFICATION FAILED (%d problem(s))" % len(problems))
        for problem in problems:
            log("  - %s" % problem)
    else:
        log(
            "All system-image content, package-policy, metadata, hash, "
            "filesystem, and size checks passed."
        )
        log("Device boot and stock-UMS behavior still require hardware validation.")

    if args.report:
        os.makedirs(os.path.dirname(os.path.abspath(args.report)), exist_ok=True)
        with open(args.report, "w") as handle:
            handle.write("\n".join(lines) + "\n")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
