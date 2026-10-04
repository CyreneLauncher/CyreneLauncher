package orbis.graphics;

/**
 * Natives of liborbis_lwjgl_platform: EGL display, one full-screen window and desktop GL
 * context, presentation and GL function lookup. Window handles are opaque; 0 means
 * none. Failures return 0/false/-1 and leave a stage description in {@link #nLastError()}.
 * Callers serialise lifecycle calls and finish GPU work before destroying the window.
 */
public final class NativePlatform {
    static {
        System.loadLibrary("orbis_lwjgl_platform");
    }

    private NativePlatform() {}

    /** Seeds Mesa's environment and initialises EGL; arenaMib 0 keeps Mesa's ladder; an empty
     *  mesaLogFile leaves Mesa logging to stderr. */
    public static native int nInitialize(String processName, int arenaMib, String mesaLogFile);

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

    /** Draws the pointer arrow (top-left at x, top, in GL window coordinates; rows scale pixels
     *  tall) into the default framebuffer and restores the state it touches from values the
     *  platform records as the application sets them - no GL queries, so no glthread sync. */
    public static native void nDrawPointer(int x, int top, int scale);

    /** DualShock 4 state into out[0..7]: connected, button bits (libScePad), left x, left y, right x,
     *  right y, L2, R2 (raw 0-255). Returns 0, or libScePad's error while the pad cannot be opened. */
    public static native int nPadRead(int[] out);

    /** Restarts the application in a fresh process (LoadExec of /app0/eboot.bin). Returns only on
     *  failure, with libSceSystemService's error. */
    public static native int nRelaunch();
}
