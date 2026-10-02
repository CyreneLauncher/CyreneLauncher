#!/usr/bin/env python3
"""Compile the LWJGL metadata resolver and run its standalone Java fixture programs.

Optionally resolves the official 1.18.2 version JSON when ORBIS_MC_VERSION_JSON and
ORBIS_LWJGL_JARS (directory of the seven 3.2.2 Java JARs) exist; LWJGL entries keep
their real hashes, other libraries are replaced by placeholders with rewritten hashes.
"""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
boot_jdk = Path(os.environ.get("BOOT_JDK", "/usr/lib/jvm/java-17-openjdk-amd64")) / "bin"
java = os.environ.get("JAVA", str(boot_jdk / "java"))
javac = os.environ.get("JAVAC", str(boot_jdk / "javac"))
version_json = Path(os.environ.get("ORBIS_MC_VERSION_JSON", "/tmp/lwjgl-audit/1.18.2.json"))
lwjgl_jars = Path(os.environ.get("ORBIS_LWJGL_JARS", "/tmp/lwjgl-audit"))
OFFICIAL_SHA1 = "334b33fcba3c9be4b7514624c965256535bd7eba"

sources = sorted((root / "src/runner/lwjgl").glob("*.java"))
tests = sorted((root / "tests/java/runner/lwjgl").glob("*.java"))

with tempfile.TemporaryDirectory() as directory:
    tmp = Path(directory)
    production = tmp / "production"
    # Production sources must compile cleanly on their own, as they ship in app.jar.
    subprocess.run([javac, "--release", "17", "-Xlint:all", "-Werror", "-d", str(production),
                    *map(str, sources)], check=True)
    classes = tmp / "classes"
    subprocess.run([javac, "--release", "17", "-d", str(classes), *map(str, sources + tests)], check=True)
    for program in ("JsonReaderTest", "VersionMetadataResolverTest", "LwjglProfileRegistryTest"):
        subprocess.run([java, "-ea", "-cp", str(classes), "runner.lwjgl." + program], check=True)

    if version_json.is_file() and lwjgl_jars.is_dir():
        if hashlib.sha1(version_json.read_bytes()).hexdigest() != OFFICIAL_SHA1:
            raise SystemExit(f"{version_json} is not the official 1.18.2 metadata")
        metadata = json.loads(version_json.read_text())
        libraries = tmp / "libraries"
        for library in metadata["libraries"]:
            artifact = library["downloads"].get("artifact")
            if artifact is None:
                continue
            target = libraries / artifact["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            real = lwjgl_jars / Path(artifact["path"]).name
            if library["name"].startswith("org.lwjgl:") and library["name"].endswith(":3.2.2") and real.is_file():
                target.write_bytes(real.read_bytes())
                continue
            content = ("placeholder " + library["name"]).encode()
            target.write_bytes(content)
            artifact["sha1"] = hashlib.sha1(content).hexdigest()
            artifact["size"] = len(content)
        prepared = tmp / "1.18.2.json"
        prepared.write_text(json.dumps(metadata))
        subprocess.run([java, "-cp", str(classes), "runner.lwjgl.RealMetadataCheck",
                        str(prepared), str(libraries)], check=True)
    else:
        print("official 1.18.2 metadata check skipped: set ORBIS_MC_VERSION_JSON and ORBIS_LWJGL_JARS")

print("LWJGL metadata resolver and profile checks passed")
