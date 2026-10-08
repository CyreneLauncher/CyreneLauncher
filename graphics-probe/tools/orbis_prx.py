"""Orbis JNI module recipe shared by the graphics PRXs.

Mirrors the JDK ports' orbis-export/tools/build_native_modules.py so the JDK dlopen wrapper can load
the result: runtime objects exporting orbis_configure_runtime_v2/orbis_run_initializers,
a private environment, wrapped dlopen/_exit, -Bsymbolic, then create-fself. Uses
orbis_prx_tls.c (per-thread TLS) and the --prx-tls link script instead of the JDK's
shared TLS block. Archive symbols stay internal (--exclude-libs=ALL).
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT.parent / 'orbis-java-app'
EXEC_TLS = re.compile(r'R_X86_64_(GOTTPOFF|TPOFF32|TPOFF64|GOTPC32_TLSDESC|TLSDESC_CALL|TLSDESC)\b')
REQUIRED_EXPORTS = ('orbis_configure_runtime_v2', 'orbis_run_initializers')
WRAPPED = ('dlopen', 'dlsym', 'dlclose', 'dlerror', 'sceKernelDebugOutText', '_exit')
ENVIRONMENT_MEMBERS = ('getenv', 'setenv', 'unsetenv', 'putenv', '__environ', 'strdup')


def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, text=True, **kwargs)


class Toolchain:
    def __init__(self, sdk, toolchain):
        self.sdk = Path(sdk).resolve()
        self.root = Path(toolchain).resolve()
        self.target = ['--target=x86_64-pc-freebsd12-elf', '-fPIC', '-isysroot', str(self.root)]

    def compile(self, source, output, *flags):
        run(self.sdk / 'bin/clang', *self.target, *flags, '-isystem', self.root / 'include',
            '-c', source, '-o', output)
        return output


def stamp_recipe(stamp, tools):
    """Adds what every module built with this recipe reads to a build_stamp.Stamp."""
    return (stamp.files(Path(__file__), RUNNER / 'tools/prepare_jvm_link.py',
                        *[RUNNER / f'tools/{name}.c' for name in
                          ('native_dlfcn', 'orbis_prx_tls', 'orbis_initializers', 'orbis_runtime')],
                        *sorted((RUNNER / 'tools').glob('*.h')))
            .stats(tools.sdk / 'bin', tools.root / 'include', tools.root / 'lib', tools.root / 'link.x',
                   tools.root / 'bin/linux/create-fself')
            .value('recipe', [str(tools.sdk), str(tools.root), WRAPPED, ENVIRONMENT_MEMBERS]))


def runtime_objects(tools, out):
    """Link script plus the objects every JVM-loadable module carries."""
    out.mkdir(parents=True, exist_ok=True)
    script = out / 'prx-link.x'
    run(sys.executable, RUNNER / 'tools/prepare_jvm_link.py', tools.root / 'link.x', script, '--prx-tls')
    objects = [tools.compile(RUNNER / f'tools/{name}.c', out / f'{name}.o')
               for name in ('native_dlfcn', 'orbis_prx_tls', 'orbis_initializers', 'orbis_runtime')]
    for member in ENVIRONMENT_MEMBERS:
        obj = out / f'environment_{member}.o'
        with obj.open('wb') as extracted:
            subprocess.run(['llvm-ar', 'p', str(tools.root / 'lib/libc.a'), member + '.lo'],
                           stdout=extracted, check=True)
        objects.append(obj)
    return script, objects


def link(tools, script, runtime, elf, inputs, *, system_libraries=(), extra=(), why_extract=None):
    """inputs: module objects and archives, placed before the runtime and SDK libraries."""
    run(tools.sdk / 'bin/ld.lld', '--shared', '--no-undefined', '-soname', elf.name, '-e', 'module_start',
        '-Bsymbolic', '--script', script, '--eh-frame-hdr', '--exclude-libs=ALL', *extra,
        *(['--why-extract=' + str(why_extract)] if why_extract else []),
        '-o', elf, *inputs, *runtime, tools.root / 'lib/crtlib.o',
        *['--wrap=' + fn for fn in WRAPPED],
        '-L' + str(tools.root / 'lib'),
        '--start-group', '-lc++', '-lc++abi', '-lc', '-lm', '-lclang_rt.builtins-x86_64', '--end-group',
        '-lkernel', *['-l' + lib for lib in system_libraries])


def readelf(elf, *args):
    return run('llvm-readelf', '--wide', *args, elf, stdout=subprocess.PIPE).stdout


def audit(elf, *, allowed_needed, jni_prefix, jni_count, require_onload=True, required_c_exports=()):
    """Checks shared by all graphics PRXs; returns a JSON-ready summary."""
    if EXEC_TLS.search(readelf(elf, '-r')):
        raise SystemExit(f'{elf.name}: exec-model TLS relocation')
    exports, imports = set(), set()
    for line in readelf(elf, '--dyn-syms').splitlines():
        fields = line.split()
        if len(fields) < 8 or not fields[0].endswith(':'):
            continue
        (imports if fields[6] == 'UND' else exports).add(fields[7].split('@')[0])
    imports.discard('')
    if '__tls_get_addr' in imports:
        raise SystemExit(f'{elf.name}: __tls_get_addr must be module-local')
    missing = [name for name in REQUIRED_EXPORTS + (('JNI_OnLoad',) if require_onload else ())
               if name not in exports]
    missing.extend(name for name in required_c_exports if name not in exports)
    natives = sorted(name for name in exports if name.startswith(jni_prefix)) if jni_prefix else []
    if missing or (jni_prefix is not None and ((jni_count is not None and len(natives) != jni_count) or not natives)):
        raise SystemExit(f'{elf.name}: missing exports {missing} or {len(natives)} {jni_prefix}* natives')
    needed = set(re.findall(r'\(NEEDED\)\s+Shared library: \[([^\]]+)\]', readelf(elf, '-d')))
    if not needed <= set(allowed_needed):
        raise SystemExit(f'{elf.name}: unexpected dependencies {sorted(needed - set(allowed_needed))}')
    tls = [line.split() for line in readelf(elf, '-l').splitlines() if line.strip().startswith('TLS ')]
    # Inventory the actual API namespace, including exports beyond the required floor.
    # Derive prefixes from required C functions so other audit callers keep their contract.
    prefixes = {match.group(1) for name in required_c_exports
                if (match := re.match(r'([a-z]+)[A-Z]', name))}
    c_exports = sorted(name for name in exports
                       if any(re.match(re.escape(prefix) + r'[A-Z]', name) for prefix in prefixes))
    summary = {'elf': str(elf), 'needed': sorted(needed), 'imports': len(imports),
               'jni_natives': len(natives), 'jni_exports': natives,
               'c_exports': c_exports, 'required_c_exports': sorted(required_c_exports),
               'import_symbols': sorted(imports),
               'size': elf.stat().st_size, 'tls': None}
    if tls:
        if len(tls) != 1:
            raise SystemExit(f'{elf.name}: expected one PT_TLS segment')
        vaddr, file_size, memory_size, align = (int(tls[0][i], 16) for i in (2, 4, 5, -1))
        bounds = {}
        for line in readelf(elf, '-s').splitlines():
            fields = line.split()
            if fields and fields[-1] in ('__orbis_tls_image_start', '__orbis_tls_image_end'):
                bounds[fields[-1]] = int(fields[1], 16)
        if (file_size != memory_size or align > 64 or bounds.get('__orbis_tls_image_start') != vaddr
                or bounds.get('__orbis_tls_image_end') != vaddr + memory_size):
            raise SystemExit(f'{elf.name}: TLS image layout mismatch {tls} {bounds}')
        summary['tls'] = {'vaddr': vaddr, 'size': memory_size, 'align': align}
    return summary, natives


def fself(tools, elf, paid):
    name = elf.stem
    prx = elf.with_suffix('.prx')
    run(tools.root / 'bin/linux/create-fself', '-in=' + str(elf), '-out=' + str(elf.with_suffix('.oelf')),
        '--lib=' + str(prx), '--libname=' + name, '--paid', paid)
    if prx.read_bytes()[:4] != b'O\x15=\x1d':
        raise SystemExit(f'create-fself did not produce a SELF for {elf.name}')
    return prx
