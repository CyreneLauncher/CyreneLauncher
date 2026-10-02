#!/usr/bin/env python3
"""Materialize revision-pinned source trees inside graphics-probe/build."""

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path


BUILD_ROOT = Path(__file__).resolve().parents[1] / "build"
SHA = re.compile(r"[0-9a-f]{40}\Z")


def inside_build(path):
    resolved = path.resolve()
    if resolved == BUILD_ROOT.resolve() or BUILD_ROOT.resolve() not in resolved.parents:
        raise ValueError("outside build root: " + str(path))
    return resolved


def git(*args):
    return subprocess.run(["git", *map(str, args)], check=True,
                          text=True, capture_output=True).stdout.strip()


def prepare(lock_file, output):
    output = inside_build(Path(output))
    data = json.loads(Path(lock_file).read_text())
    sources = data["sources"]
    for name, item in sources.items():
        if not re.fullmatch(r"[a-z0-9-]+", name):
            raise ValueError("invalid source name: " + name)
        if not SHA.fullmatch(item["revision"]):
            raise ValueError("moving revision: " + str(item["revision"]))
    if output.exists():
        raise ValueError("output already exists: " + str(output))
    output.mkdir(parents=True)
    try:
        for name, item in sources.items():
            destination = output / name
            seed = item.get("seed")
            remote = seed if seed and Path(seed).is_dir() else item["url"]
            git("clone", "--quiet", "--no-checkout", remote, destination)
            try:
                git("-C", destination, "checkout", "--quiet", "--detach", item["revision"])
            except subprocess.CalledProcessError as error:
                raise ValueError("revision mismatch: " + name) from error
            actual = git("-C", destination, "rev-parse", "HEAD")
            if actual != item["revision"]:
                raise ValueError("revision mismatch: " + name)
            print(f"{name}: {actual}")
    except Exception:
        shutil.rmtree(output)
        raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        prepare(args.lock, args.output)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
