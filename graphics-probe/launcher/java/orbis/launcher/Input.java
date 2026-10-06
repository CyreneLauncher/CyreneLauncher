package orbis.launcher;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.lwjgl.glfw.GLFWGamepadState;

import static org.lwjgl.glfw.GLFW.*;

/** Converts physical input into source-aware actions and a separate Unicode character stream. */
final class Input {
    enum Source { KEYBOARD, CONTROLLER }
    enum Action {
        UP, DOWN, LEFT, RIGHT, CONFIRM, BACK, ALT, ALT2, MENU, DELETE,
        FORWARD_DELETE, HOME, END, FOCUS, PREV_SECTION, NEXT_SECTION
    }
    record Event(Source source, Action action, String text) {
        Event(Source source, Action action) { this(source, action, ""); }
        static Event characters(String text) { return new Event(Source.KEYBOARD, null, text); }
    }

    private static final Action[] PAD = {Action.UP, Action.DOWN, Action.LEFT, Action.RIGHT,
            Action.CONFIRM, Action.BACK, Action.ALT, Action.ALT2, Action.MENU,
            Action.PREV_SECTION, Action.NEXT_SECTION};
    private final boolean[] held = new boolean[PAD.length];
    private final long[] repeatAt = new long[PAD.length];
    private final List<Event> events = new ArrayList<>();
    private final List<Event> pending = new ArrayList<>();
    private final Set<Integer> keysDown = new HashSet<>();
    private final Set<Integer> guardedKeys = new HashSet<>();
    private final EnumSet<Action> guardedPad = EnumSet.noneOf(Action.class);
    private final StringBuilder typed = new StringBuilder();

    void install(long window) {
        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (action == GLFW_RELEASE) {
                keysDown.remove(key);
                guardedKeys.remove(key);
                return;
            }
            keysDown.add(key);
            if (guardedKeys.contains(key)) return;
            Action mapped = keyAction(key);
            if (action == GLFW_REPEAT && !repeatable(mapped)) return;
            if (mapped != null) pending.add(new Event(Source.KEYBOARD, mapped));
        });
        glfwSetCharCallback(window, (w, codepoint) -> {
            if (Character.isValidCodePoint(codepoint) && (codepoint < 0xd800 || codepoint > 0xdfff)
                    && codepoint >= 32 && codepoint != 127)
                {
                    String character = new String(Character.toChars(codepoint));
                    typed.append(character);
                    pending.add(Event.characters(character));
                }
        });
    }

    /** Absorb held confirm/back controls until each has been released. */
    void releaseBarrier() {
        for (int key : keysDown)
            if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER || key == GLFW_KEY_ESCAPE)
                guardedKeys.add(key);
        for (int i = 0; i < PAD.length; i++)
            if (held[i] && (PAD[i] == Action.CONFIRM || PAD[i] == Action.BACK)) guardedPad.add(PAD[i]);
        pending.removeIf(e -> e.action() == Action.CONFIRM || e.action() == Action.BACK);
    }

    /** Collect this frame's actions after glfwPollEvents. */
    void poll(GLFWGamepadState pad) {
        events.clear();
        // GLFW callbacks are already ordered. Pad state is sampled after those callbacks.
        events.addAll(pending);
        pending.clear();
        boolean[] now = new boolean[PAD.length];
        if (glfwJoystickIsGamepad(GLFW_JOYSTICK_1) && glfwGetGamepadState(GLFW_JOYSTICK_1, pad)) {
            float x = pad.axes(GLFW_GAMEPAD_AXIS_LEFT_X), y = pad.axes(GLFW_GAMEPAD_AXIS_LEFT_Y);
            now[0] = down(pad, GLFW_GAMEPAD_BUTTON_DPAD_UP) || y < -0.6f;
            now[1] = down(pad, GLFW_GAMEPAD_BUTTON_DPAD_DOWN) || y > 0.6f;
            now[2] = down(pad, GLFW_GAMEPAD_BUTTON_DPAD_LEFT) || x < -0.6f;
            now[3] = down(pad, GLFW_GAMEPAD_BUTTON_DPAD_RIGHT) || x > 0.6f;
            now[4] = down(pad, GLFW_GAMEPAD_BUTTON_A);
            now[5] = down(pad, GLFW_GAMEPAD_BUTTON_B);
            now[6] = down(pad, GLFW_GAMEPAD_BUTTON_X);
            now[7] = down(pad, GLFW_GAMEPAD_BUTTON_Y);
            now[8] = down(pad, GLFW_GAMEPAD_BUTTON_START);
            now[9] = down(pad, GLFW_GAMEPAD_BUTTON_LEFT_BUMPER);
            now[10] = down(pad, GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER);
        }
        long t = System.nanoTime();
        for (int i = 0; i < PAD.length; i++) {
            Action a = PAD[i];
            if (!now[i]) guardedPad.remove(a);
            boolean repeats = i <= 3;
            boolean fire = now[i] && !guardedPad.contains(a)
                    && (!held[i] || (repeats && t >= repeatAt[i]));
            if (now[i] && !held[i]) repeatAt[i] = t + 400_000_000L;
            else if (fire) repeatAt[i] = t + 110_000_000L;
            held[i] = now[i];
            if (fire) events.add(new Event(Source.CONTROLLER, a));
        }
    }

    List<Event> events() { return events; }
    /** Compatibility for code that only inspects actions. */
    List<Action> actions() { return events.stream().map(Event::action).filter(a -> a != null).toList(); }
    String takeTyped() {
        String result = typed.toString();
        typed.setLength(0);
        return result;
    }

    private static boolean repeatable(Action action) {
        return action == Action.UP || action == Action.DOWN || action == Action.LEFT
                || action == Action.RIGHT || action == Action.DELETE
                || action == Action.FORWARD_DELETE;
    }

    private static Action keyAction(int key) {
        return switch (key) {
            case GLFW_KEY_UP -> Action.UP;
            case GLFW_KEY_DOWN -> Action.DOWN;
            case GLFW_KEY_LEFT -> Action.LEFT;
            case GLFW_KEY_RIGHT -> Action.RIGHT;
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> Action.CONFIRM;
            case GLFW_KEY_ESCAPE -> Action.BACK;
            case GLFW_KEY_BACKSPACE -> Action.DELETE;
            case GLFW_KEY_DELETE -> Action.FORWARD_DELETE;
            case GLFW_KEY_HOME -> Action.HOME;
            case GLFW_KEY_END -> Action.END;
            case GLFW_KEY_TAB -> Action.FOCUS;
            case GLFW_KEY_PAGE_UP -> Action.PREV_SECTION;
            case GLFW_KEY_PAGE_DOWN -> Action.NEXT_SECTION;
            default -> null;
        };
    }

    private static boolean down(GLFWGamepadState pad, int button) {
        return pad.buttons(button) == GLFW_PRESS;
    }
}
