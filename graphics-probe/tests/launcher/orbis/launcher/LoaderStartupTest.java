package orbis.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Prevents module settings/user launch targets from bypassing the managed launch transaction. */
public final class LoaderStartupTest {
    public static void main(String[] args) throws Exception {
        List<String> options = LoaderLaunch.nativeOptions(List.of("--module-path=/data/libs/a.jar", "--add-modules=ALL-MODULE-PATH", "--add-opens=java.base/java.util.jar=cpw.mods.securejarhandler"));
        ForgeProfilesTest.check(options.equals(List.of("-Dorbis.loader.modulePath=/data/libs/a.jar", "-Dorbis.loader.addModules=ALL-MODULE-PATH", "--add-opens=java.base/java.util.jar=cpw.mods.securejarhandler")),"native trusted options");
        try { InstanceArguments.jvm("-Dorbis.loader.modulePath=/evil.jar"); throw new AssertionError("user module override"); } catch (IllegalArgumentException expected) { }
        try { InstanceArguments.game("--launchTarget evil"); throw new AssertionError("user loader override"); } catch (IllegalArgumentException expected) { }
        Path root = Files.createTempDirectory("loader-startup");
        Runtimes runtimes = new Runtimes(new Runtimes.Runtime(root.resolve("jdk25"),25),List.of(new Runtimes.Runtime(root.resolve("jdk17"),17)));
        ForgeProfilesTest.check(LoaderLaunch.runtime(runtimes,17).feature() == 17,"exact Java for loader");
        ForgeProfilesTest.fails(() -> LoaderLaunch.runtime(runtimes,21));
        Path configFile = root.resolve("game/config/fml.toml");
        Files.createDirectories(configFile.getParent()); Files.writeString(configFile,"earlyWindowControl=true\nother=42\n");
        ConsoleModSettings.applyLoader(root.resolve("game"),VersionStore.Loader.FORGE);
        ForgeProfilesTest.check(Files.readString(configFile).contains("earlyWindowControl = false") && Files.readString(configFile).contains("other=42"),"early window disabled without replacing other settings");
        Path home = root.resolve("home"), runner = root.resolve("runner"), metadata = root.resolve("launcher.json");
        Files.createDirectories(home); Files.createDirectories(runner);
        Files.writeString(metadata,"{\"id\":\"orbis-launcher\",\"mainClass\":\"orbis.launcher.Launcher\",\"libraries\":[]}");
        Files.writeString(runner.resolve("runner.conf"),"java_home=/app0/runtimes/jdk17\njar=/app0/orbis/graphics/lib/orbis-launcher.jar\n");
        Files.writeString(runner.resolve("jvm-options.txt"),"-Xmx1024m\n");
        System.setProperty("orbis.launcher.home",home.toString());
        System.setProperty("orbis.runner.root",runner.toString());
        System.setProperty("orbis.launcher.gameMetadata",metadata.toString());
        LauncherConfig config = LauncherConfig.load();
        String original = Files.readString(runner.resolve("runner.conf"));
        VersionStore.GameVersion game = new VersionStore.GameVersion("1.21.1","https://piston-meta.mojang.com/test.json","a".repeat(40));
        String intent = InstallContinuation.prepare(config,game,VersionStore.Loader.NEOFORGE,"21.1.172",new Runtimes.Runtime(root.resolve("jdk21"),21));
        ForgeProfilesTest.check(Files.readString(runner.resolve("runner.conf")).contains(root.resolve("jdk21").toString()),"installation runtime applied");
        InstallContinuation.recover(home,runner);
        ForgeProfilesTest.check(Files.readString(runner.resolve("runner.conf")).equals(original),"runtime restored before config load");
        ForgeProfilesTest.check(InstallContinuation.consume(home,intent).loaderVersion().equals("21.1.172"),"installation continuation matches");
        ForgeProfilesTest.check(InstallContinuation.consume(home,intent) == null,"one-shot continuation");
        intent = InstallContinuation.prepare(config,game,VersionStore.Loader.NEOFORGE,"21.1.172",new Runtimes.Runtime(root.resolve("jdk21"),21));
        InstallContinuation.recover(home,runner);
        ForgeProfilesTest.fails(() -> InstallContinuation.consume(home,java.util.UUID.randomUUID().toString()));
        ForgeProfilesTest.check(InstallContinuation.consume(home,intent) == null,"stale intent discarded");
        System.out.println("Loader startup tests passed");
    }
}
