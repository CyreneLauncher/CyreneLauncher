#ifndef ORBIS_VULKAN_H
#define ORBIS_VULKAN_H
#include <vulkan/vulkan.h>
VkResult orbis_vulkan_create_surface(VkInstance instance, const VkAllocationCallbacks *allocator, VkSurfaceKHR *surface);
VkBool32 orbis_vulkan_presentation_support(VkInstance instance, VkPhysicalDevice physical, uint32_t family);
#endif
