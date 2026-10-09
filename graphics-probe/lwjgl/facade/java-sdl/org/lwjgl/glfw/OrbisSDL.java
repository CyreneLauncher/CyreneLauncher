package org.lwjgl.glfw;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDL_DisplayEvent;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_KeyboardEvent;
import org.lwjgl.sdl.SDL_LogOutputFunctionI;
import org.lwjgl.sdl.SDL_MessageBoxButtonData;
import org.lwjgl.sdl.SDL_MessageBoxData;
import org.lwjgl.sdl.SDL_MouseButtonEvent;
import org.lwjgl.sdl.SDL_MouseMotionEvent;
import org.lwjgl.sdl.SDL_MouseWheelEvent;
import org.lwjgl.sdl.SDL_PixelFormatDetails;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.sdl.SDL_Surface;
import org.lwjgl.sdl.SDL_TextInputEvent;
import org.lwjgl.sdl.SDL_WindowEvent;
import org.lwjgl.system.SharedLibrary;

import orbis.graphics.NativePlatform;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Orbis implementation behind the generated LWJGL 3.4.3 SDL classes (Minecraft 26.3+ creates its
 * window and reads input through SDL3). Every public method here has the exact name and descriptor of
 * an official {@code org.lwjgl.sdl} method; the generator delegates those and makes the rest report as
 * unsupported. It sits on the GLFW facade: the one full-screen window and GL context, the console's
 * keyboard, mouse, focus, clipboard and pointer, turned into SDL events in LWJGL's own event structs.
 *
 * <p>SDL windows are handles over the one real window. Minecraft keeps its GL context on a hidden
 * utility window, makes it current on a hidden test window and then on the game window; all of them
 * share the scanout. Input goes to the newest visible window.
 */
public final class OrbisSDL {
    private OrbisSDL() {}

    // ---------------------------------------------------------------- SDL constants (SDL 3.4)

    private static final long WINDOW_FULLSCREEN = 0x1, WINDOW_OPENGL = 0x2, WINDOW_HIDDEN = 0x8,
            WINDOW_MINIMIZED = 0x40, WINDOW_MOUSE_GRABBED = 0x100, WINDOW_INPUT_FOCUS = 0x200,
            WINDOW_MOUSE_FOCUS = 0x400, WINDOW_MOUSE_RELATIVE_MODE = 0x8000;
    private static final int EVENT_QUIT = 0x100, EVENT_WINDOW_MOVED = 517, EVENT_WINDOW_RESIZED = 518,
            EVENT_WINDOW_PIXEL_SIZE_CHANGED = 519, EVENT_WINDOW_MINIMIZED = 521, EVENT_WINDOW_RESTORED = 523,
            EVENT_WINDOW_MOUSE_ENTER = 524, EVENT_WINDOW_MOUSE_LEAVE = 525, EVENT_WINDOW_FOCUS_GAINED = 526,
            EVENT_WINDOW_FOCUS_LOST = 527, EVENT_WINDOW_CLOSE_REQUESTED = 528, EVENT_WINDOW_ENTER_FULLSCREEN = 535,
            EVENT_WINDOW_LEAVE_FULLSCREEN = 536, EVENT_KEY_DOWN = 768, EVENT_KEY_UP = 769, EVENT_TEXT_INPUT = 771,
            EVENT_MOUSE_MOTION = 1024, EVENT_MOUSE_BUTTON_DOWN = 1025, EVENT_MOUSE_BUTTON_UP = 1026,
            EVENT_MOUSE_WHEEL = 1027;
    private static final int GL_CONTEXT_MAJOR_VERSION = 17, GL_CONTEXT_MINOR_VERSION = 18, GL_CONTEXT_FLAGS = 19,
            GL_CONTEXT_PROFILE_MASK = 20, GL_DEPTH_SIZE = 6, GL_STENCIL_SIZE = 7, GL_DOUBLEBUFFER = 5,
            GL_CONTEXT_PROFILE_CORE = 1, GL_CONTEXT_FORWARD_COMPATIBLE_FLAG = 2;
    private static final int PIXELFORMAT_XRGB8888 = 370546692, PIXELFORMAT_ARGB8888 = 372645892,
            PIXELFORMAT_RGBA8888 = 373694468, PIXELFORMAT_ABGR8888 = 376840196, PIXELFORMAT_XBGR8888 = 374740996;
    private static final int KEYCODE_SCANCODE_MASK = 0x40000000;
    private static final int MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT = 1;
    private static final int DISPLAY = 1;
    private static final int SCANCODE_COUNT = 512;

    // ---------------------------------------------------------------- state

    private static final class Window {
        final int id;
        final long handle;
        long flags;
        String title;
        SDL_DisplayMode fullscreenMode;

        Window(int id, long flags, String title) {
            this.id = id;
            this.handle = 0x5344_4c00_0000_0000L | id; // opaque, never dereferenced
            this.flags = flags;
            this.title = title;
        }
    }

    private static final Map<Long, Window> windows = new LinkedHashMap<>();
    private static int nextWindowId = 1;
    /** The window input goes to: the newest window created without SDL_WINDOW_HIDDEN, else the newest. */
    private static Window inputWindow;
    /** The GLFW facade's one window, created with the first SDL window. */
    private static long glfwWindow;
    private static final long CONTEXT = 0x5344_4c43_0000_0001L;
    private static boolean contextCreated, initialized;

    private static int glMajor = 3, glMinor = 3, glProfile, glFlags, glDepth = 24, glStencil = 8;

    private static String error = "";
    private static final long ticksBase = System.nanoTime();

    private static final ArrayDeque<long[]> queue = new ArrayDeque<>();
    private static final ArrayDeque<String> queuedText = new ArrayDeque<>();
    /** Text of the event last returned; SDL keeps it until the next poll. */
    private static final List<ByteBuffer> heldText = new ArrayList<>();

    private static final ByteBuffer keyboardState = memCalloc(SCANCODE_COUNT);
    private static int buttonMask;
    private static boolean textInput, relativeMode, grabbed, cursorHidden;
    private static double lastX = Double.NaN, lastY = Double.NaN;

    // ---------------------------------------------------------------- errors, init, misc

    private static boolean fail(String message) {
        error = message;
        System.err.println("orbis-sdl: " + message);
        return false;
    }

    public static String SDL_GetError() {
        return error;
    }

    private static final SharedLibrary LIBRARY = new SharedLibrary() {
        @Override public String getName() { return "Orbis SDL facade"; }
        @Override public String getPath() { return null; }
        @Override public long getFunctionAddress(ByteBuffer functionName) { return NULL; }
        @Override public long address() { return NULL; }
        @Override public void free() { }
    };

    /** SDL's native library. The facade has none; Minecraft only checks that it loads. */
    public static SharedLibrary getLibrary() {
        return LIBRARY;
    }

    public static boolean SDL_Init(int flags) {
        if (!OrbisGLFW.glfwInit()) return fail("Orbis graphics platform unavailable");
        initialized = true;
        return true;
    }

    public static void SDL_Quit() {
        for (Window window : new ArrayList<>(windows.values())) SDL_DestroyWindow(window.handle);
        if (initialized) OrbisGLFW.glfwTerminate();
        initialized = false;
        queue.clear();
        queuedText.clear();
        freeHeldText();
    }

    public static boolean SDL_SetAppMetadataProperty(CharSequence name, CharSequence value) { return true; }

    public static boolean SDL_SetHint(CharSequence name, CharSequence value) { return true; }

    public static void SDL_SetLogPriorities(int priority) { }

    public static void SDL_SetLogOutputFunction(SDL_LogOutputFunctionI callback, long userdata) { }

    public static String SDL_GetPlatform() {
        return "Linux";
    }

    public static long SDL_GetTicksNS() {
        return System.nanoTime() - ticksBase;
    }

    public static String SDL_GetCurrentVideoDriver() {
        return initialized ? "orbis" : null;
    }

    public static void SDL_free(IntBuffer mem) {
        if (mem != null) nmemFree(memAddress0(mem));
    }

    public static void SDL_free(PointerBuffer mem) {
        if (mem != null) nmemFree(mem.address0());
    }

    public static boolean SDL_OpenURL(CharSequence url) {
        String link = url == null ? "" : url.toString();
        if (!link.startsWith("http://") && !link.startsWith("https://")) return fail("Unsupported URL " + link);
        return orbis.graphics.WebBrowser.open(link) || fail("The web browser did not open");
    }

    // ---------------------------------------------------------------- clipboard

    public static boolean SDL_SetClipboardText(CharSequence text) {
        OrbisGLFW.glfwSetClipboardString(NULL, text == null ? "" : text);
        return true;
    }

    public static String SDL_GetClipboardText() {
        String text = OrbisGLFW.glfwGetClipboardString(NULL);
        return text == null ? "" : text;
    }

    // ---------------------------------------------------------------- message boxes (logged; no dialog)

    public static boolean SDL_ShowSimpleMessageBox(int flags, CharSequence title, CharSequence message, long window) {
        System.err.println("orbis-sdl: message box \"" + title + "\": " + message);
        return true;
    }

    public static boolean SDL_ShowMessageBox(SDL_MessageBoxData data, IntBuffer buttonid) {
        System.err.println("orbis-sdl: message box \"" + data.titleString() + "\": " + data.messageString());
        int chosen = -1;
        SDL_MessageBoxButtonData.Buffer buttons = data.buttons();
        if (buttons != null) {
            for (int i = 0; i < buttons.remaining() && chosen == -1; i++)
                if ((buttons.get(i).flags() & MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT) != 0) chosen = buttons.get(i).buttonID();
            if (chosen == -1 && buttons.remaining() > 0) chosen = buttons.get(0).buttonID();
        }
        if (buttonid != null) buttonid.put(buttonid.position(), chosen);
        return true;
    }

    // ---------------------------------------------------------------- displays

    private static SDL_DisplayMode displayMode;

    /** The scanout's mode: what the facade reports as GLFW's video mode. */
    private static SDL_DisplayMode mode() {
        if (displayMode == null) {
            GLFWVidMode vidMode = OrbisGLFW.glfwGetVideoMode(OrbisGLFW.MONITOR);
            int refresh = vidMode == null ? 60 : vidMode.refreshRate();
            displayMode = SDL_DisplayMode.create(nmemCalloc(1, SDL_DisplayMode.SIZEOF));
            long a = displayMode.address();
            memPutInt(a + SDL_DisplayMode.DISPLAYID, DISPLAY);
            memPutInt(a + SDL_DisplayMode.FORMAT, PIXELFORMAT_XRGB8888);
            memPutInt(a + SDL_DisplayMode.W, vidMode == null ? 1920 : vidMode.width());
            memPutInt(a + SDL_DisplayMode.H, vidMode == null ? 1080 : vidMode.height());
            memPutFloat(a + SDL_DisplayMode.PIXEL_DENSITY, 1f);
            memPutFloat(a + SDL_DisplayMode.REFRESH_RATE, refresh);
            memPutInt(a + SDL_DisplayMode.REFRESH_RATE_NUMERATOR, refresh);
            memPutInt(a + SDL_DisplayMode.REFRESH_RATE_DENOMINATOR, 1);
        }
        return displayMode;
    }

    private static boolean display(int displayID) {
        return displayID == DISPLAY || fail("Invalid display " + displayID);
    }

    public static IntBuffer SDL_GetDisplays() {
        IntBuffer displays = memAllocInt(2);
        displays.put(0, DISPLAY).put(1, 0).limit(1);
        return displays;
    }

    public static int SDL_GetPrimaryDisplay() {
        return DISPLAY;
    }

    public static String SDL_GetDisplayName(int displayID) {
        return display(displayID) ? "PlayStation 4" : null;
    }

    public static boolean SDL_GetDisplayBounds(int displayID, SDL_Rect rect) {
        if (!display(displayID)) return false;
        rect.x(0).y(0).w(mode().w()).h(mode().h());
        return true;
    }

    public static SDL_DisplayMode SDL_GetCurrentDisplayMode(int displayID) {
        return display(displayID) ? mode() : null;
    }

    public static SDL_DisplayMode SDL_GetDesktopDisplayMode(int displayID) {
        return display(displayID) ? mode() : null;
    }

    public static PointerBuffer SDL_GetFullscreenDisplayModes(int displayID) {
        if (!display(displayID)) return null;
        PointerBuffer modes = memAllocPointer(2);
        modes.put(0, mode().address()).put(1, NULL).limit(1);
        return modes;
    }

    public static boolean SDL_GetClosestFullscreenDisplayMode(int displayID, int w, int h, float refresh_rate,
            boolean include_high_density_modes, SDL_DisplayMode closest) {
        if (!display(displayID)) return false;
        memCopy(mode().address(), closest.address(), SDL_DisplayMode.SIZEOF);
        return true;
    }

    public static int SDL_GetDisplayForWindow(long window) {
        return window(window) != null ? DISPLAY : 0;
    }

    private static final Map<Integer, SDL_PixelFormatDetails> formats = new LinkedHashMap<>();

    public static SDL_PixelFormatDetails SDL_GetPixelFormatDetails(int format) {
        return formats.computeIfAbsent(format, f -> {
            // 32-bit formats only; byte order per SDL's packed 8888 layouts.
            int r, g, b, a;
            switch (f) {
                case PIXELFORMAT_XRGB8888: r = 16; g = 8; b = 0; a = -1; break;
                case PIXELFORMAT_ARGB8888: r = 16; g = 8; b = 0; a = 24; break;
                case PIXELFORMAT_RGBA8888: r = 24; g = 16; b = 8; a = 0; break;
                case PIXELFORMAT_ABGR8888: r = 0; g = 8; b = 16; a = 24; break;
                case PIXELFORMAT_XBGR8888: r = 0; g = 8; b = 16; a = -1; break;
                default: return null;
            }
            long p = nmemCalloc(1, SDL_PixelFormatDetails.SIZEOF);
            memPutInt(p + SDL_PixelFormatDetails.FORMAT, f);
            memPutByte(p + SDL_PixelFormatDetails.BITS_PER_PIXEL, (byte) (a < 0 ? 24 : 32));
            memPutByte(p + SDL_PixelFormatDetails.BYTES_PER_PIXEL, (byte) 4);
            memPutInt(p + SDL_PixelFormatDetails.RMASK, 0xff << r);
            memPutInt(p + SDL_PixelFormatDetails.GMASK, 0xff << g);
            memPutInt(p + SDL_PixelFormatDetails.BMASK, 0xff << b);
            memPutInt(p + SDL_PixelFormatDetails.AMASK, a < 0 ? 0 : 0xff << a);
            memPutByte(p + SDL_PixelFormatDetails.RBITS, (byte) 8);
            memPutByte(p + SDL_PixelFormatDetails.GBITS, (byte) 8);
            memPutByte(p + SDL_PixelFormatDetails.BBITS, (byte) 8);
            memPutByte(p + SDL_PixelFormatDetails.ABITS, (byte) (a < 0 ? 0 : 8));
            memPutByte(p + SDL_PixelFormatDetails.RSHIFT, (byte) r);
            memPutByte(p + SDL_PixelFormatDetails.GSHIFT, (byte) g);
            memPutByte(p + SDL_PixelFormatDetails.BSHIFT, (byte) b);
            memPutByte(p + SDL_PixelFormatDetails.ASHIFT, (byte) Math.max(a, 0));
            return SDL_PixelFormatDetails.create(p);
        });
    }

    // ---------------------------------------------------------------- surfaces (window icons only)

    public static SDL_Surface SDL_CreateSurfaceFrom(int width, int height, int format, ByteBuffer pixels, int pitch) {
        long s = nmemCalloc(1, SDL_Surface.SIZEOF);
        memPutInt(s + SDL_Surface.FORMAT, format);
        memPutInt(s + SDL_Surface.W, width);
        memPutInt(s + SDL_Surface.H, height);
        memPutInt(s + SDL_Surface.PITCH, pitch);
        memPutAddress(s + SDL_Surface.PIXELS, pixels == null ? NULL : memAddress(pixels));
        memPutInt(s + SDL_Surface.REFCOUNT, 1);
        return SDL_Surface.create(s);
    }

    public static void SDL_DestroySurface(SDL_Surface surface) {
        if (surface != null) nmemFree(surface.address());
    }

    public static boolean SDL_AddSurfaceAlternateImage(SDL_Surface surface, SDL_Surface image) { return true; }

    /** A console scanout has no window icon. */
    public static boolean SDL_SetWindowIcon(long window, SDL_Surface icon) {
        return window(window) != null;
    }

    // ---------------------------------------------------------------- windows

    private static Window window(long handle) {
        Window window = windows.get(handle);
        if (window == null) fail("Invalid window");
        return window;
    }

    private static long realWindow() {
        return glfwWindow;
    }

    public static long SDL_CreateWindow(CharSequence title, int w, int h, long flags) {
        if (!initialized) {
            fail("Video subsystem has not been initialized");
            return NULL;
        }
        if (glfwWindow == NULL) {
            OrbisGLFW.glfwDefaultWindowHints();
            OrbisGLFW.glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, glMajor);
            OrbisGLFW.glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, glMinor);
            OrbisGLFW.glfwWindowHint(GLFW_OPENGL_PROFILE,
                    (glProfile & GL_CONTEXT_PROFILE_CORE) != 0 ? GLFW_OPENGL_CORE_PROFILE : GLFW_OPENGL_ANY_PROFILE);
            OrbisGLFW.glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT,
                    (glFlags & GL_CONTEXT_FORWARD_COMPATIBLE_FLAG) != 0 ? GLFW_TRUE : GLFW_FALSE);
            OrbisGLFW.glfwWindowHint(GLFW_DEPTH_BITS, glDepth);
            OrbisGLFW.glfwWindowHint(GLFW_STENCIL_BITS, glStencil);
            long handle = OrbisGLFW.glfwCreateWindow(w, h, title == null ? "" : title, OrbisGLFW.MONITOR, NULL);
            if (handle == NULL) {
                fail("Couldn't create the window: " + NativePlatform.nLastError());
                return NULL;
            }
            glfwWindow = handle;
            installCallbacks(handle);
        }
        Window window = new Window(nextWindowId++, (flags | WINDOW_OPENGL) & ~WINDOW_MINIMIZED,
                title == null ? "" : title.toString());
        windows.put(window.handle, window);
        if ((flags & WINDOW_HIDDEN) == 0 || inputWindow == null || (inputWindow.flags & WINDOW_HIDDEN) != 0) {
            boolean announce = (flags & WINDOW_HIDDEN) == 0;
            inputWindow = window;
            if (announce && OrbisGLFW.glfwGetWindowAttrib(glfwWindow, GLFW_FOCUSED) == GLFW_TRUE) {
                push(EVENT_WINDOW_MOUSE_ENTER);
                push(EVENT_WINDOW_FOCUS_GAINED);
            }
        }
        return window.handle;
    }

    public static void SDL_DestroyWindow(long handle) {
        Window window = windows.remove(handle);
        if (window == null) {
            fail("Invalid window");
            return;
        }
        if (inputWindow == window) {
            inputWindow = null;
            for (Window other : windows.values())
                if (inputWindow == null || (other.flags & WINDOW_HIDDEN) == 0) inputWindow = other;
        }
        if (windows.isEmpty() && glfwWindow != NULL) {
            OrbisGLFW.glfwDestroyWindow(glfwWindow);
            glfwWindow = NULL;
            contextCreated = false;
            textInput = relativeMode = grabbed = false;
            buttonMask = 0;
            memSet(keyboardState, 0);
            lastX = lastY = Double.NaN;
        }
    }

    public static long SDL_GetWindowFlags(long handle) {
        Window window = window(handle);
        if (window == null) return 0;
        long flags = window.flags;
        if (window == inputWindow && glfwWindow != NULL) {
            if (OrbisGLFW.glfwGetWindowAttrib(glfwWindow, GLFW_FOCUSED) == GLFW_TRUE) flags |= WINDOW_INPUT_FOCUS;
            if (OrbisGLFW.glfwGetWindowAttrib(glfwWindow, GLFW_HOVERED) == GLFW_TRUE) flags |= WINDOW_MOUSE_FOCUS;
            if (OrbisGLFW.glfwGetWindowAttrib(glfwWindow, GLFW_ICONIFIED) == GLFW_TRUE) flags |= WINDOW_MINIMIZED;
            if (relativeMode) flags |= WINDOW_MOUSE_RELATIVE_MODE;
            if (grabbed) flags |= WINDOW_MOUSE_GRABBED;
        }
        return flags;
    }

    public static boolean SDL_SetWindowTitle(long handle, CharSequence title) {
        Window window = window(handle);
        if (window == null) return false;
        window.title = title == null ? "" : title.toString();
        return true;
    }

    private static int width() {
        int[] w = new int[1], h = new int[1];
        if (glfwWindow != NULL) OrbisGLFW.glfwGetFramebufferSize(glfwWindow, w, h);
        return glfwWindow != NULL ? w[0] : mode().w();
    }

    private static int height() {
        int[] w = new int[1], h = new int[1];
        if (glfwWindow != NULL) OrbisGLFW.glfwGetFramebufferSize(glfwWindow, w, h);
        return glfwWindow != NULL ? h[0] : mode().h();
    }

    public static boolean SDL_GetWindowSizeInPixels(long handle, IntBuffer w, IntBuffer h) {
        if (window(handle) == null) return false;
        if (w != null) w.put(w.position(), width());
        if (h != null) h.put(h.position(), height());
        return true;
    }

    public static float SDL_GetWindowPixelDensity(long handle) {
        return window(handle) != null ? 1f : 0f;
    }

    public static boolean SDL_GetWindowPosition(long handle, IntBuffer x, IntBuffer y) {
        if (window(handle) == null) return false;
        if (x != null) x.put(x.position(), 0);
        if (y != null) y.put(y.position(), 0);
        return true;
    }

    /** The scanout's size is fixed: a resize is answered with the real size, as a window manager would. */
    public static boolean SDL_SetWindowSize(long handle, int w, int h) {
        Window window = window(handle);
        if (window == null) return false;
        if (window == inputWindow && (w != width() || h != height())) {
            push(EVENT_WINDOW_RESIZED, width(), height());
            push(EVENT_WINDOW_PIXEL_SIZE_CHANGED, width(), height());
        }
        return true;
    }

    public static boolean SDL_SetWindowPosition(long handle, int x, int y) {
        Window window = window(handle);
        if (window == null) return false;
        if (window == inputWindow && (x != 0 || y != 0)) push(EVENT_WINDOW_MOVED, 0, 0);
        return true;
    }

    public static boolean SDL_SetWindowMinimumSize(long handle, int w, int h) { return window(handle) != null; }

    public static boolean SDL_SetWindowMaximumSize(long handle, int w, int h) { return window(handle) != null; }

    public static boolean SDL_SetWindowBordered(long handle, boolean bordered) { return window(handle) != null; }

    public static boolean SDL_RestoreWindow(long handle) { return window(handle) != null; }

    public static boolean SDL_SyncWindow(long handle) { return window(handle) != null; }

    public static boolean SDL_SetWindowFullscreen(long handle, boolean fullscreen) {
        Window window = window(handle);
        if (window == null) return false;
        boolean was = (window.flags & WINDOW_FULLSCREEN) != 0;
        window.flags = fullscreen ? window.flags | WINDOW_FULLSCREEN : window.flags & ~WINDOW_FULLSCREEN;
        if (window == inputWindow && was != fullscreen)
            push(fullscreen ? EVENT_WINDOW_ENTER_FULLSCREEN : EVENT_WINDOW_LEAVE_FULLSCREEN);
        return true;
    }

    public static boolean SDL_SetWindowFullscreenMode(long handle, SDL_DisplayMode mode) {
        Window window = window(handle);
        if (window == null) return false;
        window.fullscreenMode = mode == null ? null : mode();
        return true;
    }

    public static SDL_DisplayMode SDL_GetWindowFullscreenMode(long handle) {
        Window window = window(handle);
        return window == null ? null : window.fullscreenMode;
    }

    public static boolean SDL_SetWindowMouseGrab(long handle, boolean grab) {
        if (window(handle) == null) return false;
        grabbed = grab;
        return true;
    }

    // ---------------------------------------------------------------- OpenGL

    public static boolean SDL_GL_LoadLibrary(CharSequence path) {
        return initialized || fail("Video subsystem has not been initialized");
    }

    public static void SDL_GL_UnloadLibrary() { }

    public static long SDL_GL_GetProcAddress(CharSequence proc) {
        if (!initialized || proc == null) return NULL;
        return NativePlatform.nGetProcAddress(proc.toString());
    }

    public static boolean SDL_GL_SetAttribute(int attr, int value) {
        switch (attr) {
            case GL_CONTEXT_MAJOR_VERSION: glMajor = value; break;
            case GL_CONTEXT_MINOR_VERSION: glMinor = value; break;
            case GL_CONTEXT_PROFILE_MASK: glProfile = value; break;
            case GL_CONTEXT_FLAGS: glFlags = value; break;
            case GL_DEPTH_SIZE: glDepth = value; break;
            case GL_STENCIL_SIZE: glStencil = value; break;
            default: break; // sizes, sRGB and the rest: the scanout's own format
        }
        return true;
    }

    public static boolean SDL_GL_GetAttribute(int attr, IntBuffer value) {
        int result;
        switch (attr) {
            case GL_CONTEXT_MAJOR_VERSION: result = glMajor; break;
            case GL_CONTEXT_MINOR_VERSION: result = glMinor; break;
            case GL_CONTEXT_PROFILE_MASK: result = glProfile; break;
            case GL_CONTEXT_FLAGS: result = glFlags; break;
            case GL_DEPTH_SIZE: result = glDepth; break;
            case GL_STENCIL_SIZE: result = glStencil; break;
            case GL_DOUBLEBUFFER: result = 1; break;
            default: result = 0; break;
        }
        value.put(value.position(), result);
        return true;
    }

    /** One context, created with the window; every SDL window shares it. */
    public static long SDL_GL_CreateContext(long handle) {
        if (window(handle) == null) return NULL;
        if (contextCreated) {
            fail("Only one OpenGL context is supported");
            return NULL;
        }
        OrbisGLFW.glfwMakeContextCurrent(glfwWindow);
        if (OrbisGLFW.glfwGetCurrentContext() != glfwWindow) {
            fail("eglMakeCurrent failed: " + NativePlatform.nLastError());
            return NULL;
        }
        contextCreated = true;
        return CONTEXT;
    }

    public static boolean SDL_GL_DestroyContext(long context) {
        if (context != CONTEXT || !contextCreated) return fail("Invalid context");
        if (OrbisGLFW.glfwGetCurrentContext() != NULL) OrbisGLFW.glfwMakeContextCurrent(NULL);
        contextCreated = false;
        return true;
    }

    public static boolean SDL_GL_MakeCurrent(long handle, long context) {
        if (context == NULL) {
            OrbisGLFW.glfwMakeContextCurrent(NULL);
            return true;
        }
        if (window(handle) == null) return false;
        if (context != CONTEXT || !contextCreated) return fail("Invalid context");
        OrbisGLFW.glfwMakeContextCurrent(glfwWindow);
        return OrbisGLFW.glfwGetCurrentContext() == glfwWindow || fail("eglMakeCurrent failed: " + NativePlatform.nLastError());
    }

    public static boolean SDL_GL_SetSwapInterval(int interval) {
        if (OrbisGLFW.glfwGetCurrentContext() == NULL) return fail("No current OpenGL context");
        OrbisGLFW.glfwSwapInterval(interval);
        return true;
    }

    public static boolean SDL_GL_SwapWindow(long handle) {
        if (window(handle) == null) return false;
        OrbisGLFW.glfwSwapBuffers(glfwWindow);
        return true;
    }

    // ---------------------------------------------------------------- Vulkan: none on the console

    public static boolean SDL_Vulkan_LoadLibrary(CharSequence path) {
        return fail("Vulkan is not supported");
    }

    public static void SDL_Vulkan_UnloadLibrary() { }

    // ---------------------------------------------------------------- mouse

    private static final long FIRST_CURSOR = 0x5344_4c43_5500_0000L;
    /** SDL system cursors to GLFW standard shapes; the console draws one pointer for all of them. */
    private static final int[] GLFW_SHAPE = {
        GLFW_ARROW_CURSOR, GLFW_IBEAM_CURSOR, GLFW_ARROW_CURSOR, GLFW_CROSSHAIR_CURSOR, GLFW_ARROW_CURSOR,
        0x36007 /* NWSE */, 0x36008 /* NESW */, GLFW_HRESIZE_CURSOR, GLFW_VRESIZE_CURSOR, 0x36009 /* all */,
        0x3600A /* not allowed */, GLFW_HAND_CURSOR, 0x36007, GLFW_VRESIZE_CURSOR, 0x36008, GLFW_HRESIZE_CURSOR,
        0x36007, GLFW_VRESIZE_CURSOR, 0x36008, GLFW_HRESIZE_CURSOR};

    public static long SDL_CreateSystemCursor(int id) {
        if (id < 0 || id >= GLFW_SHAPE.length) {
            fail("Invalid system cursor " + id);
            return NULL;
        }
        return FIRST_CURSOR + id;
    }

    public static long SDL_GetDefaultCursor() {
        return FIRST_CURSOR;
    }

    public static boolean SDL_SetCursor(long cursor) {
        if (cursor != NULL && (cursor < FIRST_CURSOR || cursor >= FIRST_CURSOR + GLFW_SHAPE.length))
            return fail("Invalid cursor");
        if (glfwWindow != NULL && cursor != NULL)
            OrbisGLFW.glfwSetCursor(glfwWindow, GLFW_SHAPE[(int) (cursor - FIRST_CURSOR)]);
        return true;
    }

    private static void applyCursorMode() {
        if (glfwWindow == NULL) return;
        OrbisGLFW.glfwSetInputMode(glfwWindow, GLFW_CURSOR,
                relativeMode ? GLFW_CURSOR_DISABLED : cursorHidden ? GLFW_CURSOR_HIDDEN : GLFW_CURSOR_NORMAL);
    }

    public static boolean SDL_SetWindowRelativeMouseMode(long handle, boolean enabled) {
        if (window(handle) == null) return false;
        relativeMode = enabled;
        applyCursorMode();
        return true;
    }

    public static boolean SDL_GetWindowRelativeMouseMode(long handle) {
        return window(handle) != null && relativeMode;
    }

    public static boolean SDL_HideCursor() {
        cursorHidden = true;
        applyCursorMode();
        return true;
    }

    public static boolean SDL_ShowCursor() {
        cursorHidden = false;
        applyCursorMode();
        return true;
    }

    public static boolean SDL_CursorVisible() {
        return !cursorHidden && !relativeMode;
    }

    public static void SDL_WarpMouseInWindow(long handle, float x, float y) {
        if (glfwWindow == NULL || (handle != NULL && window(handle) == null)) return;
        OrbisGLFW.glfwSetCursorPos(glfwWindow, x, y);
        lastX = x;
        lastY = y;
    }

    private static int mouseState(FloatBuffer x, FloatBuffer y) {
        double[] cx = new double[1], cy = new double[1];
        if (glfwWindow != NULL) OrbisGLFW.glfwGetCursorPos(glfwWindow, cx, cy);
        if (x != null) x.put(x.position(), (float) cx[0]);
        if (y != null) y.put(y.position(), (float) cy[0]);
        return buttonMask;
    }

    public static int SDL_GetMouseState(FloatBuffer x, FloatBuffer y) {
        return mouseState(x, y);
    }

    /** The window covers the screen from its origin, so global and window coordinates agree. */
    public static int SDL_GetGlobalMouseState(FloatBuffer x, FloatBuffer y) {
        return mouseState(x, y);
    }

    // ---------------------------------------------------------------- keyboard

    public static ByteBuffer SDL_GetKeyboardState() {
        return keyboardState;
    }

    /** HID modifier bits (LCtrl, LShift, LAlt, LGUI, RCtrl, RShift, RAlt, RGUI) as SDL_Keymod. */
    private static short modState() {
        int hid = OrbisGLFW.hidModifiers(), mod = 0;
        if ((hid & 0x01) != 0) mod |= 0x0040;
        if ((hid & 0x02) != 0) mod |= 0x0001;
        if ((hid & 0x04) != 0) mod |= 0x0100;
        if ((hid & 0x08) != 0) mod |= 0x0400;
        if ((hid & 0x10) != 0) mod |= 0x0080;
        if ((hid & 0x20) != 0) mod |= 0x0002;
        if ((hid & 0x40) != 0) mod |= 0x0200;
        if ((hid & 0x80) != 0) mod |= 0x0800;
        return (short) mod;
    }

    public static short SDL_GetModState() {
        return modState();
    }

    /** US layout: printable keys are their unshifted character, the rest their scancode with SDLK_SCANCODE_MASK. */
    private static final String PRINTABLE_40_TO_56 = "\r\u001b\b\t -=[]\\#;'`,./";

    static int keycode(int scancode) {
        if (scancode >= 4 && scancode <= 29) return 'a' + scancode - 4;
        if (scancode >= 30 && scancode <= 38) return '1' + scancode - 30;
        if (scancode == 39) return '0';
        if (scancode >= 40 && scancode <= 56) return PRINTABLE_40_TO_56.charAt(scancode - 40);
        if (scancode == 76) return 0x7f;
        if (scancode == 100) return '\\';
        return scancode <= 0 ? 0 : scancode | KEYCODE_SCANCODE_MASK;
    }

    public static int SDL_GetKeyFromScancode(int scancode, short modstate, boolean key_event) {
        return scancode < 0 || scancode >= SCANCODE_COUNT ? 0 : keycode(scancode);
    }

    private static final Map<Integer, String> NAMES = new LinkedHashMap<>();

    static {
        String[] specials = {"\r", "Return", "\u001b", "Escape", "\b", "Backspace", "\t", "Tab", " ", "Space",
            "\u007f", "Delete"};
        for (int i = 0; i < specials.length; i += 2) NAMES.put((int) specials[i].charAt(0), specials[i + 1]);
        String[] masked = {"57", "CapsLock", "70", "PrintScreen", "71", "ScrollLock", "72", "Pause", "73", "Insert",
            "74", "Home", "75", "PageUp", "77", "End", "78", "PageDown", "79", "Right", "80", "Left", "81", "Down",
            "82", "Up", "83", "Numlock", "84", "Keypad /", "85", "Keypad *", "86", "Keypad -", "87", "Keypad +",
            "88", "Keypad Enter", "98", "Keypad 0", "99", "Keypad .", "101", "Application", "103", "Keypad =",
            "224", "Left Ctrl", "225", "Left Shift", "226", "Left Alt", "227", "Left GUI", "228", "Right Ctrl",
            "229", "Right Shift", "230", "Right Alt", "231", "Right GUI"};
        for (int i = 0; i < masked.length; i += 2)
            NAMES.put(Integer.parseInt(masked[i]) | KEYCODE_SCANCODE_MASK, masked[i + 1]);
        for (int i = 0; i < 12; i++) NAMES.put((58 + i) | KEYCODE_SCANCODE_MASK, "F" + (i + 1));
        for (int i = 0; i < 12; i++) NAMES.put((104 + i) | KEYCODE_SCANCODE_MASK, "F" + (i + 13));
        for (int i = 0; i < 9; i++) NAMES.put((89 + i) | KEYCODE_SCANCODE_MASK, "Keypad " + (i + 1));
    }

    public static String SDL_GetKeyName(int key) {
        String name = NAMES.get(key);
        if (name != null) return name;
        if (key > 0 && key < KEYCODE_SCANCODE_MASK) return new String(Character.toChars(Character.toUpperCase(key)));
        return "";
    }

    public static boolean SDL_StartTextInput(long handle) {
        if (window(handle) == null) return false;
        textInput = true;
        return true;
    }

    public static boolean SDL_StopTextInput(long handle) {
        if (window(handle) == null) return false;
        textInput = false;
        return true;
    }

    public static boolean SDL_TextInputActive(long handle) {
        return window(handle) != null && textInput;
    }

    public static boolean SDL_SetTextInputArea(long handle, SDL_Rect.Buffer rect, int cursor) {
        return window(handle) != null;
    }

    public static boolean SDL_ClearComposition(long handle) {
        return window(handle) != null;
    }

    // ---------------------------------------------------------------- events

    /* Queued events: type, window id, then the event's own values. Floats travel as raw bits. */

    private static void push(int type, long... values) {
        long[] event = new long[2 + values.length];
        event[0] = type;
        event[1] = inputWindow == null ? 0 : inputWindow.id;
        System.arraycopy(values, 0, event, 2, values.length);
        queue.add(event);
    }

    private static long bits(double value) {
        return Float.floatToRawIntBits((float) value);
    }

    private static void installCallbacks(long handle) {
        OrbisGLFW.glfwSetKeyCallback(handle, (window, key, scancode, action, mods) -> {
            if (scancode <= 0 || scancode >= SCANCODE_COUNT) return;
            boolean down = action != GLFW_RELEASE;
            keyboardState.put(scancode, (byte) (down ? 1 : 0));
            push(down ? EVENT_KEY_DOWN : EVENT_KEY_UP, scancode, keycode(scancode), modState() & 0xffff,
                    action == GLFW_REPEAT ? 1 : 0);
        });
        OrbisGLFW.glfwSetCharCallback(handle, (window, codepoint) -> {
            if (!textInput) return;
            push(EVENT_TEXT_INPUT);
            queuedText.add(new String(Character.toChars(codepoint)));
        });
        OrbisGLFW.glfwSetMouseButtonCallback(handle, (window, button, action, mods) -> {
            int sdlButton = button == GLFW_MOUSE_BUTTON_LEFT ? 1 : button == GLFW_MOUSE_BUTTON_RIGHT ? 3
                    : button == GLFW_MOUSE_BUTTON_MIDDLE ? 2 : button + 1;
            int mask = 1 << (sdlButton - 1);
            boolean down = action == GLFW_PRESS;
            buttonMask = down ? buttonMask | mask : buttonMask & ~mask;
            double[] x = new double[1], y = new double[1];
            OrbisGLFW.glfwGetCursorPos(handle, x, y);
            push(down ? EVENT_MOUSE_BUTTON_DOWN : EVENT_MOUSE_BUTTON_UP, sdlButton, down ? 1 : 0, bits(x[0]), bits(y[0]));
        });
        OrbisGLFW.glfwSetCursorPosCallback(handle, (window, x, y) -> {
            double dx = Double.isNaN(lastX) ? 0 : x - lastX, dy = Double.isNaN(lastY) ? 0 : y - lastY;
            lastX = x;
            lastY = y;
            push(EVENT_MOUSE_MOTION, buttonMask, bits(x), bits(y), bits(dx), bits(dy));
        });
        OrbisGLFW.glfwSetScrollCallback(handle, (window, x, y) -> {
            double[] cx = new double[1], cy = new double[1];
            OrbisGLFW.glfwGetCursorPos(handle, cx, cy);
            push(EVENT_MOUSE_WHEEL, bits(x), bits(y), bits(cx[0]), bits(cy[0]), (long) x, (long) y);
        });
        OrbisGLFW.glfwSetWindowFocusCallback(handle, (window, focused) -> {
            if (!focused) {
                buttonMask = 0;
                memSet(keyboardState, 0);
            }
            push(focused ? EVENT_WINDOW_FOCUS_GAINED : EVENT_WINDOW_FOCUS_LOST);
        });
        OrbisGLFW.glfwSetCursorEnterCallback(handle, (window, entered) ->
                push(entered ? EVENT_WINDOW_MOUSE_ENTER : EVENT_WINDOW_MOUSE_LEAVE));
        OrbisGLFW.glfwSetWindowIconifyCallback(handle, (window, iconified) ->
                push(iconified ? EVENT_WINDOW_MINIMIZED : EVENT_WINDOW_RESTORED));
        OrbisGLFW.glfwSetFramebufferSizeCallback(handle, (window, w, h) -> {
            push(EVENT_WINDOW_RESIZED, w, h);
            push(EVENT_WINDOW_PIXEL_SIZE_CHANGED, w, h);
        });
        OrbisGLFW.glfwSetWindowCloseCallback(handle, window -> push(EVENT_WINDOW_CLOSE_REQUESTED));
    }

    private static void freeHeldText() {
        for (ByteBuffer text : heldText) memFree(text);
        heldText.clear();
    }

    public static void SDL_PumpEvents() {
        if (!initialized) return;
        OrbisGLFW.glfwPollEvents();
        if (glfwWindow != NULL && OrbisGLFW.glfwWindowShouldClose(glfwWindow)) {
            OrbisGLFW.glfwSetWindowShouldClose(glfwWindow, false);
            push(EVENT_QUIT);
        }
    }

    public static void SDL_FlushEvents(int minType, int maxType) {
        java.util.Iterator<long[]> events = queue.iterator();
        java.util.Iterator<String> texts = queuedText.iterator();
        while (events.hasNext()) {
            long[] event = events.next();
            boolean text = event[0] == EVENT_TEXT_INPUT;
            String owned = text ? texts.next() : null;
            if (event[0] >= minType && event[0] <= maxType) {
                events.remove();
                if (owned != null) texts.remove();
            }
        }
    }

    public static boolean SDL_PollEvent(SDL_Event event) {
        freeHeldText();
        if (queue.isEmpty()) SDL_PumpEvents();
        long[] next = queue.poll();
        if (next == null) return false;
        if (event == null) {
            // SDL_PollEvent(NULL) asks whether events are pending without taking one.
            queue.addFirst(next);
            return true;
        }
        write(next, event.address());
        return true;
    }

    private static void write(long[] e, long a) {
        int type = (int) e[0];
        memSet(a, 0, SDL_Event.SIZEOF);
        memPutInt(a + SDL_KeyboardEvent.TYPE, type);
        memPutLong(a + SDL_KeyboardEvent.TIMESTAMP, SDL_GetTicksNS());
        int windowID = (int) e[1];
        switch (type) {
            case EVENT_KEY_DOWN: case EVENT_KEY_UP:
                memPutInt(a + SDL_KeyboardEvent.WINDOWID, windowID);
                memPutInt(a + SDL_KeyboardEvent.SCANCODE, (int) e[2]);
                memPutInt(a + SDL_KeyboardEvent.KEY, (int) e[3]);
                memPutShort(a + SDL_KeyboardEvent.MOD, (short) e[4]);
                memPutShort(a + SDL_KeyboardEvent.RAW, (short) e[2]);
                memPutByte(a + SDL_KeyboardEvent.DOWN, (byte) (type == EVENT_KEY_DOWN ? 1 : 0));
                memPutByte(a + SDL_KeyboardEvent.REPEAT, (byte) e[5]);
                break;
            case EVENT_TEXT_INPUT:
                ByteBuffer text = memUTF8(queuedText.poll());
                heldText.add(text);
                memPutInt(a + SDL_TextInputEvent.WINDOWID, windowID);
                memPutAddress(a + SDL_TextInputEvent.TEXT, memAddress(text));
                break;
            case EVENT_MOUSE_MOTION:
                memPutInt(a + SDL_MouseMotionEvent.WINDOWID, windowID);
                memPutInt(a + SDL_MouseMotionEvent.STATE, (int) e[2]);
                memPutInt(a + SDL_MouseMotionEvent.X, (int) e[3]);
                memPutInt(a + SDL_MouseMotionEvent.Y, (int) e[4]);
                memPutInt(a + SDL_MouseMotionEvent.XREL, (int) e[5]);
                memPutInt(a + SDL_MouseMotionEvent.YREL, (int) e[6]);
                break;
            case EVENT_MOUSE_BUTTON_DOWN: case EVENT_MOUSE_BUTTON_UP:
                memPutInt(a + SDL_MouseButtonEvent.WINDOWID, windowID);
                memPutByte(a + SDL_MouseButtonEvent.BUTTON, (byte) e[2]);
                memPutByte(a + SDL_MouseButtonEvent.DOWN, (byte) e[3]);
                memPutByte(a + SDL_MouseButtonEvent.CLICKS, (byte) 1);
                memPutInt(a + SDL_MouseButtonEvent.X, (int) e[4]);
                memPutInt(a + SDL_MouseButtonEvent.Y, (int) e[5]);
                break;
            case EVENT_MOUSE_WHEEL:
                memPutInt(a + SDL_MouseWheelEvent.WINDOWID, windowID);
                memPutInt(a + SDL_MouseWheelEvent.X, (int) e[2]);
                memPutInt(a + SDL_MouseWheelEvent.Y, (int) e[3]);
                memPutInt(a + SDL_MouseWheelEvent.MOUSE_X, (int) e[4]);
                memPutInt(a + SDL_MouseWheelEvent.MOUSE_Y, (int) e[5]);
                memPutInt(a + SDL_MouseWheelEvent.INTEGER_X, (int) e[6]);
                memPutInt(a + SDL_MouseWheelEvent.INTEGER_Y, (int) e[7]);
                break;
            case EVENT_QUIT:
                break;
            default:
                if (type >= 337 && type <= 344) { // SDL_EVENT_DISPLAY_FIRST to _LAST
                    memPutInt(a + SDL_DisplayEvent.DISPLAYID, DISPLAY);
                } else {
                    memPutInt(a + SDL_WindowEvent.WINDOWID, windowID);
                    if (e.length > 2) memPutInt(a + SDL_WindowEvent.DATA1, (int) e[2]);
                    if (e.length > 3) memPutInt(a + SDL_WindowEvent.DATA2, (int) e[3]);
                }
        }
    }

    /**
     * Every event belongs to the window input goes to. Minecraft reads this after it has freed the
     * event (from tasks it queues), so the event's memory is never read here.
     */
    public static long SDL_GetWindowFromEvent(SDL_Event event) {
        return inputWindow == null ? NULL : inputWindow.handle;
    }
}
