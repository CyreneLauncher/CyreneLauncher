package orbis.launcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Identity and instance arguments are constructed in memory; never persisted or logged. */
final class LaunchArguments {
    private static final Set<String> MANAGED = Set.of("--username", "--uuid", "--accessToken", "--userType",
            "--xuid", "--clientId", "--gameDir", "--userProperties");
    /**
     * Replaced only for a downloaded version; the built-in runtime keeps the configured values. The
     * installed PKG has no app-args.txt, so without --assetsDir the game looked in its own empty
     * gameDir/assets instead of where VersionStore downloaded them.
     */
    private static final Set<String> VERSION = Set.of("--version", "--assetIndex", "--versionType", "--assetsDir");

    private LaunchArguments() {}

    static String[] build(String[] existing, InstanceStore.Instance instance, AccountStore.Session session)
            throws IOException {
        return build(existing, instance, session, null, null);
    }

    static String[] build(String[] existing, InstanceStore.Instance instance, AccountStore.Session session,
                          VersionStore.Installed version, Path assetsDir) throws IOException {
        if (version != null && assetsDir == null) throw new IOException("A downloaded version needs its assets directory");
        List<String> args = new ArrayList<>();
        for (int i = 0; i < existing.length; i++) {
            String value = existing[i];
            int equal = value.indexOf('=');
            String key = equal < 0 ? value : value.substring(0, equal);
            if (MANAGED.contains(key) || (version != null && VERSION.contains(key))) {
                if (equal < 0) {
                    if (++i >= existing.length || existing[i].startsWith("--"))
                        throw new IOException("A configured identity argument is missing its value");
                }
            } else if (!value.startsWith("--orbis-launch-intent=")) args.add(value);
        }
        add(args, "--username", session.username());
        add(args, "--uuid", session.uuid());
        add(args, "--accessToken", session.accessToken());
        add(args, "--userType", session.userType());
        add(args, "--userProperties", "{}");
        add(args, "--gameDir", instance.gameDir().toString());
        if (!session.xuid().isEmpty()) add(args, "--xuid", session.xuid());
        if (!session.clientId().isEmpty()) add(args, "--clientId", session.clientId());
        if (version != null) {
            add(args, "--version", version.id());
            add(args, "--assetsDir", assetsDir.toString());
            add(args, "--assetIndex", version.assetIndex());
            add(args, "--versionType", version.versionType());
        }
        // The instance's own arguments, which cannot replace any of the above.
        try { args.addAll(InstanceArguments.game(instance.gameArgs())); }
        catch (IllegalArgumentException invalid) { throw new IOException("Minecraft arguments: " + invalid.getMessage()); }
        return args.toArray(String[]::new);
    }

    private static void add(List<String> args, String key, String value) { args.add(key); args.add(value); }
}
