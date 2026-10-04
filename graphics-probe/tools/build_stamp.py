"""Skips a component build whose inputs are unchanged since its last successful build.

A stamp is a digest of everything the build reads: file contents for sources and scripts, and
size plus modification time for toolchain trees too large to hash on every run. The stamp is
removed before a build starts and written only when it finishes, so a failed or interrupted build
never leaves one behind. ORBIS_REBUILD=1 ignores every stamp.
"""
import hashlib
import json
import os
from pathlib import Path


class Stamp:
    def __init__(self, out, name):
        self.path = Path(out) / f'.{name}.inputs.sha256'
        self.digest = hashlib.sha256()

    def value(self, label, item):
        self.digest.update(json.dumps([label, item], sort_keys=True, default=str).encode() + b'\n')
        return self

    def files(self, *paths):
        """Contents of each file, or of every file under each directory."""
        for path in map(Path, paths):
            for file in _walk(path):
                self.value(str(file), hashlib.sha256(file.read_bytes()).hexdigest())
        return self

    def stats(self, *paths):
        """Size and modification time of each file, or of every file under each directory."""
        for path in map(Path, paths):
            for file in _walk(path):
                info = file.stat()
                self.value(str(file), [info.st_size, info.st_mtime_ns])
        return self

    def current(self, *outputs):
        """True when the last successful build had these inputs and its outputs are still present."""
        if os.environ.get('ORBIS_REBUILD', '') not in ('', '0'):
            return False
        try:
            recorded = self.path.read_text().strip()
        except OSError:
            return False
        return recorded == self.digest.hexdigest() and all(Path(output).is_file() for output in outputs)

    def invalidate(self):
        self.path.unlink(missing_ok=True)

    def record(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(self.digest.hexdigest() + '\n')


def _walk(path):
    if path.is_dir():
        for root, dirs, files in os.walk(path):
            dirs.sort()
            for name in sorted(files):
                yield Path(root) / name
    elif path.is_file():
        yield path
    else:
        raise SystemExit(f'build input missing: {path}')
