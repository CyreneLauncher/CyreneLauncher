#!/usr/bin/env python3
"""Reject host inputs and inspect target ELF objects with LLVM tools."""

import argparse
import json
import re
import shlex
import subprocess
import sys
from pathlib import Path


FORBIDDEN = ("libdrm", "libelf", "libstdc++")


def check_link_inputs(paths, roots, cwd=None, depth=0):
    roots = [Path(root).resolve() for root in roots]
    cwd = Path(cwd or Path.cwd()).resolve()
    if depth > 4:
        raise ValueError("host link input: nested response files")
    index = 0
    while index < len(paths):
        entry = paths[index]
        index += 1
        if entry.startswith("@"):
            response = Path(entry[1:])
            if not response.is_absolute():
                response = cwd / response
            response = response.resolve()
            if not any(response == root or root in response.parents for root in roots):
                raise ValueError("host link input: " + entry)
            check_link_inputs(shlex.split(response.read_text()), roots, response.parent, depth + 1)
            continue
        if entry in ("-L", "-I", "-isystem", "--sysroot", "-T", "-B", "-R",
                     "-rpath", "--rpath", "-rpath-link", "--rpath-link"):
            if index == len(paths):
                raise ValueError("host link input: missing path for " + entry)
            entry = paths[index]
            index += 1
        elif entry.startswith(("-L", "-I", "-T", "-B", "-R")) and len(entry) > 2:
            entry = entry[2:]
        elif entry.startswith(("--sysroot=", "--script=", "--library-path=", "-rpath=", "--rpath=",
                               "-rpath-link=", "--rpath-link=")):
            entry = entry.split("=", 1)[1]
        elif entry.startswith("-Wl,"):
            linker_tokens = entry[4:].split(",")
            check_link_inputs(linker_tokens, roots, cwd, depth + 1)
            continue
        if entry.startswith("-l"):
            if any(entry == "-l" + name[3:] for name in FORBIDDEN):
                raise ValueError("host link input: " + entry)
            continue
        if entry.startswith("-"):
            continue
        if entry in ("elf_x86_64", ""):
            continue
        path = Path(entry)
        if not path.is_absolute():
            path = cwd / path
        path = path.resolve()
        if any(path.name.startswith(name) for name in FORBIDDEN):
            raise ValueError("host link input: " + entry)
        if not any(path == root or root in path.parents for root in roots):
            raise ValueError("host link input: " + entry)


def target_headers(path, *, executable=False):
    header = subprocess.run(["llvm-readelf", "-h", str(path)], check=True,
                            capture_output=True, text=True).stdout
    file_count = len(re.findall(r"^  Class:", header, re.M))
    freebsd_count = len(re.findall(r"^  OS/ABI:\s+UNIX - FreeBSD", header, re.M))
    systemv_count = len(re.findall(r"^  OS/ABI:\s+UNIX - System V", header, re.M))
    x64_count = len(re.findall(r"^  Machine:\s+Advanced Micro Devices X86-64", header, re.M))
    valid_abi = freebsd_count + systemv_count == file_count if executable else freebsd_count == file_count
    if not file_count or not valid_abi or x64_count != file_count:
        raise ValueError("wrong target ELF: " + str(path))
    if executable and not re.search(r"^  Type:\s+DYN", header, re.M):
        raise ValueError("wrong target ELF: " + str(path))
    return file_count


def undefined_symbols(path):
    output = subprocess.run(["llvm-nm", "--undefined-only", str(path)], check=True,
                            capture_output=True, text=True).stdout
    symbols = [line.split()[-1] for line in output.splitlines() if line.strip().startswith("U ") or
               re.match(r"^\s+U\s", line)]
    bad = [symbol for symbol in symbols if re.search(
        r"GLIBCXX|\bGLIBC_|std::__cxx11|\bdrm(Get|Ioctl)|\belf_(begin|end|next)", symbol)]
    if bad:
        raise ValueError("host undefined symbol: " + ", ".join(bad[:8]))
    return symbols


def audit_archive(path):
    count = target_headers(path)
    members = subprocess.run(["llvm-ar", "t", str(path)], check=True,
                             capture_output=True, text=True).stdout.splitlines()
    if count != len(members):
        raise ValueError("archive member count mismatch: " + str(path))
    symbols = undefined_symbols(path)
    return {"path": str(path), "members": count, "undefined_count": len(symbols),
            "undefined_symbols": sorted(set(symbols))}


def dynamic_symbols(path):
    output = subprocess.run(["llvm-readelf", "--dyn-syms", "--wide", str(path)],
                            check=True, capture_output=True, text=True).stdout
    rows = []
    for line in output.splitlines():
        parts = line.split()
        if len(parts) >= 8 and parts[0].endswith(":"):
            rows.append(parts)
    return rows


def audit_elf(path, sdk):
    target_headers(path, executable=True)
    program_headers = subprocess.run(["llvm-readelf", "-l", str(path)], check=True,
                                     capture_output=True, text=True).stdout
    if "/libexec/ld-elf.so.1" not in program_headers:
        raise ValueError("wrong target interpreter: " + str(path))
    dynamic = subprocess.run(["llvm-readelf", "-d", str(path)], check=True,
                             capture_output=True, text=True).stdout
    if any(name in dynamic for name in FORBIDDEN):
        raise ValueError("host dynamic dependency: " + str(path))
    needed = re.findall(r"\(NEEDED\).*\[([^]]+)\]", dynamic)
    if not needed or any(name.startswith(FORBIDDEN) or not name.endswith(".so") for name in needed):
        raise ValueError("host dynamic dependency: " + str(path))
    sdk_lib = Path(sdk).resolve() / "lib"
    exports = set()
    for name in needed:
        library = sdk_lib / name
        if not library.is_file():
            raise ValueError("SDK import library missing: " + str(library))
        exports.update(row[7].split("@", 1)[0] for row in dynamic_symbols(library)
                       if row[6] != "UND" and row[4] in ("GLOBAL", "WEAK"))
    imports = {row[7].split("@", 1)[0] for row in dynamic_symbols(path)
               if row[6] == "UND" and row[4] in ("GLOBAL", "WEAK")}
    missing = imports - exports
    if missing:
        raise ValueError("SDK import unresolved: " + ", ".join(sorted(missing)[:8]))
    return {"path": str(path), "architecture": "x86_64 FreeBSD-target PIE",
            "osabi": "System V (SDK crt1 compatible)", "needed": needed,
            "sdk_import_count": len(imports), "sdk_imports": sorted(imports),
            "dynamic": dynamic.strip()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check-link-inputs")
    parser.add_argument("--sdk", default="/home/wonder/PS4Toolchain")
    parser.add_argument("--build-root", default=str(Path(__file__).resolve().parents[1] / "build"))
    parser.add_argument("--cwd")
    parser.add_argument("--elf", action="append", default=[])
    parser.add_argument("--archive", action="append", default=[])
    args = parser.parse_args()
    try:
        if args.check_link_inputs:
            check_link_inputs(json.loads(Path(args.check_link_inputs).read_text()),
                              [args.sdk, args.build_root], args.cwd)
        result = [audit_archive(Path(path)) for path in args.archive]
        result.extend(audit_elf(Path(path), args.sdk) for path in args.elf)
        if result:
            print(json.dumps(result, indent=2))
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
