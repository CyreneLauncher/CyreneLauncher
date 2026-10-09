/* Direct RADV loader entry point. One PRX owns the driver, arena and VideoOut WSI;
 * a second libvulkan with its own static Mesa would create competing owners. */
#include "orbis_vulkan.h"
#include <stdlib.h>

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL radv_GetInstanceProcAddr(VkInstance instance, const char *name);

__attribute__((visibility("default"))) VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
vkGetInstanceProcAddr(VkInstance instance, const char *name) {
    return radv_GetInstanceProcAddr(instance, name);
}

VkResult orbis_vulkan_create_surface(VkInstance instance, const VkAllocationCallbacks *allocator, VkSurfaceKHR *surface) {
    if (!surface) return VK_ERROR_INITIALIZATION_FAILED;
    *surface = VK_NULL_HANDLE;
    if (!instance) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkCreateHeadlessSurfaceEXT create = (PFN_vkCreateHeadlessSurfaceEXT)
        vkGetInstanceProcAddr(instance, "vkCreateHeadlessSurfaceEXT");
    if (!create) return VK_ERROR_EXTENSION_NOT_PRESENT;
    const VkHeadlessSurfaceCreateInfoEXT info = {.sType = VK_STRUCTURE_TYPE_HEADLESS_SURFACE_CREATE_INFO_EXT};
    return create(instance, &info, allocator, surface);
}

VkBool32 orbis_vulkan_presentation_support(VkInstance instance, VkPhysicalDevice physical, uint32_t family) {
    if (!instance || !physical) return VK_FALSE;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties query = (PFN_vkGetPhysicalDeviceQueueFamilyProperties)
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceQueueFamilyProperties");
    if (!query) return VK_FALSE;
    uint32_t count = 0;
    query(physical, &count, NULL);
    if (family >= count) return VK_FALSE;
    VkQueueFamilyProperties *queues = calloc(count, sizeof(*queues));
    if (!queues) return VK_FALSE;
    query(physical, &count, queues);
    VkBool32 supported = family < count && queues[family].queueCount &&
        (queues[family].queueFlags & VK_QUEUE_GRAPHICS_BIT) ? VK_TRUE : VK_FALSE;
    free(queues);
    return supported;
}
