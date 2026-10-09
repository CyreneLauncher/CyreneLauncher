#!/usr/bin/env python3
"""Install the locked open-source PS4 SDKs and build the PKG CLI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def verify(path, expected):
    if digest(path) != expected:
        raise ValueError('Toolchain hash mismatch: ' + str(path))


def relative(name):
    path = Path(name)
    if path.is_absolute() or '..' in path.parts:
        raise ValueError('Unsafe SDK member: ' + name)
    return path


def unpack(archive, destination):
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as source:
            for member in source.infolist():
                relative(member.filename)
                mode = member.external_attr >> 16
                if stat.S_ISLNK(mode):
                    raise ValueError('Link in SDK zip: ' + member.filename)
            for member in source.infolist():
                target = destination / relative(member.filename)
                if member.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(source.read(member))
                    target.chmod((member.external_attr >> 16) & 0o777 or 0o644)
    else:
        with tarfile.open(archive, 'r:gz') as source:
            for member in source.getmembers():
                relative(member.name)
            source.extractall(destination, filter='data')


def run(*args, **kwargs):
    subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def llvm_config_path(major):
    executable = shutil.which('llvm-config-' + str(major))
    if not executable:
        raise ValueError('Missing LLVM ' + str(major) + ' development tools')
    # The SDK resolves this value as a filename, not as a PATH command name.
    return str(Path(executable).resolve(strict=True))


def provision(destination, lock_path):
    lock = json.loads(lock_path.read_text())
    destination = destination.resolve()
    destination.mkdir(parents=True, exist_ok=True)
    for name, item in lock['archives'].items():
        archive = destination / item['filename']
        if not archive.exists():
            partial = archive.with_name(archive.name + '.partial')
            try:
                urllib.request.urlretrieve(item['url'], partial)
                verify(partial, item['sha256'])
                partial.replace(archive)
            finally:
                partial.unlink(missing_ok=True)
        verify(archive, item['sha256'])
        unpack(archive, destination)
    sdk = destination / lock['archives']['payload']['directory']
    toolchain = destination / lock['archives']['openorbis']['directory']
    for name, expected in lock['toolchain_files'].items():
        verify(toolchain / relative(name), expected)
    pkg = lock['pkgtool']
    source = destination / 'LibOrbisPkg'
    if not source.exists():
        run('git', 'init', '--quiet', source)
        run('git', '-C', source, 'remote', 'add', 'origin', pkg['url'])
        run('git', '-C', source, 'fetch', '--quiet', '--depth=1', 'origin', pkg['revision'])
        run('git', '-C', source, 'checkout', '--quiet', '--detach', pkg['revision'])
    head = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if head != pkg['revision']:
        raise ValueError('PKG tool source revision mismatch')
    status = subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain'], text=True)
    if status:
        raise ValueError('PKG tool source has local changes')
    (destination / 'global.json').write_text(json.dumps({
        'sdk': {'version': lock['dotnet_sdk'], 'rollForward': 'disable'}}) + '\n')
    build_env = {**os.environ, 'DOTNET_CLI_HOME': str(destination / '.dotnet'),
                 'NUGET_PACKAGES': str(destination / '.nuget'),
                 'DOTNET_CLI_TELEMETRY_OPTOUT': '1'}
    run('dotnet', 'publish', source / 'PkgTool.Core/PkgTool.Core.csproj',
        '--configuration', 'Release', '--output', destination / 'pkgtool',
        cwd=destination, env=build_env)
    env = {'ORBIS_SDK': str(sdk), 'PS4_TOOLCHAIN': str(toolchain),
           'PKGTOOL': str(destination / 'pkgtool/PkgTool.Core.dll'),
           'LLVM_CONFIG': llvm_config_path(lock['llvm_major'])}
    run(sdk / 'bin/clang', '--version', cwd=destination, env={**build_env, **env})
    env_path = os.environ.get('GITHUB_ENV')
    if env_path:
        with Path(env_path).open('a') as output:
            for name, value in env.items():
                output.write(name + '=' + value + '\n')
    print(json.dumps(env, indent=2))
    return env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination', type=Path, required=True)
    parser.add_argument('--lock', type=Path, default=ROOT / 'tools/toolchain.lock.json')
    args = parser.parse_args()
    provision(args.destination, args.lock)


if __name__ == '__main__':
    main()
