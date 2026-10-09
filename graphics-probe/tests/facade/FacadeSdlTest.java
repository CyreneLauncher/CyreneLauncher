import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.sdl.SDL;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.SharedLibrary;

import orbis.graphics.NativePlatform;
import runner.gpu.NativeInput;

import static org.lwjgl.sdl.SDLEvents.*;
import static org.lwjgl.sdl.SDLVideo.*;

/** The SDL facade driven the way Minecraft 26.3 drives SDL (GlBackend, GlDevice, Window, SDLEventHandler). */
public final class FacadeSdlTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static SDL_Event poll(SDL_Event event, int type) {
        while (SDL_PollEvent(event)) if (event.type() == type) return event;
        throw new AssertionError("no event " + type);
    }

    public static void main(String[] args) {
        check(SDL.getLibrary() != null, "SDL library");
        check(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO | SDLInit.SDL_INIT_EVENTS), "SDL_Init");

        // GlBackend.loadLibrary: GL's provider is a SharedLibrary and agrees with SDL on glGetError.
        check(GL.getFunctionProvider() instanceof SharedLibrary, "GL provider is a SharedLibrary");
        check(SDL_GL_LoadLibrary(((SharedLibrary) GL.getFunctionProvider()).getPath()), "SDL_GL_LoadLibrary");
        check(GL.getFunctionProvider().getFunctionAddress("glGetError") == SDL_GL_GetProcAddress("glGetError"),
                "glGetError agrees");

        IntBuffer interval = IntBuffer.allocate(2);
        interval.put(0, 77).put(1, 88).position(1);
        check(!SDL_GL_GetSwapInterval(interval) && interval.get(1) == 88, "swap query without context leaves output intact");

        // GlDevice: context on a hidden utility window, a hidden test window, then the game window.
        long hiddenFlags = 2147614728L;
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 3);
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_CORE);
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_FLAGS, SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG);
        long utility = SDL_CreateWindow("utility", 320, 480, 2L | hiddenFlags);
        check(utility != 0, "utility window");
        check(NativePlatform.lastContext[0] == 3 && NativePlatform.lastContext[1] == 3, "3.3 context requested");
        check(SDL_GL_GetCurrentContext() == 0, "no SDL context before creation");
        long context = SDL_GL_CreateContext(utility);
        check(context != 0 && SDL_GL_MakeCurrent(utility, context), "context current");
        check(SDL_GL_GetCurrentContext() == context, "current SDL handle matches created context");
        Thread other = new Thread(() -> check(SDL_GL_GetCurrentContext() == 0, "context is thread local"));
        other.setUncaughtExceptionHandler((thread, failure) -> { failure.printStackTrace(); System.exit(1); });
        other.start();
        try { other.join(); } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        check(SDL_GL_MakeCurrent(utility, 0), "detach context");
        check(SDL_GL_GetCurrentContext() == 0, "no current context after detach");
        check(SDL_GL_MakeCurrent(utility, context), "reattach context");
        check(SDL_GL_CreateContext(utility) == 0, "a second context is refused");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer major = stack.mallocInt(1), minor = stack.mallocInt(1);
            check(SDL_GL_GetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, major) && major.get(0) == 3, "major");
            check(SDL_GL_GetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, minor) && minor.get(0) == 3, "minor");
        }
        long test = SDL_CreateWindow("test", 320, 480, 2L | hiddenFlags);
        check(test != 0 && test != utility, "test window");
        SDL_DestroyWindow(test);
        check(NativePlatform.destroyed == 0, "the real window outlives the test window");

        long game = SDL_CreateWindow("Minecraft", 854, 480, 2L | SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIGH_PIXEL_DENSITY);
        check(game != 0, "game window");
        check(SDL_GL_MakeCurrent(game, context), "context on the game window");
        check(SDL_GL_SetSwapInterval(1), "swap interval");
        check(SDL_GL_GetSwapInterval(interval) && interval.get(1) == 1 && interval.get(0) == 77,
                "swap query uses buffer position and reports fixed PS4 interval");
        int savedInterval = interval.get(1);
        check(!SDL_GL_SetSwapInterval(-1), "adaptive VSync is unavailable on the fixed PS4 scanout");
        check(SDL_GL_GetSwapInterval(interval) && interval.get(1) == savedInterval, "adaptive probe preserves interval");
        check(SDL_GL_SetSwapInterval(savedInterval), "Sodium Extra restores saved interval");
        check(SDL_GL_SetSwapInterval(0) && SDL_GL_GetSwapInterval(interval) && interval.get(1) == 1,
                "EGL clamps VSync off to the PS4 fixed interval");
        NativePlatform.swapIntervalSuccess = false;
        check(!SDL_GL_SetSwapInterval(1), "native swap failure reaches SDL caller");
        NativePlatform.swapIntervalSuccess = true;
        int swaps = NativePlatform.swaps;
        check(SDL_GL_SwapWindow(game) && NativePlatform.swaps == swaps + 1, "swap");

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            check(SDL_GetWindowSizeInPixels(game, w, h) && w.get(0) == 1920 && h.get(0) == 1080, "pixel size");
            check(SDL_GetWindowPixelDensity(game) == 1f, "density");
        }
        IntBuffer displays = SDL_GetDisplays();
        check(displays != null && displays.remaining() == 1 && displays.get(0) == SDL_GetPrimaryDisplay(), "one display");
        SDLStdinc.SDL_free(displays);
        SDL_DisplayMode mode = SDL_GetCurrentDisplayMode(SDL_GetPrimaryDisplay());
        check(mode != null && mode.w() > 0 && mode.h() > 0, "display mode");
        PointerBuffer modes = SDL_GetFullscreenDisplayModes(SDL_GetPrimaryDisplay());
        check(modes.remaining() == 1 && modes.get(0) == mode.address(), "fullscreen modes");
        SDLStdinc.SDL_free(modes);
        check(SDL_SetWindowFullscreen(game, true) && (SDL_GetWindowFlags(game) & SDL_WINDOW_FULLSCREEN) != 0, "fullscreen");

        SDL_Event event = SDL_Event.malloc();
        // The new visible window is announced as focused and hovered.
        poll(event, SDL_EVENT_WINDOW_FOCUS_GAINED);
        check(SDL_GetWindowFromEvent(event) == game, "events belong to the game window");
        while (SDL_PollEvent(event)) { }

        // A key: SDL scancode (HID usage), US keycode, then text only while text input is on.
        NativeInput.push(NativeInput.EVENT_KEY_DOWN, 4, 0, 0, 0, 0, 0, 0);
        NativeInput.push(NativeInput.EVENT_TEXT, 4, 'a', 0, 0, 0, 0, 0);
        poll(event, SDL_EVENT_KEY_DOWN);
        check(event.key().scancode() == 4 && event.key().key() == 'a' && !event.key().repeat(), "key a");
        check(SDLKeyboard.SDL_GetKeyboardState().get(4) == 1, "keyboard state");
        check(!SDL_PollEvent(event), "no text without text input");
        check(SDLKeyboard.SDL_StartTextInput(game), "start text input");
        NativeInput.push(NativeInput.EVENT_TEXT, 4, 'a', 0, 0, 0, 0, 0);
        poll(event, SDL_EVENT_KEY_DOWN);
        check(event.key().repeat(), "held key repeats");
        poll(event, SDL_EVENT_TEXT_INPUT);
        check("a".equals(event.text().textString()), "text input");
        NativeInput.push(NativeInput.EVENT_KEY_UP, 4, 0, 0, 0, 0, 0, 0);
        poll(event, SDL_EVENT_KEY_UP);
        check(SDLKeyboard.SDL_GetKeyboardState().get(4) == 0, "key released");
        NativeInput.push(NativeInput.EVENT_MODIFIERS, 0, 0, 0x02, 0, 0, 0, 0);
        poll(event, SDL_EVENT_KEY_DOWN);
        check(event.key().scancode() == 225 && (SDLKeyboard.SDL_GetModState() & 0x0001) != 0, "left shift");
        check("Left Shift".equals(SDLKeyboard.SDL_GetKeyName(event.key().key())), "key name");
        check("A".equals(SDLKeyboard.SDL_GetKeyName('a')) && "Return".equals(SDLKeyboard.SDL_GetKeyName('\r')), "names");

        // Mouse: motion in window pixels, SDL button numbers, wheel.
        NativeInput.push(NativeInput.EVENT_MOTION, 0, 0, 0, 640, 360, 0, 0);
        poll(event, SDL_EVENT_MOUSE_MOTION);
        check(event.motion().x() == 960f && event.motion().y() == 540f, "motion scaled to the window");
        NativeInput.push(NativeInput.EVENT_BUTTON_DOWN, 0, 0, 0, 0, 0, 1, 0);
        poll(event, SDL_EVENT_MOUSE_BUTTON_DOWN);
        check(event.button().button() == SDLMouse.SDL_BUTTON_RIGHT, "right button");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer x = stack.mallocFloat(1), y = stack.mallocFloat(1);
            check(SDLMouse.SDL_GetMouseState(x, y) == SDLMouse.SDL_BUTTON_RMASK && x.get(0) == 960f, "mouse state");
        }
        NativeInput.push(NativeInput.EVENT_WHEEL, 0, 0, 0, 0, 0, 0, -1);
        poll(event, SDL_EVENT_MOUSE_WHEEL);
        check(event.wheel().y() == -1f, "wheel");

        // Relative mode (the grabbed mouse): deltas keep coming.
        check(SDLMouse.SDL_SetWindowRelativeMouseMode(game, true), "relative mode");
        check((SDL_GetWindowFlags(game) & SDL_WINDOW_MOUSE_RELATIVE_MODE) != 0, "relative flag");
        while (SDL_PollEvent(event)) { }
        NativeInput.push(NativeInput.EVENT_MOTION, 0, 0, 0, 650, 360, 0, 0);
        poll(event, SDL_EVENT_MOUSE_MOTION);
        check(event.motion().xrel() == 15f, "relative delta " + event.motion().xrel());

        // Flushing input drops queued key events.
        NativeInput.push(NativeInput.EVENT_KEY_DOWN, 5, 0, 0, 0, 0, 0, 0);
        SDL_PumpEvents();
        SDL_FlushEvents(SDL_EVENT_KEY_DOWN, SDL_EVENT_DROP_COMPLETE);
        check(!SDL_PollEvent(event), "flushed");
        event.free();

        check(SDL_GL_DestroyContext(context), "context destroyed");
        check(SDL_GL_GetCurrentContext() == 0, "no current context after destruction");
        SDL_DestroyWindow(game);
        SDL_DestroyWindow(utility);
        check(NativePlatform.destroyed == 1, "the real window goes with the last SDL window");
        SDLInit.SDL_Quit();
        System.out.println("SDL facade behavior passed");
    }
}
