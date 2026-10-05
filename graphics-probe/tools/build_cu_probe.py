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
                str(ROOT / 'cu-probe/src/orbis/probe/CuProbe.java')], check=True)
subprocess.run([str(bin_dir / 'jar'), '--create', '--file', str(out / 'target.jar'),
                '-C', str(classes), '.'], check=True)
subprocess.run(['python3', str(ROOT / 'tools/make_lwjgl_metadata.py'),
                '--jars', args.jars, '--id', 'orbis-cu-coverage-probe',
                '--main-class', 'orbis.probe.CuProbe', '--output', str(out / 'version.json'),
                '--libraries', str(out / 'libraries')], check=True)
(out / 'runner.conf').write_text('''java_home=/data/orbis-jdk/jdk/release-desktop
jar=/data/orbis-jdk/cu-probe/target.jar
working_directory=/data/orbis-jdk/cu-probe
output_log=/data/orbis-jdk/cu-probe/output.log
temporary_directory=/data/orbis-jdk/runner/tmp
debug=true
display=gpu
graphics_bundle=/data/orbis-jdk/graphics-cu-probe
version_metadata=/data/orbis-jdk/cu-probe/version.json
libraries=/data/orbis-jdk/libraries
''')
(out / 'jvm-options.txt').write_text('''-Xmx512m
-Dorbis.graphics.arenaMib=512
-Djava.awt.headless=true
-Dorbis.graphics.cuProbe=true
''')
print('CU probe upload set:', out)
