#!/usr/bin/env python3
"""Regression tests for final-ELF Orbis boundary inspection."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SCANNER = ROOT / "tools" / "audit_orbis_binaries.py"
POLICY = ROOT / "tools" / "orbis_native_policy.json"
MANIFEST = ROOT / "tools" / "testdata" / "audit_binaries" / "manifest.json"


def main() -> None:
    manifest = json.loads(MANIFEST.read_text())
    binaries = [ROOT / path for path in manifest["binaries"]]
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
        assert ("build/native/libjvm.so", "_exit") not in findings, findings
        assert ("build/native/libnio.so", "fstatat64") not in findings, findings
    print("Orbis binary audit tests passed")


if __name__ == "__main__":
    main()
