#!/usr/bin/env python3
"""Independently reopen and verify the produced Y2Player system.img."""
import argparse
import hashlib
import os
import re
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sparse

APK_PATH = "/priv-app/Y2Player.apk"
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
