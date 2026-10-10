package orbis.launcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Expands official loader arguments and prepares the options accepted by an embedded JVM. */
final class LoaderLaunch {
    private static final Pattern TOKEN = Pattern.compile("\\$\\{([^}]+)}");
    private LoaderLaunch() { }

    static List<String> game(JsonObject profile, Map<String,String> values) throws IOException {
        return arguments(profile, "game", values);
    }

    static List<String> jvm(JsonObject profile, Map<String,String> values) throws IOException {
        List<String> raw = arguments(profile, "jvm", values), out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String arg = raw.get(i);
            if (List.of("-p", "--module-path", "--add-modules", "--add-opens", "--add-exports").contains(arg)) {
                if (++i == raw.size()) throw new IOException("Loader JVM option has no value: " + arg);
                arg = arg + "=" + raw.get(i);
            }
            if (arg.startsWith("-p=") || arg.startsWith("--module-path=")) {
                String paths = arg.substring(arg.indexOf('=') + 1);
                for (String path : paths.split(":", -1))
                    if (!path.startsWith("/") || path.contains("/../") || path.endsWith("/.."))
                        throw new IOException("Invalid loader module path");
                out.add("-Djdk.module.path=" + paths);
            } else if (arg.startsWith("--add-modules=") || arg.startsWith("--add-opens=") || arg.startsWith("--add-exports=")) {
                out.add(arg);
            } else if (arg.startsWith("-D")) {
                // Loader-owned properties must not replace the runner or account configuration.
                try { InstanceArguments.jvm(arg); }
                catch (IllegalArgumentException invalid) { throw new IOException("Unsafe loader property", invalid); }
                out.add(arg);
            } else throw new IOException("Unsupported loader JVM option: " + arg);
        }
        return List.copyOf(out);
    }

    private static List<String> arguments(JsonObject profile, String kind, Map<String,String> values) throws IOException {
        List<String> out = new ArrayList<>();
        if (!profile.has("arguments") || !profile.getAsJsonObject("arguments").has(kind)) return out;
        for (JsonElement entry : profile.getAsJsonObject("arguments").getAsJsonArray(kind)) {
            if (entry.isJsonPrimitive()) out.add(expand(entry.getAsString(), values));
            else {
                JsonObject rule = entry.getAsJsonObject();
                if (!VersionStore.allowed(rule.get("rules"))) continue;
                JsonElement value = rule.get("value");
                if (value.isJsonArray()) for (JsonElement v : value.getAsJsonArray()) out.add(expand(v.getAsString(), values));
                else out.add(expand(value.getAsString(), values));
            }
        }
        return List.copyOf(out);
    }

    static String expand(String input, Map<String,String> values) throws IOException {
        Matcher m = TOKEN.matcher(input);
        StringBuffer result = new StringBuffer();
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) throw new IOException("Unsupported loader placeholder: " + m.group(1));
            m.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        m.appendTail(result);
        String text = result.toString();
        if (text.contains("${") || text.indexOf(0) >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
                || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8191)
            throw new IOException("Invalid or oversized loader argument");
        return text;
    }

    static Class<?> mainClass(String name) throws IOException {
        try {
            for (Module module : ModuleLayer.boot().modules()) {
                Class<?> found = Class.forName(module, name);
                if (found != null) return found;
            }
            return Class.forName(name, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException missing) { throw new IOException("Loader entry point is missing: " + name, missing); }
    }
}
