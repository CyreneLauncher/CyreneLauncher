#ifndef ORBIS_VULKAN_H
#define ORBIS_VULKAN_H
#include <vulkan/vulkan.h>
VkResult orbis_vulkan_create_surface(VkInstance instance, const VkAllocationCallbacks *allocator, VkSurfaceKHR *surface);
VkBool32 orbis_vulkan_presentation_support(VkInstance instance, VkPhysicalDevice physical, uint32_t family);
#endif

/* Optional Orbis WSI hooks, internal to the statically linked platform PRX. */
void orbis_vulkan_scanout_state(int delta);
int orbis_vulkan_scanout_ready(void);
void orbis_vulkan_set_pointer(int x, int y, int scale);
void orbis_vulkan_draw_pointer(void *pixels, uint32_t width, uint32_t height, uint32_t pitch);
