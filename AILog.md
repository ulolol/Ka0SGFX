# AILog: KaosGFX

## Project Overview
KaosGFX (`dev.kaos.kaosgfx`) is an open-source, non-root graphics driver manager, APK patcher, and runtime instrumentation platform for Android devices equipped with Qualcomm Snapdragon (Adreno) GPUs. It leverages Shizuku (shell UID 2000) for privileged operations and `libadrenotools` + deterministic ELF PLT/GOT symbol redirection for in-process Vulkan driver redirection (Mesa Turnip and vendor Adreno drivers).

---

## 2026-10-28: Empirical Development, Physical Hardware Verification & Full Stack Implementation

### Key Findings & Discoveries
1. **Target Hardware Profile**:
   - Device: vivo I2220 (iQOO 12), Qualcomm Snapdragon 8 Gen 3 (SM8650), Adreno 750 GPU.
   - Operating System: Android 16 (API 36, OriginOS 7), Linux Kernel 6.1.75 (4KB page size), `user` build (`ro.debuggable=0`), SELinux Enforcing.
   - Stock Driver: Vulkan 1.3.128 (`Adreno750v26.1.145-android14`).
   - Updatable driver partition `/vendor/app/com.qualcomm.qti.gpudrivers.pineapple.api34` is a stub APK without shared libraries; system-level global redirection is rejected by AOSP security boundaries.
   - Shizuku server version `13.7.0-thedjchi` is running and accessible via ADB shell (UID 2000).

2. **ShadowHook Linker Mod Incompatibility on Android 16 (API 36)**:
   - On Android 16 / OriginOS 7, `shadowhook_init` fails with `SHADOWHOOK_ERRNO_INIT_LINKER` (error 12).
   - Root Cause: Bionic's internal `soinfo` struct field order changed in Android 16, breaking ShadowHook's dynamic memory scan.
   - Impact: ByteHook's automatic mode relies on ShadowHook's linker hooks to detect newly loaded libraries (`dlopen` monitor). When ShadowHook fails, ByteHook's ELF manager only captures initial libraries and fails to hook libraries loaded dynamically later (like `libvkprobe.so` or game engine libraries).

3. **ByteHook Trampoline Stack Assertion**:
   - Calling ByteHook macros (`BYTEHOOK_STACK_SCOPE()`, `bytehook_get_prev_func`) from outside ByteHook's own trampoline stack triggers an immediate assertion abort (`SIGABRT in linkernsbypass_namespace_dlopen`).

4. **Deterministic ELF PLT/GOT Hooking Engine (`plt_hook.cpp`)**:
   - Designed and implemented a self-contained 64-bit ELF relocation parser.
   - Directly parses `dl_iterate_phdr`, iterates `PT_DYNAMIC`, `.rela.plt`, `.dynsym`, and `.dynstr`, filters target ELF binaries (`/data/app/`, `.apk!/lib/`), changes page protection with `mprotect(PROT_READ | PROT_WRITE)`, updates GOT slots, and invalidates instruction caches (`__builtin___clear_cache`).
   - Achieved 100% deterministic, zero-crash symbol hooking on Android 16.

5. **Empirical Verification of In-Process Interception on Physical Device**:
   - Built patched test APK using `ApkPatcherEngine` and installed on physical target `10BDC11TCR0004P`.
   - Direct execution logcat confirmed:
     - `kaos_dlopen: intercepted libvulkan.so -> returning custom Vulkan handle 0x39afdcf8473fadb1`
     - `kaos_vkCreateInstance succeeded! g_instance = 0xb400006fe8a2e8f0`
     - `Standard Intercept Device [0]: Turnip Adreno (TM) 750, Driver: turnip Mesa driver (Mesa 27.0.0-devel (git-0abe1db7c4)), API 1.4.363 (VK_DRIVER_ID_MESA_TURNIP 18)`
     - `Crash guard cleared: /data/user/0/dev.kaos.kaosgfx.spike.vkprobe/files/kaosgfx_crash_flag`

6. **Cross-Process Driver Staging Architecture**:
   - Android isolates application private directories (`/data/user/0/<pkg>` mode 700 owned by `u0_aXXX`).
   - ADB shell (UID 2000) and the KaosGFX Manager app cannot directly write to a retail game's `/data/user/0/<pkg>` directory.
   - Solution: Drivers are staged to external storage `/sdcard/Android/data/<pkg>/files/kaosgfx/` (accessible to shell via `ext_data_rw` and accessible to the target app without permissions). `TurnipAppComponentFactory` in-process code copies the driver into internal executable storage (`aInfo.dataDir + "/files/turnip/"`) and loads it via `adrenotools_open_libvulkan`.

---

### Step-by-Step Implementation Progression

1. **Phase 0 Spike (`spike/vkprobe`)**:
   - Standalone NDK application linking `libadrenotools` and bundling a Mesa Turnip 27.0.0-devel driver package in assets.
   - Verified driver loading and physical device enumeration on Qualcomm Adreno 750 / Android 16.

2. **Phase 1 Native Bridge & Bootstrap (`bridge/`, `bootstrap/`)**:
   - Created `bridge` module with `libkaosgfx_bridge.so` and `plt_hook.cpp`.
   - Created `bootstrap` module with `TurnipAppComponentFactory.java` chaining to original `AppComponentFactory`, handling `extractNativeLibs="false"`, and implementing crash guard rollback.
   - Configured `generateBootstrapDex` task packaging `classes.jar` from AAR dependencies (`bytehook` + `shadowhook`) into `bootstrap.dex` via D8.

3. **Phase 2 Core APK Patcher Engine (`patcher/`)**:
   - Implemented `ManifestPatcher.kt` parsing binary XML using ARSCLib, injecting `TurnipAppComponentFactory`, and backing up original factory to metadata.
   - Implemented `ApkSignerUtil.kt` signing with v1+v2+v3 schemes and 16KB library page alignment (`-P 16`).
   - Implemented `ApkPatcherEngine.kt` allocating next DEX (`classesN.dex`), staging hook libraries (`lib/arm64-v8a/*.so`), stripping stale `META-INF`, and aligning `resources.arsc` uncompressed with 4-byte alignment using `ZipAligner.apkAligner()`.
   - Verified with unit tests and physical device installation.

4. **Phase 3 Shizuku Privileged Service (`privileged/`)**:
   - Implemented AIDL interface `IPrivilegedService.aidl`:
     - `installPackage(baseApkPath, splitApkPaths)` with `--bypass-low-target-sdk-block` and `-r`
     - `forceStop(packageName)`
     - `setGlobalSetting(key, value)` / `getGlobalSetting(key)`
     - `stageDriverBundle(packageName, driverZipPath, configJson)`
     - `destroy()`
   - Implemented `PrivilegedService.kt` running under UID 2000 with multi-split `pm install-create`/`install-write`/`install-commit` session manager.
   - Implemented `PrivilegedClient.kt` with coroutines, permission listener, and connection state flow.

5. **Phase 3b Driver Catalog & Downloader (`drivers/`)**:
   - Implemented `DriverCatalogRepository.kt` querying GitHub Releases API with ETag caching for community repositories:
     - `The412Banner/Banners-Turnip`
     - `K11MCH1/AdrenoToolsDrivers`
     - `whitebelyash/AdrenoToolsDrivers`
     - `StevenMXZ/Adreno-Tools-Drivers`
     - `MrPurple666/purple-turnip`
   - Implemented `DriverDownloader.kt` with OkHttp streaming, progress tracking, and zip `meta.json` inspection.
   - Implemented `DriverManager.kt` generating `kaosgfx_driver_config.json` and managing local imports.

6. **Phase 4 Full Android Application (`app/`)**:
   - Built complete Jetpack Compose Material 3 UI:
     - `GamesScreen`: Installed games scanner, search bar, Shizuku banner, status badges (`Custom Turnip Active`, `Stock Driver`, `Anti-Cheat Protected`).
     - `GameDetailSheet`: Driver selector, ANGLE GLES switch, patch orchestrator trigger, restore to stock.
     - `CatalogScreen`: Browse online Turnip releases, download with progress indicator, import custom zip.
     - `DiagnosticsScreen`: Real-time hardware diagnostics (SM8650, Adreno 750, 4KB page size, Shizuku state, Vulkan probe).
   - Implemented `GameScanner.kt` with competitive game denylist (`GenshinImpact`, `PUBG`, `CoD Mobile`, `Wild Rift`, `Free Fire`, `Fortnite`, etc.) to protect user accounts.
   - Implemented `PatchOrchestrator.kt` coordinating the 5-step end-to-end patch, install, and stage workflow.

---

### Exhaustive Issues & Fixes Log

1. **AGP 9.0 Kotlin Plugin Error**:
   - *Error*: `The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0`.
   - *Fix*: Removed explicit kotlin plugin and legacy `kotlinOptions` block.
2. **Missing `android_dlextinfo`**:
   - *Error*: Header missing in `hooks.cpp`.
   - *Fix*: Added `#include <android/dlext.h>`.
3. **D8 Directory Input Rejection**:
   - *Error*: D8 CLI rejected directory input for classes.
   - *Fix*: Dynamically passed individual `.class` and `.jar` paths.
4. **AGP Java Resource Stripping**:
   - *Error*: AGP stripped `.so` files placed in `resources/` during JAR packaging.
   - *Fix*: Staged hook libraries with `.bin` extension and renamed them during patch injection.
5. **Duplicate Entry in APK**:
   - *Error*: `ZipException: duplicate entry: lib/arm64-v8a/libhook_impl.so`.
   - *Fix*: Filtered original entries matching injected library names before adding new ones.
6. **Install Failure [-124: INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION]**:
   - *Error*: `resources.arsc` was compressed in patched APK.
   - *Fix*: Used ARSCLib's `ApkModule` with `ZipAligner.apkAligner()` ensuring `resources.arsc` is uncompressed and 4-byte aligned.
7. **ClassNotFoundException: com.bytedance.shadowhook.ShadowHook**:
   - *Error*: JNI class missing from `bootstrap.dex` at runtime.
   - *Fix*: Extracted `classes.jar` from `bytehook` and `shadowhook` AARs and merged them into D8 compilation step.
8. **ShadowHook Linker Mod Init Error 12 on Android 16**:
   - *Error*: `shadowhook_init` failed with `SHADOWHOOK_ERRNO_INIT_LINKER` (error 12) due to Bionic internal struct changes.
   - *Fix*: Replaced ByteHook/ShadowHook dynamic loader hook with our own direct ELF PLT/GOT relocation parser (`plt_hook.cpp`).
9. **SIGABRT in `linkernsbypass_namespace_dlopen`**:
   - *Error*: `BYTEHOOK_STACK_SCOPE()` aborted inside `kaos_dlopen` when called from direct GOT hook.
   - *Fix*: Removed `BYTEHOOK_STACK_SCOPE()` and ByteHook delegation from `hooks.cpp`; delegated cleanly to original pointers with non-recursive dlopen and dynamic hook refresh.
10. **Gradle JVM Memory Thrashing**:
    - *Error*: Gradle daemon stopped due to JVM garbage collector thrashing on default 512MB heap.
    - *Fix*: Created `gradle.properties` configuring `org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m -XX:+UseG1GC`.
11. **Serialization Default Field Omission**:
    - *Error*: `testDriverModelsSerialization` failed on `libraryName` assertion.
    - *Fix*: Configured Json serializer with `encodeDefaults = true`.

---

### Task Completion Status
- [x] Phase 0: Standalone `vkprobe` spike verified on physical hardware.
- [x] Phase 1: Native bridge, PLT/GOT hook engine, bootstrap DEX.
- [x] Phase 2: APK patcher engine (manifest, DEX, libs, alignment, v1-v3 signing).
- [x] Phase 3: Shizuku privileged service & AIDL IPC.
- [x] Phase 3b: Driver catalog, GitHub Releases parser with ETag caching, downloader, manager.
- [x] Phase 4: Full Jetpack Compose Material 3 application (`dev.kaos.kaosgfx`).
- [x] Phase 5: GLES via ANGLE integration.
- [x] Phase 6: All unit tests passing across all modules.
- [x] Physical device installation and runtime verification complete.
