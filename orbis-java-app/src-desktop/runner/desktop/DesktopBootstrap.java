package runner.desktop;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.net.URL;
import java.net.JarURLConnection;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.LinkOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Initializes the one supported desktop session before application AWT use. */
public final class DesktopBootstrap {
    private static final String TOOLKIT = "com.github.caciocavallosilano.cacio.ctc.CTCToolkit";
    private static final String GRAPHICS_ENV = "com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment";
    private static final String CACIO_PREFIX = "com.github.caciocavallosilano.cacio.";
    private static final Object LOCK = new Object();
    private static volatile DesktopSession session;
    private static volatile Throwable failure;

    private DesktopBootstrap() { }

    public static DesktopSession initialize() {
        synchronized (LOCK) {
            if (session != null) return session;
            if (failure != null) throw initializationFailure(failure);
            try {
                DesktopArtifacts.validate(Path.of(System.getProperty("java.home")),
                        Path.of(System.getProperty("runner.desktop.bundle")));
                setOrCheck("java.awt.headless", "false");
                setOrCheck("cacio.managed.screensize", "1280x720");
                setOrCheck("orbis.awt.toolkit", TOOLKIT);
                setOrCheck("orbis.awt.graphicsenv", GRAPHICS_ENV);
                setOrCheck("swing.defaultlaf", "javax.swing.plaf.metal.MetalLookAndFeel");
                String fontManager = System.getProperty("sun.font.fontmanager");
                if (fontManager != null && !fontManager.isBlank()
                        && !"sun.font.OrbisFontManager".equals(fontManager)) {
                    throw new IllegalStateException("sun.font.fontmanager must select OrbisFontManager");
                }
                String cacioFontManager = System.getProperty("cacio.font.fontmanager");
                if (cacioFontManager != null && !cacioFontManager.isBlank()) {
                    throw new IllegalStateException("cacio.font.fontmanager is unsupported; use OrbisFontManager");
                }

                // Create GE first: CTCGraphicsEnvironment registers its surface
                // manager factory before CacioToolkit constructs its screen.
                GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
                Toolkit toolkit = Toolkit.getDefaultToolkit();
                if (!GraphicsEnvironment.class.isInstance(ge)
                        || !Toolkit.class.isInstance(toolkit)
                        || !GRAPHICS_ENV.equals(ge.getClass().getName())
                        || !TOOLKIT.equals(toolkit.getClass().getName())) {
                    throw new IllegalStateException("Configured Cacio providers were not selected");
                }
                if (GraphicsEnvironment.isHeadless()) {
                    throw new IllegalStateException("Cacio desktop initialized in headless mode");
                }

                Class<?> screenClass = Class.forName(
                        "com.github.caciocavallosilano.cacio.ctc.CTCScreen", true,
                        ClassLoader.getSystemClassLoader());
                screenClass.getMethod("initializeScreen").invoke(null);
                if (!ge.getDefaultScreenDevice().getDefaultConfiguration().getBounds()
                        .equals(new java.awt.Rectangle(0, 0, 1280, 720))) {
                    throw new IllegalStateException("Cacio screen geometry is not 1280x720");
                }
                // Initialize lazy bootstrap-owned native providers before the
                // session or application loader becomes available.
                DesktopProviders.validate(Path.of(System.getProperty("java.home")));
                session = new DesktopSession(ge, toolkit);
                return session;
            } catch (Throwable ex) {
                failure = unwrap(ex);
                throw initializationFailure(failure);
            }
        }
    }

    private static void setOrCheck(String key, String value) {
        String configured = System.getProperty(key);
        if (configured != null && !configured.equals(value)) {
            throw new IllegalStateException("Conflicting " + key + " value: " + configured);
        }
        if (configured == null) System.setProperty(key, value);
    }

    private static Throwable unwrap(Throwable ex) {
        while (ex instanceof java.lang.reflect.InvocationTargetException && ex.getCause() != null) {
            ex = ex.getCause();
        }
        return ex;
    }

    private static IllegalStateException initializationFailure(Throwable cause) {
        return new IllegalStateException("Cacio desktop initialization failed; expected toolkit "
                + TOOLKIT + " and graphics environment " + GRAPHICS_ENV, cause);
    }

    /** Uses the application JAR URL already installed in the system classloader. */
    public static ClassLoader createApplicationLoader(URL[] urls) {
        if (session == null) throw new IllegalStateException("Initialize desktop before creating application loader");
        rejectReservedDefinitions(urls);
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        if (!"runner.RunnerSystemClassLoader".equals(loader.getClass().getName())) {
            throw new IllegalStateException("Runner application system classloader is not installed");
        }
        return loader;
    }

    private static void rejectReservedDefinitions(URL[] urls) {
        ArrayDeque<URL> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (URL url : urls) pending.add(url);
        while (!pending.isEmpty()) {
            URL url = pending.removeFirst();
            if (!visited.add(url.toExternalForm())) continue;
            try {
                if ("file".equalsIgnoreCase(url.getProtocol())) {
                    Path path = Path.of(url.toURI());
                    if (Files.isDirectory(path)) {
                        try {
                            inspectApplicationDirectory(path);
                        } catch (IOException ex) {
                            throw new IllegalArgumentException(
                                    "Cannot inspect application directory " + url, ex);
                        }
                    } else {
                        try (JarFile jar = new JarFile(path.toFile())) {
                            inspectApplicationJar(jar, url, pending);
                        }
                    }
                } else {
                    var connection = url.openConnection();
                    if (!(connection instanceof JarURLConnection jarConnection)) {
                        throw new IOException("Expected a JAR URL");
                    }
                    jarConnection.setUseCaches(false);
                    try (JarFile jar = jarConnection.getJarFile()) {
                        inspectApplicationJar(jar, url, pending);
                    }
                }
            } catch (IllegalArgumentException ex) {
                throw ex;
            } catch (IOException ex) {
                // URLClassPath skips an individual location it cannot open;
                // preserve that behavior for missing optional manifest entries.
                continue;
            } catch (Exception ex) {
                throw new IllegalArgumentException("Cannot inspect application URL " + url, ex);
            }
        }
    }

    private static void inspectApplicationDirectory(Path root) throws IOException {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path cursor = absoluteRoot.getRoot();
        for (Path part : absoluteRoot) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException("Symbolic link in application class path root: " + cursor);
            }
        }

        Path reserved = absoluteRoot;
        for (String part : new String[] {"com", "github", "caciocavallosilano", "cacio"}) {
            reserved = reserved.resolve(part);
            if (Files.isSymbolicLink(reserved)) {
                throw new IllegalArgumentException("Symbolic link in reserved Cacio package path: " + reserved);
            }
            if (!Files.isDirectory(reserved, LinkOption.NOFOLLOW_LINKS)) return;
        }

        Files.walkFileTree(reserved, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new IllegalArgumentException("Symbolic link in reserved Cacio package: " + directory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file)) {
                    throw new IllegalArgumentException("Symbolic link in reserved Cacio package: " + file);
                }
                rejectCacioEntry(absoluteRoot.relativize(file).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void inspectApplicationJar(JarFile jar, URL location, ArrayDeque<URL> pending)
            throws IOException {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (isReservedCacioClass(name)) {
                throw new IllegalArgumentException("Application JAR defines reserved Cacio class: " + name);
            }
        }
        String dependencies = jar.getManifest() == null ? null
                : jar.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        if (dependencies != null) {
            for (String dependency : dependencies.trim().split("\\s+")) {
                if (!dependency.isEmpty()) pending.addLast(new URL(location, dependency));
            }
        }
    }

    private static void rejectCacioEntry(String entry) {
        if (isReservedCacioClass(entry)) {
            throw new IllegalArgumentException("Application URL defines reserved Cacio class: " + entry);
        }
    }

    private static boolean isReservedCacioClass(String entry) {
        String name = entry;
        if (name.startsWith("META-INF/versions/")) {
            int versionEnd = name.indexOf('/', "META-INF/versions/".length());
            if (versionEnd >= 0) name = name.substring(versionEnd + 1);
        }
        name = name.replace('/', '.');
        return name.endsWith(".class") && name.startsWith(CACIO_PREFIX);
    }

}
