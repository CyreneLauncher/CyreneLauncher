package orbis.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Catches invalid patch publication, server-side execution and processor classloader leakage. */
public final class InstallerProcessorsTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("processor-test");
        Path libs = root.resolve("libs");
        Files.createDirectories(libs.resolve("test/processor/1"));
        Path src = root.resolve("Processor.java");
        Files.writeString(src, "public class Processor { public static void main(String[] args) throws Exception { if(args[0].equals(\"fail\")) throw new IllegalStateException(\"processor failure\"); java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]),\"patched\"); } }");
        ForgeProfilesTest.check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",root.toString(),src.toString()) == 0,"fixture compiles");
        Manifest mf = new Manifest(); mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0"); mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS,"Processor");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(libs.resolve("test/processor/1/processor-1.jar")),mf)) {
            jar.putNextEntry(new JarEntry("Processor.class")); jar.write(Files.readAllBytes(root.resolve("Processor.class"))); jar.closeEntry();
        }
        JsonObject profile = JsonParser.parseString("{\"processors\":[{\"sides\":[\"server\"],\"jar\":\"missing:server:1\",\"args\":[]},{\"jar\":\"test:processor:1\",\"classpath\":[],\"args\":[\"{OUT}\"],\"outputs\":{\"{OUT}\":\"{HASH}\"}}]}").getAsJsonObject();
        Path out = libs.resolve("patched.jar");
        Map<String,String> data = Map.of("OUT",out.toString(),"HASH","cea8b75f1ccb56cf8d11f004254622abb8cbcdb2");
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        InstallerProcessors.run(profile,libs,data,new InstanceStore.Operation());
        ForgeProfilesTest.check(Files.readString(out).equals("patched"),"real processor output");
        ForgeProfilesTest.check(Thread.currentThread().getContextClassLoader() == before,"context restored");
        profile.getAsJsonArray("processors").get(1).getAsJsonObject().getAsJsonArray("args").set(0,new com.google.gson.JsonPrimitive("fail"));
        InstallerProcessors.run(profile,libs,data,new InstanceStore.Operation()); // Verified output skips re-execution.
        profile.getAsJsonArray("processors").get(1).getAsJsonObject().getAsJsonArray("args").set(0,new com.google.gson.JsonPrimitive("{OUT}"));
        ForgeProfilesTest.fails(() -> InstallerProcessors.run(profile,libs,Map.of("OUT",out.toString(),"HASH","0".repeat(40)),new InstanceStore.Operation()));
        JsonObject failing = JsonParser.parseString("{\"processors\":[{\"jar\":\"test:processor:1\",\"classpath\":[],\"args\":[\"fail\"]}]}").getAsJsonObject();
        ForgeProfilesTest.fails(() -> InstallerProcessors.run(failing,libs,Map.of(),new InstanceStore.Operation()));
        ForgeProfilesTest.check(Thread.currentThread().getContextClassLoader() == before,"context restored on failure");
        ForgeProfilesTest.fails(() -> InstallerProcessors.resolve("{UNKNOWN}",data,libs));
        ForgeProfilesTest.fails(() -> InstallerProcessors.resolve("[a:../escape:1]",data,libs));
        ForgeProfilesTest.fails(() -> InstallerProcessors.run(profile,libs,Map.of("OUT",root.resolve("escape.jar").toString(),"HASH","0".repeat(40)),new InstanceStore.Operation()));
        Path archive = root.resolve("oversized.zip");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("data.txt")); zip.write(new byte[101]); zip.closeEntry();
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
            ForgeProfilesTest.fails(() -> ForgeInstaller.resource(zip,"data.txt",100));
            ForgeProfilesTest.fails(() -> ForgeInstaller.resource(zip,"absent",100));
        }
        ForgeProfilesTest.fails(() -> ForgeInstaller.confined(root,"../escape"));
        // A Java-17 processor can encounter a Java-21 dependency only after entering main.
        Files.writeString(src, "public class Processor { public static void main(String[] args) throws Exception { Class.forName(\"Helper\"); } }");
        Path helper = root.resolve("Helper.java"); Files.writeString(helper,"public class Helper { }");
        ForgeProfilesTest.check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","17","-d",root.toString(),src.toString(),helper.toString()) == 0,"dependency fixture compiles");
        byte[] newer = Files.readAllBytes(root.resolve("Helper.class")); newer[6]=0; newer[7]=65;
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(libs.resolve("test/processor/1/processor-1.jar")),mf)) {
            jar.putNextEntry(new JarEntry("Processor.class")); jar.write(Files.readAllBytes(root.resolve("Processor.class"))); jar.closeEntry();
            jar.putNextEntry(new JarEntry("Helper.class")); jar.write(newer); jar.closeEntry();
        }
        try { InstallerProcessors.run(failing,libs,Map.of(),new InstanceStore.Operation()); throw new AssertionError("dependency requires newer JVM"); }
        catch (InstallerProcessors.RuntimeRequired required) { ForgeProfilesTest.check(required.java == 21,"dependency continuation Java 21"); }
        ForgeProfilesTest.check(Thread.currentThread().getContextClassLoader() == before,"context restored on newer dependency");
        System.out.println("Installer processor tests passed");
    }
}
