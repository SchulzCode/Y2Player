#!/usr/bin/env python3
"""Apply the narrowly scoped CS43131 sample-rate hook to a supported Y2 HAL.

Each patch variant is intentionally tied to an exact stock binary. It refuses to run
if the size, SHA-256, ELF layout, original function bytes, PLT stubs, device-path
string, or injection space differs.  It never touches a device; it only writes
a new local ELF file.
"""

import argparse
import hashlib
import os
import struct
import tempfile
from dataclasses import dataclass


HAL_SYSTEM_PATH = "/lib/libaudio.primary.default.so"


@dataclass(frozen=True)
class HalVariant:
    name: str
    size: int
    stock_sha256: str
    patched_sha256: str
    hook: int
    injection: int
    path: int
    open_plt: int
    ioctl_plt: int
    close_plt: int
    original_function: bytes


V1_FUNCTION = bytes.fromhex(
    "14 10 9f e5 14 20 9f e5 00 30 a0 e1 01 10 8f e0 "
    "03 00 a0 e3 02 20 8f e0 66 b4 fe ea 74 44 02 00 "
    "18 45 02 00"
)

V320_FUNCTION = bytes.fromhex(
    "14 10 9f e5 14 20 9f e5 00 30 a0 e1 01 10 8f e0 "
    "03 00 a0 e3 02 20 8f e0 a6 b2 fe ea 50 48 02 00 "
    "f4 48 02 00"
)

# Independently assembled from primary_audio_hal_hook.S with Android NDK r25c
# clang 14, linked at 0x000a73e4, then disassembled with llvm-objdump.
HOOK_PAYLOAD_TEMPLATE = bytes.fromhex(
    "30 48 2d e9 00 40 a0 e1 44 3c 0a e3 03 00 54 e1 "
    "02 00 00 0a 80 3b 0b e3 03 00 54 e1 0c 00 00 1a "
    "30 00 9f e5 00 00 8f e0 02 10 a0 e3 39 1e fe eb "
    "00 00 50 e3 06 00 00 ba 00 50 a0 e1 05 13 04 e3 "
    "04 10 44 e3 04 20 a0 e1 2f 1e fe eb 05 00 a0 e1 "
    "36 1e fe eb 30 88 bd e8 ec eb ff ff"
)

DEVICE_PATH = b"/dev/cs43131_dac\x00"

VARIANTS = (
    HalVariant(
        "Y2 original", 753072,
        "5c5162f6a68f7db57febd050ee88cc886779dcce5948937149d6cd211eb0e6de",
        "c155e239c8d13bc83bc4016ebdcbd1724114d728df86beb4d42c112150ffe216",
        0x81B20, 0xA73E4, 0xA5FFC, 0x2ECFC, 0x2ECF0, 0x2ED14, V1_FUNCTION,
    ),
    HalVariant(
        "Y2 v3.2.0 FM", 757172,
        "409430ce670538326110f8f991ead095b0c16df1274fab2e14a8988b69cc4304",
        "5e4b3c85b6cb6a65058eaa2a9685a1e003b62d45771a75e0321e968543d8d0b5",
        0x824D8, 0xA81B4, 0xA6D90, 0x2EFB4, 0x2EFA8, 0x2EFCC, V320_FUNCTION,
    ),
)

STOCK_SIZE = VARIANTS[0].size
STOCK_SHA256 = VARIANTS[0].stock_sha256
PATCHED_SHA256 = VARIANTS[0].patched_sha256


class PatchError(RuntimeError):
    pass


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def arm_branch(source, target, opcode=0xEA000000):
    """Encode an unconditional ARM-state B from source to target."""
    delta = target - (source + 8)
    if delta % 4 or not -(1 << 25) <= delta < (1 << 25):
        raise PatchError("ARM branch target is unaligned or out of range")
    instruction = opcode | ((delta >> 2) & 0x00FFFFFF)
    return struct.pack("<I", instruction)


def payload_for(variant):
    payload = bytearray(HOOK_PAYLOAD_TEMPLATE)
    payload[0x2C:0x30] = arm_branch(variant.injection + 0x2C, variant.open_plt, 0xEB000000)
    payload[0x48:0x4C] = arm_branch(variant.injection + 0x48, variant.ioctl_plt, 0xEB000000)
    payload[0x50:0x54] = arm_branch(variant.injection + 0x50, variant.close_plt, 0xEB000000)
    struct.pack_into("<i", payload, 0x58, variant.path - (variant.injection + 0x2C))
    return bytes(payload)


def identify_variant(data, patched=False):
    digest = sha256_bytes(data)
    field = "patched_sha256" if patched else "stock_sha256"
    for variant in VARIANTS:
        if len(data) == variant.size and digest == getattr(variant, field):
            return variant
    known = ", ".join(f"{variant.name}: {getattr(variant, field)}" for variant in VARIANTS)
    raise PatchError(
        f"unrecognized {'patched' if patched else 'stock'} HAL: {len(data)} bytes, "
        f"SHA-256 {digest}; expected {known}"
    )


def elf_rx_program_header(data, expected_size):
    if len(data) < 52 or data[:7] != b"\x7fELF\x01\x01\x01":
        raise PatchError("HAL is not an ELF32 little-endian current-version file")
    if struct.unpack_from("<H", data, 18)[0] != 40:
        raise PatchError("HAL ELF machine is not ARM")

    phoff = struct.unpack_from("<I", data, 28)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 42)
    if phentsize != 32 or phoff + phentsize * phnum > len(data):
        raise PatchError("unexpected or truncated ELF program-header table")

    matches = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        fields = struct.unpack_from("<IIIIIIII", data, offset)
        p_type, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_flags, p_align = fields
        if p_type == 1 and p_flags == 5:
            matches.append((offset, fields))

    if len(matches) != 1:
        raise PatchError(f"expected one executable PT_LOAD; found {len(matches)}")
    offset, fields = matches[0]
    _, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, _, p_align = fields
    expected = (0, 0, 0, expected_size, expected_size, 0x1000)
    actual = (p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align)
    if actual != expected:
        raise PatchError(
            "unexpected executable PT_LOAD layout: "
            f"offset=0x{p_offset:x} vaddr=0x{p_vaddr:x} paddr=0x{p_paddr:x} "
            f"filesz=0x{p_filesz:x} memsz=0x{p_memsz:x} align=0x{p_align:x}"
        )
    return offset


def require_range(data, offset, expected, description):
    actual = bytes(data[offset:offset + len(expected)])
    if actual != expected:
        raise PatchError(
            f"{description} mismatch at file offset 0x{offset:x}: "
            f"expected {expected.hex()}, found {actual.hex()}"
        )


def patch_hal(stock):
    variant = identify_variant(stock)
    payload = payload_for(variant)
    patched_rx_size = variant.injection + len(payload)
    phdr_offset = elf_rx_program_header(stock, variant.injection)
    require_range(stock, variant.hook, variant.original_function, "stock frequency stub")
    require_range(stock, variant.path, DEVICE_PATH, "CS43131 device path")
    require_range(
        stock, variant.injection, b"\x00" * len(payload), "HAL injection space"
    )

    patched = bytearray(stock)
    patched[variant.injection:patched_rx_size] = payload
    patched[variant.hook:variant.hook + 4] = arm_branch(variant.hook, variant.injection)
    # ELF32_Phdr p_filesz and p_memsz fields are at +16 and +20.
    struct.pack_into("<II", patched, phdr_offset + 16, patched_rx_size, patched_rx_size)
    verify_variant(patched, variant)
    return bytes(patched)


def verify_variant(data, variant, check_digest=True):
    payload = payload_for(variant)
    elf_rx_program_header(data, variant.injection + len(payload))
    require_range(data, variant.hook, arm_branch(variant.hook, variant.injection), "frequency hook branch")
    require_range(data, variant.hook + 4, variant.original_function[4:], "preserved stock stub tail")
    require_range(data, variant.injection, payload, "injected frequency routine")
    require_range(data, variant.path, DEVICE_PATH, "CS43131 device path")
    digest = sha256_bytes(data)
    if check_digest and digest != variant.patched_sha256:
        raise PatchError(
            f"patched HAL SHA-256 mismatch: expected {variant.patched_sha256}, found {digest}"
        )
    return digest


def verify_patched_hal(data):
    return verify_variant(data, identify_variant(data, patched=True))


def write_atomic(path, data):
    destination = os.path.abspath(path)
    os.makedirs(os.path.dirname(destination), exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(
        prefix=os.path.basename(destination) + ".", suffix=".tmp",
        dir=os.path.dirname(destination),
    )
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, destination)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", help="exact stock libaudio.primary.default.so")
    parser.add_argument("output", nargs="?", help="patched output HAL")
    parser.add_argument(
        "--verify-patched", action="store_true",
        help="verify input as the patched HAL instead of applying the patch",
    )
    args = parser.parse_args()

    with open(args.input, "rb") as handle:
        data = handle.read()

    if args.verify_patched:
        if args.output:
            parser.error("output is not accepted with --verify-patched")
        digest = verify_patched_hal(data)
        print(f"verified patched HAL: {len(data)} bytes, SHA-256 {digest}")
        return 0

    if not args.output:
        parser.error("output is required when applying the patch")
    patched = patch_hal(data)
    write_atomic(args.output, patched)
    print(f"stock HAL   : {len(data)} bytes, SHA-256 {sha256_bytes(data)}")
    print(f"patched HAL : {len(patched)} bytes, SHA-256 {sha256_bytes(patched)}")
    print(
        "hook         : EXT_DAC_SetPlaybackFreq -> guarded 44100/48000 Hz "
        "ioctl 0x40044305"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
