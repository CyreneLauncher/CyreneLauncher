package orbis.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Starts actual upstream bootstrap using the same system-loader shape as the console runner. */
public final class ForgeBootstrap {
    public static void main(String[] args) throws Throwable {
        Path profileFile = Path.of(args[0]), libraries = Path.of(args[1]), gameDir = Path.of(args[2]);
        Files.createDirectories(gameDir.resolve("config"));
        Files.writeString(gameDir.resolve("config/fml.toml"),"earlyWindowControl=false\n");
        JsonObject profile = JsonParser.parseString(Files.readString(profileFile)).getAsJsonObject();
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        List<String> cp = new ArrayList<>();
        cp.add(System.getProperty("runner.jar"));
        for (JsonElement element : profile.getAsJsonArray("libraries")) {
            JsonObject artifact = element.getAsJsonObject().getAsJsonObject("downloads").getAsJsonObject("artifact");
            Path jar = libraries.resolve(artifact.get("path").getAsString());
            loader.getClass().getMethod("appendToClassPath",java.net.URL.class).invoke(loader,jar.toUri().toURL()); cp.add(jar.toString());
        }
        System.setProperty("java.class.path",String.join(":",cp));
        List<String> game = new ArrayList<>();
        for (JsonElement arg : profile.getAsJsonArray("orbisLoaderArguments")) game.add(arg.getAsString());
        game.addAll(List.of("--username","CompatibilityProbe","--uuid","00000000000000000000000000000000","--accessToken","0","--version",profile.get("id").getAsString(),"--gameDir",gameDir.toString(),"--assetsDir",libraries.getParent().resolve("assets").toString(),"--assetIndex",profile.getAsJsonObject("assetIndex").get("id").getAsString()));
        Thread.currentThread().setContextClassLoader(loader);
        Class<?> main = LoaderLaunch.mainClass(profile.get("mainClass").getAsString());
        Thread.currentThread().setContextClassLoader(main.getClassLoader());
        System.out.println("BOOTSTRAP ENTRY " + main.getName() + " " + main.getModule());
        try { main.getMethod("main",String[].class).invoke(null,(Object)game.toArray(String[]::new)); }
        catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause(); }
    }
}
