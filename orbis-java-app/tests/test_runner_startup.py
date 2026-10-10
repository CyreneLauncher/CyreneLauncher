#!/usr/bin/env python3
"""Run launcher options reader on real files; platform calls are excluded."""
import ctypes
import re
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
source = (root/'launcher.c').read_text()
start = source.index('static int load_runtime_vm_options(')
end = source.index('\n#if ORBIS_PROFILE_SCREEN_CONSOLE',start)
function = source[start:end].replace('static int load_runtime_vm_options','int load_runtime_vm_options')
class Option(ctypes.Structure):
    _fields_ = [('optionString',ctypes.c_char_p),('extraInfo',ctypes.c_void_p)]
with tempfile.TemporaryDirectory() as directory:
    td=Path(directory); path=td/'jvm-options.txt'
    unit=td/'options.c'; lib=td/'options.so'
    unit.write_text('''#include <stdio.h>
#include <errno.h>
#include <string.h>
#include "runner_config.h"
static RunnerConfig runner_config = {.libraries="/data/libs"};
typedef struct { char *optionString; void *extraInfo; } JavaVMOption;
static void orbis_log_line(const char *a, const char *b, const char *c, ...) { (void)a; (void)b; (void)c; }
static void debug_text(const char *a) { (void)a; }
static int desktop_option_conflict(const char *option) { (void)option; return 0; }
#define ORBIS_PROFILE_RUNTIME_JVM_OPTIONS "'''+str(path)+'"\n'+function)
    subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror','-shared','-fPIC','-I'+str(root/'tools'),str(unit),str(root/'tools/runner_config.c'),'-o',str(lib)],check=True)
    api=ctypes.CDLL(str(lib))
    def read(text=None):
        if text is not None: path.write_bytes(text)
        options=(Option*96)(); storage=((ctypes.c_char*8192)*96)()
        count=api.load_runtime_vm_options(options,storage,96)
        return count,[options[i].optionString for i in range(max(count,0))]
    assert read()[0] == 0
    assert read(b'# ignored\r\n\r\n-Xmx512m\r\n-Dapplication.name=a b\n') == (2,[b'-Xmx512m',b'-Dapplication.name=a b'])
    for text in (b'-Djava.home=/other\n',b'-Drunner.jar=/other\n',b'-Dorbis.module.names.file=/other\n',b'--module-path=/other\n',b'-Xmx32m\n'*97,b'x'*8192+b'\n'):
        assert read(text)[0] < 0, text[:50]
    assert read(b'-Xmx32m\n'*96)[0] == 96
    long_module = b'/data/libs/' + b'a'*600 + b'.jar'
    assert read(b'-Dorbis.loader.modulePath='+long_module+b'\n') == (1,[b'--module-path='+long_module])
    assert read(b'-Dorbis.loader.modulePath=/evil.jar\n')[0] < 0

# Display ownership: configuration decides whether the CPU screen console starts.
main = source[source.index('int main(int argc, char **argv) {'):]
config_at = main.index('runner_config_load(')
init_at = main.index('orbis_screen_console_init()')
assert config_at < init_at, 'configuration must load before CPU display/input start'
guard = main[config_at:init_at]
assert 'configuration_result < 0 || !runner_display_gpu()' in guard, 'GPU mode must skip CPU screen console'
assert main.index('if (configuration_result < 0) {') > init_at, 'configuration errors still reach the screen'
gpu_at = main.index('if (runner_display_gpu()) {')
assert 'graphics-bundle.json' in main[gpu_at:main.index('if (runner_display_desktop()) {', gpu_at)]
assert '"runner/Main"' in main[gpu_at:gpu_at + 300]
# GPU mode keeps Java console output off-screen and adds the bundle natives last.
assert 'ORBIS_PROFILE_SCREEN_CONSOLE && !runner_display_gpu() ? "true" : "false"' in main
assert '"%s/lib:%s/lib/server:/app0/sce_module%s%s%s"' in main
assert '"-Drunner.graphics.bundle=%s"' in main and 'runner_display_name()' in main
assert not re.search(r'runner_config\.desktop\b', source), 'use runner_display_desktop()'
print('Runner startup options behavior passed')
