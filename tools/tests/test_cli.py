import importlib.util
import io
import json
import tarfile
import subprocess
import shutil
from types import SimpleNamespace
from unittest.mock import patch
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('cyrene', Path(__file__).parents[1] / 'cyrene.py')
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)

class CliTests(unittest.TestCase):
    def test_wrapper_loads_local_configuration(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);shutil.copyfile(Path(__file__).parents[2]/'orbis',root/'orbis')
            (root/'tools').mkdir();(root/'tools/cyrene.py').write_text("import os;print(os.environ.get('CYRENE_CONFIG_TEST','missing'))")
            (root/'.cyrene.env').write_text('CYRENE_CONFIG_TEST="path with spaces"\n')
            result=subprocess.run(['bash',str(root/'orbis')],capture_output=True,text=True,check=True)
            self.assertEqual(result.stdout.strip(),'path with spaces')
    def test_package_prerequisites_do_not_require_boot_jdk21(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            args=SimpleNamespace(sdk=root/'sdk',toolchain=root/'tc',pkgtool=root/'PkgTool.dll',boot_jdk17=root/'jdk17',boot_jdk25=root/'jdk25',boot_jdk21=None)
            for path in [args.sdk/'bin/orbis-clang',args.sdk/'bin/clang',args.toolchain/'link.x',args.toolchain/'bin/linux/create-fself',args.pkgtool,*[root/f'jdk{v}'/'bin'/name for v in (17,25) for name in ('java','javac')]]:
                path.parent.mkdir(parents=True,exist_ok=True);path.touch()
            def java_version(command,**kwargs):
                version='17' if 'jdk17' in str(command[0]) else '25'
                return SimpleNamespace(returncode=0,stdout='',stderr='openjdk version "'+version+'.0.1"')
            with patch.object(cli.shutil,'which',return_value='/tool'),patch.object(cli.subprocess,'run',side_effect=java_version):
                cli.doctor(args,required_versions=(17,25))
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
    def test_recorded_inventory_rejects_modified_output(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'component').mkdir();(root/'component/file').write_bytes(b'original')
            recorded={'component':{'file':cli.digest(root/'component/file')}}
            cli.verify_inventories(root,recorded)
            (root/'component/file').write_bytes(b'changed')
            with self.assertRaises(ValueError):cli.verify_inventories(root,recorded)
    def test_jdk_provenance_rejects_modified_binary(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'release').write_text('JAVA_VERSION="17.0.1"')
            (root/'lib/server').mkdir(parents=True);(root/'lib/server/libjvm.prx').write_bytes(b'jvm')
            report={'lock_sha256':'lock','libc_sha256':'libc','source':{'version':'17'},'output_files':cli.runtime_files(root)}
            (root/'build-provenance.json').write_text(json.dumps(report))
            cli.verify_jdk(root,{'version':'17'},'lock','libc')
            (root/'lib/server/libjvm.prx').write_bytes(b'changed')
            with self.assertRaises(ValueError):cli.verify_jdk(root,{'version':'17'},'lock','libc')
    def test_recorded_inventory_rejects_escape(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):cli.verify_inventories(Path(d),{'../escape':{}})
    def test_failed_stage_is_not_recorded(self):
        with tempfile.TemporaryDirectory() as d:
            runner=cli.Runner(Path(d))
            with self.assertRaises(RuntimeError):runner.run('failure',['python3','-c','raise SystemExit(7)'])
            self.assertTrue((Path(d)/'failure.log').exists())

if __name__=='__main__':unittest.main()
