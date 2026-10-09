package orbis.compat;

import java.lang.reflect.Method;

/**
 * Hands the console's controller to SDL3 for Controlify 3.5+ (Minecraft 26.1+), which reads
 * controllers only through SDL: called right before Controlify polls SDL (ControlifySdlFeedMixin).
 * The work needs Java's foreign function API, so it lives in orbis.compat.sdl.SdlGamepad, compiled for
 * Java 22 and loaded only on a runtime that new; on older ones, or without the console's libSDL3,
 * this does nothing.
 */
public final class GamepadFeed {
    private static Method tick;
    private static boolean disabled;

    private GamepadFeed() {}

    /** The running Java's feature release ("1.8" is 8), from a property Java 8 already has. */
    private static int javaFeature() {
        String version = System.getProperty("java.specification.version", "1.8");
        try {
            return Integer.parseInt(version.startsWith("1.") ? version.substring(2) : version);
        } catch (NumberFormatException unknown) {
            return 8;
        }
    }

    public static void tick(Object controllerManager) {
        if (disabled) return;
        try {
            if (tick == null) {
                if (javaFeature() < 22) {
                    disabled = true;
                    return;
                }
                ClassLoader game = controllerManager.getClass().getClassLoader();
                Class<?> feed = Class.forName("orbis.compat.sdl.SdlGamepad", true, GamepadFeed.class.getClassLoader());
                tick = feed.getMethod("tick", ClassLoader.class);
                tick.invoke(null, game);
                return;
            }
            tick.invoke(null, controllerManager.getClass().getClassLoader());
        } catch (Throwable failure) {
            disabled = true;
            System.err.println("orbis-compat: controller feed for SDL off: " + failure);
        }
    }
}
