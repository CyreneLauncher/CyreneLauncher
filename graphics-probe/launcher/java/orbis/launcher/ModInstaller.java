package orbis.launcher;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Plans and performs a mod install: the mod plus whatever it needs that the instance lacks,
 * resolved through Modrinth, and what would stop it working on the console. Dependency version
 * ranges are not checked; presence is.
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

    private final ModrinthClient modrinth;
    private final Path modsDir;
    private final Set<String> supportedGlfw;

    ModInstaller(ModrinthClient modrinth, Path modsDir, Path graphicsBundle) {
        this.modrinth = modrinth;
        this.modsDir = modsDir;
        this.supportedGlfw = supportedGlfw(graphicsBundle);
    }

    Plan plan(String projectId, String title) throws IOException {
        ModInspector.Installed installed = ModInspector.installed(modsDir);
        Set<String> available = installed.ids();
        List<Entry> entries = new ArrayList<>();
        List<String> blockers = new ArrayList<>(), warnings = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Entry main = fetch(projectId, title, blockers);
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
        entries.add(main);
        Set<String> provided = new HashSet<>(available);
        provided.addAll(main.info().provides());

        // Required Modrinth dependencies first, while something is still missing; then each missing id
        // as a Modrinth slug, which is usually the same as the mod id.
        List<Entry> queue = new ArrayList<>(entries);
        for (int i = 0; i < queue.size(); i++) {
            Entry entry = queue.get(i);
            for (ModrinthClient.Dependency d : entry.version().dependencies()) {
                if (!d.type().equals("required") || !visited.add(d.projectId())) continue;
                if (missingIds(entries, provided).isEmpty()) break;
                Entry dep = fetch(d.projectId(), modrinth.title(d.projectId()), blockers);
                if (dep != null && !provided.containsAll(dep.info().provides())) {
                    entries.add(dep);
                    queue.add(dep);
                    provided.addAll(dep.info().provides());
                }
            }
        }
        for (String id : missingIds(entries, provided)) {
            if (!visited.add(id)) continue;
            List<String> silent = new ArrayList<>();
            Entry dep = fetch(id, modrinth.title(id), silent);
            if (dep != null && dep.info().provides().contains(id)) {
                entries.add(dep);
                provided.addAll(dep.info().provides());
            }
        }
        for (String id : missingIds(entries, provided))
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
        return new Plan(entries, blockers, warnings);
    }

    /** Writes the plan's files into the mods folder. */
    void install(Plan plan) throws IOException {
        Files.createDirectories(modsDir);
        for (Entry e : plan.entries()) {
            Path target = modsDir.resolve(e.version().file().filename());
            Path temporary = modsDir.resolve(e.version().file().filename() + ".part");
            Files.write(temporary, e.data());
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            if (e.replaces() != null && !e.replaces().equals(target)) Files.deleteIfExists(e.replaces());
        }
    }

    private Set<String> missingIds(List<Entry> entries, Set<String> provided) {
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : entries) out.addAll(ModInspector.missing(e.info(), provided).keySet());
        return out;
    }

    private Entry fetch(String projectId, String title, List<String> blockers) throws IOException {
        ModrinthClient.Version version = modrinth.latest(projectId);
        if (version == null) {
            blockers.add(title + " has no Fabric build for this Minecraft version.");
            return null;
        }
        byte[] data = modrinth.download(version.file());
        ModInspector.ModInfo info;
        try {
            info = ModInspector.inspect(data);
        } catch (IOException | RuntimeException broken) {
            blockers.add(title + " could not be read as a Fabric mod: " + broken.getMessage());
            return null;
        }
        if (info.id() == null) {
            blockers.add(title + " is not a Fabric mod (no fabric.mod.json).");
            return null;
        }
        return new Entry(title, version, info, data, null);
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
