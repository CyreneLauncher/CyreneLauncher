#include <assert.h>
#include <stdint.h>
#include <string.h>
#include <vulkan/vulkan.h>

VkResult orbis_vulkan_create_surface(VkInstance, const VkAllocationCallbacks *, VkSurfaceKHR *);
VkBool32 orbis_vulkan_presentation_support(VkInstance, VkPhysicalDevice, uint32_t);
static VkInstance seen_instance;
static const VkAllocationCallbacks *seen_allocator;
static VkResult surface_result = VK_SUCCESS;
static int entry_available = 1;
static VkResult VKAPI_CALL create_surface(VkInstance instance, const VkHeadlessSurfaceCreateInfoEXT *info,
                                          const VkAllocationCallbacks *allocator, VkSurfaceKHR *surface) {
    assert(instance == (VkInstance)(uintptr_t)0x1234);
    assert(info->sType == VK_STRUCTURE_TYPE_HEADLESS_SURFACE_CREATE_INFO_EXT && !info->pNext && !info->flags);
    seen_allocator = allocator;
    if (surface_result == VK_SUCCESS) *surface = (VkSurfaceKHR)(uintptr_t)0x9876;
    return surface_result;
}
static void VKAPI_CALL queue_properties(VkPhysicalDevice physical, uint32_t *count, VkQueueFamilyProperties *props) {
    assert(physical == (VkPhysicalDevice)(uintptr_t)0x4321);
    if (!props) { *count = 2; return; }
    assert(*count == 2);
    memset(props, 0, 2 * sizeof(*props));
    props[0].queueFlags = VK_QUEUE_GRAPHICS_BIT;
    props[0].queueCount = 1;
    props[1].queueFlags = VK_QUEUE_COMPUTE_BIT;
    props[1].queueCount = 1;
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL radv_GetInstanceProcAddr(VkInstance instance, const char *name) {
    seen_instance = instance;
    if (!entry_available || !name) return NULL;
    if (!strcmp(name, "vkCreateHeadlessSurfaceEXT")) return (PFN_vkVoidFunction)create_surface;
    if (!strcmp(name, "vkGetPhysicalDeviceQueueFamilyProperties")) return (PFN_vkVoidFunction)queue_properties;
    return NULL;
}
void orbis_vulkan_scanout_state(int delta);
int orbis_vulkan_scanout_ready(void);
void orbis_vulkan_set_pointer(int x, int y, int scale);
void orbis_vulkan_draw_pointer(void *pixels, uint32_t width, uint32_t height, uint32_t pitch);
int main(void) {
    assert(!orbis_vulkan_scanout_ready());
    orbis_vulkan_scanout_state(1);
    assert(orbis_vulkan_scanout_ready());
    orbis_vulkan_scanout_state(1);
    orbis_vulkan_scanout_state(-1);
    assert(orbis_vulkan_scanout_ready());
    orbis_vulkan_scanout_state(-1);
    assert(!orbis_vulkan_scanout_ready());
    uint32_t pixels[16 * 16 + 2];
    for (unsigned i = 0; i < 16 * 16 + 2; i++) pixels[i] = 0x12345678;
    orbis_vulkan_set_pointer(0, 0, 1);
    orbis_vulkan_draw_pointer(pixels + 1, 16, 16, 16 * 4);
    assert(pixels[0] == 0x12345678 && pixels[257] == 0x12345678);
    assert(pixels[1] == 0xff000000 && pixels[1 + 16 + 1] == 0xffffffff);
    orbis_vulkan_set_pointer(15, 15, 2);
    orbis_vulkan_draw_pointer(pixels + 1, 16, 16, 16 * 4);
    assert(pixels[257] == 0x12345678);
    orbis_vulkan_set_pointer(0, 0, 0);
    pixels[1] = 0x12345678;
    orbis_vulkan_draw_pointer(pixels + 1, 16, 16, 16 * 4);
    assert(pixels[1] == 0x12345678);

    VkInstance instance = (VkInstance)(uintptr_t)0x1234;
    VkPhysicalDevice physical = (VkPhysicalDevice)(uintptr_t)0x4321;
    VkAllocationCallbacks allocator = {0};
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    assert(vkGetInstanceProcAddr(instance, "vkCreateHeadlessSurfaceEXT") == (PFN_vkVoidFunction)create_surface);
    assert(seen_instance == instance);
    assert(!vkGetInstanceProcAddr(instance, "unavailable"));
    assert(orbis_vulkan_create_surface(instance, &allocator, &surface) == VK_SUCCESS);
    assert(surface == (VkSurfaceKHR)(uintptr_t)0x9876 && seen_allocator == &allocator);
    surface_result = VK_ERROR_OUT_OF_HOST_MEMORY;
    assert(orbis_vulkan_create_surface(instance, NULL, &surface) == VK_ERROR_OUT_OF_HOST_MEMORY);
    assert(surface == VK_NULL_HANDLE);
    assert(orbis_vulkan_create_surface(VK_NULL_HANDLE, NULL, &surface) == VK_ERROR_INITIALIZATION_FAILED);
    assert(orbis_vulkan_create_surface(instance, NULL, NULL) == VK_ERROR_INITIALIZATION_FAILED);
    entry_available = 0;
    assert(orbis_vulkan_create_surface(instance, NULL, &surface) == VK_ERROR_EXTENSION_NOT_PRESENT);
    assert(!orbis_vulkan_presentation_support(instance, physical, 0));
    entry_available = 1;
    assert(orbis_vulkan_presentation_support(instance, physical, 0));
    assert(!orbis_vulkan_presentation_support(instance, physical, 1));
    assert(!orbis_vulkan_presentation_support(instance, physical, 2));
    assert(!orbis_vulkan_presentation_support(VK_NULL_HANDLE, physical, 0));
    assert(!orbis_vulkan_presentation_support(instance, VK_NULL_HANDLE, 0));
    return 0;
}
