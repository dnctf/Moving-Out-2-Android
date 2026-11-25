#!/usr/bin/env bash
set -Eeuo pipefail

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is required}"
: "${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT is required}"
: "${BOX64_REPOSITORY:=https://github.com/ptitSeb/box64.git}"
: "${BOX64_REF:=9d0e5b1c0a3a8def60e63bdbda37ac9617d17bcb}"

OUT_DIR="${1:?Usage: build_box64_android.sh <output-directory>}"
SRC_DIR="${RUNNER_TEMP:-/tmp}/box64-src"
BUILD_DIR="${RUNNER_TEMP:-/tmp}/box64-build"
CMAKE_BIN="$ANDROID_SDK_ROOT/cmake/3.22.1/bin/cmake"
TOOLCHAIN="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"

test -x "$CMAKE_BIN"
test -f "$TOOLCHAIN"
rm -rf "$SRC_DIR" "$BUILD_DIR"
git init "$SRC_DIR"
git -C "$SRC_DIR" remote add origin "$BOX64_REPOSITORY"
git -C "$SRC_DIR" fetch --depth 1 origin "$BOX64_REF"
git -C "$SRC_DIR" checkout --detach FETCH_HEAD
python3 scripts/patch_box64_standalone.py "$SRC_DIR"

export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
"$CMAKE_BIN" \
  -S "$SRC_DIR" -B "$BUILD_DIR" \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID=ON -DTERMUX=OFF -DARM64=ON -DARM_DYNAREC=ON \
  -DNO_LIB_INSTALL=ON -DNO_CONF_INSTALL=ON \
  -DBOX64_STANDALONE_ANDROID=ON \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DPAGE16K=ON \
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
  -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
  -DCMAKE_BUILD_TYPE=Release
"$CMAKE_BIN" --build "$BUILD_DIR" --parallel 2

BOX64_BIN="$BUILD_DIR/box64"
test -s "$BOX64_BIN"
if strings "$BOX64_BIN" | grep -Fq "/data/data/com.termux"; then
  echo "::error::Standalone Box64 embeds a Termux private path."
  exit 1
fi
readelf -h "$BOX64_BIN" | grep -Eq 'Class:.*ELF64'
readelf -h "$BOX64_BIN" | grep -Eq 'Machine:.*AArch64'
readelf -h "$BOX64_BIN" | grep -Eq 'Type:.*DYN'
echo "Box64 LOAD segment alignment:"
readelf -Wl "$BOX64_BIN" | awk '/LOAD/{print "  " $NF; if ($NF != "0x4000") {print "ERROR: expected 0x4000, got " $NF > "/dev/stderr"; exit 1} found=1} END{if (!found) {print "ERROR: no LOAD segments found" > "/dev/stderr"; exit 1}}'
mkdir -p "$OUT_DIR"
install -m 0755 "$BOX64_BIN" "$OUT_DIR/libbox64.so"
echo "Built Box64 for Android API 28: $OUT_DIR/libbox64.so"
sha256sum "$OUT_DIR/libbox64.so"
