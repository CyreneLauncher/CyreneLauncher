#!/usr/bin/env python3
"""Build orbis-launcher.jar, the launcher screen and game handoff (graphics-probe/launcher/java).

It is a second Java JAR of the graphics bundle, on the class path after the adapter JAR. It is
compiled against the official LWJGL 3.2.2 JARs and orbis.graphics.NativePlatform, which the
adapter JAR provides at run time, so neither is packaged here.
"""
import argparse
import subprocess
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lwjgl_sources  # noqa: E402

ROOT = lwjgl_sources.ROOT
SOURCES = ROOT / 'launcher/java'

parser = argparse.ArgumentParser()
parser.add_argument('--boot-jdk', default='/usr/lib/jvm/java-17-openjdk-amd64')
parser.add_argument('--jars', default='/tmp/lwjgl-audit', help='official LWJGL 3.2.2 Java JARs')
parser.add_argument('--output', default=str(ROOT / 'build/launcher'))
# Gson ships with Minecraft (one of its libraries), so it is on the class path at run time.
parser.add_argument('--gson', default=str(ROOT / 'build/minecraft-1.18.2/libraries/com/google/code/gson/gson/2.8.9/gson-2.8.9.jar'))
arguments = parser.parse_args()
out = Path(arguments.output).resolve()
classes = out / 'classes'
if classes.exists():
    import shutil
    shutil.rmtree(classes)
classes.mkdir(parents=True)

classpath = ':'.join(str(lwjgl_sources.java_artifact(arguments.jars, name)) for name in
                     ('lwjgl-3.2.2.jar', 'lwjgl-opengl-3.2.2.jar', 'lwjgl-glfw-3.2.2.jar')) + ':' + arguments.gson
own = sorted(SOURCES.rglob('*.java'))
subprocess.run([str(Path(arguments.boot_jdk) / 'bin/javac'), '--release', '17', '-encoding', 'UTF-8',
                '-Xlint:all', '-Werror', '-cp', classpath, '-sourcepath', str(ROOT / 'lwjgl/java'),
                '-implicit:none', '-d', str(classes), *map(str, own)], check=True)
jar = out / 'orbis-launcher.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(classes.rglob('*.class')):
        name = path.relative_to(classes).as_posix()
        if not name.startswith('orbis/launcher/'):
            raise SystemExit(f'unexpected class in the launcher: {name}')
        archive.write(path, name)
print(f'launcher: {jar.name}, {sum(1 for _ in classes.rglob("*.class"))} classes')
