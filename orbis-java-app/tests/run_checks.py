#!/usr/bin/env python3
"""Explicit host/source checks; binary checks require built artifact paths."""
import argparse
from pathlib import Path
import subprocess
import sys

parser = argparse.ArgumentParser()
parser.add_argument('--sdk', required=True)
parser.add_argument('--toolchain', required=True)
settings = parser.parse_args()
root = Path(__file__).resolve().parents[1]
artifact_checks = {
    'test_audit_orbis_binaries.py',
    'test_prx_initializers.py',
    'test_orbis_runtime_binary.py',
    'test_orbis_signal_diagnostic_binary.py',
    'test_runtime_image_modules.py',
}

for test in sorted((root / 'tests').glob('test_*.py')):
    if test.name in artifact_checks:
        continue
    arguments = []
    if test.name in {'test_orbis_stat_abi.py', 'test_orbis_signal_abi.py'}:
        arguments = ['--cc', str(Path(settings.sdk) / 'bin/orbis-clang'),
                     '--toolchain', settings.toolchain, '--jdk', str(root.parent)]
    if test.name == 'test_orbis_clock_abi.py':
        arguments = ['--sdk', settings.sdk, '--toolchain', settings.toolchain]
    if test.name == 'test_native_loader.py':
        arguments = ['launcher.c', 'Makefile']
    print('CHECK ' + test.name, flush=True)
    subprocess.run([sys.executable, str(test), *arguments], cwd=root, check=True)

print('All explicit host/source checks passed; binary/image checks accept artifact paths separately')
