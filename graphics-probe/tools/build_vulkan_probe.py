#!/usr/bin/env python3
"""Package a separate Minecraft-profile Vulkan acceptance app; never rewrite an installed instance."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path
import lwjgl_sources

ROOT = Path(__file__).resolve().parents[1]
MODULES = ('lwjgl', 'lwjgl-freetype', 'lwjgl-jemalloc', 'lwjgl-openal', 'lwjgl-opengl', 'lwjgl-sdl',
           'lwjgl-shaderc', 'lwjgl-spvc', 'lwjgl-stb', 'lwjgl-vma', 'lwjgl-vulkan')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jars', required=True)
    parser.add_argument('--facade', default=str(ROOT / 'build/lwjgl-facade-vulkan-3.4.3'))
    parser.add_argument('--boot-jdk', default='/usr/lib/jvm/java-17-openjdk-amd64')
    parser.add_argument('--output', default=str(ROOT / 'build/vulkan-probe'))
    args = parser.parse_args()
    out = Path(args.output).resolve()
    out.mkdir(parents=True, exist_ok=True)
    artifacts = []
    for module in MODULES:
        source = lwjgl_sources.java_artifact(args.jars, module + '-3.4.3.jar')
        data = source.read_bytes()
        relative = f'org/lwjgl/{module}/3.4.3/{source.name}'
        target = out / 'libraries' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        artifacts.append({'name': f'org.lwjgl:{module}:3.4.3', 'downloads': {'artifact': {
            'path': relative, 'size': len(data), 'sha1': hashlib.sha1(data).hexdigest()}}})
    adapter = Path(args.facade) / 'orbis-lwjgl-3.4.3-adapter.jar'
    jars = [adapter] + [Path(args.jars) / f'{module}-3.4.3.jar' for module in ('lwjgl', 'lwjgl-vulkan', 'lwjgl-vma')]
    classes = out / 'classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir()
    subprocess.run([str(Path(args.boot_jdk) / 'bin/javac'), '--release', '17', '-cp', os.pathsep.join(map(str, jars)),
                    '-d', str(classes), str(ROOT / 'lwjgl/vulkan/VulkanProbe.java')], check=True)
    subprocess.run([str(Path(args.boot_jdk) / 'bin/jar'), '--create', '--file', str(out / 'vulkan-probe.jar'),
                    '--main-class', 'orbis.vulkan.VulkanProbe', '-C', str(classes), '.'], check=True)
    (out / 'version.json').write_text(json.dumps({'id': 'orbis-vulkan-acceptance',
        'mainClass': 'orbis.vulkan.VulkanProbe', 'libraries': artifacts}, indent=2) + '\n')
    (out / 'runner.conf.example').write_text('''# Run separately; preserve the installed runner.conf before selecting this probe.
java_home=/data/orbis-jdk/jdk25
jar=/data/orbis-jdk/vulkan-probe/vulkan-probe.jar
working_directory=/data/orbis-jdk/vulkan-probe
display=gpu
graphics_bundle=/data/orbis-jdk/graphics-radeonsi-vulkan
version_metadata=/data/orbis-jdk/vulkan-probe/version.json
libraries=/data/orbis-jdk/vulkan-probe/libraries
''')
    print(f'Vulkan acceptance app: {out}; requires the updated runner app.jar and Vulkan bundle')


if __name__ == '__main__':
    main()
