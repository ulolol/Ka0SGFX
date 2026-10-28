#pragma once

#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <string>

extern void *g_vk;
extern VkInstance g_instance;
extern PFN_vkGetInstanceProcAddr g_pfn_vkGetInstanceProcAddr;
extern PFN_vkCreateInstance g_pfn_vkCreateInstance;

bool init_hooks(void *vkHandle, const char *pkgDataDir, const char *pkgCodeDir);
void shutdown_hooks();
