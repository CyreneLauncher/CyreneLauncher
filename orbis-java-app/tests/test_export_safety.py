#!/usr/bin/env python3
"""Regression checks for exporter source preservation and directory links."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import tempfile
import unittest
from export_jdk import export
from check_native_package import NATIVE_MODULES, SELF_MAGIC, check_runtime

class ExportSafety(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.jdk, self.image, self.native = [self.root / name for name in ('jdk', 'image', 'native')]
        for path in (self.jdk / 'conf', self.jdk / 'legal', self.image / 'lib', self.native):
            path.mkdir(parents=True)
        (self.jdk / 'release').write_text('release')
        (self.jdk / 'legal/LICENSE').write_text('notice')
        (self.image / 'lib/modules').write_bytes(b'image')
        for name in ('jvm', *NATIVE_MODULES):
            (self.native / f'lib{name}.prx').write_bytes(SELF_MAGIC)
        self.jimage = self.root / 'jimage'
        self.jimage.write_text('#!/bin/sh\nprintf "Module: java.base\\n"\n')
        self.jimage.chmod(0o755)

    def assemble(self, output):
        return export(self.jdk, self.image, self.native, output, self.jimage)

    def test_fallback_legal_overlap(self):
        packaged = self.root / 'images/jdk'
        packaged.mkdir(parents=True)
        (self.jdk / 'legal').rename(packaged / 'legal')
        marker = packaged / 'input-marker'
        marker.write_text('preserve packaged image')
        with self.assertRaises(ValueError):
            self.assemble(packaged)
        self.assertEqual(marker.read_text(), 'preserve packaged image')

    def test_release_symlink_overlap(self):
        output = self.root / 'external-output'
        output.mkdir()
        release_target = output / 'original-release'
        release_target.write_text('preserve release source')
        release_link = self.jdk / 'release'
        release_link.unlink()
        release_link.symlink_to(release_target)
        with self.assertRaises(ValueError):
            self.assemble(output)
        self.assertEqual(release_target.read_text(), 'preserve release source')
        self.assertTrue(release_link.is_symlink())

    def test_destination_symlink(self):
        target = self.root / 'existing-target'
        target.mkdir()
        marker = target / 'marker'
        marker.write_text('preserve target')
        output = self.root / 'output'
        output.symlink_to(target, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.assemble(output)
        self.assertTrue(output.is_symlink())
        self.assertEqual(marker.read_text(), 'preserve target')

    def test_module_metadata_matches_image(self):
        output = self.root / 'output'
        self.assemble(output)
        (output / 'lib/orbis-modules.list').write_text('unrelated.module\n')
        with self.assertRaisesRegex(ValueError, 'differs'):
            check_runtime(output, self.jimage)

    def test_directory_symlinks_and_cycle(self):
        data = self.root / 'security-data'
        data.mkdir()
        (data / 'cacerts').write_bytes(b'certificates')
        (data / 'host.so').write_bytes(b'\x7fELF')
        (self.image / 'lib/security').symlink_to(data, target_is_directory=True)
        output = self.root / 'output'
        self.assemble(output)
        self.assertEqual((output / 'lib/security/cacerts').read_bytes(), b'certificates')
        self.assertFalse((output / 'lib/security').is_symlink())
        self.assertFalse((output / 'lib/security/host.so').exists())
        (data / 'cycle').symlink_to(data, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'cycle'):
            self.assemble(output)
        self.assertEqual((output / 'lib/security/cacerts').read_bytes(), b'certificates')

if __name__ == '__main__':
    unittest.main()
