package orbis.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import runner.lwjgl.JsonReader;

/** Starts actual upstream bootstrap using the same system-loader shape as the console runner. */
public final class ForgeBootstrap {
    private static Object field(Object object, String key) { return ((Map<?,?>)object).get(key); }
    public static void main(String[] args) throws Throwable {
        Path profileFile = Path.of(args[0]), libraries = Path.of(args[1]), gameDir = Path.of(args[2]);
        Files.createDirectories(gameDir.resolve("config"));
        Files.writeString(gameDir.resolve("config/fml.toml"),"earlyWindowControl=false\n");
        Object profile = JsonReader.parse(profileFile);
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        List<String> cp = new ArrayList<>();
        cp.add(System.getProperty("runner.jar"));
        for (Object element : (List<?>)field(profile,"libraries")) {
            Object artifact = field(field(element,"downloads"),"artifact");
            Path jar = libraries.resolve((String)field(artifact,"path"));
            loader.getClass().getMethod("appendToClassPath",java.net.URL.class).invoke(loader,jar.toUri().toURL()); cp.add(jar.toString());
        }
        System.setProperty("java.class.path",String.join(":",cp));
        List<String> game = new ArrayList<>();
        for (Object arg : (List<?>)field(profile,"orbisLoaderArguments")) game.add((String)arg);
        game.addAll(List.of("--username","CompatibilityProbe","--uuid","00000000000000000000000000000000","--accessToken","0","--version",(String)field(profile,"id"),"--gameDir",gameDir.toString(),"--assetsDir",libraries.getParent().resolve("assets").toString(),"--assetIndex",(String)field(field(profile,"assetIndex"),"id")));
        Thread.currentThread().setContextClassLoader(loader);
        Class<?> main = LoaderLaunch.mainClass((String)field(profile,"mainClass"));
        Thread.currentThread().setContextClassLoader(main.getClassLoader());
        System.out.println("BOOTSTRAP ENTRY " + main.getName() + " " + main.getModule());
        try { main.getMethod("main",String[].class).invoke(null,(Object)game.toArray(String[]::new)); }
        catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause(); }
    }
}
