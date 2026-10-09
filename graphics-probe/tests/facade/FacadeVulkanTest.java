import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.vulkan.*;
import orbis.graphics.NativePlatform;
import static org.lwjgl.sdl.SDLVideo.*;
import static org.lwjgl.sdl.SDLVulkan.*;
import static org.lwjgl.system.MemoryUtil.*;

/** Minecraft's actual loader/surface API through the generated SDL classes. */
public final class FacadeVulkanTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        VK.create(args[0]);
        SharedLibrary library = (SharedLibrary) VK.getFunctionProvider();
        check(SDL_Vulkan_GetVkGetInstanceProcAddr() == 0, "not loaded yet");
        check(SDL_Vulkan_LoadLibrary(library.getPath()), "same library accepted");
        check(SDL_Vulkan_GetVkGetInstanceProcAddr() == library.getFunctionAddress("vkGetInstanceProcAddr"),
                "Minecraft loader identity check");
        check(!SDL_Vulkan_LoadLibrary("/another/driver.so"), "second driver rejected");
        PointerBuffer names = SDL_Vulkan_GetInstanceExtensions();
        check(names != null && names.remaining() == 2 && memASCII(names.get(0)).equals("VK_KHR_surface")
                && memASCII(names.get(1)).equals("VK_EXT_headless_surface"), "Orbis WSI extensions");
        check(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO), "init");
        long window = SDL_CreateWindow("Vulkan", 854, 480, SDL_WINDOW_VULKAN);
        check(window != 0 && NativePlatform.vulkanWindows == 1 && NativePlatform.lastContext == null,
                "Vulkan window creates no EGL context");
        check((SDL_GetWindowFlags(window) & SDL_WINDOW_VULKAN) != 0
                && (SDL_GetWindowFlags(window) & SDL_WINDOW_OPENGL) == 0, "window flags preserve Vulkan");
        check(SDL_GL_CreateContext(window) == 0, "Vulkan window rejects GL context");
        check(SDL_CreateWindow("mixed", 320, 480, SDL_WINDOW_OPENGL) == 0, "single scanout cannot mix backends");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default();
            VkInstance instance = new VkInstance(0x1234, info);
            VkPhysicalDevice physical = new VkPhysicalDevice(0x4321, instance);
            check(SDL_Vulkan_GetPresentationSupport(instance, physical, 0), "graphics presentation queue");
            check(!SDL_Vulkan_GetPresentationSupport(instance, physical, 1), "unsupported queue");
            LongBuffer output = stack.mallocLong(2); output.put(0, 77).put(1, 88).position(1);
            VkAllocationCallbacks allocator = VkAllocationCallbacks.calloc(stack);
            check(SDL_Vulkan_CreateSurface(window, instance, allocator, output) && output.get(0) == 77
                    && output.get(1) == 0x9876, "surface output respects buffer position");
            check(NativePlatform.vulkanInstance == instance.address() && NativePlatform.vulkanAllocator == allocator.address(),
                    "instance and allocation callbacks forwarded");
            NativePlatform.vulkanSurfaceResult = -1;
            check(!SDL_Vulkan_CreateSurface(window, instance, null, output), "native error is not success");
            NativePlatform.vulkanSurfaceResult = 0;
            SDL_DestroyWindow(window);
            long gl = SDL_CreateWindow("fallback", 854, 480, SDL_WINDOW_OPENGL);
            check(gl != 0 && NativePlatform.lastContext != null, "GL fallback after Vulkan teardown");
            check(!SDL_Vulkan_CreateSurface(gl, instance, null, output), "GL window rejects Vulkan surface");
            SDL_DestroyWindow(gl);
        }
        SDL_Vulkan_UnloadLibrary();
        check(SDL_Vulkan_GetVkGetInstanceProcAddr() == 0, "unloaded SDL view");
        check(library.getFunctionAddress("vkGetInstanceProcAddr") != 0, "SDL does not unload LWJGL's owned library");
        SDLInit.SDL_Quit(); VK.destroy();
        System.out.println("SDL Vulkan facade behavior passed");
    }
}
