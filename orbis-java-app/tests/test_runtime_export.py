#!/usr/bin/env python3
"""Exercise runtime assembly and replacement with materialized fixture data."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import tempfile
from export_jdk import export
from check_native_package import NATIVE_MODULES, SELF_MAGIC, check_runtime

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    jdk, image, native = [root / name for name in ('jdk', 'image', 'native')]
    for directory in (jdk / 'conf', jdk / 'legal', jdk / 'lib/security', image / 'lib', native):
        directory.mkdir(parents=True)
    (jdk / 'release').write_text('JAVA_VERSION="17"\n')
    (jdk / 'legal/LICENSE').write_text('legal notice')
    (jdk / 'legal/COPYING').symlink_to('LICENSE')
    (jdk / 'conf/security').write_text('security config')
    (image / 'lib/modules').write_bytes(b'image fixture')
    (image / 'lib/extra.dat').write_bytes(b'first export')
    (image / 'lib/host.so').write_bytes(b'\x7fELFhost')
    (root / 'tzdb.dat').write_bytes(b'timezone data')
    (jdk / 'lib/tzdb.dat').symlink_to(root / 'tzdb.dat')
    (jdk / 'lib/security/cacerts').write_bytes(b'trust store')
    (jdk / 'lib/host.so').write_bytes(b'\x7fELFhost')
    for name in ('jvm', *NATIVE_MODULES):
        (native / f'lib{name}.prx').write_bytes(SELF_MAGIC + b'fixture')
    jimage = root / 'jimage'
    jimage.write_text('#!/bin/sh\nprintf "Module: java.base\\nModule: java.management\\n"\n')
    jimage.chmod(0o755)
    output = root / 'installation-one'
    export(jdk, image, native, output, jimage)
    assert check_runtime(output) == 10
    assert not (output / 'legal/COPYING').is_symlink()
    assert (output / 'legal/COPYING').read_text() == 'legal notice'
    assert not (output / 'lib/host.so').exists()
    assert (output / 'lib/tzdb.dat').is_file(), 'exploded JDK timezone data missing'
    assert not (output / 'lib/tzdb.dat').is_symlink()
    assert (output / 'lib/tzdb.dat').read_bytes() == b'timezone data'
    assert (output / 'lib/security/cacerts').read_bytes() == b'trust store'
    assert (output / 'lib/orbis-modules.list').read_text() == 'java.base\njava.management\n'
    (image / 'lib/tzdb.dat').write_bytes(b'selected image timezone data')
    (image / 'lib/extra.dat').unlink()
    (output / 'lib/stale.so').write_bytes(SELF_MAGIC)
    export(jdk, image, native, output, jimage)
    assert (output / 'lib/tzdb.dat').read_bytes() == b'selected image timezone data'
    assert not (output / 'lib/extra.dat').exists()
    assert not (output / 'lib/stale.so').exists()
    export(jdk, image, native, root / 'installation-two', jimage)
    (native / 'libjava.prx').write_bytes(b'\x7fELFbad')
    try:
        export(jdk, image, native, output, jimage)
        raise AssertionError('raw ELF accepted')
    except ValueError:
        pass
    assert check_runtime(output) == 10, 'failed export destroyed previous runtime'
print('Runtime export roots, replacement, symlinks, legal, module metadata and SELF checks passed')
