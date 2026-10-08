package org.lwjgl.glfw;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Orbis implementations of GLFW calls that exist only from LWJGL 3.4 (Minecraft 26.1+), compiled into
 * that release's adapter alone; the generated GLFW delegates here as it does to OrbisGLFW.
 */
final class OrbisGLFW34 {
    private OrbisGLFW34() {}

    private static final int FIRST_PLATFORM = 0x00060001, LAST_PLATFORM = 0x00060005, NULL_PLATFORM = 0x00060005;

    /**
     * Minecraft 26.1 prefers X11 when both X11 and Wayland are built in. The facade is one platform of
     * its own, reported as GLFW's null platform (glfwGetPlatform); it may be asked before glfwInit.
     */
    static boolean glfwPlatformSupported(int platform) {
        if (platform < FIRST_PLATFORM || platform > LAST_PLATFORM) {
            OrbisGLFW.error(GLFW_INVALID_ENUM, "Invalid platform 0x" + Integer.toHexString(platform));
            return false;
        }
        return platform == NULL_PLATFORM;
    }

    // Input-method composition (TextInputManager). Text comes from the PS4 system keyboard, which
    // delivers committed characters, so there is never a preedit and the IME status never changes:
    // the callbacks are kept, as GLFW does, but never invoked.
    /** The handle given for a lambda, as OrbisGLFW's: no native upcall stub (FFM on Java 25) is made. */
    private static final long JAVA_ONLY = 1L;

    private static final class JavaPreeditCallback extends GLFWPreeditCallback {
        private final GLFWPreeditCallbackI delegate;
        JavaPreeditCallback(GLFWPreeditCallbackI delegate) { super(JAVA_ONLY); this.delegate = delegate; }
        @Override public void invoke(long window, int count, long string, int blocks, long sizes, int focused, int caret) {
            delegate.invoke(window, count, string, blocks, sizes, focused, caret);
        }
        @Override public void free() { }
    }

    private static final class JavaIMEStatusCallback extends GLFWIMEStatusCallback {
        private final GLFWIMEStatusCallbackI delegate;
        JavaIMEStatusCallback(GLFWIMEStatusCallbackI delegate) { super(JAVA_ONLY); this.delegate = delegate; }
        @Override public void invoke(long window) { delegate.invoke(window); }
        @Override public void free() { }
    }

    private static GLFWPreeditCallbackI preeditCallback;
    private static GLFWIMEStatusCallbackI imeStatusCallback;

    static GLFWPreeditCallback glfwSetPreeditCallback(long window, GLFWPreeditCallbackI callback) {
        if (!OrbisGLFW.requireWindow(window)) return null;
        GLFWPreeditCallbackI previous = preeditCallback;
        preeditCallback = callback;
        return previous == null || previous instanceof GLFWPreeditCallback
                ? (GLFWPreeditCallback) previous : new JavaPreeditCallback(previous);
    }

    static GLFWIMEStatusCallback glfwSetIMEStatusCallback(long window, GLFWIMEStatusCallbackI callback) {
        if (!OrbisGLFW.requireWindow(window)) return null;
        GLFWIMEStatusCallbackI previous = imeStatusCallback;
        imeStatusCallback = callback;
        return previous == null || previous instanceof GLFWIMEStatusCallback
                ? (GLFWIMEStatusCallback) previous : new JavaIMEStatusCallback(previous);
    }

    /** Where the composition window would go; the system keyboard places itself. */
    static void glfwSetPreeditCursorRectangle(long window, int x, int y, int w, int h) {
        OrbisGLFW.requireWindow(window);
    }
}
