#!/usr/bin/env python3
"""Package the graphics bundle from existing component builds; the runner's reader must accept it."""
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
inputs = [root / 'build/platform/platform-audit.json', root / 'build/lwjgl/lwjgl-audit.json',
          root / 'build/lwjgl-facade/facade-audit.json', root / 'build/lwjgl-optional/optional-audit.json',
          root / 'build/openal/openal-audit.json', root / 'build/lwjgl-3.3.3/lwjgl-audit.json',
          root / 'build/lwjgl-facade-3.3.3/facade-audit.json', root / 'build/freetype/freetype-audit.json']
if not all(path.is_file() for path in inputs):
    raise SystemExit('graphics bundle test missing component audits; run make -C graphics-probe graphics-bundle first')
with tempfile.TemporaryDirectory() as directory:
    out = Path(directory) / 'bundle'
    subprocess.run([sys.executable, str(root / 'tools/package_graphics_bundle.py'), '--output', str(out),
                    *(['--platform', os.environ['ORBIS_PLATFORM_BUILD']] if 'ORBIS_PLATFORM_BUILD' in os.environ else []),
                    *(['--facade-sdl', os.environ['ORBIS_FACADE_BUILD']] if 'ORBIS_FACADE_BUILD' in os.environ else []),
                    *(['--vma-sdl', os.environ['ORBIS_VMA_BUILD']] if 'ORBIS_VMA_BUILD' in os.environ else [])], check=True)
    if 'ORBIS_VMA_BUILD' in os.environ:
        rejected = subprocess.run([sys.executable, str(root / 'tools/package_graphics_bundle.py'),
                                   '--vma-sdl', os.environ['ORBIS_VMA_BUILD'],
                                   '--platform', str(root / 'build/platform-radeonsi'),
                                   '--output', str(Path(directory) / 'rejected')],
                                  text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        assert rejected.returncode != 0 and 'verified Mesa build' in rejected.stdout, rejected.stdout
    manifest = json.loads((out / 'graphics-bundle.json').read_text())
    profile, modern, current, next_profile, sdl = manifest['profiles']
    for entry in manifest['profiles']:
        for name in entry['java_adapter_paths'] + entry['native_library_paths']:
            assert name in manifest['files'], name
    if 'ORBIS_VMA_BUILD' in os.environ:
        assert 'lib/lwjgl-3.4.3/liblwjgl_vma.so' in sdl['native_library_paths']
        assert not any('liblwjgl_vma.so' in name for entry in manifest['profiles'][:-1]
                       for name in entry['native_library_paths'])
        assert (out / 'legal/vma-LICENSE.txt').is_file()
    for path in (profile['java_adapter_paths'] + profile['native_library_paths']
                 + modern['java_adapter_paths'] + modern['native_library_paths']):
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
    # 1.20.5+: LWJGL 3.3.3 natives in their own directory (the first one names org.lwjgl.librarypath),
    # OpenAL and the platform module shared from lib/ through java.library.path.
    assert modern['id'] == 'minecraft-1.20.5-lwjgl-3.3.3-orbis-v1'
    assert modern['modules'] == {f'org.lwjgl:{name}': '3.3.3' for name in
                                 ('lwjgl', 'lwjgl-freetype', 'lwjgl-glfw', 'lwjgl-jemalloc', 'lwjgl-openal',
                                  'lwjgl-opengl', 'lwjgl-stb', 'lwjgl-tinyfd')}
    assert modern['native_library_paths'][0] == 'lib/lwjgl-3.3.3/liblwjgl.so'
    assert set(modern['native_library_paths']) == {f'lib/lwjgl-3.3.3/{name}' for name in (
        'liblwjgl.so', 'liblwjgl_opengl.so', 'liblwjgl_stb.so', 'liblwjgl_tinyfd.so', 'libfreetype.so')} | {
        'lib/libopenal.so', 'lib/liborbis_lwjgl_platform.so'}
    assert modern['java_adapter_paths'][0] == 'lib/orbis-lwjgl-3.3.3-adapter.jar'
    for path in modern['native_library_paths']:
        assert (out / path).read_bytes()[:4] == b'O\x15=\x1d', path
    import hashlib
    for path, digest in manifest['files'].items():
        assert hashlib.sha256((out / path).read_bytes()).hexdigest() == digest, path
    info = json.loads((out / 'build-info.json').read_text())
    assert info['openal']['openal_soft'] == '1.23.1' and 'libSceAudioOut.so' in info['openal']['needed']
    assert (out / 'legal/openal-soft-COPYING').is_file()
    assert info['stb']['jni_natives'] == 205
    assert info['lwjgl_3.3.3']['liblwjgl_stb']['jni_natives'] == 215
    assert info['freetype']['version'] == '2.13.2' and info['freetype']['lwjgl_required_functions'] == 210
    for name in ('libffi-LICENSE', 'freetype-LICENSE.TXT', 'freetype-FTL.TXT', 'lwjgl-3.3.3-LICENSE.md'):
        assert (out / 'legal' / name).is_file(), name
    assert sorted(p.relative_to(out).parts[0] for p in out.iterdir()) == \
        ['build-info.json', 'graphics-bundle.json', 'legal', 'lib']
    assert not any(name.startswith(('jdk', 'runner')) for name in manifest['files'])
    for operation in ('glfwInit', 'glfwCreateWindow', 'glfwSwapBuffers', 'glfwPollEvents', 'glfwGetProcAddress'):
        assert operation in profile['glfw_operations'] and operation in modern['glfw_operations'], operation
print('graphics bundle packaging passed')
