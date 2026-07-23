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
        --out      dist/firmware/system.img \
        [--report  dist/firmware/integration-report.txt]

Layout note: Android 4.4 scans /system/priv-app for APK files *directly*. The
per-package subdirectory layout (priv-app/Name/Name.apk) is Android 5.0+ and
would be silently ignored here, leaving the device with no launcher.

"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile

import sparse

STOCK_LAUNCHER = "/priv-app/MyLauncher.apk"
STOCK_LAUNCHER_ODEX = "/priv-app/MyLauncher.odex"
TARGET_APK = "/priv-app/Y2Player.apk"
# Matches the context carried by every other file in /system/priv-app.
SELINUX_CONTEXT = b"u:object_r:system_file:s0\x00"
APK_MODE = "0100644"  # regular file, rw-r--r--

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


def free_bytes(image):
    out = run(["dumpe2fs", "-h", image]).stdout
    free = block = 0
    for line in out.splitlines():
        if line.startswith("Free blocks:"):
            free = int(line.split(":")[1])
        elif line.startswith("Block size:"):
            block = int(line.split(":")[1])
    return free * block


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--system", required=True, help="stock sparse system.img (never modified)")
    parser.add_argument("--apk", required=True, help="signed release Y2Player APK")
    parser.add_argument("--out", required=True, help="output sparse system.img")
    parser.add_argument("--report", help="write an integration report here")
    parser.add_argument("--keep-raw", action="store_true", help="keep the intermediate raw image")
    args = parser.parse_args()

    require_tools()
    required = [args.system, args.apk]
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

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    raw = os.path.abspath(args.out) + ".raw"

    log("\n[1/6] expanding sparse image")
    written, block_size, blocks = sparse.unpack(args.system, raw)
    log(f"      {written} bytes ({blocks} blocks x {block_size})")

    log("\n[2/6] verifying stock contents")
    stock_stat = query(raw, f"stat {STOCK_LAUNCHER}")
    if "Inode" not in stock_stat:
        raise SystemExit(f"{STOCK_LAUNCHER} not found — is this the stock Y2 system image?")
    log(f"      found {STOCK_LAUNCHER}")
    log(f"      free space before: {free_bytes(raw)} bytes")

    log("\n[3/6] removing stock launcher and installing Y2Player")
    context_file = raw + ".selinux"
    with open(context_file, "wb") as handle:
        handle.write(SELINUX_CONTEXT)
    commands = [
        f"rm {STOCK_LAUNCHER}",
        "cd /priv-app",
        f"write {os.path.abspath(args.apk)} Y2Player.apk",
        f"sif Y2Player.apk mode {APK_MODE}",
        "sif Y2Player.apk uid 0",
        "sif Y2Player.apk gid 0",
        f"ea_set -f {context_file} Y2Player.apk security.selinux",
    ]
    if "Inode" in query(raw, f"stat {STOCK_LAUNCHER_ODEX}"):
        commands.insert(1, f"rm {STOCK_LAUNCHER_ODEX}")
        log(f"      removing stale {STOCK_LAUNCHER_ODEX}")
    result = debugfs(raw, commands, write=True)
    if result.returncode != 0:
        os.unlink(context_file)
        raise SystemExit(f"debugfs failed:\n{result.stdout}\n{result.stderr}")
    for noise in ("File not found", "Invalid", "error"):
        if noise.lower() in result.stdout.lower():
            log(f"      debugfs output: {result.stdout.strip()}")

    os.unlink(context_file)

    log("\n[4/6] reconciling filesystem (e2fsck)")
    check = run(["e2fsck", "-fy", raw])
    # 0 = clean, 1 = errors corrected. Anything higher is uncorrected damage.
    if check.returncode >= 4:
        raise SystemExit(f"e2fsck reported uncorrected errors ({check.returncode}):\n{check.stdout}")
    log(f"      e2fsck exit {check.returncode} ({'clean' if check.returncode == 0 else 'corrected'})")

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
    # Nothing else in priv-app may have changed.
    listing = query(raw, "ls /priv-app")
    if "MyLauncher" in listing:
        problems.append("MyLauncher still referenced in /priv-app")
    if "Y2Player.apk" not in listing:
        problems.append("Y2Player.apk not listed in /priv-app")
    # A second copy under /system/app would install the package twice.
    if "Y2Player" in query(raw, "ls /app"):
        problems.append("a duplicate Y2Player is present in /system/app")

    log(f"      free space after: {free_bytes(raw)} bytes")
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
