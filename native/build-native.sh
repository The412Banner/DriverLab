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

# ---- freedreno GLES tests (system driver only), shipped as libtest_<name>.so ----
# Sources: freedreno/freedreno tests-3d + tests-2d (Rob Clark, MIT) at a pinned
# commit, plus DriverLab's own test-texturator and test-ovr-multiview.
FD_COMMIT=aa1e03a4f0881b9c3d02f23f080352419eccc9c9
FD="${FD_DIR:-$PWD/freedreno-src}"
if [ ! -d "$FD/.git" ]; then
  git clone -q https://github.com/freedreno/freedreno.git "$FD"
fi
git -C "$FD" checkout -q "$FD_COMMIT"
GL_CFLAGS=(-O2 -DBIONIC -std=gnu99 -fPIE -pie -Wno-everything -Wl,-z,max-page-size=16384
           -I"$FD/includes" -I"$FD/util")
GL_LIBS=(-lEGL -lGLESv3 -lGLESv2 -llog -lm -ldl)
built=0
while read -r t; do
  [ -z "$t" ] && continue
  n="${t#test-}"
  if [ -f "$HERE/gles/$t.c" ]; then
    src=("$HERE/gles/$t.c"); inc=(-I"$FD/tests-3d")
  elif [ -f "$FD/tests-3d/$t.c" ]; then
    src=("$FD/tests-3d/$t.c"); inc=(-I"$FD/tests-3d")
  elif [ -f "$FD/tests-2d/$t.c" ]; then
    src=("$FD/tests-2d/$t.c" "$FD/tests-2d/c2d2-shim.c"); inc=(-I"$FD/tests-2d")
  else
    echo "missing source for $t" >&2; exit 1
  fi
  "$CC" "${GL_CFLAGS[@]}" "${inc[@]}" -o "$OUT/libtest_$n.so" "${src[@]}" "$FD/util/bmp.c" "${GL_LIBS[@]}"
  "$STRIP" --strip-unneeded "$OUT/libtest_$n.so"
  built=$((built + 1))
done < "$HERE/gles/tests.txt"
echo "built $built GLES tests"
test "$built" -eq "$(grep -c . "$HERE/gles/tests.txt")"
