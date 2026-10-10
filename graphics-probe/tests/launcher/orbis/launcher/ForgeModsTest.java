package orbis.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Catches rejected Forge metadata and silently substituted/conflicting pack loaders. */
public final class ForgeModsTest {
    public static void main(String[] args) throws Exception {
        for (VersionStore.Loader loader : List.of(VersionStore.Loader.FORGE,VersionStore.Loader.NEOFORGE)) {
            String key = loader == VersionStore.Loader.FORGE ? "forge" : "neoforge";
            String build = loader == VersionStore.Loader.FORGE ? "47.4.0" : "20.4.237";
            Path pack = Files.createTempFile("loader-pack",".mrpack");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
                zip.putNextEntry(new ZipEntry("modrinth.index.json"));
                zip.write(("{\"formatVersion\":1,\"game\":\"minecraft\",\"name\":\"Loader pack\",\"dependencies\":{\"minecraft\":\"1.20.4\",\""+key+"\":\""+build+"\"},\"files\":[]}").getBytes(StandardCharsets.UTF_8));
            }
            PackInstaller.Pack parsed = PackInstaller.read(pack);
            ForgeProfilesTest.check(parsed.installable() && parsed.loader() == loader && parsed.loaderVersion().equals(build),"exact pack loader retained: "+key);
            ForgeProfilesTest.check(LoaderLaunch.modrinthLoaders(loader).equals(List.of(key)),"mod loader filter");
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("META-INF/mods.toml"));
            zip.write("modLoader=\"javafml\"\n[[mods]]\nmodId=\"testmod\"\nversion=\"1.2.3\"\ndisplayName=\"Test Mod\"\n[[mods]]\nmodId=\"secondmod\"\nversion=\"2.0\"\n".getBytes(StandardCharsets.UTF_8));
        }
        ModInspector.ModInfo info = ModInspector.inspect(bytes.toByteArray());
        ForgeProfilesTest.check("testmod".equals(info.id()) && "Test Mod".equals(info.name()) && "1.2.3".equals(info.version()) && info.provides().contains("secondmod"),"Forge TOML metadata");
        ForgeProfilesTest.check(ModInspector.forLoader(bytes.toByteArray(),List.of("forge")),"Forge mod accepted");
        ForgeProfilesTest.check(!ModInspector.forLoader(bytes.toByteArray(),List.of("fabric")),"foreign loader rejected");
        Path root = Files.createTempDirectory("forge-pack-profile"), home = root.resolve("home"), runner = root.resolve("runner");
        Files.createDirectories(runner); Files.writeString(runner.resolve("runner.conf"),"java_home=/app0/runtimes/jdk17\n");
        Files.writeString(runner.resolve("jvm-options.txt"),"-Xmx1024m\n");
        Path base = root.resolve("launcher.json"); Files.writeString(base,"{\"id\":\"orbis-launcher\",\"mainClass\":\"orbis.launcher.Launcher\"}");
        System.setProperty("orbis.launcher.home",home.toString()); System.setProperty("orbis.runner.root",runner.toString()); System.setProperty("orbis.launcher.gameMetadata",base.toString());
        LauncherConfig config = LauncherConfig.load();
        Path wanted = null;
        for (String build : List.of("49.2.0","49.0.0")) {
            String id = "1.20.4-forge-"+build;
            Path dir = Files.createDirectories(home.resolve("versions").resolve(id));
            Files.write(dir.resolve("client.jar"),new byte[]{1});
            Files.writeString(dir.resolve("game.json"),"{\"orbisLoaderArguments\":[\"--launchTarget\",\"forgeclient\",\"--fml.mcVersion\",\"1.20.4\"]}");
            Files.writeString(dir.resolve("boot.json"),"{}");
            Files.writeString(dir.resolve("install.json"),"{\"schema\":1,\"id\":\""+id+"\",\"minecraft\":\"1.20.4\",\"loader\":\"FORGE\",\"loaderVersion\":\""+build+"\",\"mainClass\":\"cpw.mods.bootstraplauncher.BootstrapLauncher\",\"assetIndex\":\"12\",\"versionType\":\"release\",\"java\":17,\"jvm\":[],\"provides\":{},\"generated\":[]}");
            if (build.equals("49.0.0")) wanted = dir.resolve("game.json");
        }
        Path pack = Files.createTempFile("exact-loader",".mrpack");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            zip.putNextEntry(new ZipEntry("modrinth.index.json"));
            zip.write("{\"formatVersion\":1,\"game\":\"minecraft\",\"name\":\"Exact Forge\",\"dependencies\":{\"minecraft\":\"1.20.4\",\"forge\":\"49.0.0\"},\"files\":[]}".getBytes(StandardCharsets.UTF_8));
        }
        LauncherScreen screen = new LauncherScreen(config,null,new ModrinthClient("1.20.4",List.of("forge")),null);
        PackInstaller.Installed result = screen.packInstaller().install(PackInstaller.read(pack),"Exact Forge",new InstanceStore.Operation());
        ForgeProfilesTest.check(result.instance().runtimeProfile().equals(wanted),"screen pack installation reuses exact build, not a newer build");
        List<String> gameArgs = List.of(LaunchArguments.build(new String[]{"--launchTarget","wrong","--fml.mcVersion","wrong"},result.instance(),
                new AccountStore.Session("LocalPlayer","0".repeat(32),"0","legacy","",""),config.versions.installed(wanted),root.resolve("assets")));
        ForgeProfilesTest.check(!gameArgs.contains("wrong") && gameArgs.contains("forgeclient") && gameArgs.contains("--fml.mcVersion") && gameArgs.contains("LocalPlayer"),"loader arguments coexist with managed offline identity");
        java.lang.reflect.Method cycle = LauncherScreen.class.getDeclaredMethod("cycleLoader",int.class); cycle.setAccessible(true);
        java.lang.reflect.Field selected = LauncherScreen.class.getDeclaredField("versionLoader"); selected.setAccessible(true);
        java.util.Set<Object> seen = new java.util.HashSet<>();
        for (int i=0;i<5;i++) { seen.add(selected.get(screen)); cycle.invoke(screen,1); }
        ForgeProfilesTest.check(seen.contains(VersionStore.Loader.FORGE) && seen.contains(VersionStore.Loader.NEOFORGE),"version selector cycles through both new loaders");
        System.out.println("Forge mod and pack tests passed");
    }
}
