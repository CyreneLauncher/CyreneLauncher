package orbis.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * An instance's own JVM and Minecraft arguments, as Amethyst's profile editor offers them. Both are split
 * like a shell would (spaces separate, double quotes group) and checked so they cannot take over what the
 * runner or the launcher manages: memory, class and module paths, the runner's properties, the player's
 * identity and the game directory.
 */
final class InstanceArguments {
    static final int MAX_LENGTH = 1000;
    /** runner_config.c's runner_option_reserved: the runner refuses to start with these. */
    private static final Set<String> RUNNER_PROPERTIES = Set.of("java.home", "java.class.path", "java.library.path",
            "java.io.tmpdir", "user.dir", "java.system.class.loader", "runner.display", "runner.desktop.bundle",
            "runner.graphics.bundle", "runner.gpu.metadata", "runner.gpu.libraries", "runner.jar", "runner.output.log",
            "runner.debug.log", "orbis.runner.root", "orbis.runner.screen.enabled", "orbis.console.mode", "orbis.module.finder.imageFallback",
            "orbis.module.names.file", "orbis.module.names.list", "jdk.module.path", "jdk.module.upgrade.path",
            "jdk.module.main", "jdk.module.addmods", "sun.boot.library.path", "sun.boot.class.path");
    private static final List<String> RUNNER_FLAGS = List.of("--module-path", "--upgrade-module-path", "--patch-module",
            "--limit-modules", "--add-modules", "-p", "-classpath", "-cp", "--class-path", "-Xbootclasspath");
    /** The launcher sets these from the instance's settings and account. */
    private static final Set<String> GAME_MANAGED = Set.of("--username", "--uuid", "--accessToken", "--userType",
            "--xuid", "--clientId", "--gameDir", "--userProperties", "--version", "--assetIndex", "--versionType",
            "--assetsDir", "--orbis-launch-intent");

    private InstanceArguments() { }

    static List<String> split(String text) {
        if (text.length() > MAX_LENGTH) throw new IllegalArgumentException("Arguments are limited to " + MAX_LENGTH + " characters");
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false, any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Line breaks from the multi-line field separate arguments like spaces.
            if (c == 0) throw new IllegalArgumentException("Arguments cannot contain NUL");
            if (c == '"') { quoted = !quoted; any = true; }
            else if (Character.isWhitespace(c) && !quoted) {
                if (any) out.add(current.toString());
                current.setLength(0);
                any = false;
            } else { current.append(c); any = true; }
        }
        if (quoted) throw new IllegalArgumentException("A quote is not closed");
        if (any) out.add(current.toString());
        return out;
    }

    /** JVM options the runner will accept, or why not. */
    static List<String> jvm(String text) {
        List<String> options = split(text);
        for (String option : options) {
            if (!option.startsWith("-")) throw new IllegalArgumentException("Not a JVM option: " + option);
            if (option.startsWith("-Xmx") || option.startsWith("-Xms") || option.startsWith("-Dorbis.graphics.arenaMib"))
                throw new IllegalArgumentException("Set memory with Java heap and GPU memory, not " + option);
            if (option.equals("-jar") || option.startsWith("-javaagent:") && option.contains("orbis"))
                throw new IllegalArgumentException("The launcher starts the game itself: " + option);
            if (option.startsWith("-D")) {
                String key = option.substring(2).split("=", 2)[0];
                if (RUNNER_PROPERTIES.contains(key) || key.startsWith("jdk.module.") || key.startsWith("orbis.module.") || key.startsWith("orbis.loader."))
                    throw new IllegalArgumentException("The runner manages " + key);
            }
            for (String flag : RUNNER_FLAGS)
                if (option.equals(flag) || option.startsWith(flag + "=") || option.startsWith(flag + ":")
                        || option.startsWith(flag + "/"))
                    throw new IllegalArgumentException("The runner manages " + flag);
        }
        return options;
    }

    /** Minecraft arguments that leave identity, version and game directory to the launcher, or why not. */
    static List<String> game(String text) {
        List<String> args = split(text);
        for (String arg : args) {
            String key = arg.split("=", 2)[0];
            if (GAME_MANAGED.contains(key) || key.equals("--launchTarget") || key.startsWith("--fml.")) throw new IllegalArgumentException("The launcher sets " + key);
        }
        return args;
    }
}
