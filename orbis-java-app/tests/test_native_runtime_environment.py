#!/usr/bin/env python3
"""Two separately linked loader adapters own independent libc environment mocks."""
import ctypes
from pathlib import Path
import subprocess
import shutil
import tempfile

source = Path(__file__).resolve().parents[1] / 'tools/native_dlfcn.c'
with tempfile.TemporaryDirectory() as temporary:
    root = Path(temporary)
    (root / 'orbis').mkdir()
    (root / 'orbis/libkernel.h').write_text('''#include <stdint.h>
#include <stddef.h>
typedef int OrbisKernelModule;
int sceKernelDlsym(int32_t,const char*,void**);
int sceKernelLoadStartModule(const char*,size_t,const void*,int,void*,int*);
int sceKernelGetModuleList(OrbisKernelModule*,size_t,size_t*);
int sceKernelStopUnloadModule(int32_t,size_t,const void*,int,void*,int*);
''')
    fixture = root / 'module.c'
    fixture.write_text('''#include <stdlib.h>
#include <string.h>
static char values[4][1024]; static int failure=-1; static int calls, initialized;
static int index_for(const char *key) {
 if (!strcmp(key,"ORBIS_JAVA_USER_DIR")) return 0;
 if (!strcmp(key,"ORBIS_JAVA_TMP_DIR")) return 1;
 if (!strcmp(key,"ORBIS_JAVA_HOME")) return 3;
 return 2;
}
static int local_setenv(const char *key,const char *value,int overwrite) {
 (void)overwrite; calls++; int i=index_for(key);
 if (i==failure) return -1;
 strcpy(values[i],value);return 0;
}
#define setenv local_setenv
#include "''' + str(source) + '''"
const char *test_value(int i) {return values[i];}
const char *test_home(void) {return runtime_home;}
void test_fail(int i) {failure=i;}
int test_calls(void) {return calls;}
int test_initialized(void) {return initialized;}
static int initialize(void) {initialized++;return 0;}
int sceKernelDlsym(int32_t h,const char*n,void**p){(void)h;(void)n;*p=(void*)initialize;return 0;}
int sceKernelLoadStartModule(const char*p,size_t a,const void*b,int c,void*d,int*e){(void)p;(void)a;(void)b;(void)c;(void)d;*e=0;return 42;}
int sceKernelGetModuleList(OrbisKernelModule*m,size_t z,size_t*n){(void)m;(void)z;*n=0;return 0;}
int sceKernelStopUnloadModule(int32_t h,size_t a,const void*b,int c,void*d,int*e){(void)h;(void)a;(void)b;(void)c;(void)d;(void)e;return 0;}
''')
    template = root / 'template.so'
    subprocess.run(['cc','-shared','-fPIC','-Wl,-Bsymbolic','-Wall','-Wextra','-Werror','-I',str(root),str(fixture),'-o',str(template)],check=True)
    def module_instance(index):
        output = root / ('module%d.so' % index)
        shutil.copyfile(template, output)
        module = ctypes.CDLL(str(output))
        module.orbis_configure_runtime_v2.argtypes = [ctypes.c_char_p] * 3
        module.test_value.restype = ctypes.c_char_p
        module.test_home.restype = ctypes.c_char_p
        module.__wrap_dlopen.argtypes = [ctypes.c_char_p, ctypes.c_int]
        module.__wrap_dlopen.restype = ctypes.c_void_p
        return module

    first, second = module_instance(0), module_instance(1)
    assert first.orbis_configure_runtime_v2(b'/runtime/one', b'/work/one', b'/tmp/one') == 0
    assert second.test_home() == b'' and second.test_value(0) == b''
    assert second.orbis_configure_runtime_v2(b'/runtime/two', b'/work/two', b'/tmp/two') == 0
    for module, suffix in ((first, b'one'), (second, b'two')):
        expected = [b'/work/' + suffix, b'/tmp/' + suffix, b'/tmp/' + suffix, b'/runtime/' + suffix]
        assert module.test_home() == b'/runtime/' + suffix
        assert [module.test_value(i) for i in range(4)] == expected
        module.test_fail(0)  # Permanent OOM after configuration must not allocate.
        calls = module.test_calls()
        assert module.orbis_configure_runtime_v2(b'/runtime/' + suffix, *expected[:2]) == 0
        assert module.test_calls() == calls
        for paths in ((b'/new', *expected[:2]),
                      (b'/runtime/' + suffix, b'/newwork', expected[1]),
                      (b'/runtime/' + suffix, expected[0], b'/newtmp')):
            assert module.orbis_configure_runtime_v2(*paths) == -1
            assert module.test_calls() == calls
            assert [module.test_value(i) for i in range(4)] == expected
    for failure in range(4):
        module = module_instance(failure + 2)
        module.test_fail(failure)
        assert module.orbis_configure_runtime_v2(b'/runtime', b'/work', b'/tmp') == -1
        calls = module.test_calls()
        assert module.test_home() == b''
        assert not module.__wrap_dlopen(b'libjava.so', 0)
        assert not module.__wrap_dlopen(b'/external/custom.so', 0)
        assert module.test_initialized() == 0
        assert module.orbis_configure_runtime_v2(b'/runtime', b'/work', b'/tmp') == -1
        assert module.test_calls() == calls
        module.test_fail(-1)  # Even recovered memory cannot revive a poisoned module.
        assert module.orbis_configure_runtime_v2(b'/runtime', b'/work', b'/tmp') == -1
        assert module.test_calls() == calls
    invalid = [
        (b'/new', b'relative', b'/newtmp'), (b'/new', b'/newwork', b'relative'),
        (b'/new', None, b'/newtmp'), (b'/new', b'/newwork', None),
        (b'/new', b'/' + b'x' * 1024, b'/newtmp'),
        (b'/new', b'/newwork', b'/' + b'x' * 1024),
    ]
    fresh = module_instance(6)
    for paths in invalid:
        assert fresh.orbis_configure_runtime_v2(*paths) == -1
        assert fresh.test_home() == b'' and fresh.test_calls() == 0
    assert fresh.orbis_configure_runtime_v2(b'/runtime', b'/work', b'/tmp') == 0
print('PRX environment isolation, immutable configuration and permanent OOM checks passed')
