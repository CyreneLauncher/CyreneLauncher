#!/usr/bin/env python3
"""Build SDL3 as the libSDL3 PRX Controlify loads on Minecraft 26.1+.

Controlify 3.5 drives controllers through SDL3 alone (FFM bindings to SDL 3.4.12; its GLFW controller
manager is gone). This is the pinned SDL release (lwjgl/lwjgl-sources.lock.json) configured as a
FreeBSD-like Unix with its dummy video and audio drivers, joystick, gamepad and virtual joystick, and
nothing that needs a desktop (X11, Wayland, ALSA, PulseAudio, D-Bus, udev) or USB (HIDAPI, libusb):
the console's controller reaches SDL as a virtual gamepad the compatibility mod feeds
(lwjgl/sdl/orbis_sdl_gamepad.c, exporting orbis_sdl_gamepad_update). Linked with the
shared Orbis PRX recipe (orbis_prx.py); the build fails unless every function the official
libSDL3.so of that release exports is exported here too.
"""
import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_stamp  # noqa: E402
import lwjgl_sources  # noqa: E402
import orbis_prx  # noqa: E402

ROOT = orbis_prx.ROOT
OPTIONS = {
    'SDL_SHARED': 'ON', 'SDL_STATIC': 'OFF', 'SDL_TEST_LIBRARY': 'OFF', 'SDL_TESTS': 'OFF', 'SDL_EXAMPLES': 'OFF',
    'SDL_DISABLE_INSTALL': 'ON', 'SDL_UNIX_CONSOLE_BUILD': 'ON',
    'SDL_X11': 'OFF', 'SDL_WAYLAND': 'OFF', 'SDL_KMSDRM': 'OFF', 'SDL_OFFSCREEN': 'OFF',
    'SDL_OPENGL': 'OFF', 'SDL_OPENGLES': 'OFF', 'SDL_VULKAN': 'OFF', 'SDL_GPU': 'OFF', 'SDL_RENDER_GPU': 'OFF',
    'SDL_ALSA': 'OFF', 'SDL_PULSEAUDIO': 'OFF', 'SDL_PIPEWIRE': 'OFF', 'SDL_JACK': 'OFF', 'SDL_SNDIO': 'OFF',
    'SDL_OSS': 'OFF', 'SDL_DISKAUDIO': 'OFF',
    # SDL's CMake makes the virtual joystick depend on SDL_HIDAPI; its device backends stay off.
    'SDL_HIDAPI': 'ON', 'SDL_HIDAPI_JOYSTICK': 'OFF', 'SDL_HIDAPI_LIBUSB': 'OFF', 'SDL_LIBUSB': 'OFF',
    'SDL_DBUS': 'OFF', 'SDL_IBUS': 'OFF', 'SDL_LIBUDEV': 'OFF', 'SDL_LIBURING': 'OFF',
    'SDL_JOYSTICK': 'ON', 'SDL_VIRTUAL_JOYSTICK': 'ON', 'SDL_HAPTIC': 'ON', 'SDL_SENSOR': 'ON',
    'SDL_CAMERA': 'OFF', 'SDL_DIALOG': 'OFF', 'SDL_TRAY': 'OFF', 'SDL_POWER': 'OFF',
    'SDL_SYSTEM_ICONV': 'OFF', 'SDL_LIBICONV': 'OFF', 'SDL_PTHREADS': 'ON', 'SDL_CLOCK_GETTIME': 'ON',
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', default='/home/wonder/ps4-payload-sdk')
    parser.add_argument('--toolchain', default='/home/wonder/PS4Toolchain')
    parser.add_argument('--paid', default='0x3800000000000011')
    parser.add_argument('--downloads', default=str(ROOT / 'build/downloads'))
    parser.add_argument('--fetch', action='store_true', help='download the pinned archive if missing')
    parser.add_argument('--reference', help="the official libSDL3.so of the pinned release, whose exports must be met")
    parser.add_argument('--output', default=str(ROOT / 'build/sdl3'))
    arguments = parser.parse_args()
    out = Path(arguments.output).resolve()
    out.mkdir(parents=True, exist_ok=True)
    tools = orbis_prx.Toolchain(arguments.sdk, arguments.toolchain)
    toolchain = Path(arguments.toolchain)

    cmake_version = subprocess.run(['cmake', '--version'], capture_output=True, text=True, check=True).stdout
    stamp = (orbis_prx.stamp_recipe(build_stamp.Stamp(out, 'sdl3'), tools)
             .files(Path(__file__), Path(lwjgl_sources.__file__), ROOT / 'lwjgl/lwjgl-sources.lock.json',
                    ROOT / 'lwjgl/sdl/orbis_sdl_gamepad.c')
             .value('cmake', cmake_version)
             .value('arguments', {k: v for k, v in vars(arguments).items() if k not in ('fetch', 'downloads')}))
    if stamp.current(out / 'sdl3-audit.json', out / 'libSDL3.so', out / 'libSDL3.prx'):
        print('libSDL3: unchanged inputs, reusing the last build')
        return
    stamp.invalidate()

    source = lwjgl_sources.extract(lwjgl_sources.archive('sdl3', Path(arguments.downloads), arguments.fetch),
                                   out / 'sources')
    # libkernel's pthread rwlocks do not match musl's pthread_rwlock_t (SDL's environment lock read a
    # garbage pointer on the console), and musl's sem_* are FreeBSD ksem syscalls: SDL's portable
    # read-write lock and semaphore, built on its pthread mutex and condition variable, replace them.
    for name in ('SDL_sysrwlock.c', 'SDL_syssem.c'):
        (source / 'src/thread/pthread' / name).write_text(f'#include "../generic/{name}"\n')
    # musl's pthread_mutexattr_t is 4 bytes; libkernel's (FreeBSD's) is a pointer that
    # pthread_mutexattr_init writes over the stack variable next to it, and musl's
    # PTHREAD_MUTEX_RECURSIVE (1) is FreeBSD's ERRORCHECK. The attribute gets room for the pointer
    # and SDL keeps its own recursion count (FAKE_RECURSIVE_MUTEX), so no mutex type is set.
    mutex = source / 'src/thread/pthread/SDL_sysmutex.c'
    text = mutex.read_text()
    replacements = (('    pthread_mutexattr_t attr;\n',
                     '    union { pthread_mutexattr_t attr; void *orbis_pointer[2]; } orbis_attr;  // libkernel: a pointer\n'),
                    ('pthread_mutexattr_init(&attr)', 'pthread_mutexattr_init(&orbis_attr.attr)'),
                    ('pthread_mutex_init(&mutex->id, &attr)', 'pthread_mutex_init(&mutex->id, &orbis_attr.attr)'),
                    ('pthread_mutexattr_destroy(&attr)', 'pthread_mutexattr_destroy(&orbis_attr.attr)'))
    for before, after in replacements:
        if text.count(before) != 1:
            raise SystemExit(f'SDL_sysmutex.c: expected one {before!r}')
        text = text.replace(before, after)
    mutex.write_text(text)
    stubs = out / 'link-stubs'
    stubs.mkdir(exist_ok=True)
    for name in ('libdl.a', 'libatomic.a', 'libm.a', 'libpthread.a', 'librt.a'):
        subprocess.run(['llvm-ar', 'rc', str(stubs / name)], check=True)
    # The sysroot is musl with the console's 2-byte wchar_t (-fshort-wchar keeps the compiler's
    # __SIZEOF_WCHAR_T__ in step): FreeBSD headers SDL includes for this target map onto musl's.
    shim = out / 'include-shim'
    (shim / 'sys').mkdir(parents=True, exist_ok=True)
    (shim / 'sys/endian.h').write_text('#pragma once\n#include <endian.h>\n')
    # libkernel has FreeBSD's sysctl; SDL asks it for the process path and the CPU and memory sizes.
    (shim / 'sys/sysctl.h').write_text('''#pragma once
#include <stddef.h>
#define CTL_KERN 1
#define CTL_HW 6
#define KERN_PROC 14
#define KERN_PROC_PATHNAME 12
#define HW_NCPU 3
#define HW_PHYSMEM 5
int sysctl(const int *name, unsigned int namelen, void *oldp, size_t *oldlenp, const void *newp, size_t newlen);
int sysctlbyname(const char *name, void *oldp, size_t *oldlenp, const void *newp, size_t newlen);
''')
    cmake_toolchain = out / 'orbis-toolchain.cmake'
    cmake_toolchain.write_text(f'''set(CMAKE_SYSTEM_NAME FreeBSD)
set(CMAKE_SYSTEM_PROCESSOR x86_64)
set(CMAKE_C_COMPILER {tools.sdk}/bin/clang)
set(CMAKE_C_COMPILER_TARGET x86_64-pc-freebsd12-elf)
set(CMAKE_SYSROOT {toolchain})
set(CMAKE_C_FLAGS_INIT "-fPIC -fshort-wchar -isystem {shim} -isystem {toolchain}/include")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-fuse-ld=lld -nostdlib -L{toolchain}/lib -L{stubs} -Wl,--unresolved-symbols=ignore-all")
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
''')
    build = out / 'cmake-build'
    if build.exists():
        shutil.rmtree(build)
    subprocess.run(['cmake', '-S', str(source), '-B', str(build), '-G', 'Ninja' if shutil.which('ninja') else 'Unix Makefiles',
                    f'-DCMAKE_TOOLCHAIN_FILE={cmake_toolchain}', '-DCMAKE_BUILD_TYPE=Release',
                    *[f'-D{k}={v}' for k, v in OPTIONS.items()]],
                   check=True, stdout=(out / 'configure.log').open('w'), stderr=subprocess.STDOUT)
    config = build / 'include-config-release/build_config/SDL_build_config.h'
    text = config.read_text()
    if text.count('#define SDL_THREAD_PTHREAD_RECURSIVE_MUTEX 1\n') != 1:
        raise SystemExit('SDL_build_config.h: expected SDL_THREAD_PTHREAD_RECURSIVE_MUTEX')
    # SDL's CMake drops options whose dependencies are off without failing; the controller needs this one.
    if '#define SDL_JOYSTICK_VIRTUAL 1' not in text:
        raise SystemExit('SDL was configured without its virtual-joystick driver')
    config.write_text(text.replace('#define SDL_THREAD_PTHREAD_RECURSIVE_MUTEX 1\n',
                                   '/* SDL_THREAD_PTHREAD_RECURSIVE_MUTEX: off, see build_sdl3.py */\n'))
    subprocess.run(['cmake', '--build', str(build), '-j8'], check=True,
                   stdout=(out / 'build.log').open('w'), stderr=subprocess.STDOUT)

    objects = sorted((build / 'CMakeFiles/SDL3-shared.dir').rglob('*.o'))
    if not objects:
        raise SystemExit('no SDL3 objects were built')
    # The console's controller as a virtual gamepad (lwjgl/sdl/orbis_sdl_gamepad.c), fed by the compat mod.
    objects.append(tools.compile(ROOT / 'lwjgl/sdl/orbis_sdl_gamepad.c', out / 'orbis_sdl_gamepad.o', '-std=gnu11', '-O2',
                                 '-fshort-wchar', '-isystem', out / 'include-shim', '-I', source / 'include',
                                 '-I', build / 'include-config-release', '-I', build / 'include'))
    required = ()
    if arguments.reference:
        table = subprocess.run(['llvm-readelf', '--dyn-syms', '--wide', arguments.reference],
                               capture_output=True, text=True, check=True).stdout
        required = tuple(sorted({line.split()[7].split('@')[0] for line in table.splitlines()
                                 if len(line.split()) >= 8 and line.split()[3] == 'FUNC'
                                 and line.split()[6] != 'UND' and line.split()[7].startswith('SDL_')}))
    script, runtime = orbis_prx.runtime_objects(tools, out / 'runtime')
    elf = out / 'libSDL3.so'
    orbis_prx.link(tools, script, runtime, elf, objects)
    audit, _ = orbis_prx.audit(elf, allowed_needed=('libkernel.so',), jni_prefix=None, jni_count=None,
                               require_onload=False, required_c_exports=(required or ('SDL_Init', 'SDL_AttachVirtualJoystick')) + ('orbis_sdl_gamepad_update', 'orbis_sdl_gamepad_id'))
    audit['prx'] = str(orbis_prx.fself(tools, elf, arguments.paid))
    summary = {'sdl3': lwjgl_sources.LOCK['sdl3']['version'], 'source_sha256': lwjgl_sources.LOCK['sdl3']['sha256'],
               'reference_functions': len(required), 'options': OPTIONS, 'module': audit}
    (out / 'sdl3-audit.json').write_text(json.dumps(summary, indent=2) + '\n')
    stamp.record()
    print(f"libSDL3: SDL {summary['sdl3']}, {len(required)} SDL functions exported"
          f"{f' (all {len(required)} of the official library)' if required else ''}, "
          f"{audit['imports']} imports, TLS {audit['tls']}")


if __name__ == '__main__':
    main()
