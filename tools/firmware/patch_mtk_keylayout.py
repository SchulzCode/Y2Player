#!/usr/bin/env python3
"""Guard and patch the Y2 keypad volume mappings for app-owned volume."""
import argparse
import hashlib
import os
import tempfile

KEYLAYOUT_SYSTEM_PATH = "/usr/keylayout/mtk-kpd.kl"
STOCK_SIZE = 1680
STOCK_SHA256 = "de48544bbfd465ac844bff2fd9f30c5793738041b6da6f228eeb8c40e9d444a7"
PATCHED_SIZE = 1684
PATCHED_SHA256 = "b85deb46f0ac9ebeb49f22a442ef442fc7adc612dbf075e52ddf178695969ec4"

REPLACEMENTS = (
    (
        b"key 115   VOLUME_UP         WAKE_DROPPED",
        b"key 115   MEDIA_FAST_FORWARD  WAKE_DROPPED",
    ),
    (
        b"key 114   VOLUME_DOWN       WAKE_DROPPED",
        b"key 114   MEDIA_REWIND        WAKE_DROPPED",
    ),
)


class PatchError(ValueError):
    pass


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def patch_keylayout(data):
    digest = sha256_bytes(data)
    if len(data) != STOCK_SIZE or digest != STOCK_SHA256:
        raise PatchError(
            "unexpected stock mtk-kpd.kl: size=%d SHA-256=%s; expected %d/%s"
            % (len(data), digest, STOCK_SIZE, STOCK_SHA256)
        )
    patched = data
    for stock, replacement in REPLACEMENTS:
        if patched.count(stock) != 1:
            raise PatchError("expected keypad mapping is missing or duplicated: %r" % stock)
        patched = patched.replace(stock, replacement, 1)
    verify_patched_keylayout(patched)
    return patched


def verify_patched_keylayout(data):
    digest = sha256_bytes(data)
    if len(data) != PATCHED_SIZE or digest != PATCHED_SHA256:
        raise PatchError(
            "unexpected patched mtk-kpd.kl: size=%d SHA-256=%s; expected %d/%s"
            % (len(data), digest, PATCHED_SIZE, PATCHED_SHA256)
        )
    for stock, replacement in REPLACEMENTS:
        if stock in data or data.count(replacement) != 1:
            raise PatchError("patched keypad mapping failed structural verification")
    return digest


def write_atomic(path, data):
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".mtk-kpd.", dir=directory)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input")
    parser.add_argument("output")
    args = parser.parse_args()
    with open(args.input, "rb") as handle:
        patched = patch_keylayout(handle.read())
    write_atomic(args.output, patched)
    print("patched %s -> %s" % (args.input, args.output))
    print("size=%d SHA-256=%s" % (len(patched), sha256_bytes(patched)))


if __name__ == "__main__":
    main()
