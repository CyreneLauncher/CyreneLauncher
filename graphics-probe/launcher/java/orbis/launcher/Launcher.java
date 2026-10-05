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
        keepLog(config.home.resolve("launcher.log"));
        new LauncherScreen(config).run();
    }

    /**
     * The launcher's output (GLFW errors among it) also goes to its own log: the runner's output log
     * starts over when Play restarts the application.
     */
    private static void keepLog(Path file) {
        try {
            // The previous launcher run's log survives one more start, as launcher.log.1.
            if (Files.exists(file))
                Files.move(file, file.resolveSibling("launcher.log.1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            java.io.OutputStream log = Files.newOutputStream(file);
            System.setOut(new java.io.PrintStream(new Tee(System.out, log), true, StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(new Tee(System.err, log), true, StandardCharsets.UTF_8));
            System.out.println("launcher: started " + java.time.Instant.now());
        } catch (java.io.IOException unavailable) {
            System.err.println("launcher: no launcher.log: " + unavailable);
        }
    }

    private static final class Tee extends java.io.OutputStream {
        private final java.io.OutputStream first, second;

        Tee(java.io.OutputStream first, java.io.OutputStream second) {
            this.first = first;
            this.second = second;
        }

        @Override public synchronized void write(int b) throws java.io.IOException {
            first.write(b);
            second.write(b);
        }

        @Override public synchronized void write(byte[] b, int off, int len) throws java.io.IOException {
            first.write(b, off, len);
            second.write(b, off, len);
        }

        @Override public synchronized void flush() throws java.io.IOException {
            first.flush();
            second.flush();
        }
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
