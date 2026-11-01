# KaosGFX

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2010%20to%2016-green.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Arch-arm64--v8a-orange.svg)](https://developer.android.com/ndk)
[![Vulkan](https://img.shields.io/badge/Vulkan-1.3%20%7C%201.4-red.svg)](https://www.vulkan.org)
[![Mode](https://img.shields.io/badge/Mode-Root%20(Zero%20Patch)%20%7C%20Shizuku%20(Non--Root)-brightgreen.svg)](#operating-modes)

**KaosGFX** is a unified Android graphics driver orchestrator and runtime instrumentation platform for Qualcomm Snapdragon (**Adreno 6xx, 7xx, and 8xx**) devices. It supports **two operating modes in a single APK**:
1. **Root Mode (Zero APK Patch)**: Switches Vulkan drivers per-app via Android's native `GraphicsEnvironment` updatable driver namespace and linker unload mechanism—keeping target APKs and signatures **100% original and unmodified** (no Zygisk or reboot required).
2. **Shizuku Mode (Non-Root APK Patch)**: Injects an `AppComponentFactory` bootstrap and deterministic 64-bit ELF PLT/GOT hook engine into target APKs via [libadrenotools](https://github.com/bylaws/libadrenotools) and [Shizuku](https://shizuku.rikka.app/).

---

## Key Features

- **Dual-Engine Architecture (Root Zero-Patch + Shizuku Non-Root)**:
  - **Root Mode (KernelSU Next / KernelSU / Magisk / APatch)**: Stages custom Vulkan HALs (`vulkan.adreno.so`) into a qualifying system `GraphicsEnvironment` carrier namespace (`ro.gfx.driver.0`) and opts in selected apps via `updatable_driver_production_opt_in_apps`. Target APKs are never modified or re-signed.
  - **Shizuku Mode (Rootless)**: Injects `TurnipAppComponentFactory` and a custom 64-bit ELF PLT/GOT relocation engine into target APKs for non-rooted devices.
- **Hardware-Aware GPU Generation Filtering (`A6xx` / `A7xx` / `A8xx`)**:
  - Automatically detects the device's exact Adreno GPU model and generation via `/sys/class/kgsl/kgsl-3d0/gpu_model`, 1x1 Pbuffer EGL `GL_RENDERER` probing, and SoC fallback tables.
  - Parses GitHub release notes and multi-asset filenames across all 5 community repositories to tag drivers (`A6xx`, `A7xx`, `A8xx`, `Adreno 710/720`, `Adreno 740`, `Mesa Turnip` vs `Qualcomm Blob`) and hides incompatible GPU builds by default (preventing `VK_ERROR_INCOMPATIBLE_DRIVER` crashes).
- **OpenGL ES via ANGLE**: Per-app toggle to route OpenGL ES calls through Vulkan via Google's ANGLE runtime.
- **Integrated Driver Catalog**: Connects directly to verified community repositories (`The412Banner/Banners-Turnip`, `K11MCH1/AdrenoToolsDrivers`, `whitebelyash/AdrenoToolsDrivers`, `StevenMXZ/Adreno-Tools-Drivers`, `MrPurple666/purple-turnip`) with persistent offline caching and local `.zip` import.
- **Real-Time Pipeline Monitoring**:
  - **Live Process & PID Tracking**: Real-time status badge in `GameDetailSheet` showing whether the target process is running.
  - **Integrated Logcat Inspector**: Streams live Vulkan/Mesa/Turnip logs (`MESA`, `TU`, `vulkan`, `GraphicsEnvironment`, `KaosGFX-Bootstrap`, `KaosGFX-Bridge`) directly inside the app.

---

## Operating Modes

| Feature | Root Mode (Zero APK Patch) | Shizuku Mode (Non-Root) |
| :--- | :--- | :--- |
| **Requirement** | KernelSU Next, KernelSU, Magisk, or APatch | Shizuku (Wireless Debugging / ADB) |
| **Target APK Modification** | **None (100% Untouched Original APK & Signature)** | Patched `base.apk` + Re-signed Split APKs |
| **Zygote Preload Handling** | Native `libvulkan.so` `Unload builtin Vulkan driver` via `GraphicsEnvironment` carrier namespace | In-process `libadrenotools` + ELF PLT/GOT `dlopen` hook |
| **Zygisk / Reboot Required** | **No** (Instant per-app switching) | **No** |
| **GPU Generation Filter** | Automatic (`A6xx` / `A7xx` / `A8xx`) | Automatic (`A6xx` / `A7xx` / `A8xx`) |

---

## Project Structure

```
KaosGFX/
├── app/                  # Main Android Application (UI, catalog, scanner, dual-mode orchestrator)
├── patcher/              # Non-root APK patcher (binary XML, DEX injector, alignment, v1-v3 signer)
├── bootstrap/            # TurnipAppComponentFactory & bootstrap DEX builder (D8)
├── bridge/               # Native C++ hook bridge & 64-bit ELF PLT/GOT relocation engine
├── drivers/              # GPU detector, GPU support parser, catalog repository, downloader, manager
├── privileged/           # RootDriverManager (Zero-Patch) & Shizuku AIDL service (IUserService)
├── spike/vkprobe/        # Hardware verification testbed & empirical Vulkan probe
└── third_party/          # libadrenotools submodule
```

---

## Building and Installation

### 1. Clone with Submodules
```bash
git clone --recursive https://github.com/ulolol/Ka0SGFX.git
cd Ka0SGFX
```

### 2. Configure Local Properties
Create `local.properties`:
```properties
sdk.dir=/path/to/android-sdk
ndk.dir=/path/to/android-sdk/ndk/27.2.12479018
```

### 3. Run Unit Tests & Build APK
```bash
./gradlew testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Hardware Verification

Empirically tested and verified across multiple generations of physical Qualcomm Snapdragon hardware on Android 16 (API 36):
- **Adreno 825 (`A8xx` — Snapdragon 8s Gen 4, Xiaomi `25053PC47I`)**: Verified **Root Zero-Patch Mode** on KernelSU Next (`3.0.1`) loading `Turnip_Gen8_V38` (`Mesa 27.0.0-R1`) per-app into unpatched applications (`D vulkan : Unload builtin Vulkan driver.`, `I MESA : Using fallback gralloc implementation`) while keeping all other apps on the stock Adreno 825 driver.
- **Adreno 750 (`A7xx` — Snapdragon 8 Gen 3, iQOO 12 `I2220`)**: Verified **Shizuku Non-Root Mode** intercepting `dlopen("libvulkan.so")` via PLT/GOT relocation and running `Turnip Adreno (TM) 750` (`VK_DRIVER_ID_MESA_TURNIP`).

---

## License

KaosGFX is licensed under the **GNU Affero General Public License v3 (AGPL-3.0)**. See [LICENSE](LICENSE) for details.
