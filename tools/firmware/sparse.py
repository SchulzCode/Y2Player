#!/usr/bin/env python3
"""
Android sparse-image conversion for the Innioasis Y2 (MT6582) system partition.

The stock system.img is an Android sparse image (magic 0xed26ff3a) wrapping an
ext4 filesystem. The stock encoder emits RAW chunks for data and DONT_CARE for
holes, with the image checksum left at 0; this module reproduces that exact
shape so the resulting image is byte-compatible with what SP Flash Tool already
accepts for this device.

Usage:
    python3 sparse.py unpack <system.img> <system.raw>
    python3 sparse.py pack   <system.raw> <system.img>
"""
import struct
import sys

SPARSE_MAGIC = 0xED26FF3A
CHUNK_RAW = 0xCAC1
CHUNK_FILL = 0xCAC2
CHUNK_DONT_CARE = 0xCAC3
CHUNK_CRC32 = 0xCAC4
FILE_HDR_SZ = 28
CHUNK_HDR_SZ = 12
DEFAULT_BLOCK = 4096


def unpack(src_path, dst_path):
    """Expand a sparse image to a raw ext4 image."""
    with open(src_path, "rb") as src, open(dst_path, "wb") as dst:
        header = src.read(FILE_HDR_SZ)
        (magic, _major, _minor, file_hdr_sz, chunk_hdr_sz,
         block_size, total_blocks, total_chunks, _csum) = struct.unpack("<IHHHHIIII", header)
        if magic != SPARSE_MAGIC:
            raise SystemExit(f"{src_path}: not an Android sparse image (magic {magic:#x})")
        if file_hdr_sz > FILE_HDR_SZ:
            src.read(file_hdr_sz - FILE_HDR_SZ)

        for _ in range(total_chunks):
            chunk = src.read(chunk_hdr_sz)
            chunk_type, _reserved, chunk_blocks, total_size = struct.unpack("<HHII", chunk)
            if chunk_hdr_sz > CHUNK_HDR_SZ:
                src.read(chunk_hdr_sz - CHUNK_HDR_SZ)
            payload = block_size * chunk_blocks
            if chunk_type == CHUNK_RAW:
                dst.write(src.read(payload))
            elif chunk_type == CHUNK_FILL:
                dst.write(src.read(4) * (payload // 4))
            elif chunk_type == CHUNK_DONT_CARE:
                dst.write(b"\x00" * payload)
            elif chunk_type == CHUNK_CRC32:
                src.read(4)
            else:
                raise SystemExit(f"unsupported chunk type {chunk_type:#x}")
        written = dst.tell()
    expected = total_blocks * block_size
    if written != expected:
        raise SystemExit(f"size mismatch: wrote {written}, header declares {expected}")
    return written, block_size, total_blocks


def pack(src_path, dst_path, block_size=DEFAULT_BLOCK):
    """
    Re-encode a raw ext4 image as a sparse image.

    Runs of all-zero blocks become DONT_CARE chunks; everything else is RAW.
    This mirrors the stock encoder (the original image contained only RAW and
    DONT_CARE chunks) and keeps the partition size declaration identical.
    """
    import os
    size = os.path.getsize(src_path)
    if size % block_size:
        raise SystemExit(f"{src_path}: size {size} is not a multiple of {block_size}")
    total_blocks = size // block_size
    zero = b"\x00" * block_size

    # First pass: build the chunk plan so total_chunks is known for the header.
    plan = []  # (is_data, start_block, block_count)
    with open(src_path, "rb") as src:
        run_is_data = None
        run_start = 0
        for index in range(total_blocks):
            is_data = src.read(block_size) != zero
            if run_is_data is None:
                run_is_data, run_start = is_data, index
            elif is_data != run_is_data:
                plan.append((run_is_data, run_start, index - run_start))
                run_is_data, run_start = is_data, index
        if run_is_data is not None:
            plan.append((run_is_data, run_start, total_blocks - run_start))

    with open(src_path, "rb") as src, open(dst_path, "wb") as dst:
        dst.write(struct.pack(
            "<IHHHHIIII", SPARSE_MAGIC, 1, 0, FILE_HDR_SZ, CHUNK_HDR_SZ,
            block_size, total_blocks, len(plan), 0,
        ))
        for is_data, start, count in plan:
            payload = count * block_size
            if is_data:
                dst.write(struct.pack("<HHII", CHUNK_RAW, 0, count, CHUNK_HDR_SZ + payload))
                src.seek(start * block_size)
                remaining = payload
                while remaining:
                    piece = src.read(min(remaining, 8 << 20))
                    dst.write(piece)
                    remaining -= len(piece)
            else:
                dst.write(struct.pack("<HHII", CHUNK_DONT_CARE, 0, count, CHUNK_HDR_SZ))
        return dst.tell(), len(plan)


def main():
    if len(sys.argv) != 4 or sys.argv[1] not in ("unpack", "pack"):
        raise SystemExit(__doc__)
    mode, src, dst = sys.argv[1], sys.argv[2], sys.argv[3]
    if mode == "unpack":
        written, block_size, blocks = unpack(src, dst)
        print(f"unpacked {written} bytes ({blocks} x {block_size})")
    else:
        written, chunks = pack(src, dst)
        print(f"packed {written} bytes in {chunks} chunks")


if __name__ == "__main__":
    main()
