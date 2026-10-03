#!/usr/bin/env python3
"""Build OpenAL Soft with an Orbis AudioOut backend as the libopenal PRX LWJGL loads.

The pinned OpenAL Soft source (lwjgl/lwjgl-sources.lock.json) is configured with CMake for the
Orbis target with every host audio backend off, plus lwjgl/openal/orbis_audioout.cpp registered
ahead of the Null backend. It is configured as a shared library so the AL/ALC entry points are
compiled with export visibility; CMake's own final link is a throwaway, and the module is linked
from its objects with the shared Orbis PRX recipe (orbis_prx.py).
"""
import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lwjgl_sources  # noqa: E402
import orbis_prx  # noqa: E402

ROOT = orbis_prx.ROOT
# The ALC 1.0 core LWJGL's ALCCapabilities resolves, plus AL lookup; the full API is exported.
CORE = ('alcOpenDevice', 'alcCloseDevice', 'alcCreateContext', 'alcMakeContextCurrent',
        'alcProcessContext', 'alcSuspendContext', 'alcDestroyContext', 'alcGetCurrentContext',
        'alcGetContextsDevice', 'alcIsExtensionPresent', 'alcGetProcAddress', 'alcGetEnumValue',
        'alcGetError', 'alcGetString', 'alcGetIntegerv', 'alGetProcAddress', 'alGenSources',
        'alSourcePlay', 'alBufferData')
HOST_BACKENDS = ('PIPEWIRE', 'PULSEAUDIO', 'ALSA', 'OSS', 'SOLARIS', 'SNDIO', 'JACK', 'PORTAUDIO',
                 'SDL2', 'WAVE')


def patch(path: Path, anchor: str, replacement: str) -> None:
    text = path.read_text()
    if text.count(anchor) != 1:
        raise SystemExit(f'{path.name}: expected one occurrence of {anchor!r}')
    path.write_text(text.replace(anchor, replacement))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', default='/home/wonder/ps4-payload-sdk')
    parser.add_argument('--toolchain', default='/home/wonder/PS4Toolchain')
    parser.add_argument('--paid', default='0x3800000000000011')
    parser.add_argument('--downloads', default=str(ROOT / 'build/downloads'))
    parser.add_argument('--fetch', action='store_true', help='download the pinned archive if missing')
    parser.add_argument('--output', default=str(ROOT / 'build/openal'))
    arguments = parser.parse_args()
    out = Path(arguments.output).resolve()
    out.mkdir(parents=True, exist_ok=True)
    tools = orbis_prx.Toolchain(arguments.sdk, arguments.toolchain)

    archive = lwjgl_sources.archive('openal-soft', Path(arguments.downloads), arguments.fetch)
    source = lwjgl_sources.extract(archive, out / 'sources')
    backend = source / 'alc/backends'
    for name in ('orbis_audioout.cpp', 'orbis_audioout.h'):
        shutil.copyfile(ROOT / 'lwjgl/openal' / name, backend / name)
    patch(source / 'alc/alc.cpp', '#include "backends/null.h"\n',
          '#include "backends/null.h"\n#ifdef HAVE_ORBIS\n#include "backends/orbis_audioout.h"\n#endif\n')
    patch(source / 'alc/alc.cpp', '    { "null", NullBackendFactory::getFactory },\n',
          '#ifdef HAVE_ORBIS\n    { "orbis", OrbisBackendFactory::getFactory },\n#endif\n'
          '    { "null", NullBackendFactory::getFactory },\n')
    patch(source / 'CMakeLists.txt', '    alc/backends/null.cpp\n',
          '    alc/backends/null.cpp\n    alc/backends/orbis_audioout.cpp\n')
    # al::semaphore over libkernel's pthread mutex/condvar: the SDK libc's sem_* are FreeBSD
    # ksem syscalls, and a failing sem_wait would spin the event thread while a failing
    # sem_post throws out of context teardown.
    patch(source / 'common/threads.h', '#else\n    using native_type = sem_t;\n#endif\n',
          '#elif defined(HAVE_ORBIS)\n    struct native_type { pthread_mutex_t lock; pthread_cond_t cond; unsigned int count; };\n'
          '#else\n    using native_type = sem_t;\n#endif\n')
    patch(source / 'common/threads.h', '#elif !defined(_WIN32)\n#include <semaphore.h>\n#endif\n',
          '#elif defined(HAVE_ORBIS)\n#include <pthread.h>\n#elif !defined(_WIN32)\n#include <semaphore.h>\n#endif\n')
    patch(source / 'common/threads.cpp', '#else /* !__APPLE__ */\n',
          '#elif defined(HAVE_ORBIS)\n\n' + (ROOT / 'lwjgl/openal/orbis_semaphore.inc').read_text()
          + '\n#else /* !__APPLE__ */\n')
    # Context teardown must not leave the event thread joinable: an exception out of the
    # wake-up or join would unwind into ~ContextBase and std::terminate the process.
    patch(source / 'al/event.cpp', '''    ctx->mEventSem.post();
    if(ctx->mEventThread.joinable())
        ctx->mEventThread.join();
''', '''    try {
        ctx->mEventSem.post();
        if(ctx->mEventThread.joinable())
            ctx->mEventThread.join();
    }
    catch(std::exception& e) {
        ERR("Failed to stop event thread: %s\\n", e.what());
        if(ctx->mEventThread.joinable())
            ctx->mEventThread.detach();
    }
''')

    toolchain = Path(arguments.toolchain)
    #: OpenAL Soft's thread_locals are constant-initialised, so the
    # compiler's weak _ZTH init hooks are never defined, and create-fself cannot place an
    # undefined symbol in any library.
    # Empty archives satisfy the -ldl/-latomic CMake adds to its throwaway shared link.
    stubs = out / 'link-stubs'
    stubs.mkdir(exist_ok=True)
    for name in ('libdl.a', 'libatomic.a'):
        subprocess.run(['llvm-ar', 'rc', str(stubs / name)], check=True)
    cmake_toolchain = out / 'orbis-toolchain.cmake'
    cmake_toolchain.write_text(f'''set(CMAKE_SYSTEM_NAME FreeBSD)
set(CMAKE_SYSTEM_PROCESSOR x86_64)
set(CMAKE_C_COMPILER {tools.sdk}/bin/clang)
set(CMAKE_CXX_COMPILER {tools.sdk}/bin/clang++)
set(CMAKE_C_COMPILER_TARGET x86_64-pc-freebsd12-elf)
set(CMAKE_CXX_COMPILER_TARGET x86_64-pc-freebsd12-elf)
set(CMAKE_SYSROOT {toolchain})
set(CMAKE_C_FLAGS_INIT "-fPIC -isystem {toolchain}/include")
set(CMAKE_CXX_FLAGS_INIT "-fPIC -DHAVE_ORBIS=1 -nostdinc++ -isystem {toolchain}/include/c++/v1 -isystem {toolchain}/include")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-fuse-ld=lld -nostdlib -L{toolchain}/lib -L{stubs} -Wl,--unresolved-symbols=ignore-all")
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
''')
    build = out / 'cmake-build'
    if build.exists():
        shutil.rmtree(build)
    subprocess.run(['cmake', '-S', str(source), '-B', str(build), '-DCMAKE_TOOLCHAIN_FILE=' + str(cmake_toolchain),
                    '-DCMAKE_BUILD_TYPE=Release', '-DLIBTYPE=SHARED', '-DALSOFT_UTILS=OFF',
                    '-DALSOFT_EXAMPLES=OFF', '-DALSOFT_INSTALL=OFF', '-DALSOFT_NO_CONFIG_UTIL=ON',
                    '-DALSOFT_EMBED_HRTF_DATA=ON', '-DALSOFT_REQUIRE_SSE2=ON',
                    *(f'-DALSOFT_BACKEND_{name}=OFF' for name in HOST_BACKENDS)],
                   check=True, stdout=(out / 'configure.log').open('w'), stderr=subprocess.STDOUT)
    subprocess.run(['cmake', '--build', str(build), '-j8'], check=True,
                   stdout=(out / 'build.log').open('w'), stderr=subprocess.STDOUT)

    objects = sorted((build / 'CMakeFiles/OpenAL.dir').rglob('*.o')) + sorted((build / 'CMakeFiles/common.dir').rglob('*.o'))
    if not any(o.name == 'orbis_audioout.cpp.o' for o in objects):
        raise SystemExit('the Orbis AudioOut backend was not compiled')
    # The SDK libc++ calls __cxa_thread_atexit_impl, which its libc lacks; orbis-compat provides it.
    atexit = tools.compile(ROOT / 'build/sources/orbis-compat/src/orbis_thread_atexit.c',
                           out / 'orbis_thread_atexit.o', '-std=c11', '-O2')
    # A defined no-op for the weak thread-local init hook create-fself cannot leave undefined.
    tls_init = tools.compile(ROOT / 'lwjgl/openal/orbis_openal_tls_init.c', out / 'orbis_openal_tls_init.o',
                             '-std=c11', '-O2')
    script, runtime = orbis_prx.runtime_objects(tools, out / 'runtime')
    elf = out / 'libopenal.so'
    orbis_prx.link(tools, script, runtime, elf, [*objects, atexit, tls_init],
                   system_libraries=('SceAudioOut', 'SceUserService'))
    audit, _ = orbis_prx.audit(elf, allowed_needed=('libkernel.so', 'libSceAudioOut.so', 'libSceUserService.so'),
                               jni_prefix=None, jni_count=None, require_onload=False, required_c_exports=CORE)
    audit['prx'] = str(orbis_prx.fself(tools, elf, arguments.paid))
    summary = {'openal_soft': lwjgl_sources.LOCK['openal-soft']['version'],
               'source_sha256': lwjgl_sources.LOCK['openal-soft']['sha256'],
               'backend': 'orbis (AudioOut main port, 48 kHz float stereo, 256-frame periods), then null',
               'module': audit}
    (out / 'openal-audit.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(f"libopenal: OpenAL Soft {summary['openal_soft']}, {len(audit['c_exports'])} AL/ALC exports, "
          f"{audit['imports']} imports from {audit['needed']}, TLS {audit['tls']}")


if __name__ == '__main__':
    main()
