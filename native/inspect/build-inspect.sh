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
		echo "!! $name failed"; tail -n 80 "$W/$name.log"
		for m in "$W"/*/meson-logs/meson-log.txt; do [ -f "$m" ] && { echo "-- $m"; tail -n 60 "$m"; }; done
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

# 3. rizin for Android (shared libraries, rpath relative to the executable)
cat > android.ini <<EOF
[binaries]
c = '$TC/aarch64-linux-android$API-clang'
cpp = '$TC/aarch64-linux-android$API-clang++'
ar = '$TC/llvm-ar'
ranlib = '$TC/llvm-ranlib'
strip = '$TC/llvm-strip'
pkg-config = 'false'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
if [ ! -f android/bin/rizin ]; then
	step rz-android-setup meson setup rz-android rizin --cross-file android.ini --buildtype release --prefix="$W/android" \
		-Denable_tests=false -Dlocal=enabled -Dportable=true
	step rz-android-build ninja -C rz-android install
fi

# 4. rz-ghidra for Android (decompiler plugin only; .sla files from step 2)
if [ -z "$(find "$W/android" -name 'core_ghidra.so' 2>/dev/null | head -1)" ]; then
	step gh-android-setup cmake -S rz-ghidra -B gh-android -G Ninja -DCMAKE_BUILD_TYPE=Release \
		-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
		-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="$API" -DANDROID_STL=c++_static \
		-DCMAKE_FIND_ROOT_PATH="$W/android" -DCMAKE_PREFIX_PATH="$W/android" \
		-DCMAKE_INSTALL_PREFIX="$W/android" \
		-DBUILD_SLASPECS=OFF -DBUILD_SLEIGH_PLUGIN=OFF
	step gh-android-build cmake --build gh-android -j "$JOBS"
	cmake --install gh-android > "$W/gh-android-install.log" 2>&1 || { echo "gh-android install had errors:"; tail -n 20 "$W/gh-android-install.log"; }
fi

# 5. Collect
rm -rf "$OUT"; mkdir -p "$OUT/bin" "$OUT/lib" "$OUT/share/sleigh"
cp "$W/android/bin/rizin" "$OUT/bin/"
find "$W/android" -name '*.so*' -type f -exec cp {} "$OUT/lib/" \;
cp "$SLA"/AARCH64*.sla "$SLA"/AARCH64*.ldefs "$SLA"/AARCH64*.pspec "$SLA"/AARCH64*.cspec "$OUT/share/sleigh/" 2>/dev/null || true
cp "$SLA"/ARM*.sla "$SLA"/ARM*.ldefs "$SLA"/ARM*.pspec "$SLA"/ARM*.cspec "$OUT/share/sleigh/" 2>/dev/null || true
"$TC/llvm-strip" "$OUT/bin/rizin" "$OUT"/lib/*.so* 2>/dev/null || true
echo "== engine"; ls -la "$OUT/bin" "$OUT/lib" | head -60; ls "$OUT/share/sleigh" | head -40
file "$OUT/bin/rizin" "$OUT"/lib/core_ghidra.so 2>/dev/null || true
