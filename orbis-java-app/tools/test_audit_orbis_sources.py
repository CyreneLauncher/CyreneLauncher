#!/usr/bin/env python3
"""Behavior tests for the Orbis native source-boundary scanner."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile


TOOLS = Path(__file__).resolve().parent
SCANNER = TOOLS / "audit_orbis_sources.py"
FIXTURES = TOOLS / "testdata" / "audit_sources"


def run_audit(source: Path, output: Path) -> subprocess.CompletedProcess[str]:
    policy = output.parent / "policy.json"
    policy.write_text(json.dumps({
        "schema": 1,
        "operations": [
            {
                "symbol": "fstatat64",
                "subsystem": "filesystem",
                "classification": "unresolved",
                "route": "prohibited",
                "evidence": "fixture",
                "source_globs": ["*.c"],
            },
            {
                "symbol": "dl_iterate_phdr",
                "subsystem": "loader",
                "classification": "unsupported-explicit",
                "route": "prohibited",
                "evidence": "fixture",
                "source_globs": ["*.c"],
            },
            {
                "symbol": "orbis_ucontext_pc",
                "subsystem": "signals",
                "classification": "adapter-verified",
                "route": "adapter",
                "evidence": "fixture",
                "source_globs": ["*.c"],
            },
        ],
    }))
    return subprocess.run([
        sys.executable, str(SCANNER), "--policy", str(policy),
        "--root", str(FIXTURES), "--source", str(source),
        "--output", str(output),
    ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


def main() -> None:
    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        rejected = run_audit(FIXTURES / "prohibited.c", temp / "bad.json")
        assert rejected.returncode == 1, rejected.stdout
        assert "fstatat64" in rejected.stdout, rejected.stdout
        assert "dl_iterate_phdr" in rejected.stdout, rejected.stdout
        assert "unresolved" in rejected.stdout, rejected.stdout

        accepted = run_audit(FIXTURES / "direct.c", temp / "good.json")
        assert accepted.returncode == 0, accepted.stdout
        report = json.loads((temp / "good.json").read_text())
        assert report["findings"] == [{
            "classification": "adapter-verified",
            "evidence": "fixture",
            "line": 2,
            "path": "direct.c",
            "route": "adapter",
            "subsystem": "signals",
            "symbol": "orbis_ucontext_pc",
        }], report
    print("Orbis source audit tests passed")


if __name__ == "__main__":
    main()
