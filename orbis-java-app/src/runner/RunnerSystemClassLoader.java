package runner;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** System loader that adds the configured application JAR to the normal class path. */
public final class RunnerSystemClassLoader extends URLClassLoader {
    public RunnerSystemClassLoader(ClassLoader parent) throws IOException {
        super(applicationJar(), parent);
    }

    /** GPU mode appends the graphics adapter and resolved libraries before application classes load. */
    public void appendToClassPath(URL url) {
        addURL(url);
    }

    private static URL[] applicationJar() throws IOException {
        String configured = System.getProperty("runner.jar");
        if (configured == null || configured.isEmpty()) {
            throw new IOException("runner.jar is not configured for the application class loader");
        }
        return new URL[]{Path.of(configured).toUri().toURL()};
    }
}
