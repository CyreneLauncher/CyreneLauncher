"""First-start settings are exclusive; installed libraries follow the selected JDK."""
import ctypes
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class Config(ctypes.Structure):
    _fields_ = [(name, ctypes.c_char * 512) for name in (
        'java_home', 'jar', 'working_directory', 'output_log', 'temporary_directory',
        'desktop_bundle', 'graphics_bundle', 'version_metadata', 'libraries')]
    _fields_ += [('debug', ctypes.c_int), ('display', ctypes.c_int), ('input_diagnostics', ctypes.c_int)]


with tempfile.TemporaryDirectory() as directory:
    tmp = Path(directory)
    library = tmp / 'config.so'
    subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror', '-shared', '-fPIC',
                    str(ROOT / 'tools/runner_config.c'), '-o', str(library)], check=True)
    api = ctypes.CDLL(str(library))
    error = ctypes.create_string_buffer(1024)
    api.runner_config_seed_defaults.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_size_t]
    defaults, writable = tmp / 'defaults', tmp / 'data/runner'
    defaults.mkdir()
    (defaults / 'runner.conf').write_bytes(b'display=gpu\n')
    (defaults / 'jvm-options.txt').write_bytes(b'-Xmx1024m\n')
    assert api.runner_config_seed_defaults(str(defaults).encode(), str(writable).encode(), error, len(error)) == 0, error.value
    assert (writable / 'runner.conf').read_bytes() == b'display=gpu\n'
    (writable / 'runner.conf').write_bytes(b'user configuration\n')
    assert api.runner_config_seed_defaults(str(defaults).encode(), str(writable).encode(), error, len(error)) == 0
    assert (writable / 'runner.conf').read_bytes() == b'user configuration\n'
    (defaults / 'jvm-options.txt').unlink()
    (writable / 'jvm-options.txt').unlink()
    assert api.runner_config_seed_defaults(str(defaults).encode(), str(writable).encode(), error, len(error)) < 0
    assert not (writable / 'jvm-options.txt').exists(), 'failed copy left a partial settings file'
    for version in (17, 21, 25):
        config = Config()
        config.java_home = f'/data/orbis-jdk/jdk{version}/release-desktop/'.encode()
        config.graphics_bundle = b'/data/orbis-jdk/graphics'
        assert api.runner_config_installed(ctypes.byref(config), error, len(error)) == 0, error.value
        home = f'/app0/runtimes/jdk{version}'.encode()
        assert config.java_home == home
        assert config.graphics_bundle == home + b'/lib/orbis/graphics'
        assert config.desktop_bundle == home + b'/lib/orbis/desktop'
    config.java_home = b'/unknown/runtime'
    assert api.runner_config_installed(ctypes.byref(config), error, len(error)) < 0
# The SDK's musl lstat/fstatat reach a syscall Orbis lacks; first start then failed with ENOSYS.
import re
source = (ROOT / 'tools/runner_config.c').read_text()
assert not re.search(r'\b(lstat|fstatat|stat)\s*\(', source), 'runner_config.c must not use the stat family on Orbis'
print('Installed runtime selection and exclusive first-start defaults passed')
