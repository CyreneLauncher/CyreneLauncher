/* SharedLibraryUtil.getLibraryPath for Orbis. LWJGL's Linux/ELF implementation walks dl_iterate_phdr, which
 * knows nothing of modules the runtime's dlopen wrapper loads (it hands out module ids, not link maps), and
 * crashed on a NULL entry when Minecraft 26.3's Vulkan backend asked for the Vulkan library's path. The path
 * is informational: report it unknown (0), which LWJGL returns as null. */
#include <jni.h>

JNIEXPORT int JNICALL Java_org_lwjgl_system_SharedLibraryUtil_getLibraryPath(
        JNIEnv *env, jclass clazz, jlong pLibAddress, jlong sOutAddress, jint bufSize) {
    (void)env; (void)clazz; (void)pLibAddress; (void)sOutAddress; (void)bufSize;
    return 0;
}
