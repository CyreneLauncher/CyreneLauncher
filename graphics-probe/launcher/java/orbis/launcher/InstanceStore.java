package orbis.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Versioned instance records. All managed files stay under launcher/instances/<opaque id>. */
final class InstanceStore {
    static final int SCHEMA = 1;
    record Instance(String id, String name, Path gameDir, Path runtimeProfile, String accountId,
                    int heapMib, int arenaMib, boolean imported) { }

    /** A worker owns its context. The screen can cancel it without changing another operation. */
    static final class Operation {
        private volatile boolean cancelled;
        private volatile String progress = "Preparing...";
        private volatile Path destination;
        void cancel() { cancelled = true; }
        boolean cancelled() { return cancelled; }
        String progress() { return progress; }
        Path destination() { return destination; }
        void check() {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException("Operation cancelled");
        }
        void progress(String value) { progress = value; check(); }
    }

    private final Path root;
    private final Path importedDir;
    private final Path runtimeProfile, versionsRoot;
    private final int importedHeap, importedArena;
    private final boolean launcherOnly;
    private final List<Instance> instances = new ArrayList<>();
    private String selectedId;
    private String recoveryWarning = "";

    InstanceStore(Path home, Path importedDir, Path runtimeProfile, Path versionsRoot, int heapMib, int arenaMib) {
        this(home, importedDir, runtimeProfile, versionsRoot, heapMib, arenaMib, false);
    }

    InstanceStore(Path home, Path importedDir, Path runtimeProfile, Path versionsRoot, int heapMib, int arenaMib, boolean launcherOnly) {
        this.launcherOnly = launcherOnly;
        this.root = home.toAbsolutePath().normalize().resolve("instances");
        this.importedDir = importedDir.toAbsolutePath().normalize();
        this.runtimeProfile = runtimeProfile.toAbsolutePath().normalize();
        this.versionsRoot = versionsRoot.toAbsolutePath().normalize();
        // Records only accept step-aligned values within limits; the runner's own may be anything.
        int heap = clamp(heapMib - heapMib % LauncherConfig.STEP_MIB, LauncherConfig.MIN_HEAP_MIB, LauncherConfig.MAX_HEAP_MIB);
        int arena = clamp(arenaMib - arenaMib % LauncherConfig.STEP_MIB, LauncherConfig.MIN_ARENA_MIB, LauncherConfig.MAX_ARENA_MIB);
        if (heap + arena > LauncherConfig.MAX_TOTAL_MIB) arena = Math.max(LauncherConfig.MIN_ARENA_MIB, LauncherConfig.MAX_TOTAL_MIB - heap);
        if (heap + arena > LauncherConfig.MAX_TOTAL_MIB) heap = LauncherConfig.MAX_TOTAL_MIB - arena;
        this.importedHeap = heap;
        this.importedArena = arena;
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    synchronized List<Instance> load() throws IOException {
        LauncherFiles.rejectSymlinks(root);
        Files.createDirectories(root);
        instances.clear();
        recoveryWarning = "";
        recoverPending();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || !validId(dir.getFileName().toString())) continue;
                Path record = dir.resolve("instance.json");
                if (!Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS)) {
                    warn("Unregistered instance files remain at " + dir.getFileName());
                    continue;
                }
                try {
                    Instance value = read(record);
                    if (instances.stream().anyMatch(x -> x.name().equalsIgnoreCase(value.name())))
                        throw new IOException("Duplicate display name");
                    instances.add(value);
                }
                catch (RuntimeException | IOException invalid) {
                    System.err.println("launcher: ignoring malformed instance record " + record + ": " + invalid.getMessage());
                    warn("An invalid instance record needs attention: " + dir.getFileName());
                }
                try {
                    Files.deleteIfExists(dir.resolve("delete-intent.json"));
                    Files.deleteIfExists(dir.resolve("operation.json"));
                } catch (IOException cleanup) { warn("Could not clear instance operation marker: " + dir.getFileName()); }
            }
        }
        instances.sort(Comparator.comparing(Instance::name, String.CASE_INSENSITIVE_ORDER));
        if (instances.isEmpty()) {
            String id = newId();
            Instance imported = new Instance(id, launcherOnly ? "New instance" : "Existing Minecraft", importedDir, runtimeProfile,
                    "", importedHeap, importedArena, true);
            writeRecord(imported);
            instances.add(imported);
        }
        Path selection = root.resolve("selected.json");
        if (Files.isRegularFile(selection, LinkOption.NOFOLLOW_LINKS)) {
            try {
                JsonObject saved = JsonParser.parseString(Files.readString(selection, StandardCharsets.UTF_8)).getAsJsonObject();
                if (saved.get("schema").getAsInt() == SCHEMA) selectedId = saved.get("id").getAsString();
            } catch (RuntimeException invalid) { selectedId = null; }
        }
        if (find(selectedId) == null) selectedId = instances.get(0).id();
        writeSelection();
        return list();
    }

    synchronized List<Instance> list() { return List.copyOf(instances); }
    synchronized String recoveryWarning() { return recoveryWarning; }
    synchronized Instance selected() { return find(selectedId); }
    synchronized Instance get(String id) { return find(id); }

    synchronized Instance select(String id) throws IOException {
        Instance value = require(id);
        String previous = selectedId;
        selectedId = id;
        try { writeSelection(); }
        catch (IOException failure) { selectedId = previous; throw failure; }
        return value;
    }

    Instance create(String name) throws IOException { return create(name, new Operation()); }

    Instance create(String name, Operation operation) throws IOException { return create(name, runtimeProfile, operation); }

    /** A new managed instance running the built-in runtime or a downloaded version's game profile. */
    Instance create(String name, Path profile, Operation operation) throws IOException {
        Path chosen = profile.toAbsolutePath().normalize();
        if (!knownProfile(chosen)) throw new IOException("That Minecraft version is not installed");
        LauncherFiles.rejectSymlinks(root);
        String clean = validName(name);
        synchronized (this) { uniqueName(clean, null); }
        String id = newId();
        Path stage = root.resolve(".staging-" + id);
        Path finalDir = root.resolve(id);
        try {
            operation.destination = finalDir.resolve("game");
            operation.progress("Creating " + clean);
            Files.createDirectory(stage);
            writeMarker(stage.resolve("operation.json"), id, "staging");
            Path game = stage.resolve("game");
            Files.createDirectory(game);
            Files.createDirectory(game.resolve("mods"));
            Files.createDirectory(game.resolve("config"));
            Files.createDirectory(game.resolve("saves"));
            operation.check();
            Instance value = new Instance(id, clean, finalDir.resolve("game"), chosen,
                    "", importedHeap, importedArena, false);
            writeRecord(stage, value);
            operation.check();
            moveDirectory(stage, finalDir);
            synchronized (this) { instances.add(value); sort(); }
            return value;
        } finally { if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) deleteTree(stage); }
    }

    Instance cloneInstance(String sourceId, String name, boolean copyWorlds) throws IOException {
        return cloneInstance(sourceId, name, copyWorlds, new Operation());
    }

    Instance cloneInstance(String sourceId, String name, boolean copyWorlds, Operation operation) throws IOException {
        LauncherFiles.rejectSymlinks(root);
        Instance source;
        String clean = validName(name);
        synchronized (this) { source = require(sourceId); uniqueName(clean, null); }
        rejectSymlinkComponents(source.gameDir());
        String id = newId();
        Path stage = root.resolve(".staging-" + id);
        Path finalDir = root.resolve(id);
        try {
            operation.destination = finalDir.resolve("game");
            operation.progress("Creating copy of " + source.name());
            Files.createDirectory(stage);
            writeMarker(stage.resolve("operation.json"), id, "staging");
            Path game = stage.resolve("game");
            Files.createDirectory(game);
            for (String part : copyWorlds ? List.of("mods", "config", "saves") : List.of("mods", "config")) {
                operation.progress("Copying " + part + "...");
                copySection(source.gameDir(), part, game, operation);
            }
            for (String file : List.of("options.txt", "optionsof.txt", "servers.dat")) {
                Path from = source.gameDir().resolve(file);
                if (Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
                    operation.progress("Copying " + file);
                    if (!Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(from))
                        throw new IOException("Unsupported configuration file: " + file);
                    Files.copy(from, game.resolve(file));
                }
            }
            if (!copyWorlds) Files.createDirectory(game.resolve("saves"));
            operation.check();
            Instance value = new Instance(id, clean, finalDir.resolve("game"), source.runtimeProfile(),
                    source.accountId(), source.heapMib(), source.arenaMib(), false);
            writeRecord(stage, value);
            operation.check();
            moveDirectory(stage, finalDir);
            synchronized (this) { instances.add(value); sort(); }
            return value;
        } finally { if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) deleteTree(stage); }
    }

    synchronized Instance rename(String id, String name) throws IOException {
        Instance old = require(id);
        String clean = validName(name);
        uniqueName(clean, id);
        Instance changed = new Instance(id, clean, old.gameDir(), old.runtimeProfile(), old.accountId(),
                old.heapMib(), old.arenaMib(), old.imported());
        writeRecord(changed);
        replace(old, changed);
        return changed;
    }

    synchronized Instance saveSettings(String id, int heapMib, int arenaMib, String accountId) throws IOException {
        Instance old = require(id);
        if (heapMib < LauncherConfig.MIN_HEAP_MIB || heapMib > LauncherConfig.MAX_HEAP_MIB
                || arenaMib < LauncherConfig.MIN_ARENA_MIB || arenaMib > LauncherConfig.MAX_ARENA_MIB
                || heapMib + arenaMib > LauncherConfig.MAX_TOTAL_MIB
                || heapMib % LauncherConfig.STEP_MIB != 0 || arenaMib % LauncherConfig.STEP_MIB != 0)
            throw new IOException("Invalid memory settings");
        Instance changed = new Instance(id, old.name(), old.gameDir(), old.runtimeProfile(),
                accountId == null ? "" : accountId, heapMib, arenaMib, old.imported());
        writeRecord(changed);
        replace(old, changed);
        return changed;
    }

    void remove(String id, boolean deleteFiles) throws IOException {
        LauncherFiles.rejectSymlinks(root);
        Path trash;
        synchronized (this) {
            Instance old = require(id);
            if (instances.size() <= 1) throw new IOException("Keep at least one instance");
            if (old.imported() && deleteFiles) throw new IOException("Imported game files cannot be deleted here");
            if (!old.imported() && !deleteFiles) throw new IOException("Managed instance removal requires deleting its files");
            Path dir = recordDir(id);
            trash = root.resolve(".deleting-" + id);
            if (Files.exists(trash, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Pending deletion already exists for this instance");
            writeMarker(dir.resolve("delete-intent.json"), id, "delete");
            moveDirectory(dir, trash);
            String previous = selectedId;
            instances.remove(old);
            if (id.equals(selectedId)) selectedId = instances.get(0).id();
            try { writeSelection(); }
            catch (IOException failure) {
                // A selection write failure precedes commit: restore both the record and memory.
                try {
                    moveDirectory(trash, dir);
                    instances.add(old);
                    sort();
                    selectedId = previous;
                    Files.deleteIfExists(dir.resolve("delete-intent.json"));
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                    warn("Instance deletion needs recovery: " + id);
                }
                throw failure;
            }
        }
        try { deleteTree(trash); }
        catch (IOException failure) {
            synchronized (this) { warn("Instance removed; file cleanup pending at " + trash.getFileName()); }
            System.err.println("launcher: pending instance cleanup " + trash + ": " + failure.getMessage());
        }
    }

    private void copySection(Path sourceGame, String part, Path targetGame, Operation op) throws IOException {
        Path source = sourceGame.resolve(part);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) { Files.createDirectory(targetGame.resolve(part)); return; }
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException(part + " is not a directory");
        Path target = targetGame.resolve(part);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                op.check();
                if (Files.isSymbolicLink(dir)) throw new IOException("Symlink in " + part + ": " + dir);
                Path relative = source.relativize(dir);
                Path destination = target.resolve(relative).normalize();
                if (!destination.startsWith(target)) throw new IOException("Escaping clone path");
                Files.createDirectory(destination);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                op.progress("Copying " + part + "/" + source.relativize(file));
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) throw new IOException("Unsupported file in " + part + ": " + file);
                Path destination = target.resolve(source.relativize(file)).normalize();
                if (!destination.startsWith(target)) throw new IOException("Escaping clone path");
                Files.copy(file, destination);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void rejectSymlinkComponents(Path path) throws IOException {
        Path part = path.toAbsolutePath().normalize().getRoot();
        for (Path name : path.toAbsolutePath().normalize()) {
            part = part.resolve(name);
            if (Files.isSymbolicLink(part)) throw new IOException("Symlink in source path: " + part);
        }
    }

    private Instance read(Path record) throws IOException {
        JsonObject o = JsonParser.parseString(Files.readString(record, StandardCharsets.UTF_8)).getAsJsonObject();
        if (o.get("schema").getAsInt() != SCHEMA) throw new IOException("Unsupported schema");
        String id = o.get("id").getAsString();
        if (!validId(id) || !recordDir(id).equals(record.getParent())) throw new IOException("Invalid ID/path");
        Path game = Path.of(o.get("gameDir").getAsString()).toAbsolutePath().normalize();
        Path profile = Path.of(o.get("runtimeProfile").getAsString()).toAbsolutePath().normalize();
        boolean imported = o.get("imported").getAsBoolean();
        if (!imported && !game.equals(recordDir(id).resolve("game"))) throw new IOException("Escaping managed game directory");
        // A moved built-in profile must not orphan every instance: unknown profiles fall back to it.
        if (!knownProfile(profile)) {
            System.err.println("launcher: instance " + id + " now uses runtime profile " + runtimeProfile);
            profile = runtimeProfile;
        }
        if (!imported && (Files.isSymbolicLink(game) || Files.isSymbolicLink(recordDir(id)))) throw new IOException("Symlinked managed path");
        String name = validName(o.get("name").getAsString());
        int heap = o.get("heapMib").getAsInt(), arena = o.get("arenaMib").getAsInt();
        if (heap < LauncherConfig.MIN_HEAP_MIB || heap > LauncherConfig.MAX_HEAP_MIB
                || arena < LauncherConfig.MIN_ARENA_MIB || arena > LauncherConfig.MAX_ARENA_MIB
                || heap + arena > LauncherConfig.MAX_TOTAL_MIB
                || heap % LauncherConfig.STEP_MIB != 0 || arena % LauncherConfig.STEP_MIB != 0)
            throw new IOException("Invalid memory settings");
        return new Instance(id, name, game, profile, o.get("accountId").getAsString(), heap, arena, imported);
    }

    /** The built-in runtime, or a downloaded version's game profile under launcher/versions/<id>/. */
    private boolean knownProfile(Path profile) {
        return profile.equals(runtimeProfile) || (profile.getFileName().toString().equals("game.json")
                && profile.getParent() != null && versionsRoot.equals(profile.getParent().getParent())
                && Files.isRegularFile(profile, LinkOption.NOFOLLOW_LINKS));
    }

    private void writeRecord(Instance value) throws IOException { writeRecord(recordDir(value.id()), value); }

    private void writeRecord(Path dir, Instance value) throws IOException {
        LauncherFiles.rejectSymlinks(dir);
        Files.createDirectories(dir);
        JsonObject o = new JsonObject();
        o.addProperty("schema", SCHEMA);
        o.addProperty("id", value.id());
        o.addProperty("name", value.name());
        o.addProperty("gameDir", value.gameDir().toString());
        o.addProperty("runtimeProfile", value.runtimeProfile().toString());
        o.addProperty("accountId", value.accountId());
        o.addProperty("heapMib", value.heapMib());
        o.addProperty("arenaMib", value.arenaMib());
        o.addProperty("imported", value.imported());
        writeJson(dir.resolve("instance.json"), o);
    }

    /** Only launcher-marked staging and deletion directories are eligible for automatic cleanup. */
    private void recoverPending() throws IOException {
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                String name = dir.getFileName().toString();
                String kind = name.startsWith(".staging-") ? "staging"
                        : name.startsWith(".deleting-") ? "delete" : null;
                if (kind == null) continue;
                String id = name.substring(kind.equals("staging") ? ".staging-".length() : ".deleting-".length());
                if (!validId(id) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                    warn("Unexpected pending instance path: " + name);
                    continue;
                }
                Path marker = dir.resolve(kind.equals("staging") ? "operation.json" : "delete-intent.json");
                if (!markerValid(marker, id, kind)) {
                    warn("Pending instance files need attention: " + name);
                    continue;
                }
                try { deleteTree(dir); }
                catch (IOException failure) {
                    warn("Pending instance cleanup failed: " + name);
                    System.err.println("launcher: pending instance cleanup " + dir + ": " + failure.getMessage());
                }
            }
        }
    }

    private static boolean markerValid(Path marker, String id, String kind) {
        try {
            JsonObject o = JsonParser.parseString(new String(LauncherFiles.read(marker, 512), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return o.get("schema").getAsInt() == SCHEMA && id.equals(o.get("id").getAsString())
                    && kind.equals(o.get("kind").getAsString());
        } catch (IOException | RuntimeException invalid) { return false; }
    }

    private static void writeMarker(Path path, String id, String kind) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("schema", SCHEMA);
        o.addProperty("id", id);
        o.addProperty("kind", kind);
        writeJson(path, o);
    }

    private void warn(String message) {
        if (recoveryWarning.isEmpty()) recoveryWarning = message;
        else recoveryWarning += "  " + message;
        System.err.println("launcher: " + message);
    }

    private void writeSelection() throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("schema", SCHEMA);
        o.addProperty("id", selectedId);
        writeJson(root.resolve("selected.json"), o);
    }

    private static void writeJson(Path target, JsonObject json) throws IOException {
        LauncherFiles.write(target, (json.toString() + "\n").getBytes(StandardCharsets.UTF_8), false);
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Instance destination already exists");
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(source, target); }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.equals(root.resolve("operation.json")) || file.equals(root.resolve("delete-intent.json")))
                    return FileVisitResult.CONTINUE;
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                if (dir.equals(root)) {
                    Files.deleteIfExists(dir.resolve("operation.json"));
                    Files.deleteIfExists(dir.resolve("delete-intent.json"));
                }
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    private synchronized void replace(Instance old, Instance value) { instances.set(instances.indexOf(old), value); sort(); }
    private void sort() { instances.sort(Comparator.comparing(Instance::name, String.CASE_INSENSITIVE_ORDER)); }
    private Instance find(String id) { return instances.stream().filter(x -> x.id().equals(id)).findFirst().orElse(null); }
    private Instance require(String id) throws IOException {
        Instance value = find(id);
        if (value == null) throw new IOException("Unknown instance");
        return value;
    }
    private void uniqueName(String name, String exceptId) throws IOException {
        if (instances.stream().anyMatch(x -> !x.id().equals(exceptId) && x.name().equalsIgnoreCase(name)))
            throw new IOException("An instance with that name already exists");
    }
    private Path recordDir(String id) { return root.resolve(id); }
    private static boolean validId(String id) { return id != null && id.matches("[0-9a-f]{32}"); }
    private static String newId() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String validName(String name) {
        if (name == null || name.isBlank() || name.length() > 80 || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Name must be 1-80 printable characters");
        return name.strip();
    }
}
