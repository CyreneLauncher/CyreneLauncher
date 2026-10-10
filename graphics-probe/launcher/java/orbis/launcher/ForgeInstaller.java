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

    static final class NeedsRuntime extends IOException {
        private static final long serialVersionUID = 1L;
        final int java;
        final String build;
        NeedsRuntime(int java, String build) { super("Installation needs Java " + java); this.java = java; this.build = build; }
    }

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
        if (ext.length > 2 || (ext.length == 2 && !ext[1].matches("[A-Za-z0-9]+(\\.[A-Za-z0-9]+)*"))) throw new IOException("Invalid Maven extension");
        String[] p = ext[0].split(":", -1);
        if (p.length < 3 || p.length > 4) throw new IOException("Invalid Maven coordinate");
        for (String part : p) if (!part.matches("[A-Za-z0-9_+.-]+") || part.contains("..")) throw new IOException("Unsafe Maven coordinate");
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2]
                + (p.length == 4 ? "-" + p[3] : "") + "." + (ext.length == 2 ? ext[1] : "jar");
    }
    static synchronized JsonObject prepare(VersionStore.Loader loader, String minecraft, String build,
            java.nio.file.Path client, java.nio.file.Path libraries, java.nio.file.Path staging,
            InstanceStore.Operation op) throws IOException {
        if (!supported(loader,minecraft) || !build.matches("[0-9.]+(-beta)?")) throw new IOException("Unsupported loader release");
        String coordinate = loader == VersionStore.Loader.FORGE ? "net.minecraftforge:forge:" + minecraft + "-" + build + ":installer"
                : "net.neoforged:neoforge:" + build + ":installer";
        String repo = loader == VersionStore.Loader.FORGE ? FORGE_MAVEN : NEO_MAVEN;
        java.nio.file.Path installer = staging.resolve("installer.jar");
        LauncherFiles.rejectSymlinks(staging);
        java.nio.file.Files.createDirectories(staging);
        op.progress("Downloading " + loader.title + " installer...");
        String url = repo + mavenPath(coordinate);
        String digest = new String(VersionStore.fetch(url + ".sha1",1024),StandardCharsets.UTF_8).trim().split("\\s+")[0];
        if (!digest.matches("[a-fA-F0-9]{40}")) throw new IOException("Installer has no valid checksum");
        DownloadCache.get(installer,url,"SHA-1",digest,-1,64L << 20,DownloadCache::http,(n,t) -> op.check());
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(installer.toFile())) {
            JsonObject install = resourceJson(zip,"install_profile.json");
            if (!install.has("spec") || install.get("spec").getAsInt() != 1
                    || !minecraft.equals(install.get("minecraft").getAsString())) throw new IOException("Installer does not match Minecraft " + minecraft);
            JsonObject profile = resourceJson(zip,install.get("json").getAsString().replaceFirst("^/", ""));
            if (!profile.get("mainClass").getAsString().equals("cpw.mods.bootstraplauncher.BootstrapLauncher")
                    && !profile.get("mainClass").getAsString().equals("cpw.mods.modlauncher.Launcher")
                    && !profile.get("mainClass").getAsString().equals("net.minecraftforge.bootstrap.ForgeBootstrap")
                    && !profile.get("mainClass").getAsString().equals("net.neoforged.fml.startup.Client"))
                throw new IOException("Unsupported loader entry point");
            int n = 0;
            for (com.google.gson.JsonElement entry : install.getAsJsonArray("libraries")) {
                op.progress("Downloading patch tools (" + ++n + "/" + install.getAsJsonArray("libraries").size() + ")");
                download(entry.getAsJsonObject(),libraries,zip);
            }
            Map<String,String> data = new LinkedHashMap<>();
            data.put("SIDE","client"); data.put("ROOT",staging.toString());
            data.put("INSTALLER",installer.toString()); data.put("MINECRAFT_JAR",client.toString());
            data.put("MINECRAFT_VERSION",minecraft);
            for (Map.Entry<String,com.google.gson.JsonElement> entry : install.getAsJsonObject("data").entrySet()) {
                String value = entry.getValue().getAsJsonObject().get("client").getAsString();
                if (value.startsWith("'") && value.endsWith("'")) value = value.substring(1,value.length()-1);
                else if (value.startsWith("/")) {
                    String resource = value.substring(1);
                    java.nio.file.Path target = confined(staging,resource);
                    java.nio.file.Files.createDirectories(target.getParent());
                    LauncherFiles.write(target,resource(zip,resource,64L << 20),false);
                    value = target.toString();
                } else value = InstallerProcessors.resolve(value,data,libraries);
                data.put(entry.getKey(),value);
            }
            // Processor tools are portable Java. Check all selected mains before touching client outputs.
            for (com.google.gson.JsonElement element : install.getAsJsonArray("processors")) {
                JsonObject processor = element.getAsJsonObject();
                if (processor.has("sides") && !processor.getAsJsonArray("sides").toString().contains("client")) continue;
                int needed = processorJava(libraries.resolve(mavenPath(processor.get("jar").getAsString())));
                if (needed > Runtime.version().feature()) throw new NeedsRuntime(needed,build);
            }
            InstallerProcessors.run(install,libraries,data,op);
            // Recent profiles declare the processor-generated client as a runtime artifact with an empty URL.
            for (com.google.gson.JsonElement entry : profile.getAsJsonArray("libraries")) download(entry.getAsJsonObject(),libraries,zip);
            com.google.gson.JsonArray generated = new com.google.gson.JsonArray();
            for (String key : List.of("MC_SLIM","MC_EXTRA","MC_SRG","MC_OFF","PATCHED")) {
                if (!data.containsKey(key)) continue;
                java.nio.file.Path target = java.nio.file.Path.of(data.get(key)).toAbsolutePath().normalize();
                if (!target.startsWith(libraries.toAbsolutePath().normalize())) throw new IOException("Generated client escapes library storage");
                LauncherFiles.rejectSymlinks(target);
                if (!java.nio.file.Files.isRegularFile(target)) throw new IOException("Missing patched client output: " + key);
                JsonObject artifact = new JsonObject();
                artifact.addProperty("path",libraries.relativize(target).toString());
                artifact.addProperty("sha1",InstallerProcessors.hash(target));
                artifact.addProperty("size",java.nio.file.Files.size(target));
                generated.add(artifact);
            }
            if (!data.containsKey("PATCHED")) throw new IOException("Installer declares no patched client");
            profile.add("orbisGenerated",generated);
            return profile;
        } catch (RuntimeException malformed) { throw new IOException("Invalid " + loader.title + " installer profile",malformed); }
    }

    static java.nio.file.Path confined(java.nio.file.Path root, String relative) throws IOException {
        java.nio.file.Path part = java.nio.file.Path.of(relative);
        if (part.isAbsolute() || relative.contains("\\") || !part.normalize().equals(part) || relative.contains(".."))
            throw new IOException("Unsafe installer path");
        java.nio.file.Path path = root.resolve(part).toAbsolutePath().normalize();
        if (!path.startsWith(root.toAbsolutePath().normalize())) throw new IOException("Installer path escapes storage");
        LauncherFiles.rejectSymlinks(path);
        return path;
    }

    static byte[] resource(java.util.zip.ZipFile zip, String name, long max) throws IOException {
        java.util.zip.ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory() || entry.getSize() > max) throw new IOException("Missing or oversized installer resource: " + name);
        try (java.io.InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes((int)max + 1);
            if (bytes.length > max) throw new IOException("Oversized installer resource");
            return bytes;
        }
    }
    private static JsonObject resourceJson(java.util.zip.ZipFile zip, String name) throws IOException {
        return JsonParser.parseString(new String(resource(zip,name,8L << 20),StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void download(JsonObject library, java.nio.file.Path libraries, java.util.zip.ZipFile zip) throws IOException {
        JsonObject artifact = library.getAsJsonObject("downloads").getAsJsonObject("artifact");
        String relative = mavenPath(library.get("name").getAsString());
        if (!relative.equals(artifact.get("path").getAsString())) throw new IOException("Installer artifact path does not match its coordinate");
        java.nio.file.Path file = confined(libraries,relative);
        String hash = artifact.get("sha1").getAsString(), url = artifact.get("url").getAsString();
        if (!hash.matches("[a-fA-F0-9]{40}")) throw new IOException("Library has no valid checksum");
        if (url.isEmpty()) {
            if (java.nio.file.Files.isRegularFile(file) && java.nio.file.Files.size(file) == artifact.get("size").getAsLong()
                    && InstallerProcessors.hash(file).equalsIgnoreCase(hash)) return;
            byte[] content = resource(zip,"maven/" + relative,64L << 20);
            if (!VersionStore.sha1(content).equalsIgnoreCase(hash)) throw new IOException("Embedded library checksum mismatch");
            LauncherFiles.write(file,content,false);
        } else DownloadCache.get(file,url,"SHA-1",hash,artifact.get("size").getAsLong(),64L << 20,DownloadCache::http,(n,t) -> { });
    }

    private static int processorJava(java.nio.file.Path jar) throws IOException {
        try (java.util.jar.JarFile archive = new java.util.jar.JarFile(jar.toFile())) {
            String main = archive.getManifest().getMainAttributes().getValue("Main-Class");
            try (java.io.DataInputStream in = new java.io.DataInputStream(archive.getInputStream(archive.getEntry(main.replace('.','/') + ".class")))) {
                if (in.readInt() != 0xcafebabe) throw new IOException("Invalid processor class");
                in.readUnsignedShort(); return in.readUnsignedShort() - 44;
            }
        }
    }

}
