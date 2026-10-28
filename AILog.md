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

### Issues & Fixes
- **Issue**: AGP 9.0 rejected `org.jetbrains.kotlin.android` plugin ("plugin is no longer required for Kotlin support since AGP 9.0") and complained about `kotlinOptions`.
  - **Fix**: Removed explicit kotlin plugin and `kotlinOptions` block from `spike/vkprobe/build.gradle.kts`.
- **Issue**: CMake path traversal in `spike/vkprobe/src/main/cpp/CMakeLists.txt` had 4 `..` instead of 5 to reach `third_party/libadrenotools`.
  - **Fix**: Corrected path to `${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/libadrenotools`.
- **Issue**: `vkprobe.cpp` attempted to query `vkEnumeratePhysicalDevices` using `vkGetInstanceProcAddr(VK_NULL_HANDLE, ...)`.
  - **Fix**: Updated to query `vkEnumeratePhysicalDevices` with the valid `instance` handle returned by `vkCreateInstance`.

### Progress
- [x] Project architecture plan approved.
- [x] Workspace initialized.
- [x] Phase 0: Standalone `vkprobe` spike verified on target device.
- [ ] Phase 1: Native bridge and bootstrap dex.
- [ ] Phase 2: APK patcher engine.
- [ ] Phase 3: Shizuku privileged service & Compose app.
- [ ] Phase 3b: Driver catalog & probe validation.
- [ ] Phase 4: GLES via ANGLE translation.
