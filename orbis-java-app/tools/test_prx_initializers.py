#!/usr/bin/env python3
"""Verify that relinked native modules expose explicit constructor startup."""

import re
import subprocess
import sys
from pathlib import Path


def run(*args: str) -> str:
    return subprocess.run(
        args, check=True, capture_output=True, text=True
    ).stdout


def main() -> None:
    modules = [Path(arg) for arg in sys.argv[1:]]
    assert modules, "expected at least one linked native ELF"
    modules_with_constructors = 0

    for module in modules:
        symbols = run("readelf", "--dyn-syms", "--wide", str(module))
        matches = [
            line for line in symbols.splitlines()
            if line.rstrip().endswith(" orbis_run_initializers")
        ]
        assert len(matches) == 1, (
            f"{module}: explicit PRX initializer runner is not exported"
        )
        assert " GLOBAL " in matches[0] and " DEFAULT " in matches[0], matches[0]

        dynamic = run("readelf", "--dynamic", "--wide", str(module))
        size_match = re.search(r"\(INIT_ARRAYSZ\)\s+(\d+) \(bytes\)", dynamic)
        if size_match and int(size_match.group(1)) > 0:
            modules_with_constructors += 1

    assert modules_with_constructors > 0, "no module retained a constructor table"
    print(f"PRX initializer contract passed: {len(modules)} modules")


if __name__ == "__main__":
    main()
