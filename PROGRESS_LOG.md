# DriverLab progress log

Newest first.

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

**Built:** 07061bcb (app + CI). CI run 41285265794.

**Device test:** not yet. **Release:** v0.1 requested by the user; publish
only after the APK is installed and checked.

## Lessons learned (don't repeat these)
- The Android linker namespace for executables only allows libraries under
  /data: test from /data paths, not proot /tmp.
- Never initialise an LD_PRELOAD library from mmap(): the allocator calls mmap
  while setting itself up; malloc from there returns untagged pointers that
  abort at free().
- memfd_create needs Android API 30 in bionic: the CTS needs DE_ANDROID_API >= 30.
