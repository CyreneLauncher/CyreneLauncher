package orbis.graphics;

/**
 * The console's system keyboard for applications (the Orbis compatibility mod opens it for a focused
 * Minecraft text field): one dialog at a time, driven from the render thread like the launcher's.
 * The GLFW facade reports here which kind of input came last, so the dialog opens only for a player
 * on the controller, and asks whether a dialog is open, so the system UI it shows does not count as
 * the game losing focus.
 */
public final class TextInput {
    public static final int IDLE = 0, RUNNING = 1, ACCEPTED = 2, CANCELLED = 3;
    public static final int LABEL_DEFAULT = 0, LABEL_SEND = 1, LABEL_SEARCH = 2, LABEL_GO = 3;
    /** The dialog's own limit (IME_CAPACITY in the platform). */
    public static final int MAX_LENGTH = 1024;

    private static volatile boolean active;
    private static boolean cancelling;
    private static volatile boolean gamepadLast;

    private TextInput() {}

    /** Opens the dialog; false (with nothing open) when the platform refuses. */
    public static synchronized boolean begin(String initial, String title, int maxLength, boolean multiline,
                                             int enterLabel) {
        if (active) return false;
        int limit = Math.max(1, Math.min(MAX_LENGTH, maxLength));
        String text = initial == null ? "" : initial.length() > limit ? initial.substring(0, limit) : initial;
        String heading = title == null ? "" : title.length() > 128 ? title.substring(0, 128) : title;
        int error;
        try {
            error = NativePlatform.nImeBeginOptions(text, heading, limit, multiline, enterLabel);
            // A firmware that refuses the options still offers the plain dialog.
            if (error < 0 && (multiline || enterLabel != LABEL_DEFAULT))
                error = NativePlatform.nImeBegin(text, heading, limit);
        } catch (LinkageError unavailable) {
            return false;
        }
        if (error < 0) return false;
        active = true;
        cancelling = false;
        return true;
    }

    /** IDLE, RUNNING, ACCEPTED or CANCELLED; a negative value is a platform failure (the dialog is gone). */
    public static synchronized int poll() {
        if (!active) return IDLE;
        int status = NativePlatform.nImePoll();
        if (status != RUNNING) active = false;
        return cancelling && status == ACCEPTED ? CANCELLED : status;
    }

    public static String result() {
        return NativePlatform.nImeResult();
    }

    /** Closes the dialog without its text; false while it is still closing (keep polling). */
    public static synchronized boolean cancel() {
        if (!active) return true;
        cancelling = true;
        if (NativePlatform.nImeCancel() == 0) active = false;
        return !active;
    }

    public static boolean active() {
        return active;
    }

    /** The controller was the last input. */
    public static boolean gamepadLast() {
        return gamepadLast;
    }

    public static void noteGamepad() {
        gamepadLast = true;
    }

    public static void noteKeyboardOrMouse() {
        gamepadLast = false;
    }
}
