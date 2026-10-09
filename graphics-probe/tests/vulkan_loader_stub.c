/* Host-only loader boundary fixture; the GPU/WSI bridge is exercised separately. */
#include <vulkan/vulkan.h>
#include <string.h>
static VkResult VKAPI_CALL version(uint32_t *v) { *v = VK_API_VERSION_1_2; return VK_SUCCESS; }
static VkResult VKAPI_CALL extensions(const char *layer, uint32_t *count, VkExtensionProperties *out) {
    (void)layer; (void)out; *count = 0; return VK_SUCCESS;
}
static VkResult VKAPI_CALL layers(uint32_t *count, VkLayerProperties *out) {
    (void)out; *count = 0; return VK_SUCCESS;
}
static VkResult VKAPI_CALL devices(VkInstance instance, uint32_t *count, VkPhysicalDevice *out) {
    (void)instance; (void)out; *count = 0; return VK_SUCCESS;
}
static VkResult VKAPI_CALL create(const VkInstanceCreateInfo *info, const VkAllocationCallbacks *allocator, VkInstance *out) {
    (void)info; (void)allocator; *out = (VkInstance)(uintptr_t)0x1234; return VK_SUCCESS;
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char *name) {
    (void)instance;
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)vkGetInstanceProcAddr;
    if (!strcmp(name, "vkEnumerateInstanceVersion")) return (PFN_vkVoidFunction)version;
    if (!strcmp(name, "vkEnumerateInstanceExtensionProperties")) return (PFN_vkVoidFunction)extensions;
    if (!strcmp(name, "vkEnumerateInstanceLayerProperties")) return (PFN_vkVoidFunction)layers;
    if (!strcmp(name, "vkEnumeratePhysicalDevices")) return (PFN_vkVoidFunction)devices;
    if (!strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)create;
    return NULL;
}
