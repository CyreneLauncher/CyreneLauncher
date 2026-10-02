package runner;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/** Loads a user supplied JAR from the writable Orbis application data area. */
public final class Main {
    private static final Path ROOT = Path.of(System.getProperty(
            "orbis.runner.root", "/data/orbis-jdk/runner"));
    private static final Path TARGET = Path.of(System.getProperty(
            "runner.jar", ROOT.resolve("target.jar").toString()));
    private static final Path OUTPUT_LOG = Path.of(System.getProperty(
            "runner.output.log", ROOT.resolve("runner-output.log").toString()));
    private static final Path ARGUMENTS = ROOT.resolve("app-args.txt");
    private static final Path MAIN_OVERRIDE = ROOT.resolve("main-class.txt");

    private Main() {}

    public static void main(String[] ignored) throws Throwable {
        DebugLog debug = new DebugLog();
        Class<?> desktopRuntime = null;
        debug.write("bootstrap ENTER root=" + ROOT + " target=" + TARGET
                + " output=" + OUTPUT_LOG + " launcherArguments=" + (ignored == null ? 0 : ignored.length));
        try {
            boundary(debug, "bootstrap directories path=" + ROOT.resolve("logs"), () -> {
                Files.createDirectories(ROOT.resolve("logs"));
                return null;
            });
            boundary(debug, "output setup path=" + OUTPUT_LOG + " limit=262144 screen="
                    + Boolean.getBoolean("orbis.runner.screen.enabled"), () -> {
                OutputStream destinations = new BoundedLogOutputStream(
                        Files.newOutputStream(OUTPUT_LOG,
                                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE), 256 * 1024);
                if (Boolean.getBoolean("orbis.runner.screen.enabled"))
                    destinations = new TeeOutputStream(destinations,
                            new NativeConsoleOutputStream());
                System.setOut(new PrintStream(destinations, true, StandardCharsets.UTF_8));
                System.setErr(new PrintStream(destinations, true, StandardCharsets.UTF_8));
                if (Boolean.getBoolean("orbis.runner.screen.enabled"))
                    System.setIn(new NativeConsoleInputStream());
                return null;
            });
            System.out.println("runner: entered Java bootstrap");
            System.out.println("runner: target=" + TARGET);
            System.out.println("runner: Java output log=" + OUTPUT_LOG + " (max 256 KiB)");

            boundary(debug, "JAR validation path=" + TARGET, () -> {
                if (!Files.isRegularFile(TARGET))
                    throw new IOException("Upload a JAR to " + TARGET);
                return null;
            });
            boolean gpuMode = "gpu".equals(System.getProperty("runner.display", "console"));
            String metadataMain = gpuMode
                    ? boundary(debug, "gpu launch preparation", runner.gpu.GpuLaunch::prepare) : null;
            String mainClass = readMainClass(debug, metadataMain);
            List<String> args = readArguments(debug);
            System.out.println("runner: main-class=" + mainClass + " arguments=" + args.size());

            URL jarUrl = boundary(debug, "JAR URL path=" + TARGET,
                    () -> TARGET.toUri().toURL());
            // Application threads can continue loading after main returns.
            // Keep their loader available for the lifetime of this JVM.
            boolean desktopMode = "desktop".equals(System.getProperty("runner.display", "console"));
            ClassLoader loader;
            if (desktopMode) {
                desktopRuntime = boundary(debug, "desktop runtime lookup",
                        () -> Class.forName("runner.desktop.DesktopRuntime", true,
                                ClassLoader.getSystemClassLoader()));
                Class<?> runtimeClass = desktopRuntime;
                invokeDesktop(debug, runtimeClass, "initialize");
                loader = boundary(debug, "desktop application classloader create url=" + jarUrl,
                        () -> (ClassLoader) invokeDesktopMethod(runtimeClass,
                                "createApplicationLoader", new Class<?>[]{URL[].class},
                                new Object[]{new URL[]{jarUrl}}));
                invokeDesktop(debug, runtimeClass, "start");
            } else {
                loader = boundary(debug, "system application classloader use url=" + jarUrl,
                        ClassLoader::getSystemClassLoader);
            }
            try {
                boundary(debug, "context classloader setup", () -> {
                    Thread.currentThread().setContextClassLoader(loader);
                    return null;
                });
                Class<?> target = boundary(debug, "Class.forName class=" + mainClass
                        + " initialize=true", () -> Class.forName(mainClass, true, loader));
                java.lang.reflect.Method main = boundary(debug,
                        "main method lookup class=" + mainClass,
                        () -> target.getMethod("main", String[].class));
                boundary(debug, "main invoke class=" + mainClass + " arguments=" + args.size(),
                        () -> main.invoke(null, (Object) args.toArray(String[]::new)));
            } catch (java.lang.reflect.InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                debug.failure("application main exception", cause);
                System.err.println("runner: application threw " + cause);
                cause.printStackTrace(System.err);
                throw cause;
            }
            debug.write("application main RETURN class=" + mainClass);
            System.out.println("runner: application main returned");
            debug.write("bootstrap RETURN");
        } catch (Throwable failure) {
            debug.failure("bootstrap", failure);
            if (desktopRuntime != null) {
                try {
                    invokeDesktop(debug, desktopRuntime, "shutdown");
                } catch (Throwable shutdownFailure) {
                    if (shutdownFailure != failure) failure.addSuppressed(shutdownFailure);
                }
            }
            throw failure;
        }
    }

    private static void invokeDesktop(DebugLog debug, Class<?> runtime, String method)
            throws Throwable {
        boundary(debug, "desktop runtime " + method, () -> {
            invokeDesktopMethod(runtime, method, new Class<?>[0], new Object[0]);
            return null;
        });
    }

    private static Object invokeDesktopMethod(Class<?> runtime, String method,
            Class<?>[] parameterTypes, Object[] arguments) throws Throwable {
        try {
            return runtime.getMethod(method, parameterTypes).invoke(null, arguments);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    /** main-class.txt wins; otherwise GPU metadata's mainClass, else the JAR manifest. */
    private static String readMainClass(DebugLog debug, String metadataMain) throws Throwable {
        String override = boundary(debug, "main override read path=" + MAIN_OVERRIDE, () -> {
            if (!Files.isRegularFile(MAIN_OVERRIDE)) return "";
            return Files.readString(MAIN_OVERRIDE, StandardCharsets.UTF_8).trim();
        });
        debug.write("main override result present=" + !override.isEmpty() + " main-class=" + override);
        if (!override.isEmpty()) return override;
        if (metadataMain != null) {
            debug.write("metadata main-class=" + metadataMain);
            return metadataMain;
        }
        return boundary(debug, "JAR manifest read path=" + TARGET, () -> {
            try (JarFile jar = new JarFile(TARGET.toFile())) {
                Manifest manifest = jar.getManifest();
                if (manifest == null) throw new IOException("JAR has no manifest");
                String value = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
                if (value == null || value.isBlank())
                    throw new IOException("JAR manifest has no Main-Class; set main-class.txt");
                debug.write("JAR manifest main-class=" + value);
                return value;
            }
        });
    }

    private static List<String> readArguments(DebugLog debug) throws Throwable {
        List<String> args = boundary(debug, "argument file read path=" + ARGUMENTS, () -> {
            List<String> values = new ArrayList<>();
            if (!Files.isRegularFile(ARGUMENTS)) return values;
            for (String line : Files.readAllLines(ARGUMENTS, StandardCharsets.UTF_8)) {
                if (!line.isEmpty() && !line.startsWith("#")) values.add(line);
            }
            return values;
        });
        debug.write("argument file result count=" + args.size());
        return args;
    }

    @FunctionalInterface
    private interface Action<T> {
        T run() throws Throwable;
    }

    private static <T> T boundary(DebugLog debug, String name, Action<T> action)
            throws Throwable {
        debug.write(name + " ENTER");
        try {
            T value = action.run();
            debug.write(name + " RETURN");
            return value;
        } catch (Throwable failure) {
            debug.failure(name, failure);
            throw failure;
        }
    }

    /** Independent of bounded application output; close every entry to retain boundaries. */
    private static final class DebugLog {
        private Path path;

        private DebugLog() {
            try {
                String value = System.getProperty("runner.debug.log", "");
                if (!value.isEmpty()) {
                    Path configured = Path.of(value);
                    if (!configured.isAbsolute())
                        throw new IOException("runner.debug.log must be an absolute path: " + value);
                    path = configured;
                }
            } catch (Exception failure) {
                System.err.println("runner: debug log setup failed: " + failure);
            }
        }

        private void failure(String name, Throwable failure) {
            if (path == null) return;
            try {
                StringWriter stack = new StringWriter();
                failure.printStackTrace(new PrintWriter(stack));
                write(name + " FAILURE " + stack);
            } catch (Exception loggingFailure) {
                System.err.println("runner: debug exception logging failed: " + loggingFailure);
            }
        }

        private synchronized void write(String message) {
            if (path == null) return;
            try {
                if (Files.exists(path) && Files.size(path) >= 8L * 1024 * 1024) return;
            } catch (IOException ignored) { return; }
            try (OutputStream output = Files.newOutputStream(path,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE)) {
                String entry = Instant.now() + " [thread=" + Thread.currentThread().getName()
                        + "] " + message + "\n";
                output.write(entry.getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (Exception failure) {
                System.err.println("runner: debug log write failed path=" + path + ": " + failure);
            }
        }
    }

    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream first;
        private final OutputStream second;

        private TeeOutputStream(OutputStream first, OutputStream second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public synchronized void write(int value) throws IOException {
            first.write(value);
            second.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length)
                throws IOException {
            first.write(bytes, offset, length);
            second.write(bytes, offset, length);
        }

        @Override
        public synchronized void flush() throws IOException {
            first.flush();
            second.flush();
        }
    }

    private static final class BoundedLogOutputStream extends OutputStream {
        private static final byte[] TRUNCATED =
                "\nrunner: output log truncated at 256 KiB\n"
                        .getBytes(StandardCharsets.UTF_8);
        private final OutputStream output;
        private final long limit;
        private long written;
        private boolean marked;

        private BoundedLogOutputStream(OutputStream output, long limit) {
            this.output = output;
            this.limit = limit;
        }

        @Override
        public synchronized void write(int value) throws IOException {
            if (written < limit) {
                output.write(value);
                written++;
            } else {
                markTruncated();
            }
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length)
                throws IOException {
            int count = (int) Math.min(length, Math.max(0, limit - written));
            if (count > 0) {
                output.write(bytes, offset, count);
                written += count;
            }
            if (count < length) markTruncated();
        }

        private void markTruncated() throws IOException {
            if (!marked) {
                output.write(TRUNCATED);
                marked = true;
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            output.flush();
        }
    }
}
