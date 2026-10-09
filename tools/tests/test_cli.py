import importlib.util
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('cyrene', Path(__file__).parents[1] / 'cyrene.py')
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)

class CliTests(unittest.TestCase):
    def test_corrupt_download_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)/'x'; p.write_bytes(b'bad')
            with self.assertRaises(ValueError):
                cli.fetch('unused', p, 'sha256', '0'*64)
    def test_archive_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            archive=Path(d)/'bad.tar.gz'
            with tarfile.open(archive,'w:gz') as t:
                info=tarfile.TarInfo('../outside'); info.size=1
                t.addfile(info,io.BytesIO(b'x'))
            with self.assertRaises(ValueError): cli.extract(archive,Path(d)/'out')
            self.assertFalse((Path(d)/'outside').exists())
    def test_archive_links_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            archive=Path(d)/'bad.tar.gz'
            with tarfile.open(archive,'w:gz') as t:
                info=tarfile.TarInfo('link');info.type=tarfile.SYMTYPE;info.linkname='/etc/passwd';t.addfile(info)
            with self.assertRaises(ValueError):cli.extract(archive,Path(d)/'out')
    def test_manifest_requires_complete_runtimes(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):cli.runtime_files(Path(d))
    def test_inventory_detects_changes(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'release').write_text('JAVA_VERSION="17.0.1"')
            (root/'lib/server').mkdir(parents=True);(root/'lib/server/libjvm.prx').write_bytes(b'jvm')
            first=cli.runtime_files(root);(root/'lib/server/libjvm.prx').write_bytes(b'changed')
            self.assertNotEqual(first,cli.runtime_files(root))
    def test_failed_stage_is_not_recorded(self):
        with tempfile.TemporaryDirectory() as d:
            runner=cli.Runner(Path(d))
            with self.assertRaises(RuntimeError):runner.run('failure',['python3','-c','raise SystemExit(7)'])
            self.assertTrue((Path(d)/'failure.log').exists())

if __name__=='__main__':unittest.main()
