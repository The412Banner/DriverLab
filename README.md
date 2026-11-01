# DriverLab

<img src="docs/icon.png" width="96" align="right" alt="DriverLab icon"/>

DriverLab is an Android app (Jetpack Compose) that runs the same GPU tests on
two drivers and lets you compare them:

- the phone's **system Vulkan driver** (Qualcomm's driver on Adreno phones), and
- a **custom driver** you import, such as a Mesa Turnip AdrenoTools zip.

It uses the official Khronos Vulkan CTS, can record what each driver sends to
the GPU (freedreno `.rd` captures), and shows the results side by side down to
individual GPU registers. Nothing on the phone is changed: the custom driver is
loaded only inside DriverLab's own test program. No root needed.

Work in progress; see `PROGRESS_LOG.md`.

## What it does

- **Tests**: browse the whole Vulkan CTS (3.2 million tests in 1.4.7.1) by
  group, search it, use presets (Quick check, Driver info, Rendering basics,
  Capture set), or pick any groups or single tests.
- **Drivers**: import an AdrenoTools zip (`meta.json` + `.so`) or a bare `.so`.
  Each driver is checked right away (`dEQP-VK.info.device_properties`) and can
  have its own environment options such as `TU_DEBUG=sysmem`.
- **Run**: live progress with pass/fail counters, speed and time left. Runs in a
  foreground service, restarts the CTS after crashes, stops tests that hang.
- **Record GPU commands** (optional):
  - system driver: DriverLab's recorder (`libdlrec.so`, LD_PRELOAD) writes each
    GPU submit as `.rd.gz` and logs the KGSL calls (GPU properties, buffers);
  - Turnip: Mesa's own `FD_RD_DUMP` recorder.
- **Results**: per session and per test (output, CTS log, registers), and
  **Compare** two sessions: tests whose results differ, and a register diff for
  every test recorded on both drivers.
- **Files**: everything a run produced, grouped by driver and session, to read,
  share, save a copy, copy to Downloads or delete.

After each run DriverLab saves to `Download/DriverLab/<yyyy-MM-dd>/`:

| File | Contents |
|---|---|
| `<HH-mm-ss>_<driver>.zip` | everything: `results.log`, `results.tsv`, `session.json`, CTS `.qpa` logs, raw output, per-test logs, captures |
| `<HH-mm-ss>_<driver>_test_results.zip` | same layout as the adreno-cmdstream-gather app: `<test>.log` + `<test>.rd` per test |
| `<HH-mm-ss>_<driver>_results.log` | readable, time-stamped results |

## How it works

| Part | Source |
|---|---|
| `libdeqp_vk.so` | Khronos VK-GL-CTS `vulkan-cts-1.4.7.1`, built as an Android executable with NDK r29 (`cts/build-cts.sh`, same recipe as Mesa CI) |
| `libdlvkshim.so` | `native/vkshim/vkshim.c`: gives the CTS a `vkGetInstanceProcAddr` from a custom driver (Android HAL `HMI` or loader ICD) via `--deqp-vk-library-path` |
| `libdlrec.so` | `native/dlrec/dlrec.c`: KGSL submit recorder for the system driver |
| test catalog | `tools/make_catalog.py`: compact per-group index of the CTS mustpass list |
| register names | `tools/make_regs.py` from Mesa's `a6xx.xml` (A6XX/A7XX/A8XX) |

## Building

GitHub Actions (`.github/workflows/build.yml`, run it with workflow_dispatch). The CTS
(~20 min), native libraries, catalog and Gradle are cached separately, so an
app-only change rebuilds in a few minutes. The APK is signed with the public
AOSP test key (v1 + v2 + v3).

## Credits

- Khronos Group: Vulkan CTS (Apache 2.0).
- Rob Clark and the freedreno project: the `.rd` capture format and the original
  `libwrap` recorder idea (MIT); Mesa's register database and Turnip's `FD_RD_DUMP`.
- Huggo: the adreno-cmdstream-gather app, and vauzi (Vauzi-17) for sharing it and
  for the Adreno 710/720/722 register work in Banners-Turnip that started this.
