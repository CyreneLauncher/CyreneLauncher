#!/usr/bin/env python3
"""Build the diagnostic-only CU hardware coverage probe; never upload files."""
import argparse
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--boot-jdk', default='/usr/lib/jvm/java-17-openjdk-amd64')
parser.add_argument('--jars', default='/tmp/lwjgl-audit')
parser.add_argument('--output', default=str(ROOT / 'build/cu-probe'))
parser.add_argument('--main', default='orbis.probe.CuProbe',
                    help='orbis.probe.CuProbe (CU coverage) or orbis.probe.ComputeProbe (compute output loss)')
parser.add_argument('--bundle', default='/data/orbis-jdk/graphics-cu-probe', help='graphics bundle on the console')
parser.add_argument('--driver', default='', help='ORBIS_GL_DRIVER for the run, e.g. radeonsi')
parser.add_argument('--arena', type=int, default=512, help='GPU arena MiB; radeonsi runs Minecraft with 1536')
parser.add_argument('--env', action='append', default=[], help='extra driver knob NAME=VALUE (repeatable)')
parser.add_argument('--amd-debug', default='', help='AMD_DEBUG for the run, e.g. forcecomputeblit')
args = parser.parse_args()
out = Path(args.output).resolve()
out.mkdir(parents=True, exist_ok=True)
classes = out / 'classes'
classes.mkdir(exist_ok=True)
bin_dir = Path(args.boot_jdk) / 'bin'
classpath = ':'.join(str(Path(args.jars) / (name + '-3.2.2.jar'))
                     for name in ('lwjgl', 'lwjgl-opengl', 'lwjgl-glfw'))
subprocess.run([str(bin_dir / 'javac'), '--release', '17', '-Xlint:all', '-Werror',
                '-cp', classpath, '-d', str(classes),
                *map(str, sorted((ROOT / 'cu-probe/src').rglob('*.java')))], check=True)
subprocess.run([str(bin_dir / 'jar'), '--create', '--file', str(out / 'target.jar'),
                '-C', str(classes), '.'], check=True)
subprocess.run(['python3', str(ROOT / 'tools/make_lwjgl_metadata.py'),
                '--jars', args.jars, '--id', 'orbis-cu-coverage-probe',
                '--main-class', args.main, '--output', str(out / 'version.json'),
                '--libraries', str(out / 'libraries')], check=True)
(out / 'runner.conf').write_text('''java_home=/data/orbis-jdk/jdk/release-desktop
jar=/data/orbis-jdk/cu-probe/target.jar
working_directory=/data/orbis-jdk/cu-probe
output_log=/data/orbis-jdk/cu-probe/output.log
temporary_directory=/data/orbis-jdk/runner/tmp
debug=false
display=gpu
graphics_bundle={bundle}
version_metadata=/data/orbis-jdk/cu-probe/version.json
libraries=/data/orbis-jdk/libraries
'''.replace('{bundle}', args.bundle))
(out / 'jvm-options.txt').write_text('''-Xms512m
-Xmx512m
# As the game: Parallel GC reserves far less address space than G1, and the console caps reservations
# (radeonsi's threads could not get their stacks under G1).
-XX:+UseParallelGC
-XX:MetaspaceSize=256m
# The console caps reserved address space (~448 MiB); the code cache's 240 MiB default is its largest user and
# radeonsi's threads could not get stacks. The probe needs little JIT.
-XX:ReservedCodeCacheSize=64m
-Dorbis.graphics.arenaMib={arena}
-Djava.awt.headless=true
-Dorbis.graphics.cuProbe=true
'''.replace('{arena}', str(args.arena)) + (f'-Dorbis.graphics.env.ORBIS_GL_DRIVER={args.driver}\n' if args.driver else '')
    + (f'-Dorbis.graphics.env.AMD_DEBUG={args.amd_debug}\n' if args.amd_debug else '')
    + ''.join(f'-Dorbis.graphics.env.{knob}\n' for knob in args.env))
print('CU probe upload set:', out)
