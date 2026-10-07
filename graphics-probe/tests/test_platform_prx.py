#!/usr/bin/env python3
"""Platform PRX contract.

Always: the C bridge compiles against the javac-generated JNI header for
orbis.graphics.NativePlatform, so every native exists with matching types.
With a Mesa target build present: build_platform_prx.py links and audits the PRX
(exports, imports, TLS image, wchar extraction) into a temporary directory.
"""
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
boot_jdk = Path(os.environ.get('BOOT_JDK', '/usr/lib/jvm/java-17-openjdk-amd64'))
sdk = Path(os.environ.get('ORBIS_SDK', '/home/wonder/ps4-payload-sdk'))
toolchain = Path(os.environ.get('ORBIS_TOOLCHAIN', '/home/wonder/PS4Toolchain'))
sources = root / 'build/sources'
java_source = root / 'lwjgl/java/orbis/graphics/NativePlatform.java'
bridge = root / 'lwjgl/platform/orbis_lwjgl_platform.c'

with tempfile.TemporaryDirectory() as directory:
    tmp = Path(directory)
    subprocess.run([str(boot_jdk / 'bin/javac'), '--release', '17', '-Xlint:all', '-Werror',
                    '-d', str(tmp / 'classes'), '-h', str(tmp / 'headers'), str(java_source)], check=True)
    header = tmp / 'headers/orbis_graphics_NativePlatform.h'
    declared = sorted(line.split()[-1] for line in header.read_text().splitlines()
                      if line.startswith('JNIEXPORT'))
    defined = sorted(line.split('JNICALL ')[1].split('(')[0].strip()
                     for line in bridge.read_text().splitlines()
                     if 'JNICALL Java_orbis_graphics_NativePlatform_' in line)
    assert declared == defined, f'Java natives {declared} differ from C bridge {defined}'
    if (sdk / 'bin/clang').exists() and (sources / 'mesa/include').is_dir():
        # Conflicting prototypes between the generated header and the bridge fail here.
        subprocess.run([str(sdk / 'bin/clang'), '--target=x86_64-pc-freebsd12-elf', '-fPIC',
                        '-fsyntax-only', '-Wall', '-Werror', '-D__ORBIS__', '-DPS4', '-D_BSD_SOURCE=1',
                        '-isysroot', str(toolchain), '-isystem', str(sources / 'orbis-compat/include'),
                        '-isystem', str(toolchain / 'include'), '-I', str(sources / 'mesa/include'),
                        '-I', str(boot_jdk / 'include'), '-I', str(boot_jdk / 'include/linux'),
                        '-include', str(header), str(bridge)], check=True)
        print(f'NativePlatform JNI contract passed: {len(declared)} natives')
    else:
        print('NativePlatform name contract passed; prototype check skipped without SDK and Mesa sources')

    if not (root / 'build/target/mesa/src/egl/libEGL.a').is_file():
        print('platform PRX link skipped: no Mesa target build under graphics-probe/build/target')
        sys.exit(0)
    output = tmp / 'platform'
    subprocess.run([sys.executable, str(root / 'tools/build_platform_prx.py'), '--sdk', str(sdk),
                    '--toolchain', str(toolchain), '--jni-headers', str(boot_jdk / 'include'),
                    '--output', str(output)], check=True)
    audit = json.loads((output / 'platform-audit.json').read_text())
    # The system keyboard (ba214e7fad6) added the dialog and sysmodule libraries.
    assert audit['needed'] == ['libSceCommonDialog.so', 'libSceGnmDriver.so', 'libSceImeDialog.so', 'libScePad.so',
                               'libSceSysmodule.so', 'libSceSystemService.so', 'libSceUserService.so', 'libSceVideoOut.so',
                               'libkernel.so'], audit['needed']
    assert len(audit['jni_natives']) == len(declared), audit['jni_natives']
    assert audit['tls']['align'] <= 64 and audit['tls']['size'] > 0, audit['tls']
    print('platform PRX build and audit passed')
