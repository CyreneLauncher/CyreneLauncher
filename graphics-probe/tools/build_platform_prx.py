#!/usr/bin/env python3
"""Link the Mesa platform bridge into a JVM-loadable PRX and audit it.

Uses the shared Orbis JNI module recipe (orbis_prx.py) with the Mesa/compat/libc16
inputs in the probe's console-proven order. Compat deliberately overrides libc
members, hence --allow-multiple-definition and the kit's wchar extraction audit.
"""
import argparse
import json
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
bridge = tools.compile(ROOT / f'lwjgl/platform/{NAME}.c', out / f'{NAME}.o',
                       '-fdata-sections', '-O2', '-Wall', '-Werror', '-D__ORBIS__', '-DPS4', '-D_BSD_SOURCE=1',
                       '-isystem', compat / 'include', '-I', sources / 'mesa/include',
                       '-I', arguments.jni_headers, '-I', Path(arguments.jni_headers) / arguments.jni_platform)
elf = out / f'lib{NAME}.so'
why = out / 'why-extract.txt'
orbis_prx.link(tools, script, runtime, elf,
               [bridge, '--start-group', egl, gallium, '--whole-archive', radv, '--no-whole-archive', zlib,
                '--end-group', '--whole-archive', compat_archive, '--no-whole-archive', *libc16],
               system_libraries=('SceGnmDriver', 'SceVideoOut'),
               # Mesa's thread creation passes through the bridge's diagnostic wrapper.
               extra=('--allow-multiple-definition', '--wrap=pthread_create'), why_extract=why)
wchar = orbis_prx.run('sh', kit / 'scripts/ps4/check-wchar-link.sh', why, stdout=subprocess.PIPE).stdout
(out / 'wchar-audit.log').write_text(wchar)

audit, natives = orbis_prx.audit(elf, allowed_needed=('libkernel.so', 'libSceGnmDriver.so', 'libSceVideoOut.so'),
                                 jni_prefix='Java_orbis_graphics_NativePlatform_', jni_count=12)
if audit['tls'] is None:
    raise SystemExit('platform PRX has no TLS image; Mesa TLS was expected')
audit['jni_natives'] = natives
audit['prx'] = str(orbis_prx.fself(tools, elf, arguments.paid))
(out / 'platform-audit.json').write_text(json.dumps(audit, indent=2) + '\n')
print(f"platform PRX: {audit['prx']}, TLS 0x{audit['tls']['size']:x} align {audit['tls']['align']}, "
      f"{audit['imports']} imports from {audit['needed']}")
