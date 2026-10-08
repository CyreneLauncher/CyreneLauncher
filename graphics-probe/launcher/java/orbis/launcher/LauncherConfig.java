package orbis.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Shared runtime metadata plus the currently selected instance's game files and settings. */
final class LauncherConfig {
    static final int MIN_HEAP_MIB = 768, MAX_HEAP_MIB = 1536;
    static final int MIN_ARENA_MIB = 1024, MAX_ARENA_MIB = 2048;
    static final int STEP_MIB = 128;
    /** runner_config.c's java_home when runner.conf names none. */
    static final String DEFAULT_JAVA_HOME = "/data/orbis-jdk/jdk";
    /** Heap plus arena that has run on the console (1.5 GiB each); more leaves too little native memory. */
    static final int MAX_TOTAL_MIB = 3072;

    final Path home;
    final Path runnerRoot;
    Path gameDir;
    final Path metadata;
    final InstanceStore instances;
    /** Downloaded Minecraft versions; the built-in runtime (metadata) is the installed 1.18.2. */
    final VersionStore versions;
    /** runner.conf's java_home (the default runner) and the other Java runtimes on the console. */
    final Runtimes runtimes;
    final String versionId;
    final String gameMainClass;
    final String loaderVersion;
    int heapMib;
    int arenaMib;
    boolean debugLog;

    private LauncherConfig(Path home, Path runnerRoot, Path gameDir, Path metadata) throws IOException {
        this.home = home;
        this.runnerRoot = runnerRoot;
        this.gameDir = gameDir;
        this.metadata = metadata;
        String json = Files.readString(metadata, StandardCharsets.UTF_8);
        versionId = field(json, "id", "unknown");
        gameMainClass = field(json, "mainClass", "net.minecraft.client.main.Main");
        Matcher loader = Pattern.compile("net\\.fabricmc:fabric-loader:([^\"]+)\"").matcher(json);
        loaderVersion = loader.find() ? loader.group(1) : "";
        heapMib = 1024;
        arenaMib = 1536;
        for (String line : Files.readAllLines(jvmOptions(), StandardCharsets.UTF_8)) {
            String option = line.trim();
            if (option.startsWith("-Xmx")) heapMib = mebibytes(option.substring(4), heapMib);
            else if (option.startsWith("-Dorbis.graphics.arenaMib="))
                arenaMib = parse(option.substring(option.indexOf('=') + 1), arenaMib);
        }
        for (String line : Files.readAllLines(runnerConf(), StandardCharsets.UTF_8))
            if (line.trim().startsWith("debug=")) debugLog = line.trim().endsWith("=true");
        Path settings = home.resolve("launcher-settings.json");
        if (Files.exists(settings)) {
            try {
                JsonObject saved = JsonParser.parseString(new String(LauncherFiles.read(settings, 4096),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                if (saved.get("schema").getAsInt() == 1) debugLog = saved.get("debugLog").getAsBoolean();
                else System.err.println("launcher: unsupported global settings schema");
            } catch (IOException | RuntimeException invalid) {
                System.err.println("launcher: ignoring invalid global settings: " + invalid.getMessage());
            }
        }
        Path libraries = runnerRoot.getParent().resolve("libraries"), assets = runnerRoot.getParent().resolve("assets");
        Path javaHome = Path.of(DEFAULT_JAVA_HOME);
        for (String line : Files.readAllLines(runnerConf(), StandardCharsets.UTF_8)) {
            if (line.trim().startsWith("libraries=")) libraries = Path.of(line.trim().substring("libraries=".length()));
            if (line.trim().startsWith("java_home=")) javaHome = Path.of(line.trim().substring("java_home=".length()));
        }
        runtimes = Runtimes.discover(javaHome, runnerRoot.getParent());
        VersionStore.offeredJava = runtimes.newest();
        List<String> arguments = Files.exists(runnerRoot.resolve("app-args.txt"))
                ? Files.readAllLines(runnerRoot.resolve("app-args.txt"), StandardCharsets.UTF_8) : List.of();
        for (int i = 0; i + 1 < arguments.size(); i++)
            if (arguments.get(i).trim().equals("--assetsDir")) assets = Path.of(arguments.get(i + 1).trim());
        versions = new VersionStore(home, libraries, assets, metadata);
        instances = new InstanceStore(home, gameDir, metadata, versions.versionsRoot(), heapMib, arenaMib);
        instances.load();
        use(instances.selected());
    }

    static LauncherConfig load() throws IOException {
        Path home = Path.of(System.getProperty("orbis.launcher.home", "/data/orbis-jdk/launcher"));
        Path runnerRoot = Path.of(System.getProperty("orbis.runner.root", "/data/orbis-jdk/runner"));
        Path gameDir = Path.of(System.getProperty("user.dir"));
        Path metadata = Path.of(System.getProperty("orbis.launcher.gameMetadata",
                "/data/orbis-jdk/minecraft-runner/version.json"));
        Files.createDirectories(home);
        return new LauncherConfig(home, runnerRoot, gameDir, metadata);
    }

    Path nextStart() { return home.resolve("next-start"); }
    Path modsDir() { return gameDir.resolve("mods"); }

    /** Selects an instance and updates every instance-scoped path and editable setting. */
    void selectInstance(String id) throws IOException { use(instances.select(id)); }

    InstanceStore.Instance selectedInstance() { return instances.selected(); }

    void refreshSelected() { use(instances.selected()); }

    private void use(InstanceStore.Instance instance) {
        gameDir = instance.gameDir();
        heapMib = instance.heapMib();
        arenaMib = instance.arenaMib();
    }

    /** The downloaded version an instance runs, or null for the built-in 1.18.2 runtime. */
    VersionStore.Installed installed(InstanceStore.Instance instance) {
        return instance.runtimeProfile().equals(metadata.toAbsolutePath().normalize()) ? null
                : versions.installed(instance.runtimeProfile());
    }

    /** Versions of the ids no mod JAR provides, for checking the selected instance's mods. */
    Map<String, String> builtInVersions() {
        VersionStore.Installed version = installed(selectedInstance());
        Map<String, String> v = new java.util.HashMap<>();
        if (version != null) v.putAll(version.provides());
        else {
            v.put("minecraft", builtInGameVersion());
            if (!loaderVersion.isEmpty()) v.put("fabricloader", loaderVersion);
        }
        // The runner the instance will start on, not the one the launcher runs on.
        Runtimes.Runtime runtime = version != null ? runtimes.forJava(version.java()) : runtimes.fallback;
        v.put("java", String.valueOf(runtime != null ? runtime.feature() : runtimes.fallback.feature()));
        return v;
    }

    /** The selected instance's Minecraft version. */
    String gameVersion() {
        VersionStore.Installed version = installed(selectedInstance());
        return version != null ? version.minecraft() : builtInGameVersion();
    }

    /** The selected instance's loader. */
    VersionStore.Loader loader() {
        VersionStore.Installed version = installed(selectedInstance());
        return version != null ? version.loader() : VersionStore.Loader.FABRIC;
    }

    /** What an instance runs, for display. */
    String describe(InstanceStore.Instance instance) {
        VersionStore.Installed version = installed(instance);
        return version != null ? version.title()
                : "Minecraft " + builtInGameVersion() + (loaderVersion.isEmpty() ? "" : "  Fabric " + loaderVersion);
    }

    /** The built-in runtime's Minecraft version: the end of a loader profile id such as fabric-loader-0.14.9-1.18.2. */
    String builtInGameVersion() {
        Matcher m = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)$").matcher(versionId);
        return m.find() ? m.group(1) : versionId;
    }
    Path lastPlay() { return home.resolve("last-play"); }
    Path jvmErrorDir() { return runnerRoot.resolve("tmp"); }
    Path jvmOptions() { return runnerRoot.resolve("jvm-options.txt"); }
    Path runnerConf() { return runnerRoot.resolve("runner.conf"); }

    int modCount() {
        int count = 0;
        try (DirectoryStream<Path> mods = Files.newDirectoryStream(gameDir.resolve("mods"), "*.jar")) {
            for (Path ignored : mods) count++;
        } catch (IOException missing) {
            return 0;
        }
        return count;
    }

    /** Saves selected instance memory and the global runner debug preference. */
    void save() throws IOException {
        instances.saveSettings(instances.selected().id(), heapMib, arenaMib, instances.selected().accountId());
        JsonObject global = new JsonObject();
        global.addProperty("schema", 1);
        global.addProperty("debugLog", debugLog);
        LauncherFiles.write(home.resolve("launcher-settings.json"),
                (global.toString() + "\n").getBytes(StandardCharsets.UTF_8), false);
    }

    /** Saves the selected instance's account override ("" follows the default); unsaved memory edits stay pending. */
    void setInstanceAccount(String accountId) throws IOException {
        InstanceStore.Instance current = instances.selected();
        instances.saveSettings(current.id(), current.heapMib(), current.arenaMib(), accountId);
    }

    void recordPlay(InstanceStore.Instance instance) throws IOException {
        JsonObject saved = new JsonObject();
        saved.addProperty("schema", 1);
        saved.addProperty("instance_id", instance.id());
        saved.addProperty("started_at", System.currentTimeMillis());
        LauncherFiles.write(lastPlay(), saved.toString().getBytes(StandardCharsets.UTF_8), false);
    }

    /** Why the session started by the last Play ended, if it left a crash behind; else null. */
    CrashAnalyzer.Report lastSession() {
        try {
            String text = new String(LauncherFiles.read(lastPlay(), 4096), StandardCharsets.UTF_8).trim();
            long since;
            InstanceStore.Instance instance;
            if (text.startsWith("{")) {
                JsonObject saved = JsonParser.parseString(text).getAsJsonObject();
                if (saved.get("schema").getAsInt() != 1) return null;
                since = saved.get("started_at").getAsLong();
                instance = instances.get(saved.get("instance_id").getAsString());
                if (instance == null) return null;
            } else { since = Long.parseLong(text); instance = selectedInstance(); }
            return CrashAnalyzer.analyze(instance.gameDir(), jvmErrorDir(), since);
        } catch (IOException | RuntimeException never) { return null; }
    }

    private static String field(String json, String name, String fallback) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : fallback;
    }

    private static int mebibytes(String value, int fallback) {
        String v = value.toLowerCase();
        try {
            if (v.endsWith("g")) return Integer.parseInt(v.substring(0, v.length() - 1)) * 1024;
            if (v.endsWith("m")) return Integer.parseInt(v.substring(0, v.length() - 1));
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    private static int parse(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
