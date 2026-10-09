#!/usr/bin/env python3
"""Assemble the optional Orbis graphics bundle and validate it with the runner's own reader.

Upload the CONTENTS of the output directory to the runner's graphics_bundle path
(default /data/orbis-jdk/graphics). Layout:
  graphics-bundle.json   schema 1 (runner.lwjgl.GraphicsBundle): files, profiles
  build-info.json        source pins and build audits
  lib/                   six SELF PRXs with .so names (LWJGL 3.2.2, OpenAL, the Mesa platform),
                         the adapter JARs and the launcher
  lib/lwjgl-3.3.3/       LWJGL 3.3.3 natives and FreeType (Minecraft 1.19 to 1.21.11); the profile's
                         org.lwjgl.librarypath, while OpenAL and the platform module stay in lib/
                         (java.library.path)
  lib/lwjgl-3.4.1/       the same for LWJGL 3.4.1 (Minecraft 26.1 and 26.2)
  lib/lwjgl-3.4.3/       LWJGL 3.4.3 (Minecraft 26.3): natives, FreeType, shaderc and SPIRV-Cross
  lib/libSDL3.so         SDL3 for Controlify 3.5+, found on java.library.path
  legal/                 licence texts of what the modules contain
The bundle never contains runner or JDK files. Building it does not deploy it.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT.parent / 'orbis-java-app'
PROFILE_ID = 'minecraft-1.18.2-lwjgl-3.2.2-orbis-v1'
MODULES = {f'org.lwjgl:{m}': '3.2.2' for m in
           ('lwjgl', 'lwjgl-glfw', 'lwjgl-jemalloc', 'lwjgl-openal', 'lwjgl-opengl', 'lwjgl-stb', 'lwjgl-tinyfd')}
ADAPTER = 'orbis-lwjgl-3.2.2-adapter.jar'
LAUNCHER = 'orbis-launcher.jar'
NATIVES = ('liborbis_lwjgl_platform.so', 'liblwjgl.so', 'liblwjgl_opengl.so',
           'liblwjgl_stb.so', 'liblwjgl_tinyfd.so', 'libopenal.so')
MODERN_ID = 'minecraft-1.20.5-lwjgl-3.3.3-orbis-v1'
MODERN_MODULES = {f'org.lwjgl:{m}': '3.3.3' for m in
                  ('lwjgl', 'lwjgl-freetype', 'lwjgl-glfw', 'lwjgl-jemalloc', 'lwjgl-openal', 'lwjgl-opengl',
                   'lwjgl-stb', 'lwjgl-tinyfd')}
MODERN_ADAPTER = 'orbis-lwjgl-3.3.3-adapter.jar'
MODERN_DIR = 'lwjgl-3.3.3'
# The first entry's directory becomes org.lwjgl.librarypath (runner.gpu.GpuLaunch).
MODERN_NATIVES = tuple(f'{MODERN_DIR}/{name}' for name in
                       ('liblwjgl.so', 'liblwjgl_opengl.so', 'liblwjgl_stb.so', 'liblwjgl_tinyfd.so',
                        'libfreetype.so')) + ('libopenal.so', 'liborbis_lwjgl_platform.so')

CURRENT_ID = 'minecraft-26.1-lwjgl-3.4.1-orbis-v1'
CURRENT_MODULES = {module: '3.4.1' for module in MODERN_MODULES}
CURRENT_ADAPTER = 'orbis-lwjgl-3.4.1-adapter.jar'
CURRENT_DIR = 'lwjgl-3.4.1'
CURRENT_NATIVES = tuple(name.replace(MODERN_DIR, CURRENT_DIR) for name in MODERN_NATIVES)
# Minecraft 26.2: the same natives; its shaderc, spvc, vma and vulkan modules serve only the Vulkan backend.
NEXT_ID = 'minecraft-26.2-lwjgl-3.4.1-orbis-v1'
NEXT_MODULES = CURRENT_MODULES | {f'org.lwjgl:{m}': '3.4.1' for m in ('lwjgl-shaderc', 'lwjgl-spvc', 'lwjgl-vma', 'lwjgl-vulkan')}
# Minecraft 26.3: SDL3 in place of GLFW (the adapter carries the SDL facade), no TinyFD; its frontend
# compiles shaders with shaderc and its OpenGL backend translates them with SPIRV-Cross.
SDL_ID = 'minecraft-26.3-lwjgl-3.4.3-orbis-v1'
SDL_MODULES = {f'org.lwjgl:{m}': '3.4.3' for m in
               ('lwjgl', 'lwjgl-freetype', 'lwjgl-jemalloc', 'lwjgl-openal', 'lwjgl-opengl', 'lwjgl-sdl',
                'lwjgl-shaderc', 'lwjgl-spvc', 'lwjgl-stb', 'lwjgl-vma', 'lwjgl-vulkan')}
SDL_ADAPTER = 'orbis-lwjgl-3.4.3-adapter.jar'
SDL_DIR = 'lwjgl-3.4.3'
SDL_NATIVES = tuple(f'{SDL_DIR}/{name}' for name in
                    ('liblwjgl.so', 'liblwjgl_opengl.so', 'liblwjgl_stb.so', 'libfreetype.so', 'libshaderc.so',
                     'libspirv-cross.so')) + ('libopenal.so', 'liborbis_lwjgl_platform.so')

parser = argparse.ArgumentParser()
parser.add_argument('--boot-jdk', default='/usr/lib/jvm/java-17-openjdk-amd64')
parser.add_argument('--platform', default=str(ROOT / 'build/platform'))
parser.add_argument('--lwjgl', default=str(ROOT / 'build/lwjgl'))
parser.add_argument('--facade', default=str(ROOT / 'build/lwjgl-facade'))
parser.add_argument('--optional', default=str(ROOT / 'build/lwjgl-optional'))
parser.add_argument('--openal', default=str(ROOT / 'build/openal'))
parser.add_argument('--launcher', default=str(ROOT / 'build/launcher'))
parser.add_argument('--lwjgl-modern', default=str(ROOT / 'build/lwjgl-3.3.3'))
parser.add_argument('--facade-modern', default=str(ROOT / 'build/lwjgl-facade-3.3.3'))
parser.add_argument('--freetype', default=str(ROOT / 'build/freetype'))
parser.add_argument('--lwjgl-current', default=str(ROOT / 'build/lwjgl-3.4.1'))
parser.add_argument('--facade-current', default=str(ROOT / 'build/lwjgl-facade-3.4.1'))
parser.add_argument('--freetype-current', default=str(ROOT / 'build/freetype-3.4.1'))
parser.add_argument('--lwjgl-sdl', default=str(ROOT / 'build/lwjgl-3.4.3'))
parser.add_argument('--facade-sdl', default=str(ROOT / 'build/lwjgl-facade-3.4.3'))
parser.add_argument('--freetype-sdl', default=str(ROOT / 'build/freetype-3.4.3'))
parser.add_argument('--spirv-sdl', default=str(ROOT / 'build/spirv-3.4.3'))
parser.add_argument('--vma-sdl', help='enable optional Minecraft 26.3 Vulkan using this audited VMA build')
parser.add_argument('--sdl3', default=str(ROOT / 'build/sdl3'))
parser.add_argument('--output', default=str(ROOT / 'build/graphics-bundle'))
arguments = parser.parse_args()
platform, lwjgl, facade, optional = (Path(p).resolve() for p in
                                     (arguments.platform, arguments.lwjgl, arguments.facade, arguments.optional))
out = Path(arguments.output).resolve()


def load_audit(path):
    if not path.is_file():
        raise SystemExit(f'missing {path}; build that component first')
    return json.loads(path.read_text())


platform_audit = load_audit(platform / 'platform-audit.json')
lwjgl_audit = load_audit(lwjgl / 'lwjgl-audit.json')
facade_audit = load_audit(facade / 'facade-audit.json')
optional_audit = load_audit(optional / 'optional-audit.json')
openal = Path(arguments.openal).resolve()
openal_audit = load_audit(openal / 'openal-audit.json')
sources = ROOT / 'build/sources'
lwjgl_tree = next((lwjgl / 'sources/lwjgl').iterdir())
dyncall_tree = next((lwjgl / 'sources/dyncall').iterdir())
lwjgl_modern, facade_modern, freetype = (Path(p).resolve() for p in
                                         (arguments.lwjgl_modern, arguments.facade_modern, arguments.freetype))
modern_audit = load_audit(lwjgl_modern / 'lwjgl-audit.json')
facade_modern_audit = load_audit(facade_modern / 'facade-audit.json')
freetype_audit = load_audit(freetype / 'freetype-audit.json')
modern_tree = next((lwjgl_modern / 'sources/lwjgl').iterdir())
libffi_tree = next((lwjgl_modern / 'sources/libffi').iterdir())
freetype_tree = next((freetype / 'sources/freetype').iterdir())
lwjgl_current, facade_current, freetype_current = (Path(p).resolve() for p in
                                                   (arguments.lwjgl_current, arguments.facade_current,
                                                    arguments.freetype_current))
current_audit = load_audit(lwjgl_current / 'lwjgl-audit.json')
facade_current_audit = load_audit(facade_current / 'facade-audit.json')
freetype_current_audit = load_audit(freetype_current / 'freetype-audit.json')
current_tree = next((lwjgl_current / 'sources/lwjgl').iterdir())
libffi_current_tree = next((lwjgl_current / 'sources/libffi-3.5.0').iterdir())
lwjgl_sdl, facade_sdl, freetype_sdl, spirv_sdl = (Path(p).resolve() for p in
                                                  (arguments.lwjgl_sdl, arguments.facade_sdl, arguments.freetype_sdl,
                                                   arguments.spirv_sdl))
sdl_lwjgl_audit = load_audit(lwjgl_sdl / 'lwjgl-audit.json')
facade_sdl_audit = load_audit(facade_sdl / 'facade-audit.json')
freetype_sdl_audit = load_audit(freetype_sdl / 'freetype-audit.json')
spirv_sdl_audit = load_audit(spirv_sdl / 'spirv-audit.json')
vma_sdl = Path(arguments.vma_sdl).resolve() if arguments.vma_sdl else None
vma_sdl_audit = None
if vma_sdl:
    vma_sdl_audit = load_audit(vma_sdl / 'vma-audit.json')
    if vma_sdl_audit['release'] != '3.4.3':
        raise SystemExit('the SDL Vulkan profile requires pinned LWJGL VMA 3.4.3')
    if not platform_audit.get('vulkan_wsi'):
        raise SystemExit('optional Vulkan requires a verified Mesa build with the Orbis WSI input/pointer hooks')
    if 'vkGetInstanceProcAddr' not in platform_audit.get('c_exports', []):
        raise SystemExit('optional Vulkan requires a platform PRX exporting vkGetInstanceProcAddr')
    required = {f'Java_orbis_graphics_NativePlatform_{name}' for name in
                ('nCreateVulkanWindow', 'nVulkanCreateSurface', 'nVulkanPresentationSupport', 'nVulkanScanoutReady')}
    if not required <= set(platform_audit.get('jni_exports', [])):
        raise SystemExit('optional Vulkan requires the platform Vulkan window/input JNI bridge')
    SDL_NATIVES += (f'{SDL_DIR}/liblwjgl_vma.so',)

sdl_tree = next((lwjgl_sdl / 'sources/lwjgl').iterdir())
libffi_sdl_tree = next((lwjgl_sdl / 'sources/libffi-3.8.0').iterdir())
sdl3 = Path(arguments.sdl3).resolve()
sdl3_audit = load_audit(sdl3 / 'sdl3-audit.json')
sdl3_tree = next((sdl3 / 'sources').iterdir())

if out.exists():
    shutil.rmtree(out)
(out / 'lib').mkdir(parents=True)
shutil.copyfile(platform / 'liborbis_lwjgl_platform.prx', out / 'lib/liborbis_lwjgl_platform.so')
shutil.copyfile(lwjgl / 'liblwjgl.prx', out / 'lib/liblwjgl.so')
shutil.copyfile(lwjgl / 'liblwjgl_opengl.prx', out / 'lib/liblwjgl_opengl.so')
shutil.copyfile(lwjgl / 'liblwjgl_stb.prx', out / 'lib/liblwjgl_stb.so')
shutil.copyfile(optional / 'liblwjgl_tinyfd.prx', out / 'lib/liblwjgl_tinyfd.so')
shutil.copyfile(openal / 'libopenal.prx', out / 'lib/libopenal.so')
shutil.copyfile(facade / ADAPTER, out / 'lib' / ADAPTER)
shutil.copyfile(Path(arguments.launcher).resolve() / LAUNCHER, out / 'lib' / LAUNCHER)
(out / 'lib' / MODERN_DIR).mkdir()
for name in ('liblwjgl', 'liblwjgl_opengl', 'liblwjgl_stb', 'liblwjgl_tinyfd'):
    shutil.copyfile(lwjgl_modern / f'{name}.prx', out / 'lib' / MODERN_DIR / f'{name}.so')
shutil.copyfile(freetype / 'libfreetype.prx', out / 'lib' / MODERN_DIR / 'libfreetype.so')
shutil.copyfile(facade_modern / MODERN_ADAPTER, out / 'lib' / MODERN_ADAPTER)
(out / 'lib' / CURRENT_DIR).mkdir()
for name in ('liblwjgl', 'liblwjgl_opengl', 'liblwjgl_stb', 'liblwjgl_tinyfd'):
    shutil.copyfile(lwjgl_current / f'{name}.prx', out / 'lib' / CURRENT_DIR / f'{name}.so')
shutil.copyfile(freetype_current / 'libfreetype.prx', out / 'lib' / CURRENT_DIR / 'libfreetype.so')
shutil.copyfile(facade_current / CURRENT_ADAPTER, out / 'lib' / CURRENT_ADAPTER)
(out / 'lib' / SDL_DIR).mkdir()
for name in ('liblwjgl', 'liblwjgl_opengl', 'liblwjgl_stb'):
    shutil.copyfile(lwjgl_sdl / f'{name}.prx', out / 'lib' / SDL_DIR / f'{name}.so')
shutil.copyfile(freetype_sdl / 'libfreetype.prx', out / 'lib' / SDL_DIR / 'libfreetype.so')
for name in ('libshaderc', 'libspirv-cross'):
    shutil.copyfile(spirv_sdl / f'{name}.prx', out / 'lib' / SDL_DIR / f'{name}.so')
if vma_sdl:
    shutil.copyfile(vma_sdl / 'liblwjgl_vma.prx', out / 'lib' / SDL_DIR / 'liblwjgl_vma.so')
shutil.copyfile(facade_sdl / SDL_ADAPTER, out / 'lib' / SDL_ADAPTER)
shutil.copyfile(sdl3 / 'libSDL3.prx', out / 'lib/libSDL3.so')
for name in NATIVES + MODERN_NATIVES + CURRENT_NATIVES + SDL_NATIVES + ('libSDL3.so',):
    if (out / 'lib' / name).read_bytes()[:4] != b'O\x15=\x1d':
        raise SystemExit(f'{name} is not a SELF module')

legal = out / 'legal'
legal.mkdir()
if vma_sdl:
    shutil.copyfile(sdl_tree / 'modules/lwjgl/vma/vma_license.txt', legal / 'vma-LICENSE.txt')
shutil.copyfile(sources / 'mesa/docs/license.rst', legal / 'mesa-license.rst')
shutil.copyfile(sources / 'mesa/subprojects/zlib-1.3.1/LICENSE', legal / 'zlib-LICENSE')
shutil.copyfile(next((openal / 'sources').iterdir()) / 'COPYING', legal / 'openal-soft-COPYING')
shutil.copyfile(lwjgl_tree / 'LICENSE.md', legal / 'lwjgl-LICENSE.md')
shutil.copyfile(dyncall_tree / 'LICENSE', legal / 'dyncall-LICENSE')
shutil.copyfile(modern_tree / 'LICENSE.md', legal / 'lwjgl-3.3.3-LICENSE.md')
shutil.copyfile(libffi_tree / 'LICENSE', legal / 'libffi-LICENSE')
shutil.copyfile(current_tree / 'LICENSE.md', legal / 'lwjgl-3.4.1-LICENSE.md')
shutil.copyfile(libffi_current_tree / 'LICENSE', legal / 'libffi-3.5.0-LICENSE')
shutil.copyfile(sdl_tree / 'LICENSE.md', legal / 'lwjgl-3.4.3-LICENSE.md')
shutil.copyfile(libffi_sdl_tree / 'LICENSE', legal / 'libffi-3.8.0-LICENSE')
shaderc_tree = next((spirv_sdl / 'sources/shaderc').iterdir())
for key in ('shaderc', 'glslang', 'spirv-tools', 'spirv-headers', 'spirv-cross'):
    # glslang, SPIRV-Tools and SPIRV-Headers are built from shaderc's third_party copies.
    tree = (shaderc_tree if key == 'shaderc' else shaderc_tree / 'third_party' / key
            if (shaderc_tree / 'third_party' / key).is_dir() else next((spirv_sdl / 'sources' / key).iterdir()))
    licence = next(tree / name for name in ('LICENSE', 'LICENSE.txt') if (tree / name).is_file())
    shutil.copyfile(licence, legal / f'{key}-LICENSE')
shutil.copyfile(sdl3_tree / 'LICENSE.txt', legal / 'sdl3-LICENSE.txt')
for name in ('LICENSE.TXT', 'docs/FTL.TXT'):
    shutil.copyfile(freetype_tree / name, legal / f'freetype-{Path(name).name}')
shutil.copyfile(lwjgl_tree / 'modules/lwjgl/tinyfd/src/main/c/tinyfiledialogs.h',
                legal / 'tinyfiledialogs.h')
for name in ('LICENSE', 'LICENSING.md', 'NOTICE.md'):
    shutil.copyfile(sources / 'orbis-compat' / name, legal / f'orbis-compat-{name}')
if (sources / 'orbis-compat/licenses').is_dir():
    shutil.copytree(sources / 'orbis-compat/licenses', legal / 'orbis-compat-licenses')
(legal / 'README.md').write_text('''# Licences in this bundle

- `liborbis_lwjgl_platform.so`: Mesa (see `mesa-license.rst`; component licences vary),
  zlib (`zlib-LICENSE`), orbis-compat and the OpenOrbis SDK runtime it links statically
  (`orbis-compat-*`, `orbis-compat-licenses/`).
- `liblwjgl.so`, `liblwjgl_opengl.so`, `liblwjgl_stb.so`,
  `orbis-lwjgl-3.2.2-adapter.jar`: LWJGL 3.2.2
  (`lwjgl-LICENSE.md`) and dyncall 1.0 (`dyncall-LICENSE`).
- `lwjgl-3.3.3/liblwjgl.so`, `liblwjgl_opengl.so`, `liblwjgl_stb.so`, `liblwjgl_tinyfd.so` and
  `orbis-lwjgl-3.3.3-adapter.jar`: LWJGL 3.3.3 (`lwjgl-3.3.3-LICENSE.md`) and libffi 3.4.4
  (`libffi-LICENSE`, MIT); the TinyFD stub is original, like 3.2.2's.
- `lwjgl-3.3.3/libfreetype.so` is FreeType 2.13.2 built unmodified with its default options
  (`freetype-LICENSE.TXT`; used here under the FreeType Project License, `freetype-FTL.TXT`).
- `lwjgl-3.4.1/liblwjgl.so`, `liblwjgl_opengl.so`, `liblwjgl_stb.so`, `liblwjgl_tinyfd.so` and
  `orbis-lwjgl-3.4.1-adapter.jar`: LWJGL 3.4.1 (`lwjgl-3.4.1-LICENSE.md`) and libffi 3.5.0
  (`libffi-3.5.0-LICENSE`, MIT); the same TinyFD stub. `lwjgl-3.4.1/libfreetype.so` is FreeType
  2.14.1, built the same way under the same licences.
- `lwjgl-3.4.3/liblwjgl.so`, `liblwjgl_opengl.so`, `liblwjgl_stb.so` and `orbis-lwjgl-3.4.3-adapter.jar`:
  LWJGL 3.4.3 (`lwjgl-3.4.3-LICENSE.md`) and libffi 3.8.0 (`libffi-3.8.0-LICENSE`, MIT); the adapter's
  SDL facade is original. `lwjgl-3.4.3/libfreetype.so` is FreeType 2.14.3 under the licences above.
  `lwjgl-3.4.3/liblwjgl_vma.so` contains Vulkan Memory Allocator (`vma-LICENSE.txt`, MIT)
  and the official LWJGL VMA JNI; it uses the platform PRX's Vulkan dispatch.
  `lwjgl-3.4.3/libshaderc.so` is shaderc with glslang, SPIRV-Tools and SPIRV-Headers
  (`shaderc-LICENSE`, `glslang-LICENSE`, `spirv-tools-LICENSE`, `spirv-headers-LICENSE`) and
  `lwjgl-3.4.3/libspirv-cross.so` is SPIRV-Cross (`spirv-cross-LICENSE`), all built unmodified apart from
  shaderc's install switch.
- `libSDL3.so` is SDL 3.4.12 (Zlib, `sdl3-LICENSE.txt`) built with its dummy video and audio drivers,
  plus an original virtual-gamepad entry point for the console's controller.
- STB sources in `liblwjgl_stb.so` are upstream stb headers bundled by LWJGL 3.2.2;
  their headers state public-domain or MIT dual licensing. The generated JNI is LWJGL BSD-3-Clause.
- `liblwjgl_tinyfd.so` uses LWJGL generated JNI (BSD-3-Clause) and the bounded
  original Orbis implementation of tinyfiledialogs (Zlib; see `tinyfiledialogs.h`).
- `libopenal.so` is OpenAL Soft 1.23.1 (LGPL-2.1-or-later, `openal-soft-COPYING`) built
  unmodified as a separate dynamically loaded module, plus an Orbis AudioOut playback backend
  added at build time (graphics-probe/lwjgl/openal/). TinyFD file and message dialogs cancel.
- All modules also contain the runner's own Orbis runtime objects and SDK libc/libc++.
- Compatibility fixture provenance and the DejaVu font license are in the separate
  `lwjgl-compat/runner/fixtures/` upload set; fixtures are not shipped in this bundle.

Open items, not settled by this build: a component-level audit of the Mesa drivers linked
here, the questions recorded in `orbis-compat-LICENSING.md` section 5, and the licence of the
OpenOrbis link script the module layout derives from (`orbis-compat-licenses/` and the
porting kit note that file as GPL-3.0). Review these before distributing the bundle.
''')

build_info = {
    'profile': PROFILE_ID,
    'profiles': [PROFILE_ID, MODERN_ID, CURRENT_ID, NEXT_ID, SDL_ID],
    'sources': json.loads((ROOT / 'lwjgl/lwjgl-sources.lock.json').read_text()),
    'mesa_lock': json.loads((ROOT / 'deps.lock.json').read_text())['sources'],
    'platform': {k: platform_audit[k] for k in ('needed', 'imports', 'tls')},
    'lwjgl': {name: {k: audit[k] for k in ('needed', 'imports', 'jni_natives', 'tls')}
              for name, audit in lwjgl_audit['modules'].items()},
    'stb': {k: lwjgl_audit['modules']['liblwjgl_stb'][k] for k in ('needed', 'imports', 'jni_natives', 'tls')},
    'optional': {'behavior_mode': optional_audit['behavior_mode'],
                 'modules': {name: {k: audit[k] for k in ('needed', 'imports', 'jni_natives', 'tls')}
                             for name, audit in optional_audit['modules'].items()}},
    'openal': {k: openal_audit[k] for k in ('openal_soft', 'source_sha256', 'backend')}
              | {k: openal_audit['module'][k] for k in ('needed', 'imports', 'tls')},
    'compatibility': {'dialogs': 'cancel', 'audio': 'orbis-audioout',
                      'device_callback_and_gl_debug_gate': 'pending device run',
                      'fixture_provenance': 'separate lwjgl-compat upload set'},
    'facade': {k: facade_audit[k] for k in ('classes', 'official_classes', 'glfw_methods')}
              | {'implemented': len(facade_audit['implemented']), 'unsupported': len(facade_audit['unsupported'])},
    'lwjgl_3.3.3': {name: {k: audit[k] for k in ('needed', 'imports', 'jni_natives', 'tls')}
                    for name, audit in modern_audit['modules'].items()},
    'freetype': {'version': freetype_audit['freetype'], 'lwjgl_required_functions': freetype_audit['lwjgl_required_functions']}
                | {k: freetype_audit['module'][k] for k in ('needed', 'imports', 'tls')},
    'facade_3.3.3': {k: facade_modern_audit[k] for k in ('classes', 'official_classes', 'glfw_methods')}
                    | {'implemented': len(facade_modern_audit['implemented']),
                       'unsupported': len(facade_modern_audit['unsupported'])},
    'lwjgl_3.4.1': {name: {k: audit[k] for k in ('needed', 'imports', 'jni_natives', 'tls')}
                    for name, audit in current_audit['modules'].items()},
    'freetype_3.4.1': {'version': freetype_current_audit['freetype'],
                       'lwjgl_required_functions': freetype_current_audit['lwjgl_required_functions']}
                      | {k: freetype_current_audit['module'][k] for k in ('needed', 'imports', 'tls')},
    'sdl3': {'version': sdl3_audit['sdl3'], 'reference_functions': sdl3_audit['reference_functions']}
            | {k: sdl3_audit['module'][k] for k in ('needed', 'imports', 'tls')},
    'facade_3.4.1': {k: facade_current_audit[k] for k in ('classes', 'official_classes', 'glfw_methods')}
                    | {'implemented': len(facade_current_audit['implemented']),
                       'unsupported': len(facade_current_audit['unsupported'])},
    'lwjgl_3.4.3': {name: {k: audit[k] for k in ('needed', 'imports', 'jni_natives', 'tls')}
                    for name, audit in sdl_lwjgl_audit['modules'].items()},
    'freetype_3.4.3': {'version': freetype_sdl_audit['freetype'],
                       'lwjgl_required_functions': freetype_sdl_audit['lwjgl_required_functions']}
                      | {k: freetype_sdl_audit['module'][k] for k in ('needed', 'imports', 'tls')},
    'vma_3.4.3': vma_sdl_audit,
    'vulkan_3.4.3': {'available': bool(vma_sdl), 'entry_point': 'vkGetInstanceProcAddr',
                       'library': 'lib/liborbis_lwjgl_platform.so',
                       'wsi_verified': platform_audit.get('vulkan_wsi', False),
                       'mesa_patches': platform_audit.get('mesa_patches', {}),
                       'console_acceptance': 'pending'},
    'spirv_3.4.3': {name: {k: audit[k] for k in ('needed', 'imports', 'tls')}
                    for name, audit in spirv_sdl_audit['modules'].items()},
    'facade_3.4.3': {k: facade_sdl_audit[k] for k in ('classes', 'official_classes', 'glfw_methods')}
                    | {'implemented': len(facade_sdl_audit['implemented']),
                       'sdl_implemented': len(facade_sdl_audit['sdl_implemented'])},
}
(out / 'build-info.json').write_text(json.dumps(build_info, indent=2) + '\n')

files = {}
for path in sorted(out.rglob('*')):
    if path.is_symlink():
        raise SystemExit(f'symlink in bundle: {path}')
    if path.is_file():
        files[path.relative_to(out).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
operations = sorted({entry.split(' ')[0] for entry in facade_audit['implemented']})
modern_operations = sorted({entry.split(' ')[0] for entry in facade_modern_audit['implemented']})
current_operations = sorted({entry.split(' ')[0] for entry in facade_current_audit['implemented']})
sdl_operations = sorted({entry.split(' ')[0] for entry in facade_sdl_audit['implemented']})
manifest = {
    'schema_version': 1,
    'files': files,
    'profiles': [{
        'id': PROFILE_ID,
        'modules': MODULES,
        'java_adapter_paths': [f'lib/{ADAPTER}', f'lib/{LAUNCHER}'],
        'native_library_paths': [f'lib/{name}' for name in NATIVES],
        'glfw_operations': operations,
        'gl_features': ['egl-desktop-opengl', 'single-fullscreen-window', 'eglGetProcAddress'],
    }, {
        'id': MODERN_ID,
        'modules': MODERN_MODULES,
        'java_adapter_paths': [f'lib/{MODERN_ADAPTER}', f'lib/{LAUNCHER}'],
        'native_library_paths': [f'lib/{name}' for name in MODERN_NATIVES],
        'glfw_operations': modern_operations,
        'gl_features': ['egl-desktop-opengl', 'single-fullscreen-window', 'eglGetProcAddress'],
    }, {
        'id': CURRENT_ID,
        'modules': CURRENT_MODULES,
        'java_adapter_paths': [f'lib/{CURRENT_ADAPTER}', f'lib/{LAUNCHER}'],
        'native_library_paths': [f'lib/{name}' for name in CURRENT_NATIVES],
        'glfw_operations': current_operations,
        'gl_features': ['egl-desktop-opengl', 'single-fullscreen-window', 'eglGetProcAddress'],
    }, {
        'id': NEXT_ID,
        'modules': NEXT_MODULES,
        'java_adapter_paths': [f'lib/{CURRENT_ADAPTER}', f'lib/{LAUNCHER}'],
        'native_library_paths': [f'lib/{name}' for name in CURRENT_NATIVES],
        'glfw_operations': current_operations,
        'gl_features': ['egl-desktop-opengl', 'single-fullscreen-window', 'eglGetProcAddress'],
    }, {
        'id': SDL_ID,
        'modules': SDL_MODULES,
        'java_adapter_paths': [f'lib/{SDL_ADAPTER}', f'lib/{LAUNCHER}'],
        'native_library_paths': [f'lib/{name}' for name in SDL_NATIVES],
        'glfw_operations': sdl_operations,
        'gl_features': ['egl-desktop-opengl', 'single-fullscreen-window', 'eglGetProcAddress'],
    }],
}
(out / 'graphics-bundle.json').write_text(json.dumps(manifest, indent=2) + '\n')

# The runner's reader decides whether this bundle is acceptable.
with tempfile.TemporaryDirectory() as directory:
    tmp = Path(directory)
    check = tmp / 'BundleCheck.java'
    check.write_text('''import java.nio.file.Path;
import runner.lwjgl.GraphicsBundle;
import runner.lwjgl.LwjglProfileRegistry;
public final class BundleCheck {
    public static void main(String[] args) throws Exception {
        for (var profile : LwjglProfileRegistry.profiles()) {
            GraphicsBundle bundle = GraphicsBundle.load(Path.of(args[0]), profile);
            System.out.println("runner accepts bundle for " + profile.id() + ": " + bundle.javaAdapters().size()
                    + " adapter JARs, " + bundle.nativeLibraries().size() + " native modules, "
                    + bundle.glfwOperations().size() + " GLFW operations");
        }
    }
}
''')
    javac = Path(arguments.boot_jdk) / 'bin/javac'
    subprocess.run([str(javac), '--release', '17', '-d', str(tmp / 'classes'), str(check),
                    *map(str, sorted((RUNNER / 'src/runner/lwjgl').glob('*.java')))], check=True)
    subprocess.run([str(Path(arguments.boot_jdk) / 'bin/java'), '-cp', str(tmp / 'classes'), 'BundleCheck',
                    str(out)], check=True)
print(f'graphics bundle: {out} ({len(files)} files); upload its contents to the runner graphics_bundle path')
