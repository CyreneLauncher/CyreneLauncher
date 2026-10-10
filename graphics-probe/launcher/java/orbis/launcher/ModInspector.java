package orbis.launcher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What a Fabric mod JAR, with every JAR nested in it, needs and brings on the console: the mod ids
 * it provides, the ids it depends on, native libraries (which cannot load here) and the GLFW
 * functions its classes call (the console's GLFW implements a subset).
 */
final class ModInspector {
    /** Ids every instance has without a mod providing them. */
    static final Set<String> BUILT_IN = Set.of("minecraft", "java", "fabricloader", "mixinextras");
    private static final Pattern GLFW_CALL = Pattern.compile("glfw[A-Z][A-Za-z0-9]+");
    private static final byte[] GLFW_CLASS = "org/lwjgl/glfw/GLFW".getBytes(StandardCharsets.US_ASCII);

    /** versions maps every provided id (nested JARs included) to the version that provides it. */
    record ModInfo(String id, String name, Set<String> provides, Map<String, String> versions,
                   Map<String, String> depends, List<String> natives, Set<String> glfwCalls) {
        String version() { return id == null ? "" : versions.getOrDefault(id, ""); }
    }

    private ModInspector() {}

    static ModInfo inspect(byte[] jar) throws IOException {
        Set<String> provides = new LinkedHashSet<>();
        Map<String, String> versions = new LinkedHashMap<>();
        Map<String, String> depends = new LinkedHashMap<>();
        List<String> natives = new ArrayList<>();
        Set<String> glfw = new TreeSet<>();
        String[] identity = new String[2];
        walk(jar, provides, versions, depends, natives, glfw, identity, true);
        return new ModInfo(identity[0], identity[1] == null ? identity[0] : identity[1],
                provides, versions, depends, natives, glfw);
    }

    /** The installed mods: each JAR's own id, and every id they provide (nested JARs, built-ins). */
    record Installed(Map<String, Path> jarById, Set<String> ids, Map<String, String> versions) {}

    /** builtIn: versions of the ids no mod provides (minecraft, fabricloader, java). */
    static Installed installed(Path modsDir, Map<String, String> builtIn) {
        Map<String, Path> jars = new HashMap<>();
        Set<String> ids = new LinkedHashSet<>(BUILT_IN);
        Map<String, String> versions = new HashMap<>(builtIn);
        try (DirectoryStream<Path> mods = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path mod : mods) {
                try {
                    ModInfo info = inspect(Files.readAllBytes(mod));
                    ids.addAll(info.provides());
                    versions.putAll(info.versions());
                    if (info.id() != null) jars.put(info.id(), mod);
                } catch (IOException | RuntimeException unreadable) {
                    // a broken JAR provides nothing
                }
            }
        } catch (IOException missing) {
            // no mods folder yet
        }
        return new Installed(jars, ids, versions);
    }

    /** What the Mods page shows about an installed JAR; icon holds the image bytes, or null. */
    record LocalMod(String id, String name, String version, String description, List<String> authors, byte[] icon) {}

    /** Name, version, description, authors and icon from fabric.mod.json or quilt.mod.json; null when neither is there. */
    static LocalMod describe(Path jar) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            ZipEntry fabric = zip.getEntry("fabric.mod.json"), quilt = zip.getEntry("quilt.mod.json");
            if (fabric == null && quilt == null) {
                ModInfo info = inspect(Files.readAllBytes(jar));
                return info.id() == null ? null : new LocalMod(info.id(),info.name(),info.version(),"",List.of(),null);
            }
            JsonObject root = JsonParser.parseString(new String(zip.getInputStream(fabric != null ? fabric : quilt)
                    .readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject meta = root;
            String id = text(root, "id"), version = text(root, "version");
            if (fabric == null && root.has("quilt_loader") && root.get("quilt_loader").isJsonObject()) {
                JsonObject loader = root.getAsJsonObject("quilt_loader");
                id = text(loader, "id");
                version = text(loader, "version");
                meta = loader.has("metadata") && loader.get("metadata").isJsonObject() ? loader.getAsJsonObject("metadata") : new JsonObject();
            }
            List<String> authors = new ArrayList<>();
            for (String key : new String[]{"authors", "contributors"}) {
                JsonElement list = meta.get(key);
                if (list != null && list.isJsonArray())
                    for (JsonElement a : list.getAsJsonArray()) {
                        if (a.isJsonPrimitive()) authors.add(a.getAsString());
                        else if (a.isJsonObject() && a.getAsJsonObject().has("name")) authors.add(text(a.getAsJsonObject(), "name"));
                    }
                else if (list != null && list.isJsonObject()) authors.addAll(list.getAsJsonObject().keySet());
            }
            byte[] icon = null;
            String iconPath = iconPath(meta.get("icon"));
            if (iconPath != null) {
                ZipEntry entry = zip.getEntry(iconPath.startsWith("/") ? iconPath.substring(1) : iconPath);
                if (entry != null && entry.getSize() < (4 << 20)) icon = zip.getInputStream(entry).readAllBytes();
            }
            String name = text(meta, "name");
            return new LocalMod(id, name.isEmpty() ? id : name, version, text(meta, "description"), authors, icon);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The icon path: a string, or the size closest to 128 of a {"16": path, ...} map. */
    private static String iconPath(JsonElement icon) {
        if (icon == null || icon.isJsonNull()) return null;
        if (icon.isJsonPrimitive()) return icon.getAsString();
        if (!icon.isJsonObject()) return null;
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Map.Entry<String, JsonElement> e : icon.getAsJsonObject().entrySet()) {
            try {
                int score = Math.abs(Integer.parseInt(e.getKey()) - 128);
                if (score < bestScore && e.getValue().isJsonPrimitive()) { best = e.getValue().getAsString(); bestScore = score; }
            } catch (NumberFormatException notSize) {
                // ignored
            }
        }
        return best;
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    /** Dependencies of info that neither the given ids nor info itself provide. */
    static Map<String, String> missing(ModInfo info, Set<String> available) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> d : info.depends().entrySet())
            if (!available.contains(d.getKey()) && !info.provides().contains(d.getKey()))
                out.put(d.getKey(), d.getValue());
        return out;
    }

    private static void walk(byte[] jar, Set<String> provides, Map<String, String> versions, Map<String, String> depends,
                             List<String> natives, Set<String> glfw, String[] identity, boolean top) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String name = entry.getName();
                if (name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib")
                        || name.endsWith(".jnilib")) {
                    natives.add(name);
                } else if (name.equals("fabric.mod.json")) {
                    readModJson(zip.readAllBytes(), provides, versions, depends, identity, top);
                } else if (name.equals("META-INF/mods.toml") || name.equals("META-INF/neoforge.mods.toml")) {
                    byte[] metadata = zip.readNBytes((1 << 20) + 1);
                    if (metadata.length > (1 << 20)) throw new IOException("Mod metadata is too large");
                    readForgeToml(new String(metadata,StandardCharsets.UTF_8),provides,versions,identity,top);
                } else if (name.endsWith(".jar")) {
                    walk(zip.readAllBytes(), provides, versions, depends, natives, glfw, identity, false);
                } else if (name.endsWith(".class")) {
                    byte[] bytes = zip.readAllBytes();
                    if (contains(bytes, GLFW_CLASS)) {
                        Matcher m = GLFW_CALL.matcher(new String(bytes, StandardCharsets.ISO_8859_1));
                        while (m.find()) glfw.add(m.group());
                    }
                }
            }
        }
    }

    /** Metadata presence follows the selected loader; dependency semantics remain the loader's own. */
    static boolean forLoader(byte[] jar, List<String> loaders) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                if (loaders.contains("fabric") && name.equals("fabric.mod.json")) return true;
                if (loaders.contains("quilt") && name.equals("quilt.mod.json")) return true;
                if (loaders.contains("forge") && name.equals("META-INF/mods.toml")) return true;
                if (loaders.contains("neoforge") && (name.equals("META-INF/neoforge.mods.toml") || name.equals("META-INF/mods.toml"))) return true;
            }
        }
        return false;
    }

    private static void readForgeToml(String toml, Set<String> provides, Map<String,String> versions,
            String[] identity, boolean top) {
        Map<String,String> mod = new HashMap<>();
        boolean active = false;
        for (String line : (toml + "\n[end]").split("\\R")) {
            String text = line.trim();
            if (text.startsWith("[")) {
                String id = mod.get("modId");
                if (active && id != null && id.matches("[a-z][a-z0-9_]{1,63}")) {
                    provides.add(id);
                    String version = mod.getOrDefault("version","");
                    versions.put(id,version.contains("${") ? "" : version);
                    if (top && identity[0] == null) { identity[0]=id; identity[1]=mod.getOrDefault("displayName",id); }
                }
                active = text.equals("[[mods]]"); mod.clear();
            } else if (active) {
                Matcher value = Pattern.compile("^([A-Za-z]+)\\s*=\\s*([\"'])(.*?)\\2\\s*(#.*)?$").matcher(text);
                if (value.matches()) mod.put(value.group(1),value.group(3));
            }
        }
    }

    private static void readModJson(byte[] json, Set<String> provides, Map<String, String> versions,
                                    Map<String, String> depends, String[] identity, boolean top) {
        JsonObject mod = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        String id = mod.has("id") ? mod.get("id").getAsString() : null;
        String version = mod.has("version") ? mod.get("version").getAsString() : "";
        if (id != null) {
            provides.add(id);
            versions.putIfAbsent(id, version);
        }
        if (top && id != null) {
            identity[0] = id;
            if (mod.has("name")) identity[1] = mod.get("name").getAsString();
        }
        if (mod.has("provides"))
            for (JsonElement p : mod.getAsJsonArray("provides")) {
                provides.add(p.getAsString());
                versions.putIfAbsent(p.getAsString(), version);
            }
        if (mod.has("depends") && mod.get("depends").isJsonObject())
            for (Map.Entry<String, JsonElement> d : mod.getAsJsonObject("depends").entrySet())
                depends.putIfAbsent(d.getKey(), d.getValue().isJsonPrimitive() ? d.getValue().getAsString()
                        : d.getValue().toString());
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++)
                if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }
}
