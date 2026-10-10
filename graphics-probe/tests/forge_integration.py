"""Opt-in real Forge/NeoForge installation and bootstrap probes; no player credentials."""
import argparse
import subprocess
import sys
import json
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
sys.path.insert(0, str(ROOT / 'tools'))
import launcher_deps

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--install', action='store_true')
    parser.add_argument('--bootstrap', action='store_true')
    parser.add_argument('--root', type=Path, default=Path('/tmp/cyrene-forge-integration'))
    opts = parser.parse_args()
    opts.root.mkdir(parents=True, exist_ok=True)
    classes = opts.root / 'classes'
    classes.mkdir(exist_ok=True)
    jdk17 = Path('/usr/lib/jvm/java-17-openjdk-amd64')
    jdk21 = REPO.parent / '.local-build/host-jdks/jdk-21.0.10+7'
    gson = ROOT / 'build/downloads/runner-jars/gson-2.8.9.jar'
    jars = [*sorted((ROOT / 'build/java-jars/3.2.2').glob('*.jar')), gson, *launcher_deps.jars()]
    cp = ':'.join(map(str,jars))
    sources = sorted((ROOT / 'launcher/java').rglob('*.java'))
    sources += [ROOT / 'tests/launcher/orbis/launcher/ForgeIntegration.java']
    sources += [REPO / 'orbis-java-app/src/runner/RunnerSystemClassLoader.java']
    bootstrap = ROOT / 'tests/launcher/orbis/launcher/ForgeBootstrap.java'
    if bootstrap.exists(): sources += [bootstrap]
    subprocess.run([str(jdk17 / 'bin/javac'),'--release','17','-Xlint:all','-Werror','-cp',cp,
                    '-sourcepath',str(ROOT / 'lwjgl/java'),'-implicit:class','-d',str(classes),*map(str,sources)],check=True)
    if opts.install:
        for loader,mc,build,jdk in [('FORGE','1.20.1','47.4.0',jdk17),('NEOFORGE','1.21.1','21.1.172',jdk21)]:
            subprocess.run([str(jdk / 'bin/java'),'-Xmx2g','-cp',str(classes)+':'+cp,'orbis.launcher.ForgeIntegration',
                            str(opts.root),loader,mc,build,str(REPO / 'orbis-java-app/build-cyrene/stage/manifests/launcher.json')],check=True)
    if opts.bootstrap:
        for loader,jdk in [('FORGE',jdk17),('NEOFORGE',jdk21)]:
            profile_path = Path((opts.root / (loader+'-installed.txt')).read_text())
            installed = json.loads(profile_path.with_name('install.json').read_text())
            game_dir = opts.root / (loader+'-game')
            game_dir.mkdir(exist_ok=True)
            command = [str(jdk / 'bin/java'),'-Xmx2g','-Djava.system.class.loader=runner.RunnerSystemClassLoader',
                       '-Drunner.jar='+str(profile_path.with_name('client.jar')),*installed['jvm'],
                       '-cp',str(classes)+':'+str(gson),'orbis.launcher.ForgeBootstrap',
                       str(profile_path),str(opts.root/'libraries'),str(game_dir)]
            with (opts.root / (loader+'-bootstrap.log')).open('w') as log:
                result = subprocess.run(command,cwd=game_dir,stdout=log,stderr=subprocess.STDOUT,timeout=120)
            print(loader,'bootstrap exit',result.returncode,'log',opts.root / (loader+'-bootstrap.log'),flush=True)
if __name__ == '__main__': main()
