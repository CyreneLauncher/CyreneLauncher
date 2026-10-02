package org.lwjgl.glfw;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.FunctionProvider;

import orbis.graphics.NativePlatform;
import runner.gpu.NativeInput;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Orbis implementation behind the generated {@link GLFW} facade. Every method here has
 * the exact name and descriptor of a LWJGL 3.2.2 {@code GLFW} method; the generator
 * delegates those and makes the rest report as unsupported.
 *
 * <p>One full-screen window and one desktop GL context (orbis.graphics.NativePlatform),
 * input from the runner's native input service (runner.gpu.NativeInput). Callbacks are
 * plain Java objects called on the thread that polls events; no native closures. The
 * previous callback is returned only when it is itself a callback object.
 */
final class OrbisGLFW {
    static final long MONITOR = 0x4f524249534d4f4eL; // opaque, never dereferenced
    private static final int INPUT_WIDTH = NativeInput.WIDTH, INPUT_HEIGHT = NativeInput.HEIGHT;
    private static final int MAX_EVENTS_PER_POLL = 256;
    private static final int HID_BACKSPACE = 42;

    private static boolean initialized, inputAvailable, dispatching;
    private static long window;
    private static int framebufferWidth = INPUT_WIDTH, framebufferHeight = INPUT_HEIGHT;
    private static boolean shouldClose, fullscreen;
    private static CharSequence title = "";
    private static final ThreadLocal<long[]> current = ThreadLocal.withInitial(() -> new long[1]);
    private static final long timerBase = System.nanoTime();
    private static long timerOffset;

    private static int hintMajor, hintMinor, hintProfile, hintClientApi, hintDepth, hintStencil;
    private static boolean hintForwardCompat;

    private static GLFWErrorCallbackI errorCallback;
    private static GLFWMonitorCallbackI monitorCallback;
    private static GLFWKeyCallbackI keyCallback;
    private static GLFWCharCallbackI charCallback;
    private static GLFWCharModsCallbackI charModsCallback;
    private static GLFWMouseButtonCallbackI mouseButtonCallback;
    private static GLFWCursorPosCallbackI cursorPosCallback;
    private static GLFWCursorEnterCallbackI cursorEnterCallback;
    private static GLFWScrollCallbackI scrollCallback;
    private static GLFWDropCallbackI dropCallback;
    private static GLFWFramebufferSizeCallbackI framebufferSizeCallback;
    private static GLFWWindowSizeCallbackI windowSizeCallback;
    private static GLFWWindowPosCallbackI windowPosCallback;
    private static GLFWWindowFocusCallbackI windowFocusCallback;
    private static GLFWWindowCloseCallbackI windowCloseCallback;

    private static final boolean[] keys = new boolean[GLFW_KEY_LAST + 1];
    private static final boolean[] stickyKeys = new boolean[GLFW_KEY_LAST + 1];
    private static final boolean[] rawDown = new boolean[256];
    private static final boolean[] buttons = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private static final boolean[] stickyButtons = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private static int modifierBits, lastEventType = -1, lastEventRaw = -1;
    private static int cursorMode = GLFW_CURSOR_NORMAL;
    private static boolean stickyKeysMode, stickyButtonsMode, lockKeyModsMode, rawMouseMotion;
    private static double cursorX, cursorY;
    private static int pointerX = INPUT_WIDTH / 2, pointerY = INPUT_HEIGHT / 2;
    private static String clipboard;

    private static int lastError;
    private static ByteBuffer lastErrorText;
    private static GLFWVidMode.Buffer modes;
    private static PointerBuffer monitors;
    private static final int[] packet = new int[NativeInput.EVENT_WORDS];

    static { glfwDefaultWindowHints(); }

    private OrbisGLFW() {}

    // ---------------------------------------------------------------- errors

    static void error(int code, String description) {
        synchronized (OrbisGLFW.class) {
            lastError = code;
            if (lastErrorText != null) memFree(lastErrorText);
            lastErrorText = memUTF8(description);
        }
        GLFWErrorCallbackI callback = errorCallback;
        if (callback != null) {
            ByteBuffer text = memUTF8(description);
            try {
                callback.invoke(code, memAddress(text));
            } finally {
                memFree(text);
            }
        }
    }

    static GLFWErrorCallback glfwSetErrorCallback(GLFWErrorCallbackI cbfun) {
        GLFWErrorCallbackI previous = errorCallback;
        errorCallback = cbfun;
        return previous instanceof GLFWErrorCallback ? (GLFWErrorCallback) previous : null;
    }

    static int glfwGetError(PointerBuffer description) {
        synchronized (OrbisGLFW.class) {
            int code = lastError;
            if (description != null) description.put(description.position(),
                    code == GLFW_NO_ERROR || lastErrorText == null ? NULL : memAddress(lastErrorText));
            lastError = GLFW_NO_ERROR;
            return code;
        }
    }

    private static boolean requireInit() {
        if (!initialized) error(GLFW_NOT_INITIALIZED, "The GLFW library is not initialized");
        return initialized;
    }

    private static boolean requireWindow(long handle) {
        if (!requireInit()) return false;
        if (handle == NULL || handle != window) {
            error(GLFW_INVALID_VALUE, "Invalid window handle 0x" + Long.toHexString(handle));
            return false;
        }
        return true;
    }

    private static boolean requireMonitor(long monitor) {
        if (!requireInit()) return false;
        if (monitor != MONITOR) {
            error(GLFW_INVALID_VALUE, "Invalid monitor handle 0x" + Long.toHexString(monitor));
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- lifecycle

    static boolean glfwInit() {
        if (initialized) return true;
        int status;
        try {
            // NativePlatform loads liborbis_lwjgl_platform when first used.
            status = NativePlatform.nInitialize(System.getProperty("orbis.graphics.processName", "orbis-lwjgl"),
                    Integer.getInteger("orbis.graphics.arenaMib", 0));
        } catch (LinkageError failure) {
            error(GLFW_PLATFORM_ERROR, "Orbis graphics platform library unavailable: " + failure);
            return false;
        }
        if (status != 0) {
            error(GLFW_PLATFORM_ERROR, "EGL initialisation failed: " + NativePlatform.nLastError());
            return false;
        }
        try {
            // Before GL's static initializer runs, or it loads libGL.so itself.
            Configuration.OPENGL_EXPLICIT_INIT.set(true);
            if (GL.getFunctionProvider() == null) GL.create(new EglFunctionProvider());
        } catch (RuntimeException | LinkageError failure) {
            NativePlatform.nTerminate();
            error(GLFW_PLATFORM_ERROR, "OpenGL function provider unavailable: " + failure);
            return false;
        }
        try {
            inputAvailable = NativeInput.start() == 0;
            if (!inputAvailable) error(GLFW_PLATFORM_ERROR, "Runner input service unavailable; no input events");
        } catch (LinkageError failure) {
            inputAvailable = false;
            error(GLFW_PLATFORM_ERROR, "Runner GPU input bridge unavailable (display=gpu required): " + failure);
        }
        initialized = true;
        return true;
    }

    /** OpenGL entry points from Mesa through EGL; explicit init keeps GL from loading libGL. */
    private static final class EglFunctionProvider implements FunctionProvider {
        @Override
        public long getFunctionAddress(CharSequence functionName) {
            return NativePlatform.nGetProcAddress(functionName.toString());
        }

        @Override
        public long getFunctionAddress(ByteBuffer functionName) {
            return NativePlatform.nGetProcAddress(memASCII(memAddress(functionName)));
        }
    }

    static void glfwTerminate() {
        if (!initialized) return;
        if (window != NULL) glfwDestroyWindow(window);
        NativePlatform.nTerminate();
        if (inputAvailable) NativeInput.stop();
        inputAvailable = false;
        keyCallback = null; charCallback = null; charModsCallback = null; mouseButtonCallback = null;
        cursorPosCallback = null; cursorEnterCallback = null; scrollCallback = null; dropCallback = null;
        framebufferSizeCallback = null; windowSizeCallback = null; windowPosCallback = null;
        windowFocusCallback = null; windowCloseCallback = null; monitorCallback = null;
        if (modes != null) { nmemFree(modes.address()); modes = null; }
        if (monitors != null) { memFree(monitors); monitors = null; }
        clipboard = null;
        glfwDefaultWindowHints();
        initialized = false;
    }

    static void glfwGetVersion(int[] major, int[] minor, int[] rev) {
        if (major != null) major[0] = GLFW_VERSION_MAJOR;
        if (minor != null) minor[0] = GLFW_VERSION_MINOR;
        if (rev != null) rev[0] = GLFW_VERSION_REVISION;
    }

    static void glfwGetVersion(IntBuffer major, IntBuffer minor, IntBuffer rev) {
        if (major != null) major.put(major.position(), GLFW_VERSION_MAJOR);
        if (minor != null) minor.put(minor.position(), GLFW_VERSION_MINOR);
        if (rev != null) rev.put(rev.position(), GLFW_VERSION_REVISION);
    }

    static String glfwGetVersionString() {
        return GLFW_VERSION_MAJOR + "." + GLFW_VERSION_MINOR + "." + GLFW_VERSION_REVISION + " Orbis EGL facade";
    }

    // ---------------------------------------------------------------- hints

    static void glfwDefaultWindowHints() {
        hintMajor = 1; hintMinor = 0; hintProfile = GLFW_OPENGL_ANY_PROFILE; hintClientApi = GLFW_OPENGL_API;
        hintDepth = 24; hintStencil = 8; hintForwardCompat = false;
    }

    static void glfwWindowHint(int hint, int value) {
        switch (hint) {
            case GLFW_CONTEXT_VERSION_MAJOR: hintMajor = value; break;
            case GLFW_CONTEXT_VERSION_MINOR: hintMinor = value; break;
            case GLFW_OPENGL_PROFILE: hintProfile = value; break;
            case GLFW_OPENGL_FORWARD_COMPAT: hintForwardCompat = value != GLFW_FALSE; break;
            case GLFW_CLIENT_API: hintClientApi = value; break;
            case GLFW_DEPTH_BITS: hintDepth = value == GLFW_DONT_CARE ? 0 : value; break;
            case GLFW_STENCIL_BITS: hintStencil = value == GLFW_DONT_CARE ? 0 : value; break;
            default: break; // Window decoration/size hints have no meaning on a full-screen scanout.
        }
    }

    // ---------------------------------------------------------------- monitors

    static long glfwGetPrimaryMonitor() {
        return initialized ? MONITOR : NULL;
    }

    static PointerBuffer glfwGetMonitors() {
        if (!requireInit()) return null;
        if (monitors == null) monitors = memAllocPointer(1).put(0, MONITOR);
        return monitors;
    }

    static String glfwGetMonitorName(long monitor) {
        return requireMonitor(monitor) ? "Orbis VideoOut" : null;
    }

    static void glfwGetMonitorPos(long monitor, int[] xpos, int[] ypos) {
        if (xpos != null) xpos[0] = 0;
        if (ypos != null) ypos[0] = 0;
        requireMonitor(monitor);
    }

    static void glfwGetMonitorPos(long monitor, IntBuffer xpos, IntBuffer ypos) {
        if (xpos != null) xpos.put(xpos.position(), 0);
        if (ypos != null) ypos.put(ypos.position(), 0);
        requireMonitor(monitor);
    }

    static void glfwGetMonitorContentScale(long monitor, float[] xscale, float[] yscale) {
        if (xscale != null) xscale[0] = 1f;
        if (yscale != null) yscale[0] = 1f;
        requireMonitor(monitor);
    }

    static void glfwGetMonitorContentScale(long monitor, FloatBuffer xscale, FloatBuffer yscale) {
        if (xscale != null) xscale.put(xscale.position(), 1f);
        if (yscale != null) yscale.put(yscale.position(), 1f);
        requireMonitor(monitor);
    }

    /** One mode: the scanout size once a window exists, 1280x720 before. */
    private static GLFWVidMode.Buffer modes() {
        if (modes == null) modes = GLFWVidMode.create(nmemCallocChecked(1, GLFWVidMode.SIZEOF), 1);
        long address = modes.address();
        memPutInt(address + GLFWVidMode.WIDTH, framebufferWidth);
        memPutInt(address + GLFWVidMode.HEIGHT, framebufferHeight);
        memPutInt(address + GLFWVidMode.REDBITS, 8);
        memPutInt(address + GLFWVidMode.GREENBITS, 8);
        memPutInt(address + GLFWVidMode.BLUEBITS, 8);
        memPutInt(address + GLFWVidMode.REFRESHRATE, 60);
        return modes;
    }

    static GLFWVidMode glfwGetVideoMode(long monitor) {
        return requireMonitor(monitor) ? modes().get(0) : null;
    }

    static GLFWVidMode.Buffer glfwGetVideoModes(long monitor) {
        return requireMonitor(monitor) ? modes() : null;
    }

    static GLFWMonitorCallback glfwSetMonitorCallback(GLFWMonitorCallbackI cbfun) {
        GLFWMonitorCallbackI previous = monitorCallback;
        monitorCallback = cbfun;
        return previous instanceof GLFWMonitorCallback ? (GLFWMonitorCallback) previous : null;
    }

    // ---------------------------------------------------------------- window

    static long glfwCreateWindow(int width, int height, CharSequence windowTitle, long monitor, long share) {
        if (!requireInit()) return NULL;
        if (window != NULL) {
            error(GLFW_PLATFORM_ERROR, "Only one window is supported");
            return NULL;
        }
        if (share != NULL) {
            error(GLFW_PLATFORM_ERROR, "Shared contexts are not supported");
            return NULL;
        }
        if (hintClientApi != GLFW_OPENGL_API) {
            error(GLFW_API_UNAVAILABLE, "Only desktop OpenGL contexts are supported");
            return NULL;
        }
        long handle = NativePlatform.nCreateWindow(hintMajor, hintMinor, hintProfile == GLFW_OPENGL_CORE_PROFILE,
                hintForwardCompat, hintDepth, hintStencil);
        if (handle == NULL) {
            error(GLFW_VERSION_UNAVAILABLE, "Context " + hintMajor + "." + hintMinor + " unavailable: "
                    + NativePlatform.nLastError());
            return NULL;
        }
        window = handle;
        framebufferWidth = NativePlatform.nFramebufferWidth(handle);
        framebufferHeight = NativePlatform.nFramebufferHeight(handle);
        fullscreen = monitor != NULL;
        shouldClose = false;
        title = windowTitle == null ? "" : windowTitle.toString();
        cursorX = framebufferWidth / 2.0;
        cursorY = framebufferHeight / 2.0;
        return handle;
    }

    static long glfwCreateWindow(int width, int height, ByteBuffer windowTitle, long monitor, long share) {
        return glfwCreateWindow(width, height, windowTitle == null ? "" : memUTF8(memAddress(windowTitle)), monitor, share);
    }

    static void glfwDestroyWindow(long handle) {
        if (handle == NULL || !requireWindow(handle)) return;
        if (current.get()[0] == handle) {
            NativePlatform.nMakeCurrent(NULL);
            current.get()[0] = NULL;
        }
        NativePlatform.nDestroyWindow(handle);
        window = NULL;
        clearInput();
    }

    static boolean glfwWindowShouldClose(long handle) {
        return requireWindow(handle) && shouldClose;
    }

    static void glfwSetWindowShouldClose(long handle, boolean value) {
        if (requireWindow(handle)) shouldClose = value;
    }

    static void glfwSetWindowTitle(long handle, CharSequence windowTitle) {
        if (requireWindow(handle)) title = windowTitle == null ? "" : windowTitle.toString();
    }

    static void glfwSetWindowTitle(long handle, ByteBuffer windowTitle) {
        if (requireWindow(handle)) title = windowTitle == null ? "" : memUTF8(memAddress(windowTitle));
    }

    /** A console scanout has no window icon; accepted so applications start normally. */
    static void glfwSetWindowIcon(long handle, GLFWImage.Buffer images) {
        requireWindow(handle);
    }

    static void glfwShowWindow(long handle) { requireWindow(handle); }

    static void glfwHideWindow(long handle) { requireWindow(handle); }

    static void glfwFocusWindow(long handle) { requireWindow(handle); }

    static void glfwSetWindowPos(long handle, int xpos, int ypos) { requireWindow(handle); }

    static void glfwGetWindowPos(long handle, int[] xpos, int[] ypos) {
        if (xpos != null) xpos[0] = 0;
        if (ypos != null) ypos[0] = 0;
        requireWindow(handle);
    }

    static void glfwGetWindowPos(long handle, IntBuffer xpos, IntBuffer ypos) {
        if (xpos != null) xpos.put(xpos.position(), 0);
        if (ypos != null) ypos.put(ypos.position(), 0);
        requireWindow(handle);
    }

    static void glfwGetWindowSize(long handle, int[] width, int[] height) {
        glfwGetFramebufferSize(handle, width, height);
    }

    static void glfwGetWindowSize(long handle, IntBuffer width, IntBuffer height) {
        glfwGetFramebufferSize(handle, width, height);
    }

    static void glfwGetFramebufferSize(long handle, int[] width, int[] height) {
        boolean valid = requireWindow(handle);
        if (width != null) width[0] = valid ? framebufferWidth : 0;
        if (height != null) height[0] = valid ? framebufferHeight : 0;
    }

    static void glfwGetFramebufferSize(long handle, IntBuffer width, IntBuffer height) {
        boolean valid = requireWindow(handle);
        if (width != null) width.put(width.position(), valid ? framebufferWidth : 0);
        if (height != null) height.put(height.position(), valid ? framebufferHeight : 0);
    }

    static long glfwGetWindowMonitor(long handle) {
        return requireWindow(handle) && fullscreen ? MONITOR : NULL;
    }

    /** The scanout size is fixed; only the full-screen flag changes. */
    static void glfwSetWindowMonitor(long handle, long monitor, int xpos, int ypos, int width, int height,
            int refreshRate) {
        if (!requireWindow(handle)) return;
        if (monitor != NULL && monitor != MONITOR) {
            error(GLFW_INVALID_VALUE, "Invalid monitor handle 0x" + Long.toHexString(monitor));
            return;
        }
        fullscreen = monitor != NULL;
    }

    static int glfwGetWindowAttrib(long handle, int attrib) {
        if (!requireWindow(handle)) return 0;
        switch (attrib) {
            case GLFW_FOCUSED: case GLFW_VISIBLE: return GLFW_TRUE;
            case GLFW_ICONIFIED: case GLFW_RESIZABLE: case GLFW_DECORATED: case GLFW_FLOATING:
            case GLFW_TRANSPARENT_FRAMEBUFFER: case GLFW_MAXIMIZED: case GLFW_HOVERED: return GLFW_FALSE;
            case GLFW_CLIENT_API: return GLFW_OPENGL_API;
            case GLFW_CONTEXT_VERSION_MAJOR: return hintMajor;
            case GLFW_CONTEXT_VERSION_MINOR: return hintMinor;
            case GLFW_OPENGL_PROFILE: return hintProfile;
            case GLFW_OPENGL_FORWARD_COMPAT: return hintForwardCompat ? GLFW_TRUE : GLFW_FALSE;
            default:
                error(GLFW_INVALID_ENUM, "Unsupported window attribute 0x" + Integer.toHexString(attrib));
                return 0;
        }
    }

    // ---------------------------------------------------------------- context

    static void glfwMakeContextCurrent(long handle) {
        if (!requireInit()) return;
        if (handle != NULL && !requireWindow(handle)) return;
        if (!NativePlatform.nMakeCurrent(handle)) {
            error(GLFW_PLATFORM_ERROR, "eglMakeCurrent failed: " + NativePlatform.nLastError());
            return;
        }
        current.get()[0] = handle;
    }

    static long glfwGetCurrentContext() {
        return current.get()[0];
    }

    static void glfwSwapBuffers(long handle) {
        if (requireWindow(handle) && !NativePlatform.nSwapBuffers(handle))
            error(GLFW_PLATFORM_ERROR, "eglSwapBuffers failed: " + NativePlatform.nLastError());
    }

    static void glfwSwapInterval(int interval) {
        if (!requireInit()) return;
        if (current.get()[0] == NULL) {
            error(GLFW_NO_CURRENT_CONTEXT, "Cannot set swap interval without a current OpenGL context");
            return;
        }
        if (!NativePlatform.nSwapInterval(Math.max(interval, 0)))
            error(GLFW_PLATFORM_ERROR, "eglSwapInterval failed: " + NativePlatform.nLastError());
    }

    static long glfwGetProcAddress(CharSequence procname) {
        if (!requireInit()) return NULL;
        if (current.get()[0] == NULL) {
            error(GLFW_NO_CURRENT_CONTEXT, "Cannot query entry point without a current OpenGL context");
            return NULL;
        }
        return NativePlatform.nGetProcAddress(procname.toString());
    }

    static long glfwGetProcAddress(ByteBuffer procname) {
        return glfwGetProcAddress(memASCII(memAddress(procname)));
    }

    static long nglfwGetProcAddress(long procname) {
        return glfwGetProcAddress(memASCII(procname));
    }

    // ---------------------------------------------------------------- time

    static double glfwGetTime() {
        return (System.nanoTime() - timerBase + timerOffset) / 1e9;
    }

    static void glfwSetTime(double time) {
        if (time < 0 || time > 18446744073.0) {
            error(GLFW_INVALID_VALUE, "Invalid time " + time);
            return;
        }
        timerOffset = (long) (time * 1e9) - (System.nanoTime() - timerBase);
    }

    static long glfwGetTimerValue() {
        return System.nanoTime() - timerBase;
    }

    static long glfwGetTimerFrequency() {
        return 1_000_000_000L;
    }

    // ---------------------------------------------------------------- clipboard (process-local)

    static void glfwSetClipboardString(long handle, CharSequence string) {
        if (requireInit()) clipboard = string == null ? null : string.toString();
    }

    static void glfwSetClipboardString(long handle, ByteBuffer string) {
        if (requireInit()) clipboard = string == null ? null : memUTF8(memAddress(string));
    }

    static String glfwGetClipboardString(long handle) {
        if (!requireInit()) return null;
        if (clipboard == null) error(GLFW_FORMAT_UNAVAILABLE, "Clipboard is empty");
        return clipboard;
    }

    // ---------------------------------------------------------------- callbacks

    static GLFWKeyCallback glfwSetKeyCallback(long handle, GLFWKeyCallbackI cbfun) {
        if (!requireWindow(handle)) return null;
        GLFWKeyCallbackI previous = keyCallback; keyCallback = cbfun;
        return previous instanceof GLFWKeyCallback ? (GLFWKeyCallback) previous : null;
    }

    static GLFWCharCallback glfwSetCharCallback(long handle, GLFWCharCallbackI cbfun) {
        if (!requireWindow(handle)) return null;
        GLFWCharCallbackI previous = charCallback; charCallback = cbfun;
        return previous instanceof GLFWCharCallback ? (GLFWCharCallback) previous : null;
    }

    static GLFWCharModsCallback glfwSetCharModsCallback(long handle, GLFWCharModsCallbackI cbfun) {
        if (!requireWindow(handle)) return null;
        GLFWCharModsCallbackI previous = charModsCallback; charModsCallback = cbfun;
        return previous instanceof GLFWCharModsCallback ? (GLFWCharModsCallback) previous : null;
    }

    static GLFWMouseButtonCallback glfwSetMouseButtonCallback(long handle, GLFWMouseButtonCallbackI cbfun) {
        if (!requireWindow(handle)) return null;
        GLFWMouseButtonCallbackI previous = mouseButtonCallback; mouseButtonCallback = cbfun;
        return previous instanceof GLFWMouseButtonCallback ? (GLFWMouseButtonCallback) previous : null;
    }

    static GLFWCursorPosCallback glfwSetCursorPosCallback(long handle, GLFWCursorPosCallbackI cbfun) {
        if (!requireWindow(handle)) return null;
        GLFWCursorPosCallbackI previous = cursorPosCallback; cursorPosCallback = cbfun;
        return previous instanceof GLFWCursorPosCallback ? (GLFWCursorPosCallback) previous : null;
    }

    static GLFWCursorEnterCallback glfwSetCursorEnterCallback(long handle, GLFWCursorEnterCallbackI cbfun) {
        if (!requireWindow(handle)) return null;
        GLFWCursorEnterCallbackI previous = cursorEnterCallback; cursorEnterCallback = cbfun;
        return previous instanceof GLFWCursorEnterCallback ? (GLFWCursorEnterCallback) previous : null;
    }

    static GLFWScrollCallback glfwSetScrollCallback(long handle, GLFWScrollCallbackI cbfun) {
        if (!requireWindow(handle)) return null;
        GLFWScrollCallbackI previous = scrollCallback; scrollCallback = cbfun;
        return previous instanceof GLFWScrollCallback ? (GLFWScrollCallback) previous : null;
    }

    /** Stored for API compatibility; there is no drag-and-drop source on the console. */
    static GLFWDropCallback glfwSetDropCallback(long handle, GLFWDropCallbackI cbfun) {
        if (!requireWindow(handle)) return null;
        GLFWDropCallbackI previous = dropCallback; dropCallback = cbfun;
        return previous instanceof GLFWDropCallback ? (GLFWDropCallback) previous : null;
    }

    static GLFWFramebufferSizeCallback glfwSetFramebufferSizeCallback(long handle, GLFWFramebufferSizeCallbackI cbfun) {
        if (!requireWindow(handle)) return null;
        GLFWFramebufferSizeCallbackI previous = framebufferSizeCallback; framebufferSizeCallback = cbfun;
        return previous instanceof GLFWFramebufferSizeCallback ? (GLFWFramebufferSizeCallback) previous : null;
    }

    static GLFWWindowSizeCallback glfwSetWindowSizeCallback(long handle, GLFWWindowSizeCallbackI cbfun) {
        if (!requireWindow(handle)) return null;
        GLFWWindowSizeCallbackI previous = windowSizeCallback; windowSizeCallback = cbfun;
        return previous instanceof GLFWWindowSizeCallback ? (GLFWWindowSizeCallback) previous : null;
    }

    static GLFWWindowPosCallback glfwSetWindowPosCallback(long handle, GLFWWindowPosCallbackI cbfun) {
        if (!requireWindow(handle)) return null;
        GLFWWindowPosCallbackI previous = windowPosCallback; windowPosCallback = cbfun;
        return previous instanceof GLFWWindowPosCallback ? (GLFWWindowPosCallback) previous : null;
    }

    static GLFWWindowFocusCallback glfwSetWindowFocusCallback(long handle, GLFWWindowFocusCallbackI cbfun) {
        if (!requireWindow(handle)) return null;
        GLFWWindowFocusCallbackI previous = windowFocusCallback; windowFocusCallback = cbfun;
        return previous instanceof GLFWWindowFocusCallback ? (GLFWWindowFocusCallback) previous : null;
    }

    static GLFWWindowCloseCallback glfwSetWindowCloseCallback(long handle, GLFWWindowCloseCallbackI cbfun) {
        if (!requireWindow(handle)) return null;
        GLFWWindowCloseCallbackI previous = windowCloseCallback; windowCloseCallback = cbfun;
        return previous instanceof GLFWWindowCloseCallback ? (GLFWWindowCloseCallback) previous : null;
    }

    static void freeWindowCallbacks(long handle) {
        if (!requireWindow(handle)) return;
        Object[] previous = {keyCallback, charCallback, charModsCallback, mouseButtonCallback, cursorPosCallback,
            cursorEnterCallback, scrollCallback, dropCallback, framebufferSizeCallback, windowSizeCallback,
            windowPosCallback, windowFocusCallback, windowCloseCallback};
        keyCallback = null; charCallback = null; charModsCallback = null; mouseButtonCallback = null;
        cursorPosCallback = null; cursorEnterCallback = null; scrollCallback = null; dropCallback = null;
        framebufferSizeCallback = null; windowSizeCallback = null; windowPosCallback = null;
        windowFocusCallback = null; windowCloseCallback = null;
        for (Object callback : previous)
            if (callback instanceof org.lwjgl.system.Callback) ((org.lwjgl.system.Callback) callback).free();
    }

    // ---------------------------------------------------------------- input state

    static int glfwGetKey(long handle, int key) {
        if (!requireWindow(handle)) return GLFW_RELEASE;
        if (key < GLFW_KEY_SPACE || key > GLFW_KEY_LAST) {
            error(GLFW_INVALID_ENUM, "Invalid key " + key);
            return GLFW_RELEASE;
        }
        if (stickyKeys[key]) {
            stickyKeys[key] = false;
            return GLFW_PRESS;
        }
        return keys[key] ? GLFW_PRESS : GLFW_RELEASE;
    }

    static int glfwGetMouseButton(long handle, int button) {
        if (!requireWindow(handle)) return GLFW_RELEASE;
        if (button < 0 || button > GLFW_MOUSE_BUTTON_LAST) {
            error(GLFW_INVALID_ENUM, "Invalid mouse button " + button);
            return GLFW_RELEASE;
        }
        if (stickyButtons[button]) {
            stickyButtons[button] = false;
            return GLFW_PRESS;
        }
        return buttons[button] ? GLFW_PRESS : GLFW_RELEASE;
    }

    static void glfwGetCursorPos(long handle, double[] xpos, double[] ypos) {
        boolean valid = requireWindow(handle);
        if (xpos != null) xpos[0] = valid ? cursorX : 0;
        if (ypos != null) ypos[0] = valid ? cursorY : 0;
    }

    static void glfwGetCursorPos(long handle, DoubleBuffer xpos, DoubleBuffer ypos) {
        boolean valid = requireWindow(handle);
        if (xpos != null) xpos.put(xpos.position(), valid ? cursorX : 0);
        if (ypos != null) ypos.put(ypos.position(), valid ? cursorY : 0);
    }

    static void glfwSetCursorPos(long handle, double xpos, double ypos) {
        if (!requireWindow(handle)) return;
        cursorX = xpos;
        cursorY = ypos;
        if (cursorMode != GLFW_CURSOR_DISABLED && inputAvailable) {
            pointerX = clamp((int) Math.round(xpos * INPUT_WIDTH / framebufferWidth), INPUT_WIDTH);
            pointerY = clamp((int) Math.round(ypos * INPUT_HEIGHT / framebufferHeight), INPUT_HEIGHT);
            NativeInput.setPointer(pointerX, pointerY);
        }
    }

    static void glfwSetInputMode(long handle, int mode, int value) {
        if (!requireWindow(handle)) return;
        switch (mode) {
            case GLFW_CURSOR:
                if (value != GLFW_CURSOR_NORMAL && value != GLFW_CURSOR_HIDDEN && value != GLFW_CURSOR_DISABLED) {
                    error(GLFW_INVALID_ENUM, "Invalid cursor mode 0x" + Integer.toHexString(value));
                    return;
                }
                if (value == GLFW_CURSOR_DISABLED && cursorMode != GLFW_CURSOR_DISABLED) recentre();
                cursorMode = value;
                break;
            case GLFW_STICKY_KEYS:
                stickyKeysMode = value != GLFW_FALSE;
                if (!stickyKeysMode) java.util.Arrays.fill(stickyKeys, false);
                break;
            case GLFW_STICKY_MOUSE_BUTTONS:
                stickyButtonsMode = value != GLFW_FALSE;
                if (!stickyButtonsMode) java.util.Arrays.fill(stickyButtons, false);
                break;
            case GLFW_LOCK_KEY_MODS: lockKeyModsMode = value != GLFW_FALSE; break;
            case GLFW_RAW_MOUSE_MOTION: rawMouseMotion = value != GLFW_FALSE; break;
            default: error(GLFW_INVALID_ENUM, "Invalid input mode 0x" + Integer.toHexString(mode));
        }
    }

    static int glfwGetInputMode(long handle, int mode) {
        if (!requireWindow(handle)) return 0;
        switch (mode) {
            case GLFW_CURSOR: return cursorMode;
            case GLFW_STICKY_KEYS: return stickyKeysMode ? GLFW_TRUE : GLFW_FALSE;
            case GLFW_STICKY_MOUSE_BUTTONS: return stickyButtonsMode ? GLFW_TRUE : GLFW_FALSE;
            case GLFW_LOCK_KEY_MODS: return lockKeyModsMode ? GLFW_TRUE : GLFW_FALSE;
            case GLFW_RAW_MOUSE_MOTION: return rawMouseMotion ? GLFW_TRUE : GLFW_FALSE;
            default:
                error(GLFW_INVALID_ENUM, "Invalid input mode 0x" + Integer.toHexString(mode));
                return 0;
        }
    }

    /** Pointer deltas are relative in disabled mode, so raw motion is always available. */
    static boolean glfwRawMouseMotionSupported() {
        return true;
    }

    static String glfwGetKeyName(int key, int scancode) {
        if (!requireInit()) return null;
        if (key == GLFW_KEY_UNKNOWN) key = OrbisKeys.glfwKey(scancode);
        return OrbisKeys.name(key);
    }

    static int glfwGetKeyScancode(int key) {
        return requireInit() ? OrbisKeys.scancode(key) : -1;
    }

    static boolean glfwJoystickPresent(int jid) {
        return false;
    }

    static boolean glfwJoystickIsGamepad(int jid) {
        return false;
    }

    // ---------------------------------------------------------------- events

    static void glfwPollEvents() {
        if (!requireInit()) return;
        drain(0, false);
    }

    static void glfwWaitEvents() {
        if (!requireInit()) return;
        drain(Long.MAX_VALUE, true);
    }

    static void glfwWaitEventsTimeout(double timeout) {
        if (!requireInit()) return;
        if (timeout < 0 || Double.isNaN(timeout)) {
            error(GLFW_INVALID_VALUE, "Invalid timeout " + timeout);
            return;
        }
        drain(timeout >= 9e9 ? Long.MAX_VALUE : (long) (timeout * 1e9), true);
    }

    /** No waiter blocks longer than one 50 ms read, so an empty event is unnecessary. */
    static void glfwPostEmptyEvent() {
        requireInit();
    }

    /** Reads available input; with wait, blocks up to waitNanos for the first event. */
    private static void drain(long waitNanos, boolean wait) {
        if (dispatching || window == NULL || !inputAvailable) {
            if (wait && waitNanos > 0 && waitNanos != Long.MAX_VALUE) sleep(waitNanos);
            return;
        }
        dispatching = true;
        try {
            long deadline = waitNanos == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + waitNanos;
            int handled = 0;
            while (handled < MAX_EVENTS_PER_POLL) {
                int timeout = 0;
                if (wait && handled == 0) {
                    long remaining = deadline == Long.MAX_VALUE ? 50_000_000L : deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    timeout = (int) Math.min(50, Math.max(1, remaining / 1_000_000L));
                }
                int result = NativeInput.readEvent(packet, timeout);
                if (result == 1) {
                    if (wait && handled == 0) continue;
                    break;
                }
                if (result != 0) {
                    inputAvailable = false;
                    error(GLFW_PLATFORM_ERROR, "Input service stopped (status " + result + ")");
                    break;
                }
                dispatch(packet);
                ++handled;
            }
            if (cursorMode == GLFW_CURSOR_DISABLED) recentreIfNeeded();
        } finally {
            dispatching = false;
        }
    }

    private static void sleep(long nanos) {
        try {
            Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void dispatch(int[] event) {
        int type = event[NativeInput.TYPE];
        int raw = event[NativeInput.RAW_KEY];
        switch (type) {
            case NativeInput.EVENT_RESET:
            case NativeInput.EVENT_FOCUS:
                releaseAll();
                modifierBits = event[NativeInput.MODIFIERS];
                for (int code = 0; code < 256; code++)
                    if ((event[NativeInput.PRESSED_KEYS + (code >>> 5)] & (1 << (code & 31))) != 0) {
                        int key = OrbisKeys.glfwKey(code);
                        rawDown[code] = true;
                        if (key != GLFW_KEY_UNKNOWN) keys[key] = true;
                    }
                if (type == NativeInput.EVENT_FOCUS && windowFocusCallback != null)
                    windowFocusCallback.invoke(window, event[NativeInput.FOCUS] == 1);
                break;
            case NativeInput.EVENT_KEY_DOWN:
                if (raw >= 0 && raw < 256) {
                    rawDown[raw] = true;
                    key(OrbisKeys.glfwKey(raw), raw, GLFW_PRESS);
                }
                break;
            case NativeInput.EVENT_KEY_UP:
                if (raw >= 0 && raw < 256) {
                    rawDown[raw] = false;
                    key(OrbisKeys.glfwKey(raw), raw, GLFW_RELEASE);
                }
                break;
            case NativeInput.EVENT_TEXT:
                // A printable key's first TEXT follows its KEY_DOWN directly; later ones are
                // held-key repeats. Backspace has no initial TEXT: every one is a repeat.
                boolean initial = lastEventType == NativeInput.EVENT_KEY_DOWN && lastEventRaw == raw
                        && raw != HID_BACKSPACE;
                if (!initial && raw >= 0 && raw < 256 && rawDown[raw])
                    key(OrbisKeys.glfwKey(raw), raw, GLFW_REPEAT);
                int codepoint = event[NativeInput.UNICODE_SCALAR];
                if (codepoint >= 32 && codepoint != 127) {
                    if (charCallback != null) charCallback.invoke(window, codepoint);
                    if (charModsCallback != null) charModsCallback.invoke(window, codepoint, mods());
                }
                break;
            case NativeInput.EVENT_MODIFIERS:
                int previous = modifierBits;
                modifierBits = event[NativeInput.MODIFIERS];
                for (int bit = 0; bit < 8; bit++) {
                    boolean was = (previous & (1 << bit)) != 0, now = (modifierBits & (1 << bit)) != 0;
                    if (was != now) key(OrbisKeys.glfwKey(224 + bit), 224 + bit, now ? GLFW_PRESS : GLFW_RELEASE);
                }
                break;
            case NativeInput.EVENT_MOTION:
                motion(event[NativeInput.X], event[NativeInput.Y]);
                break;
            case NativeInput.EVENT_BUTTON_DOWN:
            case NativeInput.EVENT_BUTTON_UP:
                int button = event[NativeInput.BUTTON];
                if (button >= 0 && button <= GLFW_MOUSE_BUTTON_LAST) {
                    boolean down = type == NativeInput.EVENT_BUTTON_DOWN;
                    if (buttons[button] != down) {
                        buttons[button] = down;
                        if (down && stickyButtonsMode) stickyButtons[button] = true;
                        if (mouseButtonCallback != null)
                            mouseButtonCallback.invoke(window, button, down ? GLFW_PRESS : GLFW_RELEASE, mods());
                    }
                }
                break;
            case NativeInput.EVENT_WHEEL:
                if (scrollCallback != null) scrollCallback.invoke(window, 0, event[NativeInput.WHEEL_DELTA]);
                break;
            default:
                break;
        }
        lastEventType = type;
        lastEventRaw = raw;
    }

    private static void key(int key, int scancode, int action) {
        if (key != GLFW_KEY_UNKNOWN) {
            if (action == GLFW_RELEASE) keys[key] = false;
            else {
                if (!keys[key] && stickyKeysMode) stickyKeys[key] = true;
                keys[key] = true;
            }
        }
        if (keyCallback != null) keyCallback.invoke(window, key, scancode, action, mods());
    }

    private static void motion(int x, int y) {
        if (cursorMode == GLFW_CURSOR_DISABLED) {
            cursorX += (x - pointerX) * (double) framebufferWidth / INPUT_WIDTH;
            cursorY += (y - pointerY) * (double) framebufferHeight / INPUT_HEIGHT;
        } else {
            cursorX = x * (double) framebufferWidth / INPUT_WIDTH;
            cursorY = y * (double) framebufferHeight / INPUT_HEIGHT;
        }
        pointerX = x;
        pointerY = y;
        if (cursorPosCallback != null) cursorPosCallback.invoke(window, cursorX, cursorY);
    }

    /** Disabled mode keeps the native pointer away from the clamp so deltas stay unbounded. */
    private static void recentreIfNeeded() {
        if (Math.abs(pointerX - INPUT_WIDTH / 2) > INPUT_WIDTH / 4
                || Math.abs(pointerY - INPUT_HEIGHT / 2) > INPUT_HEIGHT / 4) recentre();
    }

    private static void recentre() {
        pointerX = INPUT_WIDTH / 2;
        pointerY = INPUT_HEIGHT / 2;
        if (inputAvailable) NativeInput.setPointer(pointerX, pointerY);
    }

    private static int mods() {
        int mods = 0;
        if ((modifierBits & 0x22) != 0) mods |= GLFW_MOD_SHIFT;
        if ((modifierBits & 0x11) != 0) mods |= GLFW_MOD_CONTROL;
        if ((modifierBits & 0x44) != 0) mods |= GLFW_MOD_ALT;
        if ((modifierBits & 0x88) != 0) mods |= GLFW_MOD_SUPER;
        return mods;
    }

    /** Releases held keys and buttons with callbacks, as GLFW does on focus loss. */
    private static void releaseAll() {
        for (int code = 0; code < 256; code++) {
            if (!rawDown[code]) continue;
            rawDown[code] = false;
            key(OrbisKeys.glfwKey(code), code, GLFW_RELEASE);
        }
        for (int bit = 0; bit < 8; bit++)
            if ((modifierBits & (1 << bit)) != 0) key(OrbisKeys.glfwKey(224 + bit), 224 + bit, GLFW_RELEASE);
        modifierBits = 0;
        java.util.Arrays.fill(keys, false);
        for (int button = 0; button <= GLFW_MOUSE_BUTTON_LAST; button++) {
            if (!buttons[button]) continue;
            buttons[button] = false;
            if (mouseButtonCallback != null && window != NULL)
                mouseButtonCallback.invoke(window, button, GLFW_RELEASE, 0);
        }
    }

    /** Destroying the window drops input state without callbacks, as GLFW does. */
    private static void clearInput() {
        java.util.Arrays.fill(rawDown, false);
        java.util.Arrays.fill(keys, false);
        java.util.Arrays.fill(stickyKeys, false);
        java.util.Arrays.fill(buttons, false);
        java.util.Arrays.fill(stickyButtons, false);
        modifierBits = 0;
        lastEventType = lastEventRaw = -1;
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }
}
