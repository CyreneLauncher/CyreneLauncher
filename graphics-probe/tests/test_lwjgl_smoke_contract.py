#!/usr/bin/env python3
"""LWJGL smoke test contract.

1. The upload set's runner.conf and jvm-options.txt pass the runner's real C parser.
2. The smoke app runs on the host through the real facade, official LWJGL JARs and the
   stand-in bridges; with a failure injected after init, window and context, it logs the
   stages, the failure, and teardown in reverse acquisition order.
GL capability, pixel and frame stages need Mesa and are left to the console run.
"""
import ctypes
import os
import subprocess
import sys
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
runner_tools = root.parent / 'orbis-java-app/tools'
boot_jdk = Path(os.environ.get('BOOT_JDK', '/usr/lib/jvm/java-17-openjdk-amd64'))
jars = Path(os.environ.get('ORBIS_LWJGL_JARS', '/tmp/lwjgl-audit'))
needed = ['lwjgl-3.2.2.jar', 'lwjgl-opengl-3.2.2.jar', 'lwjgl-glfw-3.2.2.jar',
          'lwjgl-3.2.2-natives-linux.jar', 'lwjgl-opengl-3.2.2-natives-linux.jar']
if not all((jars / jar).is_file() for jar in needed) or not (root / 'build/downloads/lwjgl3-3.2.2.tar.gz').is_file():
    print('LWJGL smoke contract skipped: set ORBIS_LWJGL_JARS and place the pinned LWJGL archive in build/downloads')
    sys.exit(0)


class Config(ctypes.Structure):
    _fields_ = [(name, ctypes.c_char * 512) for name in (
        'java_home', 'jar', 'working_directory', 'output_log', 'temporary_directory', 'desktop_bundle',
        'graphics_bundle', 'version_metadata', 'libraries')] + [('debug', ctypes.c_int), ('display', ctypes.c_int), ('input_diagnostics', ctypes.c_int)]


with tempfile.TemporaryDirectory() as directory:
    tmp = Path(directory)
    smoke = tmp / 'smoke'
    subprocess.run([sys.executable, str(root / 'tools/build_lwjgl_smoke.py'), '--jars', str(jars),
                    '--output', str(smoke)], check=True)

    library = tmp / 'config.so'
    subprocess.run(['cc', '-std=c11', '-shared', '-fPIC', str(runner_tools / 'runner_config.c'), '-o', str(library)],
                   check=True)
    api = ctypes.CDLL(str(library))
    config, error = Config(), ctypes.create_string_buffer(1024)
    assert api.runner_config_load(str(smoke / 'runner/runner.conf').encode(), ctypes.byref(config), error, 1024) == 0, error.value
    assert config.display == 2 and config.debug == 1, 'display=gpu and debug=true'
    assert config.graphics_bundle == b'/data/orbis-jdk/graphics' and config.libraries == b'/data/orbis-jdk/libraries'
    assert config.version_metadata == b'/data/orbis-jdk/runner/version.json'
    for line in (smoke / 'runner/jvm-options.txt').read_text().splitlines():
        if line and not line.startswith('#'):
            assert api.runner_option_reserved(line.encode()) == 0, line

    subprocess.run([sys.executable, str(root / 'tools/build_lwjgl_facade.py'), '--jars', str(jars),
                    '--downloads', str(root / 'build/downloads'), '--output', str(tmp / 'facade')],
                   check=True, stdout=subprocess.DEVNULL)
    fakes = tmp / 'fakes'
    subprocess.run([str(boot_jdk / 'bin/javac'), '--release', '17', '-nowarn', '-cp', str(jars / 'lwjgl-3.2.2.jar'), '-d', str(fakes),
                    str(root / 'tests/facade/orbis/graphics/NativePlatform.java'),
                    str(root / 'tests/facade/runner/gpu/NativeInput.java')], check=True)
    classpath = ':'.join(map(str, [fakes, tmp / 'facade/orbis-lwjgl-3.2.2-adapter.jar',
                                   *(jars / jar for jar in needed if 'glfw' not in jar), smoke / 'runner/target.jar']))

    expected_teardown = {
        'init': ['glfwTerminate'],
        'window': ['glfwDestroyWindow', 'glfwTerminate'],
        'context': ['glfwDestroyWindow', 'glfwTerminate'],
    }
    for stage, teardown in expected_teardown.items():
        run = subprocess.run([str(boot_jdk / 'bin/java'), '-Dorg.lwjgl.opengl.libname=/nonexistent/libGL.so',
                              '-Dorbis.smoke.fail=' + stage, '-cp', classpath, 'orbis.smoke.LwjglSmoke'],
                             text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        lines = [l for l in run.stdout.splitlines() if l.startswith('SMOKE ')]
        assert run.returncode != 0, f'{stage}: injected failure must fail the application'
        assert lines[0].startswith('SMOKE stage=versions result=ok lwjgl=3.2.2'), lines
        assert f'SMOKE stage={stage} result=ok' in '\n'.join(lines), lines
        failure = next(i for i, l in enumerate(lines) if l.startswith('SMOKE stage=run result=fail'))
        assert f'injected failure after stage {stage}' in lines[failure], lines[failure]
        released = [l.split(' ', 3)[3] for l in lines[failure + 1:] if l.startswith('SMOKE stage=teardown result=ok')]
        assert released == teardown + ['released all acquired resources'], f'{stage}: {released}'
        assert lines[-1] == 'SMOKE stage=summary result=fail', lines[-1]
        print(f'smoke failure after {stage}: teardown {teardown}')
print('LWJGL smoke contract passed')
