"""Exact classfile linkage and compiled-facade scanner contracts."""
import json
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
import classfile  # noqa: E402


def fixture(*members, wide=(), modified_null=False):
    """Build a small valid class with hand-chosen constant-pool references."""
    pool = []

    def put(tag, payload):
        pool.append(bytes([tag]) + payload)
        index = len(pool)
        if tag in (5, 6):
            pool.append(None)
        return index

    def utf(s):
        b = s.encode()
        return put(1, struct.pack('>H', len(b)) + b)

    def cls(s):
        return put(7, struct.pack('>H', utf(s)))

    this = cls('example/Use')
    parent = cls('java/lang/Object')
    if modified_null:
        put(1, b'\x00\x03A\xc0\x80')
    for tag in wide:
        put(tag, b'\0' * 8)
    for tag, owner, name, descriptor in members:
        owner_index = cls(owner)
        pair = put(12, struct.pack('>HH', utf(name), utf(descriptor)))
        put(tag, struct.pack('>HH', owner_index, pair))
    return (b'\xca\xfe\xba\xbe' + struct.pack('>HHH', 0, 52, len(pool) + 1)
            + b''.join(item for item in pool if item is not None)
            + struct.pack('>HHHHHHH', 0x21, this, parent, 0, 0, 0, 0))


def run_scan(jars, audit):
    with tempfile.TemporaryDirectory() as td:
        out = Path(td) / 'report.json'
        cmd = [sys.executable, str(ROOT / 'tools/scan_lwjgl_usage.py'), str(jars[0])]
        for dependency in jars[1:]:
            cmd += ['--dependency', str(dependency)]
        cmd += ['--facade-audit', str(audit), '--json', str(out)]
        result = subprocess.run(cmd, text=True, capture_output=True)
        return result, json.loads(out.read_text()) if out.exists() else None


class LwjglUsageTests(unittest.TestCase):
    def test_overload_identity(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            jar = root / 'client.jar'
            with zipfile.ZipFile(jar, 'w') as z:
                z.writestr('example/Use.class', fixture(
                    (10, 'org/lwjgl/glfw/GLFW', 'glfwGetKey', '(JI)I'),
                    (10, 'org/lwjgl/glfw/GLFW', 'glfwGetKey', '(J)I')))
            audit = root / 'facade.json'
            audit.write_text(json.dumps({'implemented': ['glfwGetKey (JI)I']}))
            result, report = run_scan([jar], audit)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(report['missing_glfw'], [
                {'owner': 'org/lwjgl/glfw/GLFW', 'name': 'glfwGetKey', 'descriptor': '(J)I'}])
            self.assertEqual([r['descriptor'] for r in report['references']], ['(J)I', '(JI)I'])

    def test_callback_owner_not_glfw(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            jar = root / 'client.jar'
            with zipfile.ZipFile(jar, 'w') as z:
                z.writestr('example/Use.class', fixture(
                    (10, 'org/lwjgl/glfw/GLFWKeyCallback', 'create', '()J')))
            audit = root / 'facade.json'
            audit.write_text('{"implemented": []}')
            result, report = run_scan([jar], audit)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(report['missing_glfw'], [])
            self.assertEqual(report['references'][0]['owner'], 'org/lwjgl/glfw/GLFWKeyCallback')

    def test_dependency_references(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            jars = [root / 'client.jar', root / 'library.jar']
            for jar, owner in zip(jars, ('org/lwjgl/stb/STBImage', 'org/lwjgl/glfw/GLFW')):
                with zipfile.ZipFile(jar, 'w') as z:
                    z.writestr('example/Use.class', fixture((10, owner, 'call', '()V')))
            audit = root / 'facade.json'
            audit.write_text('{"implemented": []}')
            result, report = run_scan(jars, audit)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual([r['owner'] for r in report['references']],
                             ['org/lwjgl/glfw/GLFW', 'org/lwjgl/stb/STBImage'])
            self.assertEqual(report['references'][0]['users'], ['library.jar:example/Use.class'])

    def test_multiple_users_of_one_identity_are_sorted(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            jars = [root / 'z-client.jar', root / 'a-library.jar']
            identity = (10, 'org/lwjgl/glfw/GLFW', 'glfwInit', '()Z')
            with zipfile.ZipFile(jars[0], 'w') as jar:
                jar.writestr('z/Use.class', fixture(identity))
                jar.writestr('a/Use.class', fixture(identity))
            with zipfile.ZipFile(jars[1], 'w') as jar:
                jar.writestr('m/Use.class', fixture(identity))
            audit = root / 'facade.json'
            audit.write_text('{"implemented": ["glfwInit ()Z"]}')
            result, report = run_scan(jars, audit)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(['a-library.jar:m/Use.class', 'z-client.jar:a/Use.class',
                              'z-client.jar:z/Use.class'], report['references'][0]['users'])

    def test_field_reference(self):
        self.assertTrue(hasattr(classfile, 'references'))
        data = fixture((9, 'org/lwjgl/glfw/GLFW', 'GLFW_TRUE', 'I'))
        self.assertEqual(classfile.references(data),
                         {('field', 'org/lwjgl/glfw/GLFW', 'GLFW_TRUE', 'I')})

    def test_long_double_pool_slots(self):
        self.assertTrue(hasattr(classfile, 'references'))
        data = fixture((10, 'org/lwjgl/glfw/GLFW', 'glfwInit', '()Z'), wide=(5, 6))
        self.assertEqual(classfile.references(data),
                         {('method', 'org/lwjgl/glfw/GLFW', 'glfwInit', '()Z')})

    def test_truncated_class_rejected(self):
        self.assertTrue(hasattr(classfile, 'references'))
        data = fixture((10, 'org/lwjgl/glfw/GLFW', 'glfwInit', '()Z'))
        with self.assertRaises(ValueError):
            classfile.references(data[:-1])

    def test_modified_utf8_null_constant(self):
        data = fixture((10, 'org/lwjgl/glfw/GLFW', 'glfwInit', '()Z'), modified_null=True)
        self.assertEqual(classfile.references(data),
                         {('method', 'org/lwjgl/glfw/GLFW', 'glfwInit', '()Z')})


if __name__ == '__main__':
    unittest.main()
