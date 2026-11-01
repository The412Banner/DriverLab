# DriverLab progress log

Newest first.

## 2026-11-01 — pre2 (b792de2a) INSTALLED on the Pocket FIT (user: "install it")

base.apk sha256 d240ab7b… == staged, lastUpdateTime 14:55:11 local. Opens. Found my earlier
"restore" had put "Pocket FIT own Qualcomm driver (test)" in use (Drivers list was scrolled,
tapped the wrong card's Use) → fixed: System driver in use; verified in shared_prefs
settings.xml: driverId=system, record=true, jobs=1. Quick check with recording: 152 tests in
24.9 s, 123/2/27 (same as before), 152 captures, no crash-buffer lines, no FATAL/ANR.
Peer root-9c told + "done". Lesson: verify restored settings from settings.xml, not a tap.

## 2026-11-01 — read-error fix b792de2a, v0.1.2-pre2 published (feat/test-tools)

Fix: output IOException in runBatch / runGles / feature check → current test Crash with the
reason, process ended if alive, batch restarts, run continues; verdict text shows "not
reported" next to "missing". CI 41343599390 GREEN (headSha b792de2a). APK
DriverLab-v0.1.1-b792de2a.apk sha256 d240ab7bf81d7c9d33bde65720758a61ef2259f229db21a30cdf2b0e6e4c7191,
staged /sdcard/Download/DriverLab-test-tools-b792de2a.apk (host == device), NOT installed.
User: "publish it as test build 2 when it's green" →
https://github.com/The412Banner/DriverLab/releases/tag/v0.1.2-pre2 — tag → b792de2af4f65f30d0f3ff62fa62324623c9003f,
Pre-release (v0.1.1 stays Latest), downloaded back = same hash. NOT device-tested (the fix
can't be forced; it shows only if the read error happens again).
Next: user tests on the Fold; merge feat/test-tools → main = user's go (backup ref first);
stable 0.1.2 = user's word. Trace replay research done (stage 0 not started).

## 2026-11-01 — DEVICE TEST 819d7867 on the Pocket FIT (v0.1.2-pre1 published first; user yes to both)

Installed 819d7867 (base.apk sha256 a755c9fb… == staged), 14:23 local. Peer root-9c held; "done" sent.
1. Game features: System driver check = 131 extensions / 213 feature values; verdicts match
   the saved report checked independently (DXVK: VK_KHR_maintenance5/6 missing; SM 6.6:
   no compute_shader_derivatives ext; maintenance5/6 features "not reported" because their
   extension is absent). Compare with imported own driver: two rows per profile, detail table
   ✓/✗/? per driver. "Select its tests" SM 6.6 → exactly 2,986 selected, lands on Tests. PASS
2. Full 3,215,124-name caselist (430 MB) → "3,215,124 tests selected from 3,215,124 names",
   ~11 s incl. polling, Java heap ~56 MB. One-line "dEQP-VK.glsl.atomic_operations.*" → 1,040. PASS
3. Test programs at once: Quick check 8 vs 1 = identical (123/2/27, same 2 failures:
   compute.pipeline.basic.undefined_values, vec2_nclamp_nan_component); atomics 1,040:
   2.2 s (8) vs 4.0 s (1), identical; Rendering basics 16,300: 33.3 s (8) vs 2 m 33 s (1)
   = ~4.6x, identical results 9,296/328/6,676 and IDENTICAL failure sets (328). PASS
   BUG: one 1-at-a-time Rendering basics run stopped at 46% "Run error: read interrupted"
   (batch 31 output ends mid-test; no crash/kill in logcat; the test passes elsewhere; a repeat
   run finished). Cause not found; an output read error must not end the run → fix.
   Not seen: the "N at once" label (runs finished between my polls).
4. Results: search (atomics → 2, Pocket → 1), day header "Today · 13", With problems (6),
   by-group view with group counts, collapse/expand, sort menu (Slowest first), List view,
   test prev/next arrows step in the shown order, Back returns to the run. PASS
5. Find a setting that fixes it: Turnip r4 imported ("Turnip Adreno (TM) 750"); Turnip Quick
   check 140/3 fail (info extension tests) / 9 NS; Turnip Rendering basics 10,167 pass,
   0 fail (Qualcomm 328 fail). Ran the finder on info.device_memory_budget_multi_instance:
   18 runs, all Fail, "No single setting makes it pass." PASS (flow); "Use for this driver"
   not exercised (no fixing setting).
No crash-buffer entries, no FATAL/ANR, app pid unchanged all test. Restored: 1 at a time,
recording ON, System driver in use; Termux back.
Small fix: verdict shows only "N required missing" when items are also "not reported".

## 2026-11-01 — merged fix/blob-driver-zips, RELEASED v0.1.1 (main)

User: "merge it and make it 0.1.1". Backup ref refs/backup/20261101/main-before-blob-driver-zips
= 29c782031a10d40d74ce95fb703844da1c2842e4 pushed + verified first; main fast-forwarded
29c7820 → 4533e5b2 (tree == device-checked bdaaf3ba + PROGRESS_LOG). Release cut 8405f0ce
"Release 0.1.1 (versionCode 2)" (versionName 0.1.1, release-notes/v0.1.1.md).
Release workflow 41337755265 (release=true, tag=v0.1.1, headSha 8405f0ce) success;
https://github.com/The412Banner/DriverLab/releases/tag/v0.1.1 — Latest, tag →
8405f0ceee479a7403094b4ea0502ab0732fce72, asset DriverLab-v0.1.1-8405f0ce.apk sha256
527bd370e2b2e8ddd95014c79a07ec2276d839cbc664b457b77d9595d1a39cb2 (== .sha256 file),
manifest versionName 0.1.1, notes == release-notes/v0.1.1.md. The release APK itself is
not installed anywhere (same code as device-checked bdaaf3ba + version numbers).
fix/blob-driver-zips kept (not deleted).
## 2026-11-01 — feat/test-tools: 5 features, CI GREEN 819d7867 (NOT device-tested)

First compile 41340346445 FAILED: my Results rewrite deleted shareSession /
saveSessionToDownloads (they sat between ResultsScreen and SessionScreen) → restored
(819d7867), checked no other function was lost. CI 41340483938 GREEN (headSha 819d7867;
warnings only the old ones). APK DriverLab-v0.1.1-819d7867.apk sha256
a755c9fb73703fd43aeca3b1de7edb0c15781a8d30f2822f9d1a74ed8ae7ffae STAGED
/sdcard/Download/DriverLab-test-tools-819d7867.apk (host == device). NOT installed.


User: "do 1 and 2, and start the research for 4" + Hugo: remove the 500k list limit,
add a multithread option; user: make results more fluid and organised.
- 77cb5f6 test lists without limit (stream → per-top temp files → tree Lookup cursor
  → bit marks → fold to group entries); Settings › Test programs at once (workers on a
  queue, per-batch temp files + watchdog, results under a lock, Stop kills all;
  recording = 1).
- 82eb389 Find a setting that fixes it (Turnip): TU_DEBUG/IR3_SHADER_DEBUG list from
  Mesa docs at main 01bab930; one tap adds a setting to the driver.
- c09ab19 Game features: DXVK f8637768 + VKD3D-Proton 8293130e requirements (from
  their code), one CTS run of info + 192 features tests per driver, compare two drivers,
  focused test sets per profile (sizes checked in the catalog).
- c2a2b13 Results: search, day headers, by-group view, sorting, off-main-thread
  filtering, prev/next arrows on a test.
Research for "record a game, replay per driver" (GFXReconstruct) running as a
background agent → report in the session scratchpad.
Release 0.1.1 (run 41337755265) still building at this point.

## 2026-11-01 — bdaaf3ba device-checked, v0.1.1-pre3 published (fix/blob-driver-zips)

User: install + quick check; publish pre3 only after the check passes. Installed
bdaaf3ba (base.apk sha256 d1dc7f1a… == staged). Checked: Drivers help text new;
849 card "21 files · made for Android 8.1+"; load caselist-test.txt → "1 name is
not in vulkan-cts-1.4.7.1"; run 27 (Finished) → Results → list button → Not
supported → Select to run → lands on the Tests tab, 3 selected, label
"Failed + crashed + not supported · System driver" (bug fixed). No crash/ANR.
Termux back, peers got "done".

**Pre-release:** https://github.com/The412Banner/DriverLab/releases/tag/v0.1.1-pre3 —
tag → bdaaf3ba3e02bb8e3d9ccf06524e150e38b204ba, Pre-release (v0.1 stays Latest),
APK DriverLab-v0.1-bdaaf3ba.apk + .sha256, downloaded back = same hash.

**Next:** user's Fold 8 test (Android 17, Adreno 840) with shared Qualcomm drivers;
merge fix/blob-driver-zips → main = user's go (backup ref first); stable 0.1.1 =
user's word (versionCode bump in the release commit). Kimchi feature ideas await
the user's pick.

## 2026-11-01 — DEVICE TEST of b33343b0 on the Pocket FIT (user OK to install; peers told + "done")

Installed b33343b0 (base.apk sha256 b164a421… == staged), 13:04 EST.
1. Import PocketFIT-own-driver-flat.zip → card loads: GPU "Adreno (TM) 33", Vulkan
   1.3.128, 512.762.24, Library vulkan.adreno.so, "14 files · made for Android 14+". PASS
2. Import stevenmxz-849.zip → "Could not load this driver" / "Probably made for a newer
   Android than this phone's (Android 14)…" / libadreno_utils.so not found / "16 of the
   driver's own libraries could not load" / "Tap to see all". PASS
3. Import -subfolder.zip → loads, Library driver/vulkan.adreno.so. PASS
4. Tests ⋮ Load test list caselist-test.txt → "27 tests selected from 6 names", missing
   dEQP-VK.api.smoke.does_not_exist listed. PASS (wording "1 names are" to fix)
5. Save test list → caselist.txt with the same 27 names. PASS
6. Run 27 on System driver (recording on): 24 pass, 3 not supported, 9.8 s. PASS
7. Result list button: ticked Not supported → Save file = those 3 names; Select to run →
   selection 3, label "Failed + crashed + not supported · System driver"; BUG: stayed on
   the session screen (goTab restoreState brings back the Tests tab's saved stack, which
   held run → session). Ran the 3 on the imported own driver: session log shows
   "opened 12 of the driver's own libraries, 0 could not load" + "loaded HAL driver:
   Vulkan QTI" ×6, results NotSupported ×3 (same as system). PASS
No FATAL/ANR/crash-buffer entries; app pid unchanged all test. System driver set back
in use, Termux brought back. (DocumentsUI needed a media scan to list a .txt made from
the terminal — test setup only.)

**To fix:** Select to run → open Tests fresh; "1 name is"; androidName below API 29
(27 → "8.1"); Drivers screen help text still says only "(meta.json + .so)".

**Fixed (bdaaf3ba):** goTab(fresh = true) for Select to run; "1 name is"; androidName
21–28; Drivers help text. CI 41335618122 (headSha bdaaf3ba) GREEN. APK sha256
d1dc7f1a3c606de507d86fb91b32bfb1c97183624d7656929865c8c9f36b89ae STAGED
/sdcard/Download/DriverLab-test-lists-fixes-bdaaf3ba.apk (host == device). NOT installed,
NOT device-tested; pre3 / install need the user's yes.

## 2026-11-01 — test lists (caselist.txt) + "save failed tests" (fix/blob-driver-zips, CI running, NOT device-tested)

**Why:** Hugo (Discord 12:44 EST, after "Nice, it works" on v0.1.1-pre1 — his
report, no logs seen): load a custom caselist.txt. Checked 4059568: no list
import/export anywhere. User: "yes go ahead, include the save failed tests too".

**Built (b33343b0):** core/TestList.kt (parse: one name per line, `group.*`,
`#` comments, BOM, "name,Fail"/"name Fail" → name, dedup, cap 500,000 names;
write), SelectionStore.loadList (GLES names with/without fd-gles., missing names
returned, normalize), count() rewritten linear (each decision differing from its
nearest decided ancestor adds/removes its tests; old nearestChildren was O(n²)),
expand() uses a precomputed ancestor set instead of scanning all keys per node.
Tests › ⋮ Load test list / Save test list (+ summary dialog with missing names).
Session screen list button → dialog: pick result kinds (Failed + Crashed default),
"Select to run" (loads selection, goes to Tests) or "Save file" (caselist.txt).
SessionStore.namesWith reads results.tsv fully (not the 50,000-pass-capped list).
README updated. No local compile possible (no Android SDK here) → CI is the first compile.

**CI:** 41333545214 (headSha b33343b0) GREEN (all steps success; Kotlin warnings only
in old code: Catalog.kt:193, RunManager.kt:320/445, FilesScreens.kt:156, ResultsScreens TabRow).
APK DriverLab-v0.1-b33343b0.apk sha256 b164a4217f22f79ce6f9e49c7228d67296ac12e50d23c935a77157abd2857c67,
STAGED /sdcard/Download/DriverLab-test-lists-b33343b0.apk (host == device). NOT installed.
Test files (self-made) in /sdcard/Download/DriverLab-test/: caselist-test.txt (3 smoke
tests incl. ",Fail"/" Pass" suffixes, dEQP-VK.info.*, test-clear, 1 bogus name),
PocketFIT-own-driver-flat.zip + -subfolder.zip (this phone's own vendor driver + 11
vendor libs + meta.json minApi 34), stevenmxz-849.zip (expect "made for a newer Android").

**Pre-release:** v0.1.1-pre2 PUBLISHED 13:03 EST (user yes), tag → b33343b0, hash verified. User yes to install on the Pocket FIT for an app test.

**Next / resume (old):** CI green → v0.1.1-pre2 pre-release (user already asked for
pre-releases for the Fold) only after the user OKs → device test: load a list,
save a list, save failed from a run, re-run on another driver.

## 2026-11-01 — Qualcomm drivers from other phones (fix/blob-driver-zips, CI running, NOT device-tested in the app)

**Why:** Hugo (Discord, 06:38 EST) imported a Qualcomm driver shared as an
AdrenoTools zip (891.11, Adreno 840): "Could not load this driver". His two
points, both true in main 29c7820: import kept only `*.so` (Drivers.kt), and the
CTS process never looks in the driver's folder for the libraries it ships
(vkshim.c plain dlopen, no search path). His fix idea: LD_LIBRARY_PATH = driver dir.

**Checked:** StevenMXZ/Adrenotools-Drivers 849.zip (21 files) and 842.13.zip (28),
all .so + meta.json; vulkan.adreno.so needs libgsl/libadreno_utils/libllvm-glnext/
display mapper libs from the zip. Pocket FIT (Android 14), CTS from the installed
v0.1 app, run from the terminal:
- old shim: `library "libadreno_utils.so" not found ... namespace (default)` (= Hugo).
- LD_LIBRARY_PATH (Hugo's idea): the CTS itself can't start —
  `cannot locate symbol _ZNSt3__122__libcpp_verbose_abortEPKcz referenced by
  d849/android.hardware.common-V2-ndk.so` (the CTS's own libandroid/libmediandk
  deps get the zip's copies). Works on Hugo's newer Android, not on 14.
- These zips' libraries need ~11 libc++ functions Android 14 doesn't have
  (/system and vndk-v34 libc++ checked) → they can't run on Android 14 at all
  (842.13 meta even says minApi 35).

**Built (4059568):** vkshim opens the driver's own libraries from its folder
first (needed ones deepest first, then every other shipped .so, for names the
driver dlopens at run time), skips copies that can't load, no LD_LIBRARY_PATH;
DRIVERLAB_VK_LIBDIRS for subfolders. Import keeps every file (zip-slip check,
4000 files / 2 GB caps), picks the driver by meta libraryName / vulkan.* name,
stores minApi + file count. Card: real reason first, "made for a newer Android",
tap to expand. README + credit Hugo.

**Proof so far (terminal, Pocket FIT, shim built with Termux clang):** the phone's
OWN Qualcomm driver packed like a shared zip (vulkan.adreno.so + 11 vendor libs in
a folder): old shim fails like Hugo's; new shim "opened 12 of the driver's own
libraries" → info.device_properties PASS. 849 zip: clear per-library reasons.
GPU name: loaded directly, the driver says "Adreno (TM) 33"; through the system
loader "Adreno (TM) 750". CORRECTION (user, same day): "33" is right — the Pocket
FIT is a Snapdragon G3 Gen 3, kernel gpu_model = Adreno33v2; the system presents
it as 750 via persist.sys.fake.gpu = "Adreno (TM) 750". Not a DriverLab problem;
pre-release notes corrected.

**CI:** 41328224928 (headSha 40595684) GREEN (all steps success; full CTS rebuild,
new branch can't see feat/v0.1's cache). APK DriverLab-v0.1-40595684.apk sha256
9a02e9c655f54c952328fadc7663b56bfe9b213dbee02f752187ae8cb608e21a; its
libdlvkshim.so re-run in the terminal = same results (own driver PASS, 849 clear reason).

**Pre-release (user: "publish it as a pre-release so I can test on the fold"):**
https://github.com/The412Banner/DriverLab/releases/tag/v0.1.1-pre1 — tag →
405956847616122f3d603f98a02d1364dc723750, Pre-release (v0.1 stays Latest), APK +
.sha256, downloaded back = same hash. Also staged on the Pocket FIT as
Download/DriverLab-blob-driver-zips-40595684.apk (hash host == device), NOT installed.
User tests on the Galaxy Fold 8: import 849/842.13 (+others), screenshot cards,
Driver info + Quick check on each loaded driver and the System driver, Record off,
send result screens + Download/DriverLab/<date>/<time>_<driver>.zip.

**Next / resume:** CI green → stage APK in Download (sha256 host+device) → ask
user to install on the Pocket FIT (import the own-driver zip + 849, check card
text, Turnip still loads) → user tests 849/842.13 on the Galaxy Fold 8 (Adreno
840, Android 17), where they should load. Release = user's word (0.1.1).

## 2026-11-01 — merged to main (user go)

User: "yes merge to main and delete the old APKs". Backup ref
refs/backup/20261101/main-before-v0.1 = ffaa6020 pushed first, then main
fast-forwarded ffaa6020 → 51d83b68 (= feat/v0.1). main's tree equals the
released build e26682e1 except PROGRESS_LOG.md. feat/v0.1 kept (not deleted).
Deleted from /sdcard/Download: DriverLab-v0.1-62fb66cd / b0f6b21a / f0be2226
(.apk + .sha256); kept DriverLab-v0.1-e26682e1.apk (= the release).

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
