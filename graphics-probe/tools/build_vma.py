#!/usr/bin/env python3
"""Build pinned LWJGL VMA JNI for Orbis; Vulkan is supplied by the caller's dispatch table."""
import argparse
import json
import zipfile
from pathlib import Path

import build_stamp
import classfile
import lwjgl_sources
import orbis_prx

ROOT = orbis_prx.ROOT


def mangle(value):
    special = {'/': '_', '_': '_1', ';': '_2', '[': '_3'}
    return ''.join(special.get(c, c if c.isascii() and c.isalnum() else '_0%04x' % ord(c)) for c in value)


def native_exports(jar):
    exports = []
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if not name.endswith('.class'):
                continue
            cls = classfile.parse(archive.read(name))
            methods = [m for m in cls.methods if m.access & classfile.ACC_NATIVE]
            for method in methods:
                # These two platform-specific methods are excluded by upstream LWJGL on Linux.
                if method.name in ('nvmaGetMemoryWin32Handle', 'nvmaGetMemoryWin32Handle2'):
                    continue
                short = 'Java_' + mangle(cls.name) + '_' + mangle(method.name)
                if sum(m.name == method.name for m in methods) > 1:
                    short += '__' + mangle(method.descriptor[1:method.descriptor.index(')')])
                exports.append(short)
    if not exports:
        raise SystemExit('VMA JAR has no JNI contract')
    return sorted(exports)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', default='/home/wonder/ps4-payload-sdk')
    parser.add_argument('--toolchain', default='/home/wonder/PS4Toolchain')
    parser.add_argument('--paid', default='0x3800000000000011')
    parser.add_argument('--release', choices=['3.4.3'], default='3.4.3')
    parser.add_argument('--jars', required=True, help='audited official LWJGL 3.4.3 JAR directory')
    parser.add_argument('--jni-headers', default='/usr/lib/jvm/java-17-openjdk-amd64/include')
    parser.add_argument('--downloads', default=str(ROOT / 'build/downloads'))
    parser.add_argument('--fetch', action='store_true')
    parser.add_argument('--output', default=str(ROOT / 'build/vma-3.4.3'))
    args = parser.parse_args()
    out = Path(args.output).resolve()
    out.mkdir(parents=True, exist_ok=True)
    jar = lwjgl_sources.java_artifact(args.jars, 'lwjgl-vma-3.4.3.jar')
    required = native_exports(jar)
    headers = ROOT / 'build/sources/mesa/include'
    tools = orbis_prx.Toolchain(args.sdk, args.toolchain)
    stamp = (orbis_prx.stamp_recipe(build_stamp.Stamp(out, 'vma'), tools)
             .files(Path(__file__), Path(classfile.__file__), Path(lwjgl_sources.__file__),
                    ROOT / 'lwjgl/lwjgl-sources.lock.json', jar)
             .stats(headers / 'vulkan', Path(args.jni_headers))
             .value('arguments', vars(args)))
    if stamp.current(out / 'vma-audit.json', out / 'liblwjgl_vma.so', out / 'liblwjgl_vma.prx'):
        print('VMA: unchanged inputs, reusing the last build')
        return
    stamp.invalidate()
    source = lwjgl_sources.extract(lwjgl_sources.archive('lwjgl-3.4.3', Path(args.downloads), args.fetch),
                                   out / 'sources/lwjgl') / 'modules/lwjgl'
    obj = out / 'obj'
    obj.mkdir(exist_ok=True)
    flags = ('-std=c++17', '-O2', '-DNDEBUG', '-D_GNU_SOURCE', '-DLWJGL_LINUX', '-DLWJGL_x64',
             '-nostdinc++', '-isystem', tools.root / 'include/c++/v1',
             '-I', args.jni_headers, '-I', Path(args.jni_headers) / 'linux',
             '-I', source / 'core/src/main/c', '-I', source / 'core/src/main/c/linux',
             '-I', source / 'vma/src/main/c', '-I', headers)
    objects = [tools.compile(path, obj / (path.stem + '.o'), *flags)
               for path in sorted((source / 'vma/src/generated/c').glob('*.cpp'))]
    compat = ROOT / 'build/sources/orbis-compat/src'
    for name in ('orbis_thread_atexit', 'orbis_cxa_guard'):
        objects.append(tools.compile(compat / (name + '.c'), obj / (name + '.o'), '-std=gnu11', '-O2'))
    objects.append(tools.compile(ROOT / 'lwjgl/openal/orbis_openal_tls_init.c', obj / 'tls_init.o', '-std=c11', '-O2'))
    script, runtime = orbis_prx.runtime_objects(tools, out / 'runtime')
    elf = out / 'liblwjgl_vma.so'
    orbis_prx.link(tools, script, runtime, elf, objects)
    audit, natives = orbis_prx.audit(elf, allowed_needed=('libkernel.so',),
                                   jni_prefix='Java_org_lwjgl_util_vma_', jni_count=len(required),
                                   require_onload=False, required_c_exports=required)
    if set(natives) != set(required):
        raise SystemExit('VMA JNI exports differ from the official JAR')
    if any(name.startswith('vk') for name in audit['import_symbols']):
        raise SystemExit('VMA must use Vulkan function pointers, not another Vulkan loader')
    audit['prx'] = str(orbis_prx.fself(tools, elf, args.paid))
    (out / 'vma-audit.json').write_text(json.dumps({'release': args.release, 'required_jni': len(required),
                                                   'module': audit}, indent=2) + '\n')
    stamp.record()
    print(f'VMA: {len(required)} JNI exports checked against LWJGL {args.release}')


if __name__ == '__main__':
    main()
