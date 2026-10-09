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

/* WSI's optional hooks run after the GPU wait and before the VideoOut flip.
 * The normal GL cursor is rendered with scissored clears; Vulkan has no GL context. */
#include <stdatomic.h>
#include <pthread.h>
#include <stdint.h>
static atomic_int scanouts;
static pthread_mutex_t pointer_lock = PTHREAD_MUTEX_INITIALIZER;
static int pointer_x, pointer_y, pointer_scale;

void orbis_vulkan_scanout_state(int delta) {
    atomic_fetch_add(&scanouts, delta);
}
int orbis_vulkan_scanout_ready(void) {
    return atomic_load(&scanouts) > 0;
}
void orbis_vulkan_set_pointer(int x, int y, int scale) {
    pthread_mutex_lock(&pointer_lock);
    pointer_x = x; pointer_y = y;
    pointer_scale = scale > 0 && scale <= 16 ? scale : 0;
    pthread_mutex_unlock(&pointer_lock);
}
void orbis_vulkan_draw_pointer(void *pixels, uint32_t width, uint32_t height, uint32_t pitch) {
    if (!pixels || (uint64_t)pitch < (uint64_t)width * 4 || (pitch & 3)) return;
    pthread_mutex_lock(&pointer_lock);
    int x = pointer_x, y = pointer_y, scale = pointer_scale;
    pthread_mutex_unlock(&pointer_lock);
    if (!scale) return;
    for (int pass = 0; pass < 2; pass++) {
        uint32_t color = pass ? UINT32_C(0xffffffff) : UINT32_C(0xff000000);
        for (int row = 0; row < 12; row++) {
            if (pass && (row == 0 || row == 11)) continue;
            int left = pass ? 1 : 0, columns = pass ? row : row + 2;
            for (int dy = 0; dy < scale; dy++) {
                int64_t py = (int64_t)y + row * scale + dy;
                if (py < 0 || py >= height) continue;
                uint32_t *line = (uint32_t *)((uint8_t *)pixels + (size_t)py * pitch);
                for (int dx = 0; dx < columns * scale; dx++) {
                    int64_t px = (int64_t)x + left * scale + dx;
                    if (px >= 0 && px < width) line[px] = color;
                }
            }
        }
    }
}
