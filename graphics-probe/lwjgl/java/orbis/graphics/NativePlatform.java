package orbis.graphics;

/**
 * Natives of liborbis_lwjgl_platform: EGL display, one full-screen window and desktop GL
 * context, presentation and GL function lookup. Window handles are opaque; 0 means
 * none. Failures return 0/false/-1 and leave a stage description in {@link #nLastError()}.
 * Callers serialise lifecycle calls and finish GPU work before destroying the window.
 */
public final class NativePlatform {
    private NativePlatform() {}

    /** Seeds Mesa's environment and initialises EGL; arenaMib 0 keeps Mesa's ladder. */
    public static native int nInitialize(String processName, int arenaMib);

    public static native long nCreateWindow(int glMajor, int glMinor, boolean coreProfile,
            boolean forwardCompatible, int depthBits, int stencilBits);

    /** window 0 releases the calling thread's context. */
    public static native boolean nMakeCurrent(long window);

    public static native boolean nSwapBuffers(long window);

    public static native boolean nSwapInterval(int interval);

    /** GL entry point for LWJGL's FunctionProvider; 0 when unavailable. */
    public static native long nGetProcAddress(String function);

    public static native int nFramebufferWidth(long window);

    public static native int nFramebufferHeight(long window);

    public static native void nDestroyWindow(long window);

    public static native void nTerminate();

    public static native String nLastError();
}
