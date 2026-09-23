#!/usr/bin/env python3
import subprocess
import sys
from pathlib import Path


def dynamic_symbols(path: Path) -> list[str]:
    result = subprocess.run(
        ["readelf", "--dyn-syms", "--wide", str(path)],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.splitlines()


def symbol_line(path: Path, name: str) -> str:
    matches = [line for line in dynamic_symbols(path) if name in line]
    if len(matches) != 1:
        raise AssertionError(f"expected one {name} symbol in {path}, got {matches}")
    return matches[0]


def main() -> None:
    bridge, core = map(Path, sys.argv[1:3])
    bridge_jni = symbol_line(bridge, "JNI_CreateJavaVM")
    core_jni = symbol_line(core, "JNI_CreateJavaVM")
    assert "SUNWprivate_1.1" not in bridge_jni, bridge_jni
    assert "SUNWprivate_1.1" not in core_jni, core_jni
    assert "orbisCreateJavaVM" in "\n".join(dynamic_symbols(bridge))
    print("bridge metadata is unversioned")


if __name__ == "__main__":
    main()
