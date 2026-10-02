#!/usr/bin/env python3
"""Build the runner-launched LWJGL smoke test upload set (local files only).

Output (graphics-probe/build/lwjgl-smoke/):
  runner/      -> /data/orbis-jdk/runner/     target.jar, version.json, runner.conf, jvm-options.txt
  libraries/   -> /data/orbis-jdk/libraries/  the seven official LWJGL 3.2.2 Java JARs
  UPLOAD.txt, smoke-manifest.json             instructions and SHA-256 of every file
The graphics bundle (make graphics-bundle) goes to /data/orbis-jdk/graphics/ separately.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = 'orbis.smoke.LwjglSmoke'

parser = argparse.ArgumentParser()
parser.add_argument('--boot-jdk', default='/usr/lib/jvm/java-17-openjdk-amd64')
parser.add_argument('--jars', default='/tmp/lwjgl-audit', help='official LWJGL 3.2.2 Java JARs')
parser.add_argument('--output', default=str(ROOT / 'build/lwjgl-smoke'))
arguments = parser.parse_args()
out = Path(arguments.output).resolve()
jars = Path(arguments.jars)
bin_dir = Path(arguments.boot_jdk) / 'bin'
if out.exists():
    shutil.rmtree(out)
runner = out / 'runner'
runner.mkdir(parents=True)

subprocess.run([sys.executable, str(ROOT / 'tools/make_lwjgl_metadata.py'), '--jars', str(jars),
                '--id', 'orbis-lwjgl-smoke-3.2.2', '--main-class', MAIN, '--output', str(runner / 'version.json'),
                '--libraries', str(out / 'libraries')], check=True)
classes = out / 'classes'
api = ':'.join(str(jars / f'{m}-3.2.2.jar') for m in ('lwjgl', 'lwjgl-opengl', 'lwjgl-glfw'))
subprocess.run([str(bin_dir / 'javac'), '--release', '17', '-Xlint:all', '-Werror', '-cp', api, '-d', str(classes),
                str(ROOT / 'lwjgl-smoke/src/orbis/smoke/LwjglSmoke.java')], check=True)
# No Main-Class: the runner takes mainClass from version.json in display=gpu.
subprocess.run([str(bin_dir / 'jar'), '--create', '--file', str(runner / 'target.jar'), '-C', str(classes), '.'],
               check=True)
shutil.rmtree(classes)
(runner / 'runner.conf').write_text('''# LWJGL smoke test: GPU display mode with full runner boundary logging.
display=gpu
debug=true
graphics_bundle=/data/orbis-jdk/graphics
version_metadata=/data/orbis-jdk/runner/version.json
libraries=/data/orbis-jdk/libraries
''')
(runner / 'jvm-options.txt').write_text('''# Leave direct memory for Mesa: Java heap and Mesa's GPU arena share one budget.
-Xmx512m
-Dorbis.graphics.arenaMib=512
''')
(out / 'UPLOAD.txt').write_text('''LWJGL smoke test upload (nothing here is deployed by the build)

1. Install the runner PKG built from this checkout (display=gpu support).
2. Upload the contents of graphics-probe/build/graphics-bundle/ to /data/orbis-jdk/graphics/
3. Upload the contents of libraries/ to /data/orbis-jdk/libraries/
4. Upload the contents of runner/ to /data/orbis-jdk/runner/ (replaces runner.conf and
   target.jar there; keep a copy of yours).
5. Launch the runner. A green triangle on dark blue should follow the mouse and turn orange
   while a key is held; after 2 s the mouse is captured, after 4 s released; Esc or 600
   frames ends the run.
6. Collect /data/orbis-jdk/runner/runner-output.log (SMOKE lines),
   /data/orbis-jdk/runner/runner-debug.log, /data/orbis-jdk/logs/orbis-runner.log and UART.
Failure-path runs: add -Dorbis.smoke.fail=<init|window|context|capabilities|shader|pixels>
to jvm-options.txt; teardown lines must follow the injected failure.
''')
files = {p.relative_to(out).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
         for p in sorted(out.rglob('*')) if p.is_file()}
(out / 'smoke-manifest.json').write_text(json.dumps({'main_class': MAIN, 'files': files}, indent=2) + '\n')
print(f'LWJGL smoke upload set: {out} ({len(files)} files)')
