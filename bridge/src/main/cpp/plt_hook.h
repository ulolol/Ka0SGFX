#pragma once

#include <link.h>
#include <elf.h>
#include <string>

namespace kaosgfx {

/**
 * Hook a symbol in the GOT of an ELF binary mapped in memory.
 *
 * @param base_addr Base load address of the ELF
 * @param phdr Pointer to program headers array
 * @param phnum Number of program headers
 * @param target_symbol Symbol name to hook (e.g. "dlopen", "vkCreateInstance")
 * @param new_func Replacement function pointer
 * @param old_func Output pointer to store original function address (optional)
 * @return true if symbol was found and hooked, false otherwise
 */
bool hook_plt_symbol(
    uintptr_t base_addr,
    const Elf64_Phdr *phdr,
    size_t phnum,
    const char *target_symbol,
    void *new_func,
    void **old_func = nullptr
);

/**
 * Scan all loaded libraries via dl_iterate_phdr and hook target_symbol in all
 * application libraries matching caller_path_pattern (or all non-system libraries).
 *
 * @param target_symbol Symbol name to hook
 * @param new_func Replacement function pointer
 * @param old_func Output pointer to store original function address
 * @param app_pkg_substr Substring matching target application (e.g. package name or ".so")
 * @return Number of libraries successfully hooked
 */
int hook_all_matching_libraries(
    const char *target_symbol,
    void *new_func,
    void **old_func,
    const char *app_pkg_substr
);

} // namespace kaosgfx
