#!/usr/bin/env python3
"""Build and verify a legacy MediaTek boot image with secure ADB enabled.

The Y2 boot image uses Android boot-image header v0. Its ramdisk is wrapped in
a 512-byte MediaTek ROOTFS header, followed by gzip-compressed newc cpio. This
tool edits the archive in memory so modes, owners, symlinks, and ordering do not
depend on the host filesystem.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import os
import shutil
import stat
import struct
from dataclasses import dataclass
from pathlib import Path


BOOT_MAGIC = b"ANDROID!"
BOOT_HEADER_FORMAT = "<8s10I16s512s32s"
BOOT_HEADER_SIZE = struct.calcsize(BOOT_HEADER_FORMAT)
MTK_HEADER_SIZE = 512
MTK_MAGIC = 0x58881688
MTK_NAME = b"ROOTFS"
CPIO_HEADER_SIZE = 110
CPIO_TRAILER = "TRAILER!!!"
USB_CONFIG = b"persist.sys.usb.config=mass_storage,adb"
# AudioFMController::SetFmEnable picks the FM audio path from this property.
# Left at its default of 0 the HAL selects direct-connection mode whenever the
# output is headset or headphone, and that path produces no sound on the Y2.
# 2 is FM_FORCE_INDIRECT_MODE, the mixed path the speaker already uses. The
# af. prefix is not writable by shell or by an app, so it has to be set here.
FM_AUDIO_PROP = b"af.fm.force_direct_mode_type=2"
Y2_INIT_MARKER = b"# Y2Player secure ADB + mass-storage composition"
Y2_INIT_ACTION = (
    b"\n"
    + Y2_INIT_MARKER
    + b"\n"
    + b"on boot\n"
    + b"    setprop persist.sys.usb.config mass_storage,adb\n"
    + b"    setprop sys.usb.config mass_storage,adb\n"
)


def align(value: int, boundary: int) -> int:
    return (value + boundary - 1) // boundary * boundary


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


@dataclass
class CpioEntry:
    magic: bytes
    ino: int
    mode: int
    uid: int
    gid: int
    nlink: int
    mtime: int
    devmajor: int
    devminor: int
    rdevmajor: int
    rdevminor: int
    check: int
    name: str
    data: bytes


def parse_newc(blob: bytes) -> list[CpioEntry]:
    entries: list[CpioEntry] = []
    offset = 0
    while offset < len(blob):
        if blob[offset:] and set(blob[offset:]) == {0}:
            break
        header = blob[offset : offset + CPIO_HEADER_SIZE]
        if len(header) != CPIO_HEADER_SIZE or header[:6] not in (b"070701", b"070702"):
            raise ValueError(f"invalid newc header at offset {offset}")
        values = [int(header[6 + index * 8 : 14 + index * 8], 16) for index in range(13)]
        (
            ino,
            mode,
            uid,
            gid,
            nlink,
            mtime,
            filesize,
            devmajor,
            devminor,
            rdevmajor,
            rdevminor,
            namesize,
            check,
        ) = values
        name_start = offset + CPIO_HEADER_SIZE
        name_end = name_start + namesize
        raw_name = blob[name_start:name_end]
        if len(raw_name) != namesize or not raw_name.endswith(b"\0"):
            raise ValueError(f"invalid newc pathname at offset {offset}")
        name = raw_name[:-1].decode("utf-8", "surrogateescape")
        data_start = align(name_end, 4)
        data_end = data_start + filesize
        data = blob[data_start:data_end]
        if len(data) != filesize:
            raise ValueError(f"truncated newc data for {name}")
        entries.append(
            CpioEntry(
                header[:6], ino, mode, uid, gid, nlink, mtime,
                devmajor, devminor, rdevmajor, rdevminor, check, name, data
            )
        )
        offset = align(data_end, 4)
        if name == CPIO_TRAILER:
            if any(blob[offset:]):
                raise ValueError("non-zero data follows newc trailer")
            break
    if not entries or entries[-1].name != CPIO_TRAILER:
        raise ValueError("newc trailer is missing")
    return entries


def serialize_newc(entries: list[CpioEntry]) -> bytes:
    output = bytearray()
    for entry in entries:
        name = entry.name.encode("utf-8", "surrogateescape") + b"\0"
        fields = (
            entry.ino,
            entry.mode,
            entry.uid,
            entry.gid,
            entry.nlink,
            entry.mtime,
            len(entry.data),
            entry.devmajor,
            entry.devminor,
            entry.rdevmajor,
            entry.rdevminor,
            len(name),
            entry.check,
        )
        output.extend(entry.magic + b"".join(f"{value:08x}".encode("ascii") for value in fields))
        output.extend(name)
        output.extend(b"\0" * ((-len(output)) % 4))
        output.extend(entry.data)
        output.extend(b"\0" * ((-len(output)) % 4))
    return bytes(output)


def entry_map(entries: list[CpioEntry]) -> dict[str, CpioEntry]:
    result: dict[str, CpioEntry] = {}
    for entry in entries:
        if entry.name in result:
            raise ValueError(f"duplicate ramdisk entry: {entry.name}")
        result[entry.name] = entry
    return result


def unpack_mtk_ramdisk(blob: bytes) -> tuple[bytes, list[CpioEntry]]:
    if len(blob) <= MTK_HEADER_SIZE:
        raise ValueError("MediaTek ramdisk is too small")
    header = blob[:MTK_HEADER_SIZE]
    magic, payload_size = struct.unpack_from("<II", header, 0)
    if magic != MTK_MAGIC:
        raise ValueError(f"unexpected MediaTek ramdisk magic: 0x{magic:08x}")
    if not header[8 : 8 + len(MTK_NAME)] == MTK_NAME:
        raise ValueError("MediaTek ramdisk name is not ROOTFS")
    payload = blob[MTK_HEADER_SIZE:]
    if payload_size != len(payload):
        raise ValueError(f"MediaTek payload size mismatch: {payload_size} != {len(payload)}")
    return header, parse_newc(gzip.decompress(payload))


def pack_mtk_ramdisk(header: bytes, entries: list[CpioEntry]) -> bytes:
    cpio = serialize_newc(entries)
    payload = gzip.compress(cpio, compresslevel=9, mtime=0)
    updated_header = bytearray(header)
    struct.pack_into("<I", updated_header, 4, len(payload))
    return bytes(updated_header) + payload


@dataclass
class BootImage:
    first_page: bytes
    page_size: int
    kernel: bytes
    ramdisk: bytes
    second: bytes
    device_tree: bytes
    trailing: bytes
    header_values: tuple


def parse_boot_image(blob: bytes) -> BootImage:
    if len(blob) < BOOT_HEADER_SIZE:
        raise ValueError("boot image is too small")
    values = struct.unpack_from(BOOT_HEADER_FORMAT, blob, 0)
    if values[0] != BOOT_MAGIC:
        raise ValueError("legacy Android boot magic is missing")
    kernel_size = values[1]
    ramdisk_size = values[3]
    second_size = values[5]
    page_size = values[8]
    dt_size = values[9]
    if page_size not in (2048, 4096, 8192, 16384):
        raise ValueError(f"unsupported boot page size: {page_size}")
    offset = page_size
    kernel = blob[offset : offset + kernel_size]
    offset += align(kernel_size, page_size)
    ramdisk = blob[offset : offset + ramdisk_size]
    offset += align(ramdisk_size, page_size)
    second = blob[offset : offset + second_size]
    offset += align(second_size, page_size)
    device_tree = blob[offset : offset + dt_size]
    offset += align(dt_size, page_size)
    if len(kernel) != kernel_size or len(ramdisk) != ramdisk_size:
        raise ValueError("boot image component is truncated")
    return BootImage(
        blob[:page_size], page_size, kernel, ramdisk, second, device_tree,
        blob[offset:], values
    )


def boot_id(kernel: bytes, ramdisk: bytes, second: bytes, device_tree: bytes) -> bytes:
    digest = hashlib.sha1()
    for component in (kernel, ramdisk, second):
        digest.update(component)
        digest.update(struct.pack("<I", len(component)))
    if device_tree:
        digest.update(device_tree)
        digest.update(struct.pack("<I", len(device_tree)))
    return digest.digest() + b"\0" * 12


def pack_boot_image(original: BootImage, ramdisk: bytes) -> bytes:
    first_page = bytearray(original.first_page)
    struct.pack_into("<I", first_page, 16, len(ramdisk))
    first_page[576:608] = boot_id(
        original.kernel, ramdisk, original.second, original.device_tree
    )
    output = bytearray(first_page)
    for component in (original.kernel, ramdisk, original.second, original.device_tree):
        output.extend(component)
        output.extend(b"\0" * ((-len(component)) % original.page_size))
    output.extend(original.trailing)
    return bytes(output)


def replace_once(data: bytes, old: bytes, new: bytes, label: str) -> bytes:
    count = data.count(old)
    if count != 1:
        raise ValueError(f"expected exactly one {label}, found {count}")
    return data.replace(old, new, 1)


def patch_ramdisk(entries: list[CpioEntry], adbd: bytes) -> list[CpioEntry]:
    patched = [CpioEntry(**vars(entry)) for entry in entries]
    by_name = entry_map(patched)
    if "sbin/adbd" in by_name:
        raise ValueError("stock ramdisk unexpectedly already contains sbin/adbd")

    default_prop = by_name["default.prop"]
    default_prop.data = replace_once(
        default_prop.data,
        b"persist.sys.usb.config=mass_storage",
        USB_CONFIG,
        "stock USB configuration",
    )
    for required in (b"ro.secure=1", b"ro.debuggable=0", b"ro.adb.secure=1"):
        if required not in default_prop.data:
            raise ValueError(f"secure ADB property was not preserved: {required.decode()}")

    if b"af.fm.force_direct_mode_type" in default_prop.data:
        raise ValueError("stock ramdisk unexpectedly already sets the FM audio path")
    default_prop.data = default_prop.data.rstrip(b"\n") + b"\n" + FM_AUDIO_PROP + b"\n"

    project_init = by_name["init.project.rc"]
    if Y2_INIT_MARKER in project_init.data:
        raise ValueError("Y2 ADB init action is already present")
    project_init.data = project_init.data.rstrip(b"\n") + b"\n" + Y2_INIT_ACTION.lstrip(b"\n")

    trailer_index = next(index for index, entry in enumerate(patched) if entry.name == CPIO_TRAILER)
    adbd_entry = CpioEntry(
        magic=patched[0].magic,
        ino=max(entry.ino for entry in patched) + 1,
        mode=stat.S_IFREG | 0o750,
        uid=0,
        gid=0,
        nlink=1,
        mtime=0,
        devmajor=0,
        devminor=0,
        rdevmajor=0,
        rdevminor=0,
        check=0,
        name="sbin/adbd",
        data=adbd,
    )
    patched.insert(trailer_index, adbd_entry)
    return patched


def verify_patched_ramdisk(entries: list[CpioEntry], expected_adbd: bytes) -> list[str]:
    by_name = entry_map(entries)
    checks: list[str] = []
    adbd = by_name.get("sbin/adbd")
    if adbd is None:
        raise ValueError("patched ramdisk is missing sbin/adbd")
    if adbd.data != expected_adbd:
        raise ValueError("embedded adbd does not match the compiled daemon")
    if adbd.mode != stat.S_IFREG | 0o750 or adbd.uid != 0 or adbd.gid != 0:
        raise ValueError("embedded adbd must be root:root mode 0750")
    checks.append("adbd exists once as root:root mode 0750 and is byte-identical")

    default_prop = by_name["default.prop"].data
    if default_prop.count(USB_CONFIG) != 1:
        raise ValueError("mass_storage,adb is not the unique default USB composition")
    if b"persist.sys.usb.config=mass_storage\n" in default_prop:
        raise ValueError("mass-storage-only default remains")
    for required in (b"ro.secure=1", b"ro.debuggable=0", b"ro.adb.secure=1"):
        if required not in default_prop:
            raise ValueError(f"missing secure property: {required.decode()}")
    checks.append("default USB composition is mass_storage,adb")
    checks.append("RSA authorization and non-root shell security remain enabled")

    if default_prop.count(FM_AUDIO_PROP) != 1:
        raise ValueError("FM audio path property is not set exactly once")
    if default_prop.count(b"af.fm.force_direct_mode_type") != 1:
        raise ValueError("conflicting FM audio path property present")
    checks.append("FM audio path forced to indirect mode (af.fm.force_direct_mode_type=2)")

    project_init = by_name["init.project.rc"].data
    if project_init.count(Y2_INIT_MARKER) != 1:
        raise ValueError("boot-time ADB composition action is missing or duplicated")
    if project_init.count(b"setprop sys.usb.config mass_storage,adb") != 1:
        raise ValueError("boot-time USB composition is incorrect")
    checks.append("boot init reapplies mass_storage,adb after persistent properties load")

    init_rc = by_name["init.rc"].data
    if b"service adbd /sbin/adbd" not in init_rc or b"socket adbd stream 660 system system" not in init_rc:
        raise ValueError("stock adbd init service or authentication socket is missing")
    usb_rc = by_name["init.usb.rc"].data
    block_start = usb_rc.find(b"on property:sys.usb.config=mass_storage,adb")
    block_end = usb_rc.find(b"\non property:", block_start + 1)
    block = usb_rc[block_start : block_end if block_end >= 0 else None]
    if block_start < 0 or b"functions $sys.usb.config" not in block or b"start adbd" not in block:
        raise ValueError("stock concurrent mass-storage/ADB USB trigger is incomplete")
    checks.append("USB gadget exposes mass storage and ADB concurrently and starts adbd")
    return checks


def build(args: argparse.Namespace) -> None:
    stock_boot_path = Path(args.stock_boot).resolve()
    adbd_path = Path(args.adbd).resolve()
    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    stock_boot_blob = stock_boot_path.read_bytes()
    adbd_blob = adbd_path.read_bytes()
    stock = parse_boot_image(stock_boot_blob)
    mtk_header, stock_entries = unpack_mtk_ramdisk(stock.ramdisk)

    expected_id = boot_id(stock.kernel, stock.ramdisk, stock.second, stock.device_tree)
    original_id = stock.first_page[576:608]
    if original_id != expected_id:
        raise ValueError("stock boot ID does not match the Android v0 SHA-1 algorithm")
    if pack_boot_image(stock, stock.ramdisk) != stock_boot_blob:
        raise ValueError("stock boot image does not survive a byte-exact round trip")

    patched_entries = patch_ramdisk(stock_entries, adbd_blob)
    patched_ramdisk = pack_mtk_ramdisk(mtk_header, patched_entries)
    patched_boot_blob = pack_boot_image(stock, patched_ramdisk)
    patched_boot = parse_boot_image(patched_boot_blob)
    _, verified_entries = unpack_mtk_ramdisk(patched_boot.ramdisk)
    checks = verify_patched_ramdisk(verified_entries, adbd_blob)

    if patched_boot.kernel != stock.kernel:
        raise ValueError("kernel changed while repacking")
    immutable_offsets = (
        (8, 16),    # kernel size and address
        (20, 48),   # ramdisk address through unused field
        (48, 576),  # board name and command line
    )
    for start, end in immutable_offsets:
        if patched_boot.first_page[start:end] != stock.first_page[start:end]:
            raise ValueError(f"immutable boot header bytes changed at {start}:{end}")
    if len(patched_boot_blob) > args.partition_size:
        raise ValueError(
            f"boot image exceeds BOOTIMG partition: {len(patched_boot_blob)} > {args.partition_size}"
        )
    checks.append("stock boot image passes a byte-exact unpack/repack round trip")
    checks.append("kernel, load addresses, page size, board name, and command line are unchanged")
    checks.append(f"boot image fits the {args.partition_size}-byte BOOTIMG partition")

    output_boot = output_dir / "boot.img"
    output_stock = output_dir / "boot-stock.img"
    output_ramdisk = output_dir / "ramdisk-adb.img"
    output_report = output_dir / "verification-report.txt"
    output_checksums = output_dir / "checksums.txt"
    output_boot.write_bytes(patched_boot_blob)
    shutil.copyfile(stock_boot_path, output_stock)
    output_ramdisk.write_bytes(patched_ramdisk)

    board = stock.header_values[11].split(b"\0", 1)[0].decode("ascii", "replace")
    cmdline = stock.header_values[12].split(b"\0", 1)[0].decode("ascii", "replace")
    report_lines = [
        "Y2Player secure ADB boot-image verification",
        "===========================================",
        "",
        f"Stock boot SHA-256 : {sha256(stock_boot_blob)}",
        f"Output boot SHA-256: {sha256(patched_boot_blob)}",
        f"Kernel SHA-256     : {sha256(stock.kernel)}",
        f"Ramdisk SHA-256    : {sha256(patched_ramdisk)}",
        f"adbd SHA-256       : {sha256(adbd_blob)}",
        f"Page size          : {stock.page_size}",
        f"Board name         : {board}",
        f"Kernel command line: {cmdline or '(empty)'}",
        f"Stock image size   : {len(stock_boot_blob)}",
        f"Output image size  : {len(patched_boot_blob)}",
        f"Partition size     : {args.partition_size}",
        f"Ramdisk entries    : {len(verified_entries)}",
        "USB composition    : mass_storage,adb",
        "ADB authentication : RSA authorization required",
        "ADB privilege      : non-root shell",
        "",
        "Checks",
        "------",
    ]
    report_lines.extend(f"ok  {check}" for check in checks)
    report_lines.extend(
        [
            "",
            "Hardware validation still required: boot, USB mass-storage transfer,",
            "ADB unauthorized/authorization/online transition, shell, push, and pull.",
            "",
        ]
    )
    output_report.write_text("\n".join(report_lines), encoding="utf-8", newline="\n")
    checksums = []
    for path in (output_boot, output_stock, output_ramdisk, output_report, adbd_path):
        checksums.append(f"{sha256(path.read_bytes())}  {path.name}")
    output_checksums.write_text("\n".join(checksums) + "\n", encoding="ascii", newline="\n")
    print(output_report.read_text(encoding="utf-8"), end="")
    print(f"Output: {output_boot}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stock-boot", required=True)
    parser.add_argument("--adbd", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--partition-size", type=lambda value: int(value, 0), default=0x1000000)
    build(parser.parse_args())


if __name__ == "__main__":
    main()
