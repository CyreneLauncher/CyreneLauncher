"""Exercise repository pins and recovery with real, disposable Git histories."""
import importlib.util
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

TOOL = Path(__file__).resolve().parents[1] / 'tools/repositories.py'


class Repositories(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('repositories', TOOL)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.seeds = self.root / 'seeds'
        self.seed = self.seeds / 'example-port'
        self.seed.mkdir(parents=True)
        self.git('init', '-q', self.seed)
        self.git('-C', self.seed, 'config', 'user.name', 'Test')
        self.git('-C', self.seed, 'config', 'user.email', 'test@example.invalid')
        (self.seed / 'source.c').write_text('original\n')
        self.git('-C', self.seed, 'add', '.')
        self.git('-C', self.seed, 'commit', '-qm', 'Original')
        self.revision = self.git('-C', self.seed, 'rev-parse', 'HEAD')
        self.launcher = self.root / 'workspace/launcher'
        self.launcher.mkdir(parents=True)
        (self.launcher / 'source.c').symlink_to('../example-port/source.c')
        self.lock = self.launcher / 'repositories.lock.json'
        self.data = {'schema': 1, 'components': {'example': {
            'directory': 'example-port', 'revision': self.revision, 'url': None}},
            'links': {'source.c': {'component': 'example', 'path': 'source.c'}}}
        self.write_lock()

    def git(self, *args):
        return subprocess.check_output(['git', *map(str, args)], text=True).strip()

    def write_lock(self):
        self.lock.write_text(json.dumps(self.data))

    def bootstrap(self):
        self.module.bootstrap(self.lock, self.seeds)

    def verify(self):
        self.module.verify(self.lock)

    def test_seed_bootstrap_checks_out_exact_pin_and_preserves_seed(self):
        self.bootstrap()
        self.verify()
        self.assertEqual(self.git('-C', self.launcher.parent / 'example-port', 'rev-parse', 'HEAD'), self.revision)
        self.assertEqual((self.seed / 'source.c').read_text(), 'original\n')
        self.bootstrap()  # Existing clean pinned repositories are left alone.

    def test_bundle_recovery(self):
        bundles = self.root / 'bundles'
        bundles.mkdir()
        self.git('-C', self.seed, 'bundle', 'create', bundles / 'example-port.bundle', '--all')
        self.module.bootstrap(self.lock, bundles)
        self.verify()

    def test_shallow_bundle_recovery_preserves_boundary(self):
        self.git('-C', self.seed, 'commit', '-qm', 'Child', '--allow-empty')
        shallow_root = self.root / 'shallow-seeds'
        shallow_root.mkdir()
        shallow = shallow_root / 'example-port'
        self.git('clone', '-q', '--depth=1', self.seed.as_uri(), shallow)
        revision = self.git('-C', shallow, 'rev-parse', 'HEAD')
        self.data['components']['example']['revision'] = revision
        self.write_lock()
        bundles = self.root / 'bundles'
        bundles.mkdir()
        bundle = bundles / 'example-port.bundle'
        self.git('-C', shallow, 'bundle', 'create', bundle, '--all')
        shutil.copyfile(shallow / '.git/shallow', bundles / 'example-port.bundle.shallow')
        self.module.bootstrap(self.lock, bundles)
        self.verify()
        restored = self.launcher.parent / 'example-port'
        self.assertEqual(self.git('-C', restored, 'rev-parse', '--is-shallow-repository'), 'true')

    def test_missing_repository_is_actionable(self):
        with self.assertRaisesRegex(ValueError, 'missing repository'):
            self.verify()

    def test_dirty_repository_is_never_overwritten(self):
        self.bootstrap()
        source = self.launcher.parent / 'example-port/source.c'
        source.write_text('local work\n')
        with self.assertRaisesRegex(ValueError, 'dirty repository'):
            self.bootstrap()
        self.assertEqual(source.read_text(), 'local work\n')

    def test_revision_mismatch(self):
        self.bootstrap()
        self.data['components']['example']['revision'] = '0' * 40
        self.write_lock()
        with self.assertRaisesRegex(ValueError, 'revision mismatch'):
            self.verify()

    def test_wrong_compatibility_link(self):
        self.bootstrap()
        link = self.launcher / 'source.c'
        link.unlink()
        link.symlink_to('../../seeds/example-port/source.c')
        with self.assertRaisesRegex(ValueError, 'link mismatch'):
            self.verify()

    def test_directory_traversal_rejected_before_clone(self):
        self.data['components']['example']['directory'] = '../escape'
        self.write_lock()
        with self.assertRaisesRegex(ValueError, 'invalid directory'):
            self.bootstrap()
        self.assertFalse((self.launcher.parent / 'example-port').exists())

    def test_bad_pin_leaves_no_destination(self):
        self.data['components']['example']['revision'] = '0' * 40
        self.write_lock()
        with self.assertRaises((ValueError, subprocess.CalledProcessError)):
            self.bootstrap()
        self.assertFalse((self.launcher.parent / 'example-port').exists())

    def test_recipe_uses_its_own_file_and_explicit_integration_root(self):
        (self.seed / 'recipe.py').write_text('import os\nlocation = __file__\nintegration = os.environ["ORBIS_LAUNCHER_ROOT"]\n')
        self.git('-C', self.seed, 'add', 'recipe.py')
        self.git('-C', self.seed, 'commit', '-qm', 'Add recipe')
        self.data['components']['example']['revision'] = self.git('-C', self.seed, 'rev-parse', 'HEAD')
        self.write_lock()
        self.bootstrap()
        namespace = {'__name__': 'imported_recipe'}
        self.module.run_recipe('example', 'recipe.py', namespace, self.launcher)
        self.assertEqual(namespace['integration'], str(self.launcher))
        self.assertEqual(namespace['location'], str(self.launcher.parent / 'example-port/recipe.py'))


if __name__ == '__main__':
    unittest.main()
