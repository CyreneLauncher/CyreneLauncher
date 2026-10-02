#!/usr/bin/env python3
"""Every native method in the LWJGL 3.2.2 core/OpenGL JARs has an exported JNI symbol.

Reads ACC_NATIVE methods straight from the class files, applies JNI short/long name
mangling and compares with the natives build_lwjgl_natives.py recorded for each PRX.
Needs a prior build (graphics-probe/build/lwjgl) and the audited JARs (ORBIS_LWJGL_JARS).
"""
import os
import struct
import sys
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
build = Path(os.environ.get('ORBIS_LWJGL_BUILD', root / 'build/lwjgl'))
jars = Path(os.environ.get('ORBIS_LWJGL_JARS', '/tmp/lwjgl-audit'))
MODULES = {'liblwjgl': 'lwjgl-3.2.2.jar', 'liblwjgl_opengl': 'lwjgl-opengl-3.2.2.jar'}
ACC_NATIVE = 0x0100
# Natives of other platforms are not built for Linux either; LWJGL never loads these classes there.
OTHER_PLATFORMS = ('org/lwjgl/system/windows/', 'org/lwjgl/system/macosx/')


def native_methods(data):
    """Yields (class, method, descriptor) for ACC_NATIVE methods of one class file."""
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('not a class file')
    count = struct.unpack_from('>H', data, 8)[0]
    pool, offset, index = {}, 10, 1
    while index < count:
        tag = data[offset]
        if tag == 1:
            length = struct.unpack_from('>H', data, offset + 1)[0]
            pool[index] = data[offset + 3:offset + 3 + length].decode('utf-8', 'surrogatepass')
            offset += 3 + length
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = ('ref', struct.unpack_from('>H', data, offset + 1)[0])
            offset += 3
        elif tag == 15:
            offset += 4
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            offset += 5
        elif tag in (5, 6):
            offset += 9
            index += 1
        else:
            raise ValueError(f'constant tag {tag}')
        index += 1
    this_class = pool[pool[struct.unpack_from('>H', data, offset + 2)[0]][1]]
    interfaces = struct.unpack_from('>H', data, offset + 6)[0]
    offset += 8 + 2 * interfaces

    def members(offset):
        """Parses a fields/methods table at offset; returns (end offset, members)."""
        parsed = []
        count = struct.unpack_from('>H', data, offset)[0]
        offset += 2
        for _ in range(count):
            flags, name, descriptor, attributes = struct.unpack_from('>HHHH', data, offset)
            offset += 8
            for _ in range(attributes):
                offset += 6 + struct.unpack_from('>I', data, offset + 2)[0]
            parsed.append((flags, pool[name], pool[descriptor]))
        return offset, parsed

    offset, _ = members(offset)
    _, methods = members(offset)
    for flags, name, descriptor in methods:
        if flags & ACC_NATIVE:
            yield this_class, name, descriptor


def mangle(text):
    out = []
    for char in text:
        if char == '/':
            out.append('_')
        elif char == '_':
            out.append('_1')
        elif char == ';':
            out.append('_2')
        elif char == '[':
            out.append('_3')
        elif char.isascii() and char.isalnum():
            out.append(char)
        else:
            out.append('_0%04x' % ord(char))
    return ''.join(out)


if not all((build / f'{module}-natives.txt').is_file() for module in MODULES) or \
        not all((jars / jar).is_file() for jar in MODULES.values()):
    print('LWJGL native symbol contract skipped: build LWJGL natives and set ORBIS_LWJGL_JARS')
    sys.exit(0)

for module, jar in MODULES.items():
    exported = set((build / f'{module}-natives.txt').read_text().split())
    missing, total = [], 0
    with zipfile.ZipFile(jars / jar) as archive:
        for entry in archive.namelist():
            if not entry.endswith('.class') or entry.startswith(('META-INF/',) + OTHER_PLATFORMS):
                continue
            for klass, method, descriptor in native_methods(archive.read(entry)):
                total += 1
                short = 'Java_' + mangle(klass) + '_' + mangle(method)
                arguments = descriptor[1:descriptor.index(')')]
                if short not in exported and short + '__' + mangle(arguments) not in exported:
                    missing.append(f'{klass}.{method}{descriptor}')
    assert total > 0, f'{jar} declares no native methods'
    assert not missing, f'{module}: {len(missing)} of {total} natives unexported, e.g. {missing[:5]}'
    print(f'{module}: all {total} native methods of {jar} are exported')
