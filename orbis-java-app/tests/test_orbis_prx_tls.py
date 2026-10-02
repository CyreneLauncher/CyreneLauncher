#!/usr/bin/env python3
"""Per-thread PRX TLS: host behavior plus an SDK link with the --prx-tls script.

The host run proves per-thread blocks, template initialisation (.tdata.*, .tbss.*,
local-dynamic) and release at thread exit. The SDK link proves the image bounds equal
the PRX's PT_TLS, .tbss is merged into the image, and every TLS access goes through the
module's own __tls_get_addr (no initial/local-exec relocations, no import).
"""
from pathlib import Path
import os
import re
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
sdk = Path(os.environ.get('ORBIS_SDK', '/home/wonder/ps4-payload-sdk'))
toolchain = Path(os.environ.get('ORBIS_TOOLCHAIN', '/home/wonder/PS4Toolchain'))
cc = os.environ.get('HOST_CC', 'clang')
EXEC_TLS = re.compile(r'R_X86_64_(GOTTPOFF|TPOFF32|TPOFF64|GOTPC32_TLSDESC|TLSDESC_CALL|TLSDESC)\b')


def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, text=True, **kwargs)


def readelf(*args):
    return run('llvm-readelf', '--wide', *args, stdout=subprocess.PIPE).stdout


def check_tls_imports(binary):
    relocations = readelf('-r', binary)
    assert not EXEC_TLS.search(relocations), 'exec-model TLS relocation in ' + str(binary)
    for line in readelf('--dyn-syms', binary).splitlines():
        if line.endswith(' __tls_get_addr'):
            assert ' UND ' not in line, '__tls_get_addr imported instead of module-local'


with tempfile.TemporaryDirectory() as directory:
    tmp = Path(directory)
    module = tmp / 'libprxtls.so'
    run(cc, '-shared', '-fPIC', '-fdata-sections', '-ftls-model=global-dynamic', '-O2', '-Wall', '-Werror',
        '-DORBIS_PRX_TLS_EXTERNAL_TEMPLATE', '-Wl,-Bsymbolic', '-pthread',
        root / 'tools/orbis_prx_tls.c', root / 'tests/testdata/orbis_prx_tls_module.c', '-o', module)
    check_tls_imports(module)
    assert 'R_X86_64_DTPMOD64' in readelf('-r', module), 'module did not use dynamic TLS'
    driver = tmp / 'driver'
    run(cc, '-O2', '-Wall', '-Werror', '-pthread', root / 'tests/testdata/orbis_prx_tls_driver.c',
        module, '-Wl,-rpath,' + str(tmp), '-o', driver)
    print(run(driver, stdout=subprocess.PIPE).stdout.strip())

    if not (sdk / 'bin/clang').exists() or not (toolchain / 'link.x').exists():
        print('SDK PRX TLS link check skipped: set ORBIS_SDK and ORBIS_TOOLCHAIN')
        sys.exit(0)
    script = tmp / 'prx-link.x'
    run(sys.executable, root / 'tools/prepare_jvm_link.py', toolchain / 'link.x', script, '--prx-tls')
    source = tmp / 'tls_user.c'
    source.write_text('__thread int initialised = 7;\n'
                      '__thread char block[100] = {1};\n'
                      '__thread long cleared[16];\n'
                      'static __thread int local = 3;\n'
                      'int *a(void) { return &initialised; } char *b(void) { return block; }\n'
                      'long *c(void) { return cleared; } int *d(void) { return &local; }\n')
    objects = []
    for name, path in (('tls_user', source), ('orbis_prx_tls', root / 'tools/orbis_prx_tls.c')):
        obj = tmp / (name + '.o')
        run(sdk / 'bin/clang', '--target=x86_64-pc-freebsd12-elf', '-fPIC', '-fdata-sections', '-O2',
            '-isysroot', toolchain, '-isystem', toolchain / 'include', '-c', path, '-o', obj)
        objects.append(obj)
    prx = tmp / 'libtlsuser.so'
    run(sdk / 'bin/ld.lld', '--shared', '--no-undefined', '-Bsymbolic', '-e', 'module_start', '--script', script,
        '-o', prx, *objects, toolchain / 'lib/crtlib.o', '-L' + str(toolchain / 'lib'),
        '--start-group', '-lc', '-lclang_rt.builtins-x86_64', '--end-group', '-lkernel')
    check_tls_imports(prx)
    bss_source = tmp / 'tbss_only.c'
    bss_source.write_text('__thread long only_cleared[4];\nlong *e(void) { return only_cleared; }\n')
    bss_object = tmp / 'tbss_only.o'
    run(sdk / 'bin/clang', '--target=x86_64-pc-freebsd12-elf', '-fPIC', '-fdata-sections', '-O2',
        '-isysroot', toolchain, '-isystem', toolchain / 'include', '-c', bss_source, '-o', bss_object)
    bss_prx = tmp / 'libtbssonly.so'
    run(sdk / 'bin/ld.lld', '--shared', '--no-undefined', '-Bsymbolic', '-e', 'module_start', '--script', script,
        '-o', bss_prx, bss_object, objects[1], toolchain / 'lib/crtlib.o', '-L' + str(toolchain / 'lib'),
        '--start-group', '-lc', '-lclang_rt.builtins-x86_64', '--end-group', '-lkernel')
    bss_tls = [line.split() for line in readelf('-l', bss_prx).splitlines() if line.strip().startswith('TLS ')]
    assert len(bss_tls) == 1 and bss_tls[0][4] == bss_tls[0][5] and int(bss_tls[0][4], 16) > 0, \
        '.tbss-only module needs an initialised TLS image: ' + str(bss_tls)

    tls_segments = [line.split() for line in readelf('-l', prx).splitlines() if line.strip().startswith('TLS ')]
    assert len(tls_segments) == 1, tls_segments
    _, _, vaddr, _, file_size, memory_size, *_, align = tls_segments[0]
    vaddr, file_size, memory_size, align = (int(v, 16) for v in (vaddr, file_size, memory_size, align))
    assert file_size == memory_size, '.tbss must be merged into the initialised image'
    assert align <= 64, 'PT_TLS alignment exceeds ORBIS_PRX_TLS_ALIGNMENT'
    symbols = {}
    for line in readelf('-s', prx).splitlines():
        fields = line.split()
        if fields and fields[-1] in ('__orbis_tls_image_start', '__orbis_tls_image_end'):
            symbols[fields[-1]] = (int(fields[1], 16), fields[6])
    assert symbols['__orbis_tls_image_start'][0] == vaddr, symbols
    assert symbols['__orbis_tls_image_end'][0] == vaddr + memory_size, symbols
    assert all(index != 'ABS' for _, index in symbols.values()), 'image bounds must be section-relative'
    sections = readelf('-S', prx)
    assert re.search(r'\.tls\s+PROGBITS', sections) and '.tdata' not in sections and '.tbss' not in sections
    print('PRX TLS SDK link passed: image 0x%x bytes at 0x%x, align %d' % (memory_size, vaddr, align))
