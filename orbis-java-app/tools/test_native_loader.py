#!/usr/bin/env python3
import sys
from pathlib import Path


def main() -> None:
    source, makefile = map(Path, sys.argv[1:3])
    text = source.read_text()
    make = makefile.read_text()
    for name in (
        "sceKernelDlsym",
        "sceKernelLoadStartModule",
    ):
        assert name in text, f"missing native loader operation: {name}"
    assert "-ljvm " not in make, "launcher still has an app ELF JVM dependency"
    assert "dlopen(" not in text and "dlerror(" not in text
    assert "/app0/sce_module/libjvm.prx" in text
    assert "sceKernelDlsym(jvm_handle," in text
    builder = (makefile.parent / "tools/build_native_modules.py").read_text()
    assert "--no-undefined" in builder
    assert "--lib=" in builder
    print("launcher loads native JVM PRX and resolves its handle")


if __name__ == "__main__":
    main()
