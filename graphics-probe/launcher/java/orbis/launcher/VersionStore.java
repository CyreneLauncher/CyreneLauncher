package orbis.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Downloads Minecraft releases 1.14 to 1.20.4 (to 1.21.11 when a Java 21 runner is installed), with Fabric, Quilt or no loader, into the shared
 * libraries and assets the installed 1.18.2 already uses. Each version gets
 * launcher/versions/&lt;id&gt;/ holding its client JAR, a flattened profile the runner accepts
 * (game.json), the same profile booting the launcher (boot.json), and install.json, written last.
 *
 * <p>Up to 1.18.2 every profile runs on the audited LWJGL 3.2.2 Java artifacts and the Orbis GLFW
 * adapter: a client audit found no LWJGL member any of these releases uses beyond them except GLFW
 * 3.4's glfwGetPlatform, which the adapter provides, so their LWJGL is replaced by the installed
 * 1.18.2 entries. 1.19 to 1.20.4 declare LWJGL 3.3.1 or 3.3.2 and run on Mojang's 3.3.3 set (1.20.5's,
 * with lwjgl-freetype): Sodium 0.5 refuses anything older than 3.3.1, and 3.3.3 only adds to them.
 * 1.20.5 and newer declare LWJGL 3.3.3 and need Java 21; they keep their own LWJGL Java artifacts.
 * The runner's 3.3.3 profile pairs that set with Orbis natives; the Linux natives are skipped. An older gson (the launcher boots on each version's class path and needs
 * 2.8.6+) is replaced by the installed 1.18.2 entry.
 */
final class VersionStore {
    enum Loader {
        FABRIC("Fabric"), QUILT("Quilt"), VANILLA("Vanilla");
        final String title;
        Loader(String title) { this.title = title; }
    }

    record GameVersion(String id, String url, String sha1) { }

    record Installed(String id, String minecraft, Loader loader, String loaderVersion, Path gameProfile,
                     Path bootProfile, Path clientJar, String mainClass, String assetIndex, String versionType,
                     List<String> jvmOptions, Map<String, String> provides, int java) {
        String title() {
            return "Minecraft " + minecraft + (loader == Loader.VANILLA ? "" : "  " + loader.title + " " + loaderVersion);
        }
    }

    static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    static final String ASSETS_URL = "https://resources.download.minecraft.net/";
    static final String LAUNCHER_MAIN = "orbis.launcher.Launcher";
    static final String FABRIC_MAIN = "net.fabricmc.loader.impl.launch.knot.KnotClient";
    static final String QUILT_MAIN = "org.quiltmc.loader.impl.launch.knot.KnotClient";
    static final String VANILLA_MAIN = "net.minecraft.client.main.Main";
    /** The runner's own JAR stays a system library: the GLFW adapter reaches runner.gpu.NativeInput in it. */
    static final String SYSTEM_LIBRARIES = "/app0/app.jar";
    private static final int[] OLDEST = {1, 14}, NEWEST_17 = {1, 20, 4}, NEWEST_21 = {1, 21, 11},
            NEWEST_25 = {26, 3};
    /**
     * The newest Java a runner on the console provides (Runtimes.newest()); LauncherConfig sets it.
     * Java 17 stops at 1.20.4; a Java 21 runner adds 1.20.5 to 1.21.11 (LWJGL 3.3.3) and a Java 25
     * runner 26.1 to 26.2 (LWJGL 3.4.1; 26.2's OpenGL backend needs none of its Vulkan-side modules)
     * and 26.3 (LWJGL 3.4.3: SDL3 in place of GLFW, through the adapter's SDL facade).
     */
    static volatile int offeredJava = Runtime.version().feature();
    /** The LWJGL release whose Java artifacts a version keeps (the runner has a profile and natives for it). */
    static final String OWN_LWJGL = "3.3.3";
    /** Minecraft 26.1's LWJGL, whose Java artifacts are kept too (the runner's 3.4.1 profile). */
    static final String CURRENT_LWJGL = "3.4.1";
    /** Minecraft 26.3's LWJGL (SDL3), kept as well (the runner's 3.4.3 profile). */
    static final String SDL_LWJGL = "3.4.3";
    /** Mojang's LWJGL 3.3.3 Java artifacts as Minecraft 1.20.5 declares them: the runner's 3.3.3 profile pins. */
    private static final String OWN_LWJGL_LIBRARIES = """
            [
            {"name": "org.lwjgl:lwjgl-freetype:3.3.3", "downloads": {"artifact": {"path": "org/lwjgl/lwjgl-freetype/3.3.3/lwjgl-freetype-3.3.3.jar", "sha1": "a0db6c84a8becc8ca05f9dbfa985edc348a824c7", "size": 450896, "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl-freetype/3.3.3/lwjgl-freetype-3.3.3.jar"}}},
            {"name": "org.lwjgl:lwjgl-glfw:3.3.3", "downloads": {"artifact": {"path": "org/lwjgl/lwjgl-glfw/3.3.3/lwjgl-glfw-3.3.3.jar", "sha1": "efa1eb78c5ccd840e9f329717109b5e892d72f8e", "size": 135546, "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl-glfw/3.3.3/lwjgl-glfw-3.3.3.jar"}}},
            {"name": "org.lwjgl:lwjgl-jemalloc:3.3.3", "downloads": {"artifact": {"path": "org/lwjgl/lwjgl-jemalloc/3.3.3/lwjgl-jemalloc-3.3.3.jar", "sha1": "b543467b7ff3c6920539a88ee602d34098628be5", "size": 43896, "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl-jemalloc/3.3.3/lwjgl-jemalloc-3.3.3.jar"}}},
            {"name": "org.lwjgl:lwjgl-openal:3.3.3", "downloads": {"artifact": {"path": "org/lwjgl/lwjgl-openal/3.3.3/lwjgl-openal-3.3.3.jar", "sha1": "daada81ceb5fc0c291fbfdd4433cb8d9423577f2", "size": 110586, "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl-openal/3.3.3/lwjgl-openal-3.3.3.jar"}}},
            {"name": "org.lwjgl:lwjgl-opengl:3.3.3", "downloads": {"artifact": {"path": "org/lwjgl/lwjgl-opengl/3.3.3/lwjgl-opengl-3.3.3.jar", "sha1": "02f6b0147078396a58979125a4c947664e98293a", "size": 929192, "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl-opengl/3.3.3/lwjgl-opengl-3.3.3.jar"}}},
            {"name": "org.lwjgl:lwjgl-stb:3.3.3", "downloads": {"artifact": {"path": "org/lwjgl/lwjgl-stb/3.3.3/lwjgl-stb-3.3.3.jar", "sha1": "25dd6161988d7e65f71d5065c99902402ee32746", "size": 120283, "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl-stb/3.3.3/lwjgl-stb-3.3.3.jar"}}},
            {"name": "org.lwjgl:lwjgl-tinyfd:3.3.3", "downloads": {"artifact": {"path": "org/lwjgl/lwjgl-tinyfd/3.3.3/lwjgl-tinyfd-3.3.3.jar", "sha1": "82d755ca94b102e9ca77283b9e2dc46d1b15fbe5", "size": 13400, "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl-tinyfd/3.3.3/lwjgl-tinyfd-3.3.3.jar"}}},
            {"name": "org.lwjgl:lwjgl:3.3.3", "downloads": {"artifact": {"path": "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar", "sha1": "29589b5f87ed335a6c7e7ee6a5775f81f97ecb84", "size": 785029, "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar"}}}
            ]""";
    private static final int SCHEMA = 1;
    private static final long MAX_JSON = 16L << 20, MAX_FILE = 64L << 20;
    private static final int DOWNLOAD_THREADS = 6;
    private static final String USER_AGENT = "orbis-launcher (PlayStation 4 Minecraft launcher)";
    /** 1.x releases, and the year-numbered ones from 26.1 on. */
    private static final Pattern VERSION_ID = Pattern.compile("(1|2\\d)\\.\\d+(\\.\\d+)?");
    private static final Pattern PROFILE_ID = Pattern.compile("[A-Za-z0-9._+-]{1,96}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{40}");

    /** Refuses a game download unless a Minecraft owner has signed in. */
    interface Ownership { void require() throws IOException; }

    private final Path versionsRoot, libraries, assets, builtInProfile;
    private final Ownership ownership;

    VersionStore(Path launcherHome, Path libraries, Path assets, Path builtInProfile, Ownership ownership) {
        this.versionsRoot = launcherHome.toAbsolutePath().normalize().resolve("versions");
        this.libraries = libraries.toAbsolutePath().normalize();
        this.assets = assets.toAbsolutePath().normalize();
        this.builtInProfile = builtInProfile.toAbsolutePath().normalize();
        this.ownership = ownership;
    }

    Path versionsRoot() { return versionsRoot; }
    /** Where install() puts asset indexes and objects; the game must be given the same --assetsDir. */
    Path assets() { return assets; }

    // ------------------------------------------------------------------ catalog

    /** Releases in range (1.14 to 1.20.4, or 1.21.11 on Java 21) the loader supports, newest first. */
    List<GameVersion> catalog(Loader loader) throws IOException {
        JsonObject manifest = json(fetch(MANIFEST_URL, MAX_JSON));
        Set<String> supported = switch (loader) {
            case FABRIC -> ids(fetch("https://meta.fabricmc.net/v2/versions/game", MAX_JSON), "version");
            case QUILT -> ids(fetch("https://meta.quiltmc.org/v3/versions/game", MAX_JSON), "version");
            case VANILLA -> null;
        };
        List<GameVersion> out = new ArrayList<>();
        for (JsonElement element : manifest.getAsJsonArray("versions")) {
            JsonObject v = element.getAsJsonObject();
            String id = v.get("id").getAsString();
            if (!"release".equals(v.get("type").getAsString()) || !inRange(id)) continue;
            if (supported != null && !supported.contains(id)) continue;
            out.add(new GameVersion(id, v.get("url").getAsString(), v.get("sha1").getAsString()));
        }
        return out;
    }

    /** The newest stable loader for a release (Quilt's list is unordered and marks pre-releases with a hyphen). */
    String latestLoader(Loader loader, String minecraft) throws IOException {
        if (loader == Loader.VANILLA) return "";
        String url = loader == Loader.FABRIC ? "https://meta.fabricmc.net/v2/versions/loader/" + minecraft
                : "https://meta.quiltmc.org/v3/versions/loader/" + minecraft;
        String best = null;
        for (JsonElement element : JsonParser.parseString(utf8(fetch(url, MAX_JSON))).getAsJsonArray()) {
            JsonObject entry = element.getAsJsonObject().getAsJsonObject("loader");
            String version = entry.get("version").getAsString();
            boolean stable = !version.contains("-") && (loader == Loader.QUILT || entry.get("stable").getAsBoolean());
            if (stable && version.matches("[0-9.]+") && (best == null || compare(parts(version), parts(best)) > 0))
                best = version;
        }
        if (best == null) throw new IOException("No stable " + loader.title + " loader supports Minecraft " + minecraft);
        return best;
    }

    /** The newest release offered, for messages. */
    static String newest() {
        return offeredJava >= 25 ? "26.3" : offeredJava >= 21 ? "1.21.11" : "1.20.4";
    }

    static boolean inRange(String id) {
        if (!VERSION_ID.matcher(id).matches()) return false;
        int[] v = parts(id);
        return compare(v, OLDEST) >= 0
                && compare(v, offeredJava >= 25 ? NEWEST_25 : offeredJava >= 21 ? NEWEST_21 : NEWEST_17) <= 0;
    }

    // ------------------------------------------------------------------ installed versions

    static String profileId(String minecraft, Loader loader, String loaderVersion) {
        return minecraft + (loader == Loader.VANILLA ? "-vanilla" : "-" + loader.name().toLowerCase(Locale.ROOT)
                + "-" + loaderVersion);
    }

    /** The installed version a game profile belongs to, or null if it is not a complete install here. */
    Installed installed(Path gameProfile) {
        Path profile = gameProfile.toAbsolutePath().normalize();
        if (!profile.getParent().getParent().equals(versionsRoot) || !profile.getFileName().toString().equals("game.json"))
            return null;
        try { return read(profile.getParent()); }
        catch (IOException | RuntimeException invalid) { return null; }
    }

    /** Complete installs, newest Minecraft first. */
    List<Installed> list() {
        List<Installed> out = new ArrayList<>();
        try (var dirs = Files.newDirectoryStream(versionsRoot)) {
            for (Path dir : dirs) {
                try { out.add(read(dir)); } catch (IOException | RuntimeException incomplete) { /* Not installed. */ }
            }
        } catch (IOException missing) { return out; }
        out.sort((a, b) -> compare(parts(b.minecraft()), parts(a.minecraft())));
        return out;
    }

    private Installed read(Path dir) throws IOException {
        LauncherFiles.rejectSymlinks(dir);
        JsonObject o = json(LauncherFiles.read(dir.resolve("install.json"), MAX_JSON));
        if (o.get("schema").getAsInt() != SCHEMA) throw new IOException("Unsupported install record");
        String id = o.get("id").getAsString();
        if (!PROFILE_ID.matcher(id).matches() || !dir.getFileName().toString().equals(id))
            throw new IOException("Invalid install record");
        List<String> jvm = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("jvm")) jvm.add(e.getAsString());
        Map<String, String> provides = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("provides").entrySet())
            provides.put(e.getKey(), e.getValue().getAsString());
        String minecraft = o.get("minecraft").getAsString();
        int java = o.has("java") ? o.get("java").getAsInt() : requiredJava(minecraft);
        Installed value = new Installed(id, o.get("minecraft").getAsString(), Loader.valueOf(o.get("loader").getAsString()),
                o.get("loaderVersion").getAsString(), dir.resolve("game.json"), dir.resolve("boot.json"),
                dir.resolve("client.jar"), o.get("mainClass").getAsString(), o.get("assetIndex").getAsString(),
                o.get("versionType").getAsString(), List.copyOf(jvm), Map.copyOf(provides), java);
        for (Path file : List.of(value.gameProfile(), value.bootProfile(), value.clientJar()))
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Incomplete install");
        return value;
    }

    // ------------------------------------------------------------------ install

    Installed install(GameVersion version, Loader loader, InstanceStore.Operation op) throws IOException {
        return install(version, loader, op, true);
    }

    /** Host checks skip the assets (hundreds of MiB); the console always downloads them. */
    Installed install(GameVersion version, Loader loader, InstanceStore.Operation op, boolean withAssets)
            throws IOException {
        ownership.require();
        op.progress("Reading Minecraft " + version.id() + "...");
        JsonObject vanilla = json(verified(version.url(), version.sha1(), -1, MAX_JSON));
        String loaderVersion = latestLoader(loader, version.id());
        String id = profileId(version.id(), loader, loaderVersion);
        if (!PROFILE_ID.matcher(id).matches()) throw new IOException("Unexpected version name " + id);
        Path dir = versionsRoot.resolve(id);
        LauncherFiles.rejectSymlinks(dir);
        Files.createDirectories(dir);

        JsonObject loaderProfile = null;
        if (loader == Loader.FABRIC) loaderProfile = json(fetch("https://meta.fabricmc.net/v2/versions/loader/"
                + version.id() + "/" + loaderVersion + "/profile/json", MAX_JSON));
        else if (loader == Loader.QUILT) loaderProfile = json(fetch("https://meta.quiltmc.org/v3/versions/loader/"
                + version.id() + "/" + loaderVersion + "/profile/json", MAX_JSON));

        op.progress("Downloading Minecraft " + version.id() + "...");
        JsonObject client = vanilla.getAsJsonObject("downloads").getAsJsonObject("client");
        Path clientJar = dir.resolve("client.jar");
        ensure(clientJar, client.get("url").getAsString(), client.get("sha1").getAsString(), client.get("size").getAsLong());

        List<String> jvm = new ArrayList<>();
        if (loader == Loader.FABRIC) {
            jvm.add("-DFabricMcEmu=" + VANILLA_MAIN);
            jvm.add("-Dfabric.systemLibraries=" + SYSTEM_LIBRARIES);
        } else if (loader == Loader.QUILT) jvm.add("-Dloader.systemLibraries=" + SYSTEM_LIBRARIES);
        JsonObject logging = vanilla.has("logging") ? vanilla.getAsJsonObject("logging").getAsJsonObject("client") : null;
        if (logging != null) {
            // 1.14 to 1.16 ship log4j 2.8.1; Mojang's current configuration disables message lookups.
            JsonObject file = logging.getAsJsonObject("file");
            String name = file.get("id").getAsString();
            if (!PROFILE_ID.matcher(name).matches()) throw new IOException("Unexpected logging configuration name");
            Path config = assets.resolve("log_configs").resolve(name);
            ensure(config, file.get("url").getAsString(), file.get("sha1").getAsString(), file.get("size").getAsLong());
            jvm.add("-Dlog4j.configurationFile=" + config);
        }

        JsonArray merged = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        Map<String, String> provides = new LinkedHashMap<>();
        provides.put("minecraft", version.id());
        int java = vanilla.has("javaVersion") ? vanilla.getAsJsonObject("javaVersion").get("majorVersion").getAsInt()
                : requiredJava(version.id());
        if (loaderProfile != null) {
            JsonArray loaderLibraries = loaderProfile.getAsJsonArray("libraries");
            int index = 0;
            for (JsonElement element : loaderLibraries) {
                JsonObject library = element.getAsJsonObject();
                String name = library.get("name").getAsString();
                op.progress("Downloading " + loader.title + " libraries (" + ++index + "/" + loaderLibraries.size() + ")",
                        (double) index / loaderLibraries.size());
                if (!seen.add(key(name))) continue;
                JsonObject entry = mavenLibrary(library);
                merged.add(entry);
                if (name.startsWith("net.fabricmc:fabric-loader:")) provides.put("fabricloader", loaderVersion);
                if (name.startsWith("org.quiltmc:quilt-loader:")) {
                    provides.put("quilt_loader", loaderVersion);
                    String fabric = providedFabricLoader(libraries.resolve(path(entry)));
                    if (fabric != null) provides.put("fabricloader", fabric);
                }
            }
        }

        JsonObject builtIn = json(LauncherFiles.read(builtInProfile, MAX_JSON));
        List<JsonObject> lwjgl = new ArrayList<>();
        JsonObject builtInGson = null;
        for (JsonElement element : builtIn.getAsJsonArray("libraries")) {
            JsonObject library = element.getAsJsonObject();
            String name = library.get("name").getAsString();
            if (name.startsWith("org.lwjgl:")) lwjgl.add(library);
            else if (name.startsWith("com.google.code.gson:gson:")) builtInGson = library;
        }
        if (lwjgl.isEmpty() || builtInGson == null) throw new IOException("The installed 1.18.2 profile lacks LWJGL or gson");

        JsonArray vanillaLibraries = vanilla.getAsJsonArray("libraries");
        boolean ownLwjgl = declaresOnly(vanillaLibraries, OWN_LWJGL) || declaresOnly(vanillaLibraries, CURRENT_LWJGL)
                || declaresOnly(vanillaLibraries, SDL_LWJGL);
        boolean upgradedLwjgl = !ownLwjgl && upgradesToOwnLwjgl(vanillaLibraries);
        int index = 0;
        boolean lwjglAdded = false;
        for (JsonElement element : vanillaLibraries) {
            JsonObject library = element.getAsJsonObject();
            String name = library.get("name").getAsString();
            op.progress("Downloading Minecraft libraries (" + ++index + "/" + vanillaLibraries.size() + ")",
                    (double) index / vanillaLibraries.size());
            if (ownLwjgl && name.startsWith("org.lwjgl:")) {
                // Orbis natives come from the graphics bundle; the Linux ones are never used.
                if (name.split(":").length == 4 && name.split(":")[3].startsWith("natives-")) continue;
                lwjglAdded = true;
            } else if (upgradedLwjgl && name.startsWith("org.lwjgl:")) {
                if (!lwjglAdded) {
                    for (JsonElement own : ownLwjglLibraries()) {
                        JsonObject entry = own.getAsJsonObject();
                        ensureArtifact(entry);
                        seen.add(key(entry.get("name").getAsString()));
                        merged.add(entry);
                    }
                    lwjglAdded = true;
                }
                continue;
            } else if (name.startsWith("org.lwjgl:")) {
                if (!lwjglAdded) {
                    for (JsonObject entry : lwjgl) { requirePresent(entry); merged.add(entry); }
                    lwjglAdded = true;
                }
                continue;
            }
            if (name.startsWith("com.google.code.gson:gson:")
                    && compare(parts(name.split(":")[2]), parts("2.8.9")) < 0) {
                if (seen.add(key(name))) { requirePresent(builtInGson); merged.add(builtInGson); }
                continue;
            }
            if (!allowed(library.get("rules"))) continue;
            if (!seen.add(key(name))) continue;
            JsonObject downloads = library.getAsJsonObject("downloads");
            if (downloads != null && downloads.has("artifact")) {
                JsonObject artifact = downloads.getAsJsonObject("artifact");
                ensure(libraries.resolve(confined(artifact.get("path").getAsString())), artifact.get("url").getAsString(),
                        artifact.get("sha1").getAsString(), artifact.get("size").getAsLong());
            }
            merged.add(library);
        }
        if (!lwjglAdded) throw new IOException("Minecraft " + version.id() + " declares no LWJGL");

        JsonObject index2 = vanilla.getAsJsonObject("assetIndex");
        String assetIndex = index2.get("id").getAsString();
        if (!PROFILE_ID.matcher(assetIndex).matches()) throw new IOException("Unexpected asset index name");
        Path indexFile = assets.resolve("indexes").resolve(assetIndex + ".json");
        ensure(indexFile, index2.get("url").getAsString(), index2.get("sha1").getAsString(), index2.get("size").getAsLong());
        if (withAssets) downloadAssets(json(LauncherFiles.read(indexFile, MAX_JSON)), op);

        op.progress("Finishing Minecraft " + version.id() + "...");
        String mainClass = loader == Loader.FABRIC ? FABRIC_MAIN : loader == Loader.QUILT ? QUILT_MAIN : VANILLA_MAIN;
        if (loaderProfile != null && !loaderProfile.get("mainClass").getAsString().equals(mainClass))
            throw new IOException(loader.title + " now starts through " + loaderProfile.get("mainClass").getAsString());
        JsonObject game = vanilla.deepCopy();
        game.remove("inheritsFrom");
        game.addProperty("id", id);
        game.addProperty("mainClass", mainClass);
        game.add("libraries", merged);
        JsonObject boot = game.deepCopy();
        boot.addProperty("mainClass", LAUNCHER_MAIN);
        LauncherFiles.write(dir.resolve("game.json"), bytes(game), false);
        LauncherFiles.write(dir.resolve("boot.json"), bytes(boot), false);

        JsonObject record = new JsonObject();
        record.addProperty("schema", SCHEMA);
        record.addProperty("id", id);
        record.addProperty("minecraft", version.id());
        record.addProperty("loader", loader.name());
        record.addProperty("loaderVersion", loaderVersion);
        record.addProperty("mainClass", mainClass);
        record.addProperty("assetIndex", assetIndex);
        record.addProperty("versionType", vanilla.get("type").getAsString());
        record.addProperty("java", java);
        JsonArray options = new JsonArray();
        jvm.forEach(options::add);
        record.add("jvm", options);
        JsonObject provided = new JsonObject();
        provides.forEach(provided::addProperty);
        record.add("provides", provided);
        LauncherFiles.write(dir.resolve("install.json"), bytes(record), false);
        return read(dir);
    }

    private void downloadAssets(JsonObject index, InstanceStore.Operation op) throws IOException {
        Map<String, Long> objects = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : index.getAsJsonObject("objects").entrySet()) {
            JsonObject object = e.getValue().getAsJsonObject();
            String hash = object.get("hash").getAsString();
            if (!HASH.matcher(hash).matches()) throw new IOException("Invalid asset hash");
            objects.put(hash, object.get("size").getAsLong());
        }
        List<Map.Entry<String, Long>> missing = new ArrayList<>();
        for (Map.Entry<String, Long> object : objects.entrySet()) {
            Path file = assetPath(object.getKey());
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != object.getValue())
                missing.add(object);
        }
        long total = missing.stream().mapToLong(Map.Entry::getValue).sum();
        AtomicInteger done = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        op.progress("Downloading assets: " + missing.size() + " of " + objects.size() + " needed (" + (total >> 20) + " MiB)", 0);
        ExecutorService pool = Executors.newFixedThreadPool(DOWNLOAD_THREADS, task -> {
            Thread t = new Thread(task, "asset download");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> work = new ArrayList<>();
            for (Map.Entry<String, Long> object : missing) {
                work.add(pool.submit(() -> {
                    op.check();
                    String hash = object.getKey();
                    ensure(assetPath(hash), ASSETS_URL + hash.substring(0, 2) + "/" + hash, hash, object.getValue());
                    int n = done.incrementAndGet();
                    long b = bytes.addAndGet(object.getValue());
                    op.progress("Downloading assets: " + n + "/" + missing.size() + " (" + (b >> 20) + " of "
                            + (total >> 20) + " MiB)", total == 0 ? 1 : (double) b / total);
                    return null;
                }));
            }
            for (Future<?> future : work) {
                try { future.get(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Asset download interrupted");
                } catch (java.util.concurrent.ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof IOException io) throw io;
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    throw new IOException("Asset download failed", cause);
                }
            }
        } finally { pool.shutdownNow(); }
    }

    private Path assetPath(String hash) { return assets.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash); }

    /** A Maven library from a loader profile, downloaded and pinned to its SHA-1 (Fabric gives it; Quilt has .sha1 files). */
    private JsonObject mavenLibrary(JsonObject library) throws IOException {
        String name = library.get("name").getAsString();
        String repository = library.get("url").getAsString();
        if (!repository.startsWith("https://")) throw new IOException("Insecure library repository for " + name);
        if (!repository.endsWith("/")) repository += "/";
        String path = mavenPath(name);
        String url = repository + path;
        String sha1 = library.has("sha1") ? library.get("sha1").getAsString()
                : utf8(fetch(url + ".sha1", 1024)).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (!HASH.matcher(sha1).matches()) throw new IOException("No valid SHA-1 for " + name);
        long size = library.has("size") ? library.get("size").getAsLong() : -1;
        Path file = libraries.resolve(confined(path));
        ensure(file, url, sha1, size);
        JsonObject artifact = new JsonObject();
        artifact.addProperty("path", path);
        artifact.addProperty("sha1", sha1);
        artifact.addProperty("size", Files.size(file));
        artifact.addProperty("url", url);
        JsonObject downloads = new JsonObject();
        downloads.add("artifact", artifact);
        JsonObject entry = new JsonObject();
        entry.addProperty("name", name);
        entry.add("downloads", downloads);
        return entry;
    }

    /** Quilt's loader declares the Fabric Loader version it stands in for, which Fabric mods require. */
    private static String providedFabricLoader(Path jar) {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (!entry.getName().equals("quilt.mod.json")) continue;
                JsonObject mod = json(zip.readNBytes(1 << 20)).getAsJsonObject("quilt_loader");
                for (JsonElement p : mod.getAsJsonArray("provides")) {
                    if (!p.isJsonObject()) continue;
                    JsonObject provided = p.getAsJsonObject();
                    if ("fabricloader".equals(provided.get("id").getAsString())) return provided.get("version").getAsString();
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            System.err.println("launcher: could not read Quilt's Fabric compatibility version: " + unreadable);
        }
        return null;
    }

    private void requirePresent(JsonObject library) throws IOException {
        if (!allowed(library.get("rules")) || !library.getAsJsonObject("downloads").has("artifact")) return;
        Path file = libraries.resolve(confined(path(library)));
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Shared library missing: " + library.get("name").getAsString());
    }

    private static String path(JsonObject library) {
        return library.getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString();
    }

    /** Downloads unless the file is already there with the right size and SHA-1. */
    private static void ensure(Path file, String url, String sha1, long size) throws IOException {
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && (size < 0 || Files.size(file) == size)
                && sha1(Files.readAllBytes(file)).equals(sha1)) return;
        byte[] data = verified(url, sha1, size, MAX_FILE);
        Files.createDirectories(file.getParent());
        LauncherFiles.write(file, data, false);
    }

    private static byte[] verified(String url, String sha1, long size, long max) throws IOException {
        byte[] data = fetch(url, max);
        if (size >= 0 && data.length != size) throw new IOException(url + ": " + data.length + " bytes, expected " + size);
        if (!sha1(data).equals(sha1)) throw new IOException(url + ": SHA-1 does not match");
        return data;
    }

    // ------------------------------------------------------------------ helpers

    /** Mojang semantics for library rules on a Linux-like x86_64 system: last matching rule wins. */
    /** The version declares LWJGL only at this release (every org.lwjgl:module:version entry). */
    static boolean declaresOnly(JsonArray libraries, String release) {
        boolean any = false;
        for (JsonElement element : libraries) {
            String[] p = element.getAsJsonObject().get("name").getAsString().split(":");
            if (p.length != 3 || !p[0].equals("org.lwjgl")) continue;
            if (!p[2].equals(release)) return false;
            any = true;
        }
        return any;
    }

    /** The version declares LWJGL 3.3.x before 3.3.3 (1.19 to 1.20.4), which runs on the 3.3.3 set. */
    static boolean upgradesToOwnLwjgl(JsonArray libraries) {
        String release = null;
        for (JsonElement element : libraries) {
            String[] p = element.getAsJsonObject().get("name").getAsString().split(":");
            if (p.length != 3 || !p[0].equals("org.lwjgl")) continue;
            if (release != null && !release.equals(p[2])) return false;
            release = p[2];
        }
        return release != null && release.startsWith("3.3.") && compare(parts(release), parts(OWN_LWJGL)) < 0;
    }

    /** 1.19 to 1.20.4: declared LWJGL 3.3.1 or 3.3.2, run on the 3.3.3 set. */
    static boolean runsUpgradedLwjgl(String minecraft) {
        int[] v = parts(minecraft);
        return compare(v, new int[] {1, 19}) >= 0 && compare(v, new int[] {1, 20, 5}) < 0;
    }

    static JsonArray ownLwjglLibraries() {
        return JsonParser.parseString(OWN_LWJGL_LIBRARIES).getAsJsonArray();
    }

    /**
     * Installs of 1.19 to 1.20.4 made before they ran on LWJGL 3.3.3 carry the 1.18.2 LWJGL 3.2.2 entries:
     * their game and boot profiles get the 3.3.3 set in place (JARs downloaded and checked first).
     * True when a profile changed.
     */
    boolean upgradeLwjgl(Installed version) throws IOException {
        if (!runsUpgradedLwjgl(version.minecraft())) return false;
        boolean changed = false;
        for (Path profile : List.of(version.gameProfile(), version.bootProfile())) {
            JsonObject json = json(LauncherFiles.read(profile, MAX_JSON));
            JsonArray libraries = json.getAsJsonArray("libraries");
            if (declaresOnly(libraries, OWN_LWJGL)) continue;
            JsonArray updated = new JsonArray();
            boolean added = false;
            for (JsonElement element : libraries) {
                if (!element.getAsJsonObject().get("name").getAsString().startsWith("org.lwjgl:")) {
                    updated.add(element);
                } else if (!added) {
                    for (JsonElement own : ownLwjglLibraries()) {
                        ensureArtifact(own.getAsJsonObject());
                        updated.add(own);
                    }
                    added = true;
                }
            }
            if (!added) throw new IOException("Minecraft " + version.minecraft() + " declares no LWJGL");
            json.add("libraries", updated);
            LauncherFiles.write(profile, bytes(json), false);
            changed = true;
        }
        return changed;
    }

    private void ensureArtifact(JsonObject library) throws IOException {
        JsonObject artifact = library.getAsJsonObject("downloads").getAsJsonObject("artifact");
        ensure(libraries.resolve(confined(artifact.get("path").getAsString())), artifact.get("url").getAsString(),
                artifact.get("sha1").getAsString(), artifact.get("size").getAsLong());
    }

    /** The Java a release needs (Mojang's javaVersion), for install records written before it was kept. */
    static int requiredJava(String minecraft) {
        int[] v = parts(minecraft);
        if (compare(v, new int[] {26}) >= 0) return 25;
        if (compare(v, new int[] {1, 20, 5}) >= 0) return 21;
        if (compare(v, new int[] {1, 18}) >= 0) return 17;
        if (compare(v, new int[] {1, 17}) >= 0) return 16;
        return 8;
    }

    static boolean allowed(JsonElement rules) {
        if (rules == null || rules.isJsonNull()) return true;
        boolean allow = false;
        for (JsonElement element : rules.getAsJsonArray()) {
            JsonObject rule = element.getAsJsonObject();
            boolean matches = true;
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name")) matches = "linux".equals(os.get("name").getAsString());
                if (os.has("arch")) matches &= "x86_64".equals(os.get("arch").getAsString());
                if (os.has("version")) matches = false;
            }
            if (rule.has("features")) matches = false;
            if (matches) allow = "allow".equals(rule.get("action").getAsString());
        }
        return allow;
    }

    private static String key(String name) {
        String[] p = name.split(":");
        return p.length > 3 ? p[0] + ":" + p[1] + ":" + p[3] : p[0] + ":" + p[1];
    }

    static String mavenPath(String name) throws IOException {
        String[] p = name.split(":");
        if (p.length < 3 || p.length > 4) throw new IOException("Unsupported library name " + name);
        for (String part : p)
            if (!part.matches("[A-Za-z0-9._+-]+") || part.equals("..")) throw new IOException("Unsupported library name " + name);
        String file = p[1] + "-" + p[2] + (p.length == 4 ? "-" + p[3] : "") + ".jar";
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + file;
    }

    private static Path confined(String relative) throws IOException {
        Path path = Path.of(relative).normalize();
        if (path.isAbsolute() || path.startsWith("..") || relative.contains("\\")) throw new IOException("Unsafe path " + relative);
        return path;
    }

    private static Set<String> ids(byte[] data, String field) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement element : JsonParser.parseString(utf8(data)).getAsJsonArray())
            out.add(element.getAsJsonObject().get(field).getAsString());
        return out;
    }

    static int[] parts(String version) {
        String core = version.split("[-+]")[0];
        String[] p = core.split("\\.");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try { out[i] = Integer.parseInt(p[i]); } catch (NumberFormatException other) { out[i] = 0; }
        }
        return out;
    }

    static int compare(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    static byte[] fetch(String url, long max) throws IOException {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme())) throw new IOException("Refusing non-HTTPS download " + url);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("HTTP " + status + " from " + uri.getHost());
            long declared = connection.getContentLengthLong();
            if (declared > max) throw new IOException("Download from " + uri.getHost() + " is too large");
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(declared > 0 ? (int) declared : 65536);
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    if (out.size() + n > max) throw new IOException("Download from " + uri.getHost() + " is too large");
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        } finally { connection.disconnect(); }
    }

    static String sha1(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static JsonObject json(byte[] data) { return JsonParser.parseString(utf8(data)).getAsJsonObject(); }
    private static String utf8(byte[] data) { return new String(data, StandardCharsets.UTF_8); }
    private static byte[] bytes(JsonObject o) { return (o.toString() + "\n").getBytes(StandardCharsets.UTF_8); }
}
