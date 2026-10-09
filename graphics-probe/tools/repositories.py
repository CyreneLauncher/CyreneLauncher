#!/usr/bin/env python3
"""Verify pinned sibling ports, or restore missing repositories from seeds/bundles."""
import argparse
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

DEFAULT_LOCK = Path(__file__).resolve().parents[2] / 'repositories.lock.json'


def git(*args):
    return subprocess.check_output(['git', *map(str, args)], text=True,
                                   stderr=subprocess.PIPE).strip()


def relative(value):
    path = Path(value)
    if path.is_absolute() or '..' in path.parts or not path.parts:
        raise ValueError('invalid relative path: ' + str(value))
    return path


def load(lock):
    lock = Path(lock).resolve()
    data = json.loads(lock.read_text())
    if data.get('schema') != 1:
        raise ValueError('unsupported repository lock schema')
    for name, item in data['components'].items():
        if not re.fullmatch(r'[a-z0-9-]+', item['directory']):
            raise ValueError('invalid directory: ' + item['directory'])
        if not re.fullmatch(r'[0-9a-f]{40}', item['revision']):
            raise ValueError('invalid revision: ' + name)
    for path, item in data.get('links', {}).items():
        relative(path)
        relative(item['path']) if item['path'] != '.' else None
        if item['component'] not in data['components']:
            raise ValueError('unknown link component: ' + item['component'])
    return lock.parent, data


def check_repository(path, revision):
    if not path.is_dir():
        raise ValueError('missing repository: ' + str(path) + '; run repositories.py bootstrap --seed-root PATH')
    if path.is_symlink() or Path(git('-C', path, 'rev-parse', '--show-toplevel')).resolve() != path.resolve():
        raise ValueError('not an independent repository: ' + str(path))
    if git('-C', path, 'rev-parse', 'HEAD') != revision:
        raise ValueError('revision mismatch: ' + str(path))
    status = git('-C', path, 'status', '--porcelain', '--untracked-files=all')
    if status:
        raise ValueError('dirty repository: ' + str(path) + '\n' + status)


def verify(lock=DEFAULT_LOCK, components=None):
    launcher, data = load(lock)
    selected = set(data['components']) if components is None else set(components)
    pending = list(selected)
    while pending:
        name = pending.pop()
        for dependency in data['components'][name].get('dependencies', []):
            if dependency not in selected:
                selected.add(dependency)
                pending.append(dependency)
    for name, item in data['components'].items():
        if name in selected:
            check_repository(launcher.parent / item['directory'], item['revision'])
    for path, item in data.get('links', {}).items():
        if item['component'] not in selected:
            continue
        link = launcher / path
        expected = launcher.parent / data['components'][item['component']]['directory'] / item['path']
        if not link.is_symlink() or link.readlink().is_absolute() or link.resolve() != expected.resolve() or not expected.exists():
            raise ValueError('link mismatch: ' + str(link))
    return data


def run_recipe(component, filename, namespace, integration_root=None):
    """Execute an owned recipe after checking its pinned repository and dependencies."""
    launcher = Path(integration_root or DEFAULT_LOCK.parent).resolve()
    data = verify(launcher / 'repositories.lock.json', (component,))
    recipe = launcher.parent / data['components'][component]['directory'] / relative(filename)
    previous = os.environ.get('ORBIS_LAUNCHER_ROOT')
    os.environ['ORBIS_LAUNCHER_ROOT'] = str(launcher)
    namespace['__file__'] = str(recipe)
    try:
        exec(compile(recipe.read_bytes(), str(recipe), 'exec'), namespace)
    finally:
        if previous is None:
            os.environ.pop('ORBIS_LAUNCHER_ROOT', None)
        else:
            os.environ['ORBIS_LAUNCHER_ROOT'] = previous


def bootstrap(lock=DEFAULT_LOCK, seed_root=None):
    launcher, data = load(lock)
    # Reject mismatches before creating any missing repositories. Never reset existing work.
    for item in data['components'].values():
        destination = launcher.parent / item['directory']
        if destination.exists() or destination.is_symlink():
            check_repository(destination, item['revision'])
    for name, item in data['components'].items():
        destination = launcher.parent / item['directory']
        if destination.exists():
            continue
        source = None
        if seed_root:
            for candidate in (Path(seed_root) / item['directory'], Path(seed_root) / (item['directory'] + '.bundle')):
                if candidate.exists():
                    source = str(candidate.resolve())
                    break
        source = source or item.get('url')
        if not source:
            raise ValueError('missing repository seed or published URL: ' + name)
        # Clone in a sibling staging directory; a bad pin cannot leave a partial destination.
        staging = Path(tempfile.mkdtemp(prefix='.port-clone-', dir=launcher.parent))
        try:
            shallow = Path(source + '.shallow')
            if Path(source).is_file() and shallow.is_file():
                boundaries = shallow.read_text().splitlines()
                if not boundaries or any(not re.fullmatch(r'[0-9a-f]{40}', line) for line in boundaries):
                    raise ValueError('invalid shallow bundle boundaries: ' + str(shallow))
                # Bundles omit shallow metadata. Install the saved boundary before Git
                # traverses the imported objects, then restore all advertised refs.
                git('init', '--quiet', staging / 'repo')
                (staging / 'repo/.git/shallow').write_text('\n'.join(boundaries) + '\n')
                git('-C', staging / 'repo', 'fetch', '--quiet', '--update-head-ok', source, '+refs/*:refs/*')
            else:
                git('clone', '--quiet', '--no-checkout', '--no-hardlinks', source, staging / 'repo')
            git('-C', staging / 'repo', 'checkout', '--quiet', '--detach', item['revision'])
            check_repository(staging / 'repo', item['revision'])
            remotes = git('-C', staging / 'repo', 'remote').splitlines()
            if not item.get('url') and 'origin' in remotes:
                git('-C', staging / 'repo', 'remote', 'remove', 'origin')
            elif item.get('url'):
                git('-C', staging / 'repo', 'remote', 'set-url' if 'origin' in remotes else 'add', 'origin', item['url'])
            (staging / 'repo').rename(destination)
        finally:
            shutil.rmtree(staging)
    return verify(lock)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('verify', 'bootstrap'))
    parser.add_argument('--lock', type=Path, default=DEFAULT_LOCK)
    parser.add_argument('--seed-root', type=Path, help='directory containing sibling repositories or NAME.bundle files')
    args = parser.parse_args()
    try:
        data = bootstrap(args.lock, args.seed_root) if args.command == 'bootstrap' else verify(args.lock)
        for name, item in data['components'].items():
            print(f'{name}: {item["revision"]}')
        print('Repository pins and compatibility links verified.')
    except subprocess.CalledProcessError as error:
        parser.exit(1, str(error) + '\n' + (error.stderr or ''))
    except (ValueError, KeyError, OSError) as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()
