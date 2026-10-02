#!/usr/bin/env python3
"""Compile the real loader with host kernel mocks; exercise PRX-local handoff."""
from pathlib import Path
import subprocess
import tempfile

source = (Path(__file__).resolve().parents[1] / 'tools/native_dlfcn.c')
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    (root / 'orbis').mkdir()
    (root / 'orbis/libkernel.h').write_text('''#include <stdint.h>
#include <stddef.h>
typedef int OrbisKernelModule;
int sceKernelDlsym(int32_t, const char *, void **);
int sceKernelLoadStartModule(const char *, size_t, const void *, int, void *, int *);
int sceKernelGetModuleList(OrbisKernelModule *, size_t, size_t *);
int sceKernelStopUnloadModule(int32_t, size_t, const void *, int, void *, int *);
''')
    test = root / 'test.c'
    test.write_text('''#include <assert.h>
#include <string.h>
#include <stdlib.h>
#include "''' + str(source) + '''"
static char loaded[1024], child_home[1024];
static int configured, initialized;
static int configure_child(const char *home, const char *cwd, const char *tmp) {
 assert(strcmp(cwd,"/data/custom work")==0); assert(strcmp(tmp,"/data/custom tmp")==0);
 strcpy(child_home, home); configured = 1; return 0;
}
static int initialize_child(void) { assert(configured); initialized++; return 0; }
int sceKernelDlsym(int32_t h, const char *name, void **p) {
    (void)h;
    *p = strcmp(name, "orbis_configure_runtime_v2") == 0 ? (void *)configure_child : (void *)initialize_child;
    return 0;
}
int sceKernelLoadStartModule(const char *p, size_t a, const void *b, int c, void *d, int *e) {
    (void)a; (void)b; (void)c; (void)d; *e=0; strcpy(loaded,p); configured=0; return 42;
}
int sceKernelGetModuleList(OrbisKernelModule *m, size_t z, size_t *n) { (void)m;(void)z;*n=0;return 0; }
int sceKernelStopUnloadModule(int32_t h, size_t a, const void *b, int c, void *d, int *e) {
    (void)h;(void)a;(void)b;(void)c;(void)d;(void)e;return 0;
}
int main(int argc, char **argv) {
    assert(argc==2);
    assert(!__wrap_dlopen("libjava.so",0));
    const char *homes[]={argv[1]};
    for(int i=0;i<1;i++) {
        assert(orbis_configure_runtime_v2(homes[i], "/data/custom work", "/data/custom tmp")==0);
        assert(strcmp(getenv("ORBIS_JAVA_USER_DIR"),"/data/custom work")==0);
        assert(strcmp(getenv("ORBIS_JAVA_TMP_DIR"),"/data/custom tmp")==0);
        assert(strcmp(getenv("TMPDIR"),"/data/custom tmp")==0);
        assert(strcmp(getenv("ORBIS_JAVA_HOME"),homes[i])==0);
        setenv("JAVA_HOME", "/wrong/environment",1);
        assert(__wrap_dlopen("libjava.so",0));
        char expected[1024]; snprintf(expected,sizeof(expected),"%s/lib/libjava.so",homes[i]);
        assert(strcmp(loaded,expected)==0); assert(strcmp(child_home,homes[i])==0);
        assert(__wrap_dlopen("/external/custom.so",0)); assert(strcmp(loaded,"/external/custom.so")==0);
        assert(strcmp(child_home,homes[i])==0);
    }
    assert(initialized==2);
    char longpath[1100]; memset(longpath,'a',sizeof(longpath));longpath[0]='/';longpath[1099]=0;
    assert(orbis_configure_runtime_v2(longpath, "/data/custom work", "/data/custom tmp")<0); assert(!__wrap_dlopen(longpath,0));
    assert(orbis_configure_runtime_v2("relative", "/data/custom work", "/data/custom tmp")<0);
    longpath[0]='a'; assert(!__wrap_dlopen(longpath,0));
    char boundary[1019]; memset(boundary,'b',sizeof(boundary)); boundary[0]='/'; boundary[1018]=0;
    assert(orbis_configure_runtime_v2(boundary, "/data/custom work", "/data/custom tmp")<0);
    assert(orbis_configure_runtime_v2(homes[0], "/data/custom work", "/data/custom tmp")==0);
    assert(orbis_configure_runtime_v2(NULL, "/data/custom work", "/data/custom tmp")<0);
    assert(__wrap_dlopen(NULL,0)==(void *)(intptr_t)-1);
    return 0;
}
''')
    subprocess.run(['cc', '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-I', str(root), str(test), '-o', str(root / 'test')], check=True)
    for home in ('/data/jdk-release', '/mnt/runtime debug'):
        subprocess.run([str(root / 'test'), home], check=True)
print('Native runtime location, child configuration order, absolute loads and overflow checks passed')
