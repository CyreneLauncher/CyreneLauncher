package runner.gpu;

/**
 * GPU display mode input from the runner's native input service; natives are
 * registered by the launcher only when runner.display=gpu. Packets use the ABI 1
 * layout shared with runner.desktop.NativeDesktopBridge (orbis_input_packet.h).
 */
public final class NativeInput {
    public static final int ABI_VERSION = 1, EVENT_WORDS = 24;
    public static final int WIDTH = 1280, HEIGHT = 720;
    public static final int VERSION = 0, WORD_COUNT = 1, TYPE = 2, FOCUS = 3,
        TIMESTAMP_LOW = 4, TIMESTAMP_HIGH = 5, RESET_LOW = 6, RESET_HIGH = 7,
        RAW_KEY = 8, UNICODE_SCALAR = 9, MODIFIERS = 10, X = 11, Y = 12,
        BUTTON = 13, WHEEL_DELTA = 14, PRESSED_BUTTONS = 15, PRESSED_KEYS = 16;
    public static final int EVENT_RESET = 0, EVENT_KEY_DOWN = 1, EVENT_KEY_UP = 2,
        EVENT_TEXT = 3, EVENT_MOTION = 4, EVENT_BUTTON_DOWN = 5, EVENT_BUTTON_UP = 6,
        EVENT_WHEEL = 7, EVENT_MODIFIERS = 8, EVENT_FOCUS = 9;

    private NativeInput() {}

    /** Starts input with keyboard/mouse focus on this consumer; 0 on success. */
    public static native int start();

    /** 0 with a packet, 1 on timeout (0..50 ms), negative when stopped or invalid. */
    public static native int readEvent(int[] packet, int timeoutMillis);

    /** Moves the pointer reference without a motion event; 0 on success. */
    public static native int setPointer(int x, int y);

    public static native void stop();
}
