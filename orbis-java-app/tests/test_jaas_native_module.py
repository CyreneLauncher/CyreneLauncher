#!/usr/bin/env python3
"""JAAS is an unconditional converted runtime dependency."""
from pathlib import Path
builder = Path('tools/build_native_modules.py').read_text()
assert "('jaas', [])" in builder
from test_runtime_export import *
print('JAAS standalone runtime packaging passed')
