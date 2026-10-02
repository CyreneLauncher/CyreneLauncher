#!/usr/bin/env python3
"""Package the graphics bundle from existing component builds; the runner's reader must accept it."""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
inputs = [root / 'build/platform/platform-audit.json', root / 'build/lwjgl/lwjgl-audit.json',
          root / 'build/lwjgl-facade/facade-audit.json']
if not all(path.is_file() for path in inputs):
    print('graphics bundle test skipped: run make -C graphics-probe graphics-bundle first')
    sys.exit(0)
with tempfile.TemporaryDirectory() as directory:
    out = Path(directory) / 'bundle'
    subprocess.run([sys.executable, str(root / 'tools/package_graphics_bundle.py'), '--output', str(out)], check=True)
    manifest = json.loads((out / 'graphics-bundle.json').read_text())
    (profile,) = manifest['profiles']
    for path in profile['java_adapter_paths'] + profile['native_library_paths']:
        assert path in manifest['files'], path
    assert sorted(p.relative_to(out).parts[0] for p in out.iterdir()) == \
        ['build-info.json', 'graphics-bundle.json', 'legal', 'lib']
    assert not any(name.startswith(('jdk', 'runner')) for name in manifest['files'])
    for operation in ('glfwInit', 'glfwCreateWindow', 'glfwSwapBuffers', 'glfwPollEvents', 'glfwGetProcAddress'):
        assert operation in profile['glfw_operations'], operation
print('graphics bundle packaging passed')
