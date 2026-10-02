package org.lwjgl.glfw;

/**
 * Orbis replacement for LWJGL 3.2.2 {@code Callbacks}: same public API. Callbacks are
 * held by the facade as Java objects, so clearing them needs no native function table.
 */
public final class Callbacks {
    private Callbacks() {}

    /** Resets every window callback and frees those that own a native closure. */
    public static void glfwFreeCallbacks(long window) {
        OrbisGLFW.freeWindowCallbacks(window);
    }
}
