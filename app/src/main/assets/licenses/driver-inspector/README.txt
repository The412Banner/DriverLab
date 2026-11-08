DriverLab driver inspector: third-party code
============================================

The driver inspector runs rizin with the rz-ghidra decompiler plugin. Both are
built from unmodified upstream sources for Android arm64 (NDK 29) by
native/inspect/build-inspect.sh in https://github.com/The412Banner/DriverLab
and ship in the APK as lib/arm64-v8a/librizin.so, librz_*.so and
libcore_ghidra.so, with rizin's data files and the AArch64 Sleigh files in
assets/inspect/rz-data.zip. They run as a separate program that DriverLab talks
to over a pipe.

Source code: the upstream sources at the versions below, plus the build script
above, are the complete source of these binaries.

rizin 0.9.1
  https://github.com/rizinorg/rizin/tree/v0.9.1
  LGPL-3.0-only (rizin/COPYING.LESSER.txt, with the GPL-3.0 text it refers to in
  rizin/COPYING.txt). Some files are under other licenses, named in each file's
  SPDX tag and in rizin's REUSE.toml; their texts are in rizin/LICENSES/.

rz-ghidra 0.9.0
  https://github.com/rizinorg/rz-ghidra/tree/v0.9.0
  LGPL-3.0-or-later (rz-ghidra/COPYING.txt).

Ghidra decompiler (part of rz-ghidra, rizinorg/ghidra 3e5d774389b3)
  https://github.com/rizinorg/ghidra/tree/3e5d774389b3c4ff1c089ddf822f071c10f548a8
  Apache-2.0 (ghidra/LICENSE.txt, ghidra/NOTICE.txt).

pugixml (part of rz-ghidra, fdf029575336)
  https://github.com/zeux/pugixml
  MIT (pugixml/LICENSE.txt).

Libraries that rizin's build bundles (meson subprojects), linked into librz_*.so.
License texts in libraries/:

  capstone (next, 3df6ff015fb6)    BSD-3-Clause; parts under the LLVM license
                                   https://github.com/capstone-engine/capstone
  zlib 1.3.2                       Zlib                https://zlib.net
  lz4 1.10.0                       BSD-2-Clause        https://github.com/lz4/lz4
  zstd 1.5.7                       BSD-3-Clause        https://github.com/facebook/zstd
  xz / liblzma 5.8.3               0BSD                https://tukaani.org/xz/
  libzip 1.11.4                    BSD-3-Clause        https://libzip.org
  PCRE2 10.47                      BSD-3-Clause WITH PCRE2-exception
                                   https://github.com/PCRE2Project/pcre2
  tree-sitter 0.26.9               MIT                 https://github.com/tree-sitter/tree-sitter
  rizin-grammar-c (6a9d542145c2)   MIT                 https://github.com/rizinorg/rizin-grammar-c
  rz-libdemangle (e633061e9012)    LGPL-3.0-only and MIT
                                   https://github.com/rizinorg/rz-libdemangle
  libmspack 1.11                   LGPL-2.1            https://github.com/kyz/libmspack
  Berkeley SoftFloat 3e (rizinorg/softfloat 537d18e71a51)
                                   BSD-3-Clause        https://github.com/rizinorg/softfloat
  Zydis 4.1.0 with Zycore          MIT                 https://github.com/zyantific/zydis
  BLAKE3 (2de3cb1a21d0)            CC0-1.0 or Apache-2.0
                                   https://github.com/BLAKE3-team/BLAKE3
  BLAKE2 (ed1974ea8343)            CC0-1.0             https://github.com/BLAKE2/BLAKE2
  xxHash (in rizin's tree)         BSD-2-Clause
  yxml (in rizin's tree)           MIT
  Nettle parts (in rizin's tree)   LGPL-3.0-only (per-file SPDX tags; text in
                                   rizin/LICENSES/)
  rizin-shell-parser (in rizin's tree)
                                   LGPL-3.0-only
