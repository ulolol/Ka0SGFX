#include "hooks.h"
#include "plt_hook.h"
#include <bytehook.h>
#include <shadowhook.h>
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

// Direct PLT/GOT original pointers
static void *s_direct_orig_dlopen = nullptr;
static void *s_direct_orig_dlsym = nullptr;
static void *s_direct_orig_android_dlopen_ext = nullptr;
static void *s_direct_orig_vkCreateInstance = nullptr;
static void *s_direct_orig_vkGetInstanceProcAddr = nullptr;

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
    void *res = nullptr;
    if (s_direct_orig_dlopen) {
        res = reinterpret_cast<void*(*)(const char*, int)>(s_direct_orig_dlopen)(filename, flags);
    } else if (s_loader_dlopen) {
        res = s_loader_dlopen(filename, flags, __builtin_return_address(0));
    } else {
        res = dlopen(filename, flags);
    }

    if (res != nullptr && filename != nullptr) {
        static bool s_in_refresh = false;
        if (!s_in_refresh) {
            s_in_refresh = true;
            refresh_hooks();
            s_in_refresh = false;
        }
    }
    return res;
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
            if (s_direct_orig_dlsym) {
                return reinterpret_cast<void*(*)(void*, const char*)>(s_direct_orig_dlsym)(g_vk, symbol);
            }
            return dlsym(g_vk, symbol);
        }
    }

    if (s_direct_orig_dlsym) {
        return reinterpret_cast<void*(*)(void*, const char*)>(s_direct_orig_dlsym)(handle, symbol);
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
    void *res = nullptr;
    if (s_direct_orig_android_dlopen_ext) {
        res = reinterpret_cast<void*(*)(const char*, int, const void*)>(s_direct_orig_android_dlopen_ext)(filename, flag, extinfo);
    } else if (s_loader_android_dlopen_ext) {
        res = s_loader_android_dlopen_ext(filename, flag, extinfo, __builtin_return_address(0));
    } else {
        res = android_dlopen_ext(filename, flag, static_cast<const android_dlextinfo*>(extinfo));
    }

    if (res != nullptr && filename != nullptr) {
        static bool s_in_refresh_ext = false;
        if (!s_in_refresh_ext) {
            s_in_refresh_ext = true;
            refresh_hooks();
            s_in_refresh_ext = false;
        }
    }
    return res;
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
        PFN_vkVoidFunction pfn = g_pfn_vkGetInstanceProcAddr(inst, pName);
        if (pfn) return pfn;
    }

    if (s_direct_orig_vkGetInstanceProcAddr) {
        return reinterpret_cast<PFN_vkGetInstanceProcAddr>(s_direct_orig_vkGetInstanceProcAddr)(instance, pName);
    }
    return nullptr;
}

// ShadowHook inline hook proxy functions
static void* sh_kaos_dlopen(const char *filename, int flags) {
    SHADOWHOOK_STACK_SCOPE();
    if (filename != nullptr && (strstr(filename, "libvulkan.so") != nullptr)) {
        LOGI("sh_kaos_dlopen: intercepted %s -> returning custom Vulkan handle %p", filename, g_vk);
        return g_vk;
    }
    return SHADOWHOOK_CALL_PREV(sh_kaos_dlopen, filename, flags);
}

static VKAPI_ATTR VkResult VKAPI_CALL sh_kaos_vkCreateInstance(
    const VkInstanceCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkInstance *pInstance
) {
    SHADOWHOOK_STACK_SCOPE();
    LOGI("sh_kaos_vkCreateInstance called via ShadowHook inline hook");
    if (!g_pfn_vkCreateInstance) {
        LOGE("g_pfn_vkCreateInstance is null!");
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    VkResult res = g_pfn_vkCreateInstance(pCreateInfo, pAllocator, pInstance);
    if (res == VK_SUCCESS && pInstance && *pInstance != VK_NULL_HANDLE) {
        g_instance = *pInstance;
        LOGI("sh_kaos_vkCreateInstance succeeded! g_instance = %p", g_instance);
    } else {
        LOGE("g_pfn_vkCreateInstance returned code %d", res);
    }
    return res;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL sh_kaos_vkGetInstanceProcAddr(VkInstance instance, const char *pName) {
    SHADOWHOOK_STACK_SCOPE();
    if (!pName) return nullptr;

    if (strcmp(pName, "vkGetInstanceProcAddr") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(sh_kaos_vkGetInstanceProcAddr);
    }
    if (strcmp(pName, "vkCreateInstance") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(sh_kaos_vkCreateInstance);
    }

    if (g_pfn_vkGetInstanceProcAddr) {
        VkInstance inst = (instance != VK_NULL_HANDLE) ? instance : g_instance;
        PFN_vkVoidFunction pfn = g_pfn_vkGetInstanceProcAddr(inst, pName);
        if (pfn) return pfn;
    }

    return SHADOWHOOK_CALL_PREV(sh_kaos_vkGetInstanceProcAddr, instance, pName);
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

    // 1. Initialize ShadowHook for inline function hooking
    int sh_res = shadowhook_init(SHADOWHOOK_MODE_SHARED, true);
    LOGI("shadowhook_init returned %d", sh_res);

    void *sh_stub_dlopen = shadowhook_hook_sym_name(
        "libdl.so",
        "dlopen",
        reinterpret_cast<void*>(sh_kaos_dlopen),
        nullptr
    );
    LOGI("ShadowHook dlopen stub: %p (errno: %d, msg: %s)",
         sh_stub_dlopen, shadowhook_get_errno(), shadowhook_to_errmsg(shadowhook_get_errno()));

    void *sh_stub_create = shadowhook_hook_sym_name(
        "libvulkan.so",
        "vkCreateInstance",
        reinterpret_cast<void*>(sh_kaos_vkCreateInstance),
        nullptr
    );
    LOGI("ShadowHook vkCreateInstance stub: %p (errno: %d, msg: %s)",
         sh_stub_create, shadowhook_get_errno(), shadowhook_to_errmsg(shadowhook_get_errno()));

    void *sh_stub_gipa = shadowhook_hook_sym_name(
        "libvulkan.so",
        "vkGetInstanceProcAddr",
        reinterpret_cast<void*>(sh_kaos_vkGetInstanceProcAddr),
        nullptr
    );
    LOGI("ShadowHook vkGetInstanceProcAddr stub: %p (errno: %d, msg: %s)",
         sh_stub_gipa, shadowhook_get_errno(), shadowhook_to_errmsg(shadowhook_get_errno()));

    // 2. Ignore internal hook libs in ByteHook to avoid recursion
    bytehook_add_ignore("libkaosgfx_bridge.so");
    bytehook_add_ignore("libhook_impl.so");
    bytehook_add_ignore("libshadowhook.so");
    bytehook_add_ignore("libbytehook.so");
    bytehook_add_ignore("libvulkan_freedreno.so");

    // 3. Initialize ByteHook in AUTOMATIC mode so newly loaded game libraries are hooked
    int bh_res = bytehook_init(BYTEHOOK_MODE_AUTOMATIC, true);
    bytehook_set_debug(true);
    bytehook_set_recordable(true);
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

    // Perform immediate direct PLT/GOT hook on all currently loaded app libraries
    refresh_hooks();

    return true;
}

void refresh_hooks() {
    if (!g_vk) return;
    LOGI("refresh_hooks: running direct PLT/GOT hook on application libraries...");

    int n_dlopen = kaosgfx::hook_all_matching_libraries(
        "dlopen",
        reinterpret_cast<void*>(kaos_dlopen),
        &s_direct_orig_dlopen,
        nullptr
    );
    int n_dlsym = kaosgfx::hook_all_matching_libraries(
        "dlsym",
        reinterpret_cast<void*>(kaos_dlsym),
        &s_direct_orig_dlsym,
        nullptr
    );
    int n_dlopen_ext = kaosgfx::hook_all_matching_libraries(
        "android_dlopen_ext",
        reinterpret_cast<void*>(kaos_android_dlopen_ext),
        &s_direct_orig_android_dlopen_ext,
        nullptr
    );
    int n_create = kaosgfx::hook_all_matching_libraries(
        "vkCreateInstance",
        reinterpret_cast<void*>(kaos_vkCreateInstance),
        &s_direct_orig_vkCreateInstance,
        nullptr
    );
    int n_gipa = kaosgfx::hook_all_matching_libraries(
        "vkGetInstanceProcAddr",
        reinterpret_cast<void*>(kaos_vkGetInstanceProcAddr),
        &s_direct_orig_vkGetInstanceProcAddr,
        nullptr
    );

    LOGI("refresh_hooks: direct PLT/GOT hook summary: dlopen=%d, dlsym=%d, android_dlopen_ext=%d, vkCreateInstance=%d, vkGetInstanceProcAddr=%d",
         n_dlopen, n_dlsym, n_dlopen_ext, n_create, n_gipa);
}

void shutdown_hooks() {
    if (s_stub_dlopen) { bytehook_unhook(s_stub_dlopen); s_stub_dlopen = nullptr; }
    if (s_stub_dlsym) { bytehook_unhook(s_stub_dlsym); s_stub_dlsym = nullptr; }
    if (s_stub_android_dlopen_ext) { bytehook_unhook(s_stub_android_dlopen_ext); s_stub_android_dlopen_ext = nullptr; }
    if (s_stub_dlclose) { bytehook_unhook(s_stub_dlclose); s_stub_dlclose = nullptr; }
    if (s_stub_vkGetInstanceProcAddr) { bytehook_unhook(s_stub_vkGetInstanceProcAddr); s_stub_vkGetInstanceProcAddr = nullptr; }
    if (s_stub_vkCreateInstance) { bytehook_unhook(s_stub_vkCreateInstance); s_stub_vkCreateInstance = nullptr; }
}
