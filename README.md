# KaosGFX

[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD--3--Clause-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%209%20to%2016-green.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Arch-arm64--v8a-orange.svg)](https://developer.android.com/ndk)
[![Vulkan](https://img.shields.io/badge/Vulkan-1.3%20%7C%201.4-red.svg)](https://www.vulkan.org)

**KaosGFX** is a rootless Android graphics driver orchestrator and runtime instrumentation platform for Qualcomm Snapdragon (Adreno) devices. It enables Android games and graphical applications to run with bleeding-edge open-source **Mesa Turnip** Vulkan drivers or custom vendor Adreno driver releases via [libadrenotools](https://github.com/bylaws/libadrenotools) and direct in-process ELF PLT/GOT relocation hooking, privileged through [Shizuku](https://shizuku.rikka.app/).

---

## Key Features

- **Rootless In-Process Bootstrap**: Injects an `AppComponentFactory` wrapper and custom 64-bit ELF PLT/GOT relocation engine into target APKs, executing before game rendering loops without needing root or kernel modifications.
- **Mesa Turnip & Adreno Driver Support**: Load custom Vulkan ICDs (e.g., Mesa Turnip reporting Vulkan 1.4.363 on Snapdragon 8 Gen 3 / Adreno 750) with complete isolation from system drivers.
- **OpenGL ES via ANGLE**: Integrated toggle to route OpenGL ES graphics API calls through Vulkan via Google's ANGLE translation layer.
- **Privileged Automation via Shizuku**: Automates multi-split APK installations with `--bypass-low-target-sdk-block`, data preservation, and package lifecycle control without ADB cables after initial setup.
- **Integrated Driver Catalog**: Connects directly to verified community releases (Banners-Turnip, K11MCH1, Whitebelyash, StevenMXZ, MrPurple) with background downloads and ETag caching.
- **Real-Time Pipeline Monitoring**:
  - **On-Screen Startup Toast**: Displays a visual indicator (`🎮 KaosGFX: Turnip Pipeline Active (<driver_name>)`) directly inside the game on launch.
  - **Live Process & PID Tracking**: Real-time status indicator in game detail sheets showing whether the patched target process is running.
  - **Integrated Logcat Inspector**: Direct log viewer in KaosGFX streaming graphics driver interception logs (`KaosGFX-Bootstrap`, `KaosGFX-Bridge`, `KaosGFX-PLTHook`, `Turnip`, `vulkan`) via privileged AIDL IPC.
- **Automatic Crash Guard**: Implements an automatic rollback flag file mechanism; if a custom driver crashes on launch, KaosGFX rolls back to the stock driver on the next launch.
- **Anti-Cheat Protection Policy**: Built-in denylist preventing modification of competitive titles (Genshin Impact, PUBG, CoD Mobile, Wild Rift, etc.) to safeguard user accounts.

---

## Architecture Overview

```
                      +-----------------------------+
                      |     KaosGFX Manager App     |
                      |  (Jetpack Compose UI / M3)  |
                      +--------------+--------------+
                                     |
              +----------------------+----------------------+
              |                                             |
              v                                             v
  +-----------------------+                     +-----------------------+
  |    Driver Catalog     |                     |   Shizuku Service     |
  |  (GitHub API / OkHttp)|                     |  (AIDL / UID 2000)    |
  +-----------------------+                     +-----------+-----------+
                                                            |
                                             Silent Install / Global Settings
                                                            |
                                                            v
                      +-------------------------------------+
                      |         Target Retail Game          |
                      |    +---------------------------+    |
                      |    | TurnipAppComponentFactory |    |  <-- Early entrypoint
                      |    +-------------+-------------+    |
                      |                  |                  |
                      |                  v                  |
                      |    +---------------------------+    |
                      |    |   Native Bridge Engine    |    |
                      |    |    - libadrenotools       |    |
                      |    |    - Direct PLT/GOT Hook  |    |  <-- Intercepts dlopen("libvulkan.so")
                      |    +-------------+-------------+    |
                      |                  |                  |
                      |                  v                  |
                      |    +---------------------------+    |
                      |    |     Mesa Turnip Driver    |    |  <-- Custom Vulkan ICD
                      |    +---------------------------+    |
                      +-------------------------------------+
```

---

## Project Structure

```
KaosGFX/
├── app/                  # Main Android Application (UI, catalog, scanner, orchestrator)
├── patcher/              # Core APK patcher (binary XML, DEX injector, alignment, v1-v3 signer)
├── bootstrap/            # TurnipAppComponentFactory & bootstrap DEX builder (D8)
├── bridge/               # Native C++ hook bridge & 64-bit ELF PLT/GOT relocation engine
├── drivers/              # Driver catalog repository, GitHub client, downloader, manager
├── privileged/           # Shizuku AIDL service (IUserService), IPC client, install session
├── spike/vkprobe/        # Hardware verification testbed & empirical Vulkan probe
└── third_party/          # libadrenotools submodule
```

---

## Prerequisites

### Build Environment
- **JDK**: Java 21
- **Gradle**: 9.x+ (configured with 4GB heap in `gradle.properties`)
- **Android SDK**: Build Tools 36.0.0, API 36 (Android 16)
- **Android NDK**: NDK r27+ (`27.2.12479018`)
- **CMake**: 3.22.1+

### Target Device
- **Processor**: Qualcomm Snapdragon SoC (Adreno 6xx, 7xx, 8xx series)
- **Android Version**: Android 9.0+ (API 28+) up to Android 16 (API 36)
- **Page Size**: 4KB or 16KB kernel pages supported
- **Privilege Provider**: [Shizuku](https://shizuku.rikka.app/) active and running

---

## Building and Installation

### 1. Clone with Submodules
```bash
git clone --recursive https://github.com/kaos/KaosGFX.git
cd KaosGFX
```

### 2. Configure Local Properties
Create `local.properties`:
```properties
sdk.dir=/path/to/android-sdk
ndk.dir=/path/to/android-sdk/ndk/27.2.12479018
```

### 3. Build Native Bridge and Bootstrap DEX
```bash
./gradlew :bridge:packageNativeLibsToPatcherResources :bootstrap:generateBootstrapDex
```

### 4. Build and Install KaosGFX
```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Usage Guide

1. **Launch Shizuku**: Ensure Shizuku is running on your device (via Wireless Debugging or ADB).
2. **Open KaosGFX**: Grant Shizuku permission when prompted on the home screen.
3. **Download Drivers**: Navigate to the **Catalog** tab to download your preferred Turnip driver release (or tap the floating action button to import a local `.zip` file).
4. **Configure Game**: Return to the **Games** tab, select an installed game, choose your graphics driver, and toggle ANGLE GLES if desired.
5. **Patch & Launch**: Tap **Patch & Enable Custom Driver**. KaosGFX will patch the APK, install it silently via Shizuku, stage the driver bundle, and apply graphics settings.

---

## Hardware Verification

The in-process injection pipeline was empirically tested and verified on physical hardware:
- **Device**: vivo I2220 (iQOO 12), Snapdragon 8 Gen 3 (SM8650), Adreno 750
- **Environment**: Android 16 (OriginOS 7), Linux 6.1.75, SELinux Enforcing
- **Result**: `dlopen("libvulkan.so")` intercepted in-process via direct GOT relocation, returning custom Vulkan handle and creating an instance with `Turnip Adreno (TM) 750` reporting Vulkan 1.4.363 (`VK_DRIVER_ID_MESA_TURNIP` 18).

---

## Contributing

Contributions, bug reports, and driver compatibility profiles are welcome! Please open an issue or pull request on GitHub.

---

## License

KaosGFX is licensed under the **BSD-3-Clause License**. See [LICENSE](LICENSE) for details.

Third-Party Components:
- `libadrenotools`: BSD-2-Clause License
- `apksig`: Apache License 2.0
- `Shizuku API`: Apache License 2.0
- `ARSCLib`: Apache License 2.0
