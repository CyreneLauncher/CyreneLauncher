package orbis.launcher;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFWGamepadState;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Gamepad and keyboard turned into launcher actions, one per press with repeat while a direction
 * is held. Cross confirms, circle goes back, square and triangle are page-specific, options opens
 * the page's menu (search). Typed characters arrive separately, from the keyboard only.
 */
final class Input {
    enum Action { UP, DOWN, LEFT, RIGHT, CONFIRM, BACK, ALT, ALT2, MENU, DELETE }

    private static final Action[] PAD = Action.values();
    private final boolean[] held = new boolean[PAD.length];
    private final long[] repeatAt = new long[PAD.length];
    private final List<Action> actions = new ArrayList<>();
    private final List<Integer> keys = new ArrayList<>();
    private final StringBuilder typed = new StringBuilder();

    void install(long window) {
        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (action != GLFW_RELEASE) keys.add(key);
        });
        glfwSetCharCallback(window, (w, codepoint) -> {
            if (codepoint >= 32 && codepoint < 127) typed.append((char) codepoint);
        });
    }

    /** Collects this frame's actions; call once per frame after glfwPollEvents. */
    void poll(GLFWGamepadState pad) {
        actions.clear();
        boolean[] now = new boolean[PAD.length];
        if (glfwJoystickIsGamepad(GLFW_JOYSTICK_1) && glfwGetGamepadState(GLFW_JOYSTICK_1, pad)) {
            float x = pad.axes(GLFW_GAMEPAD_AXIS_LEFT_X), y = pad.axes(GLFW_GAMEPAD_AXIS_LEFT_Y);
            now[Action.UP.ordinal()] = down(pad, GLFW_GAMEPAD_BUTTON_DPAD_UP) || y < -0.6f;
            now[Action.DOWN.ordinal()] = down(pad, GLFW_GAMEPAD_BUTTON_DPAD_DOWN) || y > 0.6f;
            now[Action.LEFT.ordinal()] = down(pad, GLFW_GAMEPAD_BUTTON_DPAD_LEFT) || x < -0.6f;
            now[Action.RIGHT.ordinal()] = down(pad, GLFW_GAMEPAD_BUTTON_DPAD_RIGHT) || x > 0.6f;
            now[Action.CONFIRM.ordinal()] = down(pad, GLFW_GAMEPAD_BUTTON_A);
            now[Action.BACK.ordinal()] = down(pad, GLFW_GAMEPAD_BUTTON_B);
            now[Action.ALT.ordinal()] = down(pad, GLFW_GAMEPAD_BUTTON_X);
            now[Action.ALT2.ordinal()] = down(pad, GLFW_GAMEPAD_BUTTON_Y);
            now[Action.MENU.ordinal()] = down(pad, GLFW_GAMEPAD_BUTTON_START);
            now[Action.DELETE.ordinal()] = down(pad, GLFW_GAMEPAD_BUTTON_LEFT_BUMPER);
        }
        long t = System.nanoTime();
        for (Action a : PAD) {
            int i = a.ordinal();
            boolean repeats = i <= Action.RIGHT.ordinal() || a == Action.DELETE;
            boolean fire = now[i] && (!held[i] || (repeats && t >= repeatAt[i]));
            if (now[i] && !held[i]) repeatAt[i] = t + 400_000_000L;
            else if (fire) repeatAt[i] = t + 110_000_000L;
            held[i] = now[i];
            if (fire) actions.add(a);
        }
        for (int key : keys) {
            switch (key) {
                case GLFW_KEY_UP -> actions.add(Action.UP);
                case GLFW_KEY_DOWN -> actions.add(Action.DOWN);
                case GLFW_KEY_LEFT -> actions.add(Action.LEFT);
                case GLFW_KEY_RIGHT -> actions.add(Action.RIGHT);
                case GLFW_KEY_ENTER -> actions.add(Action.CONFIRM);
                case GLFW_KEY_ESCAPE -> actions.add(Action.BACK);
                case GLFW_KEY_BACKSPACE -> actions.add(Action.DELETE);
                case GLFW_KEY_TAB -> actions.add(Action.MENU);
                default -> { }
            }
        }
        keys.clear();
    }

    List<Action> actions() {
        return actions;
    }

    /** Characters typed on a keyboard since the last call. */
    String takeTyped() {
        String s = typed.toString();
        typed.setLength(0);
        return s;
    }

    private static boolean down(GLFWGamepadState pad, int button) {
        return pad.buttons(button) == GLFW_PRESS;
    }
}
