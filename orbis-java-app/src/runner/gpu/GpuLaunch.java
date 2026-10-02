package runner.gpu;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import runner.RunnerSystemClassLoader;
import runner.lwjgl.GraphicsBundle;
import runner.lwjgl.LaunchPlanException;
import runner.lwjgl.LwjglProfileRegistry;
import runner.lwjgl.MetadataPlatform;
import runner.lwjgl.ResolvedLaunchPlan;
import runner.lwjgl.VersionMetadataResolver;

/**
 * display=gpu launch preparation, before any application or LWJGL class loads: resolve the
 * version metadata, select the tested LWJGL profile, validate the graphics bundle, then
 * extend the system class path as adapter JARs, resolved libraries (without lwjgl-glfw,
 * which the adapter replaces) after the target JAR. Every failure is a categorized
 * LaunchPlanException raised before native code loads.
 */
public final class GpuLaunch {
    private GpuLaunch() {}

    /** Returns the metadata main class; diagnostics go to standard output. */
    public static String prepare() throws LaunchPlanException {
        Path metadata = configured("runner.gpu.metadata");
        Path libraries = configured("runner.gpu.libraries");
        Path bundleRoot = configured("runner.graphics.bundle");
        ResolvedLaunchPlan plan = VersionMetadataResolver.resolve(metadata, libraries, MetadataPlatform.ORBIS);
        System.out.println("runner: gpu metadata=" + metadata + " id=" + plan.versionId()
                + " main=" + plan.mainClass() + " libraries=" + plan.classpath().size()
                + " skipped-natives=" + plan.skippedNatives().size());
        System.out.println("runner: gpu requested LWJGL " + plan.lwjglModules());
        GraphicsBundle bundle = LwjglProfileRegistry.select(plan, bundleRoot);
        System.out.println("runner: gpu profile=" + bundle.profile().id() + " bundle=" + bundle.root()
                + " adapters=" + bundle.javaAdapters().size() + " natives=" + bundle.nativeLibraries().size());

        ClassLoader system = ClassLoader.getSystemClassLoader();
        if (!(system instanceof RunnerSystemClassLoader))
            throw new IllegalStateException("display=gpu requires runner.RunnerSystemClassLoader, found "
                    + system.getClass().getName());
        RunnerSystemClassLoader loader = (RunnerSystemClassLoader) system;
        Path replacedGlfw = plan.lwjglPaths().get("org.lwjgl:lwjgl-glfw");
        List<Path> entries = new ArrayList<>(bundle.javaAdapters());
        for (Path library : plan.classpath())
            if (!library.equals(replacedGlfw)) entries.add(library);
        for (Path entry : entries) {
            try {
                loader.appendToClassPath(entry.toUri().toURL());
            } catch (MalformedURLException failure) {
                throw new IllegalStateException("class path entry " + entry, failure);
            }
        }
        Path nativeDirectory = bundle.nativeLibraries().get(0).getParent();
        // LWJGL loads liblwjgl*.so from here; GL uses the EGL provider, never libGL; no jemalloc native.
        System.setProperty("org.lwjgl.librarypath", nativeDirectory.toString());
        System.setProperty("org.lwjgl.opengl.explicitInit", "true");
        System.setProperty("org.lwjgl.system.allocator", "system");
        System.out.println("runner: gpu class path +" + entries.size() + " entries (lwjgl-glfw replaced), natives="
                + nativeDirectory);
        return plan.mainClass();
    }

    private static Path configured(String property) {
        String value = System.getProperty(property, "");
        if (value.isEmpty() || !Path.of(value).isAbsolute())
            throw new IllegalStateException(property + " must be an absolute path, got '" + value + "'");
        return Path.of(value);
    }
}
