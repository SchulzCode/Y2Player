#!/usr/bin/env python3
"""Apply the narrowly scoped CS43131 sample-rate hook to the stock Y2 HAL.

The patch is intentionally tied to one exact stock binary.  It refuses to run
if the size, SHA-256, ELF layout, original function bytes, PLT stubs, device-path
string, or injection space differs.  It never touches a device; it only writes
a new local ELF file.
"""

import argparse
import hashlib
import os
import struct
import tempfile


HAL_SYSTEM_PATH = "/lib/libaudio.primary.default.so"
STOCK_SIZE = 753072
STOCK_SHA256 = "5c5162f6a68f7db57febd050ee88cc886779dcce5948937149d6cd211eb0e6de"
PATCHED_SHA256 = "c155e239c8d13bc83bc4016ebdcbd1724114d728df86beb4d42c112150ffe216"

HOOK_ADDRESS = 0x00081B20
INJECTION_ADDRESS = 0x000A73E4
ORIGINAL_RX_SIZE = INJECTION_ADDRESS
CS43131_PATH_ADDRESS = 0x000A5FFC
OPEN_PLT_ADDRESS = 0x0002ECFC
IOCTL_PLT_ADDRESS = 0x0002ECF0
CLOSE_PLT_ADDRESS = 0x0002ED14

ORIGINAL_FUNCTION = bytes.fromhex(
    "14 10 9f e5 14 20 9f e5 00 30 a0 e1 01 10 8f e0 "
    "03 00 a0 e3 02 20 8f e0 66 b4 fe ea 74 44 02 00 "
    "18 45 02 00"
)

# Independently assembled from primary_audio_hal_hook.S with Android NDK r25c
# clang 14, linked at 0x000a73e4, then disassembled with llvm-objdump.
HOOK_PAYLOAD = bytes.fromhex(
    "30 48 2d e9 00 40 a0 e1 44 3c 0a e3 03 00 54 e1 "
    "02 00 00 0a 80 3b 0b e3 03 00 54 e1 0c 00 00 1a "
    "30 00 9f e5 00 00 8f e0 02 10 a0 e3 39 1e fe eb "
    "00 00 50 e3 06 00 00 ba 00 50 a0 e1 05 13 04 e3 "
    "04 10 44 e3 04 20 a0 e1 2f 1e fe eb 05 00 a0 e1 "
    "36 1e fe eb 30 88 bd e8 ec eb ff ff"
)

DEVICE_PATH = b"/dev/cs43131_dac\x00"
PATCHED_RX_SIZE = INJECTION_ADDRESS + len(HOOK_PAYLOAD)


class PatchError(RuntimeError):
    pass


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def arm_branch(source, target):
    """Encode an unconditional ARM-state B from source to target."""
    delta = target - (source + 8)
    if delta % 4 or not -(1 << 25) <= delta < (1 << 25):
        raise PatchError("ARM branch target is unaligned or out of range")
    instruction = 0xEA000000 | ((delta >> 2) & 0x00FFFFFF)
    return struct.pack("<I", instruction)


HOOK_BRANCH = arm_branch(HOOK_ADDRESS, INJECTION_ADDRESS)


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
    if len(stock) != STOCK_SIZE:
        raise PatchError(f"stock HAL size mismatch: expected {STOCK_SIZE}, found {len(stock)}")
    digest = sha256_bytes(stock)
    if digest != STOCK_SHA256:
        raise PatchError(f"stock HAL SHA-256 mismatch: expected {STOCK_SHA256}, found {digest}")

    phdr_offset = elf_rx_program_header(stock, ORIGINAL_RX_SIZE)
    require_range(stock, HOOK_ADDRESS, ORIGINAL_FUNCTION, "stock frequency stub")
    require_range(stock, CS43131_PATH_ADDRESS, DEVICE_PATH, "CS43131 device path")
    require_range(
        stock, INJECTION_ADDRESS, b"\x00" * len(HOOK_PAYLOAD), "HAL injection space"
    )

    patched = bytearray(stock)
    patched[INJECTION_ADDRESS:PATCHED_RX_SIZE] = HOOK_PAYLOAD
    patched[HOOK_ADDRESS:HOOK_ADDRESS + 4] = HOOK_BRANCH
    # ELF32_Phdr p_filesz and p_memsz fields are at +16 and +20.
    struct.pack_into("<II", patched, phdr_offset + 16, PATCHED_RX_SIZE, PATCHED_RX_SIZE)
    verify_patched_hal(patched)
    return bytes(patched)


def verify_patched_hal(data):
    if len(data) != STOCK_SIZE:
        raise PatchError(f"patched HAL size mismatch: expected {STOCK_SIZE}, found {len(data)}")
    elf_rx_program_header(data, PATCHED_RX_SIZE)
    require_range(data, HOOK_ADDRESS, HOOK_BRANCH, "frequency hook branch")
    require_range(data, HOOK_ADDRESS + 4, ORIGINAL_FUNCTION[4:], "preserved stock stub tail")
    require_range(data, INJECTION_ADDRESS, HOOK_PAYLOAD, "injected frequency routine")
    require_range(data, CS43131_PATH_ADDRESS, DEVICE_PATH, "CS43131 device path")
    digest = sha256_bytes(data)
    if digest != PATCHED_SHA256:
        raise PatchError(
            f"patched HAL SHA-256 mismatch: expected {PATCHED_SHA256}, found {digest}"
        )
    return digest


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
