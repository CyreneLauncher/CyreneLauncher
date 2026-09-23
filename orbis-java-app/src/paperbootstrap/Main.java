package paperbootstrap;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Loads the pre-expanded Paper payload without running Paperclip on Orbis. */
public final class Main {
    private static final String DEFAULT_PAPER_ROOT = "/app0/paper";
    private static final String CLASSPATH_LIST = "/app0/paper/classpath.list";
    private static final String PAPER_MAIN = "org.bukkit.craftbukkit.Main";

    private Main() {
    }

    public static void main(String[] ignored) throws Throwable {
        Path work = Path.of(System.getProperty("user.dir"));
        Files.createDirectories(work);
        Files.createDirectories(work.resolve("logs"));
        Files.createDirectories(work.resolve("plugins"));
        Files.createDirectories(work.resolve("native"));
        Files.createDirectories(work.resolve("tmp"));

        Path root = Path.of(System.getProperty("orbis.paper.root", DEFAULT_PAPER_ROOT));
        Path list = root.resolve("classpath.list");
        if (DEFAULT_PAPER_ROOT.equals(root.toString())) {
            list = Path.of(CLASSPATH_LIST);
        }

        List<URL> urls = new ArrayList<>();
        Set<Path> unique = new HashSet<>();
        for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
            String entry = line.trim();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            Path relative = Path.of(entry);
            if (relative.isAbsolute() || entry.contains("..")) {
                throw new IllegalArgumentException("unsafe Paper classpath entry: " + entry);
            }
            Path jar = root.resolve(relative).normalize();
            if (!jar.startsWith(root.normalize()) || !unique.add(jar)) {
                throw new IllegalArgumentException("duplicate Paper classpath entry: " + entry);
            }
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException("missing Paper classpath entry: " + jar);
            }
            urls.add(jar.toUri().toURL());
        }
        if (urls.isEmpty()) {
            throw new IllegalStateException("Paper classpath is empty: " + list);
        }

        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new), ClassLoader.getSystemClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> mainClass = Class.forName(PAPER_MAIN, true, loader);
            Method main = mainClass.getMethod("main", String[].class);
            String[] arguments = {"nogui", "--nojline", "--noconsole"};
            try {
                main.invoke(null, (Object) arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}
