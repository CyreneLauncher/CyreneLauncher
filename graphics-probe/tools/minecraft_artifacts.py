"""Verified, confined downloads for the host-only Minecraft preparation tool."""

import hashlib
import os
import re
import shutil
import tempfile
import urllib.request
from pathlib import Path

SHA1 = re.compile(r'[0-9a-f]{40}\Z')


def verify_file(path: Path, sha1: str, size: int) -> bool:
    if not path.is_file() or path.is_symlink() or path.stat().st_size != size:
        return False
    digest = hashlib.sha1()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest() == sha1


def confined(root: Path, destination: Path) -> Path:
    root = Path(os.path.abspath(root))
    destination = Path(os.path.abspath(destination))
    if any(part.is_symlink() for part in (root, *root.parents)) or not destination.is_relative_to(root) or destination == root:
        raise ValueError(f'destination outside output root: {destination}')
    current = root
    for part in destination.relative_to(root).parts:
        if part in ('', '.', '..'):
            raise ValueError('unsafe destination segment')
        current = current / part
        if current.is_symlink():
            raise ValueError(f'symlink in destination: {current}')
    return destination


def _atomic_copy(source: Path, destination: Path) -> None:
    fd, name = tempfile.mkstemp(prefix='.download-', dir=destination.parent)
    try:
        with os.fdopen(fd, 'wb') as target, source.open('rb') as src:
            shutil.copyfileobj(src, target, 1024 * 1024)
            target.flush()
            os.fsync(target.fileno())
        os.replace(name, destination)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def fetch_verified(url: str, destination: Path, sha1: str, size: int,
                   cache_root: Path, *, output_root: Path) -> Path:
    """Fetch a declared artifact; publish only matching bytes beneath output_root."""
    if not isinstance(sha1, str) or not SHA1.fullmatch(sha1) or type(size) is not int or size < 0:
        raise ValueError('invalid artifact SHA-1 or size')
    destination = confined(output_root, destination)
    if destination.exists():
        if verify_file(destination, sha1, size):
            return destination
        raise ValueError(f'conflicting destination: {destination}')
    destination.parent.mkdir(parents=True, exist_ok=True)
    confined(output_root, destination)
    cache_root = Path(cache_root)
    if any(part.is_symlink() for part in (cache_root, *cache_root.parents)):
        raise ValueError('symlink cache root or ancestor')
    cache_root.mkdir(parents=True, exist_ok=True)
    cached = confined(cache_root, cache_root / sha1[:2] / sha1)
    cached.parent.mkdir(parents=True, exist_ok=True)
    cached = confined(cache_root, cached)
    if not verify_file(cached, sha1, size):
        if cached.exists():
            cached.unlink()
        fd, name = tempfile.mkstemp(prefix='.download-', dir=cached.parent)
        try:
            with os.fdopen(fd, 'wb') as target, urllib.request.urlopen(url, timeout=60) as response:
                while block := response.read(1024 * 1024):
                    target.write(block)
                target.flush()
                os.fsync(target.fileno())
            if not verify_file(Path(name), sha1, size):
                raise ValueError(f'artifact size/SHA-1 mismatch: {url}')
            os.replace(name, cached)
        finally:
            if os.path.exists(name):
                os.unlink(name)
    _atomic_copy(cached, destination)
    if not verify_file(destination, sha1, size):
        destination.unlink(missing_ok=True)
        raise ValueError('published artifact failed verification')
    return destination
