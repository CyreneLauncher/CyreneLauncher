package orbis.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The Modrinth API v2 calls the mod and modpack browser needs (https://docs.modrinth.com/api). */
class ModrinthClient {
    private static final String API = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "orbis-launcher (Minecraft on PlayStation 4)";
    /** Results per search page. */
    static final int PAGE = 20;
    /** Releases the Java 25 runner adds (VersionStore's 26.1 to 26.2). */
    private static final List<String> JAVA_25_RELEASES = List.of("26.2", "26.1.2", "26.1.1", "26.1");
    /** Releases the Java 21 runner adds (VersionStore's 1.20.5 to 1.21.11). */
    private static final List<String> JAVA_21_RELEASES = List.of("1.21.11", "1.21.10", "1.21.9", "1.21.8",
            "1.21.7", "1.21.6", "1.21.5", "1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.21",
            "1.20.6", "1.20.5");
    /** The releases the launcher can install on Java 17 (VersionStore's 1.14 to 1.20.4). */
    private static final List<String> JAVA_17_RELEASES = List.of("1.20.4", "1.20.3", "1.20.2", "1.20.1", "1.20",
            "1.19.4", "1.19.3", "1.19.2", "1.19.1", "1.19", "1.18.2", "1.18.1", "1.18", "1.17.1", "1.17",
            "1.16.5", "1.16.4", "1.16.3", "1.16.2", "1.16.1", "1.16", "1.15.2", "1.15.1", "1.15",
            "1.14.4", "1.14.3", "1.14.2", "1.14.1", "1.14");

    /** The releases the launcher can install with the runners on the console, for the modpack search. */
    static List<String> supportedReleases() {
        if (VersionStore.offeredJava < 21) return JAVA_17_RELEASES;
        List<String> all = new java.util.ArrayList<>(VersionStore.offeredJava >= 25 ? JAVA_25_RELEASES : List.of());
        all.addAll(JAVA_21_RELEASES);
        all.addAll(JAVA_17_RELEASES);
        return all;
    }

    enum Type { MOD("mod", "Mods"), MODPACK("modpack", "Modpacks");
        final String api, title;
        Type(String api, String title) { this.api = api; this.title = title; } }

    enum Sort { RELEVANCE("relevance", "Relevance"), DOWNLOADS("downloads", "Downloads"),
        FOLLOWS("follows", "Followers"), NEWEST("newest", "Newest"), UPDATED("updated", "Recently updated");
        final String api, title;
        Sort(String api, String title) { this.api = api; this.title = title; } }

    record Hit(String projectId, String slug, String title, String description, long downloads, String author,
               String iconUrl, List<String> categories, long follows, String featured, String updated, Type type) {
        Hit(String projectId, String slug, String title, String description, long downloads, String author) {
            this(projectId, slug, title, description, downloads, author, "", List.of(), 0, "", "", Type.MOD);
        }
    }
    /** One page of results; total is how many the search found in all. */
    record Results(List<Hit> hits, int offset, int total) {}
    record ModFile(String url, String filename, String sha1, long size) {}
    record Dependency(String projectId, String type) {}
    record Version(String projectId, String versionNumber, ModFile file, List<Dependency> dependencies) {}
    record GalleryImage(String url, String rawUrl, String title, String description, boolean featured) {}
    record Project(String id, String slug, Type type, String title, String description, String body,
                   String iconUrl, List<GalleryImage> gallery, String license, long downloads, long followers,
                   List<String> categories, List<String> loaders, List<String> gameVersions, String updated,
                   String clientSide) {
        /** The image shown above the project: the featured gallery image, else the first. */
        GalleryImage banner() {
            for (GalleryImage g : gallery) if (g.featured()) return g;
            return gallery.isEmpty() ? null : gallery.get(0);
        }
    }
    /** A published version with what it runs on; file is its primary file. */
    record VersionInfo(String id, String projectId, String name, String number, String type, String published,
                       long downloads, List<String> gameVersions, List<String> loaders, ModFile file,
                       List<Dependency> dependencies) {
        Version version() { return new Version(projectId, number, file, dependencies); }
    }

    private final String gameVersion;
    private final List<String> loaders;

    ModrinthClient(String gameVersion) {
        this(gameVersion, List.of("fabric"));
    }

    /** loaders: what the instance loads, as Modrinth names them (Quilt also loads Fabric mods). */
    ModrinthClient(String gameVersion, List<String> loaders) {
        this.gameVersion = gameVersion;
        this.loaders = List.copyOf(loaders);
    }

    String gameVersion() { return gameVersion; }
    List<String> loaders() { return loaders; }

    /**
     * Mods for this instance (its game version and loaders), or modpacks the launcher can install
     * (Fabric or Quilt, 1.14 to VersionStore.newest()).
     */
    Results search(String query, Type type, Sort sort, int offset) throws IOException {
        List<String> groups = new ArrayList<>();
        groups.add(any("project_type", List.of(type.api)));
        if (type == Type.MOD) {
            groups.add(any("versions", List.of(gameVersion)));
            groups.add(any("categories", loaders));
        } else {
            groups.add(any("versions", supportedReleases()));
            groups.add(any("categories", List.of("fabric", "quilt")));
        }
        String facets = "[" + String.join(",", groups) + "]";
        JsonObject result = get(API + "/search?limit=" + PAGE + "&offset=" + Math.max(0, offset) + "&index=" + sort.api
                + "&query=" + encode(query) + "&facets=" + encode(facets)).getAsJsonObject();
        List<Hit> hits = new ArrayList<>();
        for (JsonElement e : result.getAsJsonArray("hits")) {
            JsonObject h = e.getAsJsonObject();
            hits.add(new Hit(str(h, "project_id"), str(h, "slug"), str(h, "title"), str(h, "description"),
                    number(h, "downloads"), str(h, "author"), str(h, "icon_url"), strings(h, "display_categories"),
                    number(h, "follows"), str(h, "featured_gallery"), str(h, "date_modified"),
                    "modpack".equals(str(h, "project_type")) ? Type.MODPACK : Type.MOD));
        }
        int total = result.has("total_hits") ? result.get("total_hits").getAsInt() : hits.size();
        return new Results(hits, offset, total);
    }

    private static String any(String facet, List<String> values) {
        List<String> quoted = new ArrayList<>();
        for (String v : values) quoted.add("\"" + facet + ":" + v.replace("\"", "") + "\"");
        return "[" + String.join(",", quoted) + "]";
    }

    /** The full project: its description (Markdown), gallery and facts. */
    Project project(String idOrSlug) throws IOException {
        JsonObject p = get(API + "/project/" + encode(idOrSlug)).getAsJsonObject();
        List<GalleryImage> gallery = new ArrayList<>();
        if (p.has("gallery") && p.get("gallery").isJsonArray()) {
            List<JsonObject> raw = new ArrayList<>();
            for (JsonElement g : p.getAsJsonArray("gallery")) raw.add(g.getAsJsonObject());
            raw.sort((a, b) -> Long.compare(number(a, "ordering"), number(b, "ordering")));
            for (JsonObject g : raw)
                gallery.add(new GalleryImage(str(g, "url"), str(g, "raw_url").isEmpty() ? str(g, "url") : str(g, "raw_url"),
                        str(g, "title"), str(g, "description"), g.has("featured") && g.get("featured").getAsBoolean()));
        }
        String license = "";
        if (p.has("license") && p.get("license").isJsonObject()) {
            JsonObject l = p.getAsJsonObject("license");
            license = str(l, "name").isEmpty() ? str(l, "id").replace("LicenseRef-", "") : str(l, "name");
        }
        return new Project(str(p, "id"), str(p, "slug"), "modpack".equals(str(p, "project_type")) ? Type.MODPACK : Type.MOD,
                str(p, "title"), str(p, "description"), str(p, "body"), str(p, "icon_url"), gallery, license,
                number(p, "downloads"), number(p, "followers"), strings(p, "categories"), strings(p, "loaders"),
                strings(p, "game_versions"), str(p, "updated"), str(p, "client_side"));
    }

    /**
     * A project's versions, newest first. With compatible, only those for this instance's game
     * version and loaders (mods) or the launcher's releases and Fabric/Quilt (modpacks).
     */
    List<VersionInfo> projectVersions(String projectIdOrSlug, Type type, boolean compatible) throws IOException {
        String url = API + "/project/" + encode(projectIdOrSlug) + "/version";
        if (compatible) {
            if (type == Type.MOD) url += "?loaders=" + encode(json(loaders)) + "&game_versions=" + encode(json(List.of(gameVersion)));
            else url += "?loaders=" + encode(json(List.of("fabric", "quilt"))) + "&game_versions=" + encode(json(supportedReleases()));
        }
        JsonElement response;
        try {
            response = get(url);
        } catch (NotFound missing) {
            return List.of();
        }
        List<JsonObject> raw = new ArrayList<>();
        for (JsonElement e : response.getAsJsonArray()) raw.add(e.getAsJsonObject());
        raw.sort((a, b) -> str(b, "date_published").compareTo(str(a, "date_published")));
        List<VersionInfo> out = new ArrayList<>();
        for (JsonObject v : raw) {
            JsonObject chosen = null;
            for (JsonElement f : v.getAsJsonArray("files")) {
                JsonObject file = f.getAsJsonObject();
                if (chosen == null || file.get("primary").getAsBoolean()) chosen = file;
            }
            if (chosen == null) continue;
            ModFile file = new ModFile(str(chosen, "url"), str(chosen, "filename"),
                    str(chosen.getAsJsonObject("hashes"), "sha1"), chosen.get("size").getAsLong());
            List<Dependency> dependencies = new ArrayList<>();
            for (JsonElement d : v.getAsJsonArray("dependencies")) {
                JsonObject dep = d.getAsJsonObject();
                if (dep.has("project_id") && !dep.get("project_id").isJsonNull())
                    dependencies.add(new Dependency(str(dep, "project_id"), str(dep, "dependency_type")));
            }
            out.add(new VersionInfo(str(v, "id"), str(v, "project_id"), str(v, "name"), str(v, "version_number"),
                    str(v, "version_type"), str(v, "date_published"), number(v, "downloads"),
                    strings(v, "game_versions"), strings(v, "loaders"), file, dependencies));
        }
        return out;
    }

    /** A project's builds for this instance's game version and loaders, newest first; empty when none. */
    List<Version> versions(String projectIdOrSlug) throws IOException {
        List<Version> out = new ArrayList<>();
        for (VersionInfo v : projectVersions(projectIdOrSlug, Type.MOD, true)) out.add(v.version());
        return out;
    }

    String title(String projectIdOrSlug) throws IOException {
        try {
            return str(get(API + "/project/" + encode(projectIdOrSlug)).getAsJsonObject(), "title");
        } catch (NotFound missing) {
            return projectIdOrSlug;
        }
    }

    /** Bytes of a picture or other public file; images are not hash-checked. */
    byte[] fetchPublic(String url) throws IOException {
        return fetch(url);
    }

    /** The file's bytes, checked against Modrinth's size and SHA-1. */
    byte[] download(ModFile file) throws IOException {
        byte[] data = fetch(file.url());
        if (data.length != file.size()) throw new IOException(file.filename() + ": size " + data.length
                + " is not the published " + file.size());
        String sha1 = sha1(data);
        if (!sha1.equals(file.sha1())) throw new IOException(file.filename() + ": SHA-1 " + sha1
                + " is not the published " + file.sha1());
        return data;
    }

    static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class NotFound extends IOException {
        private static final long serialVersionUID = 1L;

        NotFound(String url) {
            super("not found: " + url);
        }
    }

    private static JsonElement get(String url) throws IOException {
        return JsonParser.parseString(new String(fetch(url), StandardCharsets.UTF_8));
    }

    /** No mod or API reply comes near this; it keeps a bad reply from exhausting the heap. */
    private static final int MAX_DOWNLOAD = 64 << 20;

    private static byte[] fetch(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        int code = connection.getResponseCode();
        if (code == 404) throw new NotFound(url);
        if (code != 200) throw new IOException("HTTP " + code + " from " + url);
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0; ) {
                if (out.size() + n > MAX_DOWNLOAD) throw new IOException("Download from " + url + " is too large");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    private static long number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        try { return e == null || e.isJsonNull() ? 0 : e.getAsLong(); }
        catch (RuntimeException notNumber) { return 0; }
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        JsonElement e = o.get(key);
        if (e != null && e.isJsonArray())
            for (JsonElement item : e.getAsJsonArray()) if (item.isJsonPrimitive()) out.add(item.getAsString());
        return out;
    }

    private static String json(List<String> values) {
        List<String> quoted = new ArrayList<>();
        for (String v : values) quoted.add("\"" + v.replace("\"", "") + "\"");
        return "[" + String.join(",", quoted) + "]";
    }
}
