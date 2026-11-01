# DriverLab progress log

Newest first.

## 2026-11-01 — v0.1 RELEASED (feat/v0.1, release from e26682e1)

**Release:** https://github.com/The412Banner/DriverLab/releases/tag/v0.1 — tag v0.1 →
e26682e10cc3d1380d6f488d0d7209939bec1a33 (the built commit, CI 41290089892),
Latest, assets DriverLab-v0.1-e26682e1.apk (sha256 2cf7729877e999b507930cfe4027ce16b7818a414d2650b3e5d3be7680c0161e)
+ .sha256. Published with gh from the exact APK installed and checked on the Duo
(downloaded back: byte-identical).

**Device check of e26682e1:** test-clear Pass, test-composite2 Fail (exit -1),
internal export zips removed after saving to Downloads, test_results zip names
like the original (test-clear.log/.rd), no app crash.

**Open / next:** merge feat/v0.1 → main needs the user's go (backup ref first);
cosmetic: Tests header total leaves out the 68 GLES programs; landscape test list
gets short when the Run bar shows; register diff could highlight init registers;
older staged APKs in Download (62fb66cd, b0f6b21a, f0be2226) can be removed.

**Resume:** nothing running. App v0.1 installed on the Duo.

## 2026-11-01 — v0.1 build-out (feat/v0.1, building)

**Why:** compare the Qualcomm system driver with Turnip builds on the Retroid
Pocket Duo (Adreno 722): same tests, recorded GPU commands, register diffs.
Started from vauzi's adreno_722_analysis.md and the adreno-cmdstream-gather APK.

**Proven on the Duo before the app (terminal, /dev/kgsl-3d0):**
- CTS + `--deqp-vk-library-path` + HAL shim loads Banners-Turnip 710-720-Test zips.
- Turnip `FD_RD_DUMP=combine` recording works; DriverLab recorder records the
  Qualcomm driver (same register values as the original APK's captures).
- Upstream freedreno libwrap was dropped: an early return broke binder calls,
  its buffer list got corrupted on GPU_COMMAND, and it forced USE_CPU_MAP off
  (changes driver behaviour). `native/dlrec` replaces it.
- Our own CTS build (vulkan-cts-1.4.7.1, CI run 41283089769) passes smoke,
  sampler, draw and info tests on both drivers.

**Built:** 07061bcb (app + CI) → CI 41285265794 compiled first time but the
v1 signature check failed (AGP drops v1 when minSdk >= 24). 62fb66cd: explicit
apksigner v1+v2+v3 with the AOSP test key, CTS stdout line-buffered through the
preloaded libdlrec, stall watchdog → **CI 41285602566 green** (Build APK 2m17s with
Gradle cache, CTS/catalog restored from cache). APK DriverLab-v0.1-62fb66cd.apk,
37 MB, sha256 12581c845061284873e16e2a85c12abedaf7773f283d624c04d0fd2bea205d73,
com.driverlab 0.1 (versionCode 1), arm64, signer A4:0D:A8:0A…F5:DC.

**Checked on the Duo (terminal, the APK's own libraries):** smoke.triangle Pass
on the system driver with recording (18.5 KB .rd.gz, KGSL log) and on Turnip
710-720-Test through libdlvkshim with FD_RD_DUMP (3.6 KB capture).

**Staged:** /sdcard/Download/DriverLab-v0.1-62fb66cd.apk (+ .sha256), hash
matches host and device. NOT installed, app UI NOT device-tested.

**Update 11-01 09:32 UTC:** user asked to match the original zip exactly + add explanations: 68 GLES programs, libwrap-format kernel log, (i) buttons, fixes for .log naming and driver version → b0f6b21a, CI 41288964290 green, APK DriverLab-v0.1-b0f6b21a.apk (sha256 fb78f384…) staged, awaiting install OK.

**Device test 2 (b0f6b21a, user OK):** Capture set on the Qualcomm driver with
recording: 117 tests (68 GLES + 49 VK) in 4m04s; test_results zip 234 files vs
the original 236 (7 VK renamed by CTS 1.4.7.1, 3 removed); the GLES failures are
identical to the original app's logs; test-tex-layout segfaults in both. ⓘ
dialogs, driver picker, Results filters, Compare, register diff and Files tab
DEVICE-PROVEN; no app crash. Fixes after it: exit(-1)=Fail (not Crash), no
duplicate export zips in app storage → f0be2226 (CI 41289826815).

**Release candidate:** e26682e1 (release notes) CI 41290089892 green,
DriverLab-v0.1-e26682e1.apk sha256 2cf7729877e999b507930cfe4027ce16b7818a414d2650b3e5d3be7680c0161e,
staged in Download. Plan: user OK → install + quick check → `gh release create v0.1`
with exactly this APK, --target e26682e10cc3d1380d6f488d0d7209939bec1a33.

**Release:** v0.1 requested by the user; publish after the user OKs the install
and the app is checked on the device.

**Resume:** ask user to install (pm install needs their yes); then test: setup
screen, system probe, Quick check on system, import Turnip zip, Quick check on
Turnip with recording, compare + register diff, Files tab, Download/DriverLab
export. Then publish v0.1 via workflow_dispatch release=true tag=v0.1.

## Lessons learned (don't repeat these)
- The Android linker namespace for executables only allows libraries under
  /data: test from /data paths, not proot /tmp.
- Never initialise an LD_PRELOAD library from mmap(): the allocator calls mmap
  while setting itself up; malloc from there returns untagged pointers that
  abort at free().
- memfd_create needs Android API 30 in bionic: the CTS needs DE_ANDROID_API >= 30.
