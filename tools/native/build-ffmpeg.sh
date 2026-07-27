#!/usr/bin/env bash
# Reproducible Android API-19 ARMv7 FFmpeg and JNI build. This script is called
# by tools/build-native-audio.ps1 after the source archives are hash-verified.

set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "usage: $0 <repository-root>" >&2
    exit 2
fi

repo="$(realpath "$1")"
cache_root="${Y2_NATIVE_CACHE:-$HOME/.cache/y2player}"
ndk="$cache_root/android-ndk-r25c"
patch_dir="$repo/tools/native/patches"

# Ordered, hashed patch set applied to the verified FFmpeg tarball.
#
# The archive stays byte-for-byte the upstream release and is still hash-checked;
# every deviation from it lives in tools/native/patches as a reviewable diff.
# LC_ALL=C so the order is byte order, not locale order, and matches the Gradle
# side that recomputes the same identity.
patch_files=()
if [[ -d "$patch_dir" ]]; then
    while IFS= read -r -d '' patch_file; do
        patch_files+=("$patch_file")
    done < <(find "$patch_dir" -maxdepth 1 -type f -name '*.patch' -print0 | LC_ALL=C sort -z)
fi

if [[ ${#patch_files[@]} -gt 0 ]]; then
    patch_identity="$(sha256sum "${patch_files[@]}" | awk '{ print $1 }' | sha256sum | cut -d' ' -f1)"
else
    patch_identity="none"
fi

# The identity is part of the extracted path, so changing or adding a patch can
# never silently reuse a differently-patched cache.
ffmpeg_source="$cache_root/ffmpeg-src/ffmpeg-8.1.2-${patch_identity:0:16}"
ffmpeg_archive="$repo/build/downloads/ffmpeg-8.1.2.tar.xz"
ndk_archive="$repo/build/downloads/android-ndk-r25c-linux.zip"
ffmpeg_sha256="464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c"
ndk_sha256="769ee342ea75f80619d985c2da990c48b3d8eaf45f48783a2d48870d04b46108"
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64"
api=19
abi=armeabi-v7a

# NEON is off by default because it has not been verified on the target unit.
#
# MT6582 is a Cortex-A7, which normally carries NEON, and FFmpeg dispatches it at
# runtime from /proc/cpuinfo, so the C paths remain as a fallback. It is still
# opt-in rather than assumed, because "the binary now uses NEON" is not the same
# claim as "this device got faster", and an illegal instruction on a unit without
# it is a hard failure with no recovery.
#
# To evaluate it:
#   adb shell grep -i Features /proc/cpuinfo      # look for "neon"
#   Y2_ENABLE_NEON=1 tools/build-native-audio.ps1 # or export before this script
#   measure: audio-thread CPU on MP3/FLAC/AAC, crossfade CPU, liby2audio.so size
#
# Toggling this changes the configure identity below, so FFmpeg rebuilds and the
# Gradle stamp check will refuse the stale binary until it is rebuilt.
enable_neon="${Y2_ENABLE_NEON:-0}"
if [[ "$enable_neon" == "1" ]]; then
    # Explicit rather than an empty array: FFmpeg still dispatches at runtime, and
    # --cpu=armv7-a above stays, so a unit without NEON keeps the C paths.
    neon_flags=("--enable-neon")
else
    neon_flags=("--disable-neon")
fi

fail() {
    echo "native build: $*" >&2
    exit 1
}

verify_hash() {
    local file="$1"
    local expected="$2"
    [[ -f "$file" ]] || fail "missing verified archive: $file"
    echo "$expected  $file" | sha256sum --check --status ||
        fail "SHA-256 mismatch: $file"
}

verify_hash "$ffmpeg_archive" "$ffmpeg_sha256"
verify_hash "$ndk_archive" "$ndk_sha256"
mkdir -p "$cache_root"

if [[ ! -x "$toolchain/bin/clang-14" ]]; then
    [[ ! -e "$ndk" ]] || fail "incomplete NDK cache; remove $ndk and retry"
    ndk_stage="$(mktemp -d "$cache_root/ndk-r25c.XXXXXX")"
    trap 'rm -rf -- "$ndk_stage"' EXIT
    unzip -q "$ndk_archive" -d "$ndk_stage"
    mv "$ndk_stage/android-ndk-r25c" "$ndk"
    rmdir "$ndk_stage"
    trap - EXIT
fi

[[ -x "$toolchain/bin/armv7a-linux-androideabi${api}-clang" ]] ||
    fail "NDK r25c API-$api compiler is unavailable"
[[ -x "$toolchain/bin/llvm-ar" ]] || fail "NDK llvm-ar is unavailable"

if [[ ! -x "$ffmpeg_source/configure" ]]; then
    [[ ! -e "$ffmpeg_source" ]] || fail "incomplete FFmpeg source cache: $ffmpeg_source"
    mkdir -p "$cache_root/ffmpeg-src"
    source_stage="$(mktemp -d "$cache_root/ffmpeg-src/ffmpeg-8.1.2.XXXXXX")"
    trap 'rm -rf -- "$source_stage"' EXIT
    tar -xJf "$ffmpeg_archive" -C "$source_stage"
    for patch_file in "${patch_files[@]}"; do
        echo "applying $(basename "$patch_file")"
        patch -p1 --forward --silent -d "$source_stage/ffmpeg-8.1.2" < "$patch_file" ||
            fail "failed to apply $(basename "$patch_file")"
    done
    mv "$source_stage/ffmpeg-8.1.2" "$ffmpeg_source"
    rmdir "$source_stage"
    trap - EXIT
fi

configure_flags=(
    "--target-os=android"
    "--arch=arm"
    "--cpu=armv7-a"
    "--enable-cross-compile"
    "--cc=$toolchain/bin/armv7a-linux-androideabi${api}-clang"
    "--cxx=$toolchain/bin/armv7a-linux-androideabi${api}-clang++"
    "--ar=$toolchain/bin/llvm-ar"
    "--nm=$toolchain/bin/llvm-nm"
    "--ranlib=$toolchain/bin/llvm-ranlib"
    "--strip=$toolchain/bin/llvm-strip"
    "--sysroot=$toolchain/sysroot"
    "--disable-everything"
    "--disable-autodetect"
    "--disable-programs"
    "--disable-doc"
    "--disable-debug"
    "--disable-network"
    "--disable-avdevice"
    "--disable-avfilter"
    "--disable-swscale"
    "--disable-encoders"
    "--disable-muxers"
    "--disable-iconv"
    "--disable-zlib"
    "--disable-bzlib"
    "--disable-lzma"
    "--disable-symver"
    "${neon_flags[@]}"
    "--enable-small"
    "--enable-static"
    "--disable-shared"
    "--enable-pic"
    "--enable-avformat"
    "--enable-avcodec"
    "--enable-avutil"
    "--enable-swresample"
    "--enable-protocol=file"
    "--enable-demuxer=aac,aiff,flac,mov,mp3,ogg,wav"
    "--enable-decoder=aac,alac,flac,mp3,opus,vorbis,pcm_f32le,pcm_f64le,pcm_s8,pcm_s16be,pcm_s16le,pcm_s24be,pcm_s24le,pcm_s32be,pcm_s32le,pcm_u8"
    "--enable-parser=aac,flac,mpegaudio,opus,vorbis"
    "--extra-cflags=-Os"
)

configure_identity="$({
    printf '%s\n' "ffmpeg=$ffmpeg_sha256" "ndk=$ndk_sha256" "api=$api" "abi=$abi" \
        "patches=$patch_identity"
    printf '%s\n' "${configure_flags[@]}"
} | sha256sum | cut -d' ' -f1)"

build_dir="$cache_root/ffmpeg-build-$configure_identity"
install_dir="$repo/build/native/ffmpeg/$abi"
stamp="$install_dir/.y2-ffmpeg-config"

if [[ ! -f "$stamp" ]] || [[ "$(cat "$stamp")" != "$configure_identity" ]] ||
   [[ ! -f "$install_dir/lib/libavformat.a" ]] ||
   [[ ! -f "$install_dir/lib/libavcodec.a" ]] ||
   [[ ! -f "$install_dir/lib/libavutil.a" ]] ||
   [[ ! -f "$install_dir/lib/libswresample.a" ]]; then
    mkdir -p "$build_dir" "$install_dir"
    pushd "$build_dir" >/dev/null
    "$ffmpeg_source/configure" "--prefix=$install_dir" "${configure_flags[@]}"
    make -j"$(nproc)"
    make install
    popd >/dev/null
    printf '%s\n' "$configure_identity" > "$stamp"
fi

native_out="$repo/build/native/output/$abi"
package_out="$repo/app/src/main/jniLibs/$abi"
mkdir -p "$native_out" "$package_out"

"$toolchain/bin/armv7a-linux-androideabi${api}-clang" \
    -shared -fPIC -Os -ffunction-sections -fdata-sections \
    -fvisibility=hidden \
    -Wl,--no-undefined -Wl,--exclude-libs,ALL \
    -Wl,-soname,liby2audio.so \
    -Wl,--version-script,"$repo/app/src/main/c/y2audio.map" \
    -I"$install_dir/include" \
    "$repo/app/src/main/c/y2audio.c" \
    -Wl,--start-group -Wl,--whole-archive \
    "$install_dir/lib/libavformat.a" \
    "$install_dir/lib/libavcodec.a" \
    "$install_dir/lib/libswresample.a" \
    "$install_dir/lib/libavutil.a" \
    -Wl,--no-whole-archive -Wl,--end-group \
    -llog -ldl -lm -latomic -pthread \
    -o "$native_out/liby2audio.so.tmp"

"$toolchain/bin/llvm-strip" --strip-unneeded "$native_out/liby2audio.so.tmp"
mv "$native_out/liby2audio.so.tmp" "$native_out/liby2audio.so"
install -m 0644 "$native_out/liby2audio.so" "$package_out/liby2audio.so"

file_text="$(file "$native_out/liby2audio.so")"
[[ "$file_text" == *"ELF 32-bit LSB shared object, ARM"* ]] ||
    fail "unexpected ELF identity: $file_text"

android_notes="$($toolchain/bin/llvm-readelf --notes "$native_out/liby2audio.so")"
grep -q 'description data: 13 00 00 00' <<<"$android_notes" ||
    fail "native artifact does not declare Android API 19"

needed="$($toolchain/bin/llvm-readelf -d "$native_out/liby2audio.so" | awk '/NEEDED/ { print $NF }' | tr -d '[]')"
if grep -Eq '(^|[[:space:]])libav(format|codec|util|swresample)\.so($|[[:space:]])' <<<"$needed"; then
    fail "FFmpeg was dynamically linked: $needed"
fi

if $toolchain/bin/llvm-readelf --dyn-syms "$native_out/liby2audio.so" |
   grep -Eq 'GLIBC_|GLIBCXX_|CXXABI_'; then
    fail "host C/C++ runtime symbol leaked into Android binary"
fi

report="$repo/build/native/native-build-report.txt"
{
    echo "Y2Player native audio build"
    echo "FFmpeg version: 8.1.2"
    echo "FFmpeg SHA-256: $ffmpeg_sha256"
    echo "FFmpeg signing key: FCF986EA15E6E293A5644F10B4322F04D67658D8"
    echo "NDK version: 25.2.9519653"
    echo "NDK archive SHA-256: $ndk_sha256"
    echo "Target API: $api"
    echo "ABI: $abi"
    echo "NEON enabled: $enable_neon (runtime-dispatched; C fallback retained)"
    echo "FFmpeg patches: ${#patch_files[@]}"
    for patch_file in "${patch_files[@]}"; do
        echo "  $(basename "$patch_file") $(sha256sum "$patch_file" | cut -d' ' -f1)"
    done
    echo "Configure identity: $configure_identity"
    echo "Configure flags:"
    printf '  %s\n' "${configure_flags[@]}"
    echo "Artifact SHA-256: $(sha256sum "$native_out/liby2audio.so" | cut -d' ' -f1)"
    echo "Artifact size: $(stat -c '%s' "$native_out/liby2audio.so") bytes"
    echo "File identity: $file_text"
    echo "Android ABI note: API 19 verified"
    echo "Imported shared libraries:"
    printf '  %s\n' $needed
    echo "Undefined platform symbols:"
    "$toolchain/bin/llvm-nm" -D --undefined-only "$native_out/liby2audio.so" | sed 's/^/  /'
} > "$report"

cat "$report"

# The APK consumes a prebuilt liby2audio.so, so a source change with no rebuild
# would ship a silently stale binary. This records what the artifact was built
# from; app/build.gradle.kts recomputes the same value and fails the build if it
# no longer matches. Everything that can change the output is covered: the JNI
# source, its version script, and this script (which carries the FFmpeg and NDK
# hashes, every configure flag and every compiler flag as literals).
{
    sha256sum "$repo/app/src/main/c/y2audio.c"
    sha256sum "$repo/app/src/main/c/y2audio.map"
    sha256sum "${BASH_SOURCE[0]}"
    if [[ ${#patch_files[@]} -gt 0 ]]; then
        sha256sum "${patch_files[@]}"
    fi
} | awk '{ print $1 }' | sha256sum | cut -d' ' -f1 > "$package_out/liby2audio.stamp"

echo "native source stamp: $(cat "$package_out/liby2audio.stamp")"
