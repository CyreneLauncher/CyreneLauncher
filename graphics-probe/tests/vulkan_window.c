/* Exercise the actual JNI no-API lifecycle, discarding unrelated JNI entry points at link time. */
#include "../lwjgl/platform/orbis_lwjgl_platform.c"
#include <assert.h>
static unsigned egl_calls;
EGLBoolean eglMakeCurrent(EGLDisplay d, EGLSurface a, EGLSurface b, EGLContext c) {
    (void)d; (void)a; (void)b; (void)c; ++egl_calls; return EGL_TRUE;
}
EGLBoolean eglDestroyContext(EGLDisplay d, EGLContext c) { (void)d; (void)c; ++egl_calls; return EGL_TRUE; }
EGLBoolean eglDestroySurface(EGLDisplay d, EGLSurface s) { (void)d; (void)s; ++egl_calls; return EGL_TRUE; }
EGLBoolean eglSwapBuffers(EGLDisplay d, EGLSurface s) { (void)d; (void)s; ++egl_calls; return EGL_TRUE; }
EGLint eglGetError(void) { return EGL_SUCCESS; }
int32_t sceGnmSubmitDone(void) { assert(0); return 0; }
PFN_vkVoidFunction radv_GetInstanceProcAddr(VkInstance instance, const char *name) {
    (void)instance; (void)name; return NULL;
}
int main(void) {
    assert(Java_orbis_graphics_NativePlatform_nCreateVulkanWindow(NULL, NULL) == 0);
    display = (EGLDisplay)(uintptr_t)0x1234;
    assert(Java_orbis_graphics_NativePlatform_nCreateVulkanWindow(NULL, NULL) == PLATFORM_WINDOW);
    assert(surface == EGL_NO_SURFACE && context == EGL_NO_CONTEXT && vulkan_window);
    assert(Java_orbis_graphics_NativePlatform_nFramebufferWidth(NULL, NULL, PLATFORM_WINDOW) == 1920);
    assert(Java_orbis_graphics_NativePlatform_nFramebufferHeight(NULL, NULL, PLATFORM_WINDOW) == 1080);
    assert(Java_orbis_graphics_NativePlatform_nCreateVulkanWindow(NULL, NULL) == 0);
    assert(!Java_orbis_graphics_NativePlatform_nMakeCurrent(NULL, NULL, PLATFORM_WINDOW));
    assert(!Java_orbis_graphics_NativePlatform_nSwapBuffers(NULL, NULL, PLATFORM_WINDOW));
    Java_orbis_graphics_NativePlatform_nDestroyWindow(NULL, NULL, 2);
    assert(vulkan_window);
    Java_orbis_graphics_NativePlatform_nDestroyWindow(NULL, NULL, PLATFORM_WINDOW);
    assert(!vulkan_window && surface_width == 0 && surface_height == 0);
    Java_orbis_graphics_NativePlatform_nDestroyWindow(NULL, NULL, PLATFORM_WINDOW);
    assert(Java_orbis_graphics_NativePlatform_nCreateVulkanWindow(NULL, NULL) == PLATFORM_WINDOW);
    Java_orbis_graphics_NativePlatform_nDestroyWindow(NULL, NULL, PLATFORM_WINDOW);
    surface = (EGLSurface)(uintptr_t)0x5678;
    context = (EGLContext)(uintptr_t)0x9876;
    assert(Java_orbis_graphics_NativePlatform_nCreateVulkanWindow(NULL, NULL) == 0);
    assert(egl_calls == 0);
    return 0;
}
