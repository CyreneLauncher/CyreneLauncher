#!/usr/bin/env python3
"""Stage a verified, pre-expanded Paper server and deterministic classpath."""

import argparse
import hashlib
from pathlib import Path
import shutil


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def safe_relative(path, parent):
    relative = path.relative_to(parent)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError(f"unsafe payload path: {relative}")
    return relative


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--paperclip", type=Path, required=True)
    parser.add_argument("--cache-root", type=Path, required=True)
    parser.add_argument("--stage-root", type=Path, required=True)
    parser.add_argument("--paperclip-sha256", required=True)
    parser.add_argument("--server-sha256", required=True)
    parser.add_argument("--library-count", type=int, required=True)
    args = parser.parse_args()

    if sha256(args.paperclip) != args.paperclip_sha256:
        raise SystemExit("Paperclip SHA-256 mismatch")

    server = args.cache_root / "versions/1.18.2/paper-1.18.2.jar"
    if not server.is_file():
        raise SystemExit(f"expanded Paper server is missing: {server}")
    if sha256(server) != args.server_sha256:
        raise SystemExit("expanded Paper server SHA-256 mismatch")

    library_root = args.cache_root / "libraries"
    libraries = sorted(library_root.rglob("*.jar"))
    if len(libraries) != args.library_count:
        raise SystemExit(
            f"expected {args.library_count} Paper libraries, found {len(libraries)}")

    if args.stage_root.exists():
        shutil.rmtree(args.stage_root)
    (args.stage_root / "libraries").mkdir(parents=True)
    shutil.copy2(server, args.stage_root / "server.jar")

    entries = ["server.jar"]
    seen = set(entries)
    for source in libraries:
        relative = safe_relative(source, library_root)
        target_entry = (Path("libraries") / relative).as_posix()
        if target_entry in seen:
            raise SystemExit(f"duplicate Paper classpath entry: {target_entry}")
        seen.add(target_entry)
        target = args.stage_root / target_entry
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        entries.append(target_entry)

    (args.stage_root / "classpath.list").write_text(
        "\n".join(entries) + "\n", encoding="utf-8")
    print(
        f"Staged Paper server and {len(libraries)} libraries "
        f"({len(entries)} classpath entries)")


if __name__ == "__main__":
    main()
