#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
temporary="$(mktemp "$ROOT/SOURCE_SHA256SUMS.txt.XXXXXX")"
trap 'rm -f -- "$temporary"' EXIT

while IFS= read -r file; do
    [[ "$file" == SOURCE_SHA256SUMS.txt || ! -f "$file" ]] && continue
    sha256sum -- "$file"
done < <(git ls-files --cached --others --exclude-standard | LC_ALL=C sort -u) > "$temporary"
mv -- "$temporary" SOURCE_SHA256SUMS.txt
trap - EXIT
printf 'Wrote %s entries to SOURCE_SHA256SUMS.txt\n' "$(wc -l < SOURCE_SHA256SUMS.txt)"
