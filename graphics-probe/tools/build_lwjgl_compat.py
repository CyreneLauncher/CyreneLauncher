#!/usr/bin/env python3
"""Build the independent runner-launched LWJGL compatibility probe upload set."""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = 'orbis.compat.CompatibilityProbe'
MODULES = ('lwjgl', 'lwjgl-glfw', 'lwjgl-jemalloc', 'lwjgl-openal',
           'lwjgl-opengl', 'lwjgl-stb', 'lwjgl-tinyfd')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--boot-jdk', default='/usr/lib/jvm/java-17-openjdk-amd64')
    parser.add_argument('--jars', default='/tmp/lwjgl-audit')
    parser.add_argument('--output', default=str(ROOT / 'build/lwjgl-compat'))
    args = parser.parse_args()
    out = Path(args.output).resolve()
    jars = Path(args.jars).resolve()
    java = Path(args.boot_jdk) / 'bin'
    if out.exists():
        shutil.rmtree(out)
    runner = out / 'runner'
    runner.mkdir(parents=True)
    subprocess.run([sys.executable, str(ROOT / 'tools/make_lwjgl_metadata.py'), '--jars', str(jars),
                    '--id', 'orbis-lwjgl-compat-3.2.2', '--main-class', MAIN,
                    '--output', str(runner / 'version.json'), '--libraries', str(out / 'libraries')], check=True)
    classes = out / 'classes'
    sources = sorted((ROOT / 'lwjgl-compat/src').rglob('*.java'))
    cp = ':'.join(str(jars / f'{module}-3.2.2.jar') for module in MODULES)
    subprocess.run([str(java / 'javac'), '--release', '17', '-Xlint:all', '-Werror',
                    '-cp', cp, '-d', str(classes), *map(str, sources)], check=True)
    subprocess.run([str(java / 'jar'), '--create', '--file', str(runner / 'target.jar'),
                    '-C', str(classes), '.'], check=True)
    shutil.rmtree(classes)
    fixtures = runner / 'fixtures'
    fixtures.mkdir()
    for name in ('pixels.png', 'tone.ogg', 'PROVENANCE.md'):
        shutil.copyfile(ROOT / 'lwjgl-compat/resources' / name, fixtures / name)
    font = ROOT.parent / 'orbis-java-app/runtime-data/desktop/fonts'
    for name in ('DejaVuSans.ttf', 'LICENSE', 'README.md'):
        shutil.copyfile(font / name, fixtures / name)
    (runner / 'runner.conf').write_text('''# Independent LWJGL compatibility gate on the existing GPU runner.
display=gpu
debug=true
graphics_bundle=/data/orbis-jdk/graphics
version_metadata=/data/orbis-jdk/runner/version.json
libraries=/data/orbis-jdk/libraries
''')
    (runner / 'jvm-options.txt').write_text('''# Initial measured budget shared with the Mesa graphics arena.
-Xms512m
-Xmx1024m
-Dorbis.graphics.arenaMib=512
''')
    (out / 'UPLOAD.txt').write_text('''Compatibility probe upload set; the build does not deploy any files.

1. After independent approval for device operations, use one application on PS4 Pro in
   the already tested Base Mode. First retest smoke close/relaunch and PS-menu closure
   with the SubmitDone fix; retain runner/UART logs.
2. Upload the contents of build/graphics-bundle/ to /data/orbis-jdk/graphics/.
3. Upload the contents of this libraries/ tree to /data/orbis-jdk/libraries/.
4. Save existing /data/orbis-jdk/runner files, then upload this runner/ tree there.
   Keep the runner PKG (OJDK00003), JDK, graphics bundle and probe tree separate.
5. Launch the runner. Collect runner-output.log (COMPAT stage= lines),
   runner-debug.log, /data/orbis-jdk/logs/orbis-runner.log, and UART.
   Require native-load, all STB stages, alc (tone on a device, else no-device contract), tinyfd-cancel, glfw-policy,
   gl-debug-callback, teardown and summary result=ok. A required stage cannot
   be accepted as skip. GL debug stage verifies invocation when advertised.
6. Repeat after adding -Dorbis.compat.fail=<native-load|stb-image|stb-font|
   stb-vorbis|stb-callback|alc|tinyfd-cancel|glfw-init|glfw-window|
   glfw-policy|gl-context|gl-capabilities|gl-debug-callback> to jvm-options.txt;
   each injected run must have summary result=fail and reverse-order teardown.
   Remove the option before the normal run. Save failure logs and memory evidence.
''')
    files = {p.relative_to(out).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
             for p in sorted(out.rglob('*')) if p.is_file()}
    (out / 'compat-manifest.json').write_text(json.dumps({'main_class': MAIN, 'files': files}, indent=2) + '\n')
    print(f'LWJGL compatibility upload set: {out} ({len(files)} files)')


if __name__ == '__main__':
    main()
