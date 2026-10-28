#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <dlfcn.h>
#include <android/log.h>
#include <vulkan/vulkan.h>
#include <adrenotools/driver.h>

#define TAG "VKPROBE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_dev_kaos_kaosgfx_spike_vkprobe_MainActivity_runProbe(
    JNIEnv *env,
    jobject /* this */,
    jstring hookLibDir,
    jstring customDriverDir,
    jstring customDriverName
) {
    const char *hookLibDirC = env->GetStringUTFChars(hookLibDir, nullptr);
    const char *customDriverDirC = env->GetStringUTFChars(customDriverDir, nullptr);
    const char *customDriverNameC = env->GetStringUTFChars(customDriverName, nullptr);

    std::string hookLibDirStr(hookLibDirC);
    std::string customDriverDirStr(customDriverDirC);
    std::string customDriverNameStr(customDriverNameC);

    env->ReleaseStringUTFChars(hookLibDir, hookLibDirC);
    env->ReleaseStringUTFChars(customDriverDir, customDriverDirC);
    env->ReleaseStringUTFChars(customDriverName, customDriverNameC);

    // Enforce trailing slash for customDriverDir as required by libadrenotools
    if (!customDriverDirStr.empty() && customDriverDirStr.back() != '/') {
        customDriverDirStr.push_back('/');
    }

    LOGI("Starting vkprobe:");
    LOGI("  hookLibDir: %s", hookLibDirStr.c_str());
    LOGI("  customDriverDir: %s", customDriverDirStr.c_str());
    LOGI("  customDriverName: %s", customDriverNameStr.c_str());

    std::ostringstream out;

    // 1. Open custom Vulkan driver via libadrenotools
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
        const char *err = dlerror();
        LOGE("adrenotools_open_libvulkan failed: %s", err ? err : "unknown error");
        out << "ERROR: adrenotools_open_libvulkan failed: " << (err ? err : "unknown error");
        return env->NewStringUTF(out.str().c_str());
    }
    LOGI("adrenotools_open_libvulkan succeeded: handle=%p", vkHandle);

    // 2. Resolve Vulkan entrypoints from custom driver loader
    LOGI("Resolving vkGetInstanceProcAddr...");
    auto pfn_vkGetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
        dlsym(vkHandle, "vkGetInstanceProcAddr")
    );
    if (!pfn_vkGetInstanceProcAddr) {
        LOGE("Failed to resolve vkGetInstanceProcAddr");
        out << "ERROR: Failed to resolve vkGetInstanceProcAddr";
        return env->NewStringUTF(out.str().c_str());
    }

    auto pfn_vkCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(
        pfn_vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance")
    );
    if (!pfn_vkCreateInstance) {
        // Fallback to dlsym directly
        pfn_vkCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(
            dlsym(vkHandle, "vkCreateInstance")
        );
    }

    if (!pfn_vkCreateInstance) {
        LOGE("Failed to resolve vkCreateInstance");
        out << "ERROR: Failed to resolve vkCreateInstance";
        return env->NewStringUTF(out.str().c_str());
    }

    // 3. Create VkInstance
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "KaosGFX-VKProbe";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "KaosGFX";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_3;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult res = pfn_vkCreateInstance(&createInfo, nullptr, &instance);
    if (res != VK_SUCCESS) {
        LOGE("vkCreateInstance failed with code %d", res);
        out << "ERROR: vkCreateInstance failed with code " << res;
        return env->NewStringUTF(out.str().c_str());
    }
    LOGI("VkInstance created successfully: %p", instance);

    // 4. Resolve instance functions with valid VkInstance
    auto pfn_vkEnumeratePhysicalDevices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
        pfn_vkGetInstanceProcAddr(instance, "vkEnumeratePhysicalDevices")
    );
    auto pfn_vkGetPhysicalDeviceProperties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
        pfn_vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties2")
    );
    auto pfn_vkDestroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(
        pfn_vkGetInstanceProcAddr(instance, "vkDestroyInstance")
    );

    if (!pfn_vkEnumeratePhysicalDevices || !pfn_vkDestroyInstance) {
        LOGE("Failed to resolve instance entrypoints after vkCreateInstance");
        out << "ERROR: Failed to resolve instance entrypoints";
        if (instance && pfn_vkDestroyInstance) pfn_vkDestroyInstance(instance, nullptr);
        return env->NewStringUTF(out.str().c_str());
    }

    // 4. Enumerate Physical Devices
    uint32_t deviceCount = 0;
    res = pfn_vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    if (res != VK_SUCCESS || deviceCount == 0) {
        LOGE("vkEnumeratePhysicalDevices returned %u devices (code %d)", deviceCount, res);
        out << "ERROR: No physical devices found (code " << res << ")";
        pfn_vkDestroyInstance(instance, nullptr);
        return env->NewStringUTF(out.str().c_str());
    }
    LOGI("Found %u physical devices", deviceCount);

    std::vector<VkPhysicalDevice> devices(deviceCount);
    pfn_vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());

    out << "SUCCESS\n";
    out << "Physical Devices: " << deviceCount << "\n";

    // 5. Inspect Physical Device Properties
    for (uint32_t i = 0; i < deviceCount; ++i) {
        VkPhysicalDevice dev = devices[i];

        VkPhysicalDeviceDriverProperties driverProps{};
        driverProps.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
        driverProps.pNext = nullptr;

        VkPhysicalDeviceProperties2 props2{};
        props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
        props2.pNext = &driverProps;

        if (pfn_vkGetPhysicalDeviceProperties2) {
            pfn_vkGetPhysicalDeviceProperties2(dev, &props2);
        } else {
            auto pfn_getProps = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
                pfn_vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties")
            );
            if (pfn_getProps) {
                pfn_getProps(dev, &props2.properties);
            }
        }

        uint32_t apiVer = props2.properties.apiVersion;
        uint32_t drvVer = props2.properties.driverVersion;

        out << "--- Device [" << i << "] ---\n";
        out << "Device Name: " << props2.properties.deviceName << "\n";
        out << "API Version: " << VK_API_VERSION_MAJOR(apiVer) << "."
            << VK_API_VERSION_MINOR(apiVer) << "." << VK_API_VERSION_PATCH(apiVer) << "\n";
        out << "Driver Version (raw): 0x" << std::hex << drvVer << std::dec << "\n";
        out << "Driver ID: " << driverProps.driverID;
        if (driverProps.driverID == VK_DRIVER_ID_MESA_TURNIP) {
            out << " (VK_DRIVER_ID_MESA_TURNIP)\n";
        } else if (driverProps.driverID == VK_DRIVER_ID_QUALCOMM_PROPRIETARY) {
            out << " (VK_DRIVER_ID_QUALCOMM_PROPRIETARY)\n";
        } else {
            out << " (Unknown ID)\n";
        }
        out << "Driver Name: " << driverProps.driverName << "\n";
        out << "Driver Info: " << driverProps.driverInfo << "\n";

        LOGI("Device [%u]: %s", i, props2.properties.deviceName);
        LOGI("  API: %u.%u.%u", VK_API_VERSION_MAJOR(apiVer), VK_API_VERSION_MINOR(apiVer), VK_API_VERSION_PATCH(apiVer));
        LOGI("  Driver ID: %u, Name: %s, Info: %s", driverProps.driverID, driverProps.driverName, driverProps.driverInfo);
    }

    pfn_vkDestroyInstance(instance, nullptr);
    return env->NewStringUTF(out.str().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_kaos_kaosgfx_spike_vkprobe_MainActivity_runStandardVulkanProbe(
    JNIEnv *env,
    jobject /* this */
) {
    LOGI("Starting runStandardVulkanProbe (testing standard game dlopen interception)...");
    std::ostringstream out;

    void *vk = dlopen("libvulkan.so", RTLD_NOW);
    if (!vk) {
        LOGE("Standard dlopen(libvulkan.so) failed: %s", dlerror());
        out << "ERROR: dlopen(libvulkan.so) failed: " << dlerror();
        return env->NewStringUTF(out.str().c_str());
    }
    LOGI("dlopen(libvulkan.so) returned %p", vk);

    auto pfn_vkGetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
        dlsym(vk, "vkGetInstanceProcAddr")
    );
    if (!pfn_vkGetInstanceProcAddr) {
        LOGE("dlsym(vkGetInstanceProcAddr) failed");
        out << "ERROR: dlsym(vkGetInstanceProcAddr) failed";
        return env->NewStringUTF(out.str().c_str());
    }

    auto pfn_vkCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(
        dlsym(vk, "vkCreateInstance")
    );
    if (!pfn_vkCreateInstance) {
        pfn_vkCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(
            pfn_vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance")
        );
    }

    if (!pfn_vkCreateInstance) {
        LOGE("Failed to find vkCreateInstance");
        out << "ERROR: Failed to find vkCreateInstance";
        return env->NewStringUTF(out.str().c_str());
    }

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "KaosGFX Standard Game Simulation";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "TestEngine";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult res = pfn_vkCreateInstance(&createInfo, nullptr, &instance);
    if (res != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        LOGE("Standard vkCreateInstance failed (code %d)", res);
        out << "ERROR: vkCreateInstance failed (code " << res << ")";
        return env->NewStringUTF(out.str().c_str());
    }

    LOGI("Standard vkCreateInstance succeeded: instance=%p", instance);

    auto pfn_vkEnumeratePhysicalDevices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
        pfn_vkGetInstanceProcAddr(instance, "vkEnumeratePhysicalDevices")
    );
    auto pfn_vkGetPhysicalDeviceProperties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
        pfn_vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties2")
    );
    auto pfn_vkDestroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(
        pfn_vkGetInstanceProcAddr(instance, "vkDestroyInstance")
    );

    uint32_t count = 0;
    pfn_vkEnumeratePhysicalDevices(instance, &count, nullptr);
    std::vector<VkPhysicalDevice> devices(count);
    pfn_vkEnumeratePhysicalDevices(instance, &count, devices.data());

    out << "STANDARD_GAME_INTERCEPT_SUCCESS\n";
    out << "Devices Count: " << count << "\n";

    for (uint32_t i = 0; i < count; ++i) {
        VkPhysicalDeviceDriverProperties driverProps{};
        driverProps.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;

        VkPhysicalDeviceProperties2 props2{};
        props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
        props2.pNext = &driverProps;

        if (pfn_vkGetPhysicalDeviceProperties2) {
            pfn_vkGetPhysicalDeviceProperties2(devices[i], &props2);
        }

        out << "Device [" << i << "]: " << props2.properties.deviceName << "\n";
        out << "Driver: " << driverProps.driverName << " (" << driverProps.driverInfo << ")\n";
        LOGI("Standard Intercept Device [%u]: %s, Driver: %s (%s)",
             i, props2.properties.deviceName, driverProps.driverName, driverProps.driverInfo);
    }

    pfn_vkDestroyInstance(instance, nullptr);
    return env->NewStringUTF(out.str().c_str());
}
