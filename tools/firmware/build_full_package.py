#!/usr/bin/env python3
"""Build and independently verify the Linux-only Innioasis Updater package."""

from __future__ import annotations

import argparse
import hashlib
import re
import shutil
import struct
import subprocess
import urllib.request
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STOCK = ROOT / "OriginalFirmware"
SCATTER_NAME = "MT6582_Android_scatter.txt"
TEMPLATE_URL = "https://github.com/y1-community/rockbox-y2-rom/releases/download/0.5/rom_y2.zip"
TEMPLATE_SHA256 = "092a5f39e2db5fb8be7b8c442cd713e0db567fd69f589aace2243ab8e7eedeb7"
TEMPLATE_SIZE = 433_341_038
TEMPLATE_CACHE = ROOT / "build/downloads/innioasis-updater/rockbox-y2-0.5-rom_y2.zip"

PARTITIONS = (
    ("PRELOADER", "preloader_eastaeon82_wet_kk.bin", False),
    ("MBR", "MBR", False), ("EBR1", "EBR1", False),
    ("UBOOT", "lk.bin", False), ("BOOTIMG", "boot.img", False),
    ("RECOVERY", "recovery.img", False), ("SEC_RO", "secro.img", False),
    ("LOGO", "logo.bin", False), ("EBR2", "EBR2", False),
    ("ANDROID", "system.img", True), ("CACHE", "cache.img", True),
    ("USRDATA", "userdata.img", True),
)
FLASH_FILES = (
    "BromAdapterTool.ini", "console_mode.xsd", "console_readback.xml", "CustPT.ini",
    "DA_PL.bin", "DA_PL_CRYPTO20.bin", "DA_SWSEC.bin", "DA_SWSEC_CRYPTO20.bin",
    "dl_without_scatter.xml", "download_scene.ini", "factory.ini", "flash_tool.exe",
    "flashtool.qch", "flashtool.qhc", "FlashToolLib.dll", "FlashToolLib.v1.dll",
    "FlashtoollibEx.dll", "hwparam.json", "key.ini", "msvcp90.dll", "msvcr90.dll",
    "MTK_AllInOne_DA.bin", "option.ini", "phonon4.dll", "platform.xml",
    "QtCLucene4.dll", "QtCore4.dll", "QtGui4.dll", "QtHelp4.dll", "QtNetwork4.dll",
    "QtSql4.dll", "QtWebKit4.dll", "QtXml4.dll", "QtXmlPatterns4.dll",
    "rb_without_scatter.xml", "readback.log", "readback.xml", "readback_ui_bak.xsd",
    "registry.ini", "SLA_Challenge.dll", "sp_readback.xml", "storage_setting.xml",
    "usb_setting.xml",
)


@dataclass(frozen=True)
class ScatterEntry:
    filename: str
    downloadable: bool
    size: int


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(4 * 1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def image_info(path: Path) -> tuple[bool, int, int]:
    stored = path.stat().st_size
    with path.open("rb") as handle:
        header = handle.read(28)
    if len(header) >= 28 and struct.unpack_from("<I", header)[0] == 0xED26FF3A:
        block_size, blocks = struct.unpack_from("<II", header, 12)
        return True, stored, block_size * blocks
    return False, stored, stored


def scatter_entries(path: Path) -> dict[str, ScatterEntry]:
    text = path.read_text(encoding="utf-8")
    if not re.search(r"^\s*platform:\s*MT6582\s*$", text, re.MULTILINE):
        raise SystemExit(f"scatter is not for MT6582: {path}")
    if not re.search(r"^\s*project:\s*eastaeon82_wet_kk\s*$", text, re.MULTILINE):
        raise SystemExit(f"scatter is not for eastaeon82_wet_kk: {path}")
    result: dict[str, ScatterEntry] = {}
    blocks = re.findall(r"^- partition_index:.*?(?=^- partition_index:|\Z)", text, re.M | re.S)
    for block in blocks:
        def field(name: str) -> str:
            match = re.search(rf"^\s*{name}:\s*(\S+)\s*$", block, re.MULTILINE)
            if not match:
                raise SystemExit(f"cannot parse {name} in scatter partition block")
            return match.group(1)
        result[field("partition_name")] = ScatterEntry(
            field("file_name"), field("is_download") == "true", int(field("partition_size"), 16)
        )
    return result


def validate_inputs(system_image: Path) -> dict[str, ScatterEntry]:
    scatter_path = STOCK / SCATTER_NAME
    if not scatter_path.is_file() or not system_image.is_file():
        raise SystemExit("stock scatter or requested system image is missing")
    scatter = scatter_entries(scatter_path)
    expected = {name for name, _, _ in PARTITIONS}
    actual = {name for name, entry in scatter.items() if entry.downloadable}
    if actual != expected:
        raise SystemExit("scatter downloadable partitions differ from the guarded Y2 layout")
    for name, filename, filesystem in PARTITIONS:
        entry = scatter.get(name)
        if entry is None or entry.filename != filename or not entry.downloadable:
            raise SystemExit(f"unexpected scatter mapping for {name}")
        source = system_image if name == "ANDROID" else STOCK / filename
        if not source.is_file():
            raise SystemExit(f"required partition image is missing: {source}")
        _, stored, expanded = image_info(source)
        size = expanded if filesystem else stored
        if size > entry.size:
            raise SystemExit(f"{filename} exceeds its declared partition")
    for filename in ("boot.img", "recovery.img"):
        if (STOCK / filename).read_bytes()[:8] != b"ANDROID!":
            raise SystemExit(f"invalid Android boot image: {filename}")
    return scatter


def template(path_arg: str | None) -> Path:
    path = Path(path_arg).resolve() if path_arg else TEMPLATE_CACHE
    if not path.exists() and not path_arg:
        path.parent.mkdir(parents=True, exist_ok=True)
        partial = path.with_suffix(path.suffix + ".partial")
        print(f"Downloading pinned updater template ({TEMPLATE_SIZE / 1024 / 1024:.0f} MiB)...")
        request = urllib.request.Request(TEMPLATE_URL, headers={"User-Agent": "Y2Player-ROM-Builder"})
        with urllib.request.urlopen(request) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output)
        partial.replace(path)
    if not path.is_file() or path.stat().st_size != TEMPLATE_SIZE or digest(path) != TEMPLATE_SHA256:
        raise SystemExit("updater template size or SHA-256 does not match the pinned release")
    return path


def copy_verified(source: Path, destination: Path) -> str:
    source_hash = digest(source)
    shutil.copyfile(source, destination)
    if digest(destination) != source_hash:
        raise SystemExit(f"copy verification failed: {source.name}")
    return source_hash


def expand_image(source: Path, destination: Path) -> None:
    sparse, _, expanded = image_info(source)
    if sparse:
        subprocess.run(
            ["python3", str(ROOT / "tools/firmware/sparse.py"), "unpack", str(source), str(destination)],
            check=True,
        )
    else:
        shutil.copyfile(source, destination)
    out_sparse, out_stored, _ = image_info(destination)
    if out_sparse or out_stored != expanded:
        raise SystemExit(f"desparsed image has the wrong format or size: {destination.name}")


def build(args: argparse.Namespace) -> None:
    system_image = Path(args.system_image).resolve()
    scatter = validate_inputs(system_image)
    if args.validate_only:
        print("Full-package inputs are valid. No artifacts were produced.")
        return
    source_firmware = Path(args.system_artifacts).resolve()
    for filename in ("system.img", "Y2Player.apk", "build-manifest.txt", "verification-report.txt"):
        if not (source_firmware / filename).is_file():
            raise SystemExit(f"system-image build did not produce {filename}")
    pinned_template = template(args.template)
    output = Path(args.output).resolve()
    staging = ROOT / "build/work/updater-rom-linux"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    output.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(pinned_template) as archive:
        names = {entry.filename: entry for entry in archive.infolist()}
        for name in FLASH_FILES:
            entry = names.get(name)
            if entry is None or (entry.file_size == 0 and name != "readback.log"):
                raise SystemExit(f"pinned updater template is missing required file: {name}")
            (staging / name).write_bytes(archive.read(entry))

    copied: list[str] = []
    for name, filename, filesystem in PARTITIONS:
        if not filesystem:
            copied.append(f"{copy_verified(STOCK / filename, staging / filename)}  {filename}")
    copied.append(f"{copy_verified(STOCK / SCATTER_NAME, staging / SCATTER_NAME)}  {SCATTER_NAME}")
    (staging / "history.ini").write_bytes(
        ("[LastDAFilePath]\r\nlastDir=MTK_AllInOne_DA.bin\r\n\r\n[RecentOpenFile]\r\n"
         f"lastDir=\r\nscatterHistory={SCATTER_NAME}\r\nauthHistory=\r\n").encode("ascii")
    )
    for name, filename, filesystem in PARTITIONS:
        if filesystem:
            expand_image(system_image if name == "ANDROID" else STOCK / filename, staging / filename)

    expected = {filename for _, filename, _ in PARTITIONS} | set(FLASH_FILES) | {SCATTER_NAME, "history.ini"}
    if {path.name for path in staging.iterdir()} != expected or any(not path.is_file() for path in staging.iterdir()):
        raise SystemExit("staging contents differ from the guarded release file list")
    image_hashes: list[str] = []
    for name, filename, filesystem in PARTITIONS:
        path = staging / filename
        sparse, stored, _ = image_info(path)
        if (filesystem and sparse) or stored > scatter[name].size:
            raise SystemExit(f"staged image is invalid or too large: {filename}")
        image_hashes.append(f"{digest(path)}  {filename}")

    manifest = [
        "Y2Player Innioasis Updater full-ROM build", "=" * 44, "",
        f"Build date (UTC)     : {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S')}",
        "Target              : Innioasis Y2 / MT6582 / eastaeon82_wet_kk",
        "Archive name        : rom_y2.zip", "Archive layout      : flat/root-level files",
        "Compression         : Deflate level 9, Zip64 enabled",
        "Installation effect : full-device flash; user data is erased",
        f"Template source     : {TEMPLATE_URL}", f"Template SHA-256    : {TEMPLATE_SHA256}", "",
        "Packaged image SHA-256", "-------------------------", *image_hashes, "",
        "Byte-verified stock copies", "--------------------------", *copied, "",
        "Embedded system-image build manifest", "------------------------------------",
        *(source_firmware / "build-manifest.txt").read_text(encoding="utf-8").splitlines(), "",
    ]
    (output / "build-manifest.txt").write_text("\n".join(manifest), encoding="utf-8")

    archive_path = output / "rom_y2.zip"
    partial = output / "rom_y2.zip.partial"
    with zipfile.ZipFile(partial, "w", zipfile.ZIP_DEFLATED, compresslevel=9, allowZip64=True) as archive:
        for name in sorted(expected, key=str.casefold):
            print(f"adding {name}")
            archive.write(staging / name, name, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    partial.replace(archive_path)
    with zipfile.ZipFile(archive_path) as archive:
        entries = archive.infolist()
        if len({entry.filename for entry in entries}) != len(entries) or {entry.filename for entry in entries} != expected:
            raise SystemExit("finished archive layout failed verification")
        for entry in entries:
            if Path(entry.filename).name != entry.filename or len(archive.read(entry)) != (staging / entry.filename).stat().st_size:
                raise SystemExit(f"archive readback failed: {entry.filename}")

    report = [
        "Y2Player full-ROM verification report", "=" * 38,
        "PASS: archive name and flat guarded layout verified",
        "PASS: every entry decompressed and read back",
        "PASS: updater support files came from the pinned template",
        "PASS: stock partitions are byte-identical to OriginalFirmware",
        "PASS: filesystem images are raw and fit their scatter allocations",
        "PASS: system.img came from the fresh Linux system-image pipeline", "",
        "WARNING: Installing this full-ROM package erases user data.", "",
    ]
    (output / "verification-report.txt").write_text("\n".join(report), encoding="utf-8")
    checks = ("rom_y2.zip", "build-manifest.txt", "verification-report.txt")
    (output / "checksums.txt").write_text(
        "".join(f"{digest(output / name)}  {name}\n" for name in checks), encoding="ascii"
    )
    if not args.keep_staging:
        shutil.rmtree(staging)
    print(f"Full updater package: {archive_path}")
    print("WARNING: installing this package erases user data.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--system-image", default=str(ROOT / "out/firmware/system.img"))
    parser.add_argument("--system-artifacts", default=str(ROOT / "out/firmware"))
    parser.add_argument("--output", default=str(ROOT / "out/updater"))
    parser.add_argument("--template")
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--keep-staging", action="store_true")
    build(parser.parse_args())


if __name__ == "__main__":
    main()
