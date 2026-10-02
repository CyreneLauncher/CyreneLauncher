#!/usr/bin/env python3
"""Provision pinned host build tools under graphics-probe/build only."""

import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path


BUILD_ROOT = Path(__file__).resolve().parents[1] / "build"


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def verify_file(path, expected):
    if not path.is_file() or digest(path) != expected:
        raise ValueError("hash mismatch: " + str(path))


def inside_build(path):
    path = Path(path).resolve()
    if BUILD_ROOT.resolve() not in path.parents:
        raise ValueError("outside build root: " + str(path))
    return path


def bootstrap(lock, output, wheels, verify_only):
    lock = json.loads(Path(lock).read_text())
    output = inside_build(output)
    wheels = Path(wheels or output / "wheels").resolve()
    if BUILD_ROOT.resolve() not in wheels.parents:
        raise ValueError("outside build root: " + str(wheels))
    if not verify_only:
        output.mkdir(parents=True, exist_ok=True)
        wheels.mkdir(parents=True, exist_ok=True)
        packages = [f"{name}=={version}" for name, version in
                    (("meson", lock["host_dependencies"]["meson"]),
                     ("Mako", lock["host_dependencies"]["Mako"]),
                     ("PyYAML", lock["host_dependencies"]["PyYAML"]),
                     ("packaging", lock["host_dependencies"]["packaging"]),
                     ("ply", lock["host_dependencies"]["ply"]),
                     ("MarkupSafe", lock["host_dependencies"]["MarkupSafe"]))]
        env = os.environ.copy()
        env["PIP_CACHE_DIR"] = str(output / "pip-cache")
        subprocess.run([sys.executable, "-m", "pip", "download", "--only-binary=:all:",
                        "--no-deps", "--dest", str(wheels), *packages], check=True, env=env)
    for filename, expected in lock["python_wheels"].items():
        verify_file(wheels / filename, expected)
    if verify_only:
        return
    debs = output / "debs"
    debs.mkdir(exist_ok=True)
    for package in ("flex", "bison"):
        version = lock["host_dependencies"][package + "_deb"]["version"]
        subprocess.run(["apt", "download", f"{package}={version}"],
                       cwd=debs, check=True)
        matches = list(debs.glob(package + "_*.deb"))
        if len(matches) != 1:
            raise ValueError("expected one package archive: " + package)
        verify_file(matches[0], lock["host_dependencies"][package + "_deb"]["sha256"])
        subprocess.run(["dpkg-deb", "-x", str(matches[0]), str(output / "host")], check=True)
    venv = output / "venv"
    if venv.exists():
        raise ValueError("venv already exists: " + str(venv))
    subprocess.run([sys.executable, "-m", "venv", str(venv)], check=True)
    subprocess.run([venv / "bin/python", "-m", "pip", "install", "--no-index",
                    "--no-deps", *[str(wheels / name) for name in lock["python_wheels"]]],
                   check=True)
    (output / "bootstrap-manifest.json").write_text(json.dumps({
        "wheel_directory": str(wheels),
        "python_wheels": lock["python_wheels"],
        "flex_deb": lock["host_dependencies"]["flex_deb"],
        "bison_deb": lock["host_dependencies"]["bison_deb"]}, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", default=str(Path(__file__).resolve().parents[1] / "deps.lock.json"))
    parser.add_argument("--output", default=str(BUILD_ROOT / "deps"))
    parser.add_argument("--wheels")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    try:
        bootstrap(args.lock, args.output, args.wheels, args.verify_only)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
