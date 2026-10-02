package runner.lwjgl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static runner.lwjgl.LaunchPlanException.Category.ARTIFACT_PATH;
import static runner.lwjgl.LaunchPlanException.Category.BUNDLE_INVALID;
import static runner.lwjgl.LaunchPlanException.Category.PROFILE_UNSUPPORTED;
import static runner.lwjgl.TestSupport.check;
import static runner.lwjgl.TestSupport.equal;
import static runner.lwjgl.TestSupport.fails;

public final class LwjglProfileRegistryTest {
    private static final LwjglProfile AUDITED = LwjglProfileRegistry.MINECRAFT_1_18_2;

    public static void main(String[] args) throws Exception {
        equal("minecraft-1.18.2-lwjgl-3.2.2-orbis-v1", AUDITED.id(), "audited profile id");
        equal(7, AUDITED.modules().size(), "seven exact coordinates");
        equal(List.of(AUDITED), LwjglProfileRegistry.profiles(), "only the audited profile is registered");

        equal(AUDITED, LwjglProfileRegistry.select(plan(AUDITED.modules(), AUDITED.javaSha1())), "exact set selected");

        Map<String, String> missing = new TreeMap<>(AUDITED.modules());
        missing.remove("org.lwjgl:lwjgl-tinyfd");
        fails(PROFILE_UNSUPPORTED, "incomplete set", () -> LwjglProfileRegistry.select(plan(missing, AUDITED.javaSha1())));
        Map<String, String> extra = new TreeMap<>(AUDITED.modules());
        extra.put("org.lwjgl:lwjgl-vulkan", "3.2.2");
        fails(PROFILE_UNSUPPORTED, "extra module", () -> LwjglProfileRegistry.select(plan(extra, AUDITED.javaSha1())));
        Map<String, String> mixedVersions = new TreeMap<>(AUDITED.modules());
        mixedVersions.put("org.lwjgl:lwjgl-glfw", "3.2.1");
        fails(PROFILE_UNSUPPORTED, "mixed versions", () -> LwjglProfileRegistry.select(plan(mixedVersions, AUDITED.javaSha1())));
        Map<String, String> unknown = new TreeMap<>();
        for (String module : AUDITED.modules().keySet()) unknown.put(module, "3.3.1");
        LaunchPlanException unknownFailure = fails(PROFILE_UNSUPPORTED, "unknown version",
                () -> LwjglProfileRegistry.select(plan(unknown, AUDITED.javaSha1())));
        check(unknownFailure.getMessage().contains(AUDITED.id()), "diagnostic names supported profiles");
        fails(PROFILE_UNSUPPORTED, "no LWJGL", () -> LwjglProfileRegistry.select(plan(Map.of(), Map.of())));
        Map<String, String> rebuilt = new TreeMap<>(AUDITED.javaSha1());
        rebuilt.put("org.lwjgl:lwjgl-opengl", "0".repeat(40));
        fails(PROFILE_UNSUPPORTED, "unaudited artifact", () -> LwjglProfileRegistry.select(plan(AUDITED.modules(), rebuilt)));

        // Resolver output feeds the registry unchanged.
        VersionMetadataResolverTest.Fixture fixture = new VersionMetadataResolverTest.Fixture().minecraftShape();
        ResolvedLaunchPlan resolved = fixture.resolve();
        LwjglProfile fixtureProfile = new LwjglProfile("fixture-3.2.2", AUDITED.modules(),
                VersionMetadataResolverTest.fixtureSha1(fixture, "3.2.2"));
        equal(fixtureProfile, LwjglProfileRegistry.select(resolved, List.of(fixtureProfile)), "resolved plan selects");
        fails(PROFILE_UNSUPPORTED, "fixture JARs are not the audited artifacts", () -> LwjglProfileRegistry.select(resolved));
        fails(PROFILE_UNSUPPORTED, "macOS track unsupported", () -> LwjglProfileRegistry.select(
                fixture.resolve(new MetadataPlatform("osx", "x86_64")), List.of(fixtureProfile)));

        bundleChecks();
        System.out.println("LwjglProfileRegistry and graphics bundle fixtures passed");
    }

    private static ResolvedLaunchPlan plan(Map<String, String> modules, Map<String, String> sha1) {
        Map<String, String> pins = new TreeMap<>(sha1);
        pins.keySet().retainAll(modules.keySet());
        return new ResolvedLaunchPlan("1.18.2", "net.minecraft.client.main.Main", List.of(), modules, pins, List.of());
    }

    private static void bundleChecks() throws Exception {
        Path bundle = validBundle();
        GraphicsBundle loaded = GraphicsBundle.load(bundle, AUDITED);
        equal(AUDITED, loaded.profile(), "bundle profile");
        equal(List.of(bundle.toRealPath().resolve("lib/lwjgl-glfw-orbis.jar")), loaded.javaAdapters(), "adapter paths");
        equal(List.of(bundle.toRealPath().resolve("lib/liblwjgl.so"), bundle.toRealPath().resolve("lib/liblwjgl_opengl.so")),
                loaded.nativeLibraries(), "native library order kept");
        equal(List.of("glfwInit", "glfwCreateWindow"), loaded.glfwOperations(), "GLFW operations");
        equal(List.of("gl32-core"), loaded.glFeatures(), "GL features");
        Path registryBundle = validBundle();
        equal(loaded.javaAdapters().size(), LwjglProfileRegistry.select(
                plan(AUDITED.modules(), AUDITED.javaSha1()), registryBundle).javaAdapters().size(), "registry validates bundle");

        expectBundle(BUNDLE_INVALID, "schema 2", m -> m.replace("\"schema_version\": 1", "\"schema_version\": 2"));
        expectBundle(BUNDLE_INVALID, "schema missing", m -> m.replace("\"schema_version\": 1,", ""));
        expectBundle(BUNDLE_INVALID, "unknown root field", m -> m.replace("\"schema_version\": 1,", "\"schema_version\": 1, \"extra\": 1,"));
        expectBundle(BUNDLE_INVALID, "unknown profile field", m -> m.replace("\"gl_features\"", "\"audio\": [], \"gl_features\""));
        expectBundle(BUNDLE_INVALID, "profile absent", m -> m.replace(AUDITED.id(), "other-profile"));
        expectBundle(BUNDLE_INVALID, "duplicate profile", m -> m.replace("\"profiles\": [", "\"profiles\": [" + profileJson() + ","));
        expectBundle(BUNDLE_INVALID, "module mismatch", m -> m.replace("\"org.lwjgl:lwjgl-tinyfd\": \"3.2.2\"", "\"org.lwjgl:lwjgl-tinyfd\": \"3.2.1\""));
        expectBundle(BUNDLE_INVALID, "module missing", m -> m.replace(", \"org.lwjgl:lwjgl-tinyfd\": \"3.2.2\"", ""));
        expectBundle(BUNDLE_INVALID, "adapter not listed", m -> m.replace("\"lib/lwjgl-glfw-orbis.jar\"]", "\"lib/other.jar\"]"));
        expectBundle(BUNDLE_INVALID, "empty natives", m -> m.replace("[\"lib/liblwjgl.so\", \"lib/liblwjgl_opengl.so\"]", "[]"));
        expectBundle(BUNDLE_INVALID, "repeated operation", m -> m.replace("\"glfwCreateWindow\"", "\"glfwInit\""));
        expectBundle(BUNDLE_INVALID, "bad hash format", m -> m.replaceFirst("\"lib/liblwjgl.so\": \"[0-9a-f]{64}\"", "\"lib/liblwjgl.so\": \"XYZ\""));
        expectBundle(BUNDLE_INVALID, "hash mismatch", m -> m.replaceFirst("\"lib/liblwjgl.so\": \"[0-9a-f]{64}\"", "\"lib/liblwjgl.so\": \"" + "0".repeat(64) + "\""));
        expectBundle(BUNDLE_INVALID, "listed file missing", m -> m.replace("\"files\": {", "\"files\": {\"lib/absent.so\": \"" + "0".repeat(64) + "\","));
        expectBundle(ARTIFACT_PATH, "traversal", m -> m.replace("\"files\": {", "\"files\": {\"../escape.so\": \"" + "0".repeat(64) + "\","));
        expectBundle(BUNDLE_INVALID, "manifest lists itself", m -> m.replace("\"files\": {", "\"files\": {\"graphics-bundle.json\": \"" + "0".repeat(64) + "\","));
        expectBundle(BUNDLE_INVALID, "malformed manifest", m -> m + "}");

        Path linked = validBundle();
        Path target = linked.resolve("lib/liblwjgl.so");
        Path moved = Files.move(target, linked.resolve("lib/real.so"));
        Files.createSymbolicLink(target, moved);
        fails(BUNDLE_INVALID, "symlinked bundle file", () -> GraphicsBundle.load(linked, AUDITED));
        fails(BUNDLE_INVALID, "missing bundle", () -> GraphicsBundle.load(Path.of("/nonexistent-graphics"), AUDITED));
        Path noManifest = validBundle();
        Files.delete(noManifest.resolve(GraphicsBundle.MANIFEST));
        fails(BUNDLE_INVALID, "missing manifest", () -> GraphicsBundle.load(noManifest, AUDITED));
    }

    interface Edit {
        String apply(String manifest);
    }

    private static void expectBundle(LaunchPlanException.Category category, String name, Edit edit) throws Exception {
        Path bundle = validBundle();
        Path manifest = bundle.resolve(GraphicsBundle.MANIFEST);
        String original = Files.readString(manifest);
        String edited = edit.apply(original);
        check(!edited.equals(original), name + ": edit did not change the manifest");
        Files.writeString(manifest, edited);
        fails(category, "bundle " + name, () -> GraphicsBundle.load(bundle, AUDITED));
    }

    private static Path validBundle() throws Exception {
        Path bundle = Files.createTempDirectory("graphics-bundle");
        StringBuilder files = new StringBuilder();
        for (String file : new String[]{"lib/lwjgl-glfw-orbis.jar", "lib/liblwjgl.so", "lib/liblwjgl_opengl.so"}) {
            Path written = TestSupport.write(bundle.resolve(file), "content of " + file);
            if (files.length() > 0) files.append(", ");
            files.append('"').append(file).append("\": \"").append(TestSupport.hash("SHA-256", written)).append('"');
        }
        TestSupport.write(bundle.resolve(GraphicsBundle.MANIFEST), "{\"schema_version\": 1, \"files\": {" + files
                + "}, \"profiles\": [" + profileJson() + "]}");
        return bundle;
    }

    private static String profileJson() {
        StringBuilder modules = new StringBuilder();
        for (Map.Entry<String, String> module : AUDITED.modules().entrySet()) {
            if (modules.length() > 0) modules.append(", ");
            modules.append('"').append(module.getKey()).append("\": \"").append(module.getValue()).append('"');
        }
        return "{\"id\": \"" + AUDITED.id() + "\", \"modules\": {" + modules + "},"
                + " \"java_adapter_paths\": [\"lib/lwjgl-glfw-orbis.jar\"],"
                + " \"native_library_paths\": [\"lib/liblwjgl.so\", \"lib/liblwjgl_opengl.so\"],"
                + " \"glfw_operations\": [\"glfwInit\", \"glfwCreateWindow\"], \"gl_features\": [\"gl32-core\"]}";
    }
}
