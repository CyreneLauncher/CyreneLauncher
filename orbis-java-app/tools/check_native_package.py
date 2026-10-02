#!/usr/bin/env python3
"""Validate an independently installed Orbis runtime directory."""
from pathlib import Path, PurePosixPath
import argparse
import json
import re
import subprocess

from orbis_native_modules import (CAPABILITY_SCHEMA, DESKTOP_CAPABILITY,
                                  DESKTOP_MODULES, native_modules)

# Compatibility alias for callers which imported the original server catalog.
NATIVE_MODULES = native_modules()
SELF_MAGIC = b'O\x15=\x1d'
MANIFEST = Path('lib/orbis-capabilities.json')
DESKTOP_FONT = 'lib/fonts/DejaVuSans.ttf'
FONT_LICENSE = Path('legal/orbis-desktop-fonts/LICENSE')


def _read_manifest(root):
    path = root / MANIFEST
    if not path.exists():
        return None
    if path.is_symlink() or not path.is_file():
        raise ValueError(f'Invalid capability manifest artifact: {path}')
    try:
        value = json.loads(path.read_text(encoding='utf-8'))
    except (json.JSONDecodeError, UnicodeError) as error:
        raise ValueError(f'Invalid capability manifest {path}: {error}') from error
    if not isinstance(value, dict):
        raise ValueError(f'Invalid capability manifest field: root must be an object ({path})')
    required = {'schema', 'capabilities', 'native_modules', 'fonts'}
    if set(value) != required:
        missing = sorted(required - set(value))
        extra = sorted(set(value) - required)
        raise ValueError(f'Invalid capability manifest fields in {path}: missing={missing}, unknown={extra}')
    if type(value['schema']) is not int or value['schema'] != CAPABILITY_SCHEMA:
        raise ValueError(f'Invalid capability manifest field schema: expected integer {CAPABILITY_SCHEMA}')
    return value


def _string_list(value, field):
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise ValueError(f'Invalid capability manifest field {field}: expected an array of strings')
    if len(value) != len(set(value)):
        raise ValueError(f'Invalid capability manifest field {field}: duplicate entries')
    return value


def _font_path(root, raw):
    if not raw or '\\' in raw:
        raise ValueError(f'Invalid capability manifest font path: {raw!r}')
    relative = PurePosixPath(raw)
    if relative.is_absolute() or any(part in ('', '.', '..') for part in raw.split('/')):
        raise ValueError(f'Invalid capability manifest font path: {raw!r}')
    path = root.joinpath(*relative.parts)
    try:
        path.relative_to(root)
    except ValueError as error:
        raise ValueError(f'Capability font path escapes runtime root: {raw!r}') from error
    if path.is_symlink() or not path.is_file():
        raise ValueError(f'Missing or non-regular capability font artifact: {path}')
    if path.stat().st_size == 0:
        raise ValueError(f'Empty capability font artifact: {path}')
    return path


def _profile(root, manifest):
    if manifest is None:
        # Historical exports have no manifest and are server-profile exports.
        for name in DESKTOP_MODULES:
            for suffix in ('.so', '.prx'):
                artifact = root / 'lib' / f'lib{name}{suffix}'
                if artifact.exists():
                    raise ValueError(f'Desktop native artifact requires {MANIFEST}: {artifact}')
        return False, native_modules(False), []

    capabilities = _string_list(manifest['capabilities'], 'capabilities')
    if any(item != DESKTOP_CAPABILITY for item in capabilities):
        raise ValueError(f'Invalid capability manifest field capabilities: unknown capability in {capabilities!r}')
    desktop = capabilities == [DESKTOP_CAPABILITY]
    if capabilities not in ([], [DESKTOP_CAPABILITY]):
        raise ValueError(f'Invalid capability manifest field capabilities: unsupported profile {capabilities!r}')
    actual_modules = _string_list(manifest['native_modules'], 'native_modules')
    expected_modules = ['jvm', *native_modules(desktop)]
    if actual_modules != expected_modules:
        unknown = sorted(set(actual_modules) - set(expected_modules))
        raise ValueError(f'Invalid capability manifest field native_modules: expected {expected_modules!r}; unknown={unknown!r}')
    fonts = _string_list(manifest['fonts'], 'fonts')
    expected_fonts = [DESKTOP_FONT] if desktop else []
    if fonts != expected_fonts:
        raise ValueError(f'Invalid capability manifest field fonts: expected {expected_fonts!r}, got {fonts!r}')
    return desktop, native_modules(desktop), fonts


def check_runtime(root, jimage=None):
    root = Path(root)
    for name in ('conf', 'legal'):
        if not (root / name).is_dir():
            raise ValueError(f'Missing runtime directory: {root / name}')
    for name in ('release', 'lib/modules', 'lib/orbis-modules.list'):
        if not (root / name).is_file():
            raise ValueError(f'Missing runtime file: {root / name}')
    manifest = _read_manifest(root)
    desktop, modules, fonts = _profile(root, manifest)
    for font in fonts:
        _font_path(root, font)
    if desktop:
        license_path = root / FONT_LICENSE
        if license_path.is_symlink() or not license_path.is_file() or license_path.stat().st_size == 0:
            raise ValueError(f'Missing or invalid required desktop font license artifact: {license_path}')

    names = (root / 'lib/orbis-modules.list').read_text(encoding='utf-8').splitlines()
    if not names or len(names) != len(set(names)) or any(
            not re.fullmatch(r'[A-Za-z][A-Za-z0-9_.]*', name) for name in names):
        raise ValueError('Invalid runtime module list')
    if jimage is not None:
        listing = subprocess.check_output(
            [str(jimage), 'list', str(root / 'lib/modules')], text=True)
        actual = [line.removeprefix('Module: ').strip()
                  for line in listing.splitlines() if line.startswith('Module: ')]
        if names != actual:
            raise ValueError(f'Runtime module list differs from module image: {root}')
    required = [root / 'lib/server/libjvm.prx'] + [
        root / f'lib/lib{name}.so' for name in modules]
    selected = {path.resolve() for path in required}
    files = set(required) | set(root.rglob('*.so')) | set(root.rglob('*.prx'))
    if manifest is not None:
        for path in sorted(files):
            if path.resolve() not in selected:
                raise ValueError(f'Unexpected native SELF artifact for selected profile: {path}')
    for path in root.rglob('*'):
        if path.is_symlink():
            raise ValueError(f'Unmaterialized runtime symlink: {path}')
        if path.is_file():
            with path.open('rb') as stream:
                if stream.read(4) == b'\x7fELF':
                    raise ValueError(f'Raw ELF in runtime: {path}')
    for path in sorted(files):
        if not path.is_file():
            raise ValueError(f'Missing required SELF module artifact: {path}')
        with path.open('rb') as stream:
            if stream.read(4) != SELF_MAGIC:
                raise ValueError(f'Not a native SELF module: {path}')
    return len(files)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root')
    parser.add_argument('--jimage')
    args = parser.parse_args()
    try:
        count = check_runtime(args.root, args.jimage)
    except (ValueError, OSError, UnicodeError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
    print(f'Runtime package check passed: {count} SELF modules')
