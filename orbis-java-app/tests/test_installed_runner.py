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
        # JDK 17 is packaged; 21 and 25 are downloaded. Earlier packages' paths move onto these.
        home = (b'/app0/runtimes/jdk17' if version == 17 else f'/data/orbis-jdk/runtimes/jdk{version}'.encode())
        for configured in (f'/data/orbis-jdk/jdk{version}/release-desktop/', f'/app0/runtimes/jdk{version}',
                           f'/data/orbis-jdk/runtimes/jdk{version}'):
            config = Config()
            config.java_home = configured.encode()
            config.graphics_bundle = b'/data/orbis-jdk/graphics'
            config.jar = f'/app0/runtimes/jdk{version}/lib/orbis/graphics/lib/orbis-launcher.jar'.encode()
            assert api.runner_config_installed(ctypes.byref(config), error, len(error)) == 0, error.value
            assert config.java_home == home, (configured, config.java_home)
            assert config.graphics_bundle == b'/app0/orbis/graphics'
            assert config.desktop_bundle == b'/app0/orbis/desktop'
            assert config.jar == b'/app0/orbis/graphics/lib/orbis-launcher.jar'
    config.jar = b'/data/orbis-jdk/launcher/versions/1.21.1/client.jar'
    config.java_home = b'/data/orbis-jdk/runtimes/jdk21'
    assert api.runner_config_installed(ctypes.byref(config), error, len(error)) == 0
    assert config.jar == b'/data/orbis-jdk/launcher/versions/1.21.1/client.jar', 'a game JAR is kept'
    config.java_home = b'/data/orbis-jdk/runtimes/jdk18'
    assert api.runner_config_installed(ctypes.byref(config), error, len(error)) < 0
    config.java_home = b'/unknown/runtime'
    assert api.runner_config_installed(ctypes.byref(config), error, len(error)) < 0
# The SDK libc's lstat (via fstatat) and link are ENOSYS stubs; first start then failed on the console.
import re
source = (ROOT / 'tools/runner_config.c').read_text()
assert not re.search(r'\b(lstat|fstatat|stat|link|linkat)\s*\(', source), 'runner_config.c must not use stat or link calls the Orbis libc stubs out'
print('Installed runtime selection and exclusive first-start defaults passed')
