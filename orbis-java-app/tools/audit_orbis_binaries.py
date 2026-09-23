#!/usr/bin/env python3
"""Classify native boundaries present in final unstripped Orbis ELFs."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys


def command(*args: str) -> str:
    return subprocess.run(args, check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def normalized(symbol: str) -> str:
    return symbol.split("@", 1)[0]


def symbols(path: Path) -> tuple[set[str], set[str]]:
    undefined, defined = set(), set()
    for line in command("readelf", "-Ws", str(path)).splitlines():
        fields = line.split()
        if len(fields) < 8 or not fields[0].rstrip(":").isdigit():
            continue
        name = normalized(fields[7])
        if not name:
            continue
        (undefined if fields[6] == "UND" else defined).add(name)
    return undefined, defined


def relocation_symbols(path: Path) -> set[str]:
    result = set()
    for line in command("readelf", "-rW", str(path)).splitlines():
        match = re.search(r"\b(?:R_X86_64_[A-Z0-9_]+)\s+[0-9a-fA-F]+\s+([^\s+]+)", line)
        if match:
            result.add(normalized(match.group(1)))
    return result


def callers(path: Path) -> dict[str, list[str]]:
    current = "<unknown>"
    found: dict[str, set[str]] = {}
    for line in command("objdump", "-d", str(path)).splitlines():
        label = re.search(r"<([^>]+)>:$", line)
        if label:
            current = normalized(label.group(1))
            continue
        call = re.search(r"\bcallq?\s+[^<]*<([^>]+)>", line)
        if call:
            target = normalized(call.group(1).replace("@plt", ""))
            found.setdefault(target, set()).add(current)
    return {key: sorted(value)[:8] for key, value in found.items()}


def display_path(path: Path) -> str:
    resolved = path.resolve()
    cwd = Path.cwd().resolve()
    try:
        return resolved.relative_to(cwd).as_posix()
    except ValueError:
        return path.as_posix()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("binaries", type=Path, nargs="+")
    args = parser.parse_args()

    policy_document = json.loads(args.policy.read_text())
    policy = {entry["symbol"]: entry for entry in policy_document["operations"]}
    tables = {path: symbols(path) for path in args.binaries}
    packaged_definitions = set().union(*(defined for _, defined in tables.values()))
    findings = []
    for path in args.binaries:
        undefined, defined = tables[path]
        relocations = relocation_symbols(path)
        called_by = callers(path)
        candidates = (undefined & relocations) | (defined & policy.keys())
        for symbol in sorted(candidates):
            if symbol in undefined and symbol in packaged_definitions:
                continue
            entry = policy.get(symbol)
            findings.append({
                "binary": display_path(path),
                "binding": "undefined" if symbol in undefined else "defined-local",
                "callers": called_by.get(symbol, []),
                "classification": entry["classification"] if entry else "unclassified",
                "evidence": entry["evidence"] if entry else "No policy entry",
                "route": entry["route"] if entry else "unknown",
                "subsystem": entry["subsystem"] if entry else "unclassified",
                "symbol": symbol,
            })
    findings.sort(key=lambda item: (item["binary"], item["symbol"]))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"schema": 1, "findings": findings},
                                      indent=2, sort_keys=True) + "\n")
    rejected = [item for item in findings if item["classification"] in
                ("unclassified", "unresolved") or item["route"] == "prohibited"]
    for item in rejected:
        print(f"{item['binary']}: {item['symbol']}: {item['classification']} ({item['route']})")
    return 1 if rejected else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print(f"binary audit error: {error}")
        sys.exit(2)
