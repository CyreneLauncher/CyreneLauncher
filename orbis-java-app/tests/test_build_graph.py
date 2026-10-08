#!/usr/bin/env python3
"""The runner graph builds only the application; CLI changes update settings without stale reuse."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MAKEFILE = ROOT / 'Makefile'


def run_make(*arguments, cwd=ROOT):
    return subprocess.run(['make', '-f', str(MAKEFILE), *arguments], cwd=cwd,
                          text=True, capture_output=True, check=True)


with tempfile.TemporaryDirectory() as temporary:
    build = Path(temporary) / 'runner'
    arguments = [f'BUILD={build}']
    graph = run_make('-n', 'package', *arguments).stdout
    # Java runtimes come from the JDK ports' own exports, never from this build.
    for forbidden in ('build_native_modules.py', 'export_jdk.py', 'jlink', 'jmod',
                      'CONF=', 'libjvm/objs', 'native-inputs', 'module-inputs'):
        assert forbidden not in graph, forbidden
    assert 'runner_config.o' in graph and '-x c++' in graph
    outside = run_make('-n', 'runner', *arguments, cwd=temporary).stdout
    assert str(ROOT / 'launcher.c') in outside
    path = build / 'settings'
    run_make(str(path), *arguments, 'VERSION=first')
    first = path.stat().st_mtime_ns
    run_make(str(path), *arguments, 'VERSION=first')
    assert path.stat().st_mtime_ns == first
    run_make(str(path), *arguments, 'VERSION=second')
    assert path.stat().st_mtime_ns != first
    assert 'second' in path.read_text()
    assert subprocess.run(['make', '-f', str(MAKEFILE), '-n', 'jdk-export', *arguments], cwd=ROOT,
                          capture_output=True).returncode != 0, 'the application has no JDK export target'

print('Runner dependency boundary and changed settings tracking passed')
