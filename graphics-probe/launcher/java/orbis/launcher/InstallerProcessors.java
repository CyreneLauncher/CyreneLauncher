package orbis.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Official client processors run sequentially with private dependencies, never a child process. */
final class InstallerProcessors {
    private static final Pattern TOKEN = Pattern.compile("\\{([A-Z0-9_]+)}");
    private InstallerProcessors() { }
    static final class RuntimeRequired extends IOException {
        private static final long serialVersionUID = 1L;
        final int java;
        RuntimeRequired(int java, Throwable cause) { super("Patch tools require Java " + java,cause); this.java=java; }
    }


    static String resolve(String text, Map<String,String> data, Path libraries) throws IOException {
        if (text.startsWith("[") && text.endsWith("]"))
            return libraries.resolve(ForgeInstaller.mavenPath(text.substring(1,text.length()-1))).toString();
        Matcher m = TOKEN.matcher(text); StringBuffer out = new StringBuffer();
        while (m.find()) {
            String value = data.get(m.group(1));
            if (value == null) throw new IOException("Missing installer data: " + m.group(1));
            m.appendReplacement(out,Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        if (out.indexOf("{") >= 0 || out.indexOf("}") >= 0 || out.indexOf("\0") >= 0)
            throw new IOException("Unresolved installer data");
        return out.toString();
    }

    static void run(JsonObject profile, Path libraries, Map<String,String> data, InstanceStore.Operation op) throws IOException {
        int count = 0;
        for (JsonElement element : profile.getAsJsonArray("processors")) {
            JsonObject proc = element.getAsJsonObject();
            if (proc.has("sides")) {
                boolean client = false;
                for (JsonElement side : proc.getAsJsonArray("sides")) if (side.getAsString().equals("client")) client = true;
                if (!client) continue;
            }
            op.progress("Patching Minecraft: step " + ++count);
            Map<Path,String> outputs = new LinkedHashMap<>();
            if (proc.has("outputs")) for (Map.Entry<String,JsonElement> output : proc.getAsJsonObject("outputs").entrySet()) {
                Path target = Path.of(resolve(output.getKey(),data,libraries)).toAbsolutePath().normalize();
                if (!target.startsWith(libraries.toAbsolutePath().normalize())) throw new IOException("Processor output escapes managed libraries");
                LauncherFiles.rejectSymlinks(target);
                String hash = resolve(output.getValue().getAsString(),data,libraries);
                if (!hash.matches("[0-9a-fA-F]{40}")) throw new IOException("Invalid processor output hash");
                outputs.put(target,hash.toLowerCase(java.util.Locale.ROOT));
            }
            if (!outputs.isEmpty() && outputs.entrySet().stream().allMatch(e -> matches(e.getKey(),e.getValue()))) continue;
            Path jar = libraries.resolve(ForgeInstaller.mavenPath(proc.get("jar").getAsString()));
            LauncherFiles.rejectSymlinks(jar);
            List<URL> urls = new ArrayList<>(); urls.add(jar.toUri().toURL());
            for (JsonElement dependency : proc.getAsJsonArray("classpath")) {
                Path file = libraries.resolve(ForgeInstaller.mavenPath(dependency.getAsString()));
                LauncherFiles.rejectSymlinks(file);
                if (!Files.isRegularFile(file)) throw new IOException("Missing processor dependency: " + dependency.getAsString());
                urls.add(file.toUri().toURL());
            }
            List<String> args = new ArrayList<>();
            for (JsonElement argument : proc.getAsJsonArray("args")) args.add(resolve(argument.getAsString(),data,libraries));
            String main;
            try (JarFile archive = new JarFile(jar.toFile())) {
                if (archive.getManifest() == null) throw new IOException("Processor has no manifest");
                main = archive.getManifest().getMainAttributes().getValue("Main-Class");
            }
            if (main == null) throw new IOException("Processor has no entry point");
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader())) {
                Thread.currentThread().setContextClassLoader(loader);
                Class.forName(main,true,loader).getMethod("main",String[].class).invoke(null,(Object)args.toArray(String[]::new));
            } catch (ReflectiveOperationException | LinkageError failure) {
                // Reflection wraps failures from a dependency reached inside the processor main.
                for (Throwable cause=failure; cause != null; cause=cause.getCause()) {
                    if (cause instanceof UnsupportedClassVersionError) {
                        Matcher version = Pattern.compile("class file version ([0-9]+)\\.[0-9]+").matcher(String.valueOf(cause.getMessage()));
                        if (version.find()) throw new RuntimeRequired(Integer.parseInt(version.group(1))-44,failure);
                    }
                }
                throw new IOException("Minecraft patching failed at step " + count, failure);
            } finally { Thread.currentThread().setContextClassLoader(previous); }
            op.check();
            for (Map.Entry<Path,String> output : outputs.entrySet())
                if (!matches(output.getKey(),output.getValue())) throw new IOException("Processor output failed verification: " + output.getKey().getFileName());
        }
    }

    static String hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer,0,n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static boolean matches(Path file, String hash) {
        try { LauncherFiles.rejectSymlinks(file); return Files.isRegularFile(file) && hash(file).equals(hash); }
        catch (IOException missing) { return false; }
    }
}
