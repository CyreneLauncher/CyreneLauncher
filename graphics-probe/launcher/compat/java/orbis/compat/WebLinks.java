package orbis.compat;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Minecraft's "open link" on the console: web links (http and https) open in the system web browser
 * (orbis.graphics.WebBrowser, in the graphics adapter, reached through the game's loader as TextEntry
 * reaches TextInput). On Linux Minecraft would start xdg-open, which the console cannot run. Other
 * schemes, such as a folder Minecraft offers to open, are left to Minecraft.
 */
public final class WebLinks {
    private static Method open;
    private static boolean disabled;

    private WebLinks() {}

    /** True when the link was handled here, so Minecraft's own opener is skipped. */
    public static boolean open(Object owner, String link) {
        if (disabled || link == null) return false;
        String scheme = link.length() > 8 ? link.substring(0, 8).toLowerCase(Locale.ROOT) : link.toLowerCase(Locale.ROOT);
        if (!scheme.startsWith("http://") && !scheme.startsWith("https://")) return false;
        try {
            if (open == null)
                open = Class.forName("orbis.graphics.WebBrowser", true, owner.getClass().getClassLoader())
                        .getMethod("open", String.class);
            boolean shown = (Boolean) open.invoke(null, link);
            System.out.println("orbis-compat: link " + (shown ? "opened in the web browser: " : "not opened: ") + link);
            return true;
        } catch (ClassNotFoundException notConsole) {
            disabled = true;
            return false;
        } catch (Throwable failure) {
            disabled = true;
            System.err.println("orbis-compat: web links off: " + failure);
            return false;
        }
    }
}
