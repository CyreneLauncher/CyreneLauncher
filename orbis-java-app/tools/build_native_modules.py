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
import struct

from orbis_native_modules import DESKTOP_MODULES, native_modules

p = argparse.ArgumentParser()
p.add_argument('--sdk', required=True)
p.add_argument('--toolchain', required=True)
p.add_argument('--jdk', required=True)
p.add_argument('--paid', required=True)
p.add_argument('--policy', required=True)
p.add_argument('--desktop', action='store_true')
p.add_argument('--output')
p.add_argument('--link-script', default='build/jvm-link.x')
a = p.parse_args()
sdk, tc, jdk = map(Path, (a.sdk, a.toolchain, a.jdk))
out = Path(a.output or ('build/native-desktop' if a.desktop else 'build/native')).resolve()
out.mkdir(parents=True, exist_ok=True)
audit_root = out.parent
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
base_modules = [
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
]

management_modules = [
    # Keep only libjvm as an external JNI dependency. Management JNI
    # utilities and common libc routines are linked locally below, avoiding
    # ownership ambiguity from undefined symbols in dependency ELFs.
    ('management', ['jvm']),
    ('management_ext', ['jvm']),
]
auth_modules = [('jaas', [])]
desktop_modules = [
    ('freetype', []),
    ('awt', ['java', 'jvm']),
    ('awt_headless', ['awt', 'java', 'jvm']),
    # Last matching dynsym wins, including undefined symbols in dependencies.
    ('fontmanager', ['freetype', 'awt_headless', 'awt', 'java', 'jvm']),
    ('javajpeg', ['java', 'jvm']),
    ('lcms', ['awt', 'java', 'jvm']),
    ('mlib_image', []),
]
modules = base_modules + management_modules + auth_modules + (desktop_modules if a.desktop else [])
if tuple(name for name, _ in modules if name != 'jvm') != native_modules(a.desktop):
    raise RuntimeError('Native build order differs from shared module catalog')
# Seed and read one environment per PRX. Dependencies precede static libc below,
# so archive scanning could otherwise import accessors from another PRX. Direct
# objects and -Bsymbolic bind all environment accessors and their state locally.
runtime_environment_objects = []
for member in ('getenv', 'setenv', 'unsetenv', 'putenv', '__environ', 'strdup'):
    obj = out / f'orbis_environment_{member}.o'
    with obj.open('wb') as extracted:
        subprocess.run(['llvm-ar', 'p', tc / 'lib/libc.a', member + '.lo'],
                       stdout=extracted, check=True)
    runtime_environment_objects.append(obj)
management_libc_objects = []
# Keep management JNI's common libc routines inside the module so
# create-fself cannot attribute them to undefined dependency symbols.
for member in ('malloc', 'snprintf', 'vsnprintf', 'vfprintf',
               'memcpy', 'memset', 'strcmp', 'strcpy', 'strcat',
               'strlen', 'strncmp'):
    obj = out/f'orbis_management_{member}.o'
    with obj.open('wb') as extracted:
        subprocess.run(['llvm-ar', 'p', tc/'lib/libc.a', member+'.lo'],
                       stdout=extracted, check=True)
    management_libc_objects.append(obj)
for name, deps in modules:
    if name == 'jvm':
        # HotSpot's object directory is not cleaned when configure changes
        # features.  In particular, a previous JFR-enabled build leaves JFR
        # and JVMTI objects behind.  Use the exact object list emitted by the
        # current configuration instead of globbing stale objects into libjvm.
        object_list = (jdk.parent/
                       'hotspot/variant-server/libjvm/objs/'
                       '_BUILD_LIBJVM_objectfilenames.txt')
        objs = [Path(arg) for arg in shlex.split(object_list.read_text())
                if arg.endswith('.o')]
    else:
        if name in DESKTOP_MODULES:
            module_base = jdk.parent / 'support/native/java.desktop'
        elif name in ('management',):
            module_base = jdk.parent / 'support/native/java.management'
        elif name in ('management_ext',):
            module_base = jdk.parent / 'support/native/jdk.management'
        elif name == 'jaas':
            module_base = jdk.parent / 'support/native/jdk.security.auth'
        else:
            module_base = base
        records = list((module_base/f'lib{name}').glob('*_link.cmdline'))
        if len(records) != 1:
            raise RuntimeError(f'Expected one recorded link command for {name}: {records}')
        args = shlex.split(records[0].read_text())
        objs = []
        for arg in args:
            if arg.startswith('@'):
                objs.extend(shlex.split(Path(arg[1:]).read_text()))
            elif arg.endswith('.o'):
                objs.append(arg)
        if name in ('management', 'management_ext'):
            # create-fself considers undefined symbols in dependency ELFs
            # when assigning NIDs.  libjava and libmanagement both mention
            # libc allocators as undefined imports, which can mis-attribute
            # management_ext's malloc/free calls to the wrong PRX.  Keep the
            # JNI utility implementation local and leave only JVM_* imports
            # to libjvm.
            objs.extend((jdk.parent / 'support/native/java.base/libjava/jni_util.o',
                         jdk.parent / 'support/native/java.base/libjava/jni_util_md.o'))
            objs.extend(management_libc_objects)
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
    if name == 'net':
        platform_libs.append('-lSceNetCtl')
    static_libs = ['--start-group', '-lc++', '-lc++abi', '-lc', '-lm',
                   '-lclang_rt.builtins-x86_64', '--end-group']
    # Resolve local C/C++ implementations before looking at JDK dynsyms.
    # Dependencies also advertise their undefined libc symbols to create-fself.
    before_deps = static_libs if name in DESKTOP_MODULES else []
    after_deps = [] if name in DESKTOP_MODULES else static_libs
    print(f'Linking {name}: {len(objs)} objects', flush=True)
    run([sdk/'bin/orbis-ld', '--shared', '--no-undefined', '-soname', f'lib{name}.so',
         '-e', 'module_start', '-Bsymbolic', '--script', a.link_script,
         '-o', out/f'lib{name}.so', *objs, *extras, *runtime_environment_objects, out/'native_dlfcn.o',
         out/'orbis_tls.o', out/'orbis_initializers.o', out/'orbis_runtime.o',
         tc/'lib/crtlib.o',
         *['--wrap='+fn for fn in
           ('dlopen', 'dlsym', 'dlclose', 'dlerror', 'sceKernelDebugOutText',
            '_exit')],
         '-L'+str(out), '-L'+str(tc/'lib'), *before_deps,
         *['-l'+d for d in deps],
         *platform_libs,
         *after_deps, '-lkernel'])
run([sys.executable, 'tools/audit_orbis_binaries.py',
     '--policy', a.policy, '--output', audit_root/'orbis-binary-audit.json',
     *[str(out/f'lib{name}.so') for name, _ in modules]])

provider_path = out
if a.desktop:
    # create-fself also treats undefined dependency dynsyms as exports.
    # Packaging-only symbol catalogs expose genuine definitions. Original
    # link ELFs remain intact and are the audited/debugging artifacts.
    provider_path = out / 'provider-symbols'
    provider_path.mkdir(exist_ok=True)
    for name, _ in modules:
        source = out / f'lib{name}.so'
        data = bytearray(source.read_bytes())
        if data[:6] != b'\x7fELF\x02\x01':
            raise RuntimeError(f'Expected little-endian ELF64 provider: {source}')
        section_offset = struct.unpack_from('<Q', data, 40)[0]
        section_size, section_count = struct.unpack_from('<HH', data, 58)
        for section in range(section_count):
            header = section_offset + section * section_size
            if struct.unpack_from('<I', data, header + 4)[0] not in (2, 11):
                continue
            offset, size = struct.unpack_from('<QQ', data, header + 24)
            entry_size = struct.unpack_from('<Q', data, header + 56)[0]
            if entry_size != 24 or size % entry_size:
                raise RuntimeError(f'Invalid provider dynsym: {source}')
            for symbol in range(offset, offset + size, entry_size):
                if struct.unpack_from('<H', data, symbol + 6)[0] == 0:
                    data[symbol:symbol + entry_size] = bytes(entry_size)
        (provider_path / source.name).write_bytes(data)

for name, _ in modules:
    run([tc/'bin/linux/create-fself', '-in='+str(out/f'lib{name}.so'),
         '-out='+str(out/f'lib{name}.oelf'), '--lib='+str(out/f'lib{name}.prx'),
         '--libname=lib'+name, '--paid', a.paid, '--library-path', provider_path])
    assert (out/f'lib{name}.prx').read_bytes()[:4] == b'O\x15=\x1d'

if a.desktop:
    run([sys.executable, 'tools/audit_native_providers.py', '--native', out,
         '--readoelf', tc/'bin/linux/readoelf',
         '--output', audit_root/'orbis-desktop-provider-audit.json'])
