#!/usr/bin/env python3
"""Check installed launcher roots and, optionally, the real packaged bootstrap profile."""
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path('/usr/lib/jvm/java-17-openjdk-amd64/bin')

SOURCE = r'''
import java.nio.file.Path;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import runner.gpu.GpuLaunch;
import runner.lwjgl.*;
public class InstalledLibraries {
    public static void main(String[] args) throws Exception {
        Path data = Path.of("/data/orbis-jdk/libraries");
        for (int feature : new int[]{17, 21, 25}) {
            Path home = Path.of("/app0/runtimes/jdk" + feature);
            if (!GpuLaunch.libraryRoot(Path.of("/app0/manifests/launcher.json"), data, home)
                    .equals(home.resolve("lib/orbis/libraries"))) throw new AssertionError("installed launcher libraries");
            if (!GpuLaunch.libraryRoot(Path.of("/data/orbis-jdk/launcher/versions/game/boot.json"), data, home)
                    .equals(data)) throw new AssertionError("downloaded game libraries");
            if (!GpuLaunch.libraryRoot(Path.of("/data/launcher.json"), data, home).equals(data))
                throw new AssertionError("same file name must not select installed dependencies");
        }
        if (args.length > 0) {
            Path stage = Path.of(args[0]);
            {
                // The launcher runs on the packaged JDK 17; the graphics bundle sits once beside it.
                Path home = stage.resolve("runtimes/jdk17");
                ResolvedLaunchPlan plan = VersionMetadataResolver.resolve(stage.resolve("manifests/launcher.json"),
                        home.resolve("lib/orbis/libraries"), MetadataPlatform.ORBIS);
                if (!plan.mainClass().equals("orbis.launcher.Launcher")) throw new AssertionError("bootstrap main");
                GraphicsBundle bundle = LwjglProfileRegistry.select(plan, stage.resolve("orbis/graphics"));
                ArrayList<URL> urls = new ArrayList<>();
                for (Path jar : bundle.javaAdapters()) urls.add(jar.toUri().toURL());
                for (Path jar : plan.classpath()) urls.add(jar.toUri().toURL());
                try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new))) {
                    Class.forName("orbis.launcher.Launcher", false, loader);
                    Class.forName("com.google.gson.JsonObject", false, loader);
                    Class.forName("org.lwjgl.Version", false, loader);
                }
            }
        }
        System.out.println("Installed launcher dependencies and downloaded game roots passed");
    }
}
'''

with tempfile.TemporaryDirectory() as directory:
    tmp = Path(directory)
    source = tmp / 'InstalledLibraries.java'
    source.write_text(SOURCE)
    sources = sorted((ROOT / 'src/runner').rglob('*.java'))
    subprocess.run([str(JAVA / 'javac'), '--release', '17', '-d', str(tmp),
                    *map(str, sources), str(source)], check=True)
    subprocess.run([str(JAVA / 'java'), '-cp', str(tmp), 'InstalledLibraries', *sys.argv[1:]], check=True)
