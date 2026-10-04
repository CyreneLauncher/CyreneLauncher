#!/usr/bin/env python3
"""Cross-build LWJGL 3.2.2 core, OpenGL and STB natives as Orbis PRXs.

Sources are the pinned archives in lwjgl/lwjgl-sources.lock.json (generated C is part of
the 3.2.2 tree). dyncall 1.0 replaces the prebuilt Linux libdyncall*_s.a LWJGL downloads.
Each module uses the shared Orbis JNI recipe (orbis_prx.py). Linux natives are never used.
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

parser = argparse.ArgumentParser()
parser.add_argument('--sdk', default='/home/wonder/ps4-payload-sdk', help='OpenOrbis clang/lld')
parser.add_argument('--toolchain', default='/home/wonder/PS4Toolchain')
parser.add_argument('--jni-headers', default='/usr/lib/jvm/java-17-openjdk-amd64/include')
parser.add_argument('--jni-platform', default='linux')
parser.add_argument('--paid', default='0x3800000000000011')
parser.add_argument('--downloads', default=str(ROOT / 'build/downloads'))
parser.add_argument('--fetch', action='store_true', help='download missing pinned archives')
parser.add_argument('--output', default=str(ROOT / 'build/lwjgl'))
arguments = parser.parse_args()
tools = orbis_prx.Toolchain(arguments.sdk, arguments.toolchain)
downloads, out = Path(arguments.downloads).resolve(), Path(arguments.output).resolve()
MODULES = ('liblwjgl', 'liblwjgl_opengl', 'liblwjgl_stb')

# The archives are pinned by the lock file, so the stamp covers them through it.
stamp = (orbis_prx.stamp_recipe(build_stamp.Stamp(out, 'lwjgl-natives'), tools)
         .files(Path(__file__), Path(lwjgl_sources.__file__), ROOT / 'lwjgl/lwjgl-sources.lock.json',
                ROOT / 'lwjgl/dyncall')
         .stats(Path(arguments.jni_headers))
         .value('arguments', {k: v for k, v in vars(arguments).items() if k not in ('fetch', 'downloads')}))
if stamp.current(out / 'lwjgl-audit.json', *[out / f'{name}{suffix}' for name in MODULES for suffix in ('.so', '.prx')]):
    for name, audit in json.loads((out / 'lwjgl-audit.json').read_text())['modules'].items():
        print(f"{name}: unchanged inputs, reusing the last build ({audit['jni_natives']} JNI natives)")
    sys.exit(0)
stamp.invalidate()


sources = out / 'sources'
lwjgl = lwjgl_sources.extract(lwjgl_sources.archive('lwjgl', downloads, arguments.fetch), sources / 'lwjgl') / 'modules/lwjgl'
dyncall = lwjgl_sources.extract(lwjgl_sources.archive('dyncall', downloads, arguments.fetch), sources / 'dyncall')
script, runtime = orbis_prx.runtime_objects(tools, out / 'runtime')
jni = ('-I', arguments.jni_headers, '-I', Path(arguments.jni_headers) / arguments.jni_platform)
# LWJGL's Linux flags, minus glibc-specific -U_FORTIFY_SOURCE handling and LTO.
lwjgl_flags = ('-std=c11', '-O2', '-fdata-sections', '-D_GNU_SOURCE', '-DNDEBUG', '-DLWJGL_LINUX',
               '-DLWJGL_x64', *jni, '-I', lwjgl / 'core/src/main/c', '-I', lwjgl / 'core/src/main/c/linux')


def compile_all(files, directory, *flags):
    directory.mkdir(parents=True, exist_ok=True)
    objects = []
    for source in files:
        stem = source.name.replace('.', '_')
        objects.append(tools.compile(source, directory / f'{stem}.o', *flags))
    return objects


# dyncall_alloc_wx_mmap.c is replaced: one 16 KiB mapping per callback exhausts Orbis memory.
dyncall_objects = compile_all([dyncall / s for s in DYNCALL_SOURCES] + [ROOT / 'lwjgl/dyncall/orbis_dyncall_alloc_wx.c'],
                              out / 'obj/dyncall', '-O2',
                              '-I', dyncall / 'dyncall', '-I', dyncall / 'dyncallback', '-I', dyncall / 'dynload')
core_sources = (sorted((lwjgl / 'core/src/main/c').glob('*.c')) + sorted((lwjgl / 'core/src/generated/c').glob('*.c'))
                + sorted((lwjgl / 'core/src/generated/c/linux').glob('*.c')))
core_objects = compile_all(core_sources, out / 'obj/core', *lwjgl_flags,
                           '-I', lwjgl / 'core/src/main/c/dyncall')
opengl_objects = compile_all(sorted((lwjgl / 'opengl/src/generated/c').glob('*.c')), out / 'obj/opengl',
                             *lwjgl_flags, '-I', lwjgl / 'opengl/src/main/c')
stb_sources = sorted((lwjgl / 'stb/src/generated/c').glob('*.c'))
if len(stb_sources) != 11:
    raise SystemExit(f'expected 11 pinned STB generated translation units, found {len(stb_sources)}')
stb_objects = compile_all(stb_sources, out / 'obj/stb', *lwjgl_flags,
                          '-I', lwjgl / 'stb/src/main/c')

summary = {'lwjgl': LOCK['lwjgl']['version'], 'dyncall': LOCK['dyncall']['version'], 'modules': {}}
for name, objects, prefix, onload in (
        ('liblwjgl', core_objects + dyncall_objects, 'Java_org_lwjgl_system_', True),
        ('liblwjgl_opengl', opengl_objects, 'Java_org_lwjgl_opengl_', False),
        ('liblwjgl_stb', stb_objects, 'Java_org_lwjgl_stb_', False)):
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
