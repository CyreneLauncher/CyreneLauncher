#!/usr/bin/env python3
"""Transfer completed build components between hosted jobs, retaining modes."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import tempfile


def digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def relative(name):
    path = Path(name)
    if path.is_absolute() or '..' in path.parts or not path.parts:
        raise ValueError('Unsafe component path: ' + name)
    return path


def inventory(root):
    files = {}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError('Link in build artifact: ' + str(path))
        if path.is_file():
            files[path.relative_to(root).as_posix()] = {
                'sha256': digest(path), 'mode': path.stat().st_mode & 0o777}
    return files


def pack(root, archive, components):
    root, archive = Path(root), Path(archive)
    components = [relative(name) for name in components]
    if len(set(components)) != len(components):
        raise ValueError('Duplicate component')
    for component in components:
        if not (root / component).is_dir() or (root / component).is_symlink():
            raise ValueError('Missing component: ' + str(component))
    archive.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as directory:
        stage = Path(directory)
        for component in components:
            shutil.copytree(root / component, stage / component, symlinks=True)
        files = inventory(stage)
        (stage / 'artifact-inventory.json').write_text(json.dumps({
            'schema': 1, 'components': [str(path) for path in components], 'files': files}, indent=2) + '\n')
        with tarfile.open(archive, 'w:gz') as output:
            for component in components:
                output.add(stage / component, arcname=str(component))
            output.add(stage / 'artifact-inventory.json', arcname='artifact-inventory.json')
    archive.with_name(archive.name + '.sha256').write_text(digest(archive) + '  ' + archive.name + '\n')


def unpack(archive, destination):
    archive, destination = Path(archive), Path(destination)
    if digest(archive) != archive.with_name(archive.name + '.sha256').read_text().split()[0]:
        raise ValueError('Build artifact hash mismatch: ' + str(archive))
    destination.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.cyrene-artifact-', dir=destination) as directory:
        stage = Path(directory)
        with tarfile.open(archive, 'r:gz') as source:
            names = set()
            for member in source.getmembers():
                relative(member.name)
                if not (member.isfile() or member.isdir()) or member.name in names:
                    raise ValueError('Unsafe build artifact member: ' + member.name)
                names.add(member.name)
            source.extractall(stage, filter='data')
        report = json.loads((stage / 'artifact-inventory.json').read_text())
        (stage / 'artifact-inventory.json').unlink()
        if report.get('schema') != 1 or inventory(stage) != report['files']:
            raise ValueError('Build artifact inventory mismatch')
        components = [relative(name) for name in report['components']]
        if not components or len(set(components)) != len(components):
            raise ValueError('Invalid artifact component list')
        for component in components:
            if not (stage / component).is_dir() or (destination / component).exists():
                raise ValueError('Missing component or existing output: ' + str(component))
        for component in components:
            target = destination / component
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(stage / component, target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['pack', 'unpack'])
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('components', nargs='*')
    args = parser.parse_intermixed_args()
    if args.action == 'pack':
        pack(args.root, args.archive, args.components)
    else:
        unpack(args.archive, args.root)


if __name__ == '__main__':
    main()
