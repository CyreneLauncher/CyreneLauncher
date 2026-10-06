package orbis.launcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Identity and instance arguments are constructed in memory; never persisted or logged. */
final class LaunchArguments {
    private static final Set<String> MANAGED = Set.of("--username", "--uuid", "--accessToken", "--userType",
            "--xuid", "--clientId", "--gameDir", "--userProperties");

    private LaunchArguments() {}

    static String[] build(String[] existing, InstanceStore.Instance instance, AccountStore.Session session)
            throws IOException {
        List<String> args = new ArrayList<>();
        for (int i = 0; i < existing.length; i++) {
            String value = existing[i];
            int equal = value.indexOf('=');
            String key = equal < 0 ? value : value.substring(0, equal);
            if (MANAGED.contains(key)) {
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
        return args.toArray(String[]::new);
    }

    private static void add(List<String> args, String key, String value) { args.add(key); args.add(value); }
}
