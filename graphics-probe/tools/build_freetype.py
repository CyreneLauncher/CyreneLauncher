#!/usr/bin/env python3
"""Build FreeType as the libfreetype PRX LWJGL 3.3.3's org.lwjgl.util.freetype loads.

LWJGL has no JNI code for FreeType: FreeType.Functions looks every entry point up by name in
libfreetype when the class initialises, and calls them through JNI.invoke*. So the module is plain
FreeType, compiled from the pinned release (lwjgl/lwjgl-sources.lock.json) with its CMake source
set and default options, and linked with the shared Orbis PRX recipe (orbis_prx.py). LWJGL's own
binary adds HarfBuzz and libpng; FreeType works without them (no HarfBuzz-assisted autohinting,
no PNG colour glyphs). The build fails unless every function FreeType.Functions requires is exported.
"""
import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_stamp  # noqa: E402
import lwjgl_sources  # noqa: E402
import orbis_prx  # noqa: E402

ROOT = orbis_prx.ROOT
# CMakeLists.txt BASE_SRCS, plus the portable stdio ftsystem.c and ftdebug.c it adds for non-Unix
# hosts (builds/unix/ftsystem.c needs the Unix configure's ftconfig.h).
SOURCES = (
    'src/autofit/autofit.c', 'src/base/ftbase.c', 'src/base/ftbbox.c', 'src/base/ftbdf.c',
    'src/base/ftbitmap.c', 'src/base/ftcid.c', 'src/base/ftfstype.c', 'src/base/ftgasp.c',
    'src/base/ftglyph.c', 'src/base/ftgxval.c', 'src/base/ftinit.c', 'src/base/ftmm.c',
    'src/base/ftotval.c', 'src/base/ftpatent.c', 'src/base/ftpfr.c', 'src/base/ftstroke.c',
    'src/base/ftsynth.c', 'src/base/fttype1.c', 'src/base/ftwinfnt.c', 'src/bdf/bdf.c',
    'src/bzip2/ftbzip2.c', 'src/cache/ftcache.c', 'src/cff/cff.c', 'src/cid/type1cid.c',
    'src/gzip/ftgzip.c', 'src/lzw/ftlzw.c', 'src/pcf/pcf.c', 'src/pfr/pfr.c', 'src/psaux/psaux.c',
    'src/pshinter/pshinter.c', 'src/psnames/psnames.c', 'src/raster/raster.c', 'src/sdf/sdf.c',
    'src/sfnt/sfnt.c', 'src/smooth/smooth.c', 'src/svg/svg.c', 'src/truetype/truetype.c',
    'src/type1/type1.c', 'src/type42/type42.c', 'src/winfonts/winfnt.c',
    'src/base/ftsystem.c', 'src/base/ftdebug.c')
BINDING = 'modules/lwjgl/freetype/src/generated/java/org/lwjgl/util/freetype/FreeType.java'


def required_functions(lwjgl):
    """The names FreeType.Functions resolves with apiGetFunctionAddress (not the optional ones)."""
    text = (lwjgl / BINDING).read_text()
    names = re.findall(r'apiGetFunctionAddress\(FREETYPE, "(\w+)"\)', text)
    version = tuple(int(re.search(rf'FREETYPE_{part}\s*=\s*(\d+);', text).group(1))
                    for part in ('MAJOR', 'MINOR', 'PATCH'))
    if not names:
        raise SystemExit(f'no FreeType functions found in {BINDING}')
    return names, version


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', default='/home/wonder/ps4-payload-sdk')
    parser.add_argument('--toolchain', default='/home/wonder/PS4Toolchain')
    parser.add_argument('--paid', default='0x3800000000000011')
    parser.add_argument('--downloads', default=str(ROOT / 'build/downloads'))
    parser.add_argument('--fetch', action='store_true', help='download the pinned archives if missing')
    parser.add_argument('--output', default=str(ROOT / 'build/freetype'))
    arguments = parser.parse_args()
    out = Path(arguments.output).resolve()
    out.mkdir(parents=True, exist_ok=True)
    downloads = Path(arguments.downloads)
    tools = orbis_prx.Toolchain(arguments.sdk, arguments.toolchain)

    stamp = (orbis_prx.stamp_recipe(build_stamp.Stamp(out, 'freetype'), tools)
             .files(Path(__file__), Path(lwjgl_sources.__file__), ROOT / 'lwjgl/lwjgl-sources.lock.json')
             .value('arguments', {k: v for k, v in vars(arguments).items() if k not in ('fetch', 'downloads')}))
    if stamp.current(out / 'freetype-audit.json', out / 'libfreetype.so', out / 'libfreetype.prx'):
        print('libfreetype: unchanged inputs, reusing the last build')
        return
    stamp.invalidate()

    lwjgl = lwjgl_sources.extract(lwjgl_sources.archive('lwjgl-3.3.3', downloads, arguments.fetch),
                                  out / 'sources/lwjgl')
    required, version = required_functions(lwjgl)
    pinned = lwjgl_sources.LOCK['freetype']['version']
    if '.'.join(map(str, version)) != pinned:
        raise SystemExit(f'LWJGL binds FreeType {version}, the lock pins {pinned}')
    source = lwjgl_sources.extract(lwjgl_sources.archive('freetype', downloads, arguments.fetch),
                                   out / 'sources/freetype')

    objects = []
    (out / 'obj').mkdir(exist_ok=True)
    for name in SOURCES:
        stem = name.replace('/', '_').replace('.', '_')
        objects.append(tools.compile(source / name, out / 'obj' / f'{stem}.o', '-std=gnu11', '-O2',
                                     '-fdata-sections', '-ffunction-sections', '-DFT2_BUILD_LIBRARY',
                                     '-DNDEBUG', '-I', source / 'include'))
    script, runtime = orbis_prx.runtime_objects(tools, out / 'runtime')
    elf = out / 'libfreetype.so'
    orbis_prx.link(tools, script, runtime, elf, objects)
    audit, _ = orbis_prx.audit(elf, allowed_needed=('libkernel.so',), jni_prefix=None, jni_count=None,
                               require_onload=False, required_c_exports=required)
    audit['prx'] = str(orbis_prx.fself(tools, elf, arguments.paid))
    summary = {'freetype': pinned, 'source_sha256': lwjgl_sources.LOCK['freetype']['sha256'],
               'lwjgl_required_functions': len(required), 'module': audit}
    (out / 'freetype-audit.json').write_text(json.dumps(summary, indent=2) + '\n')
    stamp.record()
    print(f"libfreetype: FreeType {pinned}, all {len(required)} functions LWJGL requires exported, "
          f"{audit['imports']} imports, TLS {audit['tls']}")


if __name__ == '__main__':
    main()
