package orbis.launcher;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Networked real-install probe, intentionally opt-in and never uses a player session. */
public final class ForgeIntegration {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(root);
        System.setProperty("orbis.launcher.home",root.resolve("home").toString());
        VersionStore.offeredJava = Runtime.version().feature();
        VersionStore.Loader loader = VersionStore.Loader.valueOf(args[1]);
        String minecraft = args[2], build = args[3];
        VersionStore store = new VersionStore(root.resolve("home"),root.resolve("libraries"),root.resolve("assets"),Path.of(args[4]),() -> { });
        JsonObject manifest = JsonParser.parseString(new String(VersionStore.fetch(VersionStore.MANIFEST_URL,16L << 20),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        for (JsonElement element : manifest.getAsJsonArray("versions")) {
            JsonObject game = element.getAsJsonObject();
            if (!game.get("id").getAsString().equals(minecraft)) continue;
            InstanceStore.Operation op = new InstanceStore.Operation();
            Thread progress = new Thread(() -> {
                try { while (true) { System.out.println("PROGRESS " + op.progress()); Thread.sleep(10000); } }
                catch (InterruptedException done) { Thread.currentThread().interrupt(); }
            }); progress.setDaemon(true); progress.start();
            try {
                VersionStore.Installed installed = store.install(new VersionStore.GameVersion(minecraft,game.get("url").getAsString(),game.get("sha1").getAsString()),loader,build,op,false);
                store.verifyLoader(installed);
                if (!installed.loaderVersion().equals(build) || installed.gameOptions().isEmpty()) throw new AssertionError("wrong loader install");
                Files.writeString(root.resolve(loader.name() + "-" + minecraft + "-installed.txt"),installed.gameProfile().toString());
                System.out.println("INSTALL VERIFIED " + installed.title() + " " + installed.gameProfile());
            } finally { progress.interrupt(); }
            return;
        }
        throw new IllegalStateException("Minecraft missing from manifest");
    }
}
