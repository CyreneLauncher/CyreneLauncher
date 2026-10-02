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

        configurations = [
            line for line in symbols.splitlines()
            if line.rstrip().endswith(" orbis_configure_runtime_v2")
        ]
        assert len(configurations) == 1, f"{module}: runtime configuration v2 is missing"
        assert " GLOBAL " in configurations[0] and " DEFAULT " in configurations[0]
        assert not any(line.rstrip().endswith(" orbis_configure_runtime")
                       for line in symbols.splitlines()), f"{module}: obsolete configuration ABI"

        for name in ('getenv', 'setenv', 'unsetenv', '__environ', 'strdup'):
            definitions = [line for line in symbols.splitlines()
                           if line.rstrip().endswith(" " + name) and " UND " not in line]
            assert len(definitions) == 1, f"{module}: {name} must be module-local"
        all_symbols = run("readelf", "--syms", "--wide", str(module))
        for name in ('__putenv', '__env_rm_add'):
            assert any(line.rstrip().endswith(" " + name) and " LOCAL " in line
                       and " HIDDEN " in line for line in all_symbols.splitlines()), (
                           f"{module}: SDK environment helper {name} is not bound locally")
        # Symbolic binding forbids interposition by dependency PRXs, including
        # the SDK environment data consulted by both configuration and JDK code.
        dynamic = run("readelf", "--dynamic", "--wide", str(module))
        assert "SYMBOLIC" in dynamic, f"{module}: local environment binding missing"
        size_match = re.search(r"\(INIT_ARRAYSZ\)\s+(\d+) \(bytes\)", dynamic)
        if size_match and int(size_match.group(1)) > 0:
            modules_with_constructors += 1

    assert modules_with_constructors > 0, "no module retained a constructor table"
    print(f"PRX initializer contract passed: {len(modules)} modules")


if __name__ == "__main__":
    main()
