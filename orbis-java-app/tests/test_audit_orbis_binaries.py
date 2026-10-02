#!/usr/bin/env python3
"""Regression tests for final-ELF Orbis boundary inspection."""

import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SCANNER = ROOT / "tools" / "audit_orbis_binaries.py"
POLICY = ROOT / "tools" / "orbis_native_policy.json"
MANIFEST = ROOT / "tests" / "testdata" / "audit_binaries" / "manifest.json"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--launcher', type=Path, default=ROOT/'build-runner/launcher.elf')
    parser.add_argument('--native', type=Path, default=ROOT/'build-jdk-orbis-x86_64-server-fastdebug/native')
    args = parser.parse_args()
    manifest = json.loads(MANIFEST.read_text())
    binaries = [args.launcher] + [args.native / name for name in manifest['native_binaries']]
    missing = [path for path in binaries if not path.is_file()]
    if missing:
        raise SystemExit("native audit fixtures missing; build first: "
                         + ", ".join(map(str, missing)))
    with tempfile.TemporaryDirectory() as directory:
        output = Path(directory) / "report.json"
        result = subprocess.run([
            sys.executable, str(SCANNER), "--policy", str(POLICY),
            "--output", str(output), *map(str, binaries),
        ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        assert result.returncode == 0, result.stdout
        report = json.loads(output.read_text())
        findings = {(item["binary"], item["symbol"]): item
                    for item in report["findings"]}
        assert not any(symbol == "_exit" and binary.endswith("libjvm.so") for binary,symbol in findings), findings
        assert not any(symbol == "fstatat64" and binary.endswith("libnio.so") for binary,symbol in findings), findings
    print("Orbis binary audit tests passed")


if __name__ == "__main__":
    main()
