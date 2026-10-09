package orbis.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The launcher's input and page logic on the host: text entry under random editing, and the real
 * LauncherScreen driven by random input against a temporary instance, with a renderer that skips
 * GL and a Modrinth client and installer that answer instantly. Each Play is checked by consuming
 * its prepared launch the way the next application start would.
 */
public final class LauncherLogicTest {
    private static final Input.Action[] ACTIONS = Input.Action.values();

    public static void main(String[] args) throws Exception {
        versionPredicates();
        lwjglSelection();
        lwjglUpgrade();
        runtimeSelection();
        consoleModSettings();
        scroller();
        packRules();
        packInstall();
        markdown();
        pictures();
        textEntry();
        long seeds = args.length > 0 ? Long.parseLong(args[0]) : 8;
        int steps = args.length > 1 ? Integer.parseInt(args[1]) : 6000;
        for (long seed = 1; seed <= seeds; seed++) screen(seed, steps);
        System.out.println("launcher logic passed: text entry, " + seeds + " x " + steps + " random screen steps");
    }

    // ------------------------------------------------------------------ versions

    /** 1.20.5+ keep their LWJGL 3.3.3; older releases (and any mixture) take the installed 3.2.2 set. */
    private static void lwjglSelection() {
        String modern = "[{\"name\":\"org.lwjgl:lwjgl:3.3.3\"},{\"name\":\"org.lwjgl:lwjgl:3.3.3:natives-linux\"},"
                + "{\"name\":\"org.lwjgl:lwjgl-freetype:3.3.3\"},{\"name\":\"com.mojang:brigadier:1.3.10\"}]";
        String old = "[{\"name\":\"org.lwjgl:lwjgl:3.3.2\"},{\"name\":\"org.lwjgl:lwjgl-glfw:3.3.2\"}]";
        String mixed = "[{\"name\":\"org.lwjgl:lwjgl:3.3.3\"},{\"name\":\"org.lwjgl:lwjgl-glfw:3.3.2\"}]";
        String none = "[{\"name\":\"com.mojang:brigadier:1.3.10\"}]";
        check(VersionStore.declaresOnly(array(modern), "3.3.3"), "1.21-shaped libraries keep LWJGL 3.3.3");
        check(!VersionStore.declaresOnly(array(old), "3.3.3"), "1.20.4-shaped libraries take 3.2.2");
        check(!VersionStore.declaresOnly(array(mixed), "3.3.3"), "a mixed LWJGL set takes 3.2.2");
        check(!VersionStore.declaresOnly(array(none), "3.3.3"), "no LWJGL is not 3.3.3");
        int offered = VersionStore.offeredJava;
        try {
            VersionStore.offeredJava = 17;
            check(VersionStore.inRange("1.20.4") && !VersionStore.inRange("1.20.5"), "Java 17 runner: up to 1.20.4");
            check(!ModrinthClient.supportedReleases().contains("1.21.1"), "Java 17 runner: no 1.21 packs");
            VersionStore.offeredJava = 21;
            check(VersionStore.inRange("1.21.11") && !VersionStore.inRange("1.21.12") && !VersionStore.inRange("26.1"),
                    "Java 21 runner: up to 1.21.11");
            check(ModrinthClient.supportedReleases().contains("1.21.1"), "Java 21 runner: 1.21 packs");
            check(!VersionStore.inRange("26.1") && !ModrinthClient.supportedReleases().contains("26.1.2"),
                    "Java 21 runner: no 26.1");
            VersionStore.offeredJava = 25;
            check(VersionStore.inRange("26.1") && VersionStore.inRange("26.1.2") && VersionStore.inRange("26.2")
                    && VersionStore.inRange("26.3") && !VersionStore.inRange("26.4") && VersionStore.inRange("1.21.11")
                    && VersionStore.inRange("1.14"), "Java 25 runner: up to 26.3");
            check(ModrinthClient.supportedReleases().containsAll(List.of("26.3", "26.2", "26.1.2", "1.21.11", "1.20.4")),
                    "Java 25 runner: 26.x packs as well");
            check(VersionStore.newest().equals("26.3"), "Java 25 runner: newest 26.3");
        } finally {
            VersionStore.offeredJava = offered;
        }
        check(VersionStore.requiredJava("26.1") == 25 && VersionStore.requiredJava("26.1.2") == 25,
                "26.1 needs Java 25");
        String v341 = "[{\"name\":\"org.lwjgl:lwjgl:3.4.1\"},{\"name\":\"org.lwjgl:lwjgl-glfw:3.4.1\"}]";
        check(VersionStore.declaresOnly(array(v341), VersionStore.CURRENT_LWJGL) && !VersionStore.upgradesToOwnLwjgl(array(v341)),
                "26.1-shaped libraries keep LWJGL 3.4.1");
        String v343 = "[{\"name\":\"org.lwjgl:lwjgl:3.4.3\"},{\"name\":\"org.lwjgl:lwjgl-sdl:3.4.3\"}]";
        check(VersionStore.declaresOnly(array(v343), VersionStore.SDL_LWJGL) && !VersionStore.upgradesToOwnLwjgl(array(v343)),
                "26.3-shaped libraries keep LWJGL 3.4.3");
        check(VersionStore.requiredJava("1.21.1") == 21 && VersionStore.requiredJava("1.20.5") == 21
                && VersionStore.requiredJava("1.20.4") == 17 && VersionStore.requiredJava("1.16.5") == 8,
                "required Java for records without one");

        String v331 = "[{\"name\":\"org.lwjgl:lwjgl:3.3.1\"},{\"name\":\"org.lwjgl:lwjgl-glfw:3.3.1\"}]";
        String v322 = "[{\"name\":\"org.lwjgl:lwjgl:3.2.2\"},{\"name\":\"org.lwjgl:lwjgl-glfw:3.2.2\"}]";
        check(VersionStore.upgradesToOwnLwjgl(array(old)) && VersionStore.upgradesToOwnLwjgl(array(v331)),
                "1.19 to 1.20.4 (LWJGL 3.3.1, 3.3.2) run on the 3.3.3 set");
        check(!VersionStore.upgradesToOwnLwjgl(array(v322)) && !VersionStore.upgradesToOwnLwjgl(array(modern))
                && !VersionStore.upgradesToOwnLwjgl(array(mixed)) && !VersionStore.upgradesToOwnLwjgl(array(none)),
                "3.2.2, 3.3.3, mixed and no LWJGL are not upgraded");
        java.util.Set<String> modules = new java.util.TreeSet<>();
        for (com.google.gson.JsonElement e : VersionStore.ownLwjglLibraries()) {
            String[] name = e.getAsJsonObject().get("name").getAsString().split(":");
            check(name.length == 3 && name[2].equals("3.3.3"), "own LWJGL entries are 3.3.3 Java artifacts");
            modules.add(name[1]);
        }
        check(modules.equals(new java.util.TreeSet<>(List.of("lwjgl", "lwjgl-freetype", "lwjgl-glfw", "lwjgl-jemalloc",
                "lwjgl-openal", "lwjgl-opengl", "lwjgl-stb", "lwjgl-tinyfd"))), "own LWJGL set is the runner's 3.3.3 profile");
        check(VersionStore.declaresOnly(VersionStore.ownLwjglLibraries(), "3.3.3"), "own LWJGL set declares only 3.3.3");
        check(LaunchPreparation.lwjglOptions(installed("1.20.1", 17)).equals(List.of("-Dsodium.checks.issue2561=false"))
                && LaunchPreparation.lwjglOptions(installed("1.19", 17)).size() == 1
                && LaunchPreparation.lwjglOptions(installed("1.18.2", 17)).isEmpty()
                && LaunchPreparation.lwjglOptions(installed("1.20.5", 21)).isEmpty()
                && LaunchPreparation.lwjglOptions(null).isEmpty(), "Sodium's 3.3.1 check is off exactly for 1.19 to 1.20.4");
    }

    /**
     * An install of 1.20.1 made with the 1.18.2 LWJGL 3.2.2 entries gets the 3.3.3 set in both profiles, in
     * place; other entries keep their order. Needs the official 3.3.3 JARs (ORBIS_LWJGL_JARS) to stay offline.
     */
    private static void lwjglUpgrade() throws IOException {
        String jars = System.getenv("ORBIS_LWJGL_JARS");
        Path root = Files.createTempDirectory("lwjgl-upgrade");
        Path libraries = Files.createDirectories(root.resolve("libraries"));
        VersionStore store = new VersionStore(root.resolve("home"), libraries, root.resolve("assets"), root.resolve("x.json"));
        Path dir = Files.createDirectories(store.versionsRoot().resolve("1.20.1-vanilla"));
        String profile = "{\"libraries\":[{\"name\":\"com.mojang:brigadier:1.1.8\"},{\"name\":\"org.lwjgl:lwjgl:3.2.2\"},"
                + "{\"name\":\"org.lwjgl:lwjgl:3.2.2:natives-linux\"},{\"name\":\"org.lwjgl:lwjgl-glfw:3.2.2\"},"
                + "{\"name\":\"com.mojang:logging:1.1.1\"}]}";
        for (String name : List.of("game.json", "boot.json")) Files.writeString(dir.resolve(name), profile);
        Path x = dir.resolve("game.json");
        VersionStore.Installed old = new VersionStore.Installed("1.20.1-vanilla", "1.20.1", VersionStore.Loader.VANILLA, "",
                dir.resolve("game.json"), dir.resolve("boot.json"), x, "x.Main", "1", "release", List.of(), java.util.Map.of(), 17);
        check(!store.upgradeLwjgl(new VersionStore.Installed("1.18.2-vanilla", "1.18.2", VersionStore.Loader.VANILLA, "",
                x, x, x, "x.Main", "1", "release", List.of(), java.util.Map.of(), 17)), "1.18.2 keeps LWJGL 3.2.2");
        check(!store.upgradeLwjgl(new VersionStore.Installed("1.20.5-vanilla", "1.20.5", VersionStore.Loader.VANILLA, "",
                x, x, x, "x.Main", "1", "release", List.of(), java.util.Map.of(), 21)), "1.20.5 keeps its own LWJGL");
        if (jars == null) {
            System.out.println("LWJGL upgrade migration skipped: set ORBIS_LWJGL_JARS to the official 3.3.3 JARs");
            return;
        }
        for (com.google.gson.JsonElement e : VersionStore.ownLwjglLibraries()) {
            String path = e.getAsJsonObject().getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString();
            Path target = libraries.resolve(path);
            Files.createDirectories(target.getParent());
            Files.copy(Path.of(jars).resolve(target.getFileName()), target);
        }
        check(store.upgradeLwjgl(old), "1.20.1 with LWJGL 3.2.2 is upgraded");
        for (String name : List.of("game.json", "boot.json")) {
            com.google.gson.JsonArray libs = com.google.gson.JsonParser.parseString(Files.readString(dir.resolve(name)))
                    .getAsJsonObject().getAsJsonArray("libraries");
            check(libs.size() == 10 && libs.get(0).getAsJsonObject().get("name").getAsString().startsWith("com.mojang:brigadier")
                    && libs.get(9).getAsJsonObject().get("name").getAsString().startsWith("com.mojang:logging")
                    && VersionStore.declaresOnly(libs, "3.3.3"), name + " carries the 3.3.3 set in place");
        }
        check(!store.upgradeLwjgl(old), "an upgraded install is left alone");
        System.out.println("LWJGL upgrade migration passed");
    }

    /** Ixeris is switched off through its own per-platform keys; nothing else in its config changes. */
    private static void consoleModSettings() throws IOException {
        String shipped = "#Specifies whether to enable the mod on Linux\nenabledOnLinux = true\n"
                + "#Specifies whether to enable the mod on other platforms\nenabledOnOtherPlatforms = true # note\n"
                + "flexibleThreading = true\n\n[bufferedRawInput]\n\tmouse = true\n";
        String off = ConsoleModSettings.switchOff(shipped, "enabledOnLinux", "enabledOnOtherPlatforms");
        check(off.equals(shipped.replace("enabledOnLinux = true", "enabledOnLinux = false")
                .replace("enabledOnOtherPlatforms = true # note", "enabledOnOtherPlatforms = false # note")),
                "both switches off, comments and other keys kept: " + off);
        check(ConsoleModSettings.switchOff(off, "enabledOnLinux", "enabledOnOtherPlatforms").equals(off), "idempotent");
        String added = ConsoleModSettings.switchOff("[bufferedRawInput]\n\tmouse = true\n", "enabledOnLinux");
        check(added.startsWith("enabledOnLinux = false\n") && added.contains("[bufferedRawInput]"),
                "a missing key goes ahead of the first table: " + added);
        check(ConsoleModSettings.switchOff("", "enabledOnLinux").equals("enabledOnLinux = false\n"), "a new file gets the key");

        Path game = Files.createTempDirectory("console-mods");
        Files.createDirectories(game.resolve("mods"));
        check(!ConsoleModSettings.apply(game), "no Ixeris, nothing written");
        check(!Files.exists(game.resolve("config/ixeris.toml")), "no config created without Ixeris");
    }

    /** JDK 17 stays the default runner; a version needing Java 21 starts on the JDK 21 export, if one is there. */
    private static void runtimeSelection() throws IOException {
        Path root = Files.createTempDirectory("launcher-runtimes");
        Path jdk17 = export(root.resolve("jdk"), "17.0.20"), jdk21 = export(root.resolve("jdk21"), "21.0.12");
        Files.createDirectories(root.resolve("runner"));               // not a runtime
        Files.createDirectories(root.resolve("broken/release-desktop")); // no release file
        Runtimes runtimes = Runtimes.discover(jdk17, root);
        check(runtimes.fallback.home().equals(jdk17) && runtimes.fallback.feature() == 17, "default runner is JDK 17");
        check(runtimes.all.size() == 2 && runtimes.newest() == 21, "JDK 21 export found beside it: " + runtimes.all);
        check(runtimes.forJava(17) == runtimes.fallback && runtimes.forJava(8) == runtimes.fallback,
                "older versions keep the default runner");
        check(runtimes.forJava(21).home().equals(jdk21), "1.20.5+ get the JDK 21 runner");
        check(runtimes.forJava(25) == null, "no runner for Java 25");
        check(LaunchPreparation.runtime(runtimes, installed("1.20.4", 17)) == null, "1.20.4 keeps java_home");
        check(LaunchPreparation.runtime(runtimes, installed("1.21.1", 21)).home().equals(jdk21), "1.21.1 switches");
        check(LaunchPreparation.runtime(runtimes, null) == null, "the built-in 1.18.2 keeps java_home");
        Runtimes only17 = Runtimes.discover(jdk17, jdk17);
        check(only17.newest() == 17, "no JDK 21 export, nothing newer offered");
        try {
            LaunchPreparation.runtime(only17, installed("1.21.1", 21));
            throw new AssertionError("1.21.1 without a Java 21 runner must not launch");
        } catch (IOException expected) {
            check(expected.getMessage().contains("needs Java 21"), expected.getMessage());
        }
        // A default runner on JDK 21 runs everything itself.
        check(LaunchPreparation.runtime(Runtimes.discover(jdk21, root), installed("1.21.1", 21)) == null,
                "a Java 21 default keeps java_home");
    }

    private static Path export(Path dir, String version) throws IOException {
        Path home = Files.createDirectories(dir.resolve(Runtimes.EXPORT));
        Files.createDirectories(home.resolve("lib/server"));
        Files.write(home.resolve("lib/server/libjvm.prx"), new byte[]{1});
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nMODULES=\"java.base\"\n");
        return home;
    }

    private static VersionStore.Installed installed(String minecraft, int required) {
        Path x = Path.of("/x");
        return new VersionStore.Installed(minecraft + "-vanilla", minecraft, VersionStore.Loader.VANILLA, "", x, x, x,
                "x.Main", "1", "release", List.of(), java.util.Map.of(), required);
    }

    private static com.google.gson.JsonArray array(String json) {
        return com.google.gson.JsonParser.parseString(json).getAsJsonArray();
    }

    private static void versionPredicates() {
        String[][] yes = {
            {"0.4.1+build.15", "*"}, {"0.4.1+build.15", ">=0.4.1"}, {"0.4.1+build.15", "0.4.1"},
            {"0.4.1+build.15", "~0.4.0"}, {"0.4.1+build.15", "^0.4.0-alpha5"}, {"1.18.2", "1.18.x"},
            {"1.18.2", ">=1.18 <1.19"}, {"1.18.2", "[\"1.17.x\", \"1.18.x\"]"}, {"0.19.5", ">=0.12.3"},
            {"17", ">=16"}, {"1.18.2", "~1.18.2-"}, {"0.4.0", ">0.4.0-alpha5"}, {"2.0.0", ">=1.0.0-beta.2"},
            {"0.77.0+1.18.2", ">=0.36.0"}, {"1.18.2", "1.18.2"}, {"8.0.0+17", "8.0.0"},
        };
        String[][] no = {
            {"0.4.1+build.15", "0.4.0-alpha5"}, {"0.4.1+build.15", "<0.4.1"}, {"1.18.2", "1.19.x"},
            {"1.18.2", ">=1.19"}, {"1.18.2", "~1.17"}, {"0.4.0-alpha5", ">=0.4.0"}, {"2.0.0", "^1.0"},
            {"1.18.2", "[\"1.16.x\", \"1.17.x\"]"}, {"0.14.9", ">=0.15.0"},
        };
        for (String[] c : yes) check(VersionPredicate.matches(c[0], c[1]), c[0] + " should match " + c[1]);
        for (String[] c : no) check(!VersionPredicate.matches(c[0], c[1]), c[0] + " should not match " + c[1]);
        check(VersionPredicate.matches("weird", "a.b.c"), "unparseable never blocks");
    }

    // ------------------------------------------------------------------ modpacks, descriptions, pictures

    private static void packRules() throws IOException {
        for (String bad : new String[]{"", "/etc/x", "\\x", "C:/x", "a/../b", "./a", "a//b", "a\\b", "..", "mods/", "a\u0000b"})
            check(PackInstaller.pathProblem(bad) != null, "unsafe pack path accepted: " + bad);
        for (String good : new String[]{"mods/a.jar", "config/x/y.json", "options.txt", "resourcepacks/a b.zip"})
            check(PackInstaller.pathProblem(good) == null, "safe pack path refused: " + good);
        check(PackInstaller.allowed("https://cdn.modrinth.com/data/x.jar"), "Modrinth CDN allowed");
        check(PackInstaller.allowed("https://github.com/a/b/releases/download/x.jar"), "GitHub allowed");
        for (String bad : new String[]{"http://cdn.modrinth.com/x", "https://cdn.modrinth.com.evil.org/x",
                "https://user@cdn.modrinth.com/x", "https://cdn.modrinth.com:8443/x", "ftp://github.com/x", "not a url"})
            check(!PackInstaller.allowed(bad), "download host accepted: " + bad);

        // A hostile pack: every escape attempt is a blocker, and reading never throws for it.
        Path archive = Files.createTempFile("hostile", ".mrpack");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("modrinth.index.json"));
            zip.write(("{\"formatVersion\":1,\"game\":\"minecraft\",\"name\":\"Evil\",\"dependencies\":{"
                    + "\"minecraft\":\"1.18.2\",\"fabric-loader\":\"0.14.9\"},\"files\":["
                    + "{\"path\":\"../../x.jar\",\"hashes\":{\"sha1\":\"" + "a".repeat(40) + "\"},\"downloads\":[\"https://cdn.modrinth.com/x\"]},"
                    + "{\"path\":\"mods/y.jar\",\"hashes\":{\"sha1\":\"" + "a".repeat(40) + "\"},\"downloads\":[\"https://evil.example/y\"]},"
                    + "{\"path\":\"mods/z.jar\",\"hashes\":{\"sha1\":\"" + "a".repeat(40) + "\"},\"env\":{\"client\":\"unsupported\"},"
                    + "\"downloads\":[\"https://evil.example/z\"]}]}").getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new java.util.zip.ZipEntry("overrides/../../../outside.txt"));
            zip.write(1);
        }
        PackInstaller.Pack pack = PackInstaller.read(archive);
        check(!pack.installable(), "hostile pack refused");
        check(pack.blockers().stream().anyMatch(b -> b.contains("../../x.jar")), "escaping file path named");
        check(pack.blockers().stream().anyMatch(b -> b.contains("mods/y.jar")), "foreign host named");
        check(pack.blockers().stream().noneMatch(b -> b.contains("mods/z.jar")), "server-only files are skipped");
        check(pack.blockers().stream().anyMatch(b -> b.contains("outside.txt")), "escaping override named");
        Files.delete(archive);
    }

    /** Smooth: the view eases to its target, stays within the content, and reveal moves it the least needed. */
    private static void scroller() {
        Scroller sc = new Scroller();
        sc.update(1000, 300, 0.016f);
        sc.wheel(-1, 120);
        float previous = 0;
        for (int i = 0; i < 60; i++) {
            float now = sc.update(1000, 300, 0.016f);
            check(now >= previous && now <= 120, "eases monotonically towards the target");
            previous = now;
        }
        check(sc.offset() == 120, "arrives");
        for (int i = 0; i < 40; i++) sc.wheel(-3, 120);
        for (int i = 0; i < 200; i++) sc.update(1000, 300, 0.016f);
        check(sc.offset() == 700, "stops at the end, never past it: " + sc.offset());
        sc.reveal(100, 150, 300);
        check(sc.target() == 100, "reveal scrolls up just enough");
        sc.reveal(150, 380, 300);
        check(sc.target() == 100, "a visible span does not move the view");
        sc.update(200, 300, 0.016f);
        check(sc.target() == 0 && sc.offset() >= 0, "content shorter than the view: no scrolling");
    }

    /** A whole pack into a fresh instance: files checked, overrides copied, native mods disabled, nothing outside. */
    private static void packInstall() throws IOException {
        Path root = Files.createTempDirectory("pack-install");
        Path profile = Files.writeString(root.resolve("profile.json"), "{}");
        InstanceStore store = new InstanceStore(root.resolve("home"), Files.createDirectories(root.resolve("imported")),
                profile, root.resolve("home/versions"), 1024, 1536);
        store.load();
        java.util.Map<String, byte[]> served = new java.util.HashMap<>();
        java.io.ByteArrayOutputStream nativeJar = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(nativeJar)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("fabric.mod.json"));
            zip.write("{\"id\":\"nat\",\"name\":\"Native Mod\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new java.util.zip.ZipEntry("natives/libthing.so"));
            zip.write(1);
        }
        byte[] plain = "plain".getBytes(StandardCharsets.UTF_8), natives = nativeJar.toByteArray();
        served.put("https://cdn.modrinth.com/plain", plain);
        served.put("https://cdn.modrinth.com/native", natives);
        String index = "{\"formatVersion\":1,\"game\":\"minecraft\",\"name\":\"Good Pack\",\"versionId\":\"2\","
                + "\"dependencies\":{\"minecraft\":\"1.18.2\",\"fabric-loader\":\"0.14.9\"},\"files\":["
                + "{\"path\":\"config/plain.txt\",\"hashes\":{\"sha1\":\"" + PackInstaller.hex("SHA-1", plain) + "\"},"
                + "\"fileSize\":" + plain.length + ",\"downloads\":[\"https://cdn.modrinth.com/missing\",\"https://cdn.modrinth.com/plain\"]},"
                + "{\"path\":\"mods/native.jar\",\"hashes\":{\"sha1\":\"" + PackInstaller.hex("SHA-1", natives) + "\"},"
                + "\"downloads\":[\"https://cdn.modrinth.com/native\"]}]}";
        Path archive = root.resolve("good.mrpack");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("modrinth.index.json"));
            zip.write(index.getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new java.util.zip.ZipEntry("overrides/config/plain.txt"));
            zip.write("overridden".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new java.util.zip.ZipEntry("client-overrides/options.txt"));
            zip.write("client".getBytes(StandardCharsets.UTF_8));
        }
        PackInstaller installer = new PackInstaller((url, max) -> {
            byte[] data = served.get(url);
            if (data == null) throw new IOException("HTTP 404");
            return data;
        }, (minecraft, loader, loaderVersion, op) -> {
            check(minecraft.equals("1.18.2") && loader == VersionStore.Loader.FABRIC && loaderVersion.equals("0.14.9"), "pack's versions");
            return profile;
        }, store);
        PackInstaller.Pack pack = PackInstaller.read(archive);
        check(pack.installable(), "good pack installable: " + pack.blockers());
        PackInstaller.Installed done = installer.install(pack, pack.name(), new InstanceStore.Operation());
        Path game = done.instance().gameDir();
        check(done.instance().name().equals("Good Pack"), "instance named after the pack");
        check(Files.readString(game.resolve("config/plain.txt")).equals("overridden"), "overrides win over downloads");
        check(Files.readString(game.resolve("options.txt")).equals("client"), "client overrides copied");
        check(Files.exists(game.resolve("mods/native.jar.disabled")) && !Files.exists(game.resolve("mods/native.jar")),
                "native mod disabled");
        check(done.disabled().equals(List.of("Native Mod")), "disabled mod reported");
        // The same pack again gets its own name; a failing download removes the half-made instance.
        check(installer.install(pack, pack.name(), new InstanceStore.Operation()).instance().name().equals("Good Pack (2)"),
                "second install gets a unique name");
        served.remove("https://cdn.modrinth.com/native");
        int before = store.list().size();
        try {
            installer.install(pack, pack.name(), new InstanceStore.Operation());
            throw new AssertionError("install with a missing file succeeded");
        } catch (IOException expected) {
            check(store.list().size() == before, "failed install leaves no instance behind");
        }
    }

    private static void markdown() {
        List<Markdown.Block> blocks = Markdown.parse("# Title\n\nSome **bold** [link](https://x) `code`.\n\n"
                + "- a\n  - b\n1. c\n\n![img](https://cdn.modrinth.com/a.png) ![badge](https://img.shields.io/x.svg)\n\n"
                + "<p align=\"center\"><img src=\"https://x/y.webp\" width=\"5\"></p>\n```\ncode\n```\n---\n> quote &amp; more");
        java.util.Map<Markdown.Kind, Integer> kinds = new java.util.EnumMap<>(Markdown.Kind.class);
        for (Markdown.Block b : blocks) kinds.merge(b.kind(), 1, Integer::sum);
        check(kinds.getOrDefault(Markdown.Kind.HEADING, 0) == 1, "one heading");
        check(kinds.getOrDefault(Markdown.Kind.BULLET, 0) == 3, "three list items");
        check(kinds.getOrDefault(Markdown.Kind.IMAGE, 0) == 2, "two pictures, badge dropped");
        check(blocks.stream().anyMatch(b -> b.text().equals("Some bold link code.")), "inline markup reduced to text");
        check(blocks.stream().anyMatch(b -> b.kind() == Markdown.Kind.QUOTE && b.text().equals("quote & more")), "entities");
        Random random = new Random(3);
        String alphabet = "#*_-[]()!<>`|> \n\\abc&;=\"/";
        for (int round = 0; round < 3000; round++) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < random.nextInt(200); i++) text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            for (Markdown.Block b : Markdown.parse(text.toString())) check(b.text() != null, "blocks have text");
        }
    }

    private static void pictures() throws IOException {
        java.awt.image.BufferedImage image = ImageCache.decode(png(300, 200, 0xff00ff00), ImageCache.Size.ICON);
        java.awt.image.BufferedImage fitted = ImageCache.fit(image, ImageCache.Size.ICON);
        check(fitted.getWidth() <= 128 && fitted.getHeight() <= 128 && fitted.getWidth() > fitted.getHeight(), "fitted, aspect kept");
        try {
            ImageCache.decode(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 1, 2, 3}, ImageCache.Size.ICON);
            throw new AssertionError("broken WebP decoded");
        } catch (IOException | RuntimeException expected) {
            // refused, as it should be
        }
    }

    // ------------------------------------------------------------------ text entry

    /** Random editing keeps the caret on a code point boundary; cancel restores, accept keeps. */
    private static void textEntry() {
        Random random = new Random(7);
        String[] pieces = {"a", "Z", " ", "\u00e9", "\ud83d\ude00", "\u0007", "\n", "\u7a7a"};
        Input.Action[] edits = {Input.Action.LEFT, Input.Action.RIGHT, Input.Action.HOME, Input.Action.END,
                Input.Action.DELETE, Input.Action.FORWARD_DELETE};
        for (int round = 0; round < 2000; round++) {
            TextEntry entry = new TextEntry();
            String initial = round % 3 == 0 ? "" : "Sodium \ud83d\ude00";
            entry.begin(initial);
            for (int i = 0; i < 40; i++) {
                if (random.nextBoolean()) entry.insert(pieces[random.nextInt(pieces.length)]);
                else entry.edit(edits[random.nextInt(edits.length)]);
                String v = entry.value();
                int c = entry.caret();
                check(c >= 0 && c <= v.length(), "caret in range");
                check(c == 0 || c == v.length() || !Character.isLowSurrogate(v.charAt(c)), "caret on a code point");
                check(v.codePoints().noneMatch(cp -> cp < 32 || cp == 127), "no control characters");
            }
            String edited = entry.value();
            if (random.nextBoolean()) check(entry.accept().equals(edited), "accept keeps the edit");
            else check(entry.cancel().equals(initial) && entry.value().equals(initial), "cancel restores");
            check(!entry.active(), "inactive after finishing");
        }
    }

    // ------------------------------------------------------------------ screen

    /** A renderer without GL: geometry and text metrics only. */
    static final class HeadlessRenderer extends Renderer {
        HeadlessRenderer() {
            java.util.Arrays.fill(glyphWidth, 6);
            glyphWidth[' '] = 4;
        }
        @Override void setUp() {}
        @Override void begin(int w, int h) { width = w; height = h; s = h / 1080f; }
        @Override void end() {}
        @Override void rect(float x, float y, float w, float h, int color) {}
        @Override void text(String text, float x, float y, float scale, int color) {}
        @Override void image(int texture, float x, float y, float w, float h, int color) {
            check(texture > 0, "draws only uploaded textures");
        }
        @Override void image(int texture, float x, float y, float w, float h, float u0, float v0, float u1, float v1, int color) {
            check(texture > 0 && u0 >= 0 && v0 >= 0 && u1 <= 1.0001f && v1 <= 1.0001f && u0 <= u1 && v0 <= v1,
                    "image crop stays inside the texture");
        }
        int textures;
        @Override int upload(int w, int h, java.nio.ByteBuffer rgba) {
            check(rgba.remaining() == w * h * 4, "upload size matches");
            return ++textures;
        }
        @Override void deleteTexture(int id) {}
        @Override void clip(float x, float y, float w, float h) {}
        @Override void unclip() {}
    }

    /** A small PNG, as a picture from the web would arrive. */
    static byte[] png(int w, int h, int argb) {
        try {
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) image.setRGB(x, y, argb ^ (x * 31 + y));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /**
     * Generated .mrpack files and the files they list, served by URL. Some packs are hostile or
     * unsupported (escaping paths, foreign hosts, Forge, too new a Minecraft) and must be refused.
     */
    static final class FakePacks {
        final java.util.Map<String, byte[]> files = new java.util.concurrent.ConcurrentHashMap<>();
        final Random random;
        int hostile;
        FakePacks(Random random) { this.random = random; }

        synchronized ModrinthClient.ModFile pack(String id) throws IOException {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            int kind = random.nextInt(8);
            String minecraft = kind == 1 ? "1.21" : random.nextBoolean() ? "1.18.2" : "1.20.4";
            String loader = kind == 2 ? "forge" : random.nextInt(4) == 0 ? "quilt-loader" : "fabric-loader";
            com.google.gson.JsonObject index = new com.google.gson.JsonObject();
            index.addProperty("formatVersion", 1);
            index.addProperty("game", "minecraft");
            index.addProperty("versionId", "1." + random.nextInt(9));
            index.addProperty("name", "Pack " + id + (random.nextInt(4) == 0 ? "\u0007\u00e9" : ""));
            index.addProperty("summary", "A pack " + "x".repeat(random.nextInt(200)));
            com.google.gson.JsonObject deps = new com.google.gson.JsonObject();
            deps.addProperty("minecraft", minecraft);
            deps.addProperty(loader, "0.14.9");
            index.add("dependencies", deps);
            com.google.gson.JsonArray list = new com.google.gson.JsonArray();
            int n = random.nextInt(6);
            for (int i = 0; i < n; i++) {
                byte[] data = ("file " + id + " " + i + " " + random.nextInt()).getBytes(StandardCharsets.UTF_8);
                String path = i % 2 == 0 ? "mods/m" + i + ".jar" : "config/c" + i + ".txt";
                String url = "https://cdn.modrinth.com/data/" + id + "/" + i;
                if (kind == 3 && i == 0) path = "../escape-" + i + ".txt";
                if (kind == 4 && i == 0) path = "/abs/" + i;
                if (kind == 5 && i == 0) url = "https://evil.example/" + i;
                files.put(url, random.nextInt(12) == 0 ? new byte[]{9} : data);
                com.google.gson.JsonObject f = new com.google.gson.JsonObject();
                f.addProperty("path", path);
                com.google.gson.JsonObject hashes = new com.google.gson.JsonObject();
                hashes.addProperty("sha1", PackInstaller.hex("SHA-1", data));
                hashes.addProperty("sha512", PackInstaller.hex("SHA-512", data));
                f.add("hashes", hashes);
                com.google.gson.JsonArray downloads = new com.google.gson.JsonArray();
                downloads.add(url);
                f.add("downloads", downloads);
                f.addProperty("fileSize", data.length);
                list.add(f);
            }
            index.add("files", list);
            if (kind >= 1 && kind <= 5) hostile++;
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
                zip.putNextEntry(new java.util.zip.ZipEntry("modrinth.index.json"));
                zip.write(index.toString().getBytes(StandardCharsets.UTF_8));
                zip.putNextEntry(new java.util.zip.ZipEntry("overrides/config/pack.txt"));
                zip.write("override".getBytes(StandardCharsets.UTF_8));
                zip.putNextEntry(new java.util.zip.ZipEntry("client-overrides/options.txt"));
                zip.write("client".getBytes(StandardCharsets.UTF_8));
                if (kind == 6) {
                    zip.putNextEntry(new java.util.zip.ZipEntry("overrides/../../outside.txt"));
                    zip.write(1);
                    hostile++;
                }
            }
            byte[] archive = bytes.toByteArray();
            String url = "https://cdn.modrinth.com/data/" + id + "/pack.mrpack";
            files.put(url, archive);
            return new ModrinthClient.ModFile(url, id + ".mrpack", ModrinthClient.sha1(archive), archive.length);
        }

        byte[] fetch(String url) throws IOException {
            byte[] data = files.get(url);
            if (data == null) throw new IOException("HTTP 404 " + url);
            return data;
        }
    }

    static final class FakeModrinth extends ModrinthClient {
        final Random random;
        final FakePacks packs;
        final byte[] picture;
        FakeModrinth(Random random, FakePacks packs) {
            super("1.18.2");
            this.random = random;
            this.packs = packs;
            this.picture = png(40, 30, 0xff336699);
        }

        @Override Results search(String query, Type type, Sort sort, int offset) throws IOException {
            if (random.nextInt(10) == 0) throw new IOException("network unreachable");
            List<Hit> hits = new ArrayList<>();
            int total = random.nextInt(5) == 0 ? 0 : random.nextInt(70);
            int n = Math.max(0, Math.min(PAGE, total - offset));
            for (int i = offset; i < offset + n; i++)
                hits.add(new Hit((type == Type.MODPACK ? "k" : "p") + i, "mod-" + i, (type == Type.MODPACK ? "Pack " : "Mod ")
                        + i + " " + query, "A project with a description that is " + "x".repeat(random.nextInt(300)),
                        random.nextInt(1_000_000), "author" + i, random.nextInt(3) == 0 ? "" : "https://img.test/icon" + i
                        + ".png", List.of("optimization", "utility", "decoration").subList(0, random.nextInt(4)),
                        random.nextInt(5000), "", "2024-0" + (1 + random.nextInt(9)) + "-01T00:00:00Z", type));
            return new Results(hits, offset, total);
        }

        @Override Project project(String id) throws IOException {
            if (random.nextInt(10) == 0) throw new IOException("HTTP 500");
            List<GalleryImage> gallery = new ArrayList<>();
            for (int i = 0; i < random.nextInt(11); i++)
                gallery.add(new GalleryImage("https://img.test/g" + i + "_350.png", "https://img.test/g" + i + ".png",
                        random.nextBoolean() ? "" : "Shot " + i, "About shot " + i, random.nextInt(6) == 0));
            String body = String.join("\n", "# " + id, "", "Some **bold** and _quiet_ text with a [link](https://x).",
                    "", "- one", "  - two", "1. first", "", "![shot](https://img.test/body.png)", "",
                    "<img src=\"https://img.test/html.png\"> <br> <h2>Html</h2>", "```", "code line", "```", "---",
                    "> quoted " + "y".repeat(random.nextInt(400)), "| a | b |", "|---|---|", "| 1 | 2 |",
                    "x ".repeat(random.nextInt(800)));
            return new Project(id, "slug-" + id, id.startsWith("k") ? Type.MODPACK : Type.MOD, "Title " + id, "Summary", body,
                    "https://img.test/icon.png", gallery, random.nextBoolean() ? "MIT" : "", 1234, 56, List.of("utility"),
                    List.of("fabric"), List.of("1.18.2"), "2024-01-01T00:00:00Z", "required");
        }

        @Override List<VersionInfo> projectVersions(String id, Type type, boolean compatible) throws IOException {
            if (random.nextInt(10) == 0) throw new IOException("timeout");
            List<VersionInfo> out = new ArrayList<>();
            for (int i = 0; i < random.nextInt(compatible ? 8 : 20); i++) {
                ModFile file = type == Type.MODPACK ? packs.pack(id + "v" + i)
                        : new ModFile("https://cdn.modrinth.com/" + id + "/" + i, id + "-" + i + ".jar", "00", 1);
                out.add(new VersionInfo("v" + i, id, "Version " + i, "1." + i, i % 3 == 0 ? "release" : i % 3 == 1 ? "beta" : "alpha",
                        "2024-01-0" + (1 + i % 9) + "T00:00:00Z", i * 100L, List.of("1.18.2", "1.18.1"), List.of("fabric"),
                        file, List.of()));
            }
            return out;
        }

        @Override byte[] fetchPublic(String url) throws IOException {
            if (random.nextInt(5) == 0) throw new IOException("HTTP 404");
            return random.nextInt(8) == 0 ? new byte[]{1, 2, 3} : picture;
        }
    }

    static final class FakeInstaller extends ModInstaller {
        final Random random;
        final Path mods;
        FakeInstaller(ModrinthClient m, Path mods, Random random) { super(m, mods, null, java.util.Map.of()); this.mods = mods; this.random = random; }

        @Override Plan plan(String projectId, String title, ModrinthClient.Version pinned) throws IOException {
            if (random.nextInt(8) == 0) throw new IOException("network unreachable");
            List<Entry> entries = new ArrayList<>();
            int n = random.nextInt(4);
            for (int i = 0; i < n; i++) {
                ModrinthClient.ModFile file = new ModrinthClient.ModFile("https://x", projectId + "-" + i + ".jar", "00", 1);
                entries.add(new Entry(title + " " + i, new ModrinthClient.Version(projectId, "1." + i, file, List.of()),
                        new ModInspector.ModInfo(projectId + i, title, Set.of(), java.util.Map.of(), java.util.Map.of(), List.of(), Set.of()),
                        new byte[]{1}, null));
            }
            List<String> blockers = new ArrayList<>(), warnings = new ArrayList<>();
            if (random.nextInt(3) == 0) blockers.add("Blocked because " + "reason ".repeat(random.nextInt(40)));
            if (random.nextInt(3) == 0) warnings.add("Warning " + "detail ".repeat(random.nextInt(40)));
            return new Plan(entries, blockers, warnings);
        }

        @Override void install(Plan plan) throws IOException {
            for (Entry e : plan.entries()) Files.write(mods.resolve(e.version().file().filename()), e.data());
        }
    }

    static final class TestScreen extends LauncherScreen {
        final LauncherConfig config;
        final Random random;
        final FakePacks packs;
        int relaunches, consumed, closes, installs, compatLaunches, packFetches;
        TestScreen(LauncherConfig c, Renderer r, ModrinthClient m, ModInstaller i, Random random, FakePacks packs) {
            super(c, r, m, i);
            config = c;
            this.random = random;
            this.packs = packs;
        }

        @Override protected byte[] fetchPackFile(String url, long max) throws IOException {
            check(url.startsWith("https://cdn.modrinth.com/"), "pack files only from allowed hosts: " + url);
            packFetches++;
            return packs.fetch(url);
        }

        @Override protected List<VersionStore.GameVersion> fetchCatalog(VersionStore.Loader loader) throws IOException {
            if (random.nextInt(6) == 0) throw new IOException("offline");
            List<VersionStore.GameVersion> out = new ArrayList<>();
            for (String id : new String[]{"1.20.4", "1.19.2", "1.18.2", "1.16.5", "1.14"})
                out.add(new VersionStore.GameVersion(id, "https://example.invalid/" + id, "0"));
            return out;
        }

        /** A real install record and files under the versions folder, without the network. */
        @Override protected VersionStore.Installed installVersion(VersionStore.GameVersion version,
                VersionStore.Loader loader, InstanceStore.Operation op) throws IOException {
            if (random.nextInt(5) == 0) throw new IOException("download failed");
            String loaderVersion = loader == VersionStore.Loader.VANILLA ? "" : "0.16.0";
            String id = VersionStore.profileId(version.id(), loader, loaderVersion);
            Path dir = Files.createDirectories(config.versions.versionsRoot().resolve(id));
            Files.copy(config.metadata, dir.resolve("game.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(config.metadata, dir.resolve("boot.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.write(dir.resolve("client.jar"), new byte[]{1});
            Files.writeString(dir.resolve("install.json"), "{\"schema\":1,\"id\":\"" + id + "\",\"minecraft\":\""
                    + version.id() + "\",\"loader\":\"" + loader.name() + "\",\"loaderVersion\":\"" + loaderVersion
                    + "\",\"mainClass\":\"x.Main\",\"assetIndex\":\"1\",\"versionType\":\"release\","
                    + "\"jvm\":[\"-Dlog4j.configurationFile=/x\"],\"provides\":{\"minecraft\":\"" + version.id() + "\"}}");
            installs++;
            return config.versions.installed(dir.resolve("game.json"));
        }
        @Override void presentNow() {}
        @Override void closeWindow() { closes++; }
        /** No system dialog on the host: editing stays inline. */
        @Override protected boolean beginSystemKeyboard(String title, int maxLength) { return false; }

        /** The next start consumes the request; only the imported instance's directory is the host's cwd. */
        @Override int relaunch(String intent) {
            relaunches++;
            InstanceStore.Instance instance = config.selectedInstance();
            VersionStore.Installed version = config.installed(instance);
            if (version != null) {
                try {
                    String conf = Files.readString(config.runnerRoot.resolve("runner.conf"));
                    String options = Files.readString(config.runnerRoot.resolve("jvm-options.txt"));
                    check(conf.contains("jar=" + version.clientJar() + "\n"), "prepared jar is the version's client");
                    check(conf.contains("version_metadata=" + version.bootProfile() + "\n"), "prepared boot profile");
                    check(options.contains("-Dlog4j.configurationFile=/x\n"), "prepared version JVM options");
                    String addMods = version.loader() == VersionStore.Loader.QUILT ? "-Dloader.addMods=" : "-Dfabric.addMods=";
                    check(options.contains(addMods) == (version.loader() != VersionStore.Loader.VANILLA),
                            "compatibility mod for Fabric and Quilt only");
                    if (version.loader() != VersionStore.Loader.VANILLA) compatLaunches++;
                } catch (IOException failure) { throw new AssertionError(failure); }
            }
            try {
                LaunchRequest request = new LaunchPreparation(config).consume(intent);
                check(request != null && request.instanceId().equals(instance.id()), "request names the instance");
                check(instance.imported(), "consumed only where the working directory matches");
                consumed++;
            } catch (IOException failure) {
                check(!instance.imported(), "imported instance launch rejected: " + failure.getMessage());
            }
            return -1;
        }
    }

    private static void screen(long seed, int steps) throws Exception {
        Random random = new Random(seed);
        Path root = Files.createTempDirectory("launcher-test");
        Path runner = Files.createDirectories(root.resolve("runner"));
        Path game = Files.createDirectories(root.resolve("game/mods"));
        Files.writeString(runner.resolve("jvm-options.txt"), "-Xms1024m\n-Xmx1024m\n-XX:+UseParallelGC\n"
                + "-Dorbis.graphics.arenaMib=1792\n", StandardCharsets.UTF_8);
        // Shaped like the console's runner.conf, so Play's preparation accepts it.
        Files.writeString(runner.resolve("runner.conf"), "java_home=" + root.resolve("jdk") + "\njar=" + root.resolve("target.jar")
                + "\nworking_directory=" + root.resolve("game") + "\noutput_log=" + root.resolve("out.log")
                + "\ntemporary_directory=" + runner.resolve("tmp") + "\ndebug=true\ndisplay=gpu\ngraphics_bundle="
                + root.resolve("bundle") + "\nversion_metadata=" + root.resolve("version.json") + "\nlibraries="
                + root.resolve("libraries") + "\n", StandardCharsets.UTF_8);
        Path metadata = Files.writeString(root.resolve("version.json"),
                "{\"id\": \"fabric-loader-0.14.9.5-1.18.2\", \"mainClass\": \"net.fabricmc.loader.impl.launch.knot.KnotClient\"}");
        for (int i = 0; i < random.nextInt(30); i++)
            Files.write(game.resolve("mod" + i + (random.nextInt(4) == 0 ? ".jar.disabled" : ".jar")), new byte[]{1});
        System.setProperty("orbis.launcher.home", root.resolve("home").toString());
        System.setProperty("orbis.runner.root", runner.toString());
        System.setProperty("orbis.launcher.gameMetadata", metadata.toString());
        System.setProperty("user.dir", root.resolve("game").toString());
        LauncherConfig config = LauncherConfig.load();
        // A saved Microsoft account; without a client configuration its refresh fails safely.
        if (seed % 2 == 0) new AccountStore(config.home, config.runnerRoot).putMicrosoft(
                "0123456789abcdef0123456789abcdef", "Tester", "00000000-0000-0000-0000-000000000000", "refresh");
        FakePacks packs = new FakePacks(random);
        FakeModrinth modrinth = new FakeModrinth(random, packs);
        TestScreen screen = new TestScreen(config, new HeadlessRenderer(), modrinth,
                new FakeInstaller(modrinth, game, random), random, packs);

        // Play directly once: focus the actions, Play is the first.
        String conf = Files.readString(runner.resolve("runner.conf")), options = Files.readString(runner.resolve("jvm-options.txt"));
        screen.apply("", List.of(Input.Action.FOCUS, Input.Action.CONFIRM));
        for (long deadline = System.nanoTime() + 5_000_000_000L; screen.applyResults(); Thread.sleep(1))
            if (System.nanoTime() > deadline) throw new AssertionError("Play did not finish");
        check(screen.relaunches == 1, "direct Play reaches the restart");
        check(conf.equals(Files.readString(runner.resolve("runner.conf"))), "failed restart restores runner.conf");
        check(options.equals(Files.readString(runner.resolve("jvm-options.txt"))), "failed restart restores jvm-options");
        check(!Files.exists(config.home.resolve("next-start")), "failed restart leaves no pending launch");
        screen.apply("", List.of(Input.Action.FOCUS));

        Deque<String> recent = new ArrayDeque<>();
        for (int step = 0; step < steps; step++) {
            List<Input.Action> actions = new ArrayList<>();
            int n = random.nextInt(3);
            for (int i = 0; i < n; i++) {
                // Bias towards confirming and moving, so deep pages are reached often.
                int pick = random.nextInt(14);
                actions.add(pick < 4 ? Input.Action.CONFIRM : pick < 7 ? Input.Action.DOWN
                        : pick < 8 ? Input.Action.BACK : ACTIONS[random.nextInt(ACTIONS.length)]);
            }
            String typed = random.nextInt(10) == 0 ? "ab c".substring(0, random.nextInt(5)) : "";
            // The pointer, against the last frame (1280x720): moves, clicks, right clicks and the wheel.
            List<Input.Mouse> pointer = new ArrayList<>();
            for (int i = 0, m = random.nextInt(3) == 0 ? random.nextInt(4) : 0; i < m; i++) {
                float px = random.nextFloat() * 1280, py = random.nextFloat() * 720;
                int pick = random.nextInt(10);
                pointer.add(pick < 4 ? new Input.Mouse(Input.MouseKind.MOVE, px, py, -1, 0)
                        : pick < 8 ? new Input.Mouse(Input.MouseKind.PRESS, px, py, pick == 7 ? 1 : 0, 0)
                        : new Input.Mouse(Input.MouseKind.WHEEL, px, py, -1, random.nextBoolean() ? 1 : -2));
            }
            recent.add(actions + " '" + typed + "' " + pointer);
            if (recent.size() > 12) recent.poll();
            try {
                screen.apply(typed, actions);
                // The wheel only scrolls: it selects nothing and changes no page.
                List<Input.Mouse> wheels = new ArrayList<>(), others = new ArrayList<>();
                for (Input.Mouse m : pointer) (m.kind() == Input.MouseKind.WHEEL ? wheels : others).add(m);
                String before = screen.selectionState();
                screen.applyMouse(wheels);
                check(before.equals(screen.selectionState()), "the wheel changed the selection: " + before + " -> " + screen.selectionState());
                screen.applyMouse(others);
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (screen.applyResults()) {
                    if (System.nanoTime() > deadline) throw new AssertionError("background work did not finish");
                    Thread.sleep(1);
                }
                screen.frame(1920, 1080);
                screen.frame(1280, 720);
                // Every Play here ends in a failed restart: the runner files must be back as they were.
                check(conf.equals(Files.readString(runner.resolve("runner.conf"))), "runner.conf restored");
                check(options.equals(Files.readString(runner.resolve("jvm-options.txt"))), "jvm-options restored");
            } catch (Throwable failure) {
                System.err.println("seed " + seed + " step " + step + "; last inputs: " + recent);
                throw failure;
            }
        }
        System.out.println("seed " + seed + ": " + steps + " steps, " + screen.installs + " version installs, "
                + screen.compatLaunches + " loader launches, "
                + config.instances.list().size() + " instances, " + screen.relaunches + " Play ("
                + screen.consumed + " consumed), "
                + screen.closes + " Quit, mods now " + listCount(game) + ", " + screen.packFetches + " pack downloads, "
                + packs.hostile + " hostile packs generated, " + config.instances.list().stream()
                .filter(i -> i.name().startsWith("Pack ")).count() + " pack instances");
        // An installed pack holds its overrides, client ones winning.
        for (InstanceStore.Instance i : config.instances.list()) {
            if (!i.name().startsWith("Pack ")) continue;
            check("override".equals(Files.readString(i.gameDir().resolve("config/pack.txt"))), "pack overrides copied");
            check("client".equals(Files.readString(i.gameDir().resolve("options.txt"))), "client overrides copied");
        }
        // Nothing a pack listed ever lands outside the instances.
        try (var walk = Files.walk(root)) {
            walk.filter(p -> p.getFileName().toString().startsWith("escape-") || p.getFileName().toString().equals("outside.txt"))
                    .forEach(p -> { throw new AssertionError("a pack wrote outside its instance: " + p); });
        }
    }

    private static long listCount(Path dir) throws IOException {
        try (var s = Files.list(dir)) { return s.count(); }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
