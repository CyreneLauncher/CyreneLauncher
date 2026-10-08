#!/usr/bin/env python3
"""Orbis LWJGL facade: build with its binary contract, then run GLFW behavior on the host.

The host run uses the real facade JAR with the official LWJGL core/OpenGL Java and
Linux native JARs, and pure-Java stand-ins for orbis.graphics.NativePlatform and
runner.gpu.NativeInput (checked here against the production classes). The GL library
name points nowhere, so any attempt by GL to load libGL itself fails the run.
ORBIS_LWJGL_RELEASE=3.3.3 checks the 3.3.3 adapter instead of 3.2.2's.
"""
import os
import subprocess
import sys
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'tools'))
import classfile  # noqa: E402

boot_jdk = Path(os.environ.get('BOOT_JDK', '/usr/lib/jvm/java-17-openjdk-amd64'))
jars = Path(os.environ.get('ORBIS_LWJGL_JARS', '/tmp/lwjgl-audit'))
release = os.environ.get('ORBIS_LWJGL_RELEASE', '3.2.2')
downloads = root / 'build/downloads'
needed = [f'lwjgl-{release}.jar', f'lwjgl-opengl-{release}.jar', f'lwjgl-glfw-{release}.jar',
          f'lwjgl-{release}-natives-linux.jar', f'lwjgl-opengl-{release}-natives-linux.jar']
if not all((jars / jar).is_file() for jar in needed) or not (downloads / f'lwjgl3-{release}.tar.gz').is_file():
    print('LWJGL facade test skipped: set ORBIS_LWJGL_JARS and place the pinned LWJGL archive in build/downloads')
    sys.exit(0)


def javac(*args):
    subprocess.run([str(boot_jdk / 'bin/javac'), '--release', '17', '-nowarn', *map(str, args)], check=True)


with tempfile.TemporaryDirectory() as directory:
    tmp = Path(directory)
    subprocess.run([sys.executable, str(root / 'tools/build_lwjgl_facade.py'), '--boot-jdk', str(boot_jdk),
                    '--release', release, '--jars', str(jars), '--downloads', str(downloads), '--output', str(tmp / 'facade')], check=True)
    facade = tmp / f'facade/orbis-lwjgl-{release}-adapter.jar'

    # Stand-ins must expose exactly what the production classes do.
    real = tmp / 'real'
    javac('-d', real, root.parent / 'orbis-java-app/src/runner/gpu/NativeInput.java',
          root / 'lwjgl/java/orbis/graphics/NativePlatform.java')
    fakes = tmp / 'fakes'
    javac('-d', fakes, root / 'tests/facade/orbis/graphics/NativePlatform.java',
          root / 'tests/facade/runner/gpu/NativeInput.java')
    for name in ('runner/gpu/NativeInput.class', 'orbis/graphics/NativePlatform.class'):
        production = classfile.parse((real / name).read_bytes())
        stand_in = classfile.parse((fakes / name).read_bytes())
        constants = {(f.name, f.descriptor, f.constant) for f in production.fields if f.exported}
        assert constants <= {(f.name, f.descriptor, f.constant) for f in stand_in.fields}, name + ' constants'
        methods = {(m.name, m.descriptor) for m in production.methods
                   if m.exported and m.access & classfile.ACC_STATIC}
        assert methods <= {(m.name, m.descriptor) for m in stand_in.methods}, name + ' methods'

    lwjgl = [jars / jar for jar in needed if 'glfw' not in jar]
    classpath = ':'.join(map(str, [fakes, facade, *lwjgl]))
    tests = tmp / 'tests'
    javac('-cp', classpath, '-d', tests, root / 'tests/facade/FacadeBehaviorTest.java')
    subprocess.run([str(boot_jdk / 'bin/java'), '-ea', '-Dorg.lwjgl.opengl.libname=/nonexistent/libGL.so',
                    '-Dorg.lwjgl.util.NoChecks=false', '-cp', f'{tests}:{classpath}', 'FacadeBehaviorTest'],
                   check=True)
