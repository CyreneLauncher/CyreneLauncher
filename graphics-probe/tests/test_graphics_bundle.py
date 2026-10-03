#!/usr/bin/env python3
"""Package the graphics bundle from existing component builds; the runner's reader must accept it."""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
inputs = [root / 'build/platform/platform-audit.json', root / 'build/lwjgl/lwjgl-audit.json',
          root / 'build/lwjgl-facade/facade-audit.json', root / 'build/lwjgl-optional/optional-audit.json',
          root / 'build/openal/openal-audit.json']
if not all(path.is_file() for path in inputs):
    raise SystemExit('graphics bundle test missing component audits; run make -C graphics-probe graphics-bundle first')
with tempfile.TemporaryDirectory() as directory:
    out = Path(directory) / 'bundle'
    subprocess.run([sys.executable, str(root / 'tools/package_graphics_bundle.py'), '--output', str(out)], check=True)
    manifest = json.loads((out / 'graphics-bundle.json').read_text())
    (profile,) = manifest['profiles']
    for path in profile['java_adapter_paths'] + profile['native_library_paths']:
        assert path in manifest['files'], path
    expected_natives = {f'lib/{name}' for name in ('liborbis_lwjgl_platform.so', 'liblwjgl.so',
                                                  'liblwjgl_opengl.so', 'liblwjgl_stb.so',
                                                  'liblwjgl_tinyfd.so', 'libopenal.so')}
    assert set(profile['native_library_paths']) == expected_natives, profile['native_library_paths']
    assert profile['modules'] == {f'org.lwjgl:{name}': '3.2.2' for name in
                                  ('lwjgl', 'lwjgl-glfw', 'lwjgl-jemalloc', 'lwjgl-openal',
                                   'lwjgl-opengl', 'lwjgl-stb', 'lwjgl-tinyfd')}
    for path in expected_natives:
        assert (out / path).read_bytes()[:4] == b'O\x15=\x1d', path
    import hashlib
    for path, digest in manifest['files'].items():
        assert hashlib.sha256((out / path).read_bytes()).hexdigest() == digest, path
    info = json.loads((out / 'build-info.json').read_text())
    assert info['openal']['openal_soft'] == '1.23.1' and 'libSceAudioOut.so' in info['openal']['needed']
    assert (out / 'legal/openal-soft-COPYING').is_file()
    assert info['stb']['jni_natives'] == 205
    assert sorted(p.relative_to(out).parts[0] for p in out.iterdir()) == \
        ['build-info.json', 'graphics-bundle.json', 'legal', 'lib']
    assert not any(name.startswith(('jdk', 'runner')) for name in manifest['files'])
    for operation in ('glfwInit', 'glfwCreateWindow', 'glfwSwapBuffers', 'glfwPollEvents', 'glfwGetProcAddress'):
        assert operation in profile['glfw_operations'], operation
print('graphics bundle packaging passed')
