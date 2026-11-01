#!/usr/bin/env bash
# Builds DriverLab's two small native libraries with the Android NDK:
#   libdlvkshim.so  loads a custom Vulkan driver for the CTS (--deqp-vk-library-path)
#   libdlrec.so     records system-driver GPU submits as .rd.gz (LD_PRELOAD)
set -euo pipefail
NDK="${ANDROID_NDK_PATH:?}"
API="${DE_ANDROID_API:-30}"
OUT="${OUT:?}"
HERE="$(cd "$(dirname "$0")" && pwd)"
TC="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"
CC="$TC/aarch64-linux-android${API}-clang"
STRIP="$TC/llvm-strip"
mkdir -p "$OUT"
CFLAGS=(-O2 -fPIC -shared -fvisibility=hidden -Wall -Wextra -Wno-unused-parameter -Werror
        -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1)
"$CC" "${CFLAGS[@]}" -o "$OUT/libdlvkshim.so" "$HERE/vkshim/vkshim.c" -ldl
"$CC" "${CFLAGS[@]}" -o "$OUT/libdlrec.so" "$HERE/dlrec/dlrec.c" -lz
"$STRIP" --strip-unneeded "$OUT/libdlvkshim.so" "$OUT/libdlrec.so"
for f in "$OUT"/*.so; do
  echo "== $f"
  "$TC/llvm-readelf" -d "$f" | grep NEEDED
  "$TC/llvm-readelf" --dyn-syms -W "$f" | awk '$5=="GLOBAL" && $7!="UND"{print "  export", $8}'
done
