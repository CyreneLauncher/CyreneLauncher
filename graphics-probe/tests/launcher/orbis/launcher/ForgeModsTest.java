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
        System.out.println("Forge mod and pack tests passed");
    }
}
