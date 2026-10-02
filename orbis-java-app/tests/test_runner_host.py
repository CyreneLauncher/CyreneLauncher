#!/usr/bin/env python3
"""Exercise the generic Java bootstrap with real host JARs."""

from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
boot_jdk = Path(os.environ.get(
    "BOOT_JDK", "/usr/lib/jvm/java-17-openjdk-amd64")) / "bin"
java = os.environ.get("JAVA", str(boot_jdk / "java"))
javac = os.environ.get("JAVAC", str(boot_jdk / "javac"))
jar = os.environ.get("JAR", str(boot_jdk / "jar"))

with tempfile.TemporaryDirectory() as directory:
    tmp = Path(directory)
    app = tmp / "app"
    app.mkdir()
    fixture = tmp / "fixture"
    fixture.mkdir()
    (fixture / "Fixture.java").write_text(
        "public class Fixture { public static void main(String[] a) { "
        "System.out.println(\"FIXTURE_ARGS=\" + String.join(\"|\", a)); } }\n"
    )
    subprocess.run([str(javac), "-d", str(fixture), str(fixture / "Fixture.java")],
                   check=True)
    subprocess.run([str(jar), "--create", "--file", str(app / "target.jar"),
                    "--main-class", "Fixture", "-C", str(fixture), "Fixture.class"],
                   check=True)
    (app / "app-args.txt").write_text("alpha\n# ignored\nvalue with spaces\n\n")
    alternate = tmp / "alternate"
    alternate.mkdir()
    working = tmp / "working"
    working.mkdir()
    configured_jar = alternate / "chosen.jar"
    configured_jar.write_bytes((app / "target.jar").read_bytes())
    configured_log = alternate / "chosen.log"
    classes = tmp / "classes"
    classes.mkdir()
    subprocess.run([str(javac), "--release", "17", "-d", str(classes),
                    str(root / "src/runner/Main.java"),
                    str(root / "src/runner/NativeConsoleOutputStream.java"),
                    str(root / "src/runner/NativeConsoleInputStream.java"),
                    str(root / "src/runner/RunnerSystemClassLoader.java")],
                   check=True)
    completed = subprocess.run([
        str(java), "-Dorbis.runner.root=" + str(app),
        "-Drunner.jar=" + str(app / "target.jar"),
        "-Djava.system.class.loader=runner.RunnerSystemClassLoader",
        "-Dorbis.runner.screen.enabled=false", "-cp", str(classes), "runner.Main"
    ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
    assert "FIXTURE_ARGS=alpha|value with spaces" not in completed.stdout, completed.stdout
    assert "runner: application main returned" not in completed.stdout, completed.stdout
    assert (app / "logs").is_dir(), "runner did not prepare the application logs directory"
    output_log = app / "runner-output.log"
    assert output_log.is_file(), "runner did not persist Java output"
    assert "FIXTURE_ARGS=alpha|value with spaces" in output_log.read_text()

    def run_configured(input_text="", check=True):
        return subprocess.run([
            str(java), "-Dorbis.runner.root=" + str(app),
            "-Drunner.jar=" + str(configured_jar),
            "-Drunner.output.log=" + str(configured_log),
            "-Djava.system.class.loader=runner.RunnerSystemClassLoader",
            "-Dorbis.runner.screen.enabled=false", "-cp", str(classes), "runner.Main"
        ], cwd=working, input=input_text, text=True, stdout=subprocess.PIPE,
           stderr=subprocess.STDOUT, check=check, timeout=20)

    run_configured()
    assert configured_log.is_file(), "configured output log was ignored"
    assert "FIXTURE_ARGS=alpha|value with spaces" in configured_log.read_text()
    (app / "target.jar").unlink()
    run_configured()
    assert "FIXTURE_ARGS=alpha|value with spaces" in configured_log.read_text()

    (fixture / "InputFixture.java").write_text(
        "public class InputFixture { public static void main(String[] a) throws Exception { "
        "System.out.println(\"INPUT=\" + new java.io.BufferedReader("
        "new java.io.InputStreamReader(System.in)).readLine()); "
        "System.out.println(\"INPUT_ARGS=\" + String.join(\"|\", a)); } }\n"
    )
    subprocess.run([str(javac), "--release", "17", "-d", str(fixture),
                    str(fixture / "InputFixture.java")], check=True)
    subprocess.run([str(jar), "--update", "--file", str(configured_jar),
                    "-C", str(fixture), "InputFixture.class"], check=True)
    (app / "main-class.txt").write_text("InputFixture\n")
    (working / "app-args.txt").write_text("wrong arguments\n")
    (working / "main-class.txt").write_text("WrongClass\n")
    completed = run_configured("keyboard input with spaces\n", check=False)
    output = configured_log.read_text()
    assert "INPUT=keyboard input with spaces" in output, output
    assert "INPUT_ARGS=alpha|value with spaces" in output, output
    assert completed.returncode == 0, output
    (app / "main-class.txt").unlink()

    paper = fixture / "io/papermc/paperclip"
    paper.mkdir(parents=True)
    (paper / "Main.java").write_text(
        "package io.papermc.paperclip; public class Main { "
        "public static void main(String[] a) { "
        "System.out.println(\"GENERIC_ARGS=\" + String.join(\"|\", a)); } }\n"
    )
    subprocess.run([str(javac), "--release", "17", "-d", str(fixture),
                    str(paper / "Main.java")], check=True)
    subprocess.run([str(jar), "--update", "--file", str(configured_jar),
                    "-C", str(fixture), "io/papermc/paperclip/Main.class"], check=True)
    (app / "main-class.txt").write_text("io.papermc.paperclip.Main\n")
    run_configured()
    output = configured_log.read_text()
    assert "GENERIC_ARGS=alpha|value with spaces\n" in output, output
    (app / "main-class.txt").unlink()

    # A manifest dependency is resolved relative to the configured JAR.
    (fixture / "Dependency.java").write_text(
        "public class Dependency { public static String value() { return \"dependency works\"; } }\n")
    (fixture / "ClasspathFixture.java").write_text(
        "public class ClasspathFixture { public static void main(String[] a) { "
        "System.out.println(Dependency.value()); } }\n")
    subprocess.run([str(javac), "--release", "17", "-d", str(fixture),
                    str(fixture / "Dependency.java"),
                    str(fixture / "ClasspathFixture.java")], check=True)
    subprocess.run([str(jar), "--create", "--file", str(alternate / "dependency.jar"),
                    "-C", str(fixture), "Dependency.class"], check=True)
    manifest = tmp / "manifest.mf"
    manifest.write_text("Manifest-Version: 1.0\nMain-Class: ClasspathFixture\nClass-Path: dependency.jar\n\n")
    subprocess.run([str(jar), "--create", "--file", str(configured_jar),
                    "--manifest", str(manifest), "-C", str(fixture),
                    "ClasspathFixture.class"], check=True)
    run_configured()
    assert "dependency works" in configured_log.read_text()

    # Non-daemon application threads retain classes/resources after main returns.
    (fixture / "AsyncFixture.java").write_text('''
import java.nio.file.*;
public class AsyncFixture {
    public static void main(String[] args) {
        new Thread(() -> {
            try {
                Path log = Path.of(System.getProperty("runner.output.log"));
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (!Files.readString(log).contains("application main returned")) {
                    if (System.nanoTime() >= deadline) throw new AssertionError("main did not return");
                    Thread.sleep(10);
                }
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                Class<?> dependency = Class.forName("LateDependency", true, loader);
                System.out.println("LATE_CLASS=" + dependency.getMethod("value").invoke(null));
                try (var stream = loader.getResourceAsStream("late.txt")) {
                    if (stream == null) throw new AssertionError("late resource missing");
                    System.out.println("LATE_RESOURCE=" + new String(stream.readAllBytes()));
                }
            } catch (Throwable failure) {
                System.err.println("BACKGROUND_ERROR=" + failure);
            }
        }, "fixture worker").start();
    }
}
''')
    (fixture / "LateDependency.java").write_text(
        'public class LateDependency { public static String value() { return "loaded"; } }\n')
    (fixture / "late.txt").write_text("available")
    subprocess.run([str(javac), "--release", "17", "-d", str(fixture),
                    str(fixture / "AsyncFixture.java"),
                    str(fixture / "LateDependency.java")], check=True)
    subprocess.run([str(jar), "--create", "--file", str(alternate / "late-dependency.jar"),
                    "-C", str(fixture), "LateDependency.class"], check=True)
    manifest.write_text("Manifest-Version: 1.0\nMain-Class: AsyncFixture\nClass-Path: late-dependency.jar\n\n")
    subprocess.run([str(jar), "--create", "--file", str(configured_jar),
                    "--manifest", str(manifest), "-C", str(fixture),
                    "AsyncFixture.class", "-C", str(fixture), "late.txt"], check=True)
    run_configured()
    output = configured_log.read_text()
    assert "LATE_CLASS=loaded" in output, output
    assert "LATE_RESOURCE=available" in output, output
    assert "BACKGROUND_ERROR=" not in output, output

    (fixture / "ThrowFixture.java").write_text(
        "public class ThrowFixture { public static void main(String[] a) { "
        "throw new IllegalStateException(\"fixture failure\"); } }\n")
    subprocess.run([str(javac), "--release", "17", "-d", str(fixture),
                    str(fixture / "ThrowFixture.java")], check=True)
    subprocess.run([str(jar), "--create", "--file", str(configured_jar),
                    "--main-class", "ThrowFixture", "-C", str(fixture),
                    "ThrowFixture.class"], check=True)
    completed = run_configured(check=False)
    assert completed.returncode != 0
    output = configured_log.read_text()
    assert "runner: application threw java.lang.IllegalStateException: fixture failure" in output
    assert "at ThrowFixture.main" in output

    (fixture / "LargeFixture.java").write_text(
        "public class LargeFixture { public static void main(String[] a) { "
        "System.out.println(\"x\".repeat(300000)); } }\n"
    )
    subprocess.run([str(javac), "-d", str(fixture),
                    str(fixture / "LargeFixture.java")], check=True)
    subprocess.run([str(jar), "--create", "--file", str(app / "target.jar"),
                    "--main-class", "LargeFixture", "-C", str(fixture),
                    "LargeFixture.class"], check=True)
    subprocess.run([
        str(java), "-Dorbis.runner.root=" + str(app),
        "-Drunner.jar=" + str(app / "target.jar"),
        "-Djava.system.class.loader=runner.RunnerSystemClassLoader",
        "-Dorbis.runner.screen.enabled=false", "-cp", str(classes), "runner.Main"
    ], stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, check=True)
    output = output_log.read_bytes()
    assert len(output) <= 256 * 1024 + 64
    assert b"runner: output log truncated at 256 KiB" in output

print("Runner host smoke test passed")
