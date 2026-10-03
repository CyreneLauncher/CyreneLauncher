#!/usr/bin/env python3
"""Exercise real host parser; catches missing defaults, validation and overrides."""
import ctypes
import subprocess
import tempfile
from pathlib import Path
root = Path(__file__).resolve().parents[1]
class Config(ctypes.Structure):
    _fields_ = [(name, ctypes.c_char * 512) for name in ('java_home','jar','working_directory','output_log','temporary_directory','desktop_bundle','graphics_bundle','version_metadata','libraries')] + [('debug', ctypes.c_int), ('display', ctypes.c_int), ('input_diagnostics', ctypes.c_int)]
with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    lib = td / 'config.so'
    subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror','-shared','-fPIC',str(root/'tools/runner_config.c'),'-o',str(lib)],check=True)
    api = ctypes.CDLL(str(lib))
    def load(content=None, path=None):
        path = path or td/'runner.conf'
        if content is not None: path.write_bytes(content)
        cfg, error = Config(), ctypes.create_string_buffer(1024)
        rc = api.runner_config_load(str(path).encode(),ctypes.byref(cfg),error,len(error))
        return rc,cfg,error.value
    rc,cfg,error = load(path=td/'missing')
    assert rc == 0 and cfg.java_home == b'/data/orbis-jdk/jdk' and cfg.jar == b'/data/orbis-jdk/runner/target.jar'
    assert cfg.working_directory == b'/data/orbis-jdk/runner' and cfg.output_log == b'/data/orbis-jdk/runner/runner-output.log' and cfg.temporary_directory == b'/data/orbis-jdk/runner/tmp'
    assert cfg.display == 0 and cfg.desktop_bundle == b'/data/orbis-jdk/cacio'
    assert cfg.debug == 0 and cfg.input_diagnostics == 0
    assert cfg.graphics_bundle == b'/data/orbis-jdk/graphics'
    assert cfg.version_metadata == b'/data/orbis-jdk/runner/version.json' and cfg.libraries == b'/data/orbis-jdk/libraries'
    rc,cfg,error = load(b'version_metadata=/data/game/1.18.2.json\nlibraries=/data/game/libraries\n')
    assert rc == 0 and cfg.version_metadata == b'/data/game/1.18.2.json' and cfg.libraries == b'/data/game/libraries', error
    rc,cfg,error = load(b' # comment\r\n\r\n java_home = /other/jdk \r\njar=/apps/a b.jar\r\nworking_directory=/work\noutput_log=/logs/app.log\ntemporary_directory=/tmp/app')
    assert rc == 0, error
    assert [getattr(cfg,n) for n,_ in cfg._fields_[:5]] == [b'/other/jdk',b'/apps/a b.jar',b'/work',b'/logs/app.log',b'/tmp/app']
    for mode, expected in ((b'console', 0), (b'desktop', 1), (b'gpu', 2)):
        rc,cfg,error = load(b'display='+mode+b'\ngraphics_bundle=/other/graphics\n')
        assert rc == 0 and cfg.display == expected and cfg.graphics_bundle == b'/other/graphics', error
    for value, expected in ((b'true', 1), (b'false', 0)):
        rc,cfg,error = load(b'debug=true\ninput_diagnostics='+value+b'\n')
        assert rc == 0 and cfg.debug == 1 and cfg.input_diagnostics == expected, error
    for content in (b'unknown=/x\n',b'input_diagnostics=yes\n',b'input_diagnostics=true\ninput_diagnostics=false\n',b'jar=/a\njar=/b\n',b'display=gpu\ndisplay=console\n',b'display=invalid\n',b'graphics_bundle=relative\n',b'version_metadata=relative.json\n',b'libraries=lib\n',b'libraries=/a\nlibraries=/b\n',b'graphics_bundle=/bad:path\n',b'jar=relative\n',b'jar=\n',b'bad\n',b'jar=/' + b'x'*511,b'#'+b'x'*2048,b'jar=/a\x00ignored\n'):
        rc,cfg,error = load(content)
        assert rc < 0 and error, (content[:40],rc,error)
    rc,cfg,error = load(path=td)
    assert rc < 0 and error
    for option in (b'-Djava.home=/x',b'-Djava.class.path=/x',b'-Djava.library.path=/x',b'-Djava.io.tmpdir=/x',b'-Duser.dir=/x',b'-Drunner.jar=/x',b'-Drunner.output.log=/x',b'-Dorbis.runner.root=/x',b'-Dorbis.module.names.file=/x',b'-Drunner.gpu.metadata=/x',b'-Drunner.gpu.libraries=/x',b'--module-path=/x',b'-Xbootclasspath/a:/x'):
        assert api.runner_option_reserved(option) == 1, option
    for option in (b'-Xmx512m',b'-Dapplication.name=a b',b'-Djava.home.extra=ok'):
        assert api.runner_option_reserved(option) == 0, option
print('Runner configuration behavior passed')
