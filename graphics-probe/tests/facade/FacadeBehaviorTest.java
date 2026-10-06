import java.util.ArrayList;
import java.util.List;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.Platform;

import orbis.graphics.NativePlatform;
import runner.gpu.NativeInput;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.memUTF8;

/** Drives the Orbis GLFW facade on the host with stand-in platform and input bridges. */
public final class FacadeBehaviorTest {
    private static final List<String> events = new ArrayList<>();
    private static final List<Integer> errors = new ArrayList<>();

    public static void main(String[] args) {
        check(Platform.get() == Platform.LINUX, "Platform shim keeps Linux working");
        GLFWErrorCallback previous = glfwSetErrorCallback((code, description) ->
                errors.add(code));
        check(previous == null, "no previous error callback");
        // Minecraft 1.14: replace a lambda error callback and free the one returned.
        GLFWErrorCallback replaced = glfwSetErrorCallback((code, description) -> errors.add(code));
        check(replaced != null, "a previous lambda callback comes back as a callback object");
        replaced.free();
        replaced.invoke(0, 0L);

        check(glfwInit(), "glfwInit");
        check(NativePlatform.mesaLogFile != null && NativePlatform.mesaLogFile.endsWith("/mesa.log")
                && NativePlatform.mesaLogFile.startsWith("/"), "Mesa log defaults to an absolute mesa.log");
        check(!NativeInput.started, "input waits for the window (VideoOut before keyboard)");
        check(GL.getFunctionProvider() != null, "GL function provider installed without loading libGL");
        check(GL.getFunctionProvider().getFunctionAddress("glClear") == 0x1000 + 7, "GL lookup goes through EGL");
        check(NativePlatform.procNames.contains("glClear"), "provider asked the platform");

        long monitor = glfwGetPrimaryMonitor();
        PointerBuffer monitors = glfwGetMonitors();
        check(monitor != 0 && monitors.get(0) == monitor && monitors.remaining() == 1, "one monitor");
        GLFWVidMode initialMode = glfwGetVideoMode(monitor);
        check(initialMode.width() == NativeInput.WIDTH && initialMode.height() == NativeInput.HEIGHT,
                "pre-window monitor dimensions are the input scanout fallback");
        int[] mx = {-1}, my = {-1};
        glfwGetMonitorPos(monitor, mx, my);
        check(mx[0] == 0 && my[0] == 0, "monitor at origin");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);
        glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_NATIVE_CONTEXT_API);
        glfwWindowHint(GLFW_DECORATED, GLFW_FALSE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE);
        check(glfwGetError(null) == GLFW_NO_ERROR, "documented decoration and native context hints accepted");
        glfwWindowHint(0x7ffffffe, 1);
        check(glfwGetError(null) == GLFW_INVALID_ENUM, "unknown hint rejected");
        glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_EGL_CONTEXT_API);
        check(glfwGetError(null) == GLFW_INVALID_VALUE, "unsupported EGL context hint rejected");
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
        check(glfwGetError(null) == GLFW_INVALID_VALUE, "debug context request rejected");
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_FALSE);
        check(glfwGetError(null) == GLFW_NO_ERROR, "default non-debug context accepted");
        glfwWindowHint(GLFW_CONTEXT_NO_ERROR, GLFW_TRUE);
        check(glfwGetError(null) == GLFW_NO_ERROR, "no-error context request accepted as a normal context");
        glfwWindowHint(GLFW_CONTEXT_NO_ERROR, GLFW_FALSE);
        glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_ES_API);
        check(glfwGetError(null) == GLFW_INVALID_VALUE, "unsupported ES API hint rejected");
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        long window = glfwCreateWindow(854, 480, "Minecraft* 1.18.2", 0, 0);
        check(window != 0, "window created");
        check(NativeInput.started, "input started with the window");
        int[] context = NativePlatform.lastContext;
        check(context[0] == 3 && context[1] == 2 && context[2] == 1 && context[3] == 1, "context hints forwarded");
        check(glfwCreateWindow(1, 1, "second", 0, 0) == 0 && lastError() == GLFW_PLATFORM_ERROR, "one window only");

        int[] w = new int[1], h = new int[1];
        glfwGetFramebufferSize(window, w, h);
        check(w[0] == 1920 && h[0] == 1080, "framebuffer is the scanout size");
        GLFWVidMode mode = glfwGetVideoMode(monitor);
        check(mode.width() == 1920 && mode.height() == 1080 && mode.refreshRate() == 60, "video mode follows scanout");
        check(glfwGetVideoModes(monitor).remaining() == 1, "one video mode");

        // Minecraft asked for 854x480 and scales cursor positions by the size it believes; the
        // fixed scanout must be reported through the size callbacks on the next poll.
        int[] reported = new int[4];
        glfwSetWindowSizeCallback(window, (win, width, height) -> { reported[0] = width; reported[1] = height; });
        glfwSetFramebufferSizeCallback(window, (win, width, height) -> { reported[2] = width; reported[3] = height; });
        glfwPollEvents();
        check(reported[0] == 1920 && reported[1] == 1080 && reported[2] == 1920 && reported[3] == 1080,
                "requested size reconciled with the scanout through size callbacks");
        reported[0] = 0;
        glfwPollEvents();
        check(reported[0] == 0, "size reported once");
        glfwSetWindowMonitor(window, 0, 0, 0, 854, 480, GLFW_DONT_CARE);
        glfwPollEvents();
        check(reported[0] == 1920 && reported[1] == 1080, "windowed request reconciled again");
        glfwSetWindowSizeCallback(window, null);
        glfwSetFramebufferSizeCallback(window, null);
        check(glfwGetWindowMonitor(window) == 0, "windowed until set");
        glfwSetWindowMonitor(window, monitor, 0, 0, 1920, 1080, 60);
        check(glfwGetWindowMonitor(window) == monitor, "full-screen flag");
        glfwSetWindowMonitor(window, 0, 20, 20, 854, 480, 60);
        check(glfwGetWindowMonitor(window) == 0, "windowed flag restored on fixed scanout");
        glfwGetFramebufferSize(window, w, h);
        check(w[0] == 1920 && h[0] == 1080, "fixed scanout after monitor transition");
        glfwSetWindowIcon(window, null);
        try (GLFWImage.Buffer ignoredIcon = GLFWImage.calloc(1)) {
            glfwSetWindowIcon(window, ignoredIcon);
        }
        glfwSetWindowTitle(window, "title");

        glfwMakeContextCurrent(window);
        check(glfwGetCurrentContext() == window && NativePlatform.current == window, "context current");
        glfwSwapInterval(1);
        glfwSwapBuffers(window);
        check(NativePlatform.swaps == 1, "swap reaches EGL");

        GLFWKeyCallback firstKeyCallback = GLFWKeyCallback.create((win, key, scancode, action, mods) -> {});
        GLFWKeyCallback secondKeyCallback = GLFWKeyCallback.create((win, key, scancode, action, mods) -> {});
        check(glfwSetKeyCallback(window, firstKeyCallback) == null, "first key callback has no predecessor");
        check(glfwSetKeyCallback(window, secondKeyCallback) == firstKeyCallback,
                "callback replacement returns previous callback object");
        firstKeyCallback.free();
        check(glfwSetKeyCallback(window, null) == secondKeyCallback, "callback can be cleared and freed");
        secondKeyCallback.free();
        glfwSetKeyCallback(window, (win, key, scancode, action, mods) ->
                events.add("key " + key + " " + scancode + " " + action + " " + mods));
        glfwSetCharModsCallback(window, (win, codepoint, mods) -> events.add("char " + codepoint + " " + mods));
        glfwSetMouseButtonCallback(window, (win, button, action, mods) ->
                events.add("button " + button + " " + action + " " + mods));
        glfwSetCursorPosCallback(window, (win, x, y) -> events.add("cursor " + (int) x + " " + (int) y));
        glfwSetScrollCallback(window, (win, x, y) -> events.add("scroll " + (int) x + " " + (int) y));

        // Reserved HID usages (0 = no event, 1-3 = keyboard errors) are not keys.
        NativeInput.push(NativeInput.EVENT_KEY_DOWN, 0, 0, 0, 0, 0, 0, 0);
        NativeInput.push(NativeInput.EVENT_KEY_DOWN, 1, 0, 0, 0, 0, 0, 0);
        glfwPollEvents();
        expect();

        // A: press, initial text, a repeat, release.
        NativeInput.push(NativeInput.EVENT_KEY_DOWN, 4, 0, 0, 0, 0, 0, 0);
        NativeInput.push(NativeInput.EVENT_TEXT, 4, 'a', 0, 0, 0, 0, 0);
        glfwPollEvents();
        expect("key " + GLFW_KEY_A + " 4 " + GLFW_PRESS + " 0", "char 97 0");
        check(glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS, "A held");
        NativeInput.push(NativeInput.EVENT_TEXT, 4, 'a', 0, 0, 0, 0, 0);
        NativeInput.push(NativeInput.EVENT_KEY_UP, 4, 0, 0, 0, 0, 0, 0);
        glfwPollEvents();
        expect("key " + GLFW_KEY_A + " 4 " + GLFW_REPEAT + " 0", "char 97 0",
                "key " + GLFW_KEY_A + " 4 " + GLFW_RELEASE + " 0");
        check(glfwGetKey(window, GLFW_KEY_A) == GLFW_RELEASE, "A released");

        // Left shift through the modifier byte, then Backspace repeat without a character.
        NativeInput.push(NativeInput.EVENT_MODIFIERS, 0, 0, 0x02, 0, 0, 0, 0);
        NativeInput.push(NativeInput.EVENT_KEY_DOWN, 42, 0, 0x02, 0, 0, 0, 0);
        NativeInput.push(NativeInput.EVENT_TEXT, 42, 8, 0x02, 0, 0, 0, 0);
        glfwPollEvents();
        expect("key " + GLFW_KEY_LEFT_SHIFT + " 225 " + GLFW_PRESS + " " + GLFW_MOD_SHIFT,
                "key " + GLFW_KEY_BACKSPACE + " 42 " + GLFW_PRESS + " " + GLFW_MOD_SHIFT,
                "key " + GLFW_KEY_BACKSPACE + " 42 " + GLFW_REPEAT + " " + GLFW_MOD_SHIFT);

        // Pointer: input space 1280x720 scales to the 1920x1080 framebuffer.
        NativeInput.push(NativeInput.EVENT_MOTION, 0, 0, 0x02, 640, 360, 0, 0);
        NativeInput.push(NativeInput.EVENT_BUTTON_DOWN, 0, 0, 0x02, 640, 360, 0, 0);
        NativeInput.push(NativeInput.EVENT_WHEEL, 0, 0, 0x02, 640, 360, 0, -1);
        glfwPollEvents();
        expect("cursor 960 540", "button 0 " + GLFW_PRESS + " " + GLFW_MOD_SHIFT, "scroll 0 -1");
        check(glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS, "left button held");

        // Disabled cursor: unbounded relative motion, re-centred away from the clamp.
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        check(glfwGetInputMode(window, GLFW_CURSOR) == GLFW_CURSOR_DISABLED, "cursor disabled");
        int[] centre = NativeInput.pointers.get(NativeInput.pointers.size() - 1);
        check(centre[0] == 640 && centre[1] == 360, "pointer centred on disable");
        int warps = NativeInput.pointers.size();
        NativeInput.push(NativeInput.EVENT_MOTION, 0, 0, 0x02, 1100, 360, 0, 0);
        glfwPollEvents();
        expect("cursor 1650 540");
        check(NativeInput.pointers.size() == warps + 1, "re-centred after leaving the middle half");
        NativeInput.push(NativeInput.EVENT_MOTION, 0, 0, 0x02, 1100, 360, 0, 0);
        glfwPollEvents();
        expect("cursor 2340 540");

        // Focus change releases held input with callbacks.
        NativeInput.push(NativeInput.EVENT_FOCUS, 0, 0, 0, 0, 0, 0, 0);
        glfwPollEvents();
        check(events.contains("key " + GLFW_KEY_BACKSPACE + " 42 " + GLFW_RELEASE + " " + GLFW_MOD_SHIFT),
                "held key released with the modifiers still held");
        check(events.contains("key " + GLFW_KEY_LEFT_SHIFT + " 225 " + GLFW_RELEASE + " " + GLFW_MOD_SHIFT),
                "modifier released, reporting the state held until then");
        check(events.contains("button 0 " + GLFW_RELEASE + " 0"), "held button released");
        check(glfwGetKey(window, GLFW_KEY_BACKSPACE) == GLFW_RELEASE, "state cleared");
        events.clear();

        // Errors and unsupported calls are explicit.
        errors.clear();
        check(glfwGetKey(window, 9999) == GLFW_RELEASE && errors.contains(GLFW_INVALID_ENUM), "invalid key reported");
        check(glfwGetError(null) == GLFW_INVALID_ENUM && glfwGetError(null) == GLFW_NO_ERROR, "glfwGetError resets");
        try {
            glfwRequestWindowAttention(window);
            throw new AssertionError("unsupported call returned");
        } catch (UnsupportedOperationException expected) {
            check(expected.getMessage().contains("glfwRequestWindowAttention"), "names the unsupported method");
        }
        check("a".equals(glfwGetKeyName(GLFW_KEY_A, 0)) && glfwGetKeyName(GLFW_KEY_ENTER, 0) == null, "key names");
        check(glfwGetKeyScancode(GLFW_KEY_A) == 4, "scancode is the HID usage");

        glfwSetClipboardString(window, memUTF8("copied"));
        check("copied".equals(glfwGetClipboardString(window)), "clipboard round trip");
        double t0 = glfwGetTime();
        glfwSetTime(100.0);
        check(glfwGetTime() >= 100.0 && glfwGetTime() < 101.0 && t0 >= 0, "timer");

        Callbacks.glfwFreeCallbacks(window);
        NativeInput.push(NativeInput.EVENT_KEY_DOWN, 4, 0, 0, 0, 0, 0, 0);
        glfwPollEvents();
        check(events.isEmpty(), "callbacks freed");

        check(!glfwWindowShouldClose(window), "open");
        glfwSetWindowShouldClose(window, true);
        check(glfwWindowShouldClose(window), "close requested");
        // Gamepad: the DualShock 4 as GLFW_JOYSTICK_1 with the standard mapping.
        int[] events = new int[2];
        glfwSetJoystickCallback((jid, event) -> { events[0] = jid; events[1] = event; });
        NativePlatform.padState = new int[]{1, 0x4000 | 0x0008 | 0x0040, 128, 0, 255, 128, 0, 255};
        glfwPollEvents();
        check(events[0] == GLFW_JOYSTICK_1 && events[1] == GLFW_CONNECTED, "pad connect callback");
        check(glfwJoystickPresent(GLFW_JOYSTICK_1) && glfwJoystickIsGamepad(GLFW_JOYSTICK_1)
            && !glfwJoystickPresent(GLFW_JOYSTICK_2), "pad present as joystick 1 only");
        check("PS4 Controller".equals(glfwGetGamepadName(GLFW_JOYSTICK_1)), "pad name");
        try (org.lwjgl.glfw.GLFWGamepadState state = org.lwjgl.glfw.GLFWGamepadState.calloc()) {
            check(glfwGetGamepadState(GLFW_JOYSTICK_1, state), "pad state read");
            check(state.buttons(GLFW_GAMEPAD_BUTTON_A) == GLFW_PRESS && state.buttons(GLFW_GAMEPAD_BUTTON_START) == GLFW_PRESS
                && state.buttons(GLFW_GAMEPAD_BUTTON_DPAD_DOWN) == GLFW_PRESS && state.buttons(GLFW_GAMEPAD_BUTTON_B) == GLFW_RELEASE,
                "pad buttons: cross=A, options=START, dpad down");
            check(Math.abs(state.axes(GLFW_GAMEPAD_AXIS_LEFT_Y) + 1f) < 0.01f && Math.abs(state.axes(GLFW_GAMEPAD_AXIS_RIGHT_X) - 1f) < 0.01f
                && Math.abs(state.axes(GLFW_GAMEPAD_AXIS_LEFT_X)) < 0.01f, "pad sticks: up is -1, right is +1, centre is 0");
            check(Math.abs(state.axes(GLFW_GAMEPAD_AXIS_LEFT_TRIGGER) + 1f) < 0.01f
                && Math.abs(state.axes(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER) - 1f) < 0.01f, "pad triggers: -1 released, +1 pressed");
        }
        NativePlatform.padState = new int[8];
        glfwPollEvents();
        check(events[1] == GLFW_DISCONNECTED && !glfwJoystickPresent(GLFW_JOYSTICK_1), "pad disconnect");
        glfwSetJoystickCallback(null);

        glfwDestroyWindow(window);
        check(NativePlatform.destroyed == 1 && glfwGetCurrentContext() == 0, "window destroyed and released");
        glfwTerminate();
        check(NativePlatform.terminateCalls == 1 && NativeInput.stopped, "terminate releases platform and input");
        System.out.println("GLFW facade behavior passed");
    }

    private static int lastError() {
        return errors.isEmpty() ? GLFW_NO_ERROR : errors.get(errors.size() - 1);
    }

    private static void expect(String... expected) {
        check(events.equals(List.of(expected)), "events " + events + " != " + List.of(expected));
        events.clear();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
