package orbis.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Modrinth modpacks (.mrpack, https://support.modrinth.com/en/articles/8802351): a ZIP holding
 * modrinth.index.json, which lists the files to download with their hashes and the Minecraft and
 * loader versions, and override folders copied over the game directory. A pack becomes a new
 * instance: its Minecraft version is installed (VersionStore), the instance created, every client
 * file downloaded and checked, the overrides copied. Every path stays inside the instance's game
 * directory; downloads come only from the hosts the format allows.
 */
class PackInstaller {
    /** One file to download; urls are tried in order. */
    record PackFile(String path, String sha1, String sha512, long size, List<String> urls) {}

    /** A read pack. blockers stop the install; warnings do not. */
    record Pack(String name, String versionId, String summary, String minecraft, VersionStore.Loader loader,
                String loaderVersion, List<PackFile> files, int overrides, List<String> blockers,
                List<String> warnings, Path archive) {
        boolean installable() { return blockers.isEmpty(); }
        long downloadBytes() { return files.stream().mapToLong(PackFile::size).sum(); }
    }

    /** The result of an install: the instance and the mods turned off because they cannot load. */
    record Installed(InstanceStore.Instance instance, List<String> disabled) {}

    /** Where file bytes come from; tests answer without the network. */
    interface Source { byte[] fetch(String url, long max) throws IOException; }

    /** The game profile for a Minecraft version and loader no older than loaderVersion; installs it when needed. */
    interface Profiles {
        Path profile(String minecraft, VersionStore.Loader loader, String loaderVersion, InstanceStore.Operation op)
                throws IOException;
    }

    static final Set<String> ALLOWED_HOSTS = Set.of("cdn.modrinth.com", "github.com", "raw.githubusercontent.com",
            "gitlab.com");
    private static final long MAX_INDEX = 32L << 20, MAX_FILE = 512L << 20, MAX_OVERRIDE = 512L << 20;
    private static final int MAX_FILES = 2000, THREADS = 4;
    /** Packs larger than this are likely beyond the console's memory and file-descriptor budget. */
    private static final int MANY_MODS = 150;

    private final Source source;
    private final Profiles profiles;
    private final InstanceStore instances;

    PackInstaller(Source source, Profiles profiles, InstanceStore instances) {
        this.source = source;
        this.profiles = profiles;
        this.instances = instances;
    }

    /** Downloads a pack version's .mrpack (checked against Modrinth's SHA-1) into folder and reads it. */
    Pack download(ModrinthClient.ModFile file, Path folder, InstanceStore.Operation op) throws IOException {
        if (!file.filename().toLowerCase(Locale.ROOT).endsWith(".mrpack"))
            throw new IOException(file.filename() + " is not a Modrinth modpack (.mrpack)");
        op.progress("Downloading " + file.filename() + "...");
        byte[] data = source.fetch(file.url(), MAX_FILE);
        if (!hex("SHA-1", data).equals(file.sha1()))
            throw new IOException(file.filename() + " does not match its published SHA-1");
        Files.createDirectories(folder);
        Path archive = Files.createTempFile(folder, "pack-", ".mrpack");
        Files.write(archive, data);
        op.check();
        return read(archive);
    }

    /** Reads and checks a .mrpack; problems become blockers or warnings, never an exception. */
    static Pack read(Path archive) throws IOException {
        List<String> blockers = new ArrayList<>(), warnings = new ArrayList<>();
        List<PackFile> files = new ArrayList<>();
        int overrides = 0;
        String name = "Modpack", versionId = "", summary = "", minecraft = "", loaderVersion = "";
        VersionStore.Loader loader = null;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
            if (indexEntry == null) throw new IOException("no modrinth.index.json: not a Modrinth modpack");
            if (indexEntry.getSize() > MAX_INDEX) throw new IOException("modrinth.index.json is too large");
            JsonObject index;
            try (InputStream in = zip.getInputStream(indexEntry)) {
                index = JsonParser.parseString(new String(in.readNBytes((int) MAX_INDEX), StandardCharsets.UTF_8))
                        .getAsJsonObject();
            } catch (RuntimeException malformed) {
                throw new IOException("modrinth.index.json is not valid JSON");
            }
            if (!"minecraft".equals(text(index, "game"))) blockers.add("The pack is not for Minecraft.");
            if (!index.has("formatVersion") || index.get("formatVersion").getAsInt() != 1)
                blockers.add("Unsupported pack format version " + text(index, "formatVersion") + ".");
            name = clean(text(index, "name"), "Modpack");
            versionId = clean(text(index, "versionId"), "");
            summary = clean(text(index, "summary"), "");
            JsonObject deps = index.has("dependencies") && index.get("dependencies").isJsonObject()
                    ? index.getAsJsonObject("dependencies") : new JsonObject();
            minecraft = text(deps, "minecraft");
            if (deps.has("forge") || deps.has("neoforge"))
                blockers.add("This pack needs " + (deps.has("neoforge") ? "NeoForge" : "Forge")
                        + "; the launcher runs Fabric and Quilt packs.");
            else if (deps.has("quilt-loader")) { loader = VersionStore.Loader.QUILT; loaderVersion = text(deps, "quilt-loader"); }
            else if (deps.has("fabric-loader")) { loader = VersionStore.Loader.FABRIC; loaderVersion = text(deps, "fabric-loader"); }
            else blockers.add("The pack names no Fabric or Quilt loader.");
            if (minecraft.isEmpty()) blockers.add("The pack names no Minecraft version.");
            else if (!VersionStore.inRange(minecraft))
                blockers.add("The pack is for Minecraft " + minecraft + "; the console runs 1.14 to "
                        + VersionStore.newest() + (VersionStore.offeredJava >= 21 ? "." : " (1.21.11 with the Java 21 runner)."));
            if (!loaderVersion.isEmpty() && !loaderVersion.matches("[0-9][0-9A-Za-z.+-]{0,63}"))
                blockers.add("Unexpected loader version " + loaderVersion + ".");

            JsonElement list = index.get("files");
            if (list != null && list.isJsonArray()) {
                for (JsonElement element : list.getAsJsonArray()) {
                    if (files.size() >= MAX_FILES) { blockers.add("The pack lists more than " + MAX_FILES + " files."); break; }
                    JsonObject f = element.getAsJsonObject();
                    String path = text(f, "path");
                    if (f.has("env") && f.get("env").isJsonObject()
                            && "unsupported".equals(text(f.getAsJsonObject("env"), "client"))) continue;
                    String problem = pathProblem(path);
                    if (problem != null) { blockers.add("Unsafe file path in the pack: " + path + " (" + problem + ")."); continue; }
                    JsonObject hashes = f.has("hashes") && f.get("hashes").isJsonObject() ? f.getAsJsonObject("hashes") : new JsonObject();
                    String sha1 = text(hashes, "sha1").toLowerCase(Locale.ROOT), sha512 = text(hashes, "sha512").toLowerCase(Locale.ROOT);
                    if (!sha1.matches("[0-9a-f]{40}")) { blockers.add(path + " has no SHA-1 hash."); continue; }
                    if (!sha512.isEmpty() && !sha512.matches("[0-9a-f]{128}")) sha512 = "";
                    List<String> urls = new ArrayList<>();
                    if (f.has("downloads") && f.get("downloads").isJsonArray())
                        for (JsonElement u : f.getAsJsonArray("downloads"))
                            if (u.isJsonPrimitive() && allowed(u.getAsString())) urls.add(u.getAsString());
                    if (urls.isEmpty()) { blockers.add(path + " has no download from an allowed host."); continue; }
                    long size = f.has("fileSize") ? f.get("fileSize").getAsLong() : -1;
                    if (size > MAX_FILE) { blockers.add(path + " is larger than " + (MAX_FILE >> 20) + " MiB."); continue; }
                    files.add(new PackFile(path, sha1, sha512, size, urls));
                }
            }
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                String n = entry.getName();
                String rest = overridePath(n);
                if (rest == null || entry.isDirectory()) continue;
                String problem = pathProblem(rest);
                if (problem != null) blockers.add("Unsafe override path in the pack: " + n + " (" + problem + ").");
                else overrides++;
            }
        } catch (java.util.zip.ZipException notZip) {
            throw new IOException("the file is not a valid .mrpack archive");
        } catch (RuntimeException malformed) {
            throw new IOException("modrinth.index.json is malformed: " + malformed.getMessage());
        }
        long mods = files.stream().filter(f -> f.path().startsWith("mods/")).count();
        if (mods > MANY_MODS)
            warnings.add("The pack has " + mods + " mods; large packs may run out of memory on the console.");
        if (loader == VersionStore.Loader.QUILT && !minecraft.isEmpty())
            warnings.add("Quilt packs are less tested on the console than Fabric packs.");
        return new Pack(name, versionId, summary, minecraft, loader, loaderVersion, List.copyOf(files), overrides,
                List.copyOf(blockers), List.copyOf(warnings), archive);
    }

    /**
     * Creates the instance and fills it. On failure or cancel the half-made instance is removed again.
     * Mods with native libraries, which cannot load on the console, are kept but disabled.
     */
    Installed install(Pack pack, String instanceName, InstanceStore.Operation op) throws IOException {
        if (!pack.installable()) throw new IOException("The pack cannot be installed: " + pack.blockers().get(0));
        Path profile = profiles.profile(pack.minecraft(), pack.loader(), pack.loaderVersion(), op);
        op.check();
        InstanceStore.Instance instance = instances.create(uniqueName(instanceName), profile, op);
        boolean done = false;
        try {
            Path game = instance.gameDir();
            downloadFiles(pack, game, op);
            copyOverrides(pack.archive(), game, op);
            List<String> disabled = disableNatives(game.resolve("mods"), op);
            done = true;
            return new Installed(instance, disabled);
        } finally {
            if (!done) {
                try { instances.remove(instance.id(), true); }
                catch (IOException cleanup) { System.err.println("launcher: could not remove the unfinished pack instance: " + cleanup); }
            }
        }
    }

    private void downloadFiles(Pack pack, Path game, InstanceStore.Operation op) throws IOException {
        List<PackFile> files = pack.files();
        AtomicInteger finished = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS, task -> {
            Thread t = new Thread(task, "pack download");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (PackFile file : files) {
                jobs.add(pool.submit(() -> {
                    op.check();
                    fetchFile(file, game);
                    int n = finished.incrementAndGet();
                    op.progress("Downloading pack files " + n + " of " + files.size() + "...", (double) n / files.size());
                    return null;
                }));
            }
            for (Future<?> job : jobs) {
                try {
                    job.get();
                } catch (java.util.concurrent.ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof IOException io) throw io;
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    throw new IOException(cause);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException("Operation cancelled");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void fetchFile(PackFile file, Path game) throws IOException {
        IOException last = null;
        for (String url : file.urls()) {
            try {
                byte[] data = source.fetch(url, MAX_FILE);
                if (file.size() >= 0 && data.length != file.size())
                    throw new IOException(file.path() + ": size " + data.length + " is not the listed " + file.size());
                if (!hex("SHA-1", data).equals(file.sha1())) throw new IOException(file.path() + " does not match its SHA-1");
                if (!file.sha512().isEmpty() && !hex("SHA-512", data).equals(file.sha512()))
                    throw new IOException(file.path() + " does not match its SHA-512");
                write(game, file.path(), data);
                return;
            } catch (IOException failed) {
                last = failed;
            }
        }
        throw last != null ? last : new IOException("no download for " + file.path());
    }

    /** overrides/ first, then client-overrides/, which win; server-overrides/ are not for this side. */
    private static void copyOverrides(Path archive, Path game, InstanceStore.Operation op) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (String prefix : new String[]{"overrides/", "client-overrides/"}) {
                for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                    String name = entry.getName();
                    if (!name.startsWith(prefix) || entry.isDirectory()) continue;
                    String rest = name.substring(prefix.length());
                    if (rest.isEmpty() || pathProblem(rest) != null) throw new IOException("Unsafe override path " + name);
                    op.check();
                    try (InputStream in = zip.getInputStream(entry)) {
                        byte[] data = in.readNBytes((int) MAX_OVERRIDE + 1);
                        if (data.length > MAX_OVERRIDE) throw new IOException(name + " is too large");
                        write(game, rest, data);
                    }
                }
            }
        }
    }

    /** Mods with native libraries cannot load on the console; they are renamed to .jar.disabled. */
    private static List<String> disableNatives(Path mods, InstanceStore.Operation op) throws IOException {
        List<String> disabled = new ArrayList<>();
        if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) return disabled;
        op.progress("Checking mods for the console...");
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path jar : jars) {
                op.check();
                ModInspector.ModInfo info;
                try { info = ModInspector.inspect(Files.readAllBytes(jar)); }
                catch (IOException | RuntimeException unreadable) { continue; }
                if (info.natives().isEmpty()) continue;
                Files.move(jar, jar.resolveSibling(jar.getFileName() + ".disabled"), StandardCopyOption.REPLACE_EXISTING);
                disabled.add(info.name() != null ? info.name() : jar.getFileName().toString());
            }
        }
        return disabled;
    }

    /** Writes inside game only: no absolute paths, no "..", no symbolic links on the way. */
    private static void write(Path game, String relative, byte[] data) throws IOException {
        Path base = game.toAbsolutePath().normalize();
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base) || target.equals(base)) throw new IOException("Path escapes the instance: " + relative);
        for (Path p = target.getParent(); p != null && !p.equals(base); p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("Symbolic link in the path: " + relative);
        Files.createDirectories(target.getParent());
        if (Files.isSymbolicLink(target)) throw new IOException("Symbolic link in the path: " + relative);
        Path part = Files.createTempFile(target.getParent(), ".part-", "");
        try (OutputStream out = Files.newOutputStream(part)) {
            out.write(data);
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Why a pack path is unsafe, or null: it must be relative, plain and free of "." and ".." parts. */
    static String pathProblem(String path) {
        if (path == null || path.isEmpty()) return "empty";
        if (path.length() > 400) return "too long";
        if (path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:.*")) return "absolute";
        if (path.indexOf('\\') >= 0) return "backslash";
        if (path.chars().anyMatch(c -> c < 32 || c == 127)) return "control character";
        for (String part : path.split("/", -1)) {
            if (part.isEmpty()) return "empty part";
            if (part.equals(".") || part.equals("..")) return "relative part";
        }
        return null;
    }

    private static String overridePath(String name) {
        if (name.startsWith("overrides/")) return name.substring("overrides/".length());
        if (name.startsWith("client-overrides/")) return name.substring("client-overrides/".length());
        return null;
    }

    static boolean allowed(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && ALLOWED_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)) && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /** The pack's name, or "name (2)" and so on when an instance already has it. */
    private String uniqueName(String wanted) {
        String base = wanted.length() > 60 ? wanted.substring(0, 60).strip() : wanted;
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (InstanceStore.Instance i : instances.list()) taken.add(i.name().toLowerCase(Locale.ROOT));
        if (!taken.contains(base.toLowerCase(Locale.ROOT))) return base;
        for (int n = 2; ; n++) {
            String candidate = base + " (" + n + ")";
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    /** Names come from the pack: printable, one line, reasonable length. */
    private static String clean(String value, String fallback) {
        String v = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (v.length() > 120) v = v.substring(0, 120).strip();
        return v.isEmpty() ? fallback : v;
    }

    static String hex(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
