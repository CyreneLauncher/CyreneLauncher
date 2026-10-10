package orbis.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Official Forge-family catalogs and installer metadata; no desktop launcher or subprocess dependency. */
final class ForgeInstaller {
    static final String FORGE_MAVEN = "https://maven.minecraftforge.net/";
    static final String NEO_MAVEN = "https://maven.neoforged.net/releases/";
    private static final Pattern XML_VERSION = Pattern.compile("<version>([^<]+)</version>");
    private ForgeInstaller() { }

    static boolean family(VersionStore.Loader loader) {
        return loader == VersionStore.Loader.FORGE || loader == VersionStore.Loader.NEOFORGE;
    }

    static boolean supported(VersionStore.Loader loader, String mc) {
        if (!mc.matches("1\\.\\d+(\\.\\d+)?")) return false;
        int[] p = VersionStore.parts(mc);
        return VersionStore.compare(p, VersionStore.parts(loader == VersionStore.Loader.FORGE ? "1.18.2" : "1.20.2")) >= 0
                && VersionStore.compare(p, VersionStore.parts("1.21.11")) <= 0;
    }

    static String neoMinecraft(String version) throws IOException {
        if (!version.matches("(20|21)\\.\\d+\\.\\d+(-beta)?")) throw new IOException("Unsupported NeoForge version: " + version);
        String[] p = version.split("\\.");
        return "1." + p[0] + (p[1].equals("0") ? "" : "." + p[1]);
    }

    static Map<String,List<String>> catalog(VersionStore.Loader loader) throws IOException {
        String url = loader == VersionStore.Loader.FORGE
                ? FORGE_MAVEN + "net/minecraftforge/forge/maven-metadata.xml"
                : NEO_MAVEN + "net/neoforged/neoforge/maven-metadata.xml";
        return catalog(loader, new String(VersionStore.fetch(url, 8L << 20), StandardCharsets.UTF_8));
    }

    static Map<String,List<String>> catalog(VersionStore.Loader loader, String xml) throws IOException {
        Map<String,List<String>> out = new LinkedHashMap<>();
        Matcher m = XML_VERSION.matcher(xml);
        while (m.find()) {
            String version = m.group(1), mc, build;
            if (loader == VersionStore.Loader.FORGE) {
                int dash = version.indexOf('-');
                if (dash < 0) continue;
                mc = version.substring(0, dash); build = version.substring(dash + 1);
                if (!build.matches("[0-9.]+")) continue;
            } else {
                try { mc = neoMinecraft(version); } catch (IOException unsupported) { continue; }
                if (version.endsWith("-beta")) continue;
                build = version;
            }
            if (supported(loader, mc)) out.computeIfAbsent(mc, ignored -> new ArrayList<>()).add(build);
        }
        out.values().forEach(list -> list.sort((a,b) -> VersionStore.compare(VersionStore.parts(b), VersionStore.parts(a))));
        return out;
    }

    static String latest(VersionStore.Loader loader, String minecraft) throws IOException {
        List<String> builds = catalog(loader).get(minecraft);
        if (builds == null || builds.isEmpty()) throw new IOException("No supported " + loader.title + " build for " + minecraft);
        if (loader == VersionStore.Loader.FORGE) {
            JsonObject promos = JsonParser.parseString(new String(VersionStore.fetch("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json", 1L << 20), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("promos");
            for (String tag : List.of("recommended", "latest"))
                if (promos.has(minecraft + "-" + tag) && builds.contains(promos.get(minecraft + "-" + tag).getAsString()))
                    return promos.get(minecraft + "-" + tag).getAsString();
        }
        return builds.get(0);
    }

    static String mavenPath(String coordinate) throws IOException {
        String[] ext = coordinate.split("@", -1);
        if (ext.length > 2 || (ext.length == 2 && !ext[1].matches("[A-Za-z0-9]+"))) throw new IOException("Invalid Maven extension");
        String[] p = ext[0].split(":", -1);
        if (p.length < 3 || p.length > 4) throw new IOException("Invalid Maven coordinate");
        for (String part : p) if (!part.matches("[A-Za-z0-9_+.-]+") || part.contains("..")) throw new IOException("Unsafe Maven coordinate");
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2]
                + (p.length == 4 ? "-" + p[3] : "") + "." + (ext.length == 2 ? ext[1] : "jar");
    }
}
