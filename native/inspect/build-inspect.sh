#!/usr/bin/env bash
# Builds the Driver inspector engine for Android arm64: rizin (disassembly, symbols, strings, analysis)
# and rz-ghidra (the Ghidra decompiler as a rizin plugin). Both are LGPL-3.0; the Ghidra decompiler
# inside rz-ghidra is Apache-2.0.
#
# rz-ghidra compiles its processor descriptions (.sla) with a tool it builds itself, which cannot run
# when cross-compiling, so the .sla files come from a build for the CI machine and the Android plugin
# is built without them.
#
# Output: $OUT/ = bin/rizin, lib/*.so (rizin libraries + the plugin), share/sleigh/*.sla|*.ldefs|...
set -euo pipefail

NDK="${ANDROID_NDK_PATH:?set ANDROID_NDK_PATH}"
API="${INSPECT_API:-30}"
RZ_TAG="${RZ_TAG:-v0.9.1}"
GH_TAG="${GH_TAG:-v0.9.0}"
W="${INSPECT_WORK:-$PWD/inspect-work}"
OUT="${INSPECT_OUT:-$PWD/inspect-out}"
JOBS="$(nproc)"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

mkdir -p "$W" "$OUT"
cd "$W"
# Runs one build step quietly; on failure prints the end of its log and the meson log if there is one.
step() {
	local name="$1"; shift
	echo "== $name"
	if ! "$@" > "$W/$name.log" 2>&1; then
		echo "!! $name failed"; grep -E -B2 -A8 "FAILED:|error:|ERROR:" "$W/$name.log" | head -n 120; tail -n 15 "$W/$name.log"
		case "$name" in *-setup) for m in "$W"/*/meson-logs/meson-log.txt; do [ -f "$m" ] && { echo "-- $m"; grep -v "Failed to guess install tag" "$m" | tail -n 60; }; done ;; esac
		exit 1
	fi
}
[ -d rizin ] || git clone -q --depth 1 -b "$RZ_TAG" https://github.com/rizinorg/rizin
[ -d rz-ghidra ] || git clone -q --depth 1 -b "$GH_TAG" --recurse-submodules --shallow-submodules https://github.com/rizinorg/rz-ghidra

# 1. rizin for the CI machine (only to build rz-ghidra's processor descriptions)
if [ ! -f host/bin/rizin ]; then
	step rz-host-setup meson setup rz-host rizin --buildtype release --prefix="$W/host" -Denable_tests=false
	step rz-host-build ninja -C rz-host install
fi

# 2. rz-ghidra for the CI machine: builds and installs the .sla files
if [ -z "$(find "$W/host" -name 'AARCH64.sla' 2>/dev/null | head -1)" ]; then
	step gh-host-setup cmake -S rz-ghidra -B gh-host -G Ninja -DCMAKE_BUILD_TYPE=Release \
		-DCMAKE_PREFIX_PATH="$W/host" -DCMAKE_INSTALL_PREFIX="$W/host" \
		-DBUILD_SLEIGH_PLUGIN=OFF
	step gh-host-build cmake --build gh-host -j "$JOBS"
	step gh-host-install cmake --install gh-host
fi
SLA="$(dirname "$(find "$W/host" -name 'AARCH64.sla' | head -1)")"
echo "processor descriptions: $SLA"

# 3. rizin for Android. Built for the app's own folder: inside the APK the program runs from the
#    app's library folder, where rizin cannot find its data next to itself, so it falls back to the
#    folder it was built for (librz/util/path.c). DriverLab unpacks the data there.
RZ_APP_PREFIX="${RZ_APP_PREFIX:-/data/user/0/com.driverlab/files/rz}"
DEST="$W/android-dest"
AP="$DEST$RZ_APP_PREFIX"
cat > android.ini <<EOF
[binaries]
c = '$TC/aarch64-linux-android$API-clang'
cpp = '$TC/aarch64-linux-android$API-clang++'
ar = '$TC/llvm-ar'
ranlib = '$TC/llvm-ranlib'
strip = '$TC/llvm-strip'
pkg-config = 'false'

[built-in options]
# Android keeps ldexpf and friends in libm; librz_type does not ask for it.
c_link_args = ['-lm']
cpp_link_args = ['-lm']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
if [ ! -f "$AP/bin/rizin" ]; then
	step rz-android-setup meson setup rz-android rizin --cross-file android.ini --buildtype release --prefix="$RZ_APP_PREFIX" \
		-Denable_tests=false -Dlocal=enabled -Dportable=true
	step rz-android-build ninja -C rz-android
	step rz-android-install meson install -C rz-android --destdir "$DEST"
fi

# 4. rz-ghidra for Android (decompiler plugin only; .sla files from step 2)
if [ -z "$(find "$AP" -name 'core_ghidra.so' 2>/dev/null | head -1)" ]; then
	step gh-android-setup cmake -S rz-ghidra -B gh-android -G Ninja -DCMAKE_BUILD_TYPE=Release \
		-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
		-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="$API" -DANDROID_STL=c++_static \
		-DCMAKE_FIND_ROOT_PATH="$AP" -DCMAKE_PREFIX_PATH="$AP" \
		-DCMAKE_INSTALL_PREFIX="$AP" \
		-DBUILD_SLASPECS=OFF -DBUILD_SLEIGH_PLUGIN=OFF
	step gh-android-build cmake --build gh-android -j "$JOBS"
	cmake --install gh-android > "$W/gh-android-install.log" 2>&1 || { echo "gh-android install had errors:"; tail -n 20 "$W/gh-android-install.log"; }
fi

# 5. Collect, two layouts:
#    $OUT/jni/   for the APK: librizin.so (the program), librz_*.so, libcore_ghidra.so (the decompiler)
#    $OUT/rz/    the data DriverLab unpacks to files/rz: share/rizin/ (types, formats, ...) and sleigh/
#    $OUT/tree/  bin/ lib/ share/ to run straight from a folder (device tests from /data/local/tmp)
rm -rf "$OUT"; mkdir -p "$OUT/jni" "$OUT/rz/sleigh" "$OUT/tree/bin" "$OUT/tree/lib"
GH_SO="$(find "$AP" -name 'core_ghidra.so' | head -1)"
cp "$AP/bin/rizin" "$OUT/jni/librizin.so"
find "$AP/lib" -maxdepth 1 -name 'librz_*.so' -type f -exec cp {} "$OUT/jni/" \;
cp "$GH_SO" "$OUT/jni/libcore_ghidra.so"
cp -r "$AP/share" "$OUT/rz/share"
rm -rf "$OUT/rz/share/rizin/fortunes" "$OUT/rz/share/man" "$OUT/rz/share/doc"
for e in sla ldefs pspec cspec; do cp "$SLA"/AARCH64*."$e" "$OUT/rz/sleigh/"; done
"$TC/llvm-strip" --strip-unneeded "$OUT"/jni/*.so
cp "$OUT/jni/librizin.so" "$OUT/tree/bin/rizin"
cp "$OUT"/jni/librz_*.so "$OUT/jni/libcore_ghidra.so" "$OUT/tree/lib/"
cp -r "$OUT/rz/share" "$OUT/rz/sleigh" "$OUT/tree/"
echo "== engine"; ls -la "$OUT/jni"; du -sh "$OUT/rz/share/rizin"/* | sort -h | tail -12; ls "$OUT/rz/sleigh"
"$TC/llvm-readelf" -d "$OUT/jni/librizin.so" "$OUT/jni/libcore_ghidra.so" | grep -E "NEEDED|RUNPATH|File:"
