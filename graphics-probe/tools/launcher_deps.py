"""The launcher's pinned third-party JARs (launcher/twelvemonkeys.lock.json, launcher/xz.lock.json): fetched
once into build/downloads/<lock> and checked against their SHA-256 on every use. Their classes, all under each
lock's package, are merged into orbis-launcher.jar."""
import hashlib
import json
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCKS = [ROOT / 'launcher/twelvemonkeys.lock.json', ROOT / 'launcher/xz.lock.json']


def locks():
    return [(path, json.loads(path.read_text())) for path in LOCKS]


def jars():
    """Paths of the verified JARs, downloading any that are missing."""
    return [path for path, _ in merged()]


def merged():
    """(JAR path, package prefix its classes must be under) for every pinned JAR."""
    out = []
    for lock_path, pinned in locks():
        license_file = ROOT / 'launcher' / pinned['license_file']
        if hashlib.sha256(license_file.read_bytes()).hexdigest() != pinned['license_sha256']:
            raise SystemExit(f'{license_file.name} differs from {lock_path.name}')
        cache = ROOT / 'build/downloads' / lock_path.name.removesuffix('.lock.json')
        cache.mkdir(parents=True, exist_ok=True)
        for name, item in sorted(pinned['jars'].items()):
            path = cache / name
            if not path.is_file():
                request = urllib.request.Request(item['url'], headers={'User-Agent': 'orbis-launcher-build'})
                with urllib.request.urlopen(request, timeout=60) as response:
                    data = response.read()
                if hashlib.sha256(data).hexdigest() != item['sha256']:
                    raise SystemExit(f'{name}: downloaded file does not match its pinned SHA-256')
                part = path.with_suffix('.part')
                part.write_bytes(data)
                part.replace(path)
            if hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
                raise SystemExit(f'{name}: cached file does not match its pinned SHA-256')
            out.append((path, pinned['package']))
    return out
