#include "hooks.h"
#include <jni.h>
#include <string>
#include <unistd.h>
#include <sys/stat.h>
#include <android/log.h>
#include <adrenotools/driver.h>

#define TAG "KaosGFX-Bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static bool check_and_set_crash_guard(const std::string &pkgDataDir) {
    std::string flagPath = pkgDataDir + "/files/kaosgfx_crash_flag";
    struct stat st{};
    if (stat(flagPath.c_str(), &st) == 0) {
        LOGW("Crash guard triggered! Flag file exists: %s", flagPath.c_str());
        LOGW("A previous launch crashed while running custom driver. Falling back to stock driver.");
        unlink(flagPath.c_str());
        return false; // Abort custom driver loading
    }

    // Ensure files directory exists
    std::string filesDir = pkgDataDir + "/files";
    mkdir(filesDir.c_str(), 0755);

    // Create flag file
    FILE *fp = fopen(flagPath.c_str(), "w");
    if (fp) {
        fputs("active", fp);
        fclose(fp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_kaos_kaosgfx_bootstrap_TurnipAppComponentFactory_nativeInit(
    JNIEnv *env,
    jclass /* clazz */,
    jstring pkgDataDir,
    jstring pkgCodeDir,
    jstring hookLibDir,
    jstring customDriverDir,
    jstring customDriverName
) {
    const char *pkgDataDirC = env->GetStringUTFChars(pkgDataDir, nullptr);
    const char *pkgCodeDirC = env->GetStringUTFChars(pkgCodeDir, nullptr);
    const char *hookLibDirC = env->GetStringUTFChars(hookLibDir, nullptr);
    const char *customDriverDirC = env->GetStringUTFChars(customDriverDir, nullptr);
    const char *customDriverNameC = env->GetStringUTFChars(customDriverName, nullptr);

    std::string pkgDataDirStr(pkgDataDirC ? pkgDataDirC : "");
    std::string pkgCodeDirStr(pkgCodeDirC ? pkgCodeDirC : "");
    std::string hookLibDirStr(hookLibDirC ? hookLibDirC : "");
    std::string customDriverDirStr(customDriverDirC ? customDriverDirC : "");
    std::string customDriverNameStr(customDriverNameC ? customDriverNameC : "");

    env->ReleaseStringUTFChars(pkgDataDir, pkgDataDirC);
    env->ReleaseStringUTFChars(pkgCodeDir, pkgCodeDirC);
    env->ReleaseStringUTFChars(hookLibDir, hookLibDirC);
    env->ReleaseStringUTFChars(customDriverDir, customDriverDirC);
    env->ReleaseStringUTFChars(customDriverName, customDriverNameC);

    LOGI("TurnipAppComponentFactory nativeInit starting...");
    LOGI("  pkgDataDir: %s", pkgDataDirStr.c_str());
    LOGI("  pkgCodeDir: %s", pkgCodeDirStr.c_str());
    LOGI("  hookLibDir: %s", hookLibDirStr.c_str());
    LOGI("  customDriverDir: %s", customDriverDirStr.c_str());
    LOGI("  customDriverName: %s", customDriverNameStr.c_str());

    // 1. Check crash guard
    if (!check_and_set_crash_guard(pkgDataDirStr)) {
        return JNI_FALSE;
    }

    // 2. Enforce trailing slash for customDriverDir
    if (!customDriverDirStr.empty() && customDriverDirStr.back() != '/') {
        customDriverDirStr.push_back('/');
    }

    // 3. Open custom Vulkan driver via libadrenotools
    void *vkHandle = adrenotools_open_libvulkan(
        RTLD_NOW | RTLD_LOCAL,
        ADRENOTOOLS_DRIVER_CUSTOM,
        nullptr,
        hookLibDirStr.c_str(),
        customDriverDirStr.c_str(),
        customDriverNameStr.c_str(),
        nullptr,
        nullptr
    );

    if (!vkHandle) {
        LOGE("adrenotools_open_libvulkan failed to load %s%s: %s",
             customDriverDirStr.c_str(), customDriverNameStr.c_str(), dlerror());
        // Remove crash flag since driver failed gracefully without SIGSEGV
        std::string flagPath = pkgDataDirStr + "/files/kaosgfx_crash_flag";
        unlink(flagPath.c_str());
        return JNI_FALSE;
    }

    LOGI("adrenotools_open_libvulkan succeeded, handle = %p", vkHandle);

    // 4. Force early driver load before HWUI / Skia attempts to load vendor driver
    auto pfn_vkGetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
        dlsym(vkHandle, "vkGetInstanceProcAddr")
    );
    if (pfn_vkGetInstanceProcAddr) {
        auto pfn_vkEnumerateInstanceExtensionProperties =
            reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
                pfn_vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties")
            );
        if (pfn_vkEnumerateInstanceExtensionProperties) {
            uint32_t count = 0;
            pfn_vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr);
            LOGI("Early extension probe returned %u extensions (driver pre-warmed)", count);
        }
    }

    // 5. Initialize ByteHook & PLT hooks
    if (!init_hooks(vkHandle, pkgDataDirStr.c_str(), pkgCodeDirStr.c_str())) {
        LOGE("init_hooks failed!");
        std::string flagPath = pkgDataDirStr + "/files/kaosgfx_crash_flag";
        unlink(flagPath.c_str());
        return JNI_FALSE;
    }

    LOGI("KaosGFX native bridge initialized successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kaos_kaosgfx_bootstrap_TurnipAppComponentFactory_nativeRefreshHooks(
    JNIEnv * /* env */,
    jclass /* clazz */
) {
    refresh_hooks();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kaos_kaosgfx_bootstrap_TurnipAppComponentFactory_nativeClearCrashGuard(
    JNIEnv *env,
    jclass /* clazz */,
    jstring pkgDataDir
) {
    const char *pkgDataDirC = env->GetStringUTFChars(pkgDataDir, nullptr);
    if (pkgDataDirC) {
        std::string flagPath = std::string(pkgDataDirC) + "/files/kaosgfx_crash_flag";
        unlink(flagPath.c_str());
        LOGI("Crash guard cleared: %s", flagPath.c_str());
        env->ReleaseStringUTFChars(pkgDataDir, pkgDataDirC);
    }
}
