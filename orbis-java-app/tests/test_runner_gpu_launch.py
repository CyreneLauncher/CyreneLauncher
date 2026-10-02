#!/usr/bin/env python3
"""runner.Main in display=gpu mode on the host: metadata, profile gate, bundle and class path.

Uses the official LWJGL 3.2.2 Java JARs (ORBIS_LWJGL_JARS) and a fixture bundle whose
adapter JAR carries a marker org.lwjgl.system.Platform. The application must see the adapter
ahead of lwjgl-3.2.2.jar, must not see lwjgl-glfw-3.2.2.jar, and must get the LWJGL properties.
Tampered libraries, an unsupported LWJGL set and a broken bundle must fail before main.
"""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
graphics = root.parent / 'graphics-probe'
bin_dir = Path(os.environ.get('BOOT_JDK', '/usr/lib/jvm/java-17-openjdk-amd64')) / 'bin'
jars = Path(os.environ.get('ORBIS_LWJGL_JARS', '/tmp/lwjgl-audit'))
if not (jars / 'lwjgl-3.2.2.jar').is_file():
    print('GPU launch host test skipped: set ORBIS_LWJGL_JARS to the official LWJGL 3.2.2 JARs')
    sys.exit(0)


def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


APP = '''package gpuapp;
public final class Main {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        String platform = Class.forName("org.lwjgl.system.Platform", false, loader)
                .getProtectionDomain().getCodeSource().getLocation().getPath();
        String version = Class.forName("org.lwjgl.Version", false, loader)
                .getProtectionDomain().getCodeSource().getLocation().getPath();
        boolean glfwVisible;
        try { Class.forName("org.lwjgl.glfw.GLFW", false, loader); glfwVisible = true; }
        catch (ClassNotFoundException expected) { glfwVisible = false; }
        System.out.println("GPU_APP platform=" + platform + " version=" + version + " glfw=" + glfwVisible
                + " librarypath=" + System.getProperty("org.lwjgl.librarypath")
                + " explicit=" + System.getProperty("org.lwjgl.opengl.explicitInit")
                + " allocator=" + System.getProperty("org.lwjgl.system.allocator"));
    }
}
'''
MARKER = 'package org.lwjgl.system;\npublic final class Platform { public static final String ORBIS_MARKER = "adapter"; }\n'

with tempfile.TemporaryDirectory() as directory:
    tmp = Path(directory)
    app_root = tmp / 'app'
    app_root.mkdir()
    classes = tmp / 'runner-classes'
    run(bin_dir / 'javac', '--release', '17', '-d', classes, *sorted((root / 'src/runner').rglob('*.java')))

    # Target JAR without a Main-Class: the metadata mainClass must be used.
    (tmp / 'app-src/gpuapp').mkdir(parents=True)
    (tmp / 'app-src/gpuapp/Main.java').write_text(APP)
    run(bin_dir / 'javac', '-d', tmp / 'app-classes', tmp / 'app-src/gpuapp/Main.java')
    run(bin_dir / 'jar', '--create', '--file', app_root / 'target.jar', '-C', tmp / 'app-classes', '.')

    libraries = tmp / 'libraries'
    metadata = tmp / 'version.json'
    run(sys.executable, graphics / 'tools/make_lwjgl_metadata.py', '--jars', jars, '--id', 'gpu-host-test',
        '--main-class', 'gpuapp.Main', '--output', metadata, '--libraries', libraries)

    bundle = tmp / 'graphics'
    (tmp / 'marker/org/lwjgl/system').mkdir(parents=True)
    (tmp / 'marker/org/lwjgl/system/Platform.java').write_text(MARKER)
    run(bin_dir / 'javac', '-d', tmp / 'marker-classes', tmp / 'marker/org/lwjgl/system/Platform.java')
    (bundle / 'lib').mkdir(parents=True)
    run(bin_dir / 'jar', '--create', '--file', bundle / 'lib/adapter.jar', '-C', tmp / 'marker-classes', '.')
    natives = ['lib/liborbis_lwjgl_platform.so', 'lib/liblwjgl.so', 'lib/liblwjgl_opengl.so']
    for name in natives:
        (bundle / name).write_bytes(b'fixture ' + name.encode())

    def write_manifest():
        files = {p.relative_to(bundle).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in sorted(bundle.rglob('*')) if p.is_file() and p.name != 'graphics-bundle.json'}
        (bundle / 'graphics-bundle.json').write_text(json.dumps({'schema_version': 1, 'files': files, 'profiles': [{
            'id': 'minecraft-1.18.2-lwjgl-3.2.2-orbis-v1',
            'modules': {f'org.lwjgl:{m}': '3.2.2' for m in ('lwjgl', 'lwjgl-glfw', 'lwjgl-jemalloc', 'lwjgl-openal',
                                                            'lwjgl-opengl', 'lwjgl-stb', 'lwjgl-tinyfd')},
            'java_adapter_paths': ['lib/adapter.jar'], 'native_library_paths': natives,
            'glfw_operations': ['glfwInit'], 'gl_features': ['egl-desktop-opengl']}]}))
    write_manifest()

    def launch(metadata_path=metadata):
        log = app_root / 'runner-output.log'
        if log.exists():
            log.unlink()
        completed = subprocess.run([
            str(bin_dir / 'java'), '-Dorbis.runner.root=' + str(app_root), '-Drunner.jar=' + str(app_root / 'target.jar'),
            '-Djava.system.class.loader=runner.RunnerSystemClassLoader', '-Drunner.display=gpu',
            '-Drunner.graphics.bundle=' + str(bundle), '-Drunner.gpu.metadata=' + str(metadata_path),
            '-Drunner.gpu.libraries=' + str(libraries), '-Dorbis.runner.screen.enabled=false',
            '-cp', str(classes), 'runner.Main'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        return completed.returncode, log.read_text() if log.exists() else completed.stdout

    status, output = launch()
    assert status == 0, output
    line = next(l for l in output.splitlines() if l.startswith('GPU_APP '))
    assert f'platform={bundle.resolve()}/lib/adapter.jar' in line, line
    assert 'version=' + str(libraries.resolve() / 'org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2.jar') in line, line
    assert ' glfw=false ' in line, 'lwjgl-glfw-3.2.2.jar must be replaced: ' + line
    assert f'librarypath={bundle.resolve()}/lib explicit=true allocator=system' in line, line
    assert 'runner: gpu profile=minecraft-1.18.2-lwjgl-3.2.2-orbis-v1' in output, output

    # Failures stop before the application with their category.
    tampered = libraries / 'org/lwjgl/lwjgl-stb/3.2.2/lwjgl-stb-3.2.2.jar'
    original = tampered.read_bytes()
    tampered.write_bytes(original[:-1] + bytes([original[-1] ^ 1]))
    status, output = launch()
    assert status != 0 and 'ARTIFACT_MISMATCH' in output and 'GPU_APP' not in output, output
    tampered.write_bytes(original)

    reduced = json.loads(metadata.read_text())
    reduced['libraries'] = [l for l in reduced['libraries'] if 'tinyfd' not in l['name']]
    (tmp / 'reduced.json').write_text(json.dumps(reduced))
    status, output = launch(tmp / 'reduced.json')
    assert status != 0 and 'PROFILE_UNSUPPORTED' in output and 'GPU_APP' not in output, output

    (bundle / 'lib/liblwjgl.so').write_bytes(b'changed')
    status, output = launch()
    assert status != 0 and 'BUNDLE_INVALID' in output and 'GPU_APP' not in output, output
    write_manifest()
    status, output = launch()
    assert status == 0, output
print('Runner GPU launch host behavior passed')
