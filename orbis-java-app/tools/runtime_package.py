#!/usr/bin/env python3
"""Assemble verified runtime exports into a clean, self-contained runner stage."""
import argparse
import hashlib
import json
import re
import shutil
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = 'manifests/runtime-package.json'
BASE = {'eboot.bin', 'app.jar', 'runner/README.txt', 'sce_sys/param.sfo',
        'sce_sys/icon0.png', 'sce_sys/about/right.sprx', 'sce_module/libc.prx',
        'sce_module/libSceFios2.prx'}


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def safe_path(value):
    p = Path(value)
    if p.is_absolute() or '..' in p.parts or not p.parts:
        raise ValueError('invalid package path: ' + value)
    return p


def inventory(root):
    files = {}
    for p in sorted(Path(root).rglob('*')):
        if p.is_symlink():
            raise ValueError('symlink in runtime export: ' + str(p))
        if p.is_file():
            relative = p.relative_to(root).as_posix()
            if p.suffix in ('.o', '.a', '.elf', '.log', '.py', '.c', '.cpp') or p.name in ('build-info.json', '.stamp'):
                raise ValueError('development file in runtime export: ' + relative)
            files[relative] = digest(p)
    return files


def validate(stage, allow_base_changes=False):
    stage = Path(stage)
    manifest = json.loads((stage / MANIFEST).read_text())
    if manifest.get('schema') != 1:
        raise ValueError('unsupported runtime package schema')
    expected = manifest['files']
    for name, sha in expected.items():
        p = stage / safe_path(name)
        if not re.fullmatch('[0-9a-f]{64}', sha) or not p.is_file():
            raise ValueError('missing or invalid package file: ' + name)
        if not (allow_base_changes and name in BASE) and digest(p) != sha:
            raise ValueError('package hash mismatch: ' + name)
    actual = set()
    for p in stage.rglob('*'):
        if p.is_symlink():
            raise ValueError('symlink in package: ' + str(p))
        if p.is_file():
            actual.add(p.relative_to(stage).as_posix())
    extra = actual - set(expected) - {MANIFEST, 'package.gp4'}
    if extra:
        raise ValueError('unexpected package files: ' + str(sorted(extra)))
    return manifest


def copy_verified(source, destination, expected):
    if source.is_symlink() or not source.is_file() or digest(source) != expected:
        raise ValueError('input hash mismatch: ' + str(source))
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)


def assemble(inputs, stage, launcher_jar):
    inputs = json.loads(Path(inputs).read_text())
    stage, launcher_jar = Path(stage), Path(launcher_jar)
    if inputs.get('schema') != 1:
        raise ValueError('unsupported runtime input schema')
    if stage.is_symlink():
        raise ValueError('stage may not be a symlink')
    # Only a pristine base stage or an intact previous release is accepted.
    if (stage / MANIFEST).exists():
        validate(stage, allow_base_changes=True)
    else:
        actual = {p.relative_to(stage).as_posix() for p in stage.rglob('*') if p.is_file() or p.is_symlink()}
        if actual != BASE:
            raise ValueError('stage must contain exactly the runner base files')
    for name in BASE:
        if (stage / name).is_symlink():
            raise ValueError('symlink in runner base')
    with tempfile.TemporaryDirectory(prefix='.runtime-stage-', dir=stage.parent) as temporary:
        out = Path(temporary) / 'stage'
        out.mkdir()
        for name in BASE:
            target = out / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(stage / name, target)
        graphics = inputs['graphics']
        graphics_root = ROOT / graphics['source']
        copy_manifest = json.loads((graphics_root / 'graphics-bundle.json').read_text())
        if digest(graphics_root / 'graphics-bundle.json') != graphics['manifest_sha256']:
            raise ValueError('graphics manifest input hash mismatch')
        for runtime, item in inputs['runtimes'].items():
            home = out / 'runtimes' / safe_path(runtime)
            source = ROOT / item['source']
            if inventory(source) != item['files']:
                raise ValueError('runtime export inventory mismatch: ' + runtime)
            for name, sha in item['files'].items():
                copy_verified(source / safe_path(name), home / name, sha)
            if f'JAVA_VERSION="{item["feature"]}.' not in (home / 'release').read_text():
                raise ValueError('runtime Java feature mismatch: ' + runtime)
            bundle = home / 'lib/orbis/graphics'
            emitted = json.loads(json.dumps(copy_manifest))
            for name, sha in copy_manifest['files'].items():
                if name == 'build-info.json':
                    emitted['files'].pop(name)
                    continue
                original = graphics_root / safe_path(name)
                copy_verified(original, bundle / name, sha)
            shutil.copyfile(launcher_jar, bundle / 'lib/orbis-launcher.jar')
            emitted['files']['lib/orbis-launcher.jar'] = digest(launcher_jar)
            (bundle / 'graphics-bundle.json').write_text(json.dumps(emitted, indent=2) + '\n')
            for name, item in inputs['desktop']['files'].items():
                copy_verified(ROOT / inputs['desktop']['source'] / safe_path(name), home / 'lib/orbis/desktop' / name, item)
            for name, item in inputs['launcher_libraries'].items():
                copy_verified(ROOT / item['source'], home / 'lib/orbis/libraries' / safe_path(name), item['sha256'])
        for name in ('runner.conf', 'jvm-options.txt'):
            target = out / 'defaults' / name
            target.parent.mkdir(exist_ok=True)
            shutil.copyfile(ROOT / 'orbis-java-app/defaults' / name, target)
        (out / 'manifests').mkdir(exist_ok=True)
        (out / 'manifests/launcher.json').write_text(json.dumps(inputs['launcher_profile'], indent=2) + '\n')
        manifest = {'schema': 1, 'component_revisions': inputs['component_revisions'],
                    'files': inventory(out)}
        (out / MANIFEST).write_text(json.dumps(manifest, indent=2) + '\n')
        validate(out)
        # All inputs are checked before replacing this generated stage.
        previous = Path(temporary) / 'previous'
        stage.rename(previous)
        try:
            out.rename(stage)
        except BaseException:
            previous.rename(stage)
            raise
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('assemble', 'verify', 'prepare-libraries'))
    parser.add_argument('--stage', type=Path)
    parser.add_argument('--inputs', type=Path, default=Path(__file__).with_name('runtime-package-inputs.json'))
    parser.add_argument('--launcher-jar', type=Path, default=ROOT / 'graphics-probe/build/launcher-release/orbis-launcher.jar')
    parser.add_argument('--library-output', type=Path, default=ROOT / 'graphics-probe/build/downloads/runner-jars')
    args = parser.parse_args()
    try:
        if args.command == 'prepare-libraries':
            data = json.loads(args.inputs.read_text())
            names = [Path(name).name for name in data['launcher_libraries']]
            if len(set(names)) != len(names):
                raise ValueError('colliding launcher dependency file names')
            for name, item in data['launcher_libraries'].items():
                copy_verified(ROOT / item['source'], args.library_output / Path(name).name, item['sha256'])
            print('Verified launcher dependency cache prepared.')
            return
        if args.stage is None:
            parser.error('--stage is required for assemble/verify')
        result = assemble(args.inputs, args.stage, args.launcher_jar) if args.command == 'assemble' else validate(args.stage)
        print(f'Runtime package verified: {len(result["files"])} files')
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()
