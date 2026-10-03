#!/usr/bin/env python3
"""Build Orbis TinyFD JNI and no-device OpenAL providers from pinned LWJGL sources."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

import lwjgl_sources
import orbis_prx
import tinyfd_contract

ROOT = orbis_prx.ROOT
CORE = ('alcOpenDevice', 'alcCloseDevice', 'alcCreateContext', 'alcMakeContextCurrent',
        'alcProcessContext', 'alcSuspendContext', 'alcDestroyContext', 'alcGetCurrentContext',
        'alcGetContextsDevice', 'alcIsExtensionPresent', 'alcGetProcAddress', 'alcGetEnumValue',
        'alcGetError', 'alcGetString', 'alcGetIntegerv', 'alGetProcAddress')
parser = argparse.ArgumentParser()
parser.add_argument('--sdk', default='/home/wonder/ps4-payload-sdk')
parser.add_argument('--toolchain', default='/home/wonder/PS4Toolchain')
parser.add_argument('--jni-headers', default='/usr/lib/jvm/java-17-openjdk-amd64/include')
parser.add_argument('--jni-platform', default='linux')
parser.add_argument('--paid', default='0x3800000000000011')
parser.add_argument('--downloads', default=str(ROOT / 'build/downloads'))
parser.add_argument('--jars', default='/tmp/lwjgl-audit', help='verified official LWJGL 3.2.2 Java JARs')
parser.add_argument('--output', default=str(ROOT / 'build/lwjgl-optional'))
a = parser.parse_args()
out = Path(a.output).resolve(); out.mkdir(parents=True, exist_ok=True)
archive = lwjgl_sources.archive('lwjgl', Path(a.downloads))
lwjgl = lwjgl_sources.extract(archive, out / 'sources') / 'modules/lwjgl'
tiny = lwjgl / 'tinyfd/src'
header = tiny / 'main/c'
generated = tiny / 'generated/c/org_lwjgl_util_tinyfd_TinyFileDialogs.c'
common = lwjgl / 'core/src/main/c'
tools = orbis_prx.Toolchain(a.sdk, a.toolchain)
script, runtime = orbis_prx.runtime_objects(tools, out / 'runtime')
objects = {
    'libopenal': [tools.compile(ROOT / 'lwjgl/audio/orbis_openal_no_device.c', out / 'openal.o', '-std=c11', '-O2')],
    'liblwjgl_tinyfd': [
        tools.compile(ROOT / 'lwjgl/tinyfd/orbis_tinyfd.c', out / 'tinyfd.o', '-std=c11', '-O2', '-I', header),
        tools.compile(generated, out / 'tinyfd_jni.o', '-std=c11', '-O2', '-DLWJGL_LINUX', '-DLWJGL_x64',
                      '-I', a.jni_headers, '-I', Path(a.jni_headers) / a.jni_platform,
                      '-I', common, '-I', common / 'linux', '-I', header),
    ],
}
summary = {'behavior_mode': 'no-device', 'modules': {},
           'provenance': {'openal': 'Original Orbis no-device C implementation; OpenAL 1.0 C ABI',
                          'tinyfd': 'Original Orbis cancellation C implementation; pinned LWJGL 3.2.2 generated JNI (BSD-3-Clause) and tinyfiledialogs interface (Zlib)',
                          'lwjgl_source_sha256': hashlib.sha256(archive.read_bytes()).hexdigest()}}
for name, files in objects.items():
    elf = out / (name + '.so')
    orbis_prx.link(tools, script, runtime, elf, files)
    audit, natives = orbis_prx.audit(elf, allowed_needed=('libkernel.so',),
        jni_prefix='Java_org_lwjgl_util_tinyfd_' if name == 'liblwjgl_tinyfd' else None,
        jni_count=15 if name == 'liblwjgl_tinyfd' else None, require_onload=False,
        required_c_exports=CORE if name == 'libopenal' else ())
    if name == 'liblwjgl_tinyfd':
        expected = tinyfd_contract.expected_jni_exports(Path(a.jars))
        if len(expected) != 15:
            raise SystemExit(f'expected 15 pinned TinyFD JNI declarations, found {len(expected)}')
        if set(natives) != expected:
            raise SystemExit(f'TinyFD JNI export mismatch: missing {sorted(expected - set(natives))}, extra {sorted(set(natives) - expected)}')
    audit['prx'] = str(orbis_prx.fself(tools, elf, a.paid))
    summary['modules'][name] = audit
    if natives: (out / (name + '-natives.txt')).write_text('\n'.join(natives) + '\n')
    print(f'{name}: {len(natives)} JNI exports, {len(audit["c_exports"])} C exports, {audit["imports"]} imports, TLS {audit["tls"]}')
(out / 'optional-audit.json').write_text(json.dumps(summary, indent=2) + '\n')
# Host shared objects are only used to exercise the official Java contract locally.
host = out / 'host'; host.mkdir(exist_ok=True)
subprocess.run(['cc', '-shared', '-fPIC', '-std=c11', '-O2', '-o', str(host / 'libopenal.so'),
                str(ROOT / 'lwjgl/audio/orbis_openal_no_device.c')], check=True)
subprocess.run(['cc', '-shared', '-fPIC', '-std=c11', '-O2', '-DLWJGL_LINUX', '-DLWJGL_x64',
                '-I', str(a.jni_headers), '-I', str(Path(a.jni_headers) / a.jni_platform),
                '-I', str(common), '-I', str(common / 'linux'), '-I', str(header),
                '-o', str(host / 'liblwjgl_tinyfd.so'), str(generated),
                str(ROOT / 'lwjgl/tinyfd/orbis_tinyfd.c')], check=True)
