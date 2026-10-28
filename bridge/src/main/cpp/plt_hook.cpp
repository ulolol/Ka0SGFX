#include "plt_hook.h"
#include <sys/mman.h>
#include <unistd.h>
#include <cstring>
#include <android/log.h>

#define TAG "KaosGFX-PLTHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace kaosgfx {

bool hook_plt_symbol(
    uintptr_t base_addr,
    const Elf64_Phdr *phdr,
    size_t phnum,
    const char *target_symbol,
    void *new_func,
    void **old_func
) {
    if (!phdr || phnum == 0 || !target_symbol || !new_func) return false;

    // 1. Locate PT_DYNAMIC segment
    const Elf64_Dyn *dyn = nullptr;
    for (size_t i = 0; i < phnum; ++i) {
        if (phdr[i].p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<const Elf64_Dyn *>(base_addr + phdr[i].p_vaddr);
            break;
        }
    }
    if (!dyn) return false;

    // 2. Parse DYNAMIC entries
    const Elf64_Rela *rela = nullptr;
    size_t relasz = 0;
    const Elf64_Sym *symtab = nullptr;
    const char *strtab = nullptr;

    for (const Elf64_Dyn *d = dyn; d->d_tag != DT_NULL; ++d) {
        switch (d->d_tag) {
            case DT_JMPREL:
                rela = reinterpret_cast<const Elf64_Rela *>(
                    d->d_un.d_ptr < base_addr ? base_addr + d->d_un.d_ptr : d->d_un.d_ptr
                );
                break;
            case DT_PLTRELSZ:
                relasz = d->d_un.d_val;
                break;
            case DT_SYMTAB:
                symtab = reinterpret_cast<const Elf64_Sym *>(
                    d->d_un.d_ptr < base_addr ? base_addr + d->d_un.d_ptr : d->d_un.d_ptr
                );
                break;
            case DT_STRTAB:
                strtab = reinterpret_cast<const char *>(
                    d->d_un.d_ptr < base_addr ? base_addr + d->d_un.d_ptr : d->d_un.d_ptr
                );
                break;
            default:
                break;
        }
    }

    if (!rela || relasz == 0 || !symtab || !strtab) {
        return false;
    }

    // 3. Scan relocation table (.rela.plt)
    size_t count = relasz / sizeof(Elf64_Rela);
    size_t page_size = sysconf(_SC_PAGESIZE);
    bool hooked = false;

    for (size_t i = 0; i < count; ++i) {
        uint32_t type = ELF64_R_TYPE(rela[i].r_info);
        uint32_t sym_idx = ELF64_R_SYM(rela[i].r_info);

        if (type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT) {
            continue;
        }

        const char *name = strtab + symtab[sym_idx].st_name;
        if (strcmp(name, target_symbol) == 0) {
            void **got_slot = reinterpret_cast<void **>(base_addr + rela[i].r_offset);

            // Change page protection
            uintptr_t page_start = reinterpret_cast<uintptr_t>(got_slot) & ~(page_size - 1);
            if (mprotect(reinterpret_cast<void *>(page_start), page_size, PROT_READ | PROT_WRITE) != 0) {
                LOGE("mprotect failed for GOT slot at %p", got_slot);
                continue;
            }

            if (old_func && *old_func == nullptr && *got_slot != new_func) {
                *old_func = *got_slot;
            }

            *got_slot = new_func;

            // Restore protection
            mprotect(reinterpret_cast<void *>(page_start), page_size, PROT_READ);

            // Invalidate instruction/data cache
            __builtin___clear_cache(
                reinterpret_cast<char *>(page_start),
                reinterpret_cast<char *>(page_start + page_size)
            );

            LOGI("Hooked %s in GOT at %p (new: %p, old: %p)",
                 target_symbol, got_slot, new_func, old_func ? *old_func : nullptr);
            hooked = true;
        }
    }

    return hooked;
}

struct MatchContext {
    const char *target_symbol;
    void *new_func;
    void **old_func;
    const char *app_pkg_substr;
    int hook_count;
};

int hook_all_matching_libraries(
    const char *target_symbol,
    void *new_func,
    void **old_func,
    const char *app_pkg_substr
) {
    MatchContext ctx{
        .target_symbol = target_symbol,
        .new_func = new_func,
        .old_func = old_func,
        .app_pkg_substr = app_pkg_substr,
        .hook_count = 0
    };

    dl_iterate_phdr([](struct dl_phdr_info *info, size_t /*size*/, void *data) -> int {
        auto *c = static_cast<MatchContext *>(data);
        if (!info || !info->dlpi_name || info->dlpi_name[0] == '\0') return 0;

        const char *name = info->dlpi_name;

        // Skip self, Bionic runtime, and hook infrastructure
        if (strstr(name, "libkaosgfx_bridge") != nullptr ||
            strstr(name, "libhook_impl") != nullptr ||
            strstr(name, "libmain_hook") != nullptr ||
            strstr(name, "libbytehook") != nullptr ||
            strstr(name, "libshadowhook") != nullptr ||
            strstr(name, "libvulkan_freedreno") != nullptr ||
            strstr(name, "/system/bin/linker") != nullptr ||
            strstr(name, "libc.so") != nullptr ||
            strstr(name, "libdl.so") != nullptr) {
            return 0;
        }

        // Check if matching app pattern
        bool match = false;
        if (c->app_pkg_substr && c->app_pkg_substr[0] != '\0') {
            if (strstr(name, c->app_pkg_substr) != nullptr) {
                match = true;
            }
        } else {
            // If no pattern specified, hook everything in /data/app/ or .apk!/lib/
            if (strstr(name, "/data/app/") != nullptr ||
                strstr(name, ".apk!/lib/") != nullptr ||
                strstr(name, "/data/data/") != nullptr ||
                strstr(name, "/data/user/") != nullptr) {
                match = true;
            }
        }

        if (match) {
            LOGI("Found candidate ELF to hook: %s (base: 0x%lx)", name, (unsigned long)info->dlpi_addr);
            if (hook_plt_symbol(
                info->dlpi_addr,
                info->dlpi_phdr,
                info->dlpi_phnum,
                c->target_symbol,
                c->new_func,
                c->old_func
            )) {
                c->hook_count++;
            }
        }
        return 0;
    }, &ctx);

    return ctx.hook_count;
}

} // namespace kaosgfx
