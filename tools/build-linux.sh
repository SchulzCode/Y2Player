#!/usr/bin/env bash
# Native Linux entry point for the Y2Player development and firmware builds.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
    cat <<'EOF'
Usage: tools/build-linux.sh <command>

Commands:
  native             Build the pinned API-19 ARMv7 native runtime
  app                Run unit tests, lint, and build the debug APK
  release            Build and verify the signed release APK
  adb-boot           Build the secure development boot image
  system-image       Build the signed APK and quick system-only image
  firmware-validate  Validate system-image and full-package inputs
  firmware           Build the complete Innioasis Updater firmware package
  all                Build the native runtime and complete firmware package
EOF
}

fail() {
    printf 'build-linux: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

sdk_dir() {
    local configured=""
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        configured="$ANDROID_SDK_ROOT"
    elif [[ -n "${ANDROID_HOME:-}" ]]; then
        configured="$ANDROID_HOME"
    elif [[ -f local.properties ]]; then
        configured="$(sed -n 's/^sdk\.dir=//p' local.properties | head -n 1)"
        configured="${configured//\\:/:}"
        configured="${configured//\\\\/\\}"
    fi
    [[ -n "$configured" && -d "$configured" ]] || return 1
    printf '%s\n' "$configured"
}

check_android_sdk() {
    local sdk
    sdk="$(sdk_dir)" || fail \
        "Android SDK not found. Set ANDROID_SDK_ROOT or put a Linux sdk.dir in local.properties."
    [[ -d "$sdk/platforms/android-36" ]] || fail "Android SDK Platform 36 is missing under $sdk"
    [[ -d "$sdk/build-tools" ]] || fail "Android SDK Build Tools are missing under $sdk"
}

download_verified() {
    local destination="$1" url="$2" expected="$3" temporary
    mkdir -p "$(dirname "$destination")"
    if [[ -f "$destination" ]] && printf '%s  %s\n' "$expected" "$destination" | sha256sum --check --status; then
        return
    fi
    temporary="${destination}.partial"
    rm -f -- "$temporary"
    curl --location --fail --retry 3 --retry-delay 2 --output "$temporary" "$url"
    printf '%s  %s\n' "$expected" "$temporary" | sha256sum --check --status || {
        rm -f -- "$temporary"
        fail "SHA-256 mismatch for $destination"
    }
    mv -- "$temporary" "$destination"
}

build_native() {
    for command in curl unzip tar make patch sha256sum; do require_command "$command"; done
    download_verified \
        "$ROOT/build/downloads/ffmpeg-8.1.2.tar.xz" \
        "https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.xz" \
        "464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c"
    download_verified \
        "$ROOT/build/downloads/android-ndk-r25c-linux.zip" \
        "https://dl.google.com/android/repository/android-ndk-r25c-linux.zip" \
        "769ee342ea75f80619d985c2da990c48b3d8eaf45f48783a2d48870d04b46108"
    bash tools/native/build-ffmpeg.sh "$ROOT"
}

build_app() {
    require_command java
    check_android_sdk
    ./gradlew testDebugUnitTest lintDebug assembleDebug
}

build_release() {
    local sdk apk apksigner
    require_command java
    check_android_sdk
    [[ -f keystore.properties ]] || fail \
        "keystore.properties is missing; copy and configure keystore.properties.example"
    ./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleRelease
    apk="$ROOT/app/build/outputs/apk/release/app-release.apk"
    [[ -f "$apk" ]] || fail "release APK was not produced: $apk"
    sdk="$(sdk_dir)"
    apksigner="$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner -print | sort -V | tail -n 1)"
    [[ -x "$apksigner" ]] || fail "apksigner was not found in Android Build Tools"
    "$apksigner" verify --verbose --print-certs "$apk"
    mkdir -p "$ROOT/dist/firmware"
    cp -- "$apk" "$ROOT/dist/firmware/Y2Player.apk"
    sha256sum "$ROOT/dist/firmware/Y2Player.apk" > "$ROOT/dist/firmware/Y2Player.apk.sha256"
    printf 'Release APK: %s\n' "$ROOT/dist/firmware/Y2Player.apk"
}

build_adb_boot() {
    local stock_boot="$ROOT/OriginalFirmware/boot.img"
    local adbd="$ROOT/build/adb/output/adbd"
    for command in git curl unzip tar make patch sha256sum python3; do require_command "$command"; done
    [[ -f "$stock_boot" ]] || fail "stock boot image is missing: $stock_boot"
    build_native
    bash tools/adb/fetch-aosp-sources.sh "$ROOT"
    bash tools/adb/build-adbd.sh "$ROOT"
    python3 tools/adb/build_adb_boot.py \
        --stock-boot "$stock_boot" \
        --adbd "$adbd" \
        --output-dir "$ROOT/out/boot-adb"
}

validate_firmware() {
    bash tools/firmware/build_firmware.sh --validate-only
}

build_system_image() {
    local apk native build_id commit dirty metadata
    build_release
    apk="$ROOT/app/build/outputs/apk/release/app-release.apk"
    native="$ROOT/app/src/main/jniLibs/armeabi-v7a/liby2audio.so"
    [[ -f "$apk" ]] || fail "debug APK was not produced: $apk"
    [[ -f "$native" ]] || fail "native runtime is missing; run '$0 native' first"
    commit="$(git rev-parse --short=12 HEAD 2>/dev/null || printf unknown)"
    dirty=""
    [[ -z "$(git status --porcelain 2>/dev/null)" ]] || dirty="-dirty"
    build_id="$(date -u +%Y%m%d-%H%M%S)-${commit}${dirty}"
    metadata="$ROOT/app/build/outputs/apk/release/output-metadata.json"
    [[ -f "$metadata" ]] || metadata=""
    bash tools/firmware/build_firmware.sh \
        --apk "$apk" \
        --native-lib "$native" \
        --apk-metadata "$metadata" \
        --app-build-command "./gradlew clean testDebugUnitTest lintDebug assembleRelease" \
        --app-tests-status "passed" \
        --build-id "$build_id"
}

build_firmware() {
    build_system_image
    python3 tools/firmware/build_full_package.py
}

validate_full_firmware() {
    validate_firmware
    python3 tools/firmware/build_full_package.py \
        --system-image "$ROOT/OriginalFirmware/system.img" --validate-only
}

case "${1:-}" in
    native) build_native ;;
    app) build_app ;;
    release) build_release ;;
    adb-boot) build_adb_boot ;;
    system-image) build_system_image ;;
    firmware-validate) validate_full_firmware ;;
    firmware) build_firmware ;;
    all) build_native; build_firmware ;;
    -h|--help|help) usage ;;
    *) usage >&2; exit 2 ;;
esac
