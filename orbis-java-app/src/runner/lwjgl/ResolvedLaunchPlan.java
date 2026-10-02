package runner.lwjgl;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic result of metadata resolution, produced before any native code loads.
 *
 * @param versionId      metadata {@code id}
 * @param mainClass      metadata {@code mainClass}
 * @param classpath      verified common JARs in metadata order, without duplicates
 * @param lwjglModules   {@code org.lwjgl:<module>} to selected version, sorted by module
 * @param lwjglSha1      {@code org.lwjgl:<module>} to the selected Java artifact SHA-1
 * @param lwjglPaths     {@code org.lwjgl:<module>} to the selected Java artifact, a classpath entry
 * @param skippedNatives native classifier artifacts declared by metadata and not used
 */
public record ResolvedLaunchPlan(String versionId, String mainClass, List<Path> classpath,
        Map<String, String> lwjglModules, Map<String, String> lwjglSha1,
        Map<String, Path> lwjglPaths, List<String> skippedNatives) {
    public ResolvedLaunchPlan {
        classpath = List.copyOf(classpath);
        lwjglModules = Collections.unmodifiableMap(new TreeMap<>(lwjglModules));
        lwjglSha1 = Collections.unmodifiableMap(new TreeMap<>(lwjglSha1));
        lwjglPaths = Collections.unmodifiableMap(new TreeMap<>(lwjglPaths));
        skippedNatives = List.copyOf(skippedNatives);
    }
}
