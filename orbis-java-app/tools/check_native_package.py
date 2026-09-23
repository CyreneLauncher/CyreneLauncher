#!/usr/bin/env python3
"""Reject raw ELF native libraries before generating a test package."""
from pathlib import Path
import sys
root = Path(sys.argv[1])
required = [root/'sce_module/libjvm.prx'] + [
    root/f'java/lib/lib{x}.so'
    for x in ('java', 'net', 'nio', 'zip', 'verify', 'jimage')
]
files = set(required) | set(root.rglob('*.so')) | set(root.rglob('*.prx'))
for path in sorted(files):
    with path.open('rb') as stream:
        if stream.read(4) != b'O\x15=\x1d':
            raise SystemExit(f'Not a native SELF module: {path}')
print(f'Native package check passed: {len(files)} modules, no raw ELF libraries')
