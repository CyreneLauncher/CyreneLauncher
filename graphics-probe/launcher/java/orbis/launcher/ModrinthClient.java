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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The few Modrinth API v2 calls the mod browser needs (https://docs.modrinth.com/api). */
class ModrinthClient {
    private static final String API = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "orbis-launcher (Minecraft on PlayStation 4)";

    record Hit(String projectId, String slug, String title, String description, long downloads, String author) {}
    record ModFile(String url, String filename, String sha1, long size) {}
    record Dependency(String projectId, String type) {}
    record Version(String projectId, String versionNumber, ModFile file, List<Dependency> dependencies) {}

    private final String gameVersion;

    ModrinthClient(String gameVersion) {
        this.gameVersion = gameVersion;
    }

    List<Hit> search(String query) throws IOException {
        String facets = "[[\"versions:" + gameVersion + "\"],[\"categories:fabric\"],[\"project_type:mod\"]]";
        JsonObject result = get(API + "/search?limit=30&index=relevance&query=" + encode(query)
                + "&facets=" + encode(facets)).getAsJsonObject();
        List<Hit> hits = new ArrayList<>();
        for (JsonElement e : result.getAsJsonArray("hits")) {
            JsonObject h = e.getAsJsonObject();
            hits.add(new Hit(str(h, "project_id"), str(h, "slug"), str(h, "title"), str(h, "description"),
                    h.has("downloads") ? h.get("downloads").getAsLong() : 0, str(h, "author")));
        }
        return hits;
    }

    /** A project's Fabric builds for this game version, newest first (by publish date); empty when none. */
    List<Version> versions(String projectIdOrSlug) throws IOException {
        JsonElement response;
        try {
            response = get(API + "/project/" + encode(projectIdOrSlug) + "/version?loaders="
                    + encode("[\"fabric\"]") + "&game_versions=" + encode("[\"" + gameVersion + "\"]"));
        } catch (NotFound missing) {
            return List.of();
        }
        List<JsonObject> raw = new ArrayList<>();
        for (JsonElement e : response.getAsJsonArray()) raw.add(e.getAsJsonObject());
        raw.sort((a, b) -> str(b, "date_published").compareTo(str(a, "date_published")));
        List<Version> out = new ArrayList<>();
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
                if (!dep.get("project_id").isJsonNull())
                    dependencies.add(new Dependency(str(dep, "project_id"), str(dep, "dependency_type")));
            }
            out.add(new Version(str(v, "project_id"), str(v, "version_number"), file, dependencies));
        }
        return out;
    }

    String title(String projectIdOrSlug) throws IOException {
        try {
            return str(get(API + "/project/" + encode(projectIdOrSlug)).getAsJsonObject(), "title");
        } catch (NotFound missing) {
            return projectIdOrSlug;
        }
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
            in.transferTo(out);
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
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
