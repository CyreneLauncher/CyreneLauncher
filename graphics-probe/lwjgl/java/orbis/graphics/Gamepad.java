package orbis.graphics;

/**
 * The console's controller for code outside the GLFW facade (the Orbis compatibility mod feeds it to
 * SDL3 for Controlify 3.5+, which reads controllers only through SDL). The facade installs its pad
 * reader here when GLFW initialises; the state is what it read at the last event poll, neutral while
 * the system keyboard has the pad.
 */
public final class Gamepad {
    /** Fills the raw state: connected (0 or 1), button bits, then left x/y, right x/y, L2, R2 as 0..255. */
    public interface Source {
        boolean read(int[] state);
    }

    private static volatile Source source;

    private Gamepad() {}

    public static void install(Source reader) {
        source = reader;
    }

    /** False, with state untouched, when no controller is connected or GLFW is not running. */
    public static boolean read(int[] state) {
        Source reader = source;
        return reader != null && state != null && state.length >= 8 && reader.read(state);
    }
}
