# KaosGFX

**KaosGFX** is a rootless Android graphics driver orchestrator and APK patcher for Qualcomm Snapdragon (Adreno) devices. It enables selected Android games to run with bleeding-edge open-source **Mesa Turnip** Vulkan drivers or optimized OEM driver releases via [libadrenotools](https://github.com/bylaws/libadrenotools) and [ByteHook](https://github.com/bytedance/bhook), privileged through [Shizuku](https://shizuku.rikka.app/).

---

## Architecture Overview

Android security boundaries isolate retail applications (`untrusted_app`) from external runtime code injection. KaosGFX solves this without root access through a hybrid instrumentation architecture:

1. **Lightweight Manifest Wrapping**: Injects `TurnipAppComponentFactory` into `AndroidManifest.xml`, ensuring bridge initialization executes before any application or rendering code.
2. **PLT Redirection (`ByteHook`)**: Redirects `dlopen("libvulkan.so")`, `dlsym`, and direct `vk*` imports from game shared libraries to the custom Vulkan loader namespace created by `libadrenotools`.
3. **Privileged Management (`Shizuku`)**: Automates split-APK extraction, atomic external storage preservation (`/sdcard/Android/obb` and `/sdcard/Android/data`), silent multi-split installation sessions, file permissions fixup via `StorageManager`, and ANGLE global driver routing.
4. **Driver Catalog**: Integrated download catalog connecting directly to community builds (Banners-Turnip, K11MCH1, whitebelyash, StevenMXZ, MrPurple) with pre-flight device validation and safety checks.

---

## Project Structure

```
KaosGFX/
├── app/          # Jetpack Compose UI (Material 3), game list, driver manager
├── privileged/   # Shizuku UserService (AIDL), package installer, storage fixups
├── patcher/      # AXML manifest patcher, DEX injector, APK signer (apksig)
├── bridge/       # Native C++ redirection layer (libadrenotools, ByteHook, hooks)
├── bootstrap/    # AppComponentFactory Java bootstrap compiled to classesN.dex
├── drivers/      # Driver package parser, GitHub Releases client, validation probe
└── spike/        # Standalone verification probes (vkprobe)
```

---

## Prerequisites

- **Host**: JDK 21, Gradle, Android SDK Platform 35/36, Android NDK r27+
- **Device**: Qualcomm Snapdragon SoC (Adreno 6xx, 7xx, 8xx), Android 9+ (API 28+), [Shizuku](https://shizuku.rikka.app/) running

---

## License

BSD-3-Clause License. See [LICENSE](LICENSE) for details.
Third-party dependencies:
- `libadrenotools`: BSD-2-Clause
- `ByteHook`: MIT License
- `apksig`: Apache License 2.0
- `Shizuku API`: Apache License 2.0
