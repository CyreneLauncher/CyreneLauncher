"""Exact JNI entry points declared by the pinned official LWJGL TinyFD JAR."""
from collections import Counter
from pathlib import Path
import hashlib
import zipfile

import classfile
from make_lwjgl_metadata import SHA1


def _mangle(value):
    """JNI name encoding, including argument descriptors for overloaded natives."""
    replacement = {'_': '_1', '/': '_', ';': '_2', '[': '_3'}
    return ''.join(replacement.get(char, char if char.isascii() and char.isalnum()
                                   else f'_0{ord(char):04x}') for char in value)


def expected_jni_exports(jars: Path) -> set[str]:
    jar_path = Path(jars) / 'lwjgl-tinyfd-3.2.2.jar'
    expected_digest = SHA1['lwjgl-tinyfd']
    if not jar_path.is_file() or hashlib.sha1(jar_path.read_bytes()).hexdigest() != expected_digest:
        raise ValueError(f'missing or non-official pinned TinyFD JAR: {jar_path}')
    declarations = []
    with zipfile.ZipFile(jar_path) as jar:
        for path in sorted(jar.namelist()):
            if path.endswith('.class'):
                klass = classfile.parse(jar.read(path))
                declarations.extend((klass.name, method.name, method.descriptor)
                                    for method in klass.methods if method.access & classfile.ACC_NATIVE)
    counts = Counter((owner, name) for owner, name, _ in declarations)
    exports = set()
    for owner, name, descriptor in declarations:
        symbol = 'Java_' + _mangle(owner) + '_' + _mangle(name)
        if counts[owner, name] > 1:
            parameters, _ = classfile.split_method(descriptor)
            symbol += '__' + _mangle(''.join(parameters))
        exports.add(symbol)
    if len(exports) != len(declarations):
        raise ValueError('ambiguous TinyFD JNI declarations')
    return exports
