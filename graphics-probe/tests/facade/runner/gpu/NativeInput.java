package runner.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Host stand-in for the runner's GPU input bridge: tests enqueue ABI 1 packets. */
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
    public static final ArrayDeque<int[]> queue = new ArrayDeque<>();
    public static final List<int[]> pointers = new ArrayList<>();
    public static boolean started, stopped;

    private NativeInput() {}

    public static int start() { started = true; return 0; }

    public static int readEvent(int[] packet, int timeoutMillis) {
        int[] next = queue.poll();
        if (next == null) return 1;
        System.arraycopy(next, 0, packet, 0, EVENT_WORDS);
        return 0;
    }

    public static int setPointer(int x, int y) { pointers.add(new int[]{x, y}); return 0; }

    public static void stop() { stopped = true; }

    /** Enqueues one packet with focus 1 (the GPU consumer). */
    public static void push(int type, int raw, int scalar, int modifiers, int x, int y, int button, int wheel) {
        int[] packet = new int[EVENT_WORDS];
        packet[VERSION] = ABI_VERSION; packet[WORD_COUNT] = EVENT_WORDS; packet[TYPE] = type; packet[FOCUS] = 1;
        packet[RAW_KEY] = raw; packet[UNICODE_SCALAR] = scalar; packet[MODIFIERS] = modifiers;
        packet[X] = x; packet[Y] = y; packet[BUTTON] = button; packet[WHEEL_DELTA] = wheel;
        queue.add(packet);
    }
}
