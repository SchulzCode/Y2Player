#!/usr/bin/env bash
# Fetch the two exact official AOSP revisions used by build-adbd.sh.

set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "usage: $0 <repository-root>" >&2
    exit 2
fi

repo="$(realpath "$1")"

fetch_exact() {
    local url="$1"
    local tag="$2"
    local expected="$3"
    local destination="$4"

    if [[ ! -d "$destination/.git" ]]; then
        if [[ -e "$destination" ]]; then
            echo "source path exists but is not a Git checkout: $destination" >&2
            exit 1
        fi
        git clone --depth 1 --branch "$tag" "$url" "$destination"
    fi

    local actual
    actual="$(git -C "$destination" rev-parse HEAD)"
    if [[ "$actual" != "$expected" ]]; then
        echo "source revision mismatch in $destination" >&2
        echo "expected: $expected" >&2
        echo "actual  : $actual" >&2
        exit 1
    fi
    # A checkout created by Windows Git may contain CRLF while the pinned AOSP
    # objects use LF. Permit only that end-of-line normalization; a real source
    # change still makes the verification fail.
    if ! git -C "$destination" diff --ignore-space-at-eol --quiet HEAD --; then
        echo "source checkout has non-EOL modifications: $destination" >&2
        exit 1
    fi
    echo "verified $destination @ $actual"
}

fetch_exact \
    "https://android.googlesource.com/platform/system/core.git" \
    "android-4.4.2_r1" \
    "e65b7ea8801145626504c724c28aedd0e5038a28" \
    "$repo/third_party/aosp-system-core-android-4.4.2_r1"

fetch_exact \
    "https://android.googlesource.com/platform/hardware/libhardware.git" \
    "android-4.4.2_r1" \
    "7ccf148f5066ceb1a161f0d7a7d66f75c6e8d420" \
    "$repo/third_party/aosp-libhardware-android-4.4.2_r1"
