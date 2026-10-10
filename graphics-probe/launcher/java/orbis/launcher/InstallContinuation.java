package orbis.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** One-shot installation restart on a newer JVM; contains version metadata and no account credentials. */
final class InstallContinuation {
    record Pending(VersionStore.GameVersion game, VersionStore.Loader loader, String loaderVersion, int java) { }
    private InstallContinuation() { }
    private static Path file(Path home) { return home.resolve("install-continuation.json"); }

    static String prepare(LauncherConfig config, VersionStore.GameVersion game, VersionStore.Loader loader,
            String build, Runtimes.Runtime runtime) throws IOException {
        validate(game,loader,build,runtime.feature());
        String intent = UUID.randomUUID().toString();
        JsonObject record = new JsonObject();
        record.addProperty("schema",1); record.addProperty("intent",intent);
        record.addProperty("createdAt",System.currentTimeMillis());
        record.addProperty("minecraft",game.id()); record.addProperty("url",game.url()); record.addProperty("sha1",game.sha1());
        record.addProperty("loader",loader.name()); record.addProperty("loaderVersion",build); record.addProperty("java",runtime.feature());
        LauncherFiles.write(file(config.home),record.toString().getBytes(StandardCharsets.UTF_8),true);
        try { new LaunchPreparation(config).prepareInstaller(runtime,intent); }
        catch (IOException failed) { Files.deleteIfExists(file(config.home)); throw failed; }
        return intent;
    }

    /** Restore global configuration before LauncherConfig reads runtime discovery/settings. */
    static void recover(Path home, Path runner) throws IOException {
        LauncherFiles.rejectSymlinks(file(home));
        if (Files.exists(file(home))) LaunchPreparation.recoverAfterConfigFailure(home,runner);
    }

    static Pending consume(Path home, String invocation) throws IOException {
        Path path = file(home);
        LauncherFiles.rejectSymlinks(path);
        if (!Files.exists(path)) return null;
        byte[] bytes = LauncherFiles.read(path,32768);
        Files.delete(path);
        try {
            JsonObject record = JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            long age = System.currentTimeMillis() - record.get("createdAt").getAsLong();
            String intent = record.get("intent").getAsString();
            if (record.get("schema").getAsInt() != 1 || !UUID.fromString(intent).toString().equals(intent)
                    || age < 0 || age > 300_000 || (invocation == null ? age > 30_000 : !intent.equals(invocation)))
                throw new IOException("Discarded a stale installation restart");
            VersionStore.GameVersion game = new VersionStore.GameVersion(record.get("minecraft").getAsString(),record.get("url").getAsString(),record.get("sha1").getAsString());
            VersionStore.Loader loader = VersionStore.Loader.valueOf(record.get("loader").getAsString());
            String build = record.get("loaderVersion").getAsString(); int java = record.get("java").getAsInt();
            validate(game,loader,build,java);
            return new Pending(game,loader,build,java);
        } catch (RuntimeException malformed) { throw new IOException("Invalid installation restart",malformed); }
    }

    private static void validate(VersionStore.GameVersion game, VersionStore.Loader loader, String build, int feature) throws IOException {
        java.net.URI uri = java.net.URI.create(game.url());
        if (!ForgeInstaller.family(loader) || !ForgeInstaller.supported(loader,game.id()) || !build.matches("[0-9.]+(-beta)?")
                || !game.sha1().matches("[a-f0-9]{40}") || !"https".equals(uri.getScheme())
                || !java.util.Set.of("piston-meta.mojang.com","launchermeta.mojang.com").contains(uri.getHost())
                || (feature != 17 && feature != 21 && feature != 25)) throw new IOException("Invalid installation restart target");
    }
}
