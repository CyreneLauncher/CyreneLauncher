package orbis.vulkan;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashSet;
import java.util.Set;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLVideo.*;
import static org.lwjgl.sdl.SDLVulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.util.vma.Vma.*;

/** Standalone console acceptance: real loader, required features, SDL surface, VMA and VideoOut. */
public final class VulkanProbe implements AutoCloseable {
    private long window, surface, swapchain, pool, acquired, rendered, fence, allocator;
    private VkInstance instance;
    private VkPhysicalDevice physical;
    private VkDevice device;
    private VkQueue queue;
    private int family;

    private static void check(int result, String stage) {
        if (result != VK_SUCCESS) throw new IllegalStateException(stage + " VkResult=" + result);
        System.out.println("vulkan-probe: " + stage + " OK");
    }

    public static void main(String[] args) {
        System.out.println("vulkan-probe: starting; Minecraft renderer preference is unchanged");
        VK.create();
        try (VulkanProbe probe = new VulkanProbe(); MemoryStack stack = MemoryStack.create(256 * 1024).push()) {
            probe.initialize(stack);
            probe.present(stack);
        } finally {
            SDL_Quit();
            VK.destroy();
        }
        System.out.println("vulkan-probe: PASS loader/device/features/surface/VMA/120 presents");
    }

    private void initialize(MemoryStack stack) {
        SharedLibrary driver = (SharedLibrary) VK.getFunctionProvider();
        if (!SDL_Vulkan_LoadLibrary(driver.getPath())) throw new IllegalStateException("SDL Vulkan loader rejected driver");
        long entry = SDL_Vulkan_GetVkGetInstanceProcAddr();
        if (entry == 0 || entry != driver.getFunctionAddress("vkGetInstanceProcAddr"))
            throw new IllegalStateException("SDL/LWJGL Vulkan entry points differ");
        System.out.println("vulkan-probe: entry=0x" + Long.toHexString(entry) + " driver=" + driver.getPath());
        if (!SDL_Init(SDL_INIT_VIDEO)) throw new IllegalStateException("SDL_Init failed");
        window = SDL_CreateWindow("Orbis Vulkan acceptance", 1920, 1080, SDL_WINDOW_VULKAN);
        if (window == 0) throw new IllegalStateException("Vulkan window failed");
        VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                .pApplicationName(stack.UTF8("orbis-vulkan-probe")).apiVersion(VK_API_VERSION_1_2);
        VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app)
                .ppEnabledExtensionNames(SDL_Vulkan_GetInstanceExtensions());
        PointerBuffer pointer = stack.mallocPointer(1);
        check(vkCreateInstance(ci, null, pointer), "instance");
        instance = new VkInstance(pointer.get(0), ci);
        IntBuffer count = stack.ints(0);
        check(vkEnumeratePhysicalDevices(instance, count, null), "device count");
        if (count.get(0) == 0) throw new IllegalStateException("no physical devices");
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        check(vkEnumeratePhysicalDevices(instance, count, devices), "devices");
        physical = new VkPhysicalDevice(devices.get(0), instance);
        VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
        vkGetPhysicalDeviceProperties(physical, properties);
        System.out.println("vulkan-probe: GPU=" + properties.deviceNameString() + " api="
                + VK_VERSION_MAJOR(properties.apiVersion()) + "." + VK_VERSION_MINOR(properties.apiVersion()));
        if (properties.apiVersion() < VK_API_VERSION_1_2) throw new IllegalStateException("Vulkan 1.2 required");
        Set<String> extensions = new HashSet<>();
        check(vkEnumerateDeviceExtensionProperties(physical, (ByteBuffer) null, count, null), "extension count");
        VkExtensionProperties.Buffer names = VkExtensionProperties.calloc(count.get(0), stack);
        check(vkEnumerateDeviceExtensionProperties(physical, (ByteBuffer) null, count, names), "extensions");
        for (int i = 0; i < count.get(0); i++) extensions.add(names.get(i).extensionNameString());
        String[] required = {"VK_KHR_dynamic_rendering", "VK_KHR_push_descriptor", "VK_KHR_synchronization2",
                             "VK_EXT_vertex_attribute_divisor", "VK_KHR_swapchain"};
        PointerBuffer enabled = stack.mallocPointer(required.length);
        for (String name : required) {
            System.out.println("vulkan-probe: " + name + "=" + extensions.contains(name));
            if (!extensions.contains(name)) throw new IllegalStateException("Minecraft requires " + name);
            enabled.put(stack.UTF8(name));
        }
        enabled.flip();
        VkPhysicalDeviceVertexAttributeDivisorFeaturesEXT divisor = VkPhysicalDeviceVertexAttributeDivisorFeaturesEXT.calloc(stack).sType$Default();
        VkPhysicalDeviceDynamicRenderingFeatures dynamic = VkPhysicalDeviceDynamicRenderingFeatures.calloc(stack).sType$Default().pNext(divisor.address());
        VkPhysicalDeviceSynchronization2Features sync = VkPhysicalDeviceSynchronization2Features.calloc(stack).sType$Default().pNext(dynamic.address());
        VkPhysicalDeviceVulkan12Features f12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default().pNext(sync.address());
        VkPhysicalDeviceVulkan11Features f11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default().pNext(f12.address());
        VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(f11.address());
        vkGetPhysicalDeviceFeatures2(physical, features);
        boolean supported = features.features().multiDrawIndirect() && features.features().drawIndirectFirstInstance()
                && features.features().samplerAnisotropy() && f11.shaderDrawParameters()
                && f12.timelineSemaphore() && f12.hostQueryReset() && sync.synchronization2()
                && dynamic.dynamicRendering() && divisor.vertexAttributeInstanceRateDivisor();
        System.out.println("vulkan-probe: Minecraft required feature set=" + supported);
        if (!supported) throw new IllegalStateException("Minecraft Vulkan required features unavailable");
        // Request only the features Minecraft needs, rather than every bit returned by the query.
        features.features().clear();
        features.features().multiDrawIndirect(true).drawIndirectFirstInstance(true).samplerAnisotropy(true);
        f11.clear(); f11.sType$Default().pNext(f12.address()).shaderDrawParameters(true);
        f12.clear(); f12.sType$Default().pNext(sync.address()).timelineSemaphore(true).hostQueryReset(true);
        sync.clear(); sync.sType$Default().pNext(dynamic.address()).synchronization2(true);
        dynamic.clear(); dynamic.sType$Default().pNext(divisor.address()).dynamicRendering(true);
        divisor.clear(); divisor.sType$Default().vertexAttributeInstanceRateDivisor(true);
        vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
        VkQueueFamilyProperties.Buffer queues = VkQueueFamilyProperties.calloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(physical, count, queues);
        family = -1;
        for (int i = 0; i < count.get(0); i++) {
            if ((queues.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0
                    && SDL_Vulkan_GetPresentationSupport(instance, physical, i)) { family = i; break; }
        }
        if (family < 0) throw new IllegalStateException("no graphics/presentation queue");
        LongBuffer handle = stack.longs(0);
        if (!SDL_Vulkan_CreateSurface(window, instance, null, handle)) throw new IllegalStateException("SDL surface failed");
        surface = handle.get(0);
        check(vkGetPhysicalDeviceSurfaceSupportKHR(physical, family, surface, count), "surface support");
        if (count.get(0) != VK_TRUE) throw new IllegalStateException("surface queue unsupported");
        VkDeviceQueueCreateInfo.Buffer qc = VkDeviceQueueCreateInfo.calloc(1, stack);
        qc.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1));
        VkDeviceCreateInfo dc = VkDeviceCreateInfo.calloc(stack).sType$Default().pNext(features.address())
                .pQueueCreateInfos(qc).ppEnabledExtensionNames(enabled);
        check(vkCreateDevice(physical, dc, null, pointer), "logical device");
        device = new VkDevice(pointer.get(0), physical, dc);
        vkGetDeviceQueue(device, family, 0, pointer);
        queue = new VkQueue(pointer.get(0), device);
        VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack).set(instance, device);
        VmaAllocatorCreateInfo ac = VmaAllocatorCreateInfo.calloc(stack).instance(instance).physicalDevice(physical)
                .device(device).vulkanApiVersion(VK_API_VERSION_1_2).pVulkanFunctions(functions);
        check(vmaCreateAllocator(ac, pointer), "VMA allocator");
        allocator = pointer.get(0);
        VkBufferCreateInfo bc = VkBufferCreateInfo.calloc(stack).sType$Default().size(4096).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        VmaAllocationCreateInfo allocation = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO)
                .flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
        PointerBuffer memory = stack.mallocPointer(1);
        VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
        check(vmaCreateBuffer(allocator, bc, allocation, handle, memory, info), "VMA mapped buffer");
        try {
            if (info.pMappedData() == 0) throw new IllegalStateException("VMA mapped buffer has no mapping");
            MemoryUtil.memPutLong(info.pMappedData(), 0x4f52424953564bL);
        } finally { vmaDestroyBuffer(allocator, handle.get(0), memory.get(0)); }
    }

    private void present(MemoryStack stack) {
        VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
        check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface, caps), "surface capabilities");
        IntBuffer count = stack.ints(0);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, count, null), "format count");
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(count.get(0), stack);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, count, formats), "formats");
        VkSurfaceFormatKHR format = formats.get(0);
        for (int i = 0; i < count.get(0); i++) {
            if (formats.get(i).format() == VK_FORMAT_B8G8R8A8_UNORM) { format = formats.get(i); break; }
        }
        if ((caps.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0)
            throw new IllegalStateException("swapchain has no transfer-destination usage");
        int width = caps.currentExtent().width() == -1 ? 1920 : caps.currentExtent().width();
        int height = caps.currentExtent().height() == -1 ? 1080 : caps.currentExtent().height();
        int images = Math.max(2, caps.minImageCount());
        if (caps.maxImageCount() > 0) images = Math.min(images, caps.maxImageCount());
        VkSwapchainCreateInfoKHR sc = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default().surface(surface)
                .minImageCount(images).imageFormat(format.format()).imageColorSpace(format.colorSpace())
                .imageArrayLayers(1).imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT).imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .preTransform(caps.currentTransform()).compositeAlpha(Integer.lowestOneBit(caps.supportedCompositeAlpha()))
                .presentMode(VK_PRESENT_MODE_FIFO_KHR).clipped(true);
        sc.imageExtent().set(width, height);
        LongBuffer handle = stack.longs(0);
        check(vkCreateSwapchainKHR(device, sc, null, handle), "swapchain"); swapchain = handle.get(0);
        check(vkGetSwapchainImagesKHR(device, swapchain, count, null), "image count");
        LongBuffer targets = stack.mallocLong(count.get(0));
        check(vkGetSwapchainImagesKHR(device, swapchain, count, targets), "images");
        VkCommandPoolCreateInfo pc = VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(family)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
        check(vkCreateCommandPool(device, pc, null, handle), "command pool"); pool = handle.get(0);
        PointerBuffer pointer = stack.mallocPointer(1);
        VkCommandBufferAllocateInfo ca = VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
        check(vkAllocateCommandBuffers(device, ca, pointer), "command buffer");
        VkCommandBuffer command = new VkCommandBuffer(pointer.get(0), device);
        VkSemaphoreCreateInfo sem = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
        check(vkCreateSemaphore(device, sem, null, handle), "acquire semaphore"); acquired = handle.get(0);
        check(vkCreateSemaphore(device, sem, null, handle), "render semaphore"); rendered = handle.get(0);
        VkFenceCreateInfo fc = VkFenceCreateInfo.calloc(stack).sType$Default();
        check(vkCreateFence(device, fc, null, handle), "render fence"); fence = handle.get(0);
        VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default().srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
        range.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        VkClearColorValue color = VkClearColorValue.calloc(stack);
        VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(pointer)
                .pWaitSemaphores(stack.longs(acquired)).pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
                .pSignalSemaphores(stack.longs(rendered));
        IntBuffer index = stack.ints(0);
        VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack).sType$Default().pWaitSemaphores(stack.longs(rendered))
                .swapchainCount(1).pSwapchains(stack.longs(swapchain)).pImageIndices(index);
        for (int frame = 0; frame < 120; frame++) {
            int result = vkAcquireNextImageKHR(device, swapchain, -1L, acquired, 0, index);
            if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) check(result, "acquire " + frame);
            check(vkResetCommandBuffer(command, 0), "reset " + frame);
            check(vkBeginCommandBuffer(command, begin), "begin " + frame);
            barrier.get(0).image(targets.get(index.get(0))).oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);
            color.float32(0, frame < 40 ? 0.8f : 0.1f).float32(1, frame >= 40 && frame < 80 ? 0.8f : 0.1f)
                    .float32(2, frame >= 80 ? 0.8f : 0.1f).float32(3, 1);
            vkCmdClearColorImage(command, targets.get(index.get(0)), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, range);
            barrier.get(0).oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(0);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, barrier);
            check(vkEndCommandBuffer(command), "end " + frame);
            check(vkQueueSubmit(queue, submit, fence), "submit " + frame);
            result = vkQueuePresentKHR(queue, present);
            if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) check(result, "present " + frame);
            check(vkWaitForFences(device, fence, true, -1L), "wait " + frame);
            // Keep the probe deliberately serial: presentation has consumed the binary semaphore before reuse.
            check(vkQueueWaitIdle(queue), "present idle " + frame);
            check(vkResetFences(device, fence), "fence reset " + frame);
        }
    }

    @Override public void close() {
        if (device != null) {
            vkDeviceWaitIdle(device);
            if (swapchain != 0) vkDestroySwapchainKHR(device, swapchain, null);
            if (fence != 0) vkDestroyFence(device, fence, null);
            if (acquired != 0) vkDestroySemaphore(device, acquired, null);
            if (rendered != 0) vkDestroySemaphore(device, rendered, null);
            if (pool != 0) vkDestroyCommandPool(device, pool, null);
            if (allocator != 0) vmaDestroyAllocator(allocator);
            vkDestroyDevice(device, null);
        }
        if (instance != null) {
            if (surface != 0) vkDestroySurfaceKHR(instance, surface, null);
            vkDestroyInstance(instance, null);
        }
        if (window != 0) SDL_DestroyWindow(window);
        SDL_Vulkan_UnloadLibrary();
    }
}
