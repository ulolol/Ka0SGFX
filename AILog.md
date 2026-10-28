# AILog: KaosGFX

## Project Overview
KaosGFX is an open-source, non-root graphics driver manager and instrumentation utility for Android (Qualcomm Snapdragon / Adreno). It leverages Shizuku (shell UID 2000) for privileged operations and `libadrenotools` + `ByteHook` for in-process Vulkan driver redirection (e.g., Mesa Turnip and optimized Adreno drivers).

---

## 2026-10-28: Initialization & Phase 0 Spike

### Findings
- Connected device: vivo I2220 (iQOO), Snapdragon 8 Gen 3 (SM8650), Adreno 750, Android 16 (API 36), `user` build, SELinux Enforcing.
- Stock driver: Vulkan 1.3.128 (`Adreno750v26.1.145-android14`).
- Updatable driver partition `/vendor/app/com.qualcomm.qti.gpudrivers.pineapple.api34` is a stub APK without shared libraries; system-level global redirection is rejected by AOSP security boundaries.
- Shizuku server version `13.7.0-thedjchi` is running and accessible.
- Confirmed Blueprint 1 (in-process redirection via `libadrenotools` and `ByteHook`) is the viable architecture.
- Cross-verification identified 10+ critical engineering constraints, including trailing slash requirements on `customDriverDir`, extracting hook libraries when `extractNativeLibs="false"`, using `BYTEHOOK_MODE_MANUAL` to preserve caller address via `__loader_dlopen`, no-op `dlclose` protection, and `StorageManager.fixupAppDir` handling.

### Steps
1. Initialized git repository at `~/Projects/KaosGFX`.
2. Configured `local.properties` targeting Android SDK and NDK 27.2.
3. Created initial `README.md` and `AILog.md`.
4. Implemented Phase 0: Standalone `spike/vkprobe` module linking `libadrenotools`, bundling a Mesa Turnip 27.0.0-devel driver package in assets, and executing an empirical Vulkan probe on the vivo I2220 (Adreno 750 / Android 16).
5. Tested on physical device `10BDC11TCR0004P`: Verified successful `adrenotools_open_libvulkan`, `vkCreateInstance`, and enumeration of `Turnip Adreno (TM) 750` reporting Vulkan 1.4.363 (`VK_DRIVER_ID_MESA_TURNIP`).
6. Implemented Phase 1: Native bridge and bootstrap dex:
   - Created `bridge` module with `libkaosgfx_bridge.so`, integrating ByteHook (via Maven AAR `com.bytedance:bytehook:1.1.1`) and `libadrenotools`.
   - Implemented `hooks.cpp` with `BYTEHOOK_MODE_MANUAL`, hooking `dlopen`, `dlsym`, `android_dlopen_ext`, `dlclose`, `vkGetInstanceProcAddr`, and `vkCreateInstance`. Suppressed `dlclose(g_vk)` to prevent unload during engine startup capability probes. Preserved caller-namespace lookups via `__loader_dlopen`/`__loader_dlsym`.
   - Implemented crash-guard in `bridge.cpp` using `/data/user/0/<pkg>/files/kaosgfx_crash_flag`.
   - Created `bootstrap` module with `TurnipAppComponentFactory.java` chaining to original `AppComponentFactory`, extracting native hook libs if `extractNativeLibs="false"`, loading bridge, and triggering `nativeInit`.
   - Configured build pipeline to assemble `classes.dex` via `d8` into `patcher/src/main/resources/bootstrap.dex` and strip release `.so` binaries into `patcher/src/main/resources/arm64-v8a/`.

### Issues & Fixes
- **Issue**: AGP 9.0 rejected `org.jetbrains.kotlin.android` plugin ("plugin is no longer required for Kotlin support since AGP 9.0") and complained about `kotlinOptions`.
  - **Fix**: Removed explicit kotlin plugin and `kotlinOptions` block from `spike/vkprobe/build.gradle.kts`.
- **Issue**: CMake path traversal in `spike/vkprobe/src/main/cpp/CMakeLists.txt` had 4 `..` instead of 5 to reach `third_party/libadrenotools`.
  - **Fix**: Corrected path to `${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/libadrenotools`.
- **Issue**: `vkprobe.cpp` attempted to query `vkEnumeratePhysicalDevices` using `vkGetInstanceProcAddr(VK_NULL_HANDLE, ...)`.
  - **Fix**: Updated to query `vkEnumeratePhysicalDevices` with the valid `instance` handle returned by `vkCreateInstance`.
- **Issue**: `android_dlextinfo` undeclared in `hooks.cpp`.
  - **Fix**: Added `#include <android/dlext.h>`.
- **Issue**: `d8` rejected directory input for classes when invoking D8 CLI.
  - **Fix**: Passed individual `.class` file paths dynamically gathered by Gradle.

### Progress
- [x] Project architecture plan approved.
- [x] Workspace initialized.
- [x] Phase 0: Standalone `vkprobe` spike verified on target device.
- [x] Phase 1: Native bridge and bootstrap dex.
- [ ] Phase 2: APK patcher engine.
- [ ] Phase 3: Shizuku privileged service & Compose app.
- [ ] Phase 3b: Driver catalog & probe validation.
- [ ] Phase 4: GLES via ANGLE translation.
