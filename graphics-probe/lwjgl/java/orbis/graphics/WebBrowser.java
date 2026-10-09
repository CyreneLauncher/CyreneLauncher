package orbis.graphics;

/**
 * The console's web browser for links the game opens (the Orbis compatibility mod routes Minecraft's
 * "open link" here): the system web browser dialog over the game, or the Browser application when
 * the dialog cannot open. The GLFW facade polls it with every event poll and keeps the controller
 * from the game while it is open; the system reports the dialog as an overlay, which the game sees as
 * lost focus.
 */
public final class WebBrowser {
    private static volatile boolean active;

    private WebBrowser() {}

    /** True when the link is showing (the dialog, or the Browser application); false when nothing opened. */
    public static synchronized boolean open(String url) {
        if (active || url == null || url.isEmpty()) return false;
        int result;
        try {
            result = NativePlatform.nBrowserOpen(url);
        } catch (LinkageError unavailable) {
            return false;
        }
        if (result == 1) active = true;
        return result == 1 || result == 2;
    }

    /** Whether the web browser dialog is open over the game. */
    public static boolean active() {
        return active;
    }

    /** Advances the dialog; called by the GLFW facade with every event poll. */
    public static synchronized void poll() {
        if (!active) return;
        try {
            if (NativePlatform.nBrowserPoll() != 1) active = false;
        } catch (LinkageError unavailable) {
            active = false;
        }
    }
}
