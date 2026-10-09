"""Test release inventory, clean staging and rejection of development residue."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

TOOL = Path(__file__).resolve().parents[1] / 'tools/runtime_package.py'


class RuntimePackage(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('runtime_package', TOOL)
        self.tool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.tool)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_inventory_covers_only_runtime_export_files(self):
        source = self.root / 'export'
        (source / 'lib/server').mkdir(parents=True)
        (source / 'lib/modules').write_bytes(b'modules')
        (source / 'lib/server/libjvm.prx').write_bytes(b'jvm')
        (source / 'release').write_text('JAVA_VERSION="17.0.20"\n')
        files = self.tool.inventory(source)
        self.assertEqual(set(files), {'lib/modules', 'lib/server/libjvm.prx', 'release'})
        (source / 'lib/leftover.o').write_bytes(b'object')
        with self.assertRaisesRegex(ValueError, 'development'):
            self.tool.inventory(source)

    def test_inventory_rejects_symlinks(self):
        source = self.root / 'export'
        source.mkdir()
        (source / 'release').symlink_to('/etc/passwd')
        with self.assertRaisesRegex(ValueError, 'symlink'):
            self.tool.inventory(source)

    def test_validate_rejects_extra_files_and_tampering(self):
        stage = self.root / 'stage'
        (stage / 'manifests').mkdir(parents=True)
        (stage / 'file').write_bytes(b'original')
        manifest = stage / 'manifests/runtime-package.json'
        manifest.write_text(json.dumps({'schema': 1, 'files': {'file': self.tool.digest(stage / 'file')}}))
        self.tool.validate(stage)
        (stage / 'probe.log').write_text('development')
        with self.assertRaisesRegex(ValueError, 'unexpected'):
            self.tool.validate(stage)
        (stage / 'probe.log').unlink()
        (stage / 'file').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            self.tool.validate(stage)

    def test_missing_or_escaping_manifest_entry_is_rejected(self):
        stage = self.root / 'stage'
        (stage / 'manifests').mkdir(parents=True)
        manifest = stage / 'manifests/runtime-package.json'
        for path in ('../outside', '/absolute', 'missing'):
            manifest.write_text(json.dumps({'schema': 1, 'files': {path: '0' * 64}}))
            with self.assertRaises(ValueError):
                self.tool.validate(stage)

    def test_reassembly_accepts_updated_runner_base_but_still_rejects_changed_runtime(self):
        stage = self.root / 'stage'
        (stage / 'manifests').mkdir(parents=True)
        (stage / 'app.jar').write_bytes(b'old app')
        (stage / 'runtime').write_bytes(b'old runtime')
        (stage / 'manifests/runtime-package.json').write_text(json.dumps({'schema': 1, 'files': {
            name: self.tool.digest(stage / name) for name in ('app.jar', 'runtime')}}))
        (stage / 'app.jar').write_bytes(b'rebuilt app')
        self.tool.validate(stage, allow_base_changes=True)
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            self.tool.validate(stage)
        (stage / 'runtime').write_bytes(b'changed runtime')
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            self.tool.validate(stage, allow_base_changes=True)


if __name__ == '__main__':
    unittest.main()
