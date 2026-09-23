#!/usr/bin/env python3
"""Audit policy-listed native calls in Orbis runtime source files."""

import argparse
import fnmatch
import json
from pathlib import Path
import re
import sys


VALID_CLASSIFICATIONS = {
    "direct-verified", "adapter-verified", "compile-verified",
    "unsupported-explicit", "deferred-unreachable", "unresolved",
}
VALID_ROUTES = {"direct", "adapter", "prohibited", "deferred"}


def load_policy(path: Path) -> list[dict]:
    document = json.loads(path.read_text())
    if document.get("schema") != 1 or not isinstance(document.get("operations"), list):
        raise ValueError("policy must use schema 1 and contain an operations list")
    seen = set()
    required = {"symbol", "subsystem", "classification", "route", "evidence", "source_globs"}
    for operation in document["operations"]:
        missing = required - operation.keys()
        if missing:
            raise ValueError(f"policy entry missing fields: {sorted(missing)}")
        if operation["symbol"] in seen:
            raise ValueError(f"duplicate policy symbol: {operation['symbol']}")
        seen.add(operation["symbol"])
        if operation["classification"] not in VALID_CLASSIFICATIONS:
            raise ValueError(f"invalid classification for {operation['symbol']}")
        if operation["route"] not in VALID_ROUTES:
            raise ValueError(f"invalid route for {operation['symbol']}")
        if not operation["source_globs"]:
            raise ValueError(f"empty source_globs for {operation['symbol']}")
    return document["operations"]


def strip_comments_and_strings(source: str) -> str:
    pattern = re.compile(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', re.S)
    return pattern.sub(lambda match: "\n" * match.group(0).count("\n"), source)


def selected_sources(root: Path, requested: list[str], operations: list[dict]) -> list[Path]:
    if requested:
        return sorted({Path(item).resolve() for item in requested})
    files = set()
    for pattern in sorted({pattern for operation in operations
                           for pattern in operation["source_globs"]}):
        files.update(path.resolve() for path in root.glob(pattern) if path.is_file())
    return sorted(files)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--source", action="append", default=[])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    try:
        operations = load_policy(args.policy)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"policy error: {error}")
        return 2

    root = args.root.resolve()
    findings = []
    for path in selected_sources(root, args.source, operations):
        try:
            relative = path.relative_to(root).as_posix()
        except ValueError:
            relative = path.name
        source = strip_comments_and_strings(path.read_text(errors="replace"))
        for operation in operations:
            if not any(fnmatch.fnmatch(relative, pattern) for pattern in operation["source_globs"]):
                continue
            call = re.compile(r"\b" + re.escape(operation["symbol"]) + r"\s*\(")
            for match in call.finditer(source):
                findings.append({
                    "classification": operation["classification"],
                    "evidence": operation["evidence"],
                    "line": source.count("\n", 0, match.start()) + 1,
                    "path": relative,
                    "route": operation["route"],
                    "subsystem": operation["subsystem"],
                    "symbol": operation["symbol"],
                })
    findings.sort(key=lambda item: (item["path"], item["line"], item["symbol"]))
    report = {"schema": 1, "findings": findings}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")

    rejected = [item for item in findings
                if item["route"] == "prohibited" or item["classification"] == "unresolved"]
    for item in rejected:
        print(f"{item['path']}:{item['line']}: {item['symbol']}: "
              f"{item['classification']} ({item['route']})")
    return 1 if rejected else 0


if __name__ == "__main__":
    sys.exit(main())
