#!/usr/bin/env python3
"""Runner graph is independent; CLI changes update settings without stale reuse."""
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
    export = Path(temporary) / 'jdk'
    source = Path(temporary) / 'src/java.base/bsd/native/libnet/bsd_close.c'
    source.parent.mkdir(parents=True)
    source.touch()
    arguments = [f'BUILD={build}', f'EXPORT_BUILD={export}', f'REPO_ROOT={temporary}']
    graph = run_make('-n', 'package', *arguments).stdout
    for forbidden in ('build_native_modules.py', 'export_jdk.py', 'jlink --',
                      'CONF=', 'libjvm/objs', 'native-inputs', 'module-inputs'):
        assert forbidden not in graph, forbidden
    assert 'runner_config.o' in graph and '-x c++' in graph
    outside = run_make('-n', 'runner', *arguments, cwd=temporary).stdout
    assert str(ROOT / 'launcher.c') in outside
    assert 'build_native_modules.py' not in outside
    for directory, setting in ((build, 'VERSION'), (export, 'RUNTIME_MODULES')):
        path = directory / 'settings'
        run_make(str(path), *arguments, f'{setting}=first')
        first = path.stat().st_mtime_ns
        run_make(str(path), *arguments, f'{setting}=first')
        assert path.stat().st_mtime_ns == first
        run_make(str(path), *arguments, f'{setting}=second')
        assert path.stat().st_mtime_ns != first
        assert 'second' in path.read_text()
    release = run_make('-n', 'jdk-export', *arguments,
                       'JDK_CONF=orbis-x86_64-server-release').stdout
    debug = run_make('-n', 'jdk-export', *arguments,
                     'JDK_CONF=orbis-x86_64-server-fastdebug').stdout
    assert '/build/orbis-x86_64-server-release/jdk' in release
    assert '/build/orbis-x86_64-server-fastdebug/jdk' in debug
    assert '--profile' not in release and '--jimage' in release

print('Runner dependency boundary and changed settings tracking passed')
