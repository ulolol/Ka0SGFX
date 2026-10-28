#include "hooks.h"
#include <bytehook.h>
#include <android/log.h>
#include <android/dlext.h>
#include <cstring>
#include <string>

#define TAG "KaosGFX-Bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

void *g_vk = nullptr;
VkInstance g_instance = VK_NULL_HANDLE;
PFN_vkGetInstanceProcAddr g_pfn_vkGetInstanceProcAddr = nullptr;
PFN_vkCreateInstance g_pfn_vkCreateInstance = nullptr;

static std::string s_pkgDataDir;
static std::string s_pkgCodeDir;

// Function pointer types for Bionic internal loader entrypoints
typedef void* (*loader_dlopen_fn)(const char *filename, int flags, const void *caller_addr);
typedef void* (*loader_dlsym_fn)(void *handle, const char *symbol, const void *caller_addr);
typedef void* (*loader_android_dlopen_ext_fn)(const char *filename, int flag, const void *extinfo, const void *caller_addr);

static loader_dlopen_fn s_loader_dlopen = nullptr;
static loader_dlsym_fn s_loader_dlsym = nullptr;
static loader_android_dlopen_ext_fn s_loader_android_dlopen_ext = nullptr;

// ByteHook stubs
static bytehook_stub_t s_stub_dlopen = nullptr;
static bytehook_stub_t s_stub_dlsym = nullptr;
static bytehook_stub_t s_stub_android_dlopen_ext = nullptr;
static bytehook_stub_t s_stub_dlclose = nullptr;
static bytehook_stub_t s_stub_vkGetInstanceProcAddr = nullptr;
static bytehook_stub_t s_stub_vkCreateInstance = nullptr;

// Forward declarations
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL kaos_vkGetInstanceProcAddr(VkInstance instance, const char *pName);
static VKAPI_ATTR VkResult VKAPI_CALL kaos_vkCreateInstance(
    const VkInstanceCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkInstance *pInstance
);

static void init_bionic_loader_ptrs() {
    void *libc = dlopen("libc.so", RTLD_NOW);
    void *libdl = dlopen("libdl.so", RTLD_NOW);

    if (libdl) {
        s_loader_dlopen = reinterpret_cast<loader_dlopen_fn>(dlsym(libdl, "__loader_dlopen"));
        s_loader_dlsym = reinterpret_cast<loader_dlsym_fn>(dlsym(libdl, "__loader_dlsym"));
        s_loader_android_dlopen_ext = reinterpret_cast<loader_android_dlopen_ext_fn>(
            dlsym(libdl, "__loader_android_dlopen_ext")
        );
    }
    if (!s_loader_dlopen && libc) {
        s_loader_dlopen = reinterpret_cast<loader_dlopen_fn>(dlsym(libc, "__loader_dlopen"));
        s_loader_dlsym = reinterpret_cast<loader_dlsym_fn>(dlsym(libc, "__loader_dlsym"));
        s_loader_android_dlopen_ext = reinterpret_cast<loader_android_dlopen_ext_fn>(
            dlsym(libc, "__loader_android_dlopen_ext")
        );
    }
}

static void* kaos_dlopen(const char *filename, int flags) {
    if (filename != nullptr && (strstr(filename, "libvulkan.so") != nullptr)) {
        LOGI("kaos_dlopen: intercepted %s -> returning custom Vulkan handle %p", filename, g_vk);
        return g_vk;
    }
    if (s_loader_dlopen) {
        return s_loader_dlopen(filename, flags, __builtin_return_address(0));
    }
    return dlopen(filename, flags);
}

static void* kaos_dlsym(void *handle, const char *symbol) {
    if (symbol == nullptr) return nullptr;

    if (handle == g_vk || handle == RTLD_DEFAULT) {
        if (strcmp(symbol, "vkGetInstanceProcAddr") == 0) {
            return reinterpret_cast<void*>(kaos_vkGetInstanceProcAddr);
        }
        if (strcmp(symbol, "vkCreateInstance") == 0) {
            return reinterpret_cast<void*>(kaos_vkCreateInstance);
        }

        if (strncmp(symbol, "vk", 2) == 0 && g_pfn_vkGetInstanceProcAddr) {
            PFN_vkVoidFunction pfn = nullptr;
            if (g_instance != VK_NULL_HANDLE) {
                pfn = g_pfn_vkGetInstanceProcAddr(g_instance, symbol);
            }
            if (!pfn) {
                pfn = g_pfn_vkGetInstanceProcAddr(VK_NULL_HANDLE, symbol);
            }
            if (pfn) return reinterpret_cast<void*>(pfn);
        }
        if (handle == g_vk) {
            return dlsym(g_vk, symbol);
        }
    }

    if (s_loader_dlsym) {
        return s_loader_dlsym(handle, symbol, __builtin_return_address(0));
    }
    return dlsym(handle, symbol);
}

static void* kaos_android_dlopen_ext(const char *filename, int flag, const void *extinfo) {
    if (filename != nullptr && (strstr(filename, "libvulkan.so") != nullptr)) {
        LOGI("kaos_android_dlopen_ext: intercepted %s -> returning %p", filename, g_vk);
        return g_vk;
    }
    if (s_loader_android_dlopen_ext) {
        return s_loader_android_dlopen_ext(filename, flag, extinfo, __builtin_return_address(0));
    }
    return android_dlopen_ext(filename, flag, static_cast<const android_dlextinfo*>(extinfo));
}

static int kaos_dlclose(void *handle) {
    if (handle == g_vk) {
        LOGI("kaos_dlclose: suppressed dlclose for custom driver handle %p", handle);
        return 0;
    }
    return dlclose(handle);
}

static VKAPI_ATTR VkResult VKAPI_CALL kaos_vkCreateInstance(
    const VkInstanceCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkInstance *pInstance
) {
    LOGI("kaos_vkCreateInstance called");
    if (!g_pfn_vkCreateInstance) {
        LOGE("g_pfn_vkCreateInstance is null!");
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    VkResult res = g_pfn_vkCreateInstance(pCreateInfo, pAllocator, pInstance);
    if (res == VK_SUCCESS && pInstance && *pInstance != VK_NULL_HANDLE) {
        g_instance = *pInstance;
        LOGI("kaos_vkCreateInstance succeeded! g_instance = %p", g_instance);
    } else {
        LOGE("g_pfn_vkCreateInstance returned code %d", res);
    }
    return res;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL kaos_vkGetInstanceProcAddr(VkInstance instance, const char *pName) {
    if (!pName) return nullptr;

    if (strcmp(pName, "vkGetInstanceProcAddr") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(kaos_vkGetInstanceProcAddr);
    }
    if (strcmp(pName, "vkCreateInstance") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(kaos_vkCreateInstance);
    }

    if (g_pfn_vkGetInstanceProcAddr) {
        VkInstance inst = (instance != VK_NULL_HANDLE) ? instance : g_instance;
        return g_pfn_vkGetInstanceProcAddr(inst, pName);
    }
    return nullptr;
}

static bool caller_allow_filter(const char *caller_path_name, void * /*arg*/) {
    if (!caller_path_name) return false;

    // Reject self and hook infrastructure
    if (strstr(caller_path_name, "libkaosgfx_bridge") != nullptr ||
        strstr(caller_path_name, "libadrenotools") != nullptr ||
        strstr(caller_path_name, "libbytehook") != nullptr ||
        strstr(caller_path_name, "libshadowhook") != nullptr) {
        return false;
    }

    // Match app files / nativeLibDir
    if (!s_pkgDataDir.empty() && strstr(caller_path_name, s_pkgDataDir.c_str()) != nullptr) {
        return true;
    }
    // Match code directory (e.g. /data/app/~~.../)
    if (!s_pkgCodeDir.empty() && strstr(caller_path_name, s_pkgCodeDir.c_str()) != nullptr) {
        return true;
    }
    // Match zip-mapped native libraries inside split or base APKs
    if (strstr(caller_path_name, ".apk!/lib/") != nullptr) {
        return true;
    }

    return false;
}

static void hooked_callback(
    bytehook_stub_t /*stub*/,
    int status,
    const char *caller_path_name,
    const char *sym_name,
    void * /*new_func*/,
    void * /*prev_func*/,
    void * /*arg*/
) {
    if (status == BYTEHOOK_STATUS_CODE_OK) {
        LOGI("Hooked %s in %s (status OK)", sym_name ? sym_name : "?", caller_path_name ? caller_path_name : "?");
    } else {
        LOGE("Failed hook for %s in %s (code %d)", sym_name ? sym_name : "?", caller_path_name ? caller_path_name : "?", status);
    }
}

bool init_hooks(void *vkHandle, const char *pkgDataDir, const char *pkgCodeDir) {
    g_vk = vkHandle;
    if (pkgDataDir) s_pkgDataDir = pkgDataDir;
    if (pkgCodeDir) s_pkgCodeDir = pkgCodeDir;

    init_bionic_loader_ptrs();

    // Query core entrypoints from custom driver
    g_pfn_vkGetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
        dlsym(g_vk, "vkGetInstanceProcAddr")
    );
    if (!g_pfn_vkGetInstanceProcAddr) {
        LOGE("Failed to find vkGetInstanceProcAddr in custom driver");
        return false;
    }

    g_pfn_vkCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(
        g_pfn_vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance")
    );
    if (!g_pfn_vkCreateInstance) {
        g_pfn_vkCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(
            dlsym(g_vk, "vkCreateInstance")
        );
    }

    // Initialize ByteHook in MANUAL mode for zero overhead and direct GOT entries
    int bh_res = bytehook_init(BYTEHOOK_MODE_MANUAL, false);
    if (bh_res != 0) {
        LOGE("bytehook_init failed with code %d", bh_res);
        return false;
    }

    // Hook dlopen
    s_stub_dlopen = bytehook_hook_all(
        nullptr,
        "dlopen",
        reinterpret_cast<void*>(kaos_dlopen),
        hooked_callback,
        nullptr
    );

    // Hook dlsym
    s_stub_dlsym = bytehook_hook_all(
        nullptr,
        "dlsym",
        reinterpret_cast<void*>(kaos_dlsym),
        hooked_callback,
        nullptr
    );

    // Hook android_dlopen_ext
    s_stub_android_dlopen_ext = bytehook_hook_all(
        nullptr,
        "android_dlopen_ext",
        reinterpret_cast<void*>(kaos_android_dlopen_ext),
        hooked_callback,
        nullptr
    );

    // Hook dlclose to suppress premature unload of g_vk
    s_stub_dlclose = bytehook_hook_all(
        nullptr,
        "dlclose",
        reinterpret_cast<void*>(kaos_dlclose),
        hooked_callback,
        nullptr
    );

    // Hook vkGetInstanceProcAddr
    s_stub_vkGetInstanceProcAddr = bytehook_hook_all(
        nullptr,
        "vkGetInstanceProcAddr",
        reinterpret_cast<void*>(kaos_vkGetInstanceProcAddr),
        hooked_callback,
        nullptr
    );

    // Hook vkCreateInstance
    s_stub_vkCreateInstance = bytehook_hook_all(
        nullptr,
        "vkCreateInstance",
        reinterpret_cast<void*>(kaos_vkCreateInstance),
        hooked_callback,
        nullptr
    );

    LOGI("ByteHook PLT hooks installed successfully");
    return true;
}

void shutdown_hooks() {
    if (s_stub_dlopen) bytehook_unhook(s_stub_dlopen);
    if (s_stub_dlsym) bytehook_unhook(s_stub_dlsym);
    if (s_stub_android_dlopen_ext) bytehook_unhook(s_stub_android_dlopen_ext);
    if (s_stub_dlclose) bytehook_unhook(s_stub_dlclose);
    if (s_stub_vkGetInstanceProcAddr) bytehook_unhook(s_stub_vkGetInstanceProcAddr);
    if (s_stub_vkCreateInstance) bytehook_unhook(s_stub_vkCreateInstance);
}
