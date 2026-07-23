#!/usr/bin/env bash
# System-partition-only half of the Y2Player firmware pipeline.
# Never reads, unpacks, copies, or modifies OriginalFirmware/boot.img.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

APK=""
APK_META=""
OUTDIR="$ROOT/out/firmware"
WORK="$ROOT/build/work"
VALIDATE_ONLY=0
APP_BUILD_COMMAND="unknown"
APP_TESTS_STATUS="unknown"
BUILD_ID="local-dev"

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --apk-metadata) APK_META="$2"; shift 2 ;;
    --out) OUTDIR="$2"; shift 2 ;;
    --work) WORK="$2"; shift 2 ;;
    --validate-only) VALIDATE_ONLY=1; shift ;;
    --app-build-command) APP_BUILD_COMMAND="$2"; shift 2 ;;
    --app-tests-status) APP_TESTS_STATUS="$2"; shift 2 ;;
    --build-id) BUILD_ID="$2"; shift 2 ;;
    *) printf 'unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done

STOCK_SYSTEM="$ROOT/OriginalFirmware/system.img"
STOCK_SCATTER="$ROOT/OriginalFirmware/MT6582_Android_scatter.txt"

stage() { printf '\n=== %s ===\n' "$*"; }
log() { printf '      %s\n' "$*"; }
fail() { printf '\nFAILED: %s\n' "$*" >&2; exit 1; }
sha() { sha256sum "$1" | cut -d' ' -f1; }

stage "Validating system-image environment"
missing=0
for tool in python3 debugfs e2fsck dumpe2fs sha256sum; do
  if command -v "$tool" >/dev/null; then log "found $tool"; else
    printf '      MISSING %s\n' "$tool"
    missing=1
  fi
done
[ "$missing" -eq 0 ] || fail "install python3, e2fsprogs, and coreutils"
[ -f "$STOCK_SYSTEM" ] || fail "base system image not found: $STOCK_SYSTEM"
[ -f "$STOCK_SCATTER" ] || fail "scatter file not found: $STOCK_SCATTER"

python3 - "$STOCK_SYSTEM" <<'PY'
import struct, sys
with open(sys.argv[1], 'rb') as handle:
    magic = struct.unpack('<I', handle.read(4))[0]
print("      system.img format: %s" % ("Android sparse" if magic == 0xED26FF3A else "unexpected"))
sys.exit(0 if magic == 0xED26FF3A else 1)
PY
log "base system.img: $(stat -c%s "$STOCK_SYSTEM") bytes"
log "APK install path: /system/priv-app/Y2Player.apk"
log "boot.img: not used and not emitted"

if [ -n "$APK" ]; then
  [ -f "$APK" ] || fail "APK not found: $APK"
elif [ "$VALIDATE_ONLY" -ne 1 ]; then
  fail "--apk is required for a full build"
fi

if [ "$VALIDATE_ONLY" -eq 1 ]; then
  stage "Validation complete (no artifacts produced)"
  exit 0
fi

# Fixed generated workspaces only. Canonical firmware images are never targets.
rm -rf "$WORK/system" "$WORK/boot" "$WORK/bridge" "$WORK/bridge-tests"
mkdir -p "$WORK/system" "$OUTDIR"

# Remove current artifacts plus stale bridge/boot outputs before building. The
# explicit output directory is the only location touched.
rm -f "$OUTDIR/Y2Player.apk" "$OUTDIR/system.img" \
      "$OUTDIR/boot.img" "$OUTDIR/boot-stock.img" "$OUTDIR/y2bridged" \
      "$OUTDIR/build-manifest.txt" "$OUTDIR/checksums.txt" \
      "$OUTDIR/verification-report.txt" "$OUTDIR"/bridge*

stage "Integrating Y2Player into system.img"
python3 tools/firmware/integrate_launcher.py \
  --system "$STOCK_SYSTEM" --apk "$APK" \
  --out "$WORK/system/system.img" \
  --report "$WORK/system/system-report.txt"

stage "Reopening and verifying system.img"
python3 tools/firmware/verify_images.py \
  --system "$WORK/system/system.img" --apk "$APK" \
  --scatter "$STOCK_SCATTER" --report "$WORK/verification-report.txt"

stage "Assembling current artifacts"
cp "$APK" "$OUTDIR/Y2Player.apk"
cp "$WORK/system/system.img" "$OUTDIR/system.img"
cp "$WORK/verification-report.txt" "$OUTDIR/verification-report.txt"

if git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  GIT_COMMIT="$(git -C "$ROOT" rev-parse HEAD)"
  if [ -n "$(git -C "$ROOT" status --porcelain)" ]; then GIT_DIRTY=yes; else GIT_DIRTY=no; fi
else
  GIT_COMMIT="unknown (Git metadata unavailable)"
  GIT_DIRTY=unknown
fi

{
  echo "Y2Player system-image build manifest"
  echo "===================================="
  echo
  echo "Build date (UTC)     : $(date -u '+%Y-%m-%d %H:%M:%S')"
  echo "Git commit           : $GIT_COMMIT"
  echo "Working tree dirty   : $GIT_DIRTY"
  echo "Build ID             : $BUILD_ID"
  echo
  echo "Application"
  echo "-----------"
  echo "Gradle command       : $APP_BUILD_COMMAND"
  echo "Tests/lint status    : $APP_TESTS_STATUS"
  echo "APK source path      : $APK"
  echo "APK output path      : $OUTDIR/Y2Player.apk"
  echo "APK SHA-256          : $(sha "$OUTDIR/Y2Player.apk")"
  if [ -n "$APK_META" ] && [ -f "$APK_META" ]; then cat "$APK_META"; fi
  echo
  echo "System image"
  echo "------------"
  echo "Base path            : $STOCK_SYSTEM"
  echo "Base SHA-256         : $(sha "$STOCK_SYSTEM")"
  echo "Output path          : $OUTDIR/system.img"
  echo "Output SHA-256       : $(sha "$OUTDIR/system.img")"
  echo "Format               : Android sparse (ext4 payload)"
  echo "APK install path     : /system/priv-app/Y2Player.apk"
  echo "Verification         : passed"
  echo
  echo "Tool versions"
  echo "-------------"
  echo "python3              : $(python3 --version 2>&1)"
  echo "debugfs              : $(debugfs -V 2>&1 | head -1)"
  echo "e2fsck               : $(e2fsck -V 2>&1 | head -1)"
  echo
  echo "Diagnostics"
  echo "-----------"
  echo "app log (device)     : /data/data/com.schulzcode.y2player/files/logs/"
  echo "app log mirror       : /storage/sdcard1/Y2Player/logs/ (best effort)"
  echo "collect a bundle     : .\\tools\\collect-device-diagnostics.ps1"
  echo "watch live           : .\\tools\\watch-device-logs.ps1"
  echo
  echo "Safety"
  echo "------"
  echo "Only system.img was rebuilt. No device operation was performed."
} > "$OUTDIR/build-manifest.txt"

( cd "$OUTDIR" && sha256sum Y2Player.apk system.img build-manifest.txt verification-report.txt > checksums.txt )

# A stale privileged/boot artifact must make the build visibly fail.
for forbidden in boot.img boot-stock.img y2bridged; do
  [ ! -e "$OUTDIR/$forbidden" ] || fail "forbidden stale output: $forbidden"
done
if compgen -G "$OUTDIR/bridge*" >/dev/null; then fail "forbidden bridge output remains"; fi

stage "Build complete"
log "output directory: $OUTDIR"
ls -la "$OUTDIR" | sed 's/^/      /'
