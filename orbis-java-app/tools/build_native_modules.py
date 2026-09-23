#!/usr/bin/env python3
"""Relink the startup native dependency chain, then produce Orbis SELF modules.

Use the JDK's recorded object list, not a glob: stale platform objects may coexist.
Link-only shared ELFs are retained for import resolution and crash symbolication.
"""
import argparse
from pathlib import Path
import shlex
import subprocess
import sys

p = argparse.ArgumentParser()
p.add_argument('--sdk', required=True)
p.add_argument('--toolchain', required=True)
p.add_argument('--jdk', required=True)
p.add_argument('--paid', required=True)
p.add_argument('--policy', required=True)
a = p.parse_args()
sdk, tc, jdk = map(Path, (a.sdk, a.toolchain, a.jdk))
out = Path('build/native').resolve()
out.mkdir(parents=True, exist_ok=True)
base = jdk.parent / 'support/native/java.base'
repo = Path('..').resolve()

def run(args):
    subprocess.run(list(map(str, args)), check=True)

run([sdk/'bin/orbis-clang', '--target=x86_64-pc-freebsd12-elf', '-fPIC',
     '-isysroot', tc, '-isystem', tc/'include', '-c', 'tools/native_dlfcn.c',
     '-o', out/'native_dlfcn.o'])
run([sdk/'bin/orbis-clang', '--target=x86_64-pc-freebsd12-elf', '-fPIC',
     '-isysroot', tc, '-isystem', tc/'include', '-c', 'tools/orbis_tls.c',
     '-o', out/'orbis_tls.o'])
run([sdk/'bin/orbis-clang', '--target=x86_64-pc-freebsd12-elf', '-fPIC',
     '-isysroot', tc, '-isystem', tc/'include', '-c',
     'tools/orbis_initializers.c', '-o', out/'orbis_initializers.o'])
run([sdk/'bin/orbis-clang', '--target=x86_64-pc-freebsd12-elf', '-fPIC',
     '-isysroot', tc, '-isystem', tc/'include', '-c',
     'tools/orbis_runtime.c', '-o', out/'orbis_runtime.o'])
for name, deps in [
        ('jvm', []),
        ('java', ['jvm']),
        # libjava references JVM_FindLibraryEntry, so create-fself must see
        # libjava before libjvm.  The last matching dependency owns the NID;
        # putting libjvm last keeps JVM_* imports bound to the JVM PRX while
        # JNU_* imports remain provided by libjava.
        ('net', ['java', 'jvm']),
        # create-fself scans dependencies in order and the last matching
        # dynamic symbol owns the import.  libnet itself imports JNU helpers,
        # so put libjava last to keep JNU imports assigned to their provider.
        ('nio', ['net', 'java']),
        ('zip', ['java', 'jvm']),
        ('verify', ['jvm']),
        ('jimage', []),
]:
    if name == 'jvm':
        objs = sorted((jdk.parent/'hotspot/variant-server/libjvm/objs').glob('*.o'))
    else:
        args = shlex.split(next((base/f'lib{name}').glob('*_link.cmdline')).read_text())
        objs = []
        for arg in args:
            if arg.startswith('@'):
                objs.extend(shlex.split(Path(arg[1:]).read_text()))
            elif arg.endswith('.o'):
                objs.append(arg)
        if name == 'net':
            # The Orbis target selects the BSD socket implementation but the
            # generated object list does not include bsd_close.c.  Reuse the
            # exact compiler command recorded for net_util_md.c so this
            # supplemental object has the same ABI and include configuration.
            template = shlex.split(
                (base/'libnet/net_util_md.o.cmdline').read_text())
            compile_args = []
            skip_next = False
            for arg in template:
                if skip_next:
                    skip_next = False
                    continue
                if arg in ('-MMD', '-MF'):
                    skip_next = arg == '-MF'
                    continue
                if arg == '-o':
                    compile_args.extend((arg, str(out/'bsd_close.o')))
                    skip_next = True
                    continue
                if arg.endswith('/net_util_md.c'):
                    compile_args.append(str(
                        repo/'src/java.base/bsd/native/libnet/bsd_close.c'))
                else:
                    compile_args.append(arg)
            run(compile_args)
            objs.append(str(out/'bsd_close.o'))
    if not objs:
        raise RuntimeError(f'No objects for {name}')
    extras = [base/'libfdlibm.a'] if name == 'java' else []
    platform_libs = ['-lSceNet'] if name in ('net', 'nio') else []
    print(f'Linking {name}: {len(objs)} objects', flush=True)
    run([sdk/'bin/orbis-ld', '--shared', '--no-undefined', '-soname', f'lib{name}.so',
         '-e', 'module_start', '-Bsymbolic', '--script', 'build/jvm-link.x',
         '-o', out/f'lib{name}.so', *objs, *extras, out/'native_dlfcn.o',
         out/'orbis_tls.o', out/'orbis_initializers.o', out/'orbis_runtime.o',
         tc/'lib/crtlib.o',
         *['--wrap='+fn for fn in
           ('dlopen', 'dlsym', 'dlclose', 'dlerror', 'sceKernelDebugOutText',
            '_exit')],
         '-L'+str(out), *['-l'+d for d in deps], '-L'+str(tc/'lib'),
         *platform_libs,
         '--start-group', '-lc++', '-lc++abi', '-lc', '-lm',
         '-lclang_rt.builtins-x86_64', '--end-group', '-lkernel'])
run([sys.executable, 'tools/audit_orbis_binaries.py',
     '--policy', a.policy, '--output', 'build/orbis-binary-audit.json',
     'build/launcher.elf',
     *[str(out/f'lib{name}.so') for name in
       ('jvm', 'java', 'net', 'nio', 'zip', 'verify', 'jimage')]])

for name in ('jvm', 'java', 'net', 'nio', 'zip', 'verify', 'jimage'):
    run([tc/'bin/linux/create-fself', '-in='+str(out/f'lib{name}.so'),
         '-out='+str(out/f'lib{name}.oelf'), '--lib='+str(out/f'lib{name}.prx'),
         '--libname=lib'+name, '--paid', a.paid, '--library-path', out])
    assert (out/f'lib{name}.prx').read_bytes()[:4] == b'O\x15=\x1d'
