#!/usr/bin/env python3
"""Link the Mesa platform bridge into a JVM-loadable PRX and audit it.

Uses the shared Orbis JNI module recipe (orbis_prx.py) with the Mesa/compat/libc16
inputs in the probe's console-proven order. Compat deliberately overrides libc
members, hence --allow-multiple-definition and the kit's wchar extraction audit.
"""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import orbis_prx  # noqa: E402

ROOT = orbis_prx.ROOT
NAME = 'orbis_lwjgl_platform'

parser = argparse.ArgumentParser()
parser.add_argument('--sdk', default='/home/wonder/ps4-payload-sdk', help='OpenOrbis clang/lld')
parser.add_argument('--toolchain', default='/home/wonder/PS4Toolchain')
parser.add_argument('--jni-headers', default='/usr/lib/jvm/java-17-openjdk-amd64/include')
parser.add_argument('--jni-platform', default='linux')
parser.add_argument('--paid', default='0x3800000000000011')
parser.add_argument('--build', default=str(ROOT / 'build/target'), help='Mesa target build directory')
parser.add_argument('--output', default=str(ROOT / 'build/platform'))
arguments = parser.parse_args()
tools = orbis_prx.Toolchain(arguments.sdk, arguments.toolchain)
build, out = Path(arguments.build).resolve(), Path(arguments.output).resolve()
sources = ROOT / 'build/sources'
compat = sources / 'orbis-compat'
kit = sources / 'orbis-porting-kit'


def single(pattern):
    matches = sorted(build.glob(pattern))
    if len(matches) != 1:
        raise SystemExit(f'expected one {pattern} under {build}: {matches}')
    return matches[0]


egl = single('mesa/src/egl/libEGL.a')
gallium = single('mesa/src/gallium/targets/dri/libgallium-*.a')
radv = single('mesa/src/amd/vulkan/libvulkan_radeon.a')
zlib = single('mesa/subprojects/zlib-*/libz.a')
compat_archive = compat / 'build/liborbis-compat.a'
libc16 = sorted((compat / 'build/libc16').glob('libc16_*.o'))
if len(libc16) != 5 or not compat_archive.is_file():
    raise SystemExit('pinned orbis-compat build with five libc16 objects is required')

script, runtime = orbis_prx.runtime_objects(tools, out)
# ORBIS_HEAP_CHECK=1: a guarded heap for this module (lwjgl/platform/orbis_heapcheck.c) - a diagnostic build.
heap_check = os.environ.get('ORBIS_HEAP_CHECK') in ('1', '2')  # 2: guards without the quarantine
# ORBIS_HEAP_CHECK=trace: musl's heap unchanged, every call recorded and its bins checked (orbis_heaptrace.c).
heap_trace = os.environ.get('ORBIS_HEAP_CHECK') == 'trace'
bridge = tools.compile(ROOT / f'lwjgl/platform/{NAME}.c', out / f'{NAME}.o',
                       '-fdata-sections', '-O2', '-Wall', '-Werror', '-D__ORBIS__', '-DPS4', '-D_BSD_SOURCE=1',
                       '-isystem', compat / 'include', '-I', sources / 'mesa/include',
                       '-I', arguments.jni_headers, '-I', Path(arguments.jni_headers) / arguments.jni_platform,
                       *(['-DORBIS_HEAP_CHECK'] if heap_check else []),
                       *(['-DORBIS_HEAP_TRACE'] if heap_trace else []))
# realloc goes to the bridge's fix for musl's (see __wrap_realloc), or to a diagnostic heap's own.
heap_objects, heap_wraps = [], ('--wrap=realloc',)
if heap_check:
    heap_objects = [tools.compile(ROOT / 'lwjgl/platform/orbis_heapcheck.c', out / 'orbis_heapcheck.o',
                                  '-O2', '-Wall', '-Werror', '-D__ORBIS__', '-DPS4', '-D_BSD_SOURCE=1',
                                  '-isystem', compat / 'include',
                                  *(['-DHEAPCHECK_NO_QUARANTINE'] if os.environ['ORBIS_HEAP_CHECK'] == '2' else []))]
    heap_wraps = tuple(f'--wrap={name}' for name in
                       ('malloc', 'free', 'calloc', 'realloc', 'memalign', 'posix_memalign'))
    print('platform PRX: ORBIS_HEAP_CHECK=1 - guarded heap linked in (diagnostic build)')
if heap_trace:
    heap_objects = [tools.compile(ROOT / 'lwjgl/platform/orbis_heaptrace.c', out / 'orbis_heaptrace.o',
                                  '-O2', '-Wall', '-Werror', '-D__ORBIS__', '-DPS4', '-D_BSD_SOURCE=1',
                                  '-isystem', compat / 'include')]
    heap_wraps = tuple(f'--wrap={name}' for name in ('malloc', 'free', 'calloc', 'realloc'))
    print('platform PRX: ORBIS_HEAP_CHECK=trace - heap tracer linked in (diagnostic build)')
elf = out / f'lib{NAME}.so'
why = out / 'why-extract.txt'


def link_platform(mal_offset=0):
    # The tracer reads musl's static `mal`; its address is known only after a link, and --defsym relative to
    # __text_start moves nothing, so the second link gives the same layout with the symbol filled in.
    defsym = (f'--defsym=orbis_musl_mal=__text_start+{mal_offset:#x}',) if heap_trace else ()
    orbis_prx.link(tools, script, runtime, elf,
                   [bridge, *heap_objects, '--start-group', egl, gallium, '--whole-archive', radv, '--no-whole-archive', zlib,
                    '--end-group', '--whole-archive', compat_archive, '--no-whole-archive', *libc16],
                   system_libraries=('SceGnmDriver', 'SceVideoOut', 'ScePad', 'SceUserService', 'SceSystemService', 'SceSysmodule', 'SceImeDialog', 'SceCommonDialog'),
                   # Mesa's thread creation passes through the bridge's diagnostic wrapper.
                   extra=('--allow-multiple-definition', '--wrap=pthread_create', *heap_wraps, *defsym), why_extract=why)


def symbol(name):
    for line in orbis_prx.run('llvm-nm', elf, stdout=subprocess.PIPE).stdout.splitlines():
        fields = line.split()
        if len(fields) == 3 and fields[2] == name:
            return int(fields[0], 16)
    raise SystemExit(f'{elf.name}: no symbol {name}')


link_platform()
if heap_trace:
    mal = symbol('mal') - symbol('__text_start')
    link_platform(mal)
    if symbol('orbis_musl_mal') != symbol('mal'):
        raise SystemExit('heap tracer: orbis_musl_mal does not land on musl\'s mal after the second link')
    print(f'platform PRX: heap tracer reads musl mal at text+{mal:#x}')
wchar = orbis_prx.run('sh', kit / 'scripts/ps4/check-wchar-link.sh', why, stdout=subprocess.PIPE).stdout
(out / 'wchar-audit.log').write_text(wchar)

audit, natives = orbis_prx.audit(elf, allowed_needed=('libkernel.so', 'libSceGnmDriver.so', 'libSceVideoOut.so', 'libScePad.so',
                                                 'libSceUserService.so', 'libSceSystemService.so', 'libSceSysmodule.so',
                                                 'libSceImeDialog.so', 'libSceCommonDialog.so'),
                                 jni_prefix='Java_orbis_graphics_NativePlatform_', jni_count=19)
if audit['tls'] is None:
    raise SystemExit('platform PRX has no TLS image; Mesa TLS was expected')
audit['jni_natives'] = natives
audit['prx'] = str(orbis_prx.fself(tools, elf, arguments.paid))
(out / 'platform-audit.json').write_text(json.dumps(audit, indent=2) + '\n')
print(f"platform PRX: {audit['prx']}, TLS 0x{audit['tls']['size']:x} align {audit['tls']['align']}, "
      f"{audit['imports']} imports from {audit['needed']}")
