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
12. **Package Installation Failed -1 (Private Storage Boundary)**:
    - *Error*: `PrivilegedService` reported `Base APK not found at /data/user/0/dev.kaos.kaosgfx/cache/...` because UID 2000 (shell) cannot enter `u0_aXXX` mode 700 private directories.
    - *Fix*: Redirected patch working directory to `Downloads/KaosGFX/` and `context.getExternalFilesDir("patch_work")` on shared external storage (`/sdcard/`) where UID 2000 has read access.
13. **Signature Mismatch on Retail Game Update (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`)**:
    - *Error*: Android Package Manager refuses `pm install -r` if the replacement APK signature does not match the original Play Store key.
    - *Fix*: Implemented `installPackageDetailed` in `PrivilegedService.kt` with automatic signature-mismatch recovery (backs up `/sdcard/Android/data` and `/obb`, executes `pm uninstall --user 0`, installs the patched APK, and restores data directories). Added `MANAGE_EXTERNAL_STORAGE` permission.
14. **System Server FUSE SELinux Denial (`u:object_r:fuse:s0`)**:
    - *Error*: `pm install /storage/emulated/0/...` failed with exit code 255: `System server has no access to read file context u:object_r:fuse:s0`. On modern Android, `system_server` is forbidden by SELinux from reading APKs from shared external FUSE storage directly.
    - *Fix*: In `PrivilegedService.kt`, staged the APKs into `/data/local/tmp/kaos_staging/` (which has context `u:object_r:shell_data_file:s0` and is world-readable by `system_server`) before executing `pm install`, then automatically cleaned up staging files upon completion.
15. **Driver Packaging & Unpacking Across Storage Boundaries**:
    - *Error*: Custom Turnip driver binary (`libvulkan_freedreno.so`) was missing inside `/data/user/0/<pkg>/files/turnip/` because Shizuku stages driver zips to external storage (`/sdcard/Android/data/<pkg>/files/kaosgfx/driver.zip`) and Android enforces W^X (`noexec`) on external storage.
    - *Fix*: Added automated driver download in `PatchOrchestrator.kt`, enhanced `TurnipAppComponentFactory.java` to inspect both external and internal staging paths, extract `driver.zip` into `files/turnip/` on first launch inside the app's own security context, and mark extracted `.so` files executable.
16. **Pipeline Visibility & Real-time Verification**:
    - *Feature*: Added multi-layer verification for active custom driver pipelines:
      1. On-Screen Startup Toast: Injected `TurnipAppComponentFactory` automatically displays a confirmation toast (`🎮 KaosGFX: Turnip Pipeline Active (<driver_name>)`) when the game starts.
      2. In-App Live Pipeline Status: `GameDetailSheet.kt` monitors the live process state (`Running (PID: ...)` vs `Idle`) via Shizuku.
      3. Live Logcat Stream: Added `Inspect Live Pipeline Logs` dialog directly in `GameDetailSheet.kt` powered by privileged `getPipelineLogs` AIDL call filtering `KaosGFX-Bootstrap`, `KaosGFX-Bridge`, `KaosGFX-PLTHook`, `Turnip`, and `vulkan`.
17. **App Listing & Game Detection Criteria**:
    - *Issue*: Castlevania (`jp.konami.epjCastlevania1`) and many user games/emulators were hidden because they did not explicitly declare `android:appCategory="game"` or `FLAG_IS_GAME` in their manifest.
    - *Fix*: Removed the restrictive game category filter. `GameScanner.kt` now lists all patchable user-installed applications, launchable apps, and patched apps, prioritizing patched apps at the top of the list. Added a "System Apps" toggle in `GamesScreen.kt` for optionally displaying system packages.
18. **Game Detail Sheet Layout & Driver Search UX**:
    - *Issue*: Long catalog driver lists required excessive scrolling to reach the ANGLE toggle and action buttons.
    - *Fix*: Reorganized `GameDetailSheet.kt` to place the "Enable ANGLE" toggle, "Update / Patch Custom Driver" button, and "Restore to Stock" button right at the top directly under the header/status card. Added an active driver summary card and an instant driver search bar filtering by name, version, author, and library name.
19. **Complete Stock Restoration Engine**:
    - *Issue*: "Restore to Stock Configuration" was passing `null` for `originalApk`, merely resetting ANGLE and leaving the patched APK with `TurnipAppComponentFactory` installed on the device.
    - *Fix*: Implemented end-to-end stock restoration in `PatchOrchestrator.kt`, `ApkPatcherEngine.kt`, and `ManifestPatcher.kt`:
      1. Pre-patch stock backup: `patchAndInstallGame` now creates a pristine backup of `base.apk` at `${packageName}-stock.apk` before patching.
      2. Dynamic unpatch fallback: If no prior stock backup exists on disk, `unpatchBaseApk` reverses the binary XML modifications (removing `TurnipAppComponentFactory` and restoring any original factory), deletes the injected bootstrap DEX and the 7 native hook libraries, and signs the restored APK.
      3. Complete cleanup: Installs the stock APK via privileged installer, removes staged driver files from `/sdcard/Android/data/<pkg>/files/kaosgfx`, resets ANGLE routing, force-stops the app, and triggers a full game list refresh.
20. **Unified Shared Download Storage for Driver Archives & Cross-Process Staging**:
    - *Issue*: `DriverDownloader.kt`, `DriverManager.kt`, and `DriverCatalogRepository.kt` previously stored driver archives in internal private app storage (`/data/user/0/dev.kaos.kaosgfx/files/drivers/`). Because Shizuku's `PrivilegedService` executes under shell UID 2000, Android DAC / SELinux boundaries (`0700` owned by app UID `u0_a304`) prevented UID 2000 from reading the driver `.zip` when staging to `/sdcard/Android/data/<pkg>/files/kaosgfx/driver.zip`.
    - *Fix*: Implemented `DriverStorage.kt` to maintain all downloaded driver archives directly in the shared public directory (`/sdcard/Download/KaosGFX/`) alongside APKs. Added automated legacy migration transferring existing archives from app-private files into `Download/KaosGFX/`.
21. **Deterministic Active Driver Resolution in Game Detail Sheet**:
    - *Issue*: In `GameDetailSheet.kt`, `selectedDriver` defaulted to K11MCH1 v840 because all Turnip drivers share `libraryName = "libvulkan_freedreno.so"` and heuristic matching fell back to matching on library name.
    - *Fix*: Extended `DriverConfig` to embed `driverId` and `driverTitle` directly in `kaosgfx_driver_config.json`. Updated `GameDetailSheet.kt` to match by exact `driverId`, `driverTitle`, or driver directory prefix/suffix, preventing any fallback to generic library names and accurately preselecting and badging the active driver.
22. **Android Scoped Storage Data Isolation & Driver List UI Layout**:
    - *Issue*: On Android 11+ (API 30+), Scoped Storage denies non-root apps access to `/sdcard/Android/data/<other_package>` even with `MANAGE_EXTERNAL_STORAGE` permission granted. Attempting to read `kaosgfx_driver_config.json` directly from `/sdcard/Android/data/...` returned Permission Denied, causing `activeConfig` to evaluate to `null` and defaulting `selectedDriver` to the first downloaded driver (v840). Additionally, long driver names (e.g., `turnip_mrpurple_T30-toasted.adpkg - turnip_mrpurple_T30-toasted.adpkg.zip`) in `GameDetailSheet` lacked layout weight, pushing the `Downloaded` badge completely off the right edge of the screen.
    - *Fix*: 
      1. Added `getDriverConfig(packageName)` to `IPrivilegedService.aidl` allowing UID 2000 to read active driver configs across Scoped Storage boundaries.
      2. Configured `PatchOrchestrator.kt` to store a public shared copy of driver configs in `/sdcard/Download/KaosGFX/configs/${packageName}.json` for instant, permission-free reading.
      3. Updated `GameDetailSheet.kt` to read from the shared configs directory and asynchronously query Shizuku if needed, automatically synchronizing `selectedDriver` to `activeDriver`.
      4. Constrained driver item rows with `Modifier.weight(1f)` and text truncation (`maxLines = 1, overflow = TextOverflow.Ellipsis`), ensuring badges are always visible.
      5. Enhanced `markInstalledDrivers` with case-insensitive and filename/ID fuzzy matching.
23. **GitHub API Rate-Limit Fallback & Interactive VKProbe Live Verification UI**:
    - *Issue*: GitHub API unauthenticated requests (60 requests/hour/IP) hit HTTP 403 on the target device, causing `DriverCatalogRepository.kt` to drop releases for MrPurple and other sources. Furthermore, VKProbe's test UI was cluttered with raw logs and extracted its own bundled asset driver on launch instead of cleanly displaying the live in-process Vulkan driver being used by the system.
    - *Fix*:
      1. Implemented persistent catalog caching in `/sdcard/Download/KaosGFX/catalog_cache.json`. When GitHub rate limits (403), the repository automatically falls back to cached releases per source.
      2. Added built-in catalog definitions for MrPurple (T30, T29, T28) and K11MCH1 (v840, v837), and guaranteed that `getInstalledDrivers()` from `/sdcard/Download/KaosGFX/` are always merged into the catalog.
      3. Completely redesigned VKProbe's UI with a high-contrast status dashboard displaying the active Vulkan device name, driver name, driver info, Vulkan API version, driver ID, and an unmistakable badge: `[🟢 CUSTOM TURNIP DRIVER ACTIVE]` or `[⚪ STOCK SYSTEM DRIVER ACTIVE (Qualcomm Adreno)]`. Added a "Re-scan Active Driver" button so users can instantly verify driver changes after patching.
24. **Driver Dynamic Library Auto-Discovery & Zero-Hardcoding Pipeline**:
    - *Issue*: Custom driver zip packages use varying library names inside their archives (`vulkan.purple.so` for MrPurple T30, `vulkan.adreno.so` for Qualcomm 840, `libvulkan_freedreno.so` for Banners). Because the catalog previously defaulted `libraryName` to `libvulkan_freedreno.so`, `TurnipAppComponentFactory` failed to find `libvulkan_freedreno.so` inside the extracted directory and fell back to loading the stale test driver (`files/turnip/libvulkan_freedreno.so`).
    - *Fix*:
      1. Updated `DriverManager.kt` `buildConfigJson` to inspect `meta.json` from the local driver zip on disk and write the exact `libraryName` into `kaosgfx_driver_config.json`.
      2. Updated `TurnipAppComponentFactory.java` to inspect `meta.json` inside `driverDir` and dynamically autodetect any valid `.so` library, removing the stale fallback.
      3. Rebuilt `bootstrap.dex` with D8 and updated `PatchOrchestrator.kt` stock APK backup logic.
      4. Installed official Sascha Willems **Vulkan Hardware Capability Viewer** (`de.saschawillems.vulkancapsviewer`) on device as an independent industry benchmark tool.

---

### Task Completion Status
- [x] Phase 0: Standalone `vkprobe` spike verified on physical hardware.
  - **Issue**: Patched apps (Vulkan Caps Viewer `de.saschawillems.vulkancapsviewer` and VKProbe `dev.kaos.kaosgfx.spike.vkprobe`) displayed logcat error: `Custom driver not found at ... skipping hook injection`.
    - *Empirical Diagnostics & Root Cause Analysis*:
      1. Android W^X & Scoped Storage: Target retail applications run in the `untrusted_app` SELinux domain without external storage permissions (`READ_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE`).
      2. Traversal blocked: Path traversal of `/sdcard/Android/data/<package>/` via standard Java `File` APIs throws `Permission denied` when executed within early app startup before `ContextImpl` is instantiated.
      3. Android Linker W^X: `linker64` prevents loading shared libraries (`dlopen`) directly from external storage or FUSE filesystems due to `noexec` mounts.
      4. Stale Injected DEX Shadowing: `PatchOrchestrator.kt` previously passed `baseApk` (the currently installed, already-patched APK) into `ApkPatcherEngine.patchBaseApk` instead of the pristine `stockBackup`. Each successive patch appended a new DEX (`classes2.dex`, `classes3.dex`, `classes4.dex`, `classes5.dex`). Because ART/Dalvik classloaders load classes from the first DEX in order, the original stale `classes2.dex` (which lacked `/data/local/tmp` lookups) permanently shadowed the newer DEXes.
    - *Fix Implemented*:
      1. Updated `PrivilegedService.kt` to stage `driver.zip` and `kaosgfx_driver_config.json` to `/data/local/tmp/kaosgfx/<packageName>/` with full world-readable permissions (`chmod -R 777`). Untrusted app domains have verified read access to `/data/local/tmp`.
      2. Updated `TurnipAppComponentFactory.java` to evaluate `/data/local/tmp/kaosgfx/<packageName>/` as the primary configuration and driver zip candidate, unpacking into the app's internal executable storage (`aInfo.dataDir + "/files/turnip/<driverId>"`), where `dlopen` has complete `r-x` permissions.
      3. Updated `PatchOrchestrator.kt` to always patch from the pristine `stockBackup` rather than an already patched APK, preventing DEX accumulation.
      4. Updated `ApkPatcherEngine.kt` so that if an already-patched APK is encountered, it replaces the existing injected DEX instead of compounding additional DEX files.
      5. Updated VKProbe's `MainActivity.kt` config reader and `PatchOrchestrator.kt` unpatcher to synchronize with `/data/local/tmp/kaosgfx/<packageName>/`.
      6. Recompiled `bootstrap.dex` and rebuilt `app-debug.apk` and `vkprobe-debug.apk`.

  - **Issue**: Multi-split applications (e.g. 3DMark `com.futuremark.dmandroid.application` with 10 splits) failed installation with: `Failure [INSTALL_FAILED_INVALID_APK: ... signatures are inconsistent]`.
    - *Root Cause*: `PatchOrchestrator.kt` patched and signed `base.apk` with the KaosGFX certificate (`kaosgfx_signing.p12`), but passed the raw installed split APKs (`split_config.*.apk`) unmodified. Android's PackageInstaller strictly enforces that all split APKs in an install session share identical signing certificates in their APK signature scheme blocks.
    - *Fix Implemented*:
      1. Updated `PatchOrchestrator.kt` to iterate over all split APKs in `game.splitApkPaths`, re-signing each split via `patcherEngine.reSignSplitApk()` using the matching KaosGFX credentials.
      2. Added pristine backup and restoration of split APKs (`${packageName}-stock-splits/`) to ensure clean stock restoration.
  - **Issue**: Screen rotation (portrait <-> landscape) caused the UI to recreate, dropping search filters and closing active game sheets.
    - *Fix Implemented*:
      1. Added `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize|uiMode"` to `MainActivity` in `AndroidManifest.xml` to prevent activity teardown on orientation change.
      2. Switched search states (`searchQuery` in `GamesScreen`, `driverSearchQuery` in `GameDetailSheet`, `selectedSourceId` in `CatalogScreen`) and screen navigation states to `rememberSaveable`.
      3. Tracked `selectedGamePackage` via `rememberSaveable` so open game detail sheets persist across orientation changes and recompositions.

  - **License Update**: Updated project licensing to **GNU Affero General Public License v3 (AGPL-3.0)** (Copyright (C) 2026 Vidish (Kaos)). Synchronized `README.md` badges and license sections.

  - **Release Published**: Created and uploaded GitHub Pre-release `v1.0.0-preview` on [`https://github.com/ulolol/Ka0SGFX/releases/tag/v1.0.0-preview`](https://github.com/ulolol/Ka0SGFX/releases/tag/v1.0.0-preview) containing `KaosGFX-v1.0.0-preview.apk` (64.99 MB) and companion diagnostic probe `VKProbe-v1.0.0.apk` (5.56 MB).

- [x] Phase 1: Native bridge, PLT/GOT hook engine, bootstrap DEX.
- [x] Phase 2: APK patcher engine (manifest, DEX, libs, alignment, v1-v3 signing).
- [x] Phase 3: Shizuku privileged service & AIDL IPC.
- [x] Phase 3b: Driver catalog, GitHub Releases parser with ETag caching, downloader, manager.
- [x] Phase 4: Full Jetpack Compose Material 3 application (`dev.kaos.kaosgfx`).
- [x] Phase 5: GLES via ANGLE integration.
- [x] Phase 6: All unit tests passing across all modules.
- [x] Physical device installation and runtime verification complete.


---

## 2026-11-01: Zero-Patch Root Engine & Hardware GPU Generation Filtering (`A6xx` / `A7xx` / `A8xx`)

### Key Findings & Empirical Reverse Engineering
1. **Target Hardware Profile (`73ccb990`)**:
   - Device: Xiaomi POCO F7 / Redmi Turbo 4 Pro (`25053PC47I`, board `sun` / `onyx`), Qualcomm Snapdragon 8s Gen 4 (`SM8735` / `SM8845`), **Adreno 825 (`A8xx`)** GPU.
   - OS: Android 16 (API 36), Linux 6.6.77, 4KB Page Size, **KernelSU Next (`ksud 3.0.1`)** without Zygisk.
   - Stock Vulkan Driver: `com.qualcomm.qti.gpudrivers.sun.api35` (`/vendor/lib64/hw/vulkan.adreno.so`).

2. **Empirical Disassembly of Android 16 Vulkan & GraphicsEnvironment Loader**:
   - Disassembled `/system/bin/gpuservice` (`0x6d464`–`0x6d58c`), `/system/lib64/libgraphicsenv.so` (`0xb60c`–`0xb900`), `/system/lib64/libvulkan.so` (`EnsureInitialized` at `0x1e76c`–`0x1e950` and `LoadUpdatedDriver` at `0x22538`–`0x22680`), `/system/lib64/libhwui.so` (`zygote_preload_graphics` at `0x71a704`), and `/system/framework/framework.jar` (`GraphicsEnvironment.chooseDriver` at `0x257fe0`, `chooseDriverInternal` at `0x25869c`, and `chooseAbi` at `0x257ab0`).
   - **Why `live-gpu-driver`'s post-fork `inotify` bind-mount is insufficient on its own on Android 16**:
     - `zygote64` calls `zygote_preload_graphics()` -> `vkEnumerateInstanceVersion()` at boot, preloading `/vendor/lib64/hw/vulkan.adreno.so` into `g_datachunk->hal_module_` (`[x22, #0xf8]`) in `libvulkan.so`.
     - However, in `libvulkan.so` (`EnsureInitialized` at `0x1e7b4`–`0x1e8a8`), every forked app process calls `GraphicsEnv::getInstance().getDriverNamespace()`. When `getDriverNamespace()` returns non-null, `libvulkan.so` logs `"Unload builtin Vulkan driver."`, unloads the Zygote-preloaded HAL via `android_unload_sphal_library()`, and loads `vulkan.${ro.hardware.vulkan}.so` (`vulkan.adreno.so`) from the `updatable gfx driver` namespace!
   - **Zero-Patch, Zero-Zygisk, Zero-Reboot Per-App Root Mechanism**:
     - Adding a target `packageName` to `Settings.Global.updatable_driver_production_opt_in_apps` causes `GraphicsEnvironment.chooseDriverInternal` to bypass allowlist/denylist/debuggable checks and select `SystemProperties.get("ro.gfx.driver.0")`.
     - `chooseDriver` queries `PackageManager.getPackageInfo(driverPkg, MATCH_SYSTEM_ONLY | GET_META_DATA)` (`0x00100080`), which requires the carrier package to satisfy 5 conditions: (1) `FLAG_SYSTEM`, (2) `forceQueryable=true`, (3) `targetSdkVersion >= 26`, (4) `primaryCpuAbi == "arm64-v8a"`, and (5) `applicationInfo.metaData != null`.
     - Staging `vulkan.adreno.so` into a qualifying system carrier's `lib/arm64` directory (e.g. `com.google.android.apps.wellbeing`, `com.google.android.tts`, or an overlay on `com.google.android.ext.services`), setting `resetprop ro.gfx.driver.0 <carrierPkg>`, and opting in `<targetPkg>` in `updatable_driver_production_opt_in_apps` loads the custom Turnip/Adreno driver per-app with **zero APK modification, zero signature change, and zero race condition**.

3. **Empirical Verification of GPU Generation Filtering Importance**:
   - Loading a standard `A6xx/A7xx` Turnip build (`The412Banner` `20261027-r5`) on Adreno 825 caused Mesa to log `E TU : ../src/freedreno/vulkan/tu_device.cc:1715: device (chip_id = 44030000, gpu_id = 6830) is unsupported (VK_ERROR_INCOMPATIBLE_DRIVER)` and abort `RenderThread`.
   - Loading an `A8xx` Turnip build (`StevenMXZ/Adreno-Tools-Drivers` `Turnip_Gen8_V38.zip`) on the exact same unpatched app succeeded cleanly (`D vulkan : Unload builtin Vulkan driver.`, `I MESA : Using fallback gralloc implementation`).

### Implementation Summary
1. **`drivers/GpuInfo.kt`**:
   - Implemented `AdrenoGen` (`A6XX`, `A7XX`, `A8XX`, `UNKNOWN`), `AdrenoGpu`, and `GpuDetector` with 3-tier hardware detection (`/sys/class/kgsl/kgsl-3d0/gpu_model` -> 1x1 Pbuffer EGL `GL_RENDERER` -> `Build.SOC_MODEL` lookup table).
2. **`drivers/GpuSupportParser.kt`, `DriverModels.kt`, & `DriverCatalogRepository.kt`**:
   - Implemented multi-source release & asset parser handling asset tags (`-A8xx`, `Gen8`, `710-720`, `8G2`), sibling asset disambiguation (standard `Turnip-*.zip` vs `-A8xx.zip` in `Banners-Turnip`), release body tables (`Unified: A6XX, A7XX, A8XX`, `For a7xx. Maybe a6xx.`), and driver type classification (`Mesa Turnip` vs `Qualcomm Blob`).
   - Added unit test suite `GpuSupportParserTest.kt`.
3. **`privileged/RootDriverManager.kt` & `PrivilegedClient.kt`**:
   - Implemented `RootDriverManager` supporting both direct `su -c` and Shizuku Root/Shell bridge (`hasShellRootAccess()` / `execRootScript()`), automatic qualifying carrier discovery (`resolveCarrierPackage()`), `applyRootDriver()`, `removeRootDriver()`, ANGLE routing, and live logcat inspection.
   - Unified `PrivilegedClient` with `OperatingMode` (`ROOT`, `SHIZUKU`, `UNAVAILABLE`) and user `ModePreference` (`AUTO`, `ROOT`, `SHIZUKU`).
4. **Compose UI (`CatalogScreen.kt`, `GameDetailSheet.kt`, `GamesScreen.kt`, `DiagnosticsScreen.kt`, `CommonComponents.kt`)**:
   - Added `PrivilegeModeBanner` and `GpuSupportBadges`.
   - Filtered `CatalogScreen` and `GameDetailSheet` by detected GPU generation by default (`164 compatible/unverified • 375 incompatible hidden` on Adreno 825) with a `"Show All (+375)"` toggle chip.
   - Updated `DiagnosticsScreen` with live hardware metrics and Root/Shizuku mode switching.

## 2026-11-01 — Post-v2.0.0 Hotfix: Native Default GL Driver in Root Mode & Adaptive Icon Rendering

### Root Cause 1: Hybrid GL/Vulkan Apps Crashing in `libEGL.so` / `libGLESv2_adreno.so` when ANGLE is OFF
- **Question**: Do the downloaded AdrenoTools / Turnip `.zip` packages contain both OpenGL ES and Vulkan drivers?
  - **Answer**: **No.** All AdrenoTools / Turnip `.zip` packages (`libvulkan_freedreno.so`, `vulkan.adreno.so`, `vulkan.purple.so`) ship **only a Vulkan ICD (`.so`) and `meta.json`** — they do not include OpenGL ES libraries (`libEGL_adreno.so` / `libGLESv2_adreno.so`).
- **Why 3DMark and Vulkan Caps Viewer crashed when ANGLE was OFF**:
  - When an app is added to `updatable_driver_production_opt_in_apps`, Android's `GraphicsEnvironment` switches **both** `libvulkan.so` AND `libEGL.so` (`android::Loader::open`) to load from the `updatable gfx driver` linker namespace (`<carrier>/lib/arm64`).
  - When ANGLE is OFF (using the default native GL driver), `libEGL.so` unloads the system `/vendor/lib64/egl` GL driver (`Unload system gl driver. Load updated gl driver.`) and searches `<carrier>/lib/arm64` for `libEGL_adreno.so`, `libGLESv2_adreno.so`, `libGLESv1_CM_adreno.so`, `eglSubDriverAndroid.so`, the Adreno GL shader compiler (`libllvm-qgl.so`, `libllvm-glnext.so`, `libllvm-qcom.so`, `libadreno_app_profiles.so`), and their transitive `/vendor/lib64` `DT_NEEDED` libraries.
- **Fix (`RootDriverManager.kt`)**:
  - Updated `applyRootDriver()` to stage `/vendor/lib64/egl/*.so`, `/vendor/lib64/{libllvm-qgl.so,libllvm-glnext.so,libllvm-qcom.so,libadreno_app_profiles.so}`, and their transitive `/vendor/lib64/` `DT_NEEDED` libraries into `<carrier>/lib/arm64/` alongside the custom `vulkan.adreno.so`.
  - Verified on physical device `73ccb990` with **both** `de.saschawillems.vulkancapsviewer` and `com.futuremark.dmandroid.application` (3DMark) with **ANGLE OFF**: both apps load the **native default Qualcomm Adreno OpenGL ES driver (`0800.48`)** alongside the **custom Turnip Vulkan driver** without crashing.

### Root Cause 2: App Icons Missing for Most Installed Apps
- **Why**: `GamesScreen.kt` and `GameDetailSheet.kt` used `(game.icon as? BitmapDrawable)?.bitmap`, which returns `null` on Android 8.0+ for all apps using `AdaptiveIconDrawable`, `VectorDrawable`, or `LayerDrawable`.
- **Fix (`GameScanner.kt`)**:
  - Added `drawableToBitmapDrawable()` in `GameScanner.scanGames()` (on `Dispatchers.IO`) to rasterize any `Drawable` (`AdaptiveIconDrawable`, `VectorDrawable`, `LayerDrawable`, `BitmapDrawable`) onto an `ARGB_8888` `BitmapDrawable` so all app icons render properly across `GamesScreen` and `GameDetailSheet`.
