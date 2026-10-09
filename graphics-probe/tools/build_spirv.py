#!/usr/bin/env python3
"""Build libshaderc and libspirv-cross, the PRXs LWJGL 3.4.x's shaderc and spvc modules load (Minecraft 26.2+).

Minecraft 26.2 compiles its GLSL to SPIR-V with shaderc and, on OpenGL, turns the SPIR-V back into GLSL
with SPIRV-Cross. LWJGL has no JNI code for either: it resolves every function by name when a class
initialises (shaderc's C API, glslang's C interface and resource limits and SPIRV-Tools' optimizer from
libshaderc; SPIRV-Cross's C API from libspirv-cross), so the build fails unless every one is exported.
Sources are the commits each LWJGL release's own Linux binaries record (lwjgl/lwjgl-sources.lock.json), with
glslang, SPIRV-Tools and SPIRV-Headers at the revisions shaderc's DEPS names; built as static libraries
with CMake (no tests, executables or install) and linked whole with the shared Orbis PRX recipe.
"""
import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_stamp  # noqa: E402
import lwjgl_sources  # noqa: E402
import orbis_prx  # noqa: E402

ROOT = orbis_prx.ROOT
SHADERC_BINDINGS = ('Shaderc.java', 'GLSLang.java', 'GLSLangResourceLimits.java', 'SPVOptimizer.java')
SPVC_BINDINGS = ('Spvc.java',)


def required(lwjgl, module, files):
    names = []
    for name in files:
        text = (lwjgl / f'modules/lwjgl/{module}/src/generated/java/org/lwjgl/util/{module}' / name).read_text()
        names += re.findall(r'apiGetFunctionAddress\(\w+, "(\w+)"\)', text)
    if not names:
        raise SystemExit(f'no {module} functions found in LWJGL')
    return tuple(sorted(set(names)))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', default='/home/wonder/ps4-payload-sdk')
    parser.add_argument('--toolchain', default='/home/wonder/PS4Toolchain')
    parser.add_argument('--paid', default='0x3800000000000011')
    parser.add_argument('--downloads', default=str(ROOT / 'build/downloads'))
    parser.add_argument('--fetch', action='store_true', help='download the pinned archives if missing')
    parser.add_argument('--release', choices=('3.4.1', '3.4.3'), default='3.4.3')
    parser.add_argument('--output', help='default build/spirv-<release>')
    arguments = parser.parse_args()
    if arguments.output is None:
        arguments.output = str(ROOT / f'build/spirv-{arguments.release}')
    # 3.4.1's pins carry no suffix in the lock file; later releases' carry the release.
    suffix = '' if arguments.release == '3.4.1' else f'-{arguments.release}'
    out = Path(arguments.output).resolve()
    out.mkdir(parents=True, exist_ok=True)
    tools = orbis_prx.Toolchain(arguments.sdk, arguments.toolchain)
    toolchain = Path(arguments.toolchain)
    downloads = Path(arguments.downloads)
    compat = ROOT / 'build/sources/orbis-compat'

    cmake_version = subprocess.run(['cmake', '--version'], capture_output=True, text=True, check=True).stdout
    stamp = (orbis_prx.stamp_recipe(build_stamp.Stamp(out, 'spirv'), tools)
             .files(Path(__file__), Path(lwjgl_sources.__file__), ROOT / 'lwjgl/lwjgl-sources.lock.json',
                    compat / 'src/orbis_thread_atexit.c', compat / 'src/orbis_cxa_guard.c',
                    ROOT / 'lwjgl/openal/orbis_openal_tls_init.c')
             .value('cmake', cmake_version)
             .value('arguments', {k: v for k, v in vars(arguments).items() if k not in ('fetch', 'downloads')}))
    outputs = [out / f'{n}.{s}' for n in ('libshaderc', 'libspirv-cross') for s in ('so', 'prx')]
    if stamp.current(out / 'spirv-audit.json', *outputs):
        print('libshaderc, libspirv-cross: unchanged inputs, reusing the last build')
        return
    stamp.invalidate()

    def extract(key):
        return lwjgl_sources.extract(lwjgl_sources.archive(key + suffix, downloads, arguments.fetch),
                                     out / 'sources' / key)

    lwjgl = lwjgl_sources.extract(lwjgl_sources.archive(f'lwjgl-{arguments.release}', downloads, arguments.fetch),
                                  out / 'sources/lwjgl')
    shaderc_names = required(lwjgl, 'shaderc', SHADERC_BINDINGS)
    spvc_names = required(lwjgl, 'spvc', SPVC_BINDINGS)
    shaderc = extract('shaderc')
    for key, place in (('glslang', 'glslang'), ('spirv-tools', 'spirv-tools'), ('spirv-headers', 'spirv-headers')):
        target = shaderc / 'third_party' / place
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(extract(key), target, symlinks=True)
    # shaderc hands glslang a generator expression for its install switch, which if() reads as true,
    # and glslang's install export then rejects the SPIRV-Tools-opt target installs were skipped for.
    third_party = shaderc / 'third_party/CMakeLists.txt'
    text = third_party.read_text()
    install = '    set(GLSLANG_ENABLE_INSTALL $<NOT:${SKIP_GLSLANG_INSTALL}>)\n'
    if text.count(install) != 1:
        raise SystemExit('shaderc third_party/CMakeLists.txt: expected the glslang install switch')
    third_party.write_text(text.replace(install, '    set(GLSLANG_ENABLE_INSTALL OFF)\n'))
    spirv_cross = extract('spirv-cross')

    stubs = out / 'link-stubs'
    stubs.mkdir(exist_ok=True)
    for name in ('libdl.a', 'libatomic.a', 'libpthread.a', 'librt.a', 'libm.a'):
        subprocess.run(['llvm-ar', 'rc', str(stubs / name)], check=True)
    # SHADERC_SHAREDLIB/IMPLEMENTATION: shaderc marks its C API for export only in a shared build;
    # linked whole into a PRX here, it must be exported.
    cmake_toolchain = out / 'orbis-toolchain.cmake'
    cmake_toolchain.write_text(f'''set(CMAKE_SYSTEM_NAME FreeBSD)
set(CMAKE_SYSTEM_PROCESSOR x86_64)
set(CMAKE_C_COMPILER {tools.sdk}/bin/clang)
set(CMAKE_CXX_COMPILER {tools.sdk}/bin/clang++)
set(CMAKE_C_COMPILER_TARGET x86_64-pc-freebsd12-elf)
set(CMAKE_CXX_COMPILER_TARGET x86_64-pc-freebsd12-elf)
set(CMAKE_SYSROOT {toolchain})
set(CMAKE_C_FLAGS_INIT "-fPIC -D_GNU_SOURCE -fshort-wchar -isystem {toolchain}/include")
set(CMAKE_CXX_FLAGS_INIT "-fPIC -D_GNU_SOURCE -DSHADERC_SHAREDLIB -DSHADERC_IMPLEMENTATION -nostdinc++ -isystem {toolchain}/include/c++/v1 -isystem {toolchain}/include")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-fuse-ld=lld -nostdlib -L{toolchain}/lib -L{stubs} -Wl,--unresolved-symbols=ignore-all")
set(CMAKE_EXE_LINKER_FLAGS_INIT "-fuse-ld=lld -nostdlib -L{toolchain}/lib -L{stubs} -Wl,--unresolved-symbols=ignore-all")
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
''')
    generator = 'Ninja' if shutil.which('ninja') else 'Unix Makefiles'

    def cmake(name, source, options, targets):
        build = out / f'cmake-{name}'
        if build.exists():
            shutil.rmtree(build)
        subprocess.run(['cmake', '-S', str(source), '-B', str(build), '-G', generator,
                        f'-DCMAKE_TOOLCHAIN_FILE={cmake_toolchain}', '-DCMAKE_BUILD_TYPE=Release',
                        '-DBUILD_SHARED_LIBS=OFF', *[f'-D{k}={v}' for k, v in options.items()]],
                       check=True, stdout=(out / f'{name}-configure.log').open('w'), stderr=subprocess.STDOUT)
        subprocess.run(['cmake', '--build', str(build), '-j8', '--target', *targets], check=True,
                       stdout=(out / f'{name}-build.log').open('w'), stderr=subprocess.STDOUT)
        return build

    shaderc_build = cmake('shaderc', shaderc, {
        'SHADERC_SKIP_TESTS': 'ON', 'SHADERC_SKIP_EXAMPLES': 'ON', 'SHADERC_SKIP_COPYRIGHT_CHECK': 'ON',
        'SHADERC_SKIP_INSTALL': 'ON', 'SHADERC_ENABLE_WERROR_COMPILE': 'OFF', 'SPIRV_SKIP_EXECUTABLES': 'ON',
        'SPIRV_SKIP_TESTS': 'ON', 'SPIRV_WERROR': 'OFF', 'ENABLE_GLSLANG_BINARIES': 'OFF', 'GLSLANG_TESTS': 'OFF',
        'GLSLANG_ENABLE_INSTALL': 'OFF', 'SKIP_GLSLANG_INSTALL': 'ON', 'SKIP_SPIRV_TOOLS_INSTALL': 'ON',
        'ENABLE_OPT': 'ON'}, ['shaderc', 'glslang-default-resource-limits', 'SPIRV-Tools-opt'])
    spvc_build = cmake('spirv-cross', spirv_cross, {
        'SPIRV_CROSS_SHARED': 'OFF', 'SPIRV_CROSS_STATIC': 'ON', 'SPIRV_CROSS_CLI': 'OFF',
        'SPIRV_CROSS_ENABLE_TESTS': 'OFF', 'SPIRV_CROSS_ENABLE_C_API': 'ON', 'SPIRV_CROSS_SKIP_INSTALL': 'ON',
        'SPIRV_CROSS_FORCE_PIC': 'ON'}, ['spirv-cross-c'])

    # C++ runtime pieces every libc++ module of this recipe needs (as the OpenAL build does).
    atexit = tools.compile(compat / 'src/orbis_thread_atexit.c', out / 'orbis_thread_atexit.o', '-std=c11', '-O2')
    guard = tools.compile(compat / 'src/orbis_cxa_guard.c', out / 'orbis_cxa_guard.o', '-std=gnu11', '-O2')
    tls_init = tools.compile(ROOT / 'lwjgl/openal/orbis_openal_tls_init.c', out / 'orbis_tls_init.o', '-std=c11', '-O2')
    script, runtime = orbis_prx.runtime_objects(tools, out / 'runtime')
    summary = {'modules': {}}
    for name, build, names in (('libshaderc', shaderc_build, shaderc_names), ('libspirv-cross', spvc_build, spvc_names)):
        # Every object, not archives: the recipe hides archive symbols, and LWJGL needs these exported.
        objects = sorted(p for p in (build / 'CMakeFiles').parent.rglob('*.o')
                         if '/CMakeFiles/' in p.as_posix() and 'CompilerId' not in p.as_posix())
        elf = out / f'{name}.so'
        orbis_prx.link(tools, script, runtime, elf, [*objects, atexit, guard, tls_init])
        audit, _ = orbis_prx.audit(elf, allowed_needed=('libkernel.so',), jni_prefix=None, jni_count=None,
                                   require_onload=False, required_c_exports=names)
        audit['prx'] = str(orbis_prx.fself(tools, elf, arguments.paid))
        audit['lwjgl_required_functions'] = len(names)
        summary['modules'][name] = audit
        print(f"{name}: all {len(names)} functions LWJGL requires exported, {audit['imports']} imports, TLS {audit['tls']}")
    summary['sources'] = {k: lwjgl_sources.LOCK[k]['version'] for k in ('shaderc', 'glslang', 'spirv-tools', 'spirv-headers', 'spirv-cross')}
    (out / 'spirv-audit.json').write_text(json.dumps(summary, indent=2) + '\n')
    stamp.record()


if __name__ == '__main__':
    main()
