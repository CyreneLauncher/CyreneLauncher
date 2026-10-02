package org.lwjgl.glfw;

import static org.lwjgl.glfw.GLFW.*;

/**
 * USB HID usage IDs (the input service's raw key, used as the GLFW scancode) to GLFW
 * key codes, and US-layout names for printable keys as glfwGetKeyName reports them.
 */
final class OrbisKeys {
    private static final int[] GLFW_BY_HID = new int[256];
    private static final int[] HID_BY_GLFW = new int[GLFW_KEY_LAST + 1];

    static {
        java.util.Arrays.fill(GLFW_BY_HID, GLFW_KEY_UNKNOWN);
        java.util.Arrays.fill(HID_BY_GLFW, -1);
        for (int i = 0; i < 26; i++) map(4 + i, GLFW_KEY_A + i);
        for (int i = 0; i < 9; i++) map(30 + i, GLFW_KEY_1 + i);
        map(39, GLFW_KEY_0);
        map(40, GLFW_KEY_ENTER); map(41, GLFW_KEY_ESCAPE); map(42, GLFW_KEY_BACKSPACE);
        map(43, GLFW_KEY_TAB); map(44, GLFW_KEY_SPACE); map(45, GLFW_KEY_MINUS); map(46, GLFW_KEY_EQUAL);
        map(47, GLFW_KEY_LEFT_BRACKET); map(48, GLFW_KEY_RIGHT_BRACKET); map(49, GLFW_KEY_BACKSLASH);
        map(50, GLFW_KEY_WORLD_1); map(51, GLFW_KEY_SEMICOLON); map(52, GLFW_KEY_APOSTROPHE);
        map(53, GLFW_KEY_GRAVE_ACCENT); map(54, GLFW_KEY_COMMA); map(55, GLFW_KEY_PERIOD);
        map(56, GLFW_KEY_SLASH); map(57, GLFW_KEY_CAPS_LOCK);
        for (int i = 0; i < 12; i++) map(58 + i, GLFW_KEY_F1 + i);
        map(70, GLFW_KEY_PRINT_SCREEN); map(71, GLFW_KEY_SCROLL_LOCK); map(72, GLFW_KEY_PAUSE);
        map(73, GLFW_KEY_INSERT); map(74, GLFW_KEY_HOME); map(75, GLFW_KEY_PAGE_UP); map(76, GLFW_KEY_DELETE);
        map(77, GLFW_KEY_END); map(78, GLFW_KEY_PAGE_DOWN); map(79, GLFW_KEY_RIGHT); map(80, GLFW_KEY_LEFT);
        map(81, GLFW_KEY_DOWN); map(82, GLFW_KEY_UP); map(83, GLFW_KEY_NUM_LOCK);
        map(84, GLFW_KEY_KP_DIVIDE); map(85, GLFW_KEY_KP_MULTIPLY); map(86, GLFW_KEY_KP_SUBTRACT);
        map(87, GLFW_KEY_KP_ADD); map(88, GLFW_KEY_KP_ENTER);
        for (int i = 0; i < 9; i++) map(89 + i, GLFW_KEY_KP_1 + i);
        map(98, GLFW_KEY_KP_0); map(99, GLFW_KEY_KP_DECIMAL); map(100, GLFW_KEY_WORLD_2);
        map(101, GLFW_KEY_MENU); map(103, GLFW_KEY_KP_EQUAL);
        for (int i = 0; i < 12; i++) map(104 + i, GLFW_KEY_F13 + i);
        map(224, GLFW_KEY_LEFT_CONTROL); map(225, GLFW_KEY_LEFT_SHIFT); map(226, GLFW_KEY_LEFT_ALT);
        map(227, GLFW_KEY_LEFT_SUPER); map(228, GLFW_KEY_RIGHT_CONTROL); map(229, GLFW_KEY_RIGHT_SHIFT);
        map(230, GLFW_KEY_RIGHT_ALT); map(231, GLFW_KEY_RIGHT_SUPER);
    }

    private OrbisKeys() {}

    private static void map(int hid, int key) {
        GLFW_BY_HID[hid] = key;
        HID_BY_GLFW[key] = hid;
    }

    static int glfwKey(int hid) {
        return hid >= 0 && hid < 256 ? GLFW_BY_HID[hid] : GLFW_KEY_UNKNOWN;
    }

    static int scancode(int key) {
        return key >= 0 && key <= GLFW_KEY_LAST ? HID_BY_GLFW[key] : -1;
    }

    /** Printable keys only; GLFW returns null for named keys such as Enter. */
    static String name(int key) {
        if (key >= GLFW_KEY_A && key <= GLFW_KEY_Z) return String.valueOf((char) ('a' + key - GLFW_KEY_A));
        if (key >= GLFW_KEY_0 && key <= GLFW_KEY_9) return String.valueOf((char) ('0' + key - GLFW_KEY_0));
        if (key >= GLFW_KEY_KP_0 && key <= GLFW_KEY_KP_9) return String.valueOf((char) ('0' + key - GLFW_KEY_KP_0));
        switch (key) {
            case GLFW_KEY_APOSTROPHE: return "'";
            case GLFW_KEY_COMMA: return ",";
            case GLFW_KEY_MINUS: case GLFW_KEY_KP_SUBTRACT: return "-";
            case GLFW_KEY_PERIOD: case GLFW_KEY_KP_DECIMAL: return ".";
            case GLFW_KEY_SLASH: case GLFW_KEY_KP_DIVIDE: return "/";
            case GLFW_KEY_SEMICOLON: return ";";
            case GLFW_KEY_EQUAL: case GLFW_KEY_KP_EQUAL: return "=";
            case GLFW_KEY_LEFT_BRACKET: return "[";
            case GLFW_KEY_BACKSLASH: case GLFW_KEY_WORLD_2: return "\\";
            case GLFW_KEY_RIGHT_BRACKET: return "]";
            case GLFW_KEY_GRAVE_ACCENT: return "`";
            case GLFW_KEY_KP_MULTIPLY: return "*";
            case GLFW_KEY_KP_ADD: return "+";
            default: return null;
        }
    }
}
