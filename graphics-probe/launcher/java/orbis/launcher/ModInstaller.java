package orbis.launcher;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Plans and performs a mod install: the mod plus whatever it needs that the instance lacks,
 * resolved through Modrinth, and what would stop it working on the console. Each mod is the newest
 * version whose fabric.mod.json requirements the installed and planned mods meet (VersionPredicate).
 */
class ModInstaller {
    /** A file to write; replaces is the installed JAR of the same mod it updates, or null. */
    record Entry(String title, ModrinthClient.Version version, ModInspector.ModInfo info, byte[] data, Path replaces) {}

    /** Entries to install (the requested mod first); blockers prevent installing, warnings do not. */
    record Plan(List<Entry> entries, List<String> blockers, List<String> warnings) {
        boolean installable() {
            return blockers.isEmpty() && !entries.isEmpty();
        }
    }

    /** How many of a project's newest versions are downloaded to find one that fits. */
    private static final int CANDIDATES = 6;

    private final ModrinthClient modrinth;
    private final Path modsDir;
    private final Set<String> supportedGlfw;
    private final Map<String, String> builtInVersions;

    /** builtInVersions: minecraft, fabricloader and java, which no mod JAR provides. */
    ModInstaller(ModrinthClient modrinth, Path modsDir, Path graphicsBundle, Map<String, String> builtInVersions) {
        this.modrinth = modrinth;
        this.modsDir = modsDir;
        this.supportedGlfw = supportedGlfw(graphicsBundle);
        this.builtInVersions = builtInVersions;
    }

    Plan plan(String projectId, String title) throws IOException {
        ModInspector.Installed installed = ModInspector.installed(modsDir, builtInVersions);
        // Versions present once the plan is installed: installed mods, then each planned entry.
        Map<String, String> versions = new HashMap<>(installed.versions());
        List<Entry> entries = new ArrayList<>();
        List<String> blockers = new ArrayList<>(), warnings = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        Entry main = choose(projectId, title, versions, blockers);
        visited.add(projectId);
        if (main == null) return new Plan(entries, blockers, warnings);
        Path current = installed.jarById().get(main.info().id());
        if (current != null) {
            if (current.getFileName().toString().equals(main.version().file().filename())
                    && ModrinthClient.sha1(Files.readAllBytes(current)).equals(main.version().file().sha1())) {
                blockers.add(main.title() + " " + main.version().versionNumber() + " is already installed and up to date.");
                return new Plan(entries, blockers, warnings);
            }
            main = new Entry(main.title(), main.version(), main.info(), main.data(), current);
            warnings.add("Updates the installed " + current.getFileName() + " to " + main.version().file().filename() + ".");
        }
        add(main, entries, versions);

        // Required Modrinth dependencies while something is still missing; then each missing id as a
        // Modrinth slug, which is usually the same as the mod id.
        for (int i = 0; i < entries.size(); i++) {
            for (ModrinthClient.Dependency d : entries.get(i).version().dependencies()) {
                if (!d.type().equals("required") || !visited.add(d.projectId())) continue;
                if (missingIds(entries, versions).isEmpty()) break;
                Entry dep = choose(d.projectId(), modrinth.title(d.projectId()), versions, blockers);
                if (dep != null && !versions.keySet().containsAll(dep.info().provides())) add(dep, entries, versions);
            }
        }
        for (String id : missingIds(entries, versions)) {
            if (!visited.add(id)) continue;
            Entry dep = choose(id, modrinth.title(id), versions, new ArrayList<>());
            if (dep != null && dep.info().provides().contains(id)) add(dep, entries, versions);
        }
        for (String id : missingIds(entries, versions))
            blockers.add("Needs \"" + id + "\", which is not installed and could not be found on Modrinth.");

        for (Entry e : entries) {
            if (!e.info().natives().isEmpty())
                blockers.add(e.title() + " contains native libraries (" + e.info().natives().get(0)
                        + "), which cannot load on the console.");
            Set<String> unsupported = new TreeSet<>(e.info().glfwCalls());
            unsupported.removeAll(supportedGlfw);
            if (!supportedGlfw.isEmpty() && !unsupported.isEmpty())
                warnings.add(e.title() + " calls GLFW functions the console does not support yet ("
                        + String.join(", ", unsupported) + "); it may crash when it uses them.");
        }
        // A planned mod may not fit what was planned after it.
        for (Entry e : entries)
            for (String conflict : conflicts(e.info(), versions))
                blockers.add(e.title() + " " + conflict);
        return new Plan(entries, blockers, warnings);
    }

    /** Writes the plan's files into the mods folder. */
    /**
     * Writes every file of the plan to a staging folder first, so a failed write leaves no partial
     * JAR among the mods; only complete files are then moved in.
     */
    void install(Plan plan) throws IOException {
        Files.createDirectories(modsDir);
        Path staging = Files.createTempDirectory(modsDir, ".staging-");
        try {
            List<Path> staged = new ArrayList<>();
            for (Entry e : plan.entries()) {
                String name = e.version().file().filename();
                if (!name.endsWith(".jar") || name.contains("/") || name.contains("\\") || name.startsWith("."))
                    throw new IOException("Unexpected mod file name: " + name);
                Path file = staging.resolve(name);
                Files.write(file, e.data());
                staged.add(file);
            }
            for (int i = 0; i < staged.size(); i++) {
                Entry e = plan.entries().get(i);
                Path target = modsDir.resolve(staged.get(i).getFileName());
                Files.move(staged.get(i), target, StandardCopyOption.REPLACE_EXISTING);
                if (e.replaces() != null && !e.replaces().equals(target)) Files.deleteIfExists(e.replaces());
            }
        } finally {
            try (DirectoryStream<Path> left = Files.newDirectoryStream(staging)) {
                for (Path p : left) Files.deleteIfExists(p);
            }
            Files.deleteIfExists(staging);
        }
    }

    private static void add(Entry entry, List<Entry> entries, Map<String, String> versions) {
        entries.add(entry);
        versions.putAll(entry.info().versions());
    }

    private static Set<String> missingIds(List<Entry> entries, Map<String, String> versions) {
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : entries) out.addAll(ModInspector.missing(e.info(), versions.keySet()).keySet());
        return out;
    }

    /** Requirements of info that a present mod does not meet, as "needs sodium 0.4.0-alpha5 (0.4.1 present)". */
    private static List<String> conflicts(ModInspector.ModInfo info, Map<String, String> versions) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> d : info.depends().entrySet()) {
            if (info.provides().contains(d.getKey())) continue;
            String present = versions.get(d.getKey());
            if (present != null && !VersionPredicate.matches(present, d.getValue()))
                out.add("needs " + d.getKey() + " " + d.getValue() + " (" + present + " present)");
        }
        return out;
    }

    /**
     * The newest of a project's versions whose requirements the present versions meet (absent mods
     * are resolved later), downloading at most CANDIDATES of them; null, with a blocker, when none fits.
     */
    private Entry choose(String projectId, String title, Map<String, String> versions, List<String> blockers)
            throws IOException {
        List<ModrinthClient.Version> candidates = modrinth.versions(projectId);
        if (candidates.isEmpty()) {
            blockers.add(title + " has no Fabric build for this Minecraft version.");
            return null;
        }
        String firstProblem = null;
        for (ModrinthClient.Version version : candidates.subList(0, Math.min(CANDIDATES, candidates.size()))) {
            byte[] data = modrinth.download(version.file());
            ModInspector.ModInfo info;
            try {
                info = ModInspector.inspect(data);
            } catch (IOException | RuntimeException broken) {
                if (firstProblem == null) firstProblem = "could not be read as a Fabric mod: " + broken.getMessage();
                continue;
            }
            if (info.id() == null) {
                if (firstProblem == null) firstProblem = "is not a Fabric mod (no fabric.mod.json)";
                continue;
            }
            List<String> conflicts = conflicts(info, versions);
            if (conflicts.isEmpty()) return new Entry(title, version, info, data, null);
            if (firstProblem == null)
                firstProblem = version.versionNumber() + " " + String.join(", ", conflicts);
        }
        blockers.add("No recent version of " + title + " works with the installed mods: " + firstProblem + ".");
        return null;
    }

    /** glfw_operations from the graphics bundle's manifest; empty when it cannot be read. */
    private static Set<String> supportedGlfw(Path bundle) {
        Set<String> out = new HashSet<>();
        if (bundle == null) return out;
        try (Reader in = Files.newBufferedReader(bundle.resolve("graphics-bundle.json"), StandardCharsets.UTF_8)) {
            JsonObject manifest = JsonParser.parseReader(in).getAsJsonObject();
            for (JsonElement profile : manifest.getAsJsonArray("profiles"))
                for (JsonElement op : profile.getAsJsonObject().getAsJsonArray("glfw_operations"))
                    out.add(op.getAsString());
        } catch (IOException | RuntimeException unreadable) {
            out.clear();
        }
        return out;
    }
}
