"""Opt-in real Forge/NeoForge installation and bootstrap probes; no player credentials."""
import argparse
import subprocess
import sys
import json
import time
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
sys.path.insert(0, str(ROOT / 'tools'))
import launcher_deps

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--install', action='store_true')
    parser.add_argument('--bootstrap', action='store_true')
    parser.add_argument('--loader', choices=['FORGE','NEOFORGE'])
    parser.add_argument('--minecraft', help='Run only this Minecraft version')
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
    cases = [('FORGE','1.20.1','47.4.0',jdk17),('NEOFORGE','1.21.1','21.1.172',jdk21),
             ('FORGE','1.21.11','61.2.1',jdk21),('NEOFORGE','1.21.11','21.11.45',jdk21)]
    if opts.minecraft: cases = [case for case in cases if case[1] == opts.minecraft]
    if opts.loader: cases = [case for case in cases if case[0] == opts.loader]
    if opts.install:
        for loader,mc,build,jdk in cases:
            subprocess.run([str(jdk / 'bin/java'),'-Xmx2g','-cp',str(classes)+':'+cp,'orbis.launcher.ForgeIntegration',
                            str(opts.root),loader,mc,build,str(REPO / 'orbis-java-app/build-cyrene/stage/manifests/launcher.json')],check=True)
    if opts.bootstrap:
        for loader,mc,build,jdk in cases:
            label = loader+'-'+mc
            profile_path = Path((opts.root / (label+'-installed.txt')).read_text())
            installed = json.loads(profile_path.with_name('install.json').read_text())
            game_dir = opts.root / (label+'-game')
            game_dir.mkdir(exist_ok=True)
            config_obj = opts.root / 'runner_config.o'
            subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror','-c',str(REPO/'orbis-java-app/tools/runner_config.c'),'-o',str(config_obj)],check=True)
            executable = opts.root / ('jni-bootstrap-'+str(jdk.name))
            subprocess.run(['c++','-std=c++17','-Wall','-Wextra','-Werror','-I'+str(jdk/'include'),'-I'+str(jdk/'include/linux'),
                            '-I'+str(REPO/'orbis-java-app/tools'),str(ROOT/'tests/jni_loader_bootstrap.cpp'),str(config_obj),
                            '-L'+str(jdk/'lib/server'),'-Wl,-rpath,'+str(jdk/'lib/server'),'-ljvm','-o',str(executable)],check=True)
            native_options = [x.replace('--module-path=','-Dorbis.loader.modulePath=').replace('--add-modules=','-Dorbis.loader.addModules=') for x in installed['jvm']]
            options_file = opts.root / (label+'-jni-options.txt')
            options_file.write_text('\n'.join(native_options)+'\n')
            command = [str(executable),str(options_file),str(classes)+':'+str(gson),str(profile_path.with_name('client.jar')),
                       str(profile_path),str(opts.root/'libraries'),str(game_dir)]
            log_path = opts.root / (label+'-bootstrap.log')
            with log_path.open('w') as log:
                process = subprocess.Popen(command,cwd=game_dir,stdout=log,stderr=subprocess.STDOUT)
                reached = False
                try:
                    deadline = time.monotonic()+90
                    while time.monotonic() < deadline:
                        text = log_path.read_text(errors='replace')
                        reached = 'BOOTSTRAP ENTRY '+installed['mainClass'] in text and (
                            "Launching target 'forgeclient'" in text or "Launching target 'forge_client'" in text
                            or (installed['mainClass']=='net.neoforged.fml.startup.Client' and 'net.neoforged.fml.ModLoader' in text))
                        if reached or process.poll() is not None: break
                        time.sleep(0.5)
                finally:
                    if process.poll() is None:
                        process.terminate()
                        try: process.wait(timeout=5)
                        except subprocess.TimeoutExpired: process.kill(); process.wait()
                if not reached:
                    raise RuntimeError(loader+' bootstrap did not reach the game launch target; inspect '+str(log_path))
            print(label,'BOOTSTRAP VERIFIED: entry point, mod discovery and game target; stopped before graphics',flush=True)
if __name__ == '__main__': main()
