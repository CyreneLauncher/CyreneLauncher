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
    /** HID usages 0-3 are "no event" and keyboard error codes, never keys (seen on console). */
    private static final int HID_FIRST_KEY = 4;

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
    private static GLFWWindowIconifyCallbackI windowIconifyCallback;

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
    /** -Dorbis.glfw.traceInput=true logs the first input packets to standard output. */
    private static final boolean TRACE_INPUT = Boolean.getBoolean("orbis.glfw.traceInput");
    private static int traced;

    static { glfwDefaultWindowHints(); }

    private OrbisGLFW() {}

    // ---------------------------------------------------------------- errors

    static void error(int code, String description) {
        // Applications often keep only the code (Minecraft prints the text pointer as a number).
        System.err.println("orbis-glfw: error 0x" + Integer.toHexString(code) + ": " + description);
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
        return asCallback(previous);
    }

    /**
     * GLFW returns the previous callback even when it was set as a lambda, which LWJGL wraps in a callback
     * object; Minecraft 1.14 frees it at once. These wrappers are Java only: there is no native upcall
     * stub behind them (the address is a non-null placeholder nothing reads), so free() releases nothing.
     */
    private static final long JAVA_ONLY = 1L;

    private static final class JavaErrorCallback extends GLFWErrorCallback {
        private final GLFWErrorCallbackI delegate;
        JavaErrorCallback(GLFWErrorCallbackI delegate) { super(JAVA_ONLY); this.delegate = delegate; }
        @Override public void invoke(int error, long description) { delegate.invoke(error, description); }
        @Override public void free() { }
    }

    private static final class JavaMonitorCallback extends GLFWMonitorCallback {
        private final GLFWMonitorCallbackI delegate;
        JavaMonitorCallback(GLFWMonitorCallbackI delegate) { super(JAVA_ONLY); this.delegate = delegate; }
        @Override public void invoke(long monitor, int event) { delegate.invoke(monitor, event); }
        @Override public void free() { }
    }

    private static GLFWErrorCallback asCallback(GLFWErrorCallbackI callback) {
        return callback == null || callback instanceof GLFWErrorCallback ? (GLFWErrorCallback) callback
                : new JavaErrorCallback(callback);
    }

    private static GLFWMonitorCallback asCallback(GLFWMonitorCallbackI callback) {
        return callback == null || callback instanceof GLFWMonitorCallback ? (GLFWMonitorCallback) callback
                : new JavaMonitorCallback(callback);
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

    /** Mesa's log destination: a file in the working directory unless orbis.graphics.mesaLog says
     *  otherwise ("stderr" keeps Mesa's default, which on the console is the UART). A busy title
     *  can log thousands of lines a minute and stall the system when they all go to the UART. */
    private static String mesaLogFile() {
        String configured = System.getProperty("orbis.graphics.mesaLog");
        if ("stderr".equals(configured)) return "";
        if (configured != null && !configured.isEmpty()) return configured;
        return java.nio.file.Path.of(System.getProperty("user.dir", "."), "mesa.log").toAbsolutePath().toString();
    }

    static boolean glfwInit() {
        if (initialized) return true;
        int status;
        try {
            // NativePlatform loads liborbis_lwjgl_platform when first used.
            status = NativePlatform.nInitialize(System.getProperty("orbis.graphics.processName", "orbis-lwjgl"),
                    Integer.getInteger("orbis.graphics.arenaMib", 0), mesaLogFile());
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
        windowFocusCallback = null; windowCloseCallback = null; windowIconifyCallback = null; monitorCallback = null;
        if (modes != null) { nmemFree(modes.address()); modes = null; }
        if (monitors != null) { memFree(monitors); monitors = null; }
        clipboard = null;
        glfwDefaultWindowHints();
        inputFocused = true; systemOverlaid = false; systemBackground = false; statusUnavailable = false; statusDue = 0;
        java.util.Arrays.fill(lastSystemStatus, (byte) 0);
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

    /** GLFW_JOYSTICK_HAT_BUTTONS (Controlify turns it off) decides whether the d-pad is also reported as
     *  four joystick buttons; the macOS hints are accepted and do not apply. */
    static void glfwInitHint(int hint, int value) {
        switch (hint) {
            case GLFW_JOYSTICK_HAT_BUTTONS:
                if (value != GLFW_TRUE && value != GLFW_FALSE)
                    error(GLFW_INVALID_VALUE, "Invalid value " + value + " for init hint " + hint);
                else hatButtons = value == GLFW_TRUE;
                break;
            case GLFW_COCOA_CHDIR_RESOURCES:
            case GLFW_COCOA_MENUBAR:
                if (value != GLFW_TRUE && value != GLFW_FALSE)
                    error(GLFW_INVALID_VALUE, "Invalid value " + value + " for init hint " + hint);
                break;
            default:
                error(GLFW_INVALID_ENUM, "Invalid init hint " + hint);
        }
    }

    static void glfwDefaultWindowHints() {
        hintMajor = 1; hintMinor = 0; hintProfile = GLFW_OPENGL_ANY_PROFILE; hintClientApi = GLFW_OPENGL_API;
        hintDepth = 24; hintStencil = 8; hintForwardCompat = false;
    }

    static void glfwWindowHint(int hint, int value) {
        switch (hint) {
            case GLFW_CONTEXT_VERSION_MAJOR: hintMajor = value; break;
            case GLFW_CONTEXT_VERSION_MINOR: hintMinor = value; break;
            case GLFW_OPENGL_PROFILE:
                if (value != GLFW_OPENGL_ANY_PROFILE && value != GLFW_OPENGL_CORE_PROFILE) {
                    error(GLFW_INVALID_VALUE, "Only the core OpenGL profile is available");
                    break;
                }
                hintProfile = value; break;
            case GLFW_OPENGL_FORWARD_COMPAT: hintForwardCompat = value != GLFW_FALSE; break;
            case GLFW_CLIENT_API:
                if (value != GLFW_OPENGL_API) {
                    error(GLFW_INVALID_VALUE, "Only desktop OpenGL is available");
                    break;
                }
                hintClientApi = value; break;
            case GLFW_CONTEXT_CREATION_API:
                if (value != GLFW_NATIVE_CONTEXT_API)
                    error(GLFW_INVALID_VALUE, "Only the native EGL-backed context is available");
                break;
            // A no-error context only skips error checks; a normal context behaves correctly for any caller,
            // as desktop GLFW gives where the driver cannot honour it (Sodium 0.5 asks for one).
            case GLFW_CONTEXT_NO_ERROR: break;
            case GLFW_OPENGL_DEBUG_CONTEXT:
            case GLFW_STEREO:
            case GLFW_SRGB_CAPABLE:
                if (value != GLFW_FALSE)
                    error(GLFW_INVALID_VALUE, "Context/framebuffer option 0x"
                            + Integer.toHexString(hint) + " is unavailable");
                break;
            case GLFW_CONTEXT_ROBUSTNESS:
                if (value != GLFW_NO_ROBUSTNESS)
                    error(GLFW_INVALID_VALUE, "Context robustness is unavailable");
                break;
            case GLFW_CONTEXT_RELEASE_BEHAVIOR:
                if (value != GLFW_ANY_RELEASE_BEHAVIOR)
                    error(GLFW_INVALID_VALUE, "Context release behavior is fixed");
                break;
            case GLFW_SAMPLES:
                if (value != 0)
                    error(GLFW_INVALID_VALUE, "Multisampling cannot be requested on the fixed scanout");
                break;
            case GLFW_DEPTH_BITS: hintDepth = value == GLFW_DONT_CARE ? 0 : value; break;
            case GLFW_STENCIL_BITS: hintStencil = value == GLFW_DONT_CARE ? 0 : value; break;
            // The fullscreen VideoOut surface has no window-manager decorations.
            case GLFW_RESIZABLE: case GLFW_VISIBLE: case GLFW_DECORATED: case GLFW_FOCUSED:
            case GLFW_AUTO_ICONIFY: case GLFW_FLOATING: case GLFW_MAXIMIZED:
            case GLFW_CENTER_CURSOR: case GLFW_TRANSPARENT_FRAMEBUFFER:
            case GLFW_FOCUS_ON_SHOW: case GLFW_SCALE_TO_MONITOR:
                break;
            default:
                error(GLFW_INVALID_ENUM, "Unsupported window hint 0x" + Integer.toHexString(hint));
                break;
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
        return asCallback(previous);
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
        startInput();
        framebufferWidth = NativePlatform.nFramebufferWidth(handle);
        framebufferHeight = NativePlatform.nFramebufferHeight(handle);
        fullscreen = monitor != NULL;
        shouldClose = false;
        title = windowTitle == null ? "" : windowTitle.toString();
        cursorX = framebufferWidth / 2.0;
        cursorY = framebufferHeight / 2.0;
        announcedWidth = width;
        announcedHeight = height;
        return handle;
    }

    static long glfwCreateWindow(int width, int height, ByteBuffer windowTitle, long monitor, long share) {
        return glfwCreateWindow(width, height, windowTitle == null ? "" : memUTF8(memAddress(windowTitle)), monitor, share);
    }

    /**
     * Input starts once the window, and with it VideoOut, exists: the console keyboard
     * delivered no keys when opened before VideoOut (console mode always opened it after).
     */
    private static void startInput() {
        if (inputAvailable) return;
        try {
            inputAvailable = NativeInput.start() == 0;
            if (!inputAvailable) error(GLFW_PLATFORM_ERROR, "Runner input service unavailable; no input events");
        } catch (LinkageError failure) {
            inputAvailable = false;
            error(GLFW_PLATFORM_ERROR, "Runner GPU input bridge unavailable (display=gpu required): " + failure);
        }
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
        // The application now believes the window is width x height; the scanout stays fixed.
        announcedWidth = width;
        announcedHeight = height;
    }

    /** The size the application last asked for; reconciled with the scanout on the next poll. */
    private static int announcedWidth, announcedHeight;

    /**
     * The scanout is fixed, so a requested size never takes effect. A window manager that forces
     * a size reports it through the size callbacks; do the same, so applications that track their
     * own window size (Minecraft scales cursor positions by it) see the real one.
     */
    private static void reconcileSize() {
        if (window == NULL || (announcedWidth == framebufferWidth && announcedHeight == framebufferHeight)) return;
        announcedWidth = framebufferWidth;
        announcedHeight = framebufferHeight;
        if (windowSizeCallback != null) windowSizeCallback.invoke(window, framebufferWidth, framebufferHeight);
        if (framebufferSizeCallback != null) framebufferSizeCallback.invoke(window, framebufferWidth, framebufferHeight);
    }

    static int glfwGetWindowAttrib(long handle, int attrib) {
        if (!requireWindow(handle)) return 0;
        switch (attrib) {
            case GLFW_FOCUSED: return focused() ? GLFW_TRUE : GLFW_FALSE;
            case GLFW_ICONIFIED: return systemBackground ? GLFW_TRUE : GLFW_FALSE;
            case GLFW_VISIBLE: return GLFW_TRUE;
            case GLFW_HOVERED: return systemOwnsScreen() ? GLFW_FALSE : GLFW_TRUE;
            case GLFW_RESIZABLE: case GLFW_DECORATED: case GLFW_FLOATING:
            case GLFW_TRANSPARENT_FRAMEBUFFER: case GLFW_MAXIMIZED: return GLFW_FALSE;
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

    /** The settable attributes are accepted as GLFW does; the console's window stays full-screen and
     *  undecorated, so glfwGetWindowAttrib keeps reporting that (Cubes Without Borders sets DECORATED). */
    static void glfwSetWindowAttrib(long handle, int attrib, int value) {
        if (!requireWindow(handle)) return;
        switch (attrib) {
            case GLFW_DECORATED: case GLFW_RESIZABLE: case GLFW_FLOATING: case GLFW_AUTO_ICONIFY: case GLFW_FOCUS_ON_SHOW:
                if (value != GLFW_TRUE && value != GLFW_FALSE)
                    error(GLFW_INVALID_VALUE, "Invalid value " + value + " for window attribute 0x" + Integer.toHexString(attrib));
                break;
            default:
                error(GLFW_INVALID_ENUM, "Invalid window attribute 0x" + Integer.toHexString(attrib));
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

    /** Whether the current context lists the extension (core profile: GL_NUM_EXTENSIONS and glGetStringi). */
    static boolean glfwExtensionSupported(CharSequence extension) {
        if (!requireInit()) return false;
        if (current.get()[0] == NULL) {
            error(GLFW_NO_CURRENT_CONTEXT, "Cannot query extension without a current OpenGL context");
            return false;
        }
        if (extension == null || extension.length() == 0) {
            error(GLFW_INVALID_VALUE, "Extension name cannot be an empty string");
            return false;
        }
        try {
            String name = extension.toString();
            int count = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_NUM_EXTENSIONS);
            for (int i = 0; i < count; i++)
                if (name.equals(org.lwjgl.opengl.GL30.glGetStringi(org.lwjgl.opengl.GL11.GL_EXTENSIONS, i))) return true;
            return false;
        } catch (IllegalStateException noCapabilities) {
            error(GLFW_PLATFORM_ERROR, "No OpenGL capabilities on this thread: " + noCapabilities.getMessage());
            return false;
        }
    }

    static long glfwGetCurrentContext() {
        return current.get()[0];
    }

    static void glfwSwapBuffers(long handle) {
        if (!requireWindow(handle)) return;
        drawPointer();
        if (!NativePlatform.nSwapBuffers(handle))
            error(GLFW_PLATFORM_ERROR, "eglSwapBuffers failed: " + NativePlatform.nLastError());
    }

    /** Set once a mouse has moved; until then there is no pointer to draw. */
    private static boolean pointerSeen;
    /** -Dorbis.glfw.pointer=false turns the drawn pointer off. */
    private static final boolean DRAW_POINTER = !"false".equals(System.getProperty("orbis.glfw.pointer"));

    /**
     * A desktop compositor draws the GLFW_CURSOR_NORMAL pointer; the console's scanout has none,
     * so draw a small arrow into the default framebuffer just before the swap. Scissored clears
     * touch no shader, texture or buffer state, and every piece of state used is restored.
     */
    private static void drawPointer() {
        if (!DRAW_POINTER || !pointerSeen || cursorMode != GLFW_CURSOR_NORMAL) return;
        try {
            if (GL.getCapabilities() == null) return;
        } catch (IllegalStateException noCapabilities) {
            return;
        }
        // Drawn natively from state the platform records as the application sets it: querying it
        // here made the render thread wait for Mesa's GL worker to drain every frame.
        int scale = Math.max(1, framebufferHeight / 540);
        NativePlatform.nDrawPointer((int) cursorX, framebufferHeight - (int) cursorY, scale);
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

    // ---------------------------------------------------------------- GLFW 3.4 additions

    /**
     * GLFW_PLATFORM_NULL: no desktop windowing system. Minecraft 1.20.2+ asks before setting
     * the window icon and skips it for this platform, as it does for Wayland.
     */
    static int glfwGetPlatform() {
        return 0x00060005;
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

    /** Stored for API compatibility; the full-screen console window is never iconified. */
    static GLFWWindowIconifyCallback glfwSetWindowIconifyCallback(long handle, GLFWWindowIconifyCallbackI cbfun) {
        if (!requireWindow(handle)) return null;
        GLFWWindowIconifyCallbackI previous = windowIconifyCallback; windowIconifyCallback = cbfun;
        return previous instanceof GLFWWindowIconifyCallback ? (GLFWWindowIconifyCallback) previous : null;
    }

    static void freeWindowCallbacks(long handle) {
        if (!requireWindow(handle)) return;
        Object[] previous = {keyCallback, charCallback, charModsCallback, mouseButtonCallback, cursorPosCallback,
            cursorEnterCallback, scrollCallback, dropCallback, framebufferSizeCallback, windowSizeCallback,
            windowPosCallback, windowFocusCallback, windowCloseCallback, windowIconifyCallback};
        keyCallback = null; charCallback = null; charModsCallback = null; mouseButtonCallback = null;
        cursorPosCallback = null; cursorEnterCallback = null; scrollCallback = null; dropCallback = null;
        framebufferSizeCallback = null; windowSizeCallback = null; windowPosCallback = null;
        windowFocusCallback = null; windowCloseCallback = null; windowIconifyCallback = null;
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

    // ---------------------------------------------------------------- gamepad

    /* The DualShock 4 as GLFW_JOYSTICK_1, read once per event poll (NativePlatform.nPadRead):
     * connected, button bits, then raw 0-255 sticks and triggers. */
    private static final int[] pad = new int[8];
    private static boolean padPolled, padConnected;
    private static GLFWJoystickCallbackI joystickCallback;
    private static final String PAD_NAME = "PS4 Controller";
    // SDL's GUID for a Sony DualShock 4 (v2), so mapping-aware mods recognise it.
    private static final String PAD_GUID = "030000004c050000cc09000000010000";
    // GLFW gamepad buttons A, B, X, Y, LB, RB, BACK, START, GUIDE, LTHUMB, RTHUMB, DPAD up/right/down/left.
    private static final int[] PAD_BUTTONS = {
        0x4000 /* cross */, 0x2000 /* circle */, 0x8000 /* square */, 0x1000 /* triangle */,
        0x0400 /* L1 */, 0x0800 /* R1 */, 0x100000 /* touchpad click */, 0x0008 /* options */,
        0 /* PS button: reserved by the system */, 0x0002 /* L3 */, 0x0004 /* R3 */,
        0x0010 /* up */, 0x0020 /* right */, 0x0040 /* down */, 0x0080 /* left */};

    private static int padButtonsBefore;
    private static boolean padSwallowed;

    private static void pollPad() {
        NativePlatform.nPadRead(pad);
        padPolled = true;
        boolean connected = pad[0] != 0;
        // While the system keyboard is open the player steers it with the pad; the game sees a neutral
        // pad meanwhile (Controlify moved the focus around the book under the dialog), and afterwards
        // until every button is up, so the press that accepted the dialog does not land in the game.
        if (orbis.graphics.TextInput.active()) padSwallowed = true;
        else if (padSwallowed && (!connected || pad[1] == 0)) padSwallowed = false;
        if (padSwallowed && connected) {
            pad[1] = 0;
            pad[2] = pad[3] = pad[4] = pad[5] = 128;
            pad[6] = pad[7] = 0;
        }
        // A press, or a stick pushed past a dead zone, makes the controller the last input
        // (TextInput opens the system keyboard for a focused text field only then).
        if (connected && ((pad[1] & ~padButtonsBefore) != 0 || Math.abs(pad[2] - 128) > 64 || Math.abs(pad[3] - 128) > 64
                || Math.abs(pad[4] - 128) > 64 || Math.abs(pad[5] - 128) > 64))
            orbis.graphics.TextInput.noteGamepad();
        padButtonsBefore = connected ? pad[1] : 0;
        if (connected != padConnected) {
            padConnected = connected;
            if (joystickCallback != null)
                joystickCallback.invoke(GLFW_JOYSTICK_1, connected ? GLFW_CONNECTED : GLFW_DISCONNECTED);
        }
    }

    private static boolean padPresent(int jid) {
        if (!requireInit()) return false;
        if (jid < GLFW_JOYSTICK_1 || jid > GLFW_JOYSTICK_LAST) {
            error(GLFW_INVALID_ENUM, "Invalid joystick ID " + jid);
            return false;
        }
        if (!padPolled && window != NULL) pollPad();
        return jid == GLFW_JOYSTICK_1 && padConnected;
    }

    private static float stick(int raw) {
        return Math.max(-1f, Math.min(1f, (raw - 128) / 127f));
    }

    /** GLFW_JOYSTICK_HAT_BUTTONS, true by default as in GLFW. */
    private static boolean hatButtons = true;
    // The raw joystick is the gamepad's state: its first 11 buttons, then the d-pad as four buttons
    // when hat buttons are on; the same 6 axes; one hat. GLFW keeps these arrays until the next call.
    private static final int PAD_FACE_BUTTONS = 11;
    private static final FloatBuffer JOYSTICK_AXES = org.lwjgl.BufferUtils.createFloatBuffer(6);
    private static final ByteBuffer JOYSTICK_BUTTONS = org.lwjgl.BufferUtils.createByteBuffer(PAD_BUTTONS.length);
    private static final ByteBuffer JOYSTICK_HATS = org.lwjgl.BufferUtils.createByteBuffer(1);

    private static float[] padAxes() {
        return new float[]{stick(pad[2]), stick(pad[3]), stick(pad[4]), stick(pad[5]),
            pad[6] / 127.5f - 1f, pad[7] / 127.5f - 1f};
    }

    static FloatBuffer glfwGetJoystickAxes(int jid) {
        if (!padPresent(jid)) return null;
        float[] axes = padAxes();
        JOYSTICK_AXES.clear();
        JOYSTICK_AXES.put(axes).flip();
        return JOYSTICK_AXES;
    }

    static ByteBuffer glfwGetJoystickButtons(int jid) {
        if (!padPresent(jid)) return null;
        int count = hatButtons ? PAD_BUTTONS.length : PAD_FACE_BUTTONS;
        JOYSTICK_BUTTONS.clear();
        for (int i = 0; i < count; i++)
            JOYSTICK_BUTTONS.put((byte) ((pad[1] & PAD_BUTTONS[i]) != 0 && PAD_BUTTONS[i] != 0 ? GLFW_PRESS : GLFW_RELEASE));
        JOYSTICK_BUTTONS.flip();
        return JOYSTICK_BUTTONS;
    }

    static ByteBuffer glfwGetJoystickHats(int jid) {
        if (!padPresent(jid)) return null;
        int hat = 0;
        if ((pad[1] & 0x0010) != 0) hat |= GLFW_HAT_UP;
        if ((pad[1] & 0x0020) != 0) hat |= GLFW_HAT_RIGHT;
        if ((pad[1] & 0x0040) != 0) hat |= GLFW_HAT_DOWN;
        if ((pad[1] & 0x0080) != 0) hat |= GLFW_HAT_LEFT;
        JOYSTICK_HATS.clear();
        JOYSTICK_HATS.put((byte) hat).flip();
        return JOYSTICK_HATS;
    }

    static boolean glfwJoystickPresent(int jid) {
        return padPresent(jid);
    }

    static boolean glfwJoystickIsGamepad(int jid) {
        return padPresent(jid);
    }

    static String glfwGetJoystickName(int jid) {
        return padPresent(jid) ? PAD_NAME : null;
    }

    static String glfwGetGamepadName(int jid) {
        return padPresent(jid) ? PAD_NAME : null;
    }

    static String glfwGetJoystickGUID(int jid) {
        return padPresent(jid) ? PAD_GUID : null;
    }

    /** The built-in mapping is the only one; new mappings are accepted and ignored. */
    static boolean glfwUpdateGamepadMappings(ByteBuffer string) {
        return requireInit();
    }

    static boolean glfwGetGamepadState(int jid, GLFWGamepadState state) {
        if (!padPresent(jid)) return false;
        long address = state.address();
        for (int i = 0; i < PAD_BUTTONS.length; i++)
            memPutByte(address + GLFWGamepadState.BUTTONS + i,
                (byte) ((pad[1] & PAD_BUTTONS[i]) != 0 && PAD_BUTTONS[i] != 0 ? GLFW_PRESS : GLFW_RELEASE));
        float[] axes = padAxes();
        for (int i = 0; i < axes.length; i++)
            memPutFloat(address + GLFWGamepadState.AXES + 4L * i, axes[i]);
        return true;
    }

    static GLFWJoystickCallback glfwSetJoystickCallback(GLFWJoystickCallbackI cbfun) {
        if (!requireInit()) return null;
        GLFWJoystickCallbackI previous = joystickCallback; joystickCallback = cbfun;
        return previous instanceof GLFWJoystickCallback ? (GLFWJoystickCallback) previous : null;
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
    // ---------------------------------------------------------------- system focus

    /* The console's input focus (F12 between console and desktop) and the system's own state: its UI
     * (the PS button's home screen, notifications opened) over the application, or the application in
     * the background. Either takes focus away, as another window would on a desktop, so Minecraft pauses
     * and Dynamic FPS slows down; the background also counts as iconified. The cursor leaves the window
     * with it: Dynamic FPS tells "unfocused" from "hovered" only by GLFW's cursor enter callback. */
    private static boolean inputFocused = true, systemOverlaid, systemBackground, statusUnavailable;
    private static long statusDue;
    private static final long STATUS_INTERVAL_NANOS = 100_000_000L;
    private static final byte[] systemStatus = new byte[16], lastSystemStatus = new byte[16];

    private static boolean systemOwnsScreen() {
        return systemOverlaid || systemBackground;
    }

    private static boolean focused() {
        return inputFocused && !systemOwnsScreen();
    }

    private static void pollSystem() {
        long now = System.nanoTime();
        if (statusUnavailable || (statusDue != 0 && now - statusDue < 0)) return;
        statusDue = now + STATUS_INTERVAL_NANOS;
        int result;
        try {
            result = NativePlatform.nSystemStatus(systemStatus);
        } catch (LinkageError missing) {
            statusUnavailable = true;
            System.err.println("orbis-glfw: no system status in the platform library: " + missing);
            return;
        }
        if (result != 0) {
            statusUnavailable = true;
            System.err.println("orbis-glfw: sceSystemServiceGetStatus failed: 0x" + Integer.toHexString(result));
            return;
        }
        if (!java.util.Arrays.equals(systemStatus, lastSystemStatus)) {
            System.arraycopy(systemStatus, 0, lastSystemStatus, 0, systemStatus.length);
            StringBuilder hex = new StringBuilder();
            for (byte b : systemStatus) hex.append(String.format("%02x", b & 0xff));
            System.err.println("orbis-glfw: system status " + hex);
        }
        // The system keyboard is system UI too, but one the game asked for: it keeps focus meanwhile.
        boolean overlaid = systemStatus[4] != 0 && !orbis.graphics.TextInput.active(), background = systemStatus[5] != 0;
        if (overlaid == systemOverlaid && background == systemBackground) return;
        boolean wasFocused = focused(), wasIconified = systemBackground, wasOwned = systemOwnsScreen();
        systemOverlaid = overlaid;
        systemBackground = background;
        System.err.println("orbis-glfw: system UI overlaid=" + overlaid + " background=" + background);
        if (wasOwned != systemOwnsScreen() && cursorEnterCallback != null) cursorEnterCallback.invoke(window, !systemOwnsScreen());
        if (wasIconified != background && windowIconifyCallback != null) windowIconifyCallback.invoke(window, background);
        if (wasFocused != focused() && windowFocusCallback != null) windowFocusCallback.invoke(window, focused());
    }

    private static void drain(long waitNanos, boolean wait) {
        if (!dispatching) reconcileSize();
        if (!dispatching && window != NULL) pollPad();
        if (!dispatching && window != NULL) pollSystem();
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
        if (TRACE_INPUT && traced < 400) {
            ++traced;
            System.out.println("orbis-glfw: input type=" + type + " raw=" + raw + " scalar="
                    + event[NativeInput.UNICODE_SCALAR] + " mods=0x" + Integer.toHexString(event[NativeInput.MODIFIERS])
                    + " x=" + event[NativeInput.X] + " y=" + event[NativeInput.Y] + " button=" + event[NativeInput.BUTTON]);
        }
        boolean keyEvent = type == NativeInput.EVENT_KEY_DOWN || type == NativeInput.EVENT_KEY_UP
                || type == NativeInput.EVENT_TEXT;
        if (keyEvent && raw < HID_FIRST_KEY) return;
        if (type == NativeInput.EVENT_KEY_DOWN || type == NativeInput.EVENT_BUTTON_DOWN || type == NativeInput.EVENT_WHEEL)
            orbis.graphics.TextInput.noteKeyboardOrMouse();
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
                if (type == NativeInput.EVENT_FOCUS) {
                    inputFocused = event[NativeInput.FOCUS] == 1;
                    if (windowFocusCallback != null) windowFocusCallback.invoke(window, focused());
                }
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
                pointerSeen = true;
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
