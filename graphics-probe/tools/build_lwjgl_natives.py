#!/usr/bin/env python3
"""Cross-build LWJGL core, OpenGL and STB natives as Orbis PRXs.

Sources are the pinned archives in lwjgl/lwjgl-sources.lock.json (generated C is part of both
release trees). LWJGL 3.2.2 (Minecraft up to 1.20.4) calls through dyncall 1.0, which replaces the
prebuilt Linux libdyncall*_s.a LWJGL downloads; LWJGL 3.3.3 (Minecraft 1.19 to 1.21.11) calls
through libffi 3.4.4 and LWJGL 3.4.1 (Minecraft 26.1 and 26.2) through libffi 3.5.0, built here in
place of the prebuilt libffi.a. Each module uses the shared
Orbis JNI recipe (orbis_prx.py). Linux natives are never used.
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_stamp  # noqa: E402
import lwjgl_sources  # noqa: E402
import orbis_prx  # noqa: E402

ROOT = orbis_prx.ROOT
LOCK = lwjgl_sources.LOCK
# dyncall 1.0 x86-64 SysV; the arch-specific files are #included by these units.
DYNCALL_SOURCES = (
    'dyncall/dyncall_api.c', 'dyncall/dyncall_callvm.c', 'dyncall/dyncall_callvm_base.c',
    'dyncall/dyncall_call.S', 'dyncall/dyncall_callf.c', 'dyncall/dyncall_vector.c',
    'dyncall/dyncall_struct.c', 'dyncallback/dyncall_args.c',
    'dyncallback/dyncall_callback.c', 'dyncallback/dyncall_callback_arch.S',
    'dyncallback/dyncall_thunk.c', 'dynload/dynload.c', 'dynload/dynload_syms.c')
# libffi's x86-64 Unix back-end (3.4.4 and 3.5.0), the set its configure picks for x86_64-*-linux;
# closures.c is replaced by lwjgl/libffi/orbis_ffi_closures.c. Static trampolines (tramp.c) stay off,
# as LWJGL's ffitarget.h leaves FFI_EXEC_STATIC_TRAMP undefined.
LIBFFI_SOURCES = ('src/prep_cif.c', 'src/types.c', 'src/raw_api.c', 'src/java_raw_api.c',
                  'src/x86/ffi64.c', 'src/x86/unix64.S', 'src/x86/ffiw64.c', 'src/x86/win64.S')
# Per release: the lock entry and the library that makes calls and callbacks.
RELEASES = {'3.2.2': ('lwjgl', 'dyncall'), '3.3.3': ('lwjgl-3.3.3', 'libffi'), '3.4.1': ('lwjgl-3.4.1', 'libffi-3.5.0')}

parser = argparse.ArgumentParser()
parser.add_argument('--sdk', default='/home/wonder/ps4-payload-sdk', help='OpenOrbis clang/lld')
parser.add_argument('--toolchain', default='/home/wonder/PS4Toolchain')
parser.add_argument('--jni-headers', default='/usr/lib/jvm/java-17-openjdk-amd64/include')
parser.add_argument('--jni-platform', default='linux')
parser.add_argument('--paid', default='0x3800000000000011')
parser.add_argument('--downloads', default=str(ROOT / 'build/downloads'))
parser.add_argument('--fetch', action='store_true', help='download missing pinned archives')
parser.add_argument('--release', choices=sorted(RELEASES), default='3.2.2')
parser.add_argument('--output', help='default build/lwjgl (3.2.2) or build/lwjgl-<release>')
arguments = parser.parse_args()
lwjgl_key, ffi_key = RELEASES[arguments.release]
if arguments.output is None:
    arguments.output = str(ROOT / ('build/lwjgl' if arguments.release == '3.2.2' else f'build/lwjgl-{arguments.release}'))
tools = orbis_prx.Toolchain(arguments.sdk, arguments.toolchain)
downloads, out = Path(arguments.downloads).resolve(), Path(arguments.output).resolve()
# 3.2.2's TinyFD comes from build_lwjgl_optional.py; 3.3.3's TinyFD JNI binds a newer interface and
# is built here with its own stub (lwjgl/tinyfd/orbis_tinyfd_3_3.c), which 3.4.1's identical one shares.
MODULES = ('liblwjgl', 'liblwjgl_opengl', 'liblwjgl_stb') + (() if arguments.release == '3.2.2' else ('liblwjgl_tinyfd',))

# The archives are pinned by the lock file, so the stamp covers them through it.
stamp = (orbis_prx.stamp_recipe(build_stamp.Stamp(out, 'lwjgl-natives'), tools)
         .files(Path(__file__), Path(lwjgl_sources.__file__), ROOT / 'lwjgl/lwjgl-sources.lock.json',
                ROOT / f"lwjgl/{ffi_key.split('-')[0]}", ROOT / 'lwjgl/tinyfd/orbis_tinyfd_3_3.c')
         .stats(Path(arguments.jni_headers))
         .value('arguments', {k: v for k, v in vars(arguments).items() if k not in ('fetch', 'downloads')}))
if stamp.current(out / 'lwjgl-audit.json', *[out / f'{name}{suffix}' for name in MODULES for suffix in ('.so', '.prx')]):
    for name, audit in json.loads((out / 'lwjgl-audit.json').read_text())['modules'].items():
        print(f"{name}: unchanged inputs, reusing the last build ({audit['jni_natives']} JNI natives)")
    sys.exit(0)
stamp.invalidate()


sources = out / 'sources'
lwjgl = lwjgl_sources.extract(lwjgl_sources.archive(lwjgl_key, downloads, arguments.fetch), sources / 'lwjgl') / 'modules/lwjgl'
ffi = lwjgl_sources.extract(lwjgl_sources.archive(ffi_key, downloads, arguments.fetch), sources / ffi_key)
script, runtime = orbis_prx.runtime_objects(tools, out / 'runtime')
jni = ('-I', arguments.jni_headers, '-I', Path(arguments.jni_headers) / arguments.jni_platform)
# LWJGL's Linux flags, minus glibc-specific -U_FORTIFY_SOURCE handling and LTO. 3.3 builds with
# gnu11 and includes the STB headers as system headers (they rely on implicit declarations).
modern = arguments.release != '3.2.2'
lwjgl_flags = ('-std=gnu11' if modern else '-std=c11', '-O2', '-fdata-sections', '-D_GNU_SOURCE', '-DNDEBUG', '-DLWJGL_LINUX',
               '-DLWJGL_x64', *jni, '-I', lwjgl / 'core/src/main/c', '-I', lwjgl / 'core/src/main/c/linux')


def compile_all(files, directory, *flags):
    directory.mkdir(parents=True, exist_ok=True)
    objects = []
    for source in files:
        stem = source.name.replace('.', '_')
        objects.append(tools.compile(source, directory / f'{stem}.o', *flags))
    return objects


if ffi_key == 'dyncall':
    # dyncall_alloc_wx_mmap.c is replaced: one 16 KiB mapping per callback exhausts Orbis memory.
    ffi_objects = compile_all([ffi / s for s in DYNCALL_SOURCES] + [ROOT / 'lwjgl/dyncall/orbis_dyncall_alloc_wx.c'],
                              out / 'obj/dyncall', '-O2',
                              '-I', ffi / 'dyncall', '-I', ffi / 'dyncallback', '-I', ffi / 'dynload')
    ffi_flags = ('-I', lwjgl / 'core/src/main/c/dyncall')
else:
    # LWJGL's own ffi.h/ffitarget.h, so the library and the bindings agree on every structure.
    ffi_flags = ('-I', lwjgl / 'core/src/main/c/libffi', '-I', lwjgl / 'core/src/main/c/libffi/x86', '-DX86_64')
    ffi_objects = compile_all([ffi / s for s in LIBFFI_SOURCES] + [ROOT / 'lwjgl/libffi/orbis_ffi_closures.c'],
                              out / 'obj/libffi', '-O2', '-I', ROOT / 'lwjgl/libffi', *ffi_flags,
                              '-I', ffi / 'include', '-I', ffi / 'src/x86')
# io_uring has no Orbis counterpart; its bindings stay out like the Windows ones.
core_sources = (sorted((lwjgl / 'core/src/main/c').glob('*.c')) + sorted((lwjgl / 'core/src/generated/c').glob('*.c'))
                + sorted(p for p in (lwjgl / 'core/src/generated/c/linux').glob('*.c') if 'liburing' not in p.name))
core_objects = compile_all(core_sources, out / 'obj/core', *lwjgl_flags, *ffi_flags)
# 3.4.1: JNI_OnLoad installs the GLFW facade's EGL provider while GL initializes (Minecraft 26.2).
opengl_objects = compile_all(sorted(p for p in (lwjgl / 'opengl/src/generated/c').glob('*.c')
                                    if p.name != 'org_lwjgl_opengl_WGL.c')
                             + ([ROOT / 'lwjgl/opengl/orbis_opengl_onload.c'] if arguments.release == '3.4.1' else []),
                             out / 'obj/opengl', *lwjgl_flags, '-I', lwjgl / 'opengl/src/main/c')
stb_sources = sorted((lwjgl / 'stb/src/generated/c').glob('*.c'))
if len(stb_sources) != 11:
    raise SystemExit(f'expected 11 pinned STB generated translation units, found {len(stb_sources)}')
stb_objects = compile_all(stb_sources, out / 'obj/stb', *lwjgl_flags,
                          '-isystem' if modern else '-I', lwjgl / 'stb/src/main/c')

builds = [('liblwjgl', core_objects + ffi_objects, 'Java_org_lwjgl_system_', True),
          ('liblwjgl_opengl', opengl_objects, 'Java_org_lwjgl_opengl_', arguments.release == '3.4.1'),
          ('liblwjgl_stb', stb_objects, 'Java_org_lwjgl_stb_', False)]
if 'liblwjgl_tinyfd' in MODULES:
    tinyfd = lwjgl / 'tinyfd/src'
    builds.append(('liblwjgl_tinyfd', compile_all(
        [tinyfd / 'generated/c/org_lwjgl_util_tinyfd_TinyFileDialogs.c', ROOT / 'lwjgl/tinyfd/orbis_tinyfd_3_3.c'],
        out / 'obj/tinyfd', *lwjgl_flags, '-I', tinyfd / 'main/c'), 'Java_org_lwjgl_util_tinyfd_', False))
summary = {'lwjgl': LOCK[lwjgl_key]['version'], ffi_key: LOCK[ffi_key]['version'], 'modules': {}}
for name, objects, prefix, onload in builds:
    elf = out / f'{name}.so'
    orbis_prx.link(tools, script, runtime, elf, objects)
    audit, natives = orbis_prx.audit(elf, allowed_needed=('libkernel.so',), jni_prefix=prefix,
                                     jni_count=None, require_onload=onload)
    audit['prx'] = str(orbis_prx.fself(tools, elf, arguments.paid))
    (out / f'{name}-natives.txt').write_text('\n'.join(natives) + '\n')
    summary['modules'][name] = audit
    print(f"{name}: {audit['jni_natives']} JNI natives, {audit['imports']} imports, TLS {audit['tls']}")
(out / 'lwjgl-audit.json').write_text(json.dumps(summary, indent=2) + '\n')
stamp.record()
