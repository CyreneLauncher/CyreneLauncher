package orbis.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single instance the runner is set up for, and the settings the launcher edits: heap and
 * graphics arena in the runner's jvm-options.txt, and its debug log in runner.conf. Only those
 * lines are rewritten; everything else in both files is kept as it is.
 */
final class LauncherConfig {
    static final int MIN_HEAP_MIB = 768, MAX_HEAP_MIB = 1536;
    static final int MIN_ARENA_MIB = 1024, MAX_ARENA_MIB = 2048;
    static final int STEP_MIB = 128;
    /** Heap plus arena that has run on the console (1.5 GiB each); more leaves too little native memory. */
    static final int MAX_TOTAL_MIB = 3072;

    final Path home;
    final Path runnerRoot;
    final Path gameDir;
    final Path metadata;
    final String versionId;
    final String gameMainClass;
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

    /** Writes the settings; they take effect at the next start of the application. */
    void save() throws IOException {
        List<String> options = new ArrayList<>();
        for (String line : Files.readAllLines(jvmOptions(), StandardCharsets.UTF_8)) {
            String option = line.trim();
            if (option.startsWith("-Xms")) options.add("-Xms" + heapMib + "m");
            else if (option.startsWith("-Xmx")) options.add("-Xmx" + heapMib + "m");
            else if (option.startsWith("-Dorbis.graphics.arenaMib=")) options.add("-Dorbis.graphics.arenaMib=" + arenaMib);
            else options.add(line);
        }
        replace(jvmOptions(), options);
        List<String> conf = new ArrayList<>();
        boolean sawDebug = false;
        for (String line : Files.readAllLines(runnerConf(), StandardCharsets.UTF_8)) {
            if (line.trim().startsWith("debug=")) {
                conf.add("debug=" + debugLog);
                sawDebug = true;
            } else {
                conf.add(line);
            }
        }
        if (!sawDebug) conf.add("debug=" + debugLog);
        replace(runnerConf(), conf);
    }

    void writeNextStart() throws IOException {
        replace(lastPlay(), List.of(Long.toString(System.currentTimeMillis())));
        replace(nextStart(), List.of(gameMainClass));
    }

    /** Why the session started by the last Play ended, if it left a crash behind; else null. */
    CrashAnalyzer.Report lastSession() {
        long since;
        try {
            since = Long.parseLong(Files.readString(lastPlay(), StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException never) {
            return null;
        }
        return CrashAnalyzer.analyze(gameDir, jvmErrorDir(), since);
    }

    private static void replace(Path file, List<String> lines) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".new");
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
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
