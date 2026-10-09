package runner.lwjgl;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static runner.lwjgl.LaunchPlanException.Category.ARTIFACT_MISMATCH;
import static runner.lwjgl.LaunchPlanException.Category.ARTIFACT_MISSING;
import static runner.lwjgl.LaunchPlanException.Category.ARTIFACT_PATH;
import static runner.lwjgl.LaunchPlanException.Category.LWJGL_CONFLICT;
import static runner.lwjgl.LaunchPlanException.Category.METADATA_CONFLICT;
import static runner.lwjgl.LaunchPlanException.Category.METADATA_PARSE;
import static runner.lwjgl.LaunchPlanException.Category.METADATA_UNSUPPORTED;
import static runner.lwjgl.TestSupport.check;
import static runner.lwjgl.TestSupport.equal;
import static runner.lwjgl.TestSupport.fails;

public final class VersionMetadataResolverTest {
    static final String[] MODULES = {"lwjgl", "lwjgl-jemalloc", "lwjgl-openal", "lwjgl-opengl",
            "lwjgl-glfw", "lwjgl-stb", "lwjgl-tinyfd"};
    private static final String OSX_ONLY = "[{\"action\":\"allow\",\"os\":{\"name\":\"osx\"}}]";
    private static final String NOT_OSX = "[{\"action\":\"allow\"},{\"action\":\"disallow\",\"os\":{\"name\":\"osx\"}}]";

    /** Builds a libraries root plus a version JSON shaped like official 1.18.2 metadata. */
    static final class Fixture {
        final Path directory;
        final Path libraries;
        final List<String> entries = new ArrayList<>();
        String extraRoot = "";

        Fixture() throws Exception {
            directory = Files.createTempDirectory("metadata");
            libraries = Files.createDirectories(directory.resolve("libraries"));
        }

        /** Writes an artifact and returns its downloads.artifact JSON. */
        String artifact(String path, String content) throws Exception {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            TestSupport.write(libraries.resolve(path), content);
            return "{\"path\":\"" + path + "\",\"sha1\":\"" + TestSupport.hash("SHA-1", bytes)
                    + "\",\"size\":" + bytes.length + ",\"url\":\"https://example.invalid/" + path + "\"}";
        }

        void library(String name, String artifactJson, String rules) {
            entries.add("{\"downloads\":{\"artifact\":" + artifactJson + "},\"name\":\"" + name + "\""
                    + (rules == null ? "" : ",\"rules\":" + rules) + "}");
        }

        void nativesLibrary(String name, String artifactJson, String rules, String path) {
            entries.add("{\"downloads\":{\"artifact\":" + artifactJson + ",\"classifiers\":{"
                    + "\"natives-linux\":{\"path\":\"" + path + "-natives-linux.jar\",\"sha1\":\"" + "0".repeat(40)
                    + "\",\"size\":1,\"url\":\"https://example.invalid/n\"},"
                    + "\"natives-windows\":{\"path\":\"" + path + "-natives-windows.jar\",\"sha1\":\"" + "1".repeat(40)
                    + "\",\"size\":1,\"url\":\"https://example.invalid/n\"}}},"
                    + "\"name\":\"" + name + "\",\"natives\":{\"linux\":\"natives-linux\",\"windows\":\"natives-windows\"},"
                    + "\"rules\":" + rules + "}");
        }

        /** The 1.18.2 shape: common JARs, 3.2.1 macOS track, 3.2.2 elsewhere, natives duplicates. */
        Fixture minecraftShape() throws Exception {
            library("com.mojang:logging:1.0.0", artifact("com/mojang/logging/1.0.0/logging-1.0.0.jar", "logging"), null);
            library("com.google.guava:guava:31.0.1-jre",
                    artifact("com/google/guava/guava/31.0.1-jre/guava-31.0.1-jre.jar", "guava"), null);
            for (String version : new String[]{"3.2.1", "3.2.2"})
                for (String module : MODULES)
                    library("org.lwjgl:" + module + ":" + version, lwjgl(module, version),
                            version.equals("3.2.1") ? OSX_ONLY : NOT_OSX);
            for (String version : new String[]{"3.2.1", "3.2.2"})
                for (String module : MODULES)
                    nativesLibrary("org.lwjgl:" + module + ":" + version, lwjgl(module, version),
                            version.equals("3.2.1") ? OSX_ONLY : NOT_OSX,
                            "org/lwjgl/" + module + "/" + version + "/" + module + "-" + version);
            return this;
        }

        String lwjgl(String module, String version) throws Exception {
            return artifact("org/lwjgl/" + module + "/" + version + "/" + module + "-" + version + ".jar",
                    module + " " + version);
        }

        Path json() throws Exception {
            return TestSupport.write(directory.resolve("version.json"), "{\"id\":\"1.18.2\","
                    + "\"mainClass\":\"net.minecraft.client.main.Main\"," + extraRoot
                    + "\"libraries\":[" + String.join(",", entries) + "]}");
        }

        ResolvedLaunchPlan resolve(MetadataPlatform platform) throws Exception {
            return VersionMetadataResolver.resolve(json(), libraries, platform);
        }

        ResolvedLaunchPlan resolve() throws Exception {
            return resolve(MetadataPlatform.ORBIS);
        }
    }

    public static void main(String[] args) throws Exception {
        Fixture fixture = new Fixture().minecraftShape();
        ResolvedLaunchPlan plan = fixture.resolve();
        equal("1.18.2", plan.versionId(), "version id");
        equal("net.minecraft.client.main.Main", plan.mainClass(), "main class");
        equal(2 + MODULES.length, plan.classpath().size(), "natives duplicates collapse to one classpath entry");
        equal(fixture.libraries.toRealPath().resolve("com/mojang/logging/1.0.0/logging-1.0.0.jar"),
                plan.classpath().get(0), "metadata order kept");
        check(plan.classpath().get(2).toString().endsWith("lwjgl-3.2.2.jar"), "3.2.2 track selected");
        for (Path entry : plan.classpath()) check(!entry.toString().contains("3.2.1"), "macOS track excluded: " + entry);
        equal(MODULES.length, plan.lwjglModules().size(), "seven LWJGL modules");
        for (String module : MODULES) {
            equal("3.2.2", plan.lwjglModules().get("org.lwjgl:" + module), module + " version");
            equal(TestSupport.hash("SHA-1", fixture.libraries.resolve("org/lwjgl/" + module + "/3.2.2/"
                    + module + "-3.2.2.jar")), plan.lwjglSha1().get("org.lwjgl:" + module), module + " SHA-1");
            equal(fixture.libraries.toRealPath().resolve("org/lwjgl/" + module + "/3.2.2/" + module + "-3.2.2.jar"),
                    plan.lwjglPaths().get("org.lwjgl:" + module), module + " path");
            check(plan.classpath().contains(plan.lwjglPaths().get("org.lwjgl:" + module)), module + " on classpath");
        }
        equal(List.copyOf(plan.lwjglModules().keySet()).stream().sorted().toList(),
                List.copyOf(plan.lwjglModules().keySet()), "modules sorted");
        check(plan.skippedNatives().contains("org.lwjgl:lwjgl:3.2.2:natives-linux"), "linux natives recorded as skipped");
        check(plan.skippedNatives().stream().noneMatch(n -> n.contains("3.2.1")), "unselected natives not recorded");
        for (Path entry : plan.classpath()) check(!entry.toString().contains("natives"), "no natives on classpath");
        equal(plan, fixture.resolve(), "deterministic plan");

        ResolvedLaunchPlan osx = fixture.resolve(new MetadataPlatform("osx", "x86_64"));
        for (String module : MODULES) equal("3.2.1", osx.lwjglModules().get("org.lwjgl:" + module), "osx " + module);

        Fixture arch = new Fixture();
        arch.library("a:only-arm:1", arch.artifact("a/arm.jar", "arm"), "[{\"action\":\"allow\",\"os\":{\"arch\":\"arm64\"}}]");
        arch.library("a:only-x64:1", arch.artifact("a/x64.jar", "x64"), "[{\"action\":\"allow\",\"os\":{\"name\":\"linux\",\"arch\":\"x86_64\"}}]");
        equal(1, arch.resolve().classpath().size(), "os.arch rule evaluated");
        check(arch.resolve().lwjglModules().isEmpty(), "no LWJGL in plain plan");

        Fixture modern = new Fixture();
        modern.library("org.lwjgl:lwjgl:3.3.1", modern.lwjgl("lwjgl", "3.3.1"), null);
        modern.library("org.lwjgl:lwjgl:3.3.1:natives-linux", modern.artifact("n/lwjgl-natives-linux.jar", "n"), null);
        ResolvedLaunchPlan modernPlan = modern.resolve();
        equal(1, modernPlan.classpath().size(), "classifier-named natives are not on the classpath");
        equal(List.of("org.lwjgl:lwjgl:3.3.1:natives-linux"), modernPlan.skippedNatives(), "classifier natives recorded");

        // Minecraft 26.2 declares the core only as its "unsafe" variant; it is still the core module.
        Fixture unsafe = new Fixture();
        unsafe.library("org.lwjgl:lwjgl:3.4.1:unsafe", unsafe.artifact("org/lwjgl/lwjgl/3.4.1/lwjgl-3.4.1-unsafe.jar", "core"), null);
        unsafe.library("org.lwjgl:lwjgl:3.4.1:natives-linux", unsafe.artifact("n/lwjgl-natives-linux.jar", "n"), null);
        ResolvedLaunchPlan unsafePlan = unsafe.resolve();
        equal(Map.of("org.lwjgl:lwjgl", "3.4.1"), unsafePlan.lwjglModules(), "the unsafe core is the core module");
        equal(1, unsafePlan.classpath().size(), "the unsafe core is on the classpath");
        Fixture both = new Fixture();
        both.library("org.lwjgl:lwjgl:3.4.1", both.lwjgl("lwjgl", "3.4.1"), null);
        both.library("org.lwjgl:lwjgl:3.4.1:unsafe", both.artifact("org/lwjgl/lwjgl/3.4.1/lwjgl-3.4.1-unsafe.jar", "core"), null);
        fails(LWJGL_CONFLICT, "core declared plain and unsafe", both::resolve);

        // Artifact problems.
        Fixture missing = new Fixture().minecraftShape();
        Files.delete(missing.libraries.resolve("com/google/guava/guava/31.0.1-jre/guava-31.0.1-jre.jar"));
        fails(ARTIFACT_MISSING, "missing artifact", missing::resolve);
        Fixture changed = new Fixture().minecraftShape();
        Files.writeString(changed.libraries.resolve("org/lwjgl/lwjgl-glfw/3.2.2/lwjgl-glfw-3.2.2.jar"), "lwjgl-glfw 3.2.X");
        fails(ARTIFACT_MISMATCH, "SHA-1 mismatch with equal size", changed::resolve);
        Fixture resized = new Fixture().minecraftShape();
        Files.writeString(resized.libraries.resolve("com/mojang/logging/1.0.0/logging-1.0.0.jar"), "longer logging");
        fails(ARTIFACT_MISMATCH, "size mismatch", resized::resolve);
        Fixture sha256 = new Fixture();
        sha256.library("a:b:1", "{\"path\":\"a/b.jar\",\"sha256\":\"" + "a".repeat(64) + "\"}", null);
        sha256.artifact("a/b.jar", "b");
        fails(ARTIFACT_MISMATCH, "SHA-256 mismatch", sha256::resolve);
        Fixture sha256ok = new Fixture();
        sha256ok.artifact("a/b.jar", "b");
        sha256ok.library("a:b:1", "{\"path\":\"a/b.jar\",\"sha256\":\""
                + TestSupport.hash("SHA-256", "b".getBytes(StandardCharsets.UTF_8)) + "\"}", null);
        equal(1, sha256ok.resolve().classpath().size(), "SHA-256 accepted");
        Fixture badHash = new Fixture();
        badHash.library("a:b:1", "{\"path\":\"a/b.jar\",\"sha1\":\"ABC\"}", null);
        fails(METADATA_UNSUPPORTED, "malformed sha1", badHash::resolve);
        fails(ARTIFACT_MISSING, "missing libraries root", () -> VersionMetadataResolver.resolve(
                new Fixture().minecraftShape().json(), Path.of("/nonexistent-libraries"), MetadataPlatform.ORBIS));

        // Path traversal and confinement.
        Fixture outside = new Fixture();
        TestSupport.write(outside.directory.resolve("evil.jar"), "evil");
        for (String path : new String[]{"../evil.jar", "/etc/hosts", "a/../../evil.jar", "a//b.jar", "./a.jar",
                "a\\\\b.jar", "C:/evil.jar", "a/./b.jar"}) {
            Fixture traversal = new Fixture();
            traversal.library("a:b:1", "{\"path\":\"" + path + "\"}", null);
            fails(ARTIFACT_PATH, "traversal " + path, traversal::resolve);
        }
        Fixture link = new Fixture();
        TestSupport.write(link.directory.resolve("outside.jar"), "outside");
        Files.createDirectories(link.libraries.resolve("a"));
        Files.createSymbolicLink(link.libraries.resolve("a/link.jar"), link.directory.resolve("outside.jar"));
        link.library("a:link:1", "{\"path\":\"a/link.jar\"}", null);
        fails(ARTIFACT_PATH, "symlink escape", link::resolve);

        // Unsupported or ambiguous metadata.
        String[][] unsupportedRules = {
                {"os.version", "[{\"action\":\"allow\",\"os\":{\"name\":\"linux\",\"version\":\"^10\\\\.\"}}]"},
                {"features", "[{\"action\":\"allow\",\"features\":{\"is_demo_user\":true}}]"},
                {"action", "[{\"action\":\"maybe\"}]"},
                {"rules object", "{\"action\":\"allow\"}"},
                {"os name type", "[{\"action\":\"allow\",\"os\":{\"name\":1}}]"}};
        for (String[] rule : unsupportedRules) {
            Fixture unsupported = new Fixture();
            unsupported.library("a:b:1", unsupported.artifact("a/b.jar", "b"), rule[1]);
            fails(METADATA_UNSUPPORTED, "rule " + rule[0], unsupported::resolve);
        }
        Fixture inherits = new Fixture().minecraftShape();
        inherits.extraRoot = "\"inheritsFrom\":\"1.18.2\",";
        fails(METADATA_UNSUPPORTED, "inheritsFrom", inherits::resolve);
        Fixture legacy = new Fixture();
        legacy.entries.add("{\"name\":\"a:b:1\"}");
        fails(METADATA_UNSUPPORTED, "legacy library without downloads", legacy::resolve);
        Fixture unknownKey = new Fixture();
        unknownKey.entries.add("{\"name\":\"a:b:1\",\"url\":\"https://example.invalid/\",\"downloads\":{}}");
        fails(METADATA_UNSUPPORTED, "unknown library key", unknownKey::resolve);
        for (String name : new String[]{"a:b", "a:b:1:c:d", "a::1", "a:b c:1", "a:b:1/../x"}) {
            Fixture coordinate = new Fixture();
            coordinate.library(name, coordinate.artifact("a/b.jar", "b"), null);
            fails(METADATA_UNSUPPORTED, "coordinate " + name, coordinate::resolve);
        }
        Fixture badMain = new Fixture();
        TestSupport.write(badMain.directory.resolve("version.json"),
                "{\"id\":\"x\",\"mainClass\":\"not a class\",\"libraries\":[]}");
        fails(METADATA_UNSUPPORTED, "invalid mainClass", () -> VersionMetadataResolver.resolve(
                badMain.directory.resolve("version.json"), badMain.libraries, MetadataPlatform.ORBIS));
        TestSupport.write(badMain.directory.resolve("version.json"), "{\"id\":\"x\",\"libraries\":[]");
        fails(METADATA_PARSE, "malformed JSON", () -> VersionMetadataResolver.resolve(
                badMain.directory.resolve("version.json"), badMain.libraries, MetadataPlatform.ORBIS));
        TestSupport.write(badMain.directory.resolve("version.json"), "[]");
        fails(METADATA_UNSUPPORTED, "root array", () -> VersionMetadataResolver.resolve(
                badMain.directory.resolve("version.json"), badMain.libraries, MetadataPlatform.ORBIS));

        // Conflicts.
        Fixture mixed = new Fixture();
        mixed.library("org.lwjgl:lwjgl:3.2.2", mixed.lwjgl("lwjgl", "3.2.2"), null);
        mixed.library("org.lwjgl:lwjgl:3.3.1", mixed.lwjgl("lwjgl", "3.3.1"), null);
        fails(LWJGL_CONFLICT, "one module at two versions", mixed::resolve);
        Fixture redeclared = new Fixture();
        redeclared.library("a:b:1", redeclared.artifact("a/b.jar", "b"), null);
        redeclared.library("a:b:1", redeclared.artifact("a/c.jar", "c"), null);
        fails(METADATA_CONFLICT, "coordinate redeclared with another artifact", redeclared::resolve);
        Fixture shared = new Fixture();
        String sharedArtifact = shared.artifact("a/b.jar", "b");
        shared.library("a:b:1", sharedArtifact, null);
        shared.library("a:c:1", sharedArtifact, null);
        fails(METADATA_CONFLICT, "two coordinates share one artifact", shared::resolve);
        System.out.println("VersionMetadataResolver fixtures passed");
    }

    static Map<String, String> fixtureSha1(Fixture fixture, String version) throws Exception {
        Map<String, String> result = new java.util.TreeMap<>();
        for (String module : MODULES)
            result.put("org.lwjgl:" + module, TestSupport.hash("SHA-1", fixture.libraries.resolve(
                    "org/lwjgl/" + module + "/" + version + "/" + module + "-" + version + ".jar")));
        return result;
    }
}
