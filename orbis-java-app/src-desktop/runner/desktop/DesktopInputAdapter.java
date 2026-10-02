package runner.desktop;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import com.github.caciocavallosilano.cacio.ctc.CTCEventSource;
import com.github.caciocavallosilano.cacio.ctc.CTCScreen;
import com.github.caciocavallosilano.cacio.peer.managed.EventData;

/** ABI 1 translation, consumed only by the existing daemon CacioEventPump. */
final class DesktopInputAdapter implements CTCEventSource.PollingEventSource {
    private static final int MAX_PENDING = 576;
    private final int[] packet = new int[NativeDesktopBridge.EVENT_WORDS];
    private final EventData[] pending = new EventData[MAX_PENDING];
    private final boolean[] keys = new boolean[256];
    private final boolean[] buttons = new boolean[3];
    private final boolean[] clickEligible = new boolean[3];
    private final int[] pressX = new int[3], pressY = new int[3];
    private final long[] pressTime = new long[3];
    private int head, count, x, y, modifiers;
    private long nativeOrigin, javaOrigin, lastTime;
    private boolean clockSet;
    private volatile boolean enabled, stopped;
    private volatile Thread consumer;

    void enable() { enabled = true; }
    void requestStop() {
        stopped = true;
        Thread thread = consumer;
        if (thread != null) thread.interrupt();
    }
    Thread consumerThread() { return consumer; }

    @Override
    public EventData getNextEvent() throws InterruptedException {
        for (;;) {
            EventData event = pollNextEvent(2);
            if (event != null) return event;
        }
    }

    @Override
    public EventData pollNextEvent(int timeoutMillis) throws InterruptedException {
        consumer = Thread.currentThread();
        if (stopped || consumer.isInterrupted()) throw new InterruptedException("Desktop input stopped");
        if (count != 0) return remove(); // Reconcile completely before another native read.
        if (!enabled) { Thread.sleep(Math.min(2, timeoutMillis)); return null; }
        int status = NativeDesktopBridge.readEvent(packet, Math.min(2, timeoutMillis));
        if (stopped || consumer.isInterrupted()) throw new InterruptedException("Desktop input stopped");
        if (status == 1) return null; // Internal finite poll only; getNextEvent never returns null.
        if (status < 0) {
            DesktopRuntime.inputFailed(status);
            throw new InterruptedException("Native desktop input stopped");
        }
        if (packet[0] != 1 || packet[1] != 24) {
            DesktopRuntime.inputFailed(-1);
            throw new InterruptedException("Invalid native input packet");
        }
        translate();
        return count == 0 ? null : remove();
    }

    private void translate() {
        long time = eventTime();
        int type = packet[NativeDesktopBridge.TYPE];
        x = packet[NativeDesktopBridge.X]; y = packet[NativeDesktopBridge.Y];
        modifiers = keyboardModifiers(packet[NativeDesktopBridge.MODIFIERS]);
        if (type == 0 || (type == 9 && packet[NativeDesktopBridge.FOCUS] != 1)) {
            reset(time, type == 0 && packet[NativeDesktopBridge.FOCUS] == 1);
            return;
        }
        if (packet[NativeDesktopBridge.FOCUS] != 1) return;
        int raw = packet[NativeDesktopBridge.RAW_KEY];
        switch (type) {
        case 1:
            if (raw >= 0 && raw < 256 && !keys[raw]) {
                keys[raw] = true; key(KeyEvent.KEY_PRESSED, raw, time);
                // Swing editing is on pressed bindings. Typed control events
                // preserve AWT semantics; Swing excludes controls from insertion.
                if (raw == 40 || raw == 88) typed('\n', time);
                else if (raw == 42) typed('\b', time);
                else if (raw == 43) typed('\t', time);
            }
            break;
        case 2:
            if (raw >= 0 && raw < 256 && keys[raw]) {
                keys[raw] = false; key(KeyEvent.KEY_RELEASED, raw, time);
            }
            break;
        case 3:
            int scalar = packet[NativeDesktopBridge.UNICODE_SCALAR];
            if (raw < 0 || raw >= 256 || !keys[raw] || hid(raw) == KeyEvent.VK_UNDEFINED) break;
            if (scalar == 8 && raw == 42) {
                key(KeyEvent.KEY_PRESSED, raw, time); typed('\b', time);
            } else if (scalar >= 32 && scalar <= 126) {
                // Normalize Ctrl ASCII to control characters, as AWT expects;
                // Swing's default typed action excludes these from insertion.
                if ((modifiers & InputEvent.ALT_GRAPH_DOWN_MASK) != 0) typed((char) scalar, time);
                else if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) {
                    int upper = scalar >= 'a' && scalar <= 'z' ? scalar - 32 : scalar;
                    if (upper >= '@' && upper <= '_') typed((char) (upper & 31), time);
                    else if (scalar == ' ') typed((char) 0, time);
                } else if ((modifiers & (InputEvent.META_DOWN_MASK | InputEvent.ALT_DOWN_MASK)) == 0) {
                    typed((char) scalar, time);
                }
            }
            break;
        case 4:
            for (int i = 0; i < 3; i++) {
                if (buttons[i] && (x != pressX[i] || y != pressY[i])) clickEligible[i] = false;
            }
            mouse(anyButton() ? MouseEvent.MOUSE_DRAGGED : MouseEvent.MOUSE_MOVED, -1, time, 0);
            break;
        case 5: case 6:
            int bit = packet[NativeDesktopBridge.BUTTON_BIT]; // Native field is an index.
            if (bit >= 0 && bit < 3) {
                boolean down = type == 5;
                if (buttons[bit] == down) break;
                buttons[bit] = down;
                if (down) {
                    pressX[bit] = x; pressY[bit] = y; pressTime[bit] = time;
                    clickEligible[bit] = true;
                    mouse(MouseEvent.MOUSE_PRESSED, bit, time, 1);
                } else {
                    mouse(MouseEvent.MOUSE_RELEASED, bit, time, 1);
                    if (clickEligible[bit] && x == pressX[bit] && y == pressY[bit]
                            && time - pressTime[bit] <= 500) mouse(MouseEvent.MOUSE_CLICKED, bit, time, 1);
                    clickEligible[bit] = false;
                }
            }
            break;
        case 7:
            EventData wheel = mouse(MouseEvent.MOUSE_WHEEL, -1, time, 0);
            wheel.setWheelRotation(packet[NativeDesktopBridge.WHEEL_DELTA]);
            break;
        case 8:
            // SDK modifiers are separate from the HID key bitmap. Preserve
            // left/right transitions as raw HID IDs in the same bounded state.
            for (int i = 0; i < 8; i++) {
                int modifierRaw = 224 + i;
                boolean down = (packet[NativeDesktopBridge.MODIFIERS] & (1 << i)) != 0;
                if (keys[modifierRaw] != down) {
                    keys[modifierRaw] = down;
                    key(down ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED, modifierRaw, time);
                }
            }
            break;
        default: break; // Unknown packet kinds have no Java event.
        }
    }

    private void reset(long time, boolean active) {
        // Reset releases use empty modifiers, never generate clicks or typed text.
        modifiers = 0;
        for (int raw = 0; raw < 256; raw++) {
            if (keys[raw]) { keys[raw] = false; key(KeyEvent.KEY_RELEASED, raw, time); }
        }
        for (int bit = 0; bit < 3; bit++) {
            clickEligible[bit] = false;
            if (buttons[bit]) { buttons[bit] = false; mouse(MouseEvent.MOUSE_RELEASED, bit, time, 0); }
        }
        if (!active) return;
        modifiers = keyboardModifiers(packet[NativeDesktopBridge.MODIFIERS]);
        for (int raw = 0; raw < 256; raw++) {
            if ((packet[NativeDesktopBridge.PRESSED_KEYS + (raw >>> 5)] & (1 << (raw & 31))) != 0
                    || (raw >= 224 && raw <= 231
                        && (packet[NativeDesktopBridge.MODIFIERS] & (1 << (raw - 224))) != 0)) {
                keys[raw] = true; key(KeyEvent.KEY_PRESSED, raw, time);
            }
        }
        for (int bit = 0; bit < 3; bit++) {
            if ((packet[NativeDesktopBridge.PRESSED_BUTTONS] & (1 << bit)) != 0) {
                buttons[bit] = true; mouse(MouseEvent.MOUSE_PRESSED, bit, time, 0);
            }
        }
    }

    private long eventTime() {
        long micros = (packet[NativeDesktopBridge.TIMESTAMP_LOW] & 0xffffffffL)
                | ((long) packet[NativeDesktopBridge.TIMESTAMP_HIGH] << 32);
        if (!clockSet) {
            nativeOrigin = micros; javaOrigin = System.currentTimeMillis(); clockSet = true;
        }
        long elapsed = Math.max(0, micros - nativeOrigin) / 1000;
        lastTime = Math.max(lastTime, javaOrigin + elapsed);
        return lastTime;
    }

    private EventData base(int id, long time) {
        EventData event = new EventData(); event.setId(id); event.setTime(time);
        event.setSource(CTCScreen.getEventRoot()); event.setModifiers(modifiers | buttonModifiers());
        add(event); return event;
    }
    private void key(int id, int raw, long time) {
        EventData event = base(id, time); event.setKeyCode(hid(raw));
        event.setKeyChar(raw == 40 || raw == 88 ? '\n' : raw == 42 ? '\b'
                : raw == 43 ? '\t' : KeyEvent.CHAR_UNDEFINED);
    }
    private void typed(char character, long time) {
        EventData event = base(KeyEvent.KEY_TYPED, time); event.setKeyCode(KeyEvent.VK_UNDEFINED);
        event.setKeyChar(character);
    }
    private EventData mouse(int id, int bit, long time, int clicks) {
        EventData event = base(id, time); event.setX(x); event.setY(y);
        event.setButton(bit < 0 ? MouseEvent.NOBUTTON : awtButton(bit));
        event.setClickCount(clicks); event.setPopup(bit == 1 && id == MouseEvent.MOUSE_RELEASED && clicks != 0);
        return event;
    }
    private boolean anyButton() { return buttons[0] || buttons[1] || buttons[2]; }
    private int buttonModifiers() {
        return (buttons[0] ? InputEvent.BUTTON1_DOWN_MASK : 0)
                | (buttons[1] ? InputEvent.BUTTON3_DOWN_MASK : 0)
                | (buttons[2] ? InputEvent.BUTTON2_DOWN_MASK : 0);
    }
    // Inferred mouse ABI: bit0 primary, bit1 secondary, bit2 middle; PS4 confirmation pending.
    private static int awtButton(int bit) { return bit == 0 ? 1 : bit == 1 ? 3 : 2; }
    private void add(EventData event) {
        if (count == MAX_PENDING) throw new IllegalStateException("Desktop synthesis capacity exceeded");
        pending[(head + count++) % MAX_PENDING] = event;
    }
    private EventData remove() {
        EventData event = pending[head]; pending[head] = null;
        head = (head + 1) % MAX_PENDING; count--; return event;
    }
    private static int keyboardModifiers(int sdk) {
        // Verified against Orbis _types/keyboard.h, independent of HID key IDs.
        return ((sdk & (1 | 16)) != 0 ? InputEvent.CTRL_DOWN_MASK : 0)
                | ((sdk & (2 | 32)) != 0 ? InputEvent.SHIFT_DOWN_MASK : 0)
                | ((sdk & (4 | 64)) != 0 ? InputEvent.ALT_DOWN_MASK : 0)
                | ((sdk & 64) != 0 ? InputEvent.ALT_GRAPH_DOWN_MASK : 0)
                | ((sdk & (8 | 128)) != 0 ? InputEvent.META_DOWN_MASK : 0);
    }
    private static int hid(int raw) {
        if (raw >= 4 && raw <= 29) return KeyEvent.VK_A + raw - 4;
        if (raw >= 30 && raw <= 38) return KeyEvent.VK_1 + raw - 30;
        if (raw >= 58 && raw <= 69) return KeyEvent.VK_F1 + raw - 58;
        if (raw >= 104 && raw <= 115) return KeyEvent.VK_F13 + raw - 104;
        if (raw >= 89 && raw <= 97) return KeyEvent.VK_NUMPAD1 + raw - 89;
        switch (raw) {
        case 39: return KeyEvent.VK_0; case 40: case 88: return KeyEvent.VK_ENTER;
        case 41: return KeyEvent.VK_ESCAPE; case 42: return KeyEvent.VK_BACK_SPACE;
        case 43: return KeyEvent.VK_TAB; case 44: return KeyEvent.VK_SPACE;
        case 45: return KeyEvent.VK_MINUS; case 46: return KeyEvent.VK_EQUALS;
        case 47: return KeyEvent.VK_OPEN_BRACKET; case 48: return KeyEvent.VK_CLOSE_BRACKET;
        case 49: case 100: return KeyEvent.VK_BACK_SLASH; case 51: return KeyEvent.VK_SEMICOLON;
        case 52: return KeyEvent.VK_QUOTE; case 53: return KeyEvent.VK_BACK_QUOTE;
        case 54: return KeyEvent.VK_COMMA; case 55: return KeyEvent.VK_PERIOD;
        case 56: return KeyEvent.VK_SLASH; case 57: return KeyEvent.VK_CAPS_LOCK;
        case 70: return KeyEvent.VK_PRINTSCREEN; case 71: return KeyEvent.VK_SCROLL_LOCK;
        case 72: return KeyEvent.VK_PAUSE; case 73: return KeyEvent.VK_INSERT;
        case 74: return KeyEvent.VK_HOME; case 75: return KeyEvent.VK_PAGE_UP;
        case 76: return KeyEvent.VK_DELETE; case 77: return KeyEvent.VK_END;
        case 78: return KeyEvent.VK_PAGE_DOWN; case 79: return KeyEvent.VK_RIGHT;
        case 80: return KeyEvent.VK_LEFT; case 81: return KeyEvent.VK_DOWN;
        case 82: return KeyEvent.VK_UP; case 83: return KeyEvent.VK_NUM_LOCK;
        case 84: return KeyEvent.VK_DIVIDE; case 85: return KeyEvent.VK_MULTIPLY;
        case 86: return KeyEvent.VK_SUBTRACT; case 87: return KeyEvent.VK_ADD;
        case 98: return KeyEvent.VK_NUMPAD0; case 99: return KeyEvent.VK_DECIMAL;
        case 101: return KeyEvent.VK_CONTEXT_MENU; case 103: return KeyEvent.VK_EQUALS;
        case 224: case 228: return KeyEvent.VK_CONTROL;
        case 225: case 229: return KeyEvent.VK_SHIFT;
        case 226: return KeyEvent.VK_ALT; case 230: return KeyEvent.VK_ALT_GRAPH;
        case 227: case 231: return KeyEvent.VK_META;
        default: return KeyEvent.VK_UNDEFINED;
        }
    }
}
