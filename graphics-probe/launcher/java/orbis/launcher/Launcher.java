package orbis.launcher;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point named by the launcher's version metadata. The runner starts it with the game's
 * arguments on a class path that already holds the game and its libraries.
 *
 * <p>A one-shot {@code next-start} file, written by Play just before the application restarts
 * itself, holds the game's main class: the launcher deletes it and runs the game in this process,
 * with JVM options that took effect at this start. Without it the launcher's own screen runs, so a
 * cold start from the system menu always lands here.
 */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) throws Throwable {
        LauncherConfig config = LauncherConfig.load();
        Path next = config.nextStart();
        if (Files.isRegularFile(next)) {
            String mainClass = Files.readString(next, StandardCharsets.UTF_8).trim();
            Files.delete(next);
            System.out.println("launcher: starting " + mainClass);
            // Quitting the game ends the process (Minecraft's exit skips shutdown hooks), so the
            // application closes; the next start from the system menu shows the launcher again.
            startGame(mainClass, args);
            return;
        }
        new LauncherScreen(config).run();
    }

    static void startGame(String mainClass, String[] args) throws Throwable {
        Class<?> target = Class.forName(mainClass, true, Thread.currentThread().getContextClassLoader());
        Method main = target.getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
