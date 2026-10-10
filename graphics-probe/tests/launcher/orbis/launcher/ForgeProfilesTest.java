package orbis.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Catches unresolved tokens, wrong module startup forms and malformed installer coordinates. */
public final class ForgeProfilesTest {
    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]);
        Map<String,String> values = Map.of("library_directory", "/data/libs", "classpath_separator", ":",
                "version_name", "1.20.1-forge-47.4.0");
        for (String name : List.of("forge", "neo")) {
            JsonObject profile = JsonParser.parseString(Files.readString(fixtures.resolve(name + "-version.json"))).getAsJsonObject();
            List<String> jvm = LoaderLaunch.jvm(profile, values);
            check(jvm.stream().anyMatch(x -> x.startsWith("--module-path=/data/libs/cpw/mods/bootstraplauncher/")), "module path translated");
            check(jvm.contains("--add-modules=ALL-MODULE-PATH"), "modules loaded before boot");
            check(jvm.contains("--add-opens=java.base/java.util.jar=cpw.mods.securejarhandler"), "opens use JNI form");
            check(jvm.stream().noneMatch(x -> x.contains("${")), "all placeholders expanded");
            List<String> game = LoaderLaunch.game(profile, values);
            check(game.contains("--launchTarget") && game.contains("forgeclient"), "loader target retained");
        }
        check(ForgeInstaller.mavenPath("net.neoforged:neoform:1.21.1:mappings@txt").equals("net/neoforged/neoform/1.21.1/neoform-1.21.1-mappings.txt"), "classifier extension");
        check(ForgeInstaller.mavenPath("a.b:c:1:mappings@tsrg.lzma").equals("a/b/c/1/c-1-mappings.tsrg.lzma"), "compressed mapping extension");
        check(ForgeInstaller.mavenPath("a.b:c:1@zip").equals("a/b/c/1/c-1.zip"), "zip coordinate");
        fails(() -> ForgeInstaller.mavenPath("a:../../evil:1"));
        fails(() -> ForgeInstaller.mavenPath("a:b:1:two:extra"));
        fails(() -> LoaderLaunch.jvm(JsonParser.parseString("{\"arguments\":{\"jvm\":[\"-Dtest=${unknown}\"]}}").getAsJsonObject(), values));
        fails(() -> LoaderLaunch.jvm(JsonParser.parseString("{\"arguments\":{\"jvm\":[\"-javaagent:evil.jar\"]}}").getAsJsonObject(), values));
        check(ForgeInstaller.neoMinecraft("20.2.86").equals("1.20.2"), "Neo version mapping");
        check(ForgeInstaller.neoMinecraft("21.0.167").equals("1.21"), "zero minor");
        check(!ForgeInstaller.supported(VersionStore.Loader.FORGE,"1.16.5"), "old Forge not advertised");
        check(ForgeInstaller.supported(VersionStore.Loader.NEOFORGE,"1.21.1"), "modern Neo offered");
        check(!ForgeInstaller.supported(VersionStore.Loader.NEOFORGE,"1.20.1"), "early Neo excluded");
        Map<String,List<String>> catalog = ForgeInstaller.catalog(VersionStore.Loader.FORGE,
                "<versions><version>1.20.1-47.4.0</version><version>1.16.5-36.0.0</version><version>1.20.1-47.2.0</version></versions>");
        check(catalog.get("1.20.1").equals(List.of("47.4.0", "47.2.0")) && !catalog.containsKey("1.16.5"), "supported sorted catalog");
        check(ForgeInstaller.catalog(VersionStore.Loader.NEOFORGE,
                "<version>21.1.172</version><version>21.1.173-beta</version>").get("1.21.1").equals(List.of("21.1.172", "21.1.173-beta")), "stable Neo selection");
        fails(() -> LoaderLaunch.expand("-Dtest=" + "a".repeat(8192), values));
        check(LoaderLaunch.game(JsonParser.parseString("{\"arguments\":{\"game\":[{\"rules\":[{\"action\":\"disallow\"}],\"value\":\"omit\"},\"keep\"]}}").getAsJsonObject(), values).equals(List.of("keep")), "argument rules");
        check(LoaderLaunch.mainClass("orbis.launcher.ForgeProfilesTest") == ForgeProfilesTest.class, "classpath main lookup");
        System.out.println("Forge profile tests passed");
    }
    interface Action { void run() throws Exception; }
    static void fails(Action action) throws Exception { try { action.run(); } catch (IOException expected) { return; } throw new AssertionError("expected rejection"); }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
