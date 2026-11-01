#!/usr/bin/env bash
# Builds the Khronos Vulkan CTS (deqp-vk) as an Android arm64 executable,
# the same way Mesa CI does (.gitlab-ci/container/debian/test-android.sh),
# and packs what the app needs into $OUT:
#   deqp-vk               stripped executable (shipped as libdeqp_vk.so)
#   vulkan-data.zip       CTS data files (--deqp-archive-dir)
#   vk-default.txt.gz     mustpass case list (test picker)
#   cts-version.txt       CTS tag + commit
set -euo pipefail

CTS_TAG="${CTS_TAG:?}"
NDK="${ANDROID_NDK_PATH:?}"
API="${DE_ANDROID_API:-29}"
OUT="${OUT:?}"
JOBS="${JOBS:-$(nproc)}"
WORK="${WORK:-$PWD/cts-work}"

mkdir -p "$WORK" "$OUT"
cd "$WORK"

if [ ! -d VK-GL-CTS ]; then
  git clone --depth 1 --branch "$CTS_TAG" https://github.com/KhronosGroup/VK-GL-CTS.git
fi
cd VK-GL-CTS
CTS_COMMIT="$(git rev-parse HEAD)"
python3 external/fetch_sources.py --insecure

cmake -S . -B ../build -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DDEQP_TARGET=android \
  -DDEQP_ANDROID_EXE=ON \
  -DDEQP_TARGET_TOOLCHAIN=ndk-modern \
  -DANDROID_NDK_PATH="$NDK" \
  -DANDROID_ABI=arm64-v8a \
  -DDE_ANDROID_API="$API" \
  -DDEQP_DISABLE_VK_VIDEO_TESTS=ON \
  -DSELECTED_BUILD_TARGETS=deqp-vk

ninja -C ../build -j "$JOBS" deqp-vk

BIN="$(find ../build -type f -name deqp-vk -perm -u+x | head -1)"
DATA=external/vulkancts/data/vulkan   # what add_data_dir() copies
test -n "$BIN" && test -x "$BIN"
test -d "$DATA"
file "$BIN" || true

STRIP="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip | head -1)"
"$STRIP" --strip-unneeded -o "$OUT/deqp-vk" "$BIN"

# Data files: zip from inside the module dir so paths start with "vulkan/".
rm -f "$OUT/vulkan-data.zip"
(cd "$DATA/.." && zip -q -r -9 "$OUT/vulkan-data.zip" vulkan -x 'vulkan/video/*')
unzip -l "$OUT/vulkan-data.zip" | tail -1

# Mustpass list (same concatenation Mesa CI uses).
MP=external/vulkancts/mustpass/main
: > "$OUT/vk-default.txt"
for f in $(< "$MP/vk-default.txt"); do
  cat "$MP/$f" >> "$OUT/vk-default.txt"
done
gzip -9 -f "$OUT/vk-default.txt"

printf '%s\n%s\n' "$CTS_TAG" "$CTS_COMMIT" > "$OUT/cts-version.txt"
ls -la "$OUT"
