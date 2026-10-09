/* GL's static initializer loads this library before it checks for a function provider.
 * Minecraft 26.2 shuffles its library preloads and requires GL.getFunctionProvider() before
 * glfwInit, so the GLFW facade installs its EGL provider from here, through GL's own class loader. */
#include <jni.h>

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_VERSION_1_8;
    jclass facade = (*env)->FindClass(env, "org/lwjgl/glfw/OrbisGLFW");
    if (facade) {
        jmethodID install = (*env)->GetStaticMethodID(env, facade, "installGlProvider", "()V");
        if (install) (*env)->CallStaticVoidMethod(env, facade, install);
    }
    /* Without the facade (or if it fails), glfwInit installs the provider as before. */
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return JNI_VERSION_1_8;
}
