#!/usr/bin/env python3
"""Validate the independent runner package's complete file boundary."""
from pathlib import Path
import sys
root = Path(sys.argv[1])
allowed = {'eboot.bin', 'app.jar', 'runner/README.txt', 'sce_sys/param.sfo', 'sce_sys/icon0.png', 'sce_sys/about/right.sprx', 'sce_module/libc.prx', 'sce_module/libSceFios2.prx', 'package.gp4'}
actual = {str(p.relative_to(root)) for p in root.rglob('*') if p.is_file() or p.is_symlink()}
assert not actual - allowed, f'Unexpected runner payload: {sorted(actual - allowed)}'
assert allowed - {'package.gp4'} <= actual, f'Missing runner files: {sorted(allowed - actual)}'
assert not any(p.is_symlink() for p in root.rglob('*')), 'Runner contains symlinks'
assert (root/'eboot.bin').read_bytes()[:4] == b'\x4f\x15\x3d\x1d', 'Runner eboot is not SELF'
print('Independent runner package boundary passed')
