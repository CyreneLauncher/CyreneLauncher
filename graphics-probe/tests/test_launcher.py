#!/usr/bin/env python3
"""Host test of the launcher's input and page logic (tests/launcher/LauncherLogicTest.java)."""
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JDK = Path('/usr/lib/jvm/java-17-openjdk-amd64/bin')
JARS = Path('/tmp/lwjgl-audit')
GSON = ROOT / 'build/minecraft-1.18.2/libraries/com/google/code/gson/gson/2.8.9/gson-2.8.9.jar'


def main():
    classpath = ':'.join(str(JARS / name) for name in (
        'lwjgl-3.2.2.jar', 'lwjgl-opengl-3.2.2.jar', 'lwjgl-glfw-3.2.2.jar', 'lwjgl-3.2.2-natives-linux.jar')) + f':{GSON}'
    sources = sorted((ROOT / 'launcher/java').rglob('*.java')) + sorted((ROOT / 'tests/launcher').rglob('*.java'))
    # The launcher JAR embeds the compatibility mod; Play extracts it from the class path.
    subprocess.run([sys.executable, str(ROOT / 'tools/build_launcher.py')], check=True, stdout=subprocess.DEVNULL)
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / 'orbis/launcher').mkdir(parents=True)
        (Path(tmp) / 'orbis/launcher/orbis-compat.jar').write_bytes((ROOT / 'build/launcher/orbis-compat.jar').read_bytes())
        subprocess.run([str(JDK / 'javac'), '--release', '17', '-Xlint:all', '-Werror', '-encoding', 'UTF-8',
                        '-cp', classpath, '-sourcepath', str(ROOT / 'lwjgl/java'), '-implicit:class',
                        '-d', tmp, *map(str, sources)], check=True)
        subprocess.run([str(JDK / 'java'), '-cp', f'{tmp}:{classpath}', 'orbis.launcher.LauncherLogicTest',
                        *sys.argv[1:]], check=True)


if __name__ == '__main__':
    main()
