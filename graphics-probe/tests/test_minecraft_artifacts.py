import hashlib
import http.server
import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from minecraft_artifacts import fetch_verified


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.data = b'verified artifact contents'
        self.source = self.root / 'source'
        self.source.write_bytes(self.data)
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0),
            lambda *a, **kw: http.server.SimpleHTTPRequestHandler(*a, directory=str(self.root), **kw))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.url = f'http://127.0.0.1:{self.server.server_port}/source'
        self.sha1 = hashlib.sha1(self.data).hexdigest()
        self.output = self.root / 'output'
        self.output.mkdir()
        self.cache = self.root / 'cache'

    def fetch(self, dest=None, sha1=None, size=None, url=None):
        return fetch_verified(url or self.url, dest or self.output / 'artifact',
                              sha1 or self.sha1, len(self.data) if size is None else size,
                              self.cache, output_root=self.output)

    def test_verified_fetch_and_offline_cache_reuse(self):
        target = self.fetch()
        self.assertEqual(self.data, target.read_bytes())
        target.unlink()
        self.assertEqual(self.data, self.fetch(url='http://127.0.0.1:1/offline').read_bytes())

    def test_rejects_wrong_size_and_hash_without_replacing_good_file(self):
        target = self.fetch()
        for sha1, size in [('0' * 40, len(self.data)), (self.sha1, len(self.data) + 1)]:
            with self.assertRaises(ValueError):
                self.fetch(sha1=sha1, size=size)
            self.assertEqual(self.data, target.read_bytes())

    def test_rejects_path_escape_and_symlink_ancestors(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (self.output / 'link').symlink_to(outside, target_is_directory=True)
        for dest in [self.output / '..' / 'outside' / 'x', self.output / 'link' / 'x', outside / 'x']:
            with self.assertRaises(ValueError):
                self.fetch(dest=dest)
            self.assertFalse((outside / 'x').exists())
        (self.root / 'rootlink').symlink_to(self.output, target_is_directory=True)
        nested = self.root / 'rootlink' / 'nested'
        with self.assertRaises(ValueError):
            fetch_verified(self.url, nested / 'x', self.sha1, len(self.data),
                           self.cache, output_root=nested)

    def test_rejects_cache_hash_prefix_symlink_without_touching_outside_file(self):
        outside = self.root / 'outside-cache'
        outside.mkdir()
        victim = outside / self.sha1
        victim.write_bytes(b'outside contents must remain unchanged')
        self.cache.mkdir()
        (self.cache / self.sha1[:2]).symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            self.fetch()
        self.assertEqual(b'outside contents must remain unchanged', victim.read_bytes())
        self.assertEqual([victim], list(outside.iterdir()))

        victim.write_bytes(self.data)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            self.fetch()
        self.assertEqual(self.data, victim.read_bytes())

    def test_rejects_conflicting_destination_and_interrupted_transfer(self):
        target = self.fetch()
        changed = b'another artifact'
        (self.root / 'changed').write_bytes(changed)
        with self.assertRaises(ValueError):
            self.fetch(sha1=hashlib.sha1(changed).hexdigest(), size=len(changed),
                       url=f'http://127.0.0.1:{self.server.server_port}/changed')
        self.assertEqual(self.data, target.read_bytes())
        missing = self.output / 'missing'
        with self.assertRaises(Exception):
            self.fetch(dest=missing, sha1=hashlib.sha1(b'uncached').hexdigest(),
                       size=8, url='http://127.0.0.1:1/interrupted')
        self.assertFalse(missing.exists())
        self.assertEqual([], list(self.output.glob('*.tmp')))


if __name__ == '__main__':
    unittest.main()
