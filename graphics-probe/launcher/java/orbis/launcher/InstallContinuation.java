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
    record Request(String instanceName, Path archive, String sha1) {
        static Request version() { return new Request("",null,""); }
        static Request instance(String name) { return new Request(name,null,""); }
        static Request pack(String name, Path archive) throws IOException {
            LauncherFiles.rejectSymlinks(archive);
            return new Request(name.length()>60 ? name.substring(0,60).strip() : name,archive.toAbsolutePath().normalize(),InstallerProcessors.hash(archive));
        }
    }
    record Pending(VersionStore.GameVersion game, VersionStore.Loader loader, String loaderVersion, int java, Request request) { }
    static final class RestartRequired extends IOException {
        private static final long serialVersionUID = 1L;
        RestartRequired() { super("Restart required to finish loader installation"); }
    }
    private InstallContinuation() { }
    private static Path file(Path home) { return home.resolve("install-continuation.json"); }

    static String prepare(LauncherConfig config, VersionStore.GameVersion game, VersionStore.Loader loader,
            String build, Runtimes.Runtime runtime) throws IOException {
        return prepare(config,game,loader,build,runtime,Request.version());
    }

    static String prepare(LauncherConfig config, VersionStore.GameVersion game, VersionStore.Loader loader,
            String build, Runtimes.Runtime runtime, Request request) throws IOException {
        validate(game,loader,build,runtime.feature());
        validateRequest(config.home,request);
        String intent = UUID.randomUUID().toString();
        JsonObject record = new JsonObject();
        record.addProperty("schema",2); record.addProperty("intent",intent);
        record.addProperty("createdAt",System.currentTimeMillis());
        record.addProperty("minecraft",game.id()); record.addProperty("url",game.url()); record.addProperty("sha1",game.sha1());
        record.addProperty("loader",loader.name()); record.addProperty("loaderVersion",build); record.addProperty("java",runtime.feature());
        record.addProperty("instanceName",request.instanceName());
        if (request.archive() != null) {
            record.addProperty("archive",request.archive().toString()); record.addProperty("archiveSha1",request.sha1());
        }
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

    static void discard(Path home, Path runner) throws IOException {
        recover(home,runner);
        Path path=file(home); LauncherFiles.rejectSymlinks(path);
        if (!Files.exists(path)) return;
        JsonObject record=JsonParser.parseString(new String(LauncherFiles.read(path,32768),StandardCharsets.UTF_8)).getAsJsonObject();
        Files.delete(path);
        if (record.has("archive")) {
            Path archive=Path.of(record.get("archive").getAsString()).toAbsolutePath().normalize();
            if (archive.startsWith(home.resolve("cache/packs").toAbsolutePath().normalize())) {
                LauncherFiles.rejectSymlinks(archive); Files.deleteIfExists(archive);
            }
        }
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
            if (record.get("schema").getAsInt() != 2 || !UUID.fromString(intent).toString().equals(intent)
                    || age < 0 || age > 300_000 || (invocation == null ? age > 30_000 : !intent.equals(invocation)))
                throw new IOException("Discarded a stale installation restart");
            VersionStore.GameVersion game = new VersionStore.GameVersion(record.get("minecraft").getAsString(),record.get("url").getAsString(),record.get("sha1").getAsString());
            VersionStore.Loader loader = VersionStore.Loader.valueOf(record.get("loader").getAsString());
            String build = record.get("loaderVersion").getAsString(); int java = record.get("java").getAsInt();
            validate(game,loader,build,java);
            Request request = new Request(record.get("instanceName").getAsString(),
                    record.has("archive") ? Path.of(record.get("archive").getAsString()) : null,
                    record.has("archiveSha1") ? record.get("archiveSha1").getAsString() : "");
            validateRequest(home,request);
            return new Pending(game,loader,build,java,request);
        } catch (RuntimeException malformed) { throw new IOException("Invalid installation restart",malformed); }
    }

    /** Resume exactly the player's original operation after patching, with normal pack cleanup. */
    static void finish(LauncherConfig config, Pending pending, Path profile, InstanceStore.Operation op) throws IOException {
        Request request=pending.request();
        if (request.instanceName().isEmpty()) return;
        InstanceStore.Instance instance;
        if (request.archive() == null) instance=config.instances.create(request.instanceName(),profile,op);
        else {
            try {
                PackInstaller.Pack pack=PackInstaller.read(request.archive());
                if (!pack.minecraft().equals(pending.game().id()) || pack.loader()!=pending.loader()
                        || !pack.loaderVersion().equals(pending.loaderVersion())) throw new IOException("Restarted pack does not match its loader");
                PackInstaller.Source source=new PackInstaller.Source() {
                    @Override public byte[] fetch(String url, long max) throws IOException { return VersionStore.fetch(url,max); }
                    @Override public byte[] verified(String url, String sha1, long size, long max) throws IOException {
                        return DownloadCache.bytes(config.home.resolve("cache/downloads"),url,"SHA-1",sha1,size,max);
                    }
                };
                instance=new PackInstaller(source,(mc,loader,build,operation)->profile,config.instances).install(pack,request.instanceName(),op).instance();
            } finally { Files.deleteIfExists(request.archive()); }
        }
        config.selectInstance(instance.id());
    }

    private static void validateRequest(Path home, Request request) throws IOException {
        String name=request.instanceName();
        if (name==null || name.length()>80 || name.codePoints().anyMatch(Character::isISOControl)
                || (!name.isEmpty() && name.isBlank())) throw new IOException("Invalid installation instance name");
        if (request.archive()==null) { if (!request.sha1().isEmpty()) throw new IOException("Invalid pack restart"); return; }
        Path archive=request.archive().toAbsolutePath().normalize();
        if (name.isEmpty() || !archive.startsWith(home.resolve("cache/packs").toAbsolutePath().normalize())
                || !archive.toString().endsWith(".mrpack") || !request.sha1().matches("[a-f0-9]{40}")) throw new IOException("Invalid pack restart path");
        LauncherFiles.rejectSymlinks(archive);
        if (!Files.isRegularFile(archive) || Files.size(archive)>(512L<<20) || !InstallerProcessors.hash(archive).equals(request.sha1()))
            throw new IOException("Restarted pack failed verification");
    }

    private static void validate(VersionStore.GameVersion game, VersionStore.Loader loader, String build, int feature) throws IOException {
        java.net.URI uri = java.net.URI.create(game.url());
        if (!ForgeInstaller.family(loader) || !ForgeInstaller.supported(loader,game.id()) || !build.matches("[0-9.]+(-beta)?")
                || !game.sha1().matches("[a-f0-9]{40}") || !"https".equals(uri.getScheme())
                || !java.util.Set.of("piston-meta.mojang.com","launchermeta.mojang.com").contains(uri.getHost())
                || (feature != 17 && feature != 21 && feature != 25)) throw new IOException("Invalid installation restart target");
    }
}
