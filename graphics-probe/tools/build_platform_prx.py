#!/usr/bin/env python3
"""Link the Mesa platform bridge into a JVM-loadable PRX and audit it.

The module follows the JDK native-module recipe (orbis-java-app/tools/
build_native_modules.py): runtime objects the JDK dlopen wrapper requires, a private
environment, wrapped dlopen/_exit, -Bsymbolic and create-fself. It differs in two ways:
orbis_prx_tls.c replaces the shared TLS block with per-thread blocks for Mesa, and the
Mesa/compat/libc16 inputs follow the probe's console-proven order.
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT.parent / 'orbis-java-app'
EXEC_TLS = re.compile(r'R_X86_64_(GOTTPOFF|TPOFF32|TPOFF64|GOTPC32_TLSDESC|TLSDESC_CALL|TLSDESC)\b')
REQUIRED_EXPORTS = ('orbis_configure_runtime_v2', 'orbis_run_initializers', 'JNI_OnLoad')
ALLOWED_NEEDED = {'libkernel.so', 'libSceGnmDriver.so', 'libSceVideoOut.so'}
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
sdk, toolchain, build, out = (Path(p).resolve() for p in
                              (arguments.sdk, arguments.toolchain, arguments.build, arguments.output))
sources = ROOT / 'build/sources'
compat = sources / 'orbis-compat'
kit = sources / 'orbis-porting-kit'
out.mkdir(parents=True, exist_ok=True)


def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, text=True, **kwargs)


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

target = ['--target=x86_64-pc-freebsd12-elf', '-fPIC', '-isysroot', toolchain]
script = out / 'prx-link.x'
run(sys.executable, RUNNER / 'tools/prepare_jvm_link.py', toolchain / 'link.x', script, '--prx-tls')

objects = []
for name in ('native_dlfcn', 'orbis_prx_tls', 'orbis_initializers', 'orbis_runtime'):
    obj = out / f'{name}.o'
    run(sdk / 'bin/clang', *target, '-isystem', toolchain / 'include', '-c',
        RUNNER / f'tools/{name}.c', '-o', obj)
    objects.append(obj)
# One environment per PRX, as for JDK modules: Mesa's getenv reads what nInitialize seeds.
for member in ('getenv', 'setenv', 'unsetenv', 'putenv', '__environ', 'strdup'):
    obj = out / f'environment_{member}.o'
    with obj.open('wb') as extracted:
        subprocess.run(['llvm-ar', 'p', str(toolchain / 'lib/libc.a'), member + '.lo'],
                       stdout=extracted, check=True)
    objects.append(obj)
bridge = out / f'{NAME}.o'
run(sdk / 'bin/clang', *target, '-fdata-sections', '-O2', '-Wall', '-Werror',
    '-D__ORBIS__', '-DPS4', '-D_BSD_SOURCE=1',
    '-isystem', compat / 'include', '-isystem', toolchain / 'include',
    '-I', sources / 'mesa/include', '-I', arguments.jni_headers,
    '-I', Path(arguments.jni_headers) / arguments.jni_platform,
    '-c', ROOT / f'lwjgl/platform/{NAME}.c', '-o', bridge)

elf = out / f'lib{NAME}.so'
why = out / 'why-extract.txt'
run(sdk / 'bin/ld.lld', '--shared', '--no-undefined', '-soname', elf.name, '-e', 'module_start',
    '-Bsymbolic', '--script', script, '--eh-frame-hdr', '--allow-multiple-definition',
    # Export only this module's interface; Mesa, compat and libc stay internal.
    '--exclude-libs=ALL',
    '--why-extract=' + str(why), '-o', elf,
    bridge, *objects, toolchain / 'lib/crtlib.o',
    *['--wrap=' + fn for fn in ('dlopen', 'dlsym', 'dlclose', 'dlerror', 'sceKernelDebugOutText', '_exit')],
    '--start-group', egl, gallium, '--whole-archive', radv, '--no-whole-archive', zlib, '--end-group',
    '--whole-archive', compat_archive, '--no-whole-archive', *libc16,
    '-L' + str(toolchain / 'lib'),
    '--start-group', '-lc++', '-lc++abi', '-lc', '-lm', '-lclang_rt.builtins-x86_64', '--end-group',
    '-lkernel', '-lSceGnmDriver', '-lSceVideoOut')
wchar = run('sh', kit / 'scripts/ps4/check-wchar-link.sh', why, stdout=subprocess.PIPE).stdout
(out / 'wchar-audit.log').write_text(wchar)


def readelf(*args):
    return run('llvm-readelf', '--wide', *args, elf, stdout=subprocess.PIPE).stdout


audit = {'elf': str(elf)}
relocations = readelf('-r')
if EXEC_TLS.search(relocations):
    raise SystemExit('exec-model TLS relocation in platform PRX')
dynamic = readelf('--dyn-syms')
exports, imports = set(), set()
for line in dynamic.splitlines():
    fields = line.split()
    if len(fields) < 8 or not fields[0].endswith(':'):
        continue
    (imports if fields[6] == 'UND' else exports).add(fields[7].split('@')[0])
imports.discard('')
if '__tls_get_addr' in imports:
    raise SystemExit('__tls_get_addr must be module-local')
missing = [name for name in REQUIRED_EXPORTS if name not in exports]
natives = sorted(name for name in exports if name.startswith('Java_orbis_graphics_NativePlatform_'))
if missing or len(natives) != 11:
    raise SystemExit(f'missing exports {missing} or unexpected JNI natives {natives}')
needed = set(re.findall(r'\(NEEDED\)\s+Shared library: \[([^\]]+)\]', readelf('-d')))
if not needed <= ALLOWED_NEEDED:
    raise SystemExit(f'unexpected dynamic dependencies {sorted(needed - ALLOWED_NEEDED)}')
tls = [line.split() for line in readelf('-l').splitlines() if line.strip().startswith('TLS ')]
if len(tls) != 1:
    raise SystemExit(f'expected one PT_TLS segment: {tls}')
tls_vaddr, tls_file, tls_memory, tls_align = (int(tls[0][i], 16) for i in (2, 4, 5, -1))
bounds = {}
for line in readelf('-s').splitlines():
    fields = line.split()
    if fields and fields[-1] in ('__orbis_tls_image_start', '__orbis_tls_image_end'):
        bounds[fields[-1]] = int(fields[1], 16)
if (tls_file != tls_memory or tls_align > 64 or bounds.get('__orbis_tls_image_start') != tls_vaddr
        or bounds.get('__orbis_tls_image_end') != tls_vaddr + tls_memory):
    raise SystemExit(f'TLS image layout mismatch: segment {tls} bounds {bounds}')
audit.update(needed=sorted(needed), imports=len(imports), jni_natives=natives,
             tls={'vaddr': tls_vaddr, 'size': tls_memory, 'align': tls_align},
             size=elf.stat().st_size)

prx = out / f'lib{NAME}.prx'
run(toolchain / 'bin/linux/create-fself', '-in=' + str(elf), '-out=' + str(out / f'lib{NAME}.oelf'),
    '--lib=' + str(prx), '--libname=lib' + NAME, '--paid', arguments.paid)
if prx.read_bytes()[:4] != b'O\x15=\x1d':
    raise SystemExit('create-fself did not produce a SELF')
audit['prx'] = str(prx)
(out / 'platform-audit.json').write_text(json.dumps(audit, indent=2) + '\n')
print(f'platform PRX: {prx} ({prx.stat().st_size} bytes), TLS 0x{tls_memory:x} align {tls_align}, '
      f'{len(imports)} imports from {sorted(needed)}')
