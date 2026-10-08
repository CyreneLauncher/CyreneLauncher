"""Pinned LWJGL/dyncall archives and official Java JARs from lwjgl/lwjgl-sources.lock.json."""
import hashlib
import json
import shutil
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / 'lwjgl/lwjgl-sources.lock.json').read_text())


def archive(name, downloads, fetch=False):
    """Returns the verified archive for LOCK[name], downloading it only when fetch is set."""
    entry = LOCK[name]
    path = Path(downloads) / entry['filename']
    if not path.is_file():
        if not fetch:
            raise SystemExit(f'missing {path}; download {entry["url"]} or pass --fetch')
        path.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(entry['url']) as response, path.open('wb') as target:
            shutil.copyfileobj(response, target)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != entry['sha256']:
        raise SystemExit(f'{path} SHA-256 {digest} is not the pinned {entry["sha256"]}')
    return path


def extract(path, destination):
    """Fresh extraction; returns the archive's single top-level directory."""
    destination = Path(destination)
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    with tarfile.open(path) as tar:
        tar.extractall(destination, filter='data')
    (top,) = destination.iterdir()
    return top


def java_artifact(jars, filename):
    """An official LWJGL Java JAR, verified against its pinned SHA-1."""
    path = Path(jars) / filename
    # Each LWJGL release has its own table; file names carry the version, so they do not collide.
    tables = [entry for key, entry in LOCK.items() if key.startswith('java_artifacts')]
    expected = next((table[filename] for table in tables if filename in table), None)
    if expected is None:
        raise SystemExit(f'{filename} is not a pinned LWJGL Java artifact')
    if not path.is_file():
        raise SystemExit(f'missing official {filename} under {jars}')
    digest = hashlib.sha1(path.read_bytes()).hexdigest()
    if digest != expected:
        raise SystemExit(f'{path} SHA-1 {digest} is not the audited {expected}')
    return path
