package orbis.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Why the last game session ended, from what it left behind after Play was pressed: Fabric's
 * dependency check in logs/latest.log (which writes no crash report), Minecraft's crash report,
 * or a JVM error file. A session that quit normally leaves none of them and yields no report.
 */
final class CrashAnalyzer {
    /** A finished analysis: a headline, explanation lines, and the file with the details. */
    record Report(String title, List<String> lines, Path source) {}

    private static final int MAX_LINES = 6;

    private CrashAnalyzer() {}

    static Report analyze(Path gameDir, Path jvmErrorDir, long since) {
        try {
            Report report = incompatibleMods(gameDir.resolve("logs/latest.log"), since);
            if (report != null) return report;
            Path crash = newest(gameDir.resolve("crash-reports"), "crash-*.txt", since);
            if (crash != null) return crashReport(crash);
            Path jvm = newest(jvmErrorDir, "hs_err_pid*.log", since);
            if (jvm != null) return jvmError(jvm);
        } catch (IOException | RuntimeException unreadable) {
            return new Report("COULD NOT READ THE LAST SESSION'S LOGS", List.of(String.valueOf(unreadable)), null);
        }
        return null;
    }

    private static Report incompatibleMods(Path log, long since) throws IOException {
        if (!modifiedAfter(log, since)) return null;
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        int start = -1;
        for (int i = 0; i < lines.size(); i++)
            if (lines.get(i).contains("Incompatible mods found!")) start = i;
        if (start < 0) return null;
        List<String> problems = new ArrayList<>();
        for (int i = start + 1; i < lines.size() && problems.size() < MAX_LINES; i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("- Mod ")) problems.add(line.substring(2));
            else if (line.startsWith("at ")) break;
        }
        if (problems.isEmpty()) problems.add("Fabric found mods that are incompatible with the game or each other.");
        return new Report("MODS ARE MISSING DEPENDENCIES OR INCOMPATIBLE", problems, log);
    }

    private static Report crashReport(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String description = "", exception = "";
        List<String> frames = new ArrayList<>();
        for (String line : lines) {
            if (description.isEmpty() && line.startsWith("Description: ")) description = line.substring(13).trim();
            else if (!description.isEmpty() && exception.isEmpty() && !line.isBlank()) exception = line.trim();
            else if (!exception.isEmpty() && line.trim().startsWith("at ")) frames.add(line.trim().substring(3));
            else if (!exception.isEmpty() && line.isBlank()) break;
        }
        String all = String.join("\n", lines);
        List<String> out = new ArrayList<>();
        if (all.contains("java.lang.OutOfMemoryError")) {
            out.add("The game ran out of memory.");
            Matcher size = Pattern.compile("Failed to allocate (\\d+) bytes").matcher(all);
            if (size.find()) out.add("A " + (Long.parseLong(size.group(1)) >> 20) + " MiB native allocation failed.");
            if (all.contains("net.coderbot.iris"))
                out.add("Iris rebuilt its shaders: pick a shader preset, then restart instead of changing it again.");
            out.add("Lower shader settings or render distance, or adjust memory in Settings.");
            return new Report("OUT OF MEMORY", out, file);
        }
        Matcher glfw = Pattern.compile("(glfw\\w+)\\(.*is not supported by the Orbis GLFW facade").matcher(all);
        if (glfw.find()) {
            out.add("A mod called " + glfw.group(1) + ", which the console's GLFW does not support yet.");
            String caller = firstCaller(frames);
            if (caller != null) out.add("Called from " + caller + ".");
            out.add("Remove that mod, or report the function so it can be added.");
            return new Report("UNSUPPORTED GLFW FUNCTION", out, file);
        }
        Matcher mixin = Pattern.compile("Mixin apply for mod (\\S+) failed (\\S+)").matcher(all);
        if (mixin.find()) {
            out.add("Mod " + mixin.group(1) + " could not patch the game (" + mixin.group(2) + ").");
            out.add("It is likely incompatible with another installed mod.");
            return new Report("A MOD FAILED TO LOAD", out, file);
        }
        if (!description.isEmpty()) out.add(description);
        if (!exception.isEmpty()) out.add(exception);
        String caller = firstCaller(frames);
        if (caller != null) out.add("In " + caller + ".");
        return new Report("THE GAME CRASHED", out, file);
    }

    private static Report jvmError(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String l = line.trim();
            if (l.startsWith("#  SIGSEGV") || l.startsWith("#  SIGBUS") || l.startsWith("#  Internal Error")
                    || l.startsWith("#  Out of Memory") || l.startsWith("# There is insufficient memory"))
                out.add(l.substring(1).trim());
            if (l.startsWith("# Problematic frame:")) out.add("Problematic frame:");
            else if (out.size() > 0 && out.get(out.size() - 1).equals("Problematic frame:") && l.startsWith("#"))
                out.add(l.substring(1).trim());
            if (out.size() >= 3) break;
        }
        if (out.isEmpty()) out.add("The Java runtime stopped with a fatal error.");
        out.add("This is a crash of the console runtime, not of a mod.");
        return new Report("THE JAVA RUNTIME CRASHED", out, file);
    }

    /** The first frame outside the JDK, LWJGL and Minecraft's own classes: usually the mod. */
    private static String firstCaller(List<String> frames) {
        for (String frame : frames) {
            String f = frame.replaceFirst("^knot//", "").replaceFirst("^[\\w.]+@[\\w.]+/", "");
            if (f.startsWith("java.") || f.startsWith("jdk.") || f.startsWith("sun.") || f.startsWith("org.lwjgl.")
                    || f.startsWith("net.minecraft.") || f.startsWith("net.fabricmc.") || f.startsWith("runner."))
                continue;
            int paren = f.indexOf('(');
            return paren > 0 ? f.substring(0, paren) : f;
        }
        return null;
    }

    private static Path newest(Path dir, String glob, long since) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        Path best = null;
        long bestTime = since;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, glob)) {
            for (Path file : files) {
                long time = Files.getLastModifiedTime(file).toMillis();
                if (time > bestTime) {
                    best = file;
                    bestTime = time;
                }
            }
        }
        return best;
    }

    private static boolean modifiedAfter(Path file, long since) throws IOException {
        return Files.isRegularFile(file) && Files.getLastModifiedTime(file).toMillis() > since;
    }
}
