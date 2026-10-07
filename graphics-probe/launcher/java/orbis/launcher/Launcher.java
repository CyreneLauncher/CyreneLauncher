package orbis.launcher;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point named by the launcher's version metadata. The runner starts it with the game's
 * arguments on a class path that already holds the game and its libraries.
 *
 * <p>Play publishes a credential-free request and relaunches with a matching invocation ID.
 * Runner settings are restored before authentication and game startup. An unrelated cold start
 * discards stale requests and opens the launcher.
 */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) throws Throwable {
        LauncherConfig config;
        try { config = LauncherConfig.load(); }
        catch (java.io.IOException | RuntimeException failure) {
            try {
                LaunchPreparation.recoverAfterConfigFailure(
                        Path.of(System.getProperty("orbis.launcher.home", "/data/orbis-jdk/launcher")),
                        Path.of(System.getProperty("orbis.runner.root", "/data/orbis-jdk/runner")));
            } catch (java.io.IOException recovery) { failure.addSuppressed(recovery); }
            throw failure;
        }
        keepLog(config.home.resolve("launcher.log"));
        String notice = null;
        boolean gameStarted = false;
        try {
            String intent = null;
            java.util.List<String> base = new java.util.ArrayList<>();
            for (String arg : args) {
                if (arg.startsWith("--orbis-launch-intent=")) {
                    if (intent != null) throw new java.io.IOException("Duplicate launch invocation");
                    String value = arg.substring("--orbis-launch-intent=".length());
                    if (!java.util.UUID.fromString(value).toString().equals(value))
                        throw new java.io.IOException("Invalid launch invocation");
                    intent = value;
                } else base.add(arg);
            }
            LaunchRequest request = new LaunchPreparation(config).consume(intent);
            if (request != null) {
                InstanceStore.Instance instance = config.instances.get(request.instanceId());
                AccountStore accounts = new AccountStore(config.home, config.runnerRoot);
                AccountStore.Account account = accounts.get(request.accountId());
                if (account == null) throw new java.io.IOException("The requested account no longer exists");
                AccountStore.Session session = new MicrosoftAuthClient(accounts, config.home).refresh(account.id());
                VersionStore.Installed version = config.installed(instance);
                if (version == null && !instance.runtimeProfile().equals(config.metadata.toAbsolutePath().normalize()))
                    throw new java.io.IOException("The instance's Minecraft version is no longer installed");
                String[] gameArgs = LaunchArguments.build(base.toArray(String[]::new), instance, session, version);
                config.recordPlay(instance);
                System.out.println("launcher: starting instance " + instance.id() + " ("
                        + config.describe(instance) + ")");
                gameStarted = true;
                startGame(version != null ? version.mainClass() : config.gameMainClass, gameArgs);
                return;
            }
        } catch (java.io.IOException | RuntimeException failed) {
            try { new LaunchPreparation(config).rollback(); }
            catch (java.io.IOException recovery) { System.err.println("launcher: runner settings need recovery"); }
            // Authentication errors are sanitized by its backend. Never print arguments or tokens.
            notice = "Cannot start: " + failed.getMessage();
            System.err.println("launcher: launch preparation failed");
            // Past authentication: the game's own failure, whose causes name what went wrong.
            if (gameStarted) failed.printStackTrace();
        }
        LauncherScreen screen = new LauncherScreen(config);
        if (notice != null) screen.showLaunchError(notice);
        screen.run();
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
        // The game is over once its main returns: a quit, or a failure Minecraft only logged (such as window
        // creation). Leftover non-daemon threads would otherwise keep a dark application open.
        System.out.println("launcher: game main returned; closing the application");
        System.exit(0);
    }
}
