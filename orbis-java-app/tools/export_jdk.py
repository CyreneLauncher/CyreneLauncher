#!/usr/bin/env python3
"""Assemble a replaceable FTP runtime, independently of runner staging."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from check_native_package import check_runtime
from orbis_native_modules import CAPABILITY_SCHEMA, DESKTOP_CAPABILITY, native_modules

FONT_SOURCE = Path(__file__).resolve().parent.parent / "runtime-data/desktop/fonts"


def materialized_entries(source, relative=Path('.'), ancestors=frozenset()):
    """Follow directory links, rejecting cycles while retaining their logical paths."""
    resolved = source.resolve(strict=True)
    yield source, relative
    if source.is_dir():
        if resolved in ancestors:
            raise ValueError(f'Runtime source directory symlink cycle: {source}')
        for child in sorted(source.iterdir()):
            yield from materialized_entries(child, relative / child.name,
                                            ancestors | {resolved})


def export(jdk, runtime_image, native, output, jimage, desktop=False):
    modules = native_modules(desktop)
    font_inputs = [FONT_SOURCE / name for name in ("DejaVuSans.ttf", "LICENSE")] if desktop else []
    for source in font_inputs:
        if source.is_symlink() or not source.is_file():
            raise ValueError(f"Desktop font input is not a regular file: {source}")
    destination = Path(output).absolute()
    if destination.is_symlink():
        raise ValueError(f'Export destination is a symlink: {destination}')
    output = destination.resolve()
    jdk, runtime_image, native = map(
        lambda path: Path(path).resolve(), (jdk, runtime_image, native))
    sources = {}
    for name in ('conf', 'legal'):
        source = runtime_image / name
        if not source.is_dir():
            source = jdk / name
        if name == 'legal' and not source.is_dir():
            source = jdk.parent / 'images/jdk/legal'
        sources[name] = list(materialized_entries(source))
    release = runtime_image / 'release'
    release = (release if release.is_file() else jdk / 'release').resolve(strict=True)
    # Class-only jmods omit the exploded JDK's timezone/security data.
    # Merge that data first, then prefer files supplied by the selected image.
    data_entries = []
    if (jdk / 'lib').is_dir():
        data_entries.extend(materialized_entries(jdk / 'lib'))
    data_entries.extend(materialized_entries(runtime_image / 'lib'))
    # Include effective fallback sources and all followed links in the guard.
    input_paths = [jdk, runtime_image, native, release]
    input_paths.extend(source.resolve() for entries in sources.values()
                       for source, _ in entries)
    input_paths.extend(source.resolve() for source, _ in data_entries)
    input_paths.extend((native / f'lib{name}.prx').resolve()
                       for name in ('jvm', *modules))
    input_paths.extend(source.resolve() for source in font_inputs)
    for source in input_paths:
        if output == source or output in source.parents or source in output.parents:
            raise ValueError(f'Export output overlaps input: {source}')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=output.name + '.export-', dir=output.parent) as tmp:
        stage = Path(tmp) / 'runtime'
        stage.mkdir()
        for name, entries in sources.items():
            for source, relative in entries:
                if name == "legal" and relative.parts[:1] == ("orbis-desktop-fonts",):
                    continue
                target = stage / name / relative
                if source.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    shutil.copyfile(source, target)
        shutil.copyfile(release, stage / 'release')
        (stage / 'lib/server').mkdir(parents=True)
        shutil.copyfile(runtime_image / 'lib/modules', stage / 'lib/modules')
        listing = subprocess.check_output([str(jimage), 'list', str(stage / 'lib/modules')], text=True)
        names = [line.removeprefix('Module: ').strip() for line in listing.splitlines()
                 if line.startswith('Module: ')]
        (stage / 'lib/orbis-modules.list').write_text('\n'.join(names) + '\n', encoding='utf-8')
        for name in modules:
            shutil.copyfile(native / f'lib{name}.prx', stage / f'lib/lib{name}.so')
        shutil.copyfile(native / 'libjvm.prx', stage / 'lib/server/libjvm.prx')
        # Preserve non-native runtime data (tzdb, security data, etc.).
        for source, data_relative in data_entries:
            relative = Path('lib') / data_relative
            if (not source.is_file()
                    or relative.as_posix() in ('lib/modules', 'lib/orbis-modules.list', 'lib/orbis-capabilities.json')
                    or relative.parts[:2] == ('lib', 'fonts')):
                continue
            if source.suffix in ('.so', '.prx', '.a', '.o', '.oelf'):
                continue
            with source.open('rb') as stream:
                if stream.read(4) == b'\x7fELF':
                    continue
            target = stage / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        fonts = []
        if desktop:
            (stage / 'lib/fonts').mkdir(parents=True)
            (stage / 'legal/orbis-desktop-fonts').mkdir(parents=True)
            shutil.copyfile(font_inputs[0], stage / 'lib/fonts/DejaVuSans.ttf')
            shutil.copyfile(font_inputs[1], stage / 'legal/orbis-desktop-fonts/LICENSE')
            fonts.append('lib/fonts/DejaVuSans.ttf')
        manifest = {
            'schema': CAPABILITY_SCHEMA,
            'capabilities': [DESKTOP_CAPABILITY] if desktop else [],
            'native_modules': ['jvm', *modules],
            'fonts': fonts,
        }
        (stage / 'lib/orbis-capabilities.json').write_text(
            json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
        check_runtime(stage, jimage)
        backup = Path(tmp) / 'previous'
        if destination.is_symlink():
            raise ValueError(f'Export destination is a symlink: {destination}')
        if output.exists():
            output.rename(backup)
        try:
            stage.rename(output)
        except BaseException:
            if backup.exists():
                backup.rename(output)
            raise
    return output


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('jdk', 'runtime-image', 'native', 'output'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--jimage', default='jimage')
    parser.add_argument('--desktop', action='store_true')
    args = parser.parse_args()
    try:
        print(export(args.jdk, args.runtime_image, args.native, args.output, args.jimage, args.desktop))
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'Runtime export failed: {error}\n')
