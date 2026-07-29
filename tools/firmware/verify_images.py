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

APK_PATH = "/priv-app/Y2Player.apk"
NATIVE_LIBRARY_PATH = "/lib/liby2audio.so"
PRIMARY_AUDIO_HAL_PATH = "/lib/libaudio.primary.default.so"
PRIMARY_AUDIO_HAL_SIZE = 753072
PRIMARY_AUDIO_HAL_SHA256 = "c155e239c8d13bc83bc4016ebdcbd1724114d728df86beb4d42c112150ffe216"
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

        check(query(raw, "ls -p /lib").count("liby2audio.so") == 1,
              "liby2audio.so exists exactly once under /system/lib")
        for directory in ("/vendor/lib", "/lib64", "/vendor/lib64"):
            check("liby2audio.so" not in query(raw, "ls -p %s" % directory),
                  "no conflicting runtime library exists under /system%s" % directory)

        check("Inode" not in query(raw, "stat %s" % STOCK_LAUNCHER),
              "stock MyLauncher.apk is absent")
        check("Inode" not in query(raw, "stat %s" % STOCK_LAUNCHER_ODEX),
              "stale MyLauncher.odex is absent")
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
        log("All system-image content, metadata, hash, filesystem, and size checks passed.")
        log("Device boot and stock-UMS behavior still require hardware validation.")

    if args.report:
        os.makedirs(os.path.dirname(os.path.abspath(args.report)), exist_ok=True)
        with open(args.report, "w") as handle:
            handle.write("\n".join(lines) + "\n")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
