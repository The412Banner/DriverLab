# DriverLab progress log

Newest first.

## 2026-11-02 — dashboard UI (Option B, upright + sideways, blue accent) (feat/dashboard-ui, IN PROGRESS)

**Why:** user asked for compact, more professional tabs/menus, sharper outlines, live graphs; picked from the
HTML preview (Download/DriverLab-layout-options.html; canvas https://claude.ai/artifact/1ovmKhZoBZEYAUgXiGV6LW):
"B for both, blue accent" — the one-screen dashboard upright AND its sideways version.

**Plan:** branch off main de9366dd, worktree /root/src/DriverLab-wt/dash.
1. Theme: dark-first, blue accent #4C8DFF, 4–6 dp corners, 1 dp outlines, JetBrains Mono (OFL, bundled) for numbers.
2. Home = dashboard: driver A vs B cards, Conformance/Speed switch, test set chips, one Start button, four live tiles
   (pass ring, tests/s line, repeat, GPU health; speed: live picture, frame-time line, score), recent runs.
   Sideways (wide window): left column setup + Start + recent, right 2x2 tiles. No bottom tabs: Tests, Results,
   Drivers, Settings from header icons; Files reached from results.
3. dlbench prints a frame-time sample a few times a second ("DLBENCH tick") for the live line.
4. Other screens restyled with the new theme.

**Built:** 12ce30d (dashboard HomeScreen upright + wide, theme, outlines/BtnShape sweep, back arrows on Tests/Results/
Files/Drivers, bottom bar removed, Results gets Speed + Files icons, settings homeMode/speed*/dynamicColor2, SpeedManager
Live ft/aId/bId from "DLBENCH tick", README) — CI 41444350879 FAILED: "Argument already passed" (my sweep added
shape = BtnShape to buttons that already had a shape) → 957643f fix → CI 41444676198 (headSha 957643f) success.
APK has libdlbench.so 76,720 B + 4 font files (res/*.ttf, R8 names) + licenses/JetBrainsMono-OFL.txt. CI dlbench run
alone on the Pocket FIT: 20 tick lines in 2 s of scenes, results ok.
**Staged:** /sdcard/Download/DriverLab-dashboard-957643f0.apk, sha256 == CI == device. NOT installed (ask the user).
Proof level: CI-green; dashboard UI NOT device-tested.

**User 2026-11-02: "install it"** → installing 957643f0 on the Pocket FIT + quick test. Settings backup
/data/local/tmp/driverlab-settings.xml.bak-dash; screen rotation settings noted before forcing sideways (restore after).
**DEVICE TEST 957643f0 on the Pocket FIT 07:37–07:49 local (installed hash f2cfaa53… == staged; peer told + done):**
- Dashboard upright: new look OK (blue, outlines, JetBrains Mono, header icons). Conformance Quick check ×3: Stop button,
  live progress 173/456, pass ring + tests/s line (141/s) live, "Run 2 of 3", finished + saved, "no test changed result".
- Speed test A System / B Turnip r4 (6 scenes, 1 round, 3 s): live test card picture, fps label, frame-time line, score;
  ROTATED sideways mid-run (user_rotation 1, auto-rotate off): wide layout (setup left, picture/frame/score right), run
  kept going; done: "A faster 1, B faster 5", last test card, "Speed results and pictures" button.
- Tests/Results/Drivers/Settings: new look + back arrows OK.
- Problems found → fixed in 3e5a1b0 (CI 41447833663 3e5a1b043dc1800fd1f53f071528a23e3ae49cf3): teal secondaryContainer (chips, tonal buttons, sliders) → blue;
  progress track teal; Tests run bar under the system buttons (no navigationBarsPadding after the tabs went);
  "pictures match" tag missing right after a run; "1 rounds"; label spacing in the wide picture tile.
- The user used the phone during the test (Discord, preset chips) — paused and asked; user: "yes that was me, go ahead".
- Cleanup: settings.xml restored (cmp), rotation restored 1/1, screenshots removed, crash buffer 0, FATAL/ANR 0, Termux back.
Proof: dashboard device-tested on the Pocket FIT (957643f0); 3e5a1b0 fixes NOT device-tested yet.

CI 41447833663 (headSha 3e5a1b0) success; STAGED /sdcard/Download/DriverLab-dashboard-3e5a1b04.apk (sha256 583958d9…,
CI == device); old 957643f0 staged APK removed (that build stays installed).
**User: "install it"** → installing 3e5a1b04 on the Pocket FIT for a quick look at the fixes (settings backup
/data/local/tmp/driverlab-settings.xml.bak-dash2; restore after; Termux back; peer done).
**Resume point:** after the check, merge = user's go. Device test was: dashboard upright + sideways
(rotate), conformance Start/Stop + live tiles, speed test live picture/frame time/score, header icons, back arrows.

## 2026-11-02 — MERGED feat/speed-and-repeat into main (user: "merge it")

Backup ref refs/backup/20261102/main-before-speed-and-repeat = 1c6c607f01a06c9e3b5a17038dd7acc095fb48ca (pushed +
verified first). main fast-forwarded 1c6c607 → 2b8db843227f3c9be9f14c55250bbc761b09bde7; tree == device-tested
73bc0409 build except PROGRESS_LOG.md (git diff 73bc040 main, log excluded: empty). Contents: repeat runs +
"sometimes fails", GPU error check (root via Magisk Superuser), Mesa known results, driver copies, second-driver
run + Compare, speed test (10 scenes, alternating rounds, live picture, full screen, test card picture check,
report + pictures in Downloads). CI on the tested commit: 41431006975 green. No version bump (no release asked);
v0.1.2 stays Latest. Branch kept (not deleted).
Not tested: the red "differs" path and "sometimes fails" (no real case on the Pocket FIT); the Duo / A722.

## 2026-11-02 — solid pictures + picture check for the speed test (feat/speed-and-repeat, IN PROGRESS)

**Why:** user watched the speed test: scenes looked "garbled" (texture = noise, draws/passes = tiny dots by design;
cube was fine on both drivers, checked: Download/DriverLab-speed-test-pictures.png). User: "I was hoping for something
solid to see and check visually for errors" → plan approved ("yes go ahead"): (1) new "card" scene = TV-style test
card (colour bars, ramps, checkerboard, circles, 5 spinning textured cubes, translucent panel); (2) picture check:
every driver draws the same fixed frame (t = 1.0 s, --snapshot 960x540), compared pixel by pixel with driver A's
(tolerance 24/255 per channel, "matches" below 0.2 % of pixels), red difference image, side-by-side sheet to
Downloads; (3) texture = 256-texel coloured-block tile instead of noise, draws = 20,000 tiles edge to edge,
passes = bigger squares; tap the live picture for full screen.

**State:** app side committed as WIP (SpeedPictures.kt, SpeedTest.kt card scene + snapshot arg + checkPictures +
report/JSON, SpeedScreen.kt PictureCheckCard + FullScreenPicture) — NOT compiled yet. Native side (dlbench "card"
scene, --snapshot, texture/draws/passes changes) is being written by the helper session in the worktree,
UNCOMMITTED (native/dlbench/**); its PNGs go to scratchpad dlb2/.

**Native done (helper session) 73bc040:** card scene first in the list (bars/ramps/checker/rings/5 cubes/panel,
exact-pixel check), --snapshot (t = 1 s frame, 960x540, "DLBENCH snapshot" line), texture = flat colour-block tile
(check ±2), draws = 200x100 tiles edge to edge (static gradient), passes = 70 % squares, 24/255 per pass (check 192).
Its terminal runs on the Pocket FIT (system + Turnip r4): all checks ok; card snapshots system vs Turnip: 0 pixels
differ by > 8 (max 1 level) → kept TOLERANCE 24, MATCH_SHARE 0.2 %. I viewed both snapshots side by side + the
preview grid: solid, identical.
**CI 41431006975 (headSha 73bc040) success** (also compiles the WIP picture-check app code). CI libdlbench.so run
alone (system driver): card/texture/draws/passes ok, snapshot 2,073,608 bytes, exit 0. Note: texture now ~1080 fps
vs ~106 with noise (compressible, cache-friendly) — lighter test now, as warned.
**Staged:** /sdcard/Download/DriverLab-test-card-73bc0409.apk sha256 1c124691…4b40 == CI == device. Old staged
d62271ce APK removed from Download (that build is still installed). NOT installed — ask the user (away at work).

**User 2026-11-02: "yes install it and test".** DEVICE TEST 73bc0409 on the Pocket FIT 06:00–06:05 local:
installed base.apk sha256 1c124691… == staged (versionCode 3); peer root-e6 told + "done".
- Speed test A System vs B Turnip r4, 7 scenes (card, cube, fill, texture, geometry, draws, passes), 3 rounds, 3 s,
  no pause: finished. Live window showed the solid test card; tap → full screen (textures' colour blocks live).
- Picture check card: "Every test card picture matches driver A's", A / B / Differences thumbnails (no red),
  6 lines (A r1 reference; A r2–r3, B r1–r3 0.00 % differ); tap → full screen OK. Downloads got
  06-01-40_speed-test-pictures.png (A | B round 1 | differences) + 06-01-40_speed-test.txt with the picture section.
- Numbers: card 3700 vs 3802 fps (no clear difference), cube Turnip +9 %, fill Turnip +10 %, textures 1148 fps (B).
- Crash buffer: 0 DriverLab lines; FATAL/ANR: 0 (the 3 hits were the root tool echoing my own grep).
- Settings unchanged (cmp with backup), backup removed, screenshots folder removed, Termux back.
- NOT exercised: the "differs" path (red areas), because both drivers matched; Duo / A722.

**Resume point:** branch feat/speed-and-repeat (head = this log commit after 73bc040), device-tested on the Pocket FIT;
merge = user's go (backup ref first); Duo test optional. Plan was: speed test with card (A system, B Turnip
r4) → picture check card matches, tap-to-full-screen, sheet PNG in Downloads; optional fake-difference check.

## 2026-11-02 — repeat runs, GPU error check, Mesa known failures, driver copies, speed test (feat/speed-and-repeat, IN PROGRESS)

**Why:** user (2026-11-02): "do 1-4 and the speed test" from the offered list — (1) run tests
several times and flag ones that only sometimes fail, (2) one driver with two settings without
importing it twice, (3) GPU errors from the kernel log per test (root), (4) mark failures Mesa
already knows (Mesa CI fails/flakes lists), (5) a speed test: same small Vulkan scenes on two
drivers, alternating rounds, plain verdict only when the gap is bigger than the noise.
Motivation: the Adreno 722 GMEM bug showed in 1–3 of 5 runs only, and faulted on passing tests.

**Plan:** branch off main 1c6c607f, worktree /root/src/DriverLab-wt/speed.
- native/dlbench: own headless Vulkan benchmark program (libdlbench.so), loads the system
  loader or libdlvkshim.so; scenes fill, texture, geometry, draws, compute, bandwidth,
  passes, pipelines; machine-readable "DLBENCH ..." lines; picture checks.
- app: repeat count (rounds) + "sometimes fails" summary; driver copies (hard link, own name
  and settings) + optional second driver run with Compare; root GPU-error watcher (dmesg -w);
  Mesa expectations assets (tools/make_mesa_expect.py, pinned Mesa commit); Speed test screen
  and runner in the foreground service.
- CI build → test the native program in a terminal on the Pocket FIT → stage APK → ASK before install.

**Built (app side) a48e713:** repeat rounds (results.tsv column 8 = round; SessionStore.combineRuns →
"sometimes fails"), GpuErrorWatcher (su -c 'echo mark > /dev/kmsg; exec dmesg -w', lines tied to
running tests → gpu-errors.tsv), MesaExpect (assets/mesa/expect.tsv from Mesa 01bab930: 186 VK lines,
lists a618/a660/a6xx/a702/a750; Turnip sessions only, closest list labelled), driver copy (hard links),
"then also run on" + Compare, SpeedManager/SpeedScreen (alternating rounds, median ± spread, verdict
only above max(spread, 3 %), preview window reading a raw RGBA file). CI 41424210720 (headSha a48e713)
= compile check; native benchmark (native/dlbench) is being written in parallel, not committed yet.

**CI:** 41424210720 (a48e713, app only) success — Kotlin compiled first try. d62271c = native/dlbench
(helper session, own code: 9 scenes, picture checks, preview file; tested by it on the Pocket FIT with
system driver + Turnip r4, all checks ok; Turnip passes GPU 0.09 ms sysmem vs 2.06 ms gmem) + build
changes (glslang-tools, stage libdlbench.so) + README. CI 41424524869 (headSha d62271c) success, all
steps; skipped = cached CTS/catalog + release only. APK has lib/arm64-v8a/libdlbench.so (68 KB) +
assets/mesa/expect.tsv. CI-built libdlbench.so run alone on the Pocket FIT as the Termux uid (system
driver, 1 s per scene, preview): all 9 scenes, checks ok/none, exit 0, preview 230,408 bytes; temp dir removed.

**Staged:** /sdcard/Download/DriverLab-speed-and-repeat-d62271ce.apk (+ .sha256), sha256
15438edb534dd9c4d5e460b23f0a426a37889d3d74e0820bab0f38b46c49b568 == CI .sha256 == host == device.
NOT installed (needs the user's yes). Proof level: CI-green + benchmark program device-run in a
terminal; app UI NOT device-tested.

**User 2026-11-02: "yes install it and test, you tap grant"** (Magisk root prompt for com.driverlab: I tap Grant).
Settings before the test (to restore, check shared_prefs): driverId=system, record=true, jobs=1.

**DEVICE TEST d62271ce on the Pocket FIT (2026-11-02 05:00–05:16 local; installed base.apk sha256 15438edb… == staged,
versionCode 3; peer root-e6 told + "done"; settings.xml backed up and restored byte-identical; Termux back):**
- Speed test System vs Turnip r4, 9 scenes, 3 rounds, 3 s, no pause: finished, alternating order, live picture window
  showed the scenes, verdicts e.g. cube Turnip +10 %, draws System +118 %, triangles/passes "no clear difference";
  report saved Download/DriverLab/2026-11-02/05-01-18_speed-test.txt. Cosmetic: result cards not full width,
  report "Android 14, unknown" (SOC_MODEL) → fixed in 1a9abc0.
- Driver copy "Mesa Turnip … r4 (copy)" TU_DEBUG=gmem: files hard-linked (inode 2093857, 2 links).
- Quick check ×3 on Turnip r4 + then also on the copy: 456/456 runs each, "Each test ran 3 times · same result
  every time", "Fail in all 3 runs — …", Compare button → 152 in both, 152 same.
- GPU error check: first try Magisk 31.1 rejected uid 10210 silently ("su: request rejected (10210)", no prompt).
  CORRECTION (user, verified in the v31.1 release notes 2026-10-25): Magisk 31.1 REMOVED the request prompt; apps must
  be allowed ahead of time in the Superuser tab — my "Magisk app never opened" guess was wrong → app said "Could not read the kernel log: Permission denied" (message
  improved in 1a9abc0). With the user's "you tap grant": added Magisk policy uid 10210 allow (same as the Termux uids'
  rows). Then "No GPU errors" on both drivers; fake labelled line written to /dev/kmsg mid-run ("kgsl kgsl-3d0:
  DRIVERLAB SELF-TEST fake GPU page fault line (not a real fault)") → GPU errors: 1, tied to the 6 tests running
  then (jobs=6), filter chip "GPU errors 6", test detail shows the line, results.log GPU ERROR line.
- Mesa labels: pipeline.monolithic.timestamp.calibrated (3 tests) ×3 on Turnip: calibration_test + dev_domain_test
  Fail → "Known to Mesa 2", "Mesa's testing on the Adreno 750 gets the same: Fail".
- NOT exercised: "sometimes fails" (no test changed result between runs), real GPU faults, A722/Duo.
- No DriverLab lines in the crash buffer, no FATAL/ANR.
Left on the device: the copy driver, 8 test sessions from today, Magisk allow rule for DriverLab (uid 10210).

**Resume point:** 1a9abc0 (3 small fixes) CI 41427537161 running; merge = user's go (backup ref first, PR not
needed for this repo); plan was: speed test system vs
Turnip r4 (preview, verdicts, report), Quick check ×3 on Turnip (sometimes-fails + Mesa a750 labels),
GPU error check (Magisk root prompt for com.driverlab — user's call), driver copy TU_DEBUG=gmem +
"then also run on" + Compare. Announce takeover to peers, check topResumedActivity, Termux back.

## 2026-11-01 — test leftovers cleaned up on the Pocket FIT (user picked the scope)

Removed from the app: test drivers (two "Pocket FIT own Qualcomm driver (test)" copies,
qcom-849) and the 15 test sessions from 13:10–15:08; kept System driver, Banners-Turnip r4,
the user's 04:04 and 04:26 sessions. Deleted Download/DriverLab-test, the 45 export files of
those runs (kept 04-04-33 and 04-26-49), the old staged DriverLab APKs (kept
DriverLab-v0.1.2-91ee8c28.apk) and the terminal test folder ~/driverlab-fit.

## 2026-11-01 — v0.1.2 release APK INSTALLED on the Pocket FIT (user: "install 0.1.2 on the pocket fit")

Staged /sdcard/Download/DriverLab-v0.1.2-91ee8c28.apk (release asset, sha256 761a0683… host ==
device); installed base.apk sha256 761a068312b504b9b4785852309f08d6afbfec6e5e224e3c47b8ab8d7838bb57
== release, versionCode 3, versionName 0.1.2, 15:08:18 local. Settings kept (driverId=system,
record=true, jobs=1). Quick check with recording: 152 tests, 25.2 s, 123/2/27, 152 captures,
no crash-buffer lines, no FATAL/ANR. Termux back, peer root-9c got "done".

## 2026-11-01 — merged feat/test-tools, RELEASED v0.1.2 (main)

User: "merge it and make it 0.1.2". Backup ref refs/backup/20261101/main-before-test-tools
= 4729b4c7b1ec0a20424274f964dc30ea1d61c6eb pushed + verified first; main fast-forwarded
4729b4c → 0126f628 (tree == installed/checked b792de2a + PROGRESS_LOG). README commit
(new features; credits: DXVK Philip Rebohle and VKD3D-Proton Hans-Kristian Arntzen, names
checked in DXVK LICENSE / GitHub profile; Hugo; Kimchi). Release cut 91ee8c28 "Release 0.1.2
(versionCode 3)" + release-notes/v0.1.2.md. Release workflow 41344759544 (release=true,
tag=v0.1.2, headSha 91ee8c28) success;
https://github.com/The412Banner/DriverLab/releases/tag/v0.1.2 — Latest, tag →
91ee8c2829778e9016980f2f5307c2b3c06b1934, asset DriverLab-v0.1.2-91ee8c28.apk sha256
761a068312b504b9b4785852309f08d6afbfec6e5e224e3c47b8ab8d7838bb57 (== .sha256), manifest
versionName 0.1.2, notes == release-notes/v0.1.2.md. Release APK itself not installed
(Pocket FIT has b792de2a = same code + old version numbers). feat/test-tools kept.

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
