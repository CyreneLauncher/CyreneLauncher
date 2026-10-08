package orbis.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Java runtimes on the console. runner.conf's java_home is the default runner (JDK 17): the
 * launcher always starts on it. Every other JDK export beside it (&lt;orbis root&gt;/&lt;name&gt;/release-desktop,
 * such as the optional JDK 21 runner at /data/orbis-jdk/jdk21/release-desktop) is offered for the
 * versions the default cannot run; each is recognised by its release file's JAVA_VERSION.
 */
final class Runtimes {
    /** One runtime: its java_home and Java feature version (17, 21). */
    record Runtime(Path home, int feature) { }

    /** The exported runtime layout each JDK port uploads (desktop natives: Minecraft needs AWT headless). */
    static final String EXPORT = "release-desktop";
    private static final Pattern JAVA_VERSION = Pattern.compile("(?m)^JAVA_VERSION=\"(1\\.)?(\\d+)");

    final Runtime fallback;
    final List<Runtime> all;
    /** Where other runtimes are looked for (/data/orbis-jdk). */
    final Path root;

    Runtimes(Runtime fallback, List<Runtime> others, Path root) {
        this.fallback = fallback;
        this.root = root;
        List<Runtime> list = new ArrayList<>();
        list.add(fallback);
        for (Runtime runtime : others)
            if (!runtime.home().equals(fallback.home())) list.add(runtime);
        list.sort(Comparator.comparingInt(Runtime::feature));
        all = List.copyOf(list);
    }

    /**
     * The default runner (java_home) plus the exports under root. An unreadable default counts as the
     * running JVM's version: the launcher itself runs on the default.
     */
    static Runtimes discover(Path defaultHome, Path root) {
        int running = java.lang.Runtime.version().feature();
        Runtime fallback = new Runtime(defaultHome.toAbsolutePath().normalize(), feature(defaultHome, running));
        List<Runtime> others = new ArrayList<>();
        if (root != null && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
                for (Path dir : dirs) {
                    Path home = dir.resolve(EXPORT);
                    int feature = feature(home, -1);
                    if (feature > 0) others.add(new Runtime(home.toAbsolutePath().normalize(), feature));
                }
            } catch (IOException unreadable) {
                System.err.println("launcher: cannot list Java runtimes in " + root + ": " + unreadable.getMessage());
            }
        }
        return new Runtimes(fallback, others, root);
    }

    /** JAVA_VERSION's feature number from home/release, or orElse when it is missing or unreadable. */
    static int feature(Path home, int orElse) {
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(home.resolve("lib/server/libjvm.prx"), LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(home.resolve("lib/server/libjvm.so"), LinkOption.NOFOLLOW_LINKS))
            return orElse;
        try {
            Matcher m = JAVA_VERSION.matcher(new String(LauncherFiles.read(release, 65536), StandardCharsets.UTF_8));
            return m.find() ? Integer.parseInt(m.group(2)) : orElse;
        } catch (IOException | RuntimeException unreadable) {
            return orElse;
        }
    }

    /** The newest Java any runtime here provides. */
    int newest() {
        return all.get(all.size() - 1).feature();
    }

    /** The default runner when it is new enough, else the oldest runtime that is; null when none is. */
    Runtime forJava(int required) {
        if (fallback.feature() >= required) return fallback;
        for (Runtime runtime : all)
            if (runtime.feature() >= required) return runtime;
        return null;
    }
}
