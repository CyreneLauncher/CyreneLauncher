package orbis.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Reversible runner settings transaction; only a matching short-lived intent can launch a game. */
final class LaunchPreparation {
    private static final int MAX_FILE_BYTES = 262144;
    private static final int MAX_JOURNAL_BYTES = 4 * ((MAX_FILE_BYTES + 2) / 3 * 4) + 16384;
    private final LauncherConfig config;
    private final Path journal, nextStart, optionsFile, confFile;

    LaunchPreparation(LauncherConfig config) {
        this(config, config.home, config.runnerRoot);
    }

    private LaunchPreparation(LauncherConfig config, Path home, Path runnerRoot) {
        this.config = config;
        journal = home.resolve("launch-preparation.json");
        nextStart = home.resolve("next-start");
        optionsFile = runnerRoot.resolve("jvm-options.txt");
        confFile = runnerRoot.resolve("runner.conf");
    }

    static void recoverAfterConfigFailure(Path home, Path runnerRoot) throws IOException {
        new LaunchPreparation(null, home, runnerRoot).rollback();
    }

    LaunchRequest prepare(InstanceStore.Instance instance, String accountId) throws IOException {
        rollback(); // A previous interrupted transaction cannot contaminate this one.
        validateInstance(instance);
        VersionStore.Installed version = config.installed(instance);
        LaunchRequest request = LaunchRequest.create(instance.id(), accountId);
        byte[] originalOptions = LauncherFiles.read(optionsFile, MAX_FILE_BYTES);
        byte[] originalConf = LauncherFiles.read(confFile, MAX_FILE_BYTES);
        byte[] preparedOptions = options(originalOptions, instance, version, compatibilityMod(version));
        byte[] preparedConf = configuration(originalConf, instance.gameDir(), config.debugLog, version);
        if (preparedOptions.length > MAX_FILE_BYTES || preparedConf.length > MAX_FILE_BYTES)
            throw new IOException("Prepared runner settings are too large");
        JsonObject saved = new JsonObject();
        saved.addProperty("schema", 1);
        saved.addProperty("preparation_id", request.preparationId());
        saved.addProperty("request", request.encode());
        put(saved, "options", originalOptions, preparedOptions);
        put(saved, "conf", originalConf, preparedConf);
        byte[] encodedJournal = saved.toString().getBytes(StandardCharsets.UTF_8);
        if (encodedJournal.length > MAX_JOURNAL_BYTES) throw new IOException("Launch journal is too large");
        LauncherFiles.write(journal, encodedJournal, true);
        try {
            LauncherFiles.write(optionsFile, preparedOptions, false);
            LauncherFiles.write(confFile, preparedConf, false);
            LauncherFiles.write(nextStart, request.encode().getBytes(StandardCharsets.UTF_8), false);
        } catch (IOException failure) {
            try { rollback(); } catch (IOException recovery) { failure.addSuppressed(recovery); }
            throw failure;
        }
        return request;
    }

    /** Consumes the intent first, restores global runner files, and returns a validated launch. */
    private static final long FALLBACK_MILLIS = 30_000;

    LaunchRequest consume(String invocationIntent) throws IOException {
        String text = null;
        try {
            if (Files.exists(nextStart, LinkOption.NOFOLLOW_LINKS)) {
                text = new String(LauncherFiles.read(nextStart, MAX_FILE_BYTES), StandardCharsets.UTF_8).trim();
                Files.delete(nextStart);
            }
            if (text == null) { rollback(); return null; }
            if (!text.startsWith("{")) {
                if (!text.equals(config.gameMainClass)) throw new IOException("Unknown legacy launch class");
                if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("A legacy launch cannot consume a new preparation");
                InstanceStore.Instance instance = config.selectedInstance();
                return LaunchRequest.create(instance.id(), "offline");
            }
            LaunchRequest request = LaunchRequest.decode(text);
            if (invocationIntent == null && System.currentTimeMillis() - request.createdAt() < FALLBACK_MILLIS) {
                // The system may drop LoadExec arguments; accept only a request made moments ago.
                System.err.println("launcher: no launch intent argument; accepting fresh preparation "
                        + request.preparationId());
            } else if (!request.preparationId().equals(invocationIntent))
                throw new IOException("Discarded a pending launch from another application start");
            JsonObject saved = readJournal();
            if (!request.preparationId().equals(saved.get("preparation_id").getAsString())
                    || !request.encode().equals(saved.get("request").getAsString()))
                throw new IOException("The pending launch does not match its prepared settings");
            InstanceStore.Instance instance = config.instances.get(request.instanceId());
            if (instance == null) throw new IOException("The pending instance no longer exists");
            validateInstance(instance);
            // CWD was selected by native bootstrap before JVM creation. Never substitute user.dir
            // for chdir: relative native and Java files must see the same instance directory.
            if (!Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().equals(instance.gameDir()))
                throw new IOException("Instance working directory was not applied; choose Play again");
            rollback();
            return request;
        } catch (IOException | RuntimeException invalid) {
            try { rollback(); } catch (IOException recovery) { invalid.addSuppressed(recovery); }
            if (invalid instanceof IOException io) throw io;
            throw new IOException("The pending launch could not be recovered");
        }
    }

    void rollback() throws IOException {
        LauncherFiles.rejectSymlinks(nextStart);
        Files.deleteIfExists(nextStart);
        if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) return;
        JsonObject saved;
        try { saved = readJournal(); }
        catch (RuntimeException invalid) { throw new IOException("The launch recovery journal is invalid"); }
        IOException failure = null;
        try { restore(saved, "options", optionsFile); }
        catch (IOException | RuntimeException conflict) { failure = new IOException("Could not restore JVM options", conflict); }
        try { restore(saved, "conf", confFile); }
        catch (IOException | RuntimeException conflict) {
            IOException next = new IOException("Could not restore runner configuration", conflict);
            if (failure == null) failure = next; else failure.addSuppressed(next);
        }
        if (failure != null) throw failure;
        Files.delete(journal);
    }

    private JsonObject readJournal() throws IOException {
        if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing launch preparation journal");
        JsonObject saved = JsonParser.parseString(new String(LauncherFiles.read(journal, MAX_JOURNAL_BYTES),
                StandardCharsets.UTF_8)).getAsJsonObject();
        if (saved.get("schema").getAsInt() != 1) throw new IOException("Unsupported launch journal schema");
        return saved;
    }

    private static void put(JsonObject saved, String key, byte[] original, byte[] prepared) {
        saved.addProperty(key + "_original", Base64.getEncoder().encodeToString(original));
        saved.addProperty(key + "_prepared", Base64.getEncoder().encodeToString(prepared));
    }

    private static void restore(JsonObject saved, String key, Path target) throws IOException {
        try {
            byte[] original = Base64.getDecoder().decode(saved.get(key + "_original").getAsString());
            byte[] prepared = Base64.getDecoder().decode(saved.get(key + "_prepared").getAsString());
            if (original.length > MAX_FILE_BYTES || prepared.length > MAX_FILE_BYTES)
                throw new IOException("Oversized recovery settings");
            byte[] current = LauncherFiles.read(target, MAX_FILE_BYTES);
            if (MessageDigest.isEqual(current, original)) return;
            if (!MessageDigest.isEqual(current, prepared)) {
                if (key.equals("conf")) {
                    LauncherFiles.write(target, mergeConfigurationRecovery(original, prepared, current), false);
                    return;
                }
                throw new IOException("Runner settings changed during launch; recovery preserved the newer file");
            }
            LauncherFiles.write(target, original, false);
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid recovery settings"); }
    }

    private void validateInstance(InstanceStore.Instance instance) throws IOException {
        if (instance == null) throw new IOException("Select an instance first");
        LauncherFiles.rejectSymlinks(instance.gameDir());
        if (!Files.isDirectory(instance.gameDir(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(instance.runtimeProfile(), LinkOption.NOFOLLOW_LINKS)
                || (!instance.runtimeProfile().equals(config.metadata.toAbsolutePath().normalize())
                    && config.installed(instance) == null))
            throw new IOException("The instance's installed runtime or game directory is missing");
        if (instance.heapMib() < LauncherConfig.MIN_HEAP_MIB || instance.heapMib() > LauncherConfig.MAX_HEAP_MIB
                || instance.arenaMib() < LauncherConfig.MIN_ARENA_MIB || instance.arenaMib() > LauncherConfig.MAX_ARENA_MIB
                || instance.heapMib() + instance.arenaMib() > LauncherConfig.MAX_TOTAL_MIB
                || instance.heapMib() % LauncherConfig.STEP_MIB != 0 || instance.arenaMib() % LauncherConfig.STEP_MIB != 0)
            throw new IOException("The instance's memory settings are invalid");
    }

    /** Options a downloaded version replaces with its own (install.json jvm). */
    private static final List<String> VERSION_OPTIONS = List.of("-DFabricMcEmu=", "-Dfabric.systemLibraries=",
            "-Dloader.systemLibraries=", "-Dlog4j.configurationFile=", "-Dfabric.addMods=", "-Dloader.addMods=");

    /**
     * The launcher's compatibility mod (launcher/compat, embedded in this JAR), extracted for Fabric and
     * Quilt versions and added through the loader's addMods property, never into the instance's mods.
     */
    private List<String> compatibilityMod(VersionStore.Installed version) throws IOException {
        if (version == null || version.loader() == VersionStore.Loader.VANILLA) return List.of();
        byte[] jar;
        try (java.io.InputStream in = LaunchPreparation.class.getResourceAsStream("orbis-compat.jar")) {
            if (in == null) throw new IOException("The launcher's compatibility mod is missing");
            jar = in.readAllBytes();
        }
        Path target = config.home.resolve("compat").resolve("orbis-compat.jar");
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || !MessageDigest.isEqual(LauncherFiles.read(target, MAX_FILE_BYTES), jar)) {
            Files.createDirectories(target.getParent());
            LauncherFiles.write(target, jar, false);
        }
        return List.of((version.loader() == VersionStore.Loader.QUILT ? "-Dloader.addMods=" : "-Dfabric.addMods=") + target);
    }

    private static byte[] options(byte[] original, InstanceStore.Instance instance, VersionStore.Installed version,
                                  List<String> extra) {
        List<String> result = new ArrayList<>();
        boolean min = false, max = false, arena = false;
        for (String line : new String(original, StandardCharsets.UTF_8).split("\\R")) {
            String value = line.trim();
            if (version != null && VERSION_OPTIONS.stream().anyMatch(value::startsWith)) continue;
            if (value.startsWith("-Xms")) { result.add("-Xms" + instance.heapMib() + "m"); min = true; }
            else if (value.startsWith("-Xmx")) { result.add("-Xmx" + instance.heapMib() + "m"); max = true; }
            else if (value.startsWith("-Dorbis.graphics.arenaMib=")) {
                result.add("-Dorbis.graphics.arenaMib=" + instance.arenaMib()); arena = true;
            } else result.add(line);
        }
        if (!min) result.add("-Xms" + instance.heapMib() + "m");
        if (!max) result.add("-Xmx" + instance.heapMib() + "m");
        if (!arena) result.add("-Dorbis.graphics.arenaMib=" + instance.arenaMib());
        if (version != null) result.addAll(version.jvmOptions());
        result.addAll(extra);
        return String.join("\n", result).concat("\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] configuration(byte[] original, Path gameDir, boolean debugLog, VersionStore.Installed version)
            throws IOException {
        java.util.Map<String, String> set = new java.util.LinkedHashMap<>();
        set.put("working_directory", gameDir.toString());
        set.put("debug", String.valueOf(debugLog));
        // A downloaded version boots on its own client JAR and profile, which starts the launcher first.
        if (version != null) {
            set.put("jar", version.clientJar().toString());
            set.put("version_metadata", version.bootProfile().toString());
        }
        for (String value : set.values())
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw new IOException("Invalid runner setting");
        if (!gameDir.isAbsolute()) throw new IOException("Invalid instance working directory");
        List<String> result = new ArrayList<>();
        java.util.Set<String> found = new java.util.HashSet<>();
        for (String line : new String(original, StandardCharsets.UTF_8).split("\\R")) {
            String key = configurationKey(line);
            if (set.containsKey(key)) { result.add(key + "=" + set.get(key)); found.add(key); }
            else result.add(line);
        }
        for (java.util.Map.Entry<String, String> entry : set.entrySet())
            if (!found.contains(entry.getKey())) result.add(entry.getKey() + "=" + entry.getValue());
        byte[] prepared = String.join("\n", result).concat("\n").getBytes(StandardCharsets.UTF_8);
        validateConfiguration(prepared);
        return prepared;
    }

    private static String configurationKey(String line) {
        String value = line.trim();
        int equal = value.indexOf('=');
        return equal < 0 || value.startsWith("#") ? "" : value.substring(0, equal).trim();
    }

    private static final java.util.Set<String> PREPARED_KEYS =
            java.util.Set.of("working_directory", "debug", "jar", "version_metadata");

    /** Restore only our configuration edits when another writer changed unrelated settings. */
    private static byte[] mergeConfigurationRecovery(byte[] original, byte[] prepared, byte[] current)
            throws IOException {
        java.util.Map<String, String> before = configurationValues(original);
        java.util.Map<String, String> applied = configurationValues(prepared);
        List<String> lines = new ArrayList<>();
        for (String line : new String(current, StandardCharsets.UTF_8).split("\n")) {
            String key = configurationKey(line);
            if (PREPARED_KEYS.contains(key)
                    && !java.util.Objects.equals(before.get(key), applied.get(key))
                    && line.substring(line.indexOf('=') + 1).trim().equals(applied.get(key))) {
                if (before.containsKey(key)) lines.add(key + "=" + before.get(key));
            } else lines.add(line);
        }
        byte[] merged = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        validateConfiguration(merged);
        return merged;
    }

    private static java.util.Map<String, String> configurationValues(byte[] bytes) throws IOException {
        validateConfiguration(bytes);
        java.util.Map<String, String> values = new java.util.HashMap<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            String key = configurationKey(line);
            if (!key.isEmpty()) values.put(key, line.substring(line.indexOf('=') + 1).trim());
        }
        return values;
    }

    private static void validateConfiguration(byte[] bytes) throws IOException {
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Map<String, String> values = new java.util.HashMap<>();
        java.util.Set<String> paths = java.util.Set.of("java_home", "jar", "working_directory", "output_log",
                "temporary_directory", "desktop_bundle", "graphics_bundle", "version_metadata", "libraries");
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n", -1)) {
            if (line.indexOf(0) >= 0 || line.getBytes(StandardCharsets.UTF_8).length > 1023)
                throw new IOException("Runner configuration contains an overlong or invalid line");
            String entry = line.trim();
            if (entry.isEmpty() || entry.startsWith("#")) continue;
            int equal = entry.indexOf('=');
            if (equal < 0) throw new IOException("Runner configuration requires key=value");
            String key = entry.substring(0, equal).trim(), value = entry.substring(equal + 1).trim();
            if (!seen.add(key)) throw new IOException("Runner configuration contains duplicate keys");
            values.put(key, value);
            if (paths.contains(key)) {
                if (!value.startsWith("/") || value.getBytes(StandardCharsets.UTF_8).length >= 512
                        || ((key.equals("desktop_bundle") || key.equals("graphics_bundle")) && value.contains(":")))
                    throw new IOException("Runner configuration contains an invalid path");
            } else if (key.equals("debug") || key.equals("input_diagnostics")) {
                if (!value.equals("true") && !value.equals("false")) throw new IOException("Invalid runner boolean");
            } else if (key.equals("display")) {
                if (!java.util.Set.of("console", "desktop", "gpu").contains(value)) throw new IOException("Invalid runner display");
            } else throw new IOException("Unknown runner configuration key");
        }
        if ("true".equals(values.get("debug"))) {
            for (String key : java.util.List.of("jar", "output_log")) {
                String value = values.get(key);
                if (value == null) continue;
                String normalized = Path.of(value).normalize().toString();
                if (normalized.equals("/data/orbis-jdk/runner/runner-debug.log")
                        || normalized.equals("/data/orbis-jdk/runner/runner-debug.log.1"))
                    throw new IOException("Runner debug log paths are reserved");
            }
        }
    }
}
