#!/usr/bin/env python3
"""Static contract for deterministic pre-expanded Paper staging."""

from pathlib import Path
import hashlib
import subprocess
import tempfile


root = Path(__file__).resolve().parents[1]
makefile = (root / "Makefile").read_text()
for token in (
    "stage_paper_payload.py", "classpath.list", "paperbootstrap/Main.java",
    "PAPERCLIP_SHA256", "PAPER_SERVER_SHA256", "PAPER_LIBRARY_COUNT",
):
    assert token in makefile, token

source = (root / "src/paperbootstrap/Main.java").read_text()
for token in (
    "/app0/paper/classpath.list", "URLClassLoader", "setContextClassLoader",
    "org.bukkit.craftbukkit.Main", '"nogui"', '"--nojline"',
    '"--noconsole"',
):
    assert token in source, token


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


with tempfile.TemporaryDirectory() as directory:
    temp = Path(directory)
    paperclip = temp / "paperclip.jar"
    paperclip.write_bytes(b"paperclip fixture")
    cache = temp / "cache"
    server = cache / "versions/1.18.2/paper-1.18.2.jar"
    server.parent.mkdir(parents=True)
    server.write_bytes(b"expanded server fixture")
    first = cache / "libraries/example/a/one.jar"
    second = cache / "libraries/example/b/two.jar"
    first.parent.mkdir(parents=True)
    second.parent.mkdir(parents=True)
    first.write_bytes(b"one")
    second.write_bytes(b"two")
    stage = temp / "stage"
    command = [
        "python3", str(root / "tools/stage_paper_payload.py"),
        "--paperclip", str(paperclip), "--cache-root", str(cache),
        "--stage-root", str(stage),
        "--paperclip-sha256", digest(paperclip),
        "--server-sha256", digest(server), "--library-count", "2",
    ]
    subprocess.run(command, check=True, stdout=subprocess.DEVNULL)
    entries = (stage / "classpath.list").read_text().splitlines()
    assert entries == [
        "server.jar", "libraries/example/a/one.jar",
        "libraries/example/b/two.jar",
    ]
    assert (stage / entries[0]).read_bytes() == server.read_bytes()
    assert (stage / entries[1]).read_bytes() == first.read_bytes()

    wrong_hash = command.copy()
    wrong_hash[wrong_hash.index("--server-sha256") + 1] = "0" * 64
    result = subprocess.run(wrong_hash, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT)
    assert result.returncode != 0
    assert "SHA-256 mismatch" in result.stdout

    wrong_count = command.copy()
    wrong_count[wrong_count.index("--library-count") + 1] = "3"
    result = subprocess.run(wrong_count, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT)
    assert result.returncode != 0
    assert "expected 3 Paper libraries, found 2" in result.stdout

print("Paper payload source contract passed")
