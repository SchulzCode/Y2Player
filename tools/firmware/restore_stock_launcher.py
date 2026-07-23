#!/usr/bin/env python3
"""
Roll back a Y2Player firmware image to the stock launcher.

This is the surgical rollback: it lifts MyLauncher.apk out of the untouched
stock system.img and puts it back into a modified image, removing Y2Player.
It is the mirror image of integrate_launcher.py and restores the original
mode, ownership and SELinux context.

    python3 restore_stock_launcher.py \
        --stock    OriginalFirmware/system.img \
        --modified dist/firmware/system.img \
        --out      dist/firmware/system_stock_launcher.img

If you only need the device working again, the simpler and safer option is to
flash the untouched OriginalFirmware/system.img directly — one partition, no
tooling. Use this script only when a modified image must be kept for other
reasons.
"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile

import sparse

STOCK_LAUNCHER_NAME = "MyLauncher.apk"
STOCK_LAUNCHER = f"/priv-app/{STOCK_LAUNCHER_NAME}"
TARGET_APK = "/priv-app/Y2Player.apk"
SELINUX_CONTEXT = b"u:object_r:system_file:s0\x00"


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True)


def debugfs(image, commands, write=False):
    with tempfile.NamedTemporaryFile("w", suffix=".debugfs", delete=False) as handle:
        handle.write("\n".join(commands) + "\n")
        script = handle.name
    try:
        cmd = ["debugfs"] + (["-w"] if write else []) + ["-f", script, image]
        return run(cmd)
    finally:
        os.unlink(script)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--stock", required=True, help="untouched stock system.img (donor)")
    parser.add_argument("--modified", required=True, help="modified system.img to roll back")
    parser.add_argument("--out", required=True, help="output sparse system.img")
    args = parser.parse_args()

    for tool in ("debugfs", "e2fsck"):
        if shutil.which(tool) is None:
            raise SystemExit(f"missing {tool} — install e2fsprogs")

    workdir = tempfile.mkdtemp(prefix="y2-restore-")
    try:
        print("[1/5] extracting stock launcher from donor image")
        stock_raw = os.path.join(workdir, "stock.raw")
        sparse.unpack(args.stock, stock_raw)
        launcher = os.path.join(workdir, STOCK_LAUNCHER_NAME)
        result = debugfs(stock_raw, [f"dump {STOCK_LAUNCHER} {launcher}"])
        if not os.path.isfile(launcher) or os.path.getsize(launcher) == 0:
            raise SystemExit(f"could not extract {STOCK_LAUNCHER}:\n{result.stdout}{result.stderr}")
        print(f"      {STOCK_LAUNCHER_NAME}: {os.path.getsize(launcher)} bytes, sha256 {sha256(launcher)}")

        print("[2/5] expanding image to roll back")
        target_raw = os.path.abspath(args.out) + ".raw"
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        sparse.unpack(args.modified, target_raw)

        print("[3/5] swapping Y2Player out for the stock launcher")
        context_file = os.path.join(workdir, "ctx.bin")
        with open(context_file, "wb") as handle:
            handle.write(SELINUX_CONTEXT)
        commands = []
        if "Inode" in debugfs(target_raw, [f"stat {TARGET_APK}"]).stdout:
            commands.append(f"rm {TARGET_APK}")
        commands += [
            "cd /priv-app",
            f"write {launcher} {STOCK_LAUNCHER_NAME}",
            f"sif {STOCK_LAUNCHER_NAME} mode 0100644",
            f"sif {STOCK_LAUNCHER_NAME} uid 0",
            f"sif {STOCK_LAUNCHER_NAME} gid 0",
            f"ea_set -f {context_file} {STOCK_LAUNCHER_NAME} security.selinux",
        ]
        swap = debugfs(target_raw, commands, write=True)
        if swap.returncode != 0:
            raise SystemExit(f"debugfs failed:\n{swap.stdout}{swap.stderr}")

        print("[4/5] reconciling filesystem")
        check = run(["e2fsck", "-fy", target_raw])
        if check.returncode >= 4:
            raise SystemExit(f"e2fsck reported uncorrected errors:\n{check.stdout}")

        verify = debugfs(target_raw, [f"stat {STOCK_LAUNCHER}"]).stdout
        if "Inode" not in verify:
            raise SystemExit("restore verification failed: stock launcher missing")
        if "Inode" in debugfs(target_raw, [f"stat {TARGET_APK}"]).stdout:
            raise SystemExit("restore verification failed: Y2Player.apk still present")

        print("[5/5] re-encoding sparse image")
        packed, chunks = sparse.pack(target_raw, args.out)
        os.unlink(target_raw)
        print(f"      {args.out}: {packed} bytes, {chunks} chunks")
        print(f"      sha256: {sha256(args.out)}")
        print("\nRESULT: stock launcher restored. Flash the ANDROID partition only.")
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
