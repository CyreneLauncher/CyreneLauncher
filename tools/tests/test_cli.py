import importlib.util
import io
import contextlib
import os
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

class RuntimeReleaseTests(unittest.TestCase):
    def layout(self, root, revision='a'*40):
        launcher=root/'orbis-launcher';(launcher/'tools').mkdir(parents=True)
        (launcher/'repositories.lock.json').write_text(json.dumps({'components':{'orbis-openjdk':{'revision':revision}}}))
        lock=launcher/'tools/runtimes.lock.json'
        lock.write_text(json.dumps({'schema':1,'repository':'o/r','revision':None,'tag':None,
            'assets':{v:{'name':f'orbis-jdk{v}-x86_64.tar.xz','sha256':None} for v in ('17','21','25')}}))
        jdk=root/'orbis-openjdk';jdk.mkdir()
        (jdk/'versions.lock.json').write_text(json.dumps({'versions':{v:{'base':v} for v in ('17','21','25')}}))
        toolchain=root/'tc';(toolchain/'lib').mkdir(parents=True);(toolchain/'lib/libc.a').write_bytes(b'libc')
        return launcher,lock,toolchain

    def runtime_archive(self, root, version, versions_lock, toolchain, member=None):
        runtime=root/'build'/('jdk'+version);(runtime/'lib/server').mkdir(parents=True)
        (runtime/'release').write_text('JAVA_VERSION="'+version+'"')
        (runtime/'lib/server/libjvm.prx').write_bytes(b'prx')
        files=cli.rp.inventory(runtime)
        (runtime/'build-provenance.json').write_text(json.dumps({'source':{'base':version},
            'lock_sha256':cli.digest(versions_lock),'libc_sha256':cli.digest(toolchain/'lib/libc.a'),'output_files':files}))
        archive=root/'assets'/f'orbis-jdk{version}-x86_64.tar.xz';archive.parent.mkdir(exist_ok=True)
        with tarfile.open(archive,'w:xz') as out:
            out.add(runtime,arcname=member or 'jdk'+version)
        return archive

    def test_unpinned_release_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            launcher,lock,_=self.layout(Path(d))
            with patch.object(cli,'ROOT',launcher),patch.object(cli,'RUNTIMES_LOCK',lock):
                with self.assertRaises(ValueError):cli.runtime_release()

    def test_pin_and_fetch_installs_verified_runtime(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);launcher,lock,toolchain=self.layout(root)
            archives=[self.runtime_archive(root,v,root/'orbis-openjdk/versions.lock.json',toolchain) for v in ('17','21','25')]
            (root/'assets/SHA256SUMS').write_text(''.join(cli.digest(a)+'  '+a.name+'\n' for a in archives))
            args=SimpleNamespace(check=False,from_dir=root/'assets',jdk='17',toolchain=toolchain)
            with patch.object(cli,'ROOT',launcher),patch.object(cli,'RUNTIMES_LOCK',lock),patch.object(cli,'WORK',root):
                cli.pin_jdks(args)
                self.assertEqual(json.loads(lock.read_text())['tag'],'jdk-'+'a'*12)
                cli.fetch_jdks(args)
                self.assertEqual((root/'orbis-openjdk/output/jdk17/lib/server/libjvm.prx').read_bytes(),b'prx')
                cli.fetch_jdks(args)
                archives[1].write_bytes(b'corrupt')
                with self.assertRaises(ValueError):
                    cli.fetch_jdks(SimpleNamespace(**{**vars(args),'jdk':'21'}))
                self.assertFalse((root/'orbis-openjdk/output/jdk21').exists())

    def test_runtime_archive_outside_its_version_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);_,_,toolchain=self.layout(root)
            archive=self.runtime_archive(root,'17',root/'orbis-openjdk/versions.lock.json',toolchain,member='jdk25')
            with self.assertRaises(ValueError):
                cli.extract_runtime(archive,'17',root/'out')


class LibraryReleaseTests(unittest.TestCase):
    PORTS = {'mesa-orbis': ('orbis-port/release.json', 'mesa'), 'openal-soft-orbis': ('release.json', 'openal'),
             'sdl-orbis': ('release.json', 'sdl'), 'lwjgl-orbis': ('release.json', 'lwjgl')}

    def layout(self, root):
        support = Path(__file__).parents[3] / 'orbis-runtime-support/release_archive.py'
        if not support.is_file():
            self.skipTest('orbis-runtime-support checkout is not beside the launcher')
        (root / 'orbis-runtime-support').mkdir()
        shutil.copyfile(support, root / 'orbis-runtime-support/release_archive.py')
        launcher = root / 'orbis-launcher'; (launcher / 'tools').mkdir(parents=True)
        components = {}
        for index, (name, (spec, prefix)) in enumerate(self.PORTS.items()):
            revision = str(index) * 40
            components[name] = {'directory': name, 'revision': revision,
                                'url': f'https://github.com/CyreneLauncher/{name}.git'}
            (root / name / spec).parent.mkdir(parents=True, exist_ok=True)
            (root / name / spec).write_text(json.dumps({'asset': f'{name}.tar.xz', 'tag_prefix': prefix,
                                                        'outputs': [], 'files': [f'{prefix}-out']}))
        (launcher / 'repositories.lock.json').write_text(json.dumps({'components': components}))
        lock = launcher / 'tools/library-releases.lock.json'
        lock.write_text(json.dumps({'schema': 1, 'components': {name: {'spec': spec, 'revision': None, 'sha256': None}
                                                                for name, (spec, _) in self.PORTS.items()}}))
        return launcher, lock, components

    def releases(self, root, archive, mesa_sha=None):
        assets = root / 'assets'
        for name, (spec, prefix) in self.PORTS.items():
            build = root / 'release-build' / name
            (build / f'{prefix}-out').mkdir(parents=True)
            (build / f'{prefix}-out/lib.prx').write_bytes(name.encode())
            asset = archive.pack(build, {'asset': f'{name}.tar.xz', 'files': [f'{prefix}-out']}, assets / name / f'{name}.tar.xz')
            (assets / name / 'SHA256SUMS').write_text(archive.digest(asset) + '  ' + asset.name + '\n')
        mesa = (assets / 'mesa-orbis/SHA256SUMS').read_text().split()[0]
        (root / 'lwjgl-orbis/dependencies.lock.json').write_text(json.dumps(
            {'mesa-orbis': {'revision': '0' * 40, 'sha256': mesa_sha or mesa}}))
        return assets

    def test_unpinned_libraries_are_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            launcher, lock, _ = self.layout(Path(d))
            with patch.object(cli, 'ROOT', launcher), patch.object(cli, 'LIBRARY_LOCK', lock):
                with self.assertRaises(ValueError):
                    cli.check_library_pins()

    def test_pin_and_fetch_install_every_port_release(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); launcher, lock, _ = self.layout(root)
            with patch.object(cli, 'ROOT', launcher), patch.object(cli, 'WORK', root), \
                    patch.object(cli, 'GP', launcher / 'graphics-probe'), patch.object(cli, 'LIBRARY_LOCK', lock):
                assets = self.releases(root, cli.release_archive())
                args = SimpleNamespace(check=False, from_dir=assets)
                cli.pin_libraries(args)
                cli.check_library_pins()
                tags = [item['tag'] for item in cli.library_releases(json.loads(lock.read_text()))]
                self.assertEqual(tags, ['mesa-' + '0' * 12, 'openal-' + '1' * 12, 'sdl-' + '2' * 12, 'lwjgl-' + '3' * 12])
                cli.fetch_libraries(args)
                self.assertEqual((launcher / 'graphics-probe/build/lwjgl-out/lib.prx').read_bytes(), b'lwjgl-orbis')
                cli.fetch_libraries(args)  # recorded installs are not unpacked twice

    def test_platform_must_link_the_shipped_mesa_release(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); launcher, lock, _ = self.layout(root)
            with patch.object(cli, 'ROOT', launcher), patch.object(cli, 'WORK', root), patch.object(cli, 'LIBRARY_LOCK', lock):
                assets = self.releases(root, cli.release_archive(), mesa_sha='f' * 64)
                with self.assertRaises(ValueError):
                    cli.pin_libraries(SimpleNamespace(check=False, from_dir=assets))


class CliTests(unittest.TestCase):
    def test_single_jdk_build_selects_matching_boot_jdk(self):
        with tempfile.TemporaryDirectory() as directory:
            args=SimpleNamespace(jdk='21',boot_jdk21=Path('/boot21'),sdk=Path('/sdk'),toolchain=Path('/tc'),jobs=2)
            with patch.object(cli,'WORK',Path(directory)), patch.object(cli,'script') as script:
                cli.jdks(args,object())
            self.assertEqual(script.call_count,1)
            self.assertEqual(script.call_args.args[1],'jdk21')
            self.assertIn(Path('/boot21'),script.call_args.args)

    def test_assets_build_does_not_require_runtime_exports(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch('sys.argv',['orbis','assets']), patch.object(cli,'Runner'), patch.object(cli,'doctor') as doctor, patch.object(cli,'bootstrap'), patch.object(cli,'dependencies'), patch.object(cli,'native') as native, patch.object(cli,'fetch_libraries') as libraries, patch.object(cli,'facades') as facades, patch.object(cli,'launcher') as launcher, patch.object(cli,'record_components') as record:
                self.assertEqual(cli.main(),0)
                self.assertEqual(doctor.call_args.args[1],(17,25))
                # Released libraries replace the native source build; the launcher builds the facades.
                native.assert_not_called();libraries.assert_called_once();facades.assert_called_once()
                launcher.assert_called_once();record.assert_not_called()

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
    def test_ci_runner_streams_and_retains_compiler_output(self):
        with tempfile.TemporaryDirectory() as directory:
            runner=cli.Runner(Path(directory))
            display=io.StringIO()
            source=Path(directory)/'compiler.py'
            source.write_text("import sys; print('compiled module'); print('compiler warning', file=sys.stderr)")
            with patch.dict(os.environ,{'GITHUB_ACTIONS':'true'}), contextlib.redirect_stdout(display):
                runner.run('compiler',['python3',str(source)])
            for text in ('compiled module','compiler warning'):
                self.assertIn(text,display.getvalue())
                self.assertIn(text,(Path(directory)/'compiler.log').read_text())

    def test_failed_stage_is_not_recorded(self):
        with tempfile.TemporaryDirectory() as d:
            runner=cli.Runner(Path(d))
            with self.assertRaises(RuntimeError):runner.run('failure',['python3','-c','raise SystemExit(7)'])
            self.assertTrue((Path(d)/'failure.log').exists())

if __name__=='__main__':unittest.main()
