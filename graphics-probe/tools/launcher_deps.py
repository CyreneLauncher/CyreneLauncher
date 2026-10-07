"""The launcher's pinned third-party JARs (launcher/twelvemonkeys.lock.json): fetched once into
build/downloads/twelvemonkeys and checked against their SHA-256 on every use."""
import hashlib
import json
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / 'launcher/twelvemonkeys.lock.json'
CACHE = ROOT / 'build/downloads/twelvemonkeys'


def lock():
    return json.loads(LOCK.read_text())


def jars():
    """Paths of the verified JARs, downloading any that are missing."""
    pinned = lock()
    license_file = ROOT / 'launcher' / pinned['license_file']
    if hashlib.sha256(license_file.read_bytes()).hexdigest() != pinned['license_sha256']:
        raise SystemExit('TwelveMonkeys license file differs from its lock')
    CACHE.mkdir(parents=True, exist_ok=True)
    out = []
    for name, item in sorted(pinned['jars'].items()):
        path = CACHE / name
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
        out.append(path)
    return out
