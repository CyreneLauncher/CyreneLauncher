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

    record ModInfo(String id, String name, Set<String> provides, Map<String, String> depends,
                   List<String> natives, Set<String> glfwCalls) {}

    private ModInspector() {}

    static ModInfo inspect(byte[] jar) throws IOException {
        Set<String> provides = new LinkedHashSet<>();
        Map<String, String> depends = new LinkedHashMap<>();
        List<String> natives = new ArrayList<>();
        Set<String> glfw = new TreeSet<>();
        String[] identity = new String[2];
        walk(jar, provides, depends, natives, glfw, identity, true);
        return new ModInfo(identity[0], identity[1] == null ? identity[0] : identity[1],
                provides, depends, natives, glfw);
    }

    /** The installed mods: each JAR's own id, and every id they provide (nested JARs, built-ins). */
    record Installed(Map<String, Path> jarById, Set<String> ids) {}

    static Installed installed(Path modsDir) {
        Map<String, Path> jars = new HashMap<>();
        Set<String> ids = new LinkedHashSet<>(BUILT_IN);
        try (DirectoryStream<Path> mods = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path mod : mods) {
                try {
                    ModInfo info = inspect(Files.readAllBytes(mod));
                    ids.addAll(info.provides());
                    if (info.id() != null) jars.put(info.id(), mod);
                } catch (IOException | RuntimeException unreadable) {
                    // a broken JAR provides nothing
                }
            }
        } catch (IOException missing) {
            // no mods folder yet
        }
        return new Installed(jars, ids);
    }

    /** Dependencies of info that neither the given ids nor info itself provide. */
    static Map<String, String> missing(ModInfo info, Set<String> available) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> d : info.depends().entrySet())
            if (!available.contains(d.getKey()) && !info.provides().contains(d.getKey()))
                out.put(d.getKey(), d.getValue());
        return out;
    }

    private static void walk(byte[] jar, Set<String> provides, Map<String, String> depends, List<String> natives,
                             Set<String> glfw, String[] identity, boolean top) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String name = entry.getName();
                if (name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib")
                        || name.endsWith(".jnilib")) {
                    natives.add(name);
                } else if (name.equals("fabric.mod.json")) {
                    readModJson(zip.readAllBytes(), provides, depends, identity, top);
                } else if (name.endsWith(".jar")) {
                    walk(zip.readAllBytes(), provides, depends, natives, glfw, identity, false);
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

    private static void readModJson(byte[] json, Set<String> provides, Map<String, String> depends,
                                    String[] identity, boolean top) {
        JsonObject mod = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        String id = mod.has("id") ? mod.get("id").getAsString() : null;
        if (id != null) provides.add(id);
        if (top && id != null) {
            identity[0] = id;
            if (mod.has("name")) identity[1] = mod.get("name").getAsString();
        }
        if (mod.has("provides"))
            for (JsonElement p : mod.getAsJsonArray("provides")) provides.add(p.getAsString());
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
