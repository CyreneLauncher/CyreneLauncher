package orbis.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Shared runtime metadata plus the currently selected instance's game files and settings. */
final class LauncherConfig {
    static final int MIN_HEAP_MIB = 768, MAX_HEAP_MIB = 1536;
    static final int MIN_ARENA_MIB = 1024, MAX_ARENA_MIB = 2048;
    static final int STEP_MIB = 128;
    /** Heap plus arena that has run on the console (1.5 GiB each); more leaves too little native memory. */
    static final int MAX_TOTAL_MIB = 3072;

    final Path home;
    final Path runnerRoot;
    Path gameDir;
    final Path metadata;
    final InstanceStore instances;
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
        instances = new InstanceStore(home, gameDir, metadata, heapMib, arenaMib);
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

    /** Versions of the ids no mod JAR provides, for checking mods' requirements. */
    java.util.Map<String, String> builtInVersions() {
        java.util.Map<String, String> v = new java.util.HashMap<>();
        v.put("minecraft", gameVersion());
        if (!loaderVersion.isEmpty()) v.put("fabricloader", loaderVersion);
        v.put("java", System.getProperty("java.specification.version", "17"));
        return v;
    }

    /** The Minecraft version: the end of a loader profile id such as fabric-loader-0.14.9-1.18.2. */
    String gameVersion() {
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
