#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODE=all
REPEATS=1
SERIAL="${ANDROID_SERIAL:-0123456789ABCDEF}"
TIMEOUT=600
PROBE_BYTES=32768
ANALYZE_US=100000
SKIP_BUILD=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --mode) MODE="$2"; shift 2 ;;
        --repeats) REPEATS="$2"; shift 2 ;;
        --serial) SERIAL="$2"; shift 2 ;;
        --timeout) TIMEOUT="$2"; shift 2 ;;
        --probe-bytes) PROBE_BYTES="$2"; shift 2 ;;
        --analyze-us) ANALYZE_US="$2"; shift 2 ;;
        --skip-build) SKIP_BUILD=1; shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
case "$MODE" in all|metadata|scan|decode|resource) ;; *) echo "invalid mode: $MODE" >&2; exit 2 ;; esac

command -v adb >/dev/null || { echo "adb is required" >&2; exit 1; }
CORPUS="$ROOT/tools/media/corpus"
MANIFEST="$CORPUS/manifest.json"
[[ -f "$MANIFEST" ]] || { echo "run tools/media/generate-corpus.py first" >&2; exit 1; }
python3 - "$MANIFEST" "$CORPUS" <<'PY'
import hashlib, json, pathlib, sys
manifest, corpus = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
for fixture in json.loads(manifest.read_text()).get("fixtures", []):
    path = corpus / fixture["path"]
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != fixture["sha256"]:
        raise SystemExit("fixture missing or hash mismatch: " + fixture["path"])
PY

[[ "$SKIP_BUILD" -eq 1 ]] || (cd "$ROOT" && ./gradlew assembleDebug --console=plain)
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || { echo "debug APK is missing: $APK" >&2; exit 1; }

PACKAGE=com.schulzcode.y2player.debug
COMPONENT="$PACKAGE/com.schulzcode.y2player.debug.MediaRegressionService"
DEVICE_BASE=/storage/sdcard1/Y2MediaRegression
DEVICE_CORPUS="$DEVICE_BASE/corpus"
DEVICE_RESULT="$DEVICE_BASE/device-result.json"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT="$ROOT/build/media-regression/$STAMP-$MODE"
mkdir -p "$REPORT"
ADB=(adb -s "$SERIAL")

[[ "$("${ADB[@]}" get-state)" == device ]]
"${ADB[@]}" install -r "$APK" | tee "$REPORT/install.txt"
"${ADB[@]}" shell pm clear "$PACKAGE" | grep -q Success
"${ADB[@]}" shell rm -rf "$DEVICE_BASE"
"${ADB[@]}" shell mkdir -p "$DEVICE_CORPUS"
"${ADB[@]}" push "$CORPUS/." "$DEVICE_CORPUS/"
"${ADB[@]}" logcat -c
"${ADB[@]}" shell am startservice -n "$COMPONENT" \
    -a com.schulzcode.y2player.debug.RUN_MEDIA_REGRESSION \
    --es corpus "$DEVICE_CORPUS" --es output "$DEVICE_RESULT" \
    --es mode "$MODE" --ei repeats "$REPEATS" \
    --ei probeBytes "$PROBE_BYTES" --ei analyzeUs "$ANALYZE_US"

deadline=$((SECONDS + TIMEOUT))
while ! "${ADB[@]}" shell ls "$DEVICE_RESULT" 2>/dev/null | tr -d '\r' | grep -Fxq "$DEVICE_RESULT"; do
    if (( SECONDS >= deadline )); then
        "${ADB[@]}" logcat -d -v threadtime > "$REPORT/logcat-timeout.txt"
        echo "timed out waiting for $DEVICE_RESULT" >&2
        exit 1
    fi
    sleep 1
done
sleep 0.5
"${ADB[@]}" pull "$DEVICE_RESULT" "$REPORT/device-result.json"
"${ADB[@]}" logcat -d -v threadtime > "$REPORT/logcat.txt"
python3 "$ROOT/tools/media/compare-results.py" \
    --manifest "$MANIFEST" --actual "$REPORT/device-result.json" \
    --json-report "$REPORT/report.json" --markdown-report "$REPORT/report.md"
printf 'Media regression report: %s\n' "$REPORT/report.md"
