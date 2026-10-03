#!/usr/bin/env python3
"""Prepare an official Minecraft 1.18.2 upload tree for the Orbis GPU runner."""

import argparse
import concurrent.futures
import ctypes
import errno
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import urllib.request
import uuid
from pathlib import Path

from minecraft_arguments import argument_lines, game_arguments
from minecraft_artifacts import confined, fetch_verified, verify_file

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
MANIFEST_URL = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
METADATA_SHA1 = '334b33fcba3c9be4b7514624c965256535bd7eba'
METADATA_SIZE = 30469
CLIENT_SHA1 = '2e9a3e3107cca00d6bc9c97bf7d149cae163ef21'
ASSET_INDEX_SHA1 = 'd31a2e85ae149dd1b1a7070b22cb8887892fda6c'
DEVICE = '/data/orbis-jdk'
REQUIRED = {'runner/target.jar', 'runner/version.json', 'runner/runner.conf',
            'runner/app-args.txt', 'runner/jvm-options.txt',
            'assets/indexes/1.18.json'}
PLAIN_PATH = re.compile(r'[A-Za-z0-9._+\-/]+\Z')
SHA1 = re.compile(r'[0-9a-f]{40}\Z')


def offline_uuid(username: str) -> str:
    if not username or any(c in username for c in '\r\n\x00'):
        raise ValueError('invalid offline username')
    digest = bytearray(hashlib.md5(('OfflinePlayer:' + username).encode('utf-8')).digest())
    digest[6] = (digest[6] & 0x0f) | 0x30
    digest[8] = (digest[8] & 0x3f) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def _json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(value, dict):
        raise ValueError(f'expected JSON object: {path}')
    return value


def _safe_relative(value: str) -> str:
    if not isinstance(value, str) or not PLAIN_PATH.fullmatch(value) or value.startswith('/'):
        raise ValueError(f'unsafe relative artifact path: {value}')
    if any(part in ('', '.', '..') for part in value.split('/')):
        raise ValueError(f'unsafe artifact path segment: {value}')
    return value


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def _fetch(url, dest, sha1, size, cache, stage):
    return fetch_verified(url, dest, sha1, size, cache, output_root=stage)


def _host_plan(stage: Path, boot_jdk: Path) -> dict:
    with tempfile.TemporaryDirectory(prefix='.' + stage.name + '-host-', dir=stage.parent) as temporary:
        scratch = Path(temporary)
        classes = scratch / 'classes'
        classes.mkdir()
        sources = sorted((REPO / 'orbis-java-app/src/runner/lwjgl').glob('*.java'))
        sources.append(ROOT / 'tools/java/orbis/tools/HostMetadataPlan.java')
        subprocess.run([str(boot_jdk / 'bin/javac'), '--release', '17', '-d', str(classes),
                        *map(str, sources)], check=True)
        plan_file = scratch / 'plan.json'
        subprocess.run([str(boot_jdk / 'bin/java'), '-cp', str(classes),
                        'orbis.tools.HostMetadataPlan', str(stage / 'runner/version.json'),
                        str(stage / 'libraries'), str(plan_file)], check=True)
        return _json(plan_file)


def _candidate_libraries(metadata: dict):
    """Fetch candidate common JARs; the Java resolver decides final inventory/order."""
    selected = {}
    for library in metadata['libraries']:
        rules = library.get('rules')
        allowed = rules is None
        if rules is not None:
            allowed = False
            for rule in rules:
                if set(rule) - {'action', 'os'} or rule['action'] not in ('allow', 'disallow'):
                    raise ValueError('unsupported library rule')
                os_rule = rule.get('os', {})
                if set(os_rule) - {'name', 'arch'}:
                    raise ValueError('unsupported library OS rule')
                if all(v == {'name': 'linux', 'arch': 'x86_64'}[k] for k, v in os_rule.items()):
                    allowed = rule['action'] == 'allow'
        if not allowed:
            continue
        if len(library['name'].split(':')) == 4 and library['name'].split(':')[3].startswith('natives-'):
            continue
        artifact = library.get('downloads', {}).get('artifact')
        if artifact:
            relative = _safe_relative(artifact['path'])
            previous = selected.setdefault(relative, artifact)
            if previous != artifact:
                raise ValueError(f'conflicting artifact destination: {relative}')
    return selected


def verify_upload_tree(root: Path, *, boot_jdk: Path = Path('/usr/lib/jvm/java-17-openjdk-amd64')) -> dict:
    root = Path(root)
    manifest = _json(root / 'launch-manifest.json')
    files = manifest.get('files')
    if not isinstance(files, dict) or not REQUIRED.issubset(files):
        raise ValueError('incomplete upload manifest')
    host_files = manifest.get('host_files')
    if not isinstance(host_files, dict) or set(host_files) != {'UPLOAD.txt'}:
        raise ValueError('incomplete host instructions inventory')
    if not (root / 'minecraft').is_dir() or (root / 'minecraft').is_symlink():
        raise ValueError('missing game directory')
    classpath = manifest.get('classpath')
    if not isinstance(classpath, list) or not classpath or len(classpath) != len(set(classpath)):
        raise ValueError('missing or duplicate classpath')
    if {p.removeprefix('libraries/') for p in files if p.startswith('libraries/')} != set(classpath):
        raise ValueError('partial or extra library tree')
    if not any(p.startswith('assets/objects/') for p in files):
        raise ValueError('missing asset objects')
    if any(p.is_symlink() for p in root.rglob('*')):
        raise ValueError('symlink in upload tree')
    actual = {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()}
    if actual != set(files) | set(host_files) | {'launch-manifest.json'}:
        raise ValueError('partial or extra upload tree')
    for relative, info in {**files, **host_files}.items():
        _safe_relative(relative)
        path = confined(root, root / relative)
        if not path.is_file() or path.stat().st_size != info['size'] or _sha256(path) != info['sha256']:
            raise ValueError(f'upload mismatch: {relative}')
        if relative in files and info.get('device_path') != DEVICE + '/' + relative:
            raise ValueError(f'wrong device path: {relative}')
        if relative in host_files and 'device_path' in info:
            raise ValueError(f'host instruction has device path: {relative}')
    metadata_path = root / 'runner/version.json'
    metadata = _json(metadata_path)
    if metadata.get('id') != manifest.get('version') or not verify_file(
            metadata_path, manifest['version_metadata_sha1'], metadata_path.stat().st_size):
        raise ValueError('version metadata mismatch')
    client = metadata['downloads']['client']
    if client['sha1'] != manifest['client_sha1'] or not verify_file(
            root / 'runner/target.jar', client['sha1'], client['size']):
        raise ValueError('client mismatch')
    index = metadata['assetIndex']
    index_path = root / 'assets/indexes/1.18.json'
    if index['sha1'] != manifest['asset_index_sha1'] or not verify_file(
            index_path, index['sha1'], index['size']):
        raise ValueError('asset index mismatch')
    libraries = _candidate_libraries(metadata)
    if set(libraries) != set(classpath):
        raise ValueError('metadata library inventory mismatch')
    for relative, artifact in libraries.items():
        if not verify_file(root / 'libraries' / relative, artifact['sha1'], artifact['size']):
            raise ValueError(f'library mismatch: {relative}')
    objects = {entry['hash']: entry['size'] for entry in _json(index_path)['objects'].values()}
    expected = {f'assets/objects/{sha1[:2]}/{sha1}' for sha1 in objects}
    actual_objects = {p for p in files if p.startswith('assets/objects/')}
    if actual_objects != expected:
        raise ValueError('asset object inventory mismatch')
    for sha1, size in objects.items():
        if not verify_file(root / 'assets/objects' / sha1[:2] / sha1, sha1, size):
            raise ValueError(f'asset object mismatch: {sha1}')
    plan = _host_plan(root, Path(boot_jdk))
    if (plan['classpath'] != classpath or plan['profile'] != manifest.get('profile')
            or plan['main_class'] != manifest.get('main_class')
            or plan['skipped_natives'] != manifest.get('skipped_native_classifiers')):
        raise ValueError('upload manifest differs from ordered Java resolver plan')
    return manifest


def _atomic_exchange(left: Path, right: Path) -> None:
    """Exchange two same-filesystem directories in one Linux renameat2 operation."""
    libc = ctypes.CDLL(None, use_errno=True)
    renameat2 = getattr(libc, 'renameat2', None)
    if renameat2 is None:
        raise OSError(errno.ENOSYS, 'renameat2 unavailable')
    renameat2.argtypes = (ctypes.c_int, ctypes.c_char_p,
                          ctypes.c_int, ctypes.c_char_p, ctypes.c_uint)
    renameat2.restype = ctypes.c_int
    at_fdcwd = -100
    rename_exchange = 2
    if renameat2(at_fdcwd, os.fsencode(left), at_fdcwd,
                 os.fsencode(right), rename_exchange) != 0:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error))


def _recover_previous(output: Path, backup: Path, boot_jdk: Path) -> None:
    """Complete recovery after a prior interrupted two-rename fallback."""
    if not backup.exists() and not backup.is_symlink():
        return
    if backup.is_symlink() or not backup.is_dir():
        raise ValueError(f'unsafe previous output: {backup}')
    verify_upload_tree(backup, boot_jdk=boot_jdk)
    if output.exists():
        verify_upload_tree(output, boot_jdk=boot_jdk)
        shutil.rmtree(backup)
    else:
        backup.rename(output)


def _publish(stage: Path, output: Path, backup: Path) -> None:
    if not output.exists():
        stage.rename(output)
        return
    try:
        _atomic_exchange(stage, output)
    except OSError as failure:
        if failure.errno not in (errno.ENOSYS, errno.EINVAL, errno.EOPNOTSUPP):
            raise
        output.rename(backup)
        try:
            stage.rename(output)
        except BaseException:
            if backup.exists() and not output.exists():
                backup.rename(output)
            raise
        shutil.rmtree(backup)
    else:
        # After the exchange, stage names the old complete output tree.
        shutil.rmtree(stage)


def prepare(version: str, output: Path, cache: Path, username: str, *,
            boot_jdk: Path = Path('/usr/lib/jvm/java-17-openjdk-amd64'),
            java_home: str = DEVICE + '/jdk/release-desktop', manifest_url: str = MANIFEST_URL,
            metadata_sha1: str = METADATA_SHA1, metadata_size: int = METADATA_SIZE,
            client_sha1: str = CLIENT_SHA1,
            asset_index_sha1: str = ASSET_INDEX_SHA1,
            asset_base_url: str = 'https://resources.download.minecraft.net') -> dict:
    if version != '1.18.2':
        raise ValueError('only Minecraft 1.18.2 is supported')
    identity = offline_uuid(username)
    if not java_home.startswith('/') or any(c in java_home for c in '\r\n\x00'):
        raise ValueError('java home must be an absolute device path')
    output = Path(os.path.abspath(output))
    cache = Path(cache)
    if output.is_symlink() or any(parent.is_symlink() for parent in output.parents):
        raise ValueError('symlink output path')
    output.parent.mkdir(parents=True, exist_ok=True)
    backup = output.parent / ('.' + output.name + '-previous')
    _recover_previous(output, backup, Path(boot_jdk))
    stage = Path(tempfile.mkdtemp(prefix='.' + output.name + '-', dir=output.parent))
    try:
        with urllib.request.urlopen(manifest_url, timeout=60) as response:
            versions = json.load(response)['versions']
        declaration = next((v for v in versions if v['id'] == version), None)
        if declaration is None or declaration['sha1'] != metadata_sha1:
            raise ValueError('version manifest metadata SHA-1 mismatch')
        metadata_file = _fetch(declaration['url'], stage / 'runner/version.json',
                               metadata_sha1, metadata_size, cache, stage)
        metadata = _json(metadata_file)
        if metadata['id'] != version:
            raise ValueError('version metadata ID mismatch')
        client = metadata['downloads']['client']
        if client['sha1'] != client_sha1:
            raise ValueError('client SHA-1 mismatch')
        _fetch(client['url'], stage / 'runner/target.jar', client_sha1, client['size'], cache, stage)
        index = metadata['assetIndex']
        if index['id'] != '1.18' or index['sha1'] != asset_index_sha1:
            raise ValueError('asset index mismatch')
        index_path = _fetch(index['url'], stage / 'assets/indexes/1.18.json',
                            index['sha1'], index['size'], cache, stage)
        objects = _json(index_path)['objects']
        selected = _candidate_libraries(metadata)
        jobs = [(a['url'], stage / 'libraries' / relative, a['sha1'], a['size'])
                for relative, a in selected.items()]
        unique_assets = {}
        for name, info in objects.items():
            sha1, size = info['hash'], info['size']
            if not SHA1.fullmatch(sha1) or type(size) is not int or size < 0:
                raise ValueError(f'invalid asset object: {name}')
            if sha1 in unique_assets and unique_assets[sha1] != size:
                raise ValueError('conflicting asset object size')
            unique_assets[sha1] = size
        jobs.extend((f'{asset_base_url}/{sha1[:2]}/{sha1}',
                     stage / 'assets/objects' / sha1[:2] / sha1, sha1, size)
                    for sha1, size in unique_assets.items())
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            futures = [pool.submit(_fetch, url, path, sha1, size, cache, stage)
                       for url, path, sha1, size in jobs]
            for future in concurrent.futures.as_completed(futures):
                future.result()
        plan = _host_plan(stage, Path(boot_jdk))
        planned = set(plan['classpath'])
        if planned != set(selected) or len(planned) != len(plan['classpath']):
            raise ValueError('host/device selected library inventory differs')
        (stage / 'minecraft').mkdir()
        values = {'auth_player_name': username, 'version_name': version,
                  'game_directory': DEVICE + '/minecraft', 'assets_root': DEVICE + '/assets',
                  'assets_index_name': '1.18', 'auth_uuid': identity.replace('-', ''),
                  'auth_access_token': '0', 'clientid': '', 'auth_xuid': '',
                  'user_type': 'legacy', 'version_type': metadata['type']}
        args = game_arguments(metadata, values,
                              {'is_demo_user': False, 'has_custom_resolution': False})
        runner = stage / 'runner'
        (runner / 'app-args.txt').write_text(argument_lines(args), encoding='utf-8')
        # Saved worlds load their icon through ImageIO, which needs java.desktop's
        # native AWT libraries (release-desktop) in headless mode.
        (runner / 'jvm-options.txt').write_text('-Xms512m\n-Xmx1024m\n-Dorbis.graphics.arenaMib=512\n'
                                                '-Djava.awt.headless=true\n')
        (runner / 'runner.conf').write_text(f'''java_home={java_home}
jar={DEVICE}/runner/target.jar
working_directory={DEVICE}/minecraft
output_log={DEVICE}/runner/runner-output.log
temporary_directory={DEVICE}/runner/tmp
debug=true
display=gpu
graphics_bundle={DEVICE}/graphics
version_metadata={DEVICE}/runner/version.json
libraries={DEVICE}/libraries
''')
        (stage / 'UPLOAD.txt').write_text(f'''Minecraft {version} host-prepared upload tree. No files are deployed by this tool.
Copy runner/ to {DEVICE}/runner/, libraries/ to {DEVICE}/libraries/,
assets/ to {DEVICE}/assets/, and create {DEVICE}/minecraft/ from minecraft/.
Before launch, remove stale {DEVICE}/runner/main-class.txt; the runner uses
version.json mainClass ({plan['main_class']}). Keep runner PKG OJDK00003,
the JDK and graphics bundle independently installed. Test Base Mode first.
Retest graphics smoke close/relaunch and PS-menu closure before this client.
Collect runner-output.log, runner-debug.log, logs/orbis-runner.log and UART.
The initial heap/arena budget is 512 MiB / 1024 MiB / 512 MiB; record memory
evidence and repeat normal close and three relaunches. No automatic device step.
''')
        files = {p.relative_to(stage).as_posix(): {'size': p.stat().st_size, 'sha256': _sha256(p),
                  'device_path': DEVICE + '/' + p.relative_to(stage).as_posix()}
                 for p in sorted(stage.rglob('*')) if p.is_file() and p.name != 'UPLOAD.txt'}
        host_files = {'UPLOAD.txt': {'size': (stage / 'UPLOAD.txt').stat().st_size,
                      'sha256': _sha256(stage / 'UPLOAD.txt')}}
        manifest = {'version': version, 'version_metadata_sha1': metadata_sha1,
                    'client_sha1': client_sha1, 'asset_index_sha1': asset_index_sha1,
                    'main_class': plan['main_class'], 'profile': plan['profile'],
                    'classpath': plan['classpath'], 'skipped_native_classifiers': plan['skipped_natives'],
                    'offline_identity': {'username': username, 'uuid': identity,
                                         'client_id': '', 'xuid': '', 'user_type': 'legacy'},
                    'files': files, 'host_files': host_files}
        (stage / 'launch-manifest.json').write_text(json.dumps(manifest, sort_keys=True, indent=2) + '\n')
        verify_upload_tree(stage, boot_jdk=Path(boot_jdk))
        _publish(stage, output, backup)
        return manifest
    finally:
        if stage.exists():
            shutil.rmtree(stage)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/minecraft-1.18.2')
    parser.add_argument('--cache', type=Path, default=ROOT / 'build/downloads/minecraft')
    parser.add_argument('--username', required=True)
    parser.add_argument('--boot-jdk', type=Path, default=Path('/usr/lib/jvm/java-17-openjdk-amd64'))
    parser.add_argument('--java-home', default=DEVICE + '/jdk/release-desktop')
    options = parser.parse_args()
    manifest = prepare(options.version, options.output, options.cache, options.username,
                       boot_jdk=options.boot_jdk, java_home=options.java_home)
    print(f"Prepared {options.output}: {len(manifest['files'])} verified files, "
          f"{sum(item['size'] for item in manifest['files'].values())} bytes")


if __name__ == '__main__':
    main()
