package runner.lwjgl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static runner.lwjgl.LaunchPlanException.Category.ARTIFACT_MISMATCH;
import static runner.lwjgl.LaunchPlanException.Category.ARTIFACT_MISSING;
import static runner.lwjgl.LaunchPlanException.Category.LWJGL_CONFLICT;
import static runner.lwjgl.LaunchPlanException.Category.METADATA_CONFLICT;
import static runner.lwjgl.LaunchPlanException.Category.METADATA_UNSUPPORTED;

/**
 * Resolves a local Mojang-style version JSON into a verified launch plan.
 *
 * <p>Supported: the {@code libraries} format used by Minecraft 1.18.2, with rules
 * conditioned on {@code os.name} and {@code os.arch}. Native classifiers are recorded
 * and never selected; Orbis natives come from the graphics bundle. Unsupported fields
 * and rule conditions fail rather than being treated as allow or deny. Game argument
 * rules are not interpreted here.
 */
public final class VersionMetadataResolver {
    private static final Set<String> LIBRARY_KEYS = Set.of("name", "downloads", "rules", "natives", "extract");
    private static final Set<String> DOWNLOAD_KEYS = Set.of("artifact", "classifiers");
    private static final Set<String> ARTIFACT_KEYS = Set.of("path", "sha1", "sha256", "size", "url");
    private static final Set<String> RULE_KEYS = Set.of("action", "os");
    private static final Set<String> OS_KEYS = Set.of("name", "arch");
    private static final Pattern COORDINATE_PART = Pattern.compile("[A-Za-z0-9._+-]+");
    private static final Pattern CLASS_NAME =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final String LWJGL_GROUP = "org.lwjgl";

    private VersionMetadataResolver() {}

    public static ResolvedLaunchPlan resolve(Path versionJson, Path librariesRoot,
            MetadataPlatform platform) throws LaunchPlanException {
        Objects.requireNonNull(platform, "platform");
        Map<String, Object> root = JsonFields.object(JsonReader.parse(versionJson),
                "version metadata", METADATA_UNSUPPORTED);
        if (root.containsKey("inheritsFrom"))
            throw new LaunchPlanException(METADATA_UNSUPPORTED,
                    "inheritsFrom (mod loader inheritance) is not supported");
        String id = JsonFields.string(root.get("id"), "id", METADATA_UNSUPPORTED);
        String mainClass = JsonFields.string(root.get("mainClass"), "mainClass", METADATA_UNSUPPORTED);
        if (!CLASS_NAME.matcher(mainClass).matches())
            throw new LaunchPlanException(METADATA_UNSUPPORTED, "mainClass is not a class name: " + mainClass);
        List<Object> libraries = JsonFields.array(root.get("libraries"), "libraries", METADATA_UNSUPPORTED);

        Path realRoot;
        try {
            realRoot = librariesRoot.toRealPath();
        } catch (IOException failure) {
            throw new LaunchPlanException(ARTIFACT_MISSING, "libraries root " + librariesRoot
                    + " is unavailable: " + failure.getMessage());
        }
        if (!Files.isDirectory(realRoot))
            throw new LaunchPlanException(ARTIFACT_MISSING, "libraries root is not a directory: " + realRoot);

        Map<String, Map<String, Object>> selected = new LinkedHashMap<>();
        Map<String, Path> classpath = new LinkedHashMap<>();
        Map<String, String> lwjglModules = new TreeMap<>();
        Map<String, String> lwjglSha1 = new TreeMap<>();
        Map<String, Path> lwjglPaths = new TreeMap<>();
        List<String> skippedNatives = new ArrayList<>();

        for (int index = 0; index < libraries.size(); ++index) {
            String where = "libraries[" + index + "]";
            Map<String, Object> library = JsonFields.object(libraries.get(index), where, METADATA_UNSUPPORTED);
            JsonFields.onlyKeys(library, LIBRARY_KEYS, where, METADATA_UNSUPPORTED);
            String name = JsonFields.string(library.get("name"), where + ".name", METADATA_UNSUPPORTED);
            String[] coordinate = coordinate(name, where);
            where += " (" + name + ")";
            if (!allowed(library.get("rules"), platform, where)) continue;
            if (!library.containsKey("downloads"))
                throw new LaunchPlanException(METADATA_UNSUPPORTED, where + " has no downloads (legacy format)");
            Map<String, Object> downloads = JsonFields.object(library.get("downloads"),
                    where + ".downloads", METADATA_UNSUPPORTED);
            JsonFields.onlyKeys(downloads, DOWNLOAD_KEYS, where + ".downloads", METADATA_UNSUPPORTED);
            recordNatives(library, downloads, name, where, skippedNatives);
            if (coordinate.length == 4 && coordinate[3].startsWith("natives-")) {
                skippedNatives.add(name);
                continue;
            }
            if (!downloads.containsKey("artifact")) continue;
            Map<String, Object> artifact = JsonFields.object(downloads.get("artifact"),
                    where + ".downloads.artifact", METADATA_UNSUPPORTED);
            JsonFields.onlyKeys(artifact, ARTIFACT_KEYS, where + ".downloads.artifact", METADATA_UNSUPPORTED);

            // 1.18.2 repeats each LWJGL coordinate: once plain, once carrying natives.
            Map<String, Object> previous = selected.putIfAbsent(name, artifact);
            if (previous != null) {
                if (!Objects.equals(previous.get("path"), artifact.get("path"))
                        || !Objects.equals(previous.get("sha1"), artifact.get("sha1"))
                        || !Objects.equals(previous.get("sha256"), artifact.get("sha256")))
                    throw new LaunchPlanException(METADATA_CONFLICT, where
                            + " is declared again with a different artifact");
                continue;
            }
            Verified verified = verify(realRoot, artifact, where);
            for (Map.Entry<String, Path> entry : classpath.entrySet())
                if (entry.getValue().equals(verified.path))
                    throw new LaunchPlanException(METADATA_CONFLICT, where + " reuses the artifact of "
                            + entry.getKey());
            classpath.put(name, verified.path);

            // An LWJGL module may come as a classified variant of its JAR: Minecraft 26.2 declares the core
            // as org.lwjgl:lwjgl:3.4.1:unsafe (the core without its Java 25 FFM classes). It is that module.
            if (coordinate[0].equals(LWJGL_GROUP)) {
                String module = LWJGL_GROUP + ":" + coordinate[1];
                String existing = lwjglModules.putIfAbsent(module, coordinate[2]);
                if (existing != null)
                    throw new LaunchPlanException(LWJGL_CONFLICT, module + " selected at both "
                            + existing + " and " + coordinate[2]);
                lwjglSha1.put(module, verified.sha1);
                lwjglPaths.put(module, verified.path);
            }
        }
        return new ResolvedLaunchPlan(id, mainClass, new ArrayList<>(classpath.values()),
                lwjglModules, lwjglSha1, lwjglPaths, skippedNatives);
    }

    private static String[] coordinate(String name, String where) throws LaunchPlanException {
        String[] parts = name.split(":", -1);
        if (parts.length < 3 || parts.length > 4)
            throw new LaunchPlanException(METADATA_UNSUPPORTED, where + " name is not group:artifact:version[:classifier]: " + name);
        for (String part : parts)
            if (!COORDINATE_PART.matcher(part).matches())
                throw new LaunchPlanException(METADATA_UNSUPPORTED, where + " name has an invalid segment: " + name);
        return parts;
    }

    /** Mojang semantics: no rules allows; otherwise the last matching rule decides, default deny. */
    private static boolean allowed(Object rulesValue, MetadataPlatform platform, String where)
            throws LaunchPlanException {
        if (rulesValue == null) return true;
        List<Object> rules = JsonFields.array(rulesValue, where + ".rules", METADATA_UNSUPPORTED);
        boolean allowed = false;
        for (int i = 0; i < rules.size(); ++i) {
            String ruleWhere = where + ".rules[" + i + "]";
            Map<String, Object> rule = JsonFields.object(rules.get(i), ruleWhere, METADATA_UNSUPPORTED);
            JsonFields.onlyKeys(rule, RULE_KEYS, ruleWhere, METADATA_UNSUPPORTED);
            String action = JsonFields.string(rule.get("action"), ruleWhere + ".action", METADATA_UNSUPPORTED);
            if (!action.equals("allow") && !action.equals("disallow"))
                throw new LaunchPlanException(METADATA_UNSUPPORTED, ruleWhere + " action is " + action);
            boolean matches = true;
            if (rule.containsKey("os")) {
                Map<String, Object> os = JsonFields.object(rule.get("os"), ruleWhere + ".os", METADATA_UNSUPPORTED);
                JsonFields.onlyKeys(os, OS_KEYS, ruleWhere + ".os", METADATA_UNSUPPORTED);
                if (os.containsKey("name"))
                    matches = JsonFields.string(os.get("name"), ruleWhere + ".os.name", METADATA_UNSUPPORTED)
                            .equals(platform.osName());
                if (os.containsKey("arch"))
                    matches &= JsonFields.string(os.get("arch"), ruleWhere + ".os.arch", METADATA_UNSUPPORTED)
                            .equals(platform.osArch());
            }
            if (matches) allowed = action.equals("allow");
        }
        return allowed;
    }

    private static void recordNatives(Map<String, Object> library, Map<String, Object> downloads,
            String name, String where, List<String> skippedNatives) throws LaunchPlanException {
        if (library.containsKey("natives"))
            JsonFields.object(library.get("natives"), where + ".natives", METADATA_UNSUPPORTED);
        if (!downloads.containsKey("classifiers")) return;
        Map<String, Object> classifiers = JsonFields.object(downloads.get("classifiers"),
                where + ".downloads.classifiers", METADATA_UNSUPPORTED);
        for (String classifier : classifiers.keySet()) {
            if (!COORDINATE_PART.matcher(classifier).matches())
                throw new LaunchPlanException(METADATA_UNSUPPORTED, where + " has an invalid classifier " + classifier);
            skippedNatives.add(name + ":" + classifier);
        }
    }

    private record Verified(Path path, String sha1) {}

    private static Verified verify(Path realRoot, Map<String, Object> artifact, String where)
            throws LaunchPlanException {
        String relative = JsonFields.string(artifact.get("path"), where + ".path", METADATA_UNSUPPORTED);
        String sha1 = artifact.containsKey("sha1")
                ? JsonFields.string(artifact.get("sha1"), where + ".sha1", METADATA_UNSUPPORTED) : null;
        String sha256 = artifact.containsKey("sha256")
                ? JsonFields.string(artifact.get("sha256"), where + ".sha256", METADATA_UNSUPPORTED) : null;
        if (sha1 != null && !JsonFields.lowercaseHex(sha1, 40))
            throw new LaunchPlanException(METADATA_UNSUPPORTED, where + ".sha1 is not 40 lowercase hex digits");
        if (sha256 != null && !JsonFields.lowercaseHex(sha256, 64))
            throw new LaunchPlanException(METADATA_UNSUPPORTED, where + ".sha256 is not 64 lowercase hex digits");
        Path file = ArtifactFiles.confined(realRoot, relative, where, ARTIFACT_MISSING);
        try {
            if (artifact.containsKey("size")) {
                long size = JsonFields.nonNegativeLong(artifact.get("size"), where + ".size", METADATA_UNSUPPORTED);
                long actual = Files.size(file);
                if (actual != size)
                    throw new LaunchPlanException(ARTIFACT_MISMATCH, where + " size " + actual
                            + " differs from declared " + size + ": " + file);
            }
            String[] actual = ArtifactFiles.digests(file, "SHA-1", "SHA-256");
            if (sha1 != null && !sha1.equals(actual[0]))
                throw new LaunchPlanException(ARTIFACT_MISMATCH, where + " SHA-1 " + actual[0]
                        + " differs from declared " + sha1 + ": " + file);
            if (sha256 != null && !sha256.equals(actual[1]))
                throw new LaunchPlanException(ARTIFACT_MISMATCH, where + " SHA-256 " + actual[1]
                        + " differs from declared " + sha256 + ": " + file);
            return new Verified(file, actual[0]);
        } catch (IOException failure) {
            throw new LaunchPlanException(ARTIFACT_MISSING, where + " cannot be read: " + failure.getMessage());
        }
    }
}
