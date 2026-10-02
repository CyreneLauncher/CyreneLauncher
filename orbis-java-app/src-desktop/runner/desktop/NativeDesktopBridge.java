package runner.desktop;

/** ABI 1 native services; no AWT initialization or library loading. */
public final class NativeDesktopBridge {
    public static final int ABI_VERSION = 1;
    public static final int WIDTH = 1280, HEIGHT = 720, STRIDE_PIXELS = 1280;
    public static final int PIXEL_COUNT = WIDTH * HEIGHT, PIXEL_FORMAT_ARGB = 1;
    public static final int CAPABILITY_WORDS = 6, EVENT_WORDS = 24;
    public static final int CAP_ABI = 0, CAP_WIDTH = 1, CAP_HEIGHT = 2,
        CAP_STRIDE = 3, CAP_PIXEL_FORMAT = 4, CAP_EVENT_WORDS = 5;
    public static final int VERSION = 0, WORD_COUNT = 1, TYPE = 2, FOCUS = 3,
        TIMESTAMP_LOW = 4, TIMESTAMP_HIGH = 5, RESET_LOW = 6, RESET_HIGH = 7,
        RAW_KEY = 8, UNICODE_SCALAR = 9, MODIFIERS = 10, X = 11, Y = 12,
        BUTTON_BIT = 13, WHEEL_DELTA = 14, PRESSED_BUTTONS = 15, PRESSED_KEYS = 16;
    private NativeDesktopBridge() { }
    public static native int query(int[] capabilities);
    public static native int start();
    public static native int submit(int[] argb, long sequence);
    public static native int readEvent(int[] packet, int timeoutMillis);
    public static native void stop();
}
