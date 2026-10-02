package runner.lwjgl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static runner.lwjgl.LaunchPlanException.Category.BUNDLE_INVALID;

/**
 * Validated {@code graphics-bundle.json} entry for one profile.
 *
 * <p>Schema version 1: {@code schema_version}; {@code files}, relative path to SHA-256;
 * {@code profiles}, entries with {@code id}, exact {@code modules},
 * {@code java_adapter_paths}, {@code native_library_paths}, {@code glfw_operations} and
 * {@code gl_features}. Every listed file must exist below the bundle root, must not be
 * a symlink, and must match its hash. Unknown fields and schema versions fail closed.
 */
public record GraphicsBundle(Path root, LwjglProfile profile, List<Path> javaAdapters,
        List<Path> nativeLibraries, List<String> glfwOperations, List<String> glFeatures) {
    public static final String MANIFEST = "graphics-bundle.json";
    public static final int SCHEMA_VERSION = 1;
    private static final Set<String> ROOT_KEYS = Set.of("schema_version", "files", "profiles");
    private static final Set<String> PROFILE_KEYS = Set.of("id", "modules", "java_adapter_paths",
            "native_library_paths", "glfw_operations", "gl_features");

    public GraphicsBundle {
        javaAdapters = List.copyOf(javaAdapters);
        nativeLibraries = List.copyOf(nativeLibraries);
        glfwOperations = List.copyOf(glfwOperations);
        glFeatures = List.copyOf(glFeatures);
    }

    public static GraphicsBundle load(Path bundleRoot, LwjglProfile profile) throws LaunchPlanException {
        Path realRoot;
        try {
            realRoot = bundleRoot.toRealPath();
        } catch (IOException failure) {
            throw new LaunchPlanException(BUNDLE_INVALID, "graphics bundle " + bundleRoot
                    + " is unavailable: " + failure.getMessage());
        }
        Object parsed;
        try {
            parsed = JsonReader.parse(realRoot.resolve(MANIFEST));
        } catch (LaunchPlanException failure) {
            throw new LaunchPlanException(BUNDLE_INVALID, MANIFEST + ": " + failure.getMessage());
        }
        Map<String, Object> manifest = JsonFields.object(parsed, MANIFEST, BUNDLE_INVALID);
        JsonFields.onlyKeys(manifest, ROOT_KEYS, MANIFEST, BUNDLE_INVALID);
        if (!manifest.containsKey("schema_version")
                || JsonFields.nonNegativeLong(manifest.get("schema_version"), "schema_version", BUNDLE_INVALID)
                        != SCHEMA_VERSION)
            throw new LaunchPlanException(BUNDLE_INVALID, "schema_version must be " + SCHEMA_VERSION);

        Map<String, Path> files = verifyFiles(realRoot,
                JsonFields.object(manifest.get("files"), "files", BUNDLE_INVALID));

        List<Object> profiles = JsonFields.array(manifest.get("profiles"), "profiles", BUNDLE_INVALID);
        Set<String> ids = new HashSet<>();
        Map<String, Object> match = null;
        for (int i = 0; i < profiles.size(); ++i) {
            String where = "profiles[" + i + "]";
            Map<String, Object> entry = JsonFields.object(profiles.get(i), where, BUNDLE_INVALID);
            JsonFields.onlyKeys(entry, PROFILE_KEYS, where, BUNDLE_INVALID);
            String id = JsonFields.string(entry.get("id"), where + ".id", BUNDLE_INVALID);
            if (!ids.add(id)) throw new LaunchPlanException(BUNDLE_INVALID, "duplicate profile id " + id);
            if (id.equals(profile.id())) match = entry;
        }
        if (match == null)
            throw new LaunchPlanException(BUNDLE_INVALID, "bundle does not provide profile " + profile.id());

        String where = "profile " + profile.id();
        Map<String, String> modules = new TreeMap<>();
        for (Map.Entry<String, Object> module : JsonFields.object(match.get("modules"),
                where + ".modules", BUNDLE_INVALID).entrySet())
            modules.put(module.getKey(), JsonFields.string(module.getValue(),
                    where + ".modules." + module.getKey(), BUNDLE_INVALID));
        if (!modules.equals(profile.modules()))
            throw new LaunchPlanException(BUNDLE_INVALID, where + " modules " + modules
                    + " differ from the registered " + profile.modules());
        return new GraphicsBundle(realRoot, profile,
                listedFiles(match.get("java_adapter_paths"), files, where + ".java_adapter_paths"),
                listedFiles(match.get("native_library_paths"), files, where + ".native_library_paths"),
                names(match.get("glfw_operations"), where + ".glfw_operations"),
                names(match.get("gl_features"), where + ".gl_features"));
    }

    private static Map<String, Path> verifyFiles(Path realRoot, Map<String, Object> declared)
            throws LaunchPlanException {
        if (declared.isEmpty()) throw new LaunchPlanException(BUNDLE_INVALID, "files is empty");
        Map<String, Path> files = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : declared.entrySet()) {
            String relative = entry.getKey();
            String where = "files." + relative;
            String expected = JsonFields.string(entry.getValue(), where, BUNDLE_INVALID);
            if (!JsonFields.lowercaseHex(expected, 64))
                throw new LaunchPlanException(BUNDLE_INVALID, where + " is not 64 lowercase hex digits");
            if (relative.equals(MANIFEST))
                throw new LaunchPlanException(BUNDLE_INVALID, "files may not list " + MANIFEST);
            Path file = ArtifactFiles.confined(realRoot, relative, where, BUNDLE_INVALID);
            if (Files.isSymbolicLink(file))
                throw new LaunchPlanException(BUNDLE_INVALID, where + " is a symbolic link");
            String actual;
            try {
                actual = ArtifactFiles.digests(file, "SHA-256")[0];
            } catch (IOException failure) {
                throw new LaunchPlanException(BUNDLE_INVALID, where + " cannot be read: " + failure.getMessage());
            }
            if (!actual.equals(expected))
                throw new LaunchPlanException(BUNDLE_INVALID, where + " SHA-256 " + actual
                        + " differs from declared " + expected);
            files.put(relative, file);
        }
        return files;
    }

    private static List<Path> listedFiles(Object value, Map<String, Path> files, String where)
            throws LaunchPlanException {
        List<Path> result = new ArrayList<>();
        for (String relative : names(value, where)) {
            Path file = files.get(relative);
            if (file == null)
                throw new LaunchPlanException(BUNDLE_INVALID, where + " names " + relative + " which files does not list");
            result.add(file);
        }
        return result;
    }

    private static List<String> names(Object value, String where) throws LaunchPlanException {
        List<Object> array = JsonFields.array(value, where, BUNDLE_INVALID);
        if (array.isEmpty()) throw new LaunchPlanException(BUNDLE_INVALID, where + " is empty");
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < array.size(); ++i) {
            String name = JsonFields.string(array.get(i), where + "[" + i + "]", BUNDLE_INVALID);
            if (!seen.add(name)) throw new LaunchPlanException(BUNDLE_INVALID, where + " repeats " + name);
            result.add(name);
        }
        return result;
    }
}
