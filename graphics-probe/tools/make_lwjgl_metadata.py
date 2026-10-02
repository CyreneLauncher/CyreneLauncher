#!/usr/bin/env python3
"""Write a Mojang-style version JSON for an LWJGL 3.2.2 application, plus its libraries tree.

The libraries are the seven official LWJGL 3.2.2 Java JARs of the runner's
minecraft-1.18.2-lwjgl-3.2.2-orbis-v1 profile, verified against their pinned SHA-1s and
recorded with Maven paths, sizes and hashes, so the runner resolver and profile gate treat
the application exactly as they will treat Minecraft 1.18.2's own metadata.
"""
import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lwjgl_sources  # noqa: E402

MODULES = ('lwjgl', 'lwjgl-glfw', 'lwjgl-jemalloc', 'lwjgl-openal', 'lwjgl-opengl', 'lwjgl-stb', 'lwjgl-tinyfd')
SHA1 = {'lwjgl': '8ad6294407e15780b43e84929c40e4c5e997972e',
        'lwjgl-glfw': 'd3ad4df38e400b8afba1de63f84338809399df5b',
        'lwjgl-jemalloc': 'ee8e57a79300f78294576d87c4a587f8c99402e2',
        'lwjgl-openal': '2b772a102b0a11ee5f2109a5b136f4dc7c630827',
        'lwjgl-opengl': '6ac5bb88b44c43ea195a570aab059f63da004cd8',
        'lwjgl-stb': '3b8e6ebc5851dd3d17e37e5cadce2eff2a429f0f',
        'lwjgl-tinyfd': 'fcbe606c8f8da6f8f9a05e2c540eb1ee8632b0e9'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jars', required=True, help='directory with the official lwjgl*-3.2.2.jar files')
    parser.add_argument('--id', required=True)
    parser.add_argument('--main-class', required=True)
    parser.add_argument('--output', required=True, help='version JSON to write')
    parser.add_argument('--libraries', help='also lay out the Maven libraries tree here')
    arguments = parser.parse_args()
    libraries = []
    for module in MODULES:
        source = Path(arguments.jars) / f'{module}-3.2.2.jar'
        data = source.read_bytes() if source.is_file() else None
        if data is None or hashlib.sha1(data).hexdigest() != SHA1[module]:
            raise SystemExit(f'{source} is missing or not the audited official artifact')
        path = f'org/lwjgl/{module}/3.2.2/{module}-3.2.2.jar'
        libraries.append({'downloads': {'artifact': {
            'path': path, 'sha1': SHA1[module], 'size': len(data),
            'url': 'https://libraries.minecraft.net/' + path}}, 'name': f'org.lwjgl:{module}:3.2.2'})
        if arguments.libraries:
            target = Path(arguments.libraries) / path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
    if set(SHA1) != set(MODULES) or any(lwjgl_sources.LOCK['java_artifacts'].get(f'{m}-3.2.2.jar', SHA1[m]) != SHA1[m]
                                        for m in MODULES):
        raise SystemExit('pinned SHA-1s disagree with lwjgl-sources.lock.json')
    metadata = {'id': arguments.id, 'mainClass': arguments.main_class, 'libraries': libraries}
    Path(arguments.output).parent.mkdir(parents=True, exist_ok=True)
    Path(arguments.output).write_text(json.dumps(metadata, indent=2) + '\n')


if __name__ == '__main__':
    main()
