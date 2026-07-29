#!/usr/bin/env bash
# Build the Android 4.4.2 device-side ADB daemon for the Y2's ARMv7/API-19
# userspace. The daemon is fully static so it does not depend on another
# device's vendor libraries.

set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "usage: $0 <repository-root>" >&2
    exit 2
fi

repo="$(realpath "$1")"
source_root="$repo/third_party/aosp-system-core-android-4.4.2_r1"
expected_commit="e65b7ea8801145626504c724c28aedd0e5038a28"
libhardware_root="$repo/third_party/aosp-libhardware-android-4.4.2_r1"
expected_libhardware_commit="7ccf148f5066ceb1a161f0d7a7d66f75c6e8d420"
ndk="$repo/build/toolchains/android-ndk-r25c"
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64"
cc="$toolchain/bin/armv7a-linux-androideabi19-clang"
strip="$toolchain/bin/llvm-strip"
readelf="$toolchain/bin/llvm-readelf"
output_dir="$repo/build/adb/output"
object_dir="$repo/build/adb/objects-${expected_commit:0:12}"
output="$output_dir/adbd"

fail() {
    echo "adbd build: $*" >&2
    exit 1
}

[[ -x "$cc" ]] || fail "missing NDK API-19 ARM compiler: $cc"
[[ -d "$source_root/.git" ]] || fail \
    "missing AOSP source; clone android-4.4.2_r1 into $source_root"
actual_commit="$(git -C "$source_root" rev-parse HEAD)"
[[ "$actual_commit" == "$expected_commit" ]] || fail \
    "AOSP source commit mismatch: expected $expected_commit, got $actual_commit"
git -C "$source_root" diff --ignore-space-at-eol --quiet HEAD -- || fail \
    "AOSP system/core source has non-EOL modifications: $source_root"
[[ -d "$libhardware_root/.git" ]] || fail \
    "missing matching AOSP libhardware source: $libhardware_root"
actual_libhardware_commit="$(git -C "$libhardware_root" rev-parse HEAD)"
[[ "$actual_libhardware_commit" == "$expected_libhardware_commit" ]] || fail \
    "libhardware commit mismatch: expected $expected_libhardware_commit, got $actual_libhardware_commit"
git -C "$libhardware_root" diff --ignore-space-at-eol --quiet HEAD -- || fail \
    "AOSP libhardware source has non-EOL modifications: $libhardware_root"

adb_sources=(
    adb/adb.c
    adb/backup_service.c
    adb/fdevent.c
    adb/transport.c
    adb/transport_local.c
    adb/transport_usb.c
    adb/adb_auth_client.c
    adb/sockets.c
    adb/services.c
    adb/file_sync_service.c
    adb/jdwp_service.c
    adb/framebuffer_service.c
    adb/remount_service.c
    adb/usb_linux_client.c
    adb/log_service.c
)

cutils_sources=(
    libcutils/load_file.c
    libcutils/list.c
    libcutils/partition_utils.c
    libcutils/properties.c
    libcutils/socket_inaddr_any_server.c
    libcutils/socket_local_client.c
    libcutils/socket_local_server.c
    libcutils/socket_loopback_client.c
    libcutils/socket_loopback_server.c
    libcutils/socket_network_client.c
    libcutils/sockets.c
)

mincrypt_sources=(
    libmincrypt/rsa.c
    libmincrypt/sha.c
    libmincrypt/sha256.c
)

log_sources=(
    liblog/logd_write.c
)

sources=(
    "${adb_sources[@]}"
    "${cutils_sources[@]}"
    "${mincrypt_sources[@]}"
    "${log_sources[@]}"
)

common_flags=(
    --target=armv7a-linux-androideabi19
    -march=armv7-a
    -mfloat-abi=softfp
    -Os
    -fPIE
    -ffunction-sections
    -fdata-sections
    -DADB_HOST=0
    -DANDROID
    -DHAVE_ANDROID_OS
    -DHAVE_LIBC_SYSTEM_PROPERTIES
    -DHAVE_SYS_SOCKET_H=1
    -DHAVE_SYS_UIO_H=1
    -DHAVE_STRLCPY=1
    -DANDROID_SMP=1
    -D_XOPEN_SOURCE=700
    -D_GNU_SOURCE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -include
    sys/prctl.h
    -I"$source_root/adb"
    -I"$source_root/include"
    -I"$source_root/libmincrypt"
    -I"$libhardware_root/include"
)

mkdir -p "$object_dir" "$output_dir"

objects=()
for relative_source in "${sources[@]}"; do
    source_file="$source_root/$relative_source"
    [[ -f "$source_file" ]] || fail "missing AOSP source: $relative_source"
    object_file="$object_dir/${relative_source//\//_}.o"
    "$cc" "${common_flags[@]}" -c "$source_file" -o "$object_file"
    objects+=("$object_file")
done

"$cc" --target=armv7a-linux-androideabi19 -march=armv7-a \
    -fPIE -static -Wl,--gc-sections -Wl,-z,noexecstack \
    "${objects[@]}" -o "$output.unstripped"
"$strip" --strip-unneeded "$output.unstripped" -o "$output"
chmod 0750 "$output"

elf_header="$("$readelf" -h "$output")"
grep -q 'Class:.*ELF32' <<<"$elf_header" || fail "adbd is not ELF32"
grep -q 'Machine:.*ARM' <<<"$elf_header" || fail "adbd is not ARM"
dynamic_section="$("$readelf" -d "$output" 2>/dev/null || true)"
if grep -q '(NEEDED)' <<<"$dynamic_section"; then
    fail "adbd is not fully static"
fi

sha256sum "$output"
file "$output"
