# DriverLab

<img src="docs/icon.png" width="96" align="right" alt="DriverLab icon"/>

DriverLab runs the same GPU tests on two Vulkan drivers on your Android
device and shows how they differ:

- the device's **own driver** (Qualcomm's on Adreno), and
- a **custom driver** you import, such as a Mesa Turnip AdrenoTools zip.

It can also record what each driver sends to the GPU and compare the two
register by register. Nothing on the device is changed: an imported driver
is loaded only inside DriverLab's own test program. No root needed.

**Download:** [latest release](https://github.com/The412Banner/DriverLab/releases/latest)
· arm64, Android 11 or newer.

## What it can do

- **Tests**
  - the official Vulkan conformance tests (Khronos CTS 1.4.7.1, 3.2 million tests);
  - 68 OpenGL ES programs: the set used by the adreno-cmdstream-gather app
    (66 from freedreno, two written for DriverLab). They run on the device's
    own driver only.
- **Choosing tests:** browse by group, search, pick single tests, or use a preset:
  - Quick check: about a minute;
  - Driver info: what the driver reports;
  - Rendering basics;
  - Capture set: the adreno-cmdstream-gather test set.

  Every preset, group and test has an ⓘ button that explains what it checks.
- **Test lists:** load a `caselist.txt` (one test name per line, the format
  the Vulkan CTS uses; `group.*` for whole groups, `#` comments, and
  `name,Fail` lines from Mesa CI lists work too) or save the current
  selection as one (Tests › ⋮). From a finished run, the list button makes
  a list of its failed and crashed tests (or any other results) to run
  again, for example on another driver, or to save as a file.
- **Drivers:** import a zip or `.so`. DriverLab checks it right away
  (GPU name, Vulkan version, driver version). Each driver can have its own
  settings, such as `TU_DEBUG=sysmem`.
  - Qualcomm drivers taken from other phones (shared as AdrenoTools zips
    with their own `libgsl.so`, `libllvm-glnext.so` and so on) work too: every
    file in the zip is kept, and the driver's own libraries are loaded from
    its folder. Many of these were built for Android 15 or newer and can't
    load on older Android; the driver card then says so.
- **Running:** a live screen shows progress, pass/fail counters, speed and
  time left. Runs keep going with the screen off. A test that crashes or
  hangs is marked as such, and the run continues.
- **Recording (optional):** saves each test's GPU command stream as a
  freedreno `.rd` file.
  - On the device's own driver, this includes a full log of the driver's
    kernel calls, in the same format as freedreno's libwrap.
  - On Turnip, Mesa's own recorder is used.
- **Results:** each session can be filtered and searched. Each test shows its
  output, its CTS log and the GPU registers its recording wrote.
- **Compare:** pick two sessions to see which tests differ, then open a
  register-by-register diff, with register names, for any test recorded on
  both drivers.
- **Files:** everything a run saved, grouped by driver and session. Read,
  share, save a copy, copy to Downloads, or delete.

After every run DriverLab saves three files to `Download/DriverLab/<date>/`:

| File | Contents |
|---|---|
| `<time>_<driver>.zip` | everything: readable log, results table, CTS logs, test output, recordings |
| `<time>_<driver>_test_results.zip` | one `<test>.log` and one `<test>.rd` per test, the same layout as the adreno-cmdstream-gather app |
| `<time>_<driver>_results.log` | readable results with a time on every line |

To make the same `test_results` zip as the adreno-cmdstream-gather app:
choose **Capture set**, keep **System driver**, turn on
**Record GPU commands**, and press **Run**.

## How it works

| Part | What it is |
|---|---|
| `libdeqp_vk.so` | the Vulkan CTS, built as an Android program with the NDK (`cts/build-cts.sh`) |
| `libdlvkshim.so` | lets the CTS load a custom driver: Android HAL (`HMI`) or loader ICD, plus the libraries shipped with it (`native/vkshim`) |
| `libdlrec.so` | records the device driver's GPU submits as `.rd` and logs its kernel calls (`native/dlrec`) |
| `libtest_*.so` | the OpenGL ES test programs (`native/gles`, freedreno sources) |
| test catalog | a compact index of the CTS test list (`tools/make_catalog.py`) |
| register names | generated from Mesa's register database (`tools/make_regs.py`) |

Builds run on GitHub Actions (`.github/workflows/build.yml`). The CTS, the
native libraries, the test catalog and Gradle are cached separately, so a
normal app change builds in a few minutes. APKs are signed with the public
AOSP test key (v1, v2 and v3).

## Credits

- Khronos Group: Vulkan CTS (Apache 2.0).
- Rob Clark and the freedreno and Mesa developers: the OpenGL ES test
  programs, the `.rd` format and libwrap, the register database, and
  Turnip's `FD_RD_DUMP` (MIT).
- Huggo: the adreno-cmdstream-gather app.
- Hugo: the report and fix idea for loading Qualcomm drivers taken from
  other phones.
- vauzi (Vauzi-17): for sharing that app and for the Adreno 710/720/722
  register work that started DriverLab.
