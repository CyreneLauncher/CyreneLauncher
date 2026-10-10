package orbis.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settings some mods need on the console, applied to an instance before each launch through the
 * mods' own configuration switches.
 *
 * <p>Ixeris moves GLFW event handling to a thread of its own by injecting into LWJGL's GLFW class
 * around its native calls. On the console GLFW is the Orbis facade, which has no native calls and
 * gets its events from the system input service: the injections find no targets, and the game
 * fails to start. Ixeris turns itself off per platform (as it does on PojavLauncher); Orbis
 * reports itself as Linux through LWJGL's Platform, so enabledOnLinux and enabledOnOtherPlatforms
 * are set to false in config/ixeris.toml. Nothing else in the file changes.
 */
final class ConsoleModSettings {
    static final String IXERIS = "ixeris";
    private static final String[] IXERIS_OFF = {"enabledOnLinux", "enabledOnOtherPlatforms"};
    private static final long MAX_CONFIG = 256 * 1024;

    private ConsoleModSettings() {}

    /** Applies every setting an installed mod of the instance needs; true when a file changed. */
    static boolean apply(Path gameDir) throws IOException {
        if (!hasMod(gameDir.resolve("mods"), IXERIS)) return false;
        Path config = gameDir.resolve("config").resolve("ixeris.toml");
        String text = Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)
                ? new String(LauncherFiles.read(config, MAX_CONFIG), StandardCharsets.UTF_8) : "";
        String updated = switchOff(text, IXERIS_OFF);
        if (updated.equals(text)) return false;
        Files.createDirectories(config.getParent());
        LauncherFiles.write(config, updated.getBytes(StandardCharsets.UTF_8), false);
        System.out.println("launcher: Ixeris turned off for the console in " + config);
        return true;
    }

    /** Forge's separate early window is disabled so the game owns the console display. */
    static void applyLoader(Path gameDir, VersionStore.Loader loader) throws IOException {
        if (!ForgeInstaller.family(loader)) return;
        Path file = gameDir.resolve("config/fml.toml");
        LauncherFiles.rejectSymlinks(file);
        String text = Files.exists(file) ? new String(LauncherFiles.read(file,MAX_CONFIG),StandardCharsets.UTF_8) : "";
        String updated = switchOff(text,"earlyWindowControl");
        if (!text.equals(updated)) LauncherFiles.write(file,updated.getBytes(StandardCharsets.UTF_8),false);
    }

    /** Sets each top-level key to false, adding the keys that are missing ahead of the first table. */
    static String switchOff(String toml, String... keys) {
        String text = toml;
        StringBuilder missing = new StringBuilder();
        for (String key : keys) {
            Pattern line = Pattern.compile("(?m)^([ \\t]*)" + Pattern.quote(key) + "[ \\t]*=[ \\t]*[^\\r\\n#]*?([ \\t]*(#[^\\r\\n]*)?)$");
            Matcher m = line.matcher(text);
            if (m.find()) text = m.replaceAll("$1" + Matcher.quoteReplacement(key) + " = false$2");
            else missing.append(key).append(" = false\n");
        }
        if (missing.length() == 0) return text;
        Matcher table = Pattern.compile("(?m)^[ \\t]*\\[").matcher(text);
        int at = table.find() ? table.start() : text.length();
        String head = text.substring(0, at);
        if (!head.isEmpty() && !head.endsWith("\n")) head += "\n";
        String rest = text.substring(at);
        return head + missing + (rest.isEmpty() ? "" : "\n" + rest);
    }

    /** An enabled JAR in mods whose own mod id is id; names are checked first so most JARs are not read. */
    private static boolean hasMod(Path mods, String id) {
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path jar : jars) {
                if (!jar.getFileName().toString().toLowerCase(Locale.ROOT).contains(id)) continue;
                try {
                    if (id.equals(ModInspector.inspect(Files.readAllBytes(jar)).id())) return true;
                } catch (IOException | RuntimeException unreadable) {
                    // not a readable mod
                }
            }
        } catch (IOException noMods) {
            return false;
        }
        return false;
    }
}
