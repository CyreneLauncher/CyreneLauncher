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
    def layout(self, root):
        launcher=root/'orbis-launcher';(launcher/'tools').mkdir(parents=True)
        (launcher/'repositories.lock.json').write_text(json.dumps({'components':{'orbis-openjdk':{
            'revision':'a'*40,'url':'https://github.com/CyreneLauncher/orbis-openjdk.git'}}}))
        jdk=root/'orbis-openjdk';jdk.mkdir()
        (jdk/'versions.lock.json').write_text(json.dumps({'versions':{v:{'base':v} for v in ('17','21','25')}}))
        toolchain=root/'tc';(toolchain/'lib').mkdir(parents=True);(toolchain/'lib/libc.a').write_bytes(b'libc')
        return launcher,toolchain

    def release(self, root, tag, toolchain, libc=None, member=None):
        """An orbis-openjdk release as the workflow downloads it: archives, SHA256SUMS, release.json."""
        assets=root/tag;assets.mkdir()
        for version in ('17','21','25'):
            runtime=root/'build'/tag/('jdk'+version);(runtime/'lib/server').mkdir(parents=True)
            (runtime/'release').write_text('JAVA_VERSION="'+version+'" '+tag)
            (runtime/'lib/server/libjvm.prx').write_bytes(b'prx')
            files=cli.rp.inventory(runtime)
            # Built from another orbis-openjdk revision than the launcher pins: only libc must match.
            (runtime/'build-provenance.json').write_text(json.dumps({'version':version,'source':{'base':'other'},
                'lock_sha256':'0'*64,'libc_sha256':libc or cli.digest(toolchain/'lib/libc.a'),'output_files':files}))
            with tarfile.open(assets/cli.runtime_asset(version),'w:xz') as out:
                out.add(runtime,arcname=member or 'jdk'+version)
        (assets/'SHA256SUMS').write_text(''.join(cli.digest(a)+'  '+a.name+'\n' for a in sorted(assets.glob('*.tar.xz'))))
        (assets/'release.json').write_text(json.dumps({'tagName':tag,'targetCommitish':'b'*40}))
        return assets

    def patches(self, root, launcher):
        return (patch.object(cli,'ROOT',launcher),patch.object(cli,'WORK',root),
                patch.object(cli,'JDK_RELEASE',root/'orbis-openjdk/output/release.json'))

    def test_installs_any_release_whose_libc_matches(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);launcher,toolchain=self.layout(root)
            first=self.release(root,'jdk-111111111111',toolchain)
            args=SimpleNamespace(from_dir=first,jdk='all',jdk_tag=None,toolchain=toolchain)
            a,b,c=self.patches(root,launcher)
            with a,b,c:
                cli.fetch_jdks(args)
                self.assertIn('jdk-111111111111',(root/'orbis-openjdk/output/jdk17/release').read_text())
                self.assertEqual(cli.installed_jdk_release()['tag'],'jdk-111111111111')
                cli.check_installed_jdk(root/'orbis-openjdk/output/jdk17',17,cli.digest(toolchain/'lib/libc.a'))
                # Only the packaged runtime: the launcher downloads 21 and 25 itself.
                self.assertFalse((root/'orbis-openjdk/output/jdk21').exists())
                cli.fetch_jdks(args)  # the same release again is a no-op
                newer=self.release(root,'jdk-222222222222',toolchain)
                with self.assertRaises(ValueError):  # never replaces an installed release silently
                    cli.fetch_jdks(SimpleNamespace(**{**vars(args),'from_dir':newer}))

    def test_rejects_other_libc_corruption_and_misplaced_runtimes(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);launcher,toolchain=self.layout(root)
            cases=[self.release(root,'jdk-libc',toolchain,libc='f'*64),self.release(root,'jdk-moved',toolchain,member='jdk25')]
            corrupt=self.release(root,'jdk-corrupt',toolchain);(corrupt/cli.runtime_asset('17')).write_bytes(b'corrupt')
            a,b,c=self.patches(root,launcher)
            with a,b,c:
                for assets in [*cases,corrupt]:
                    with self.assertRaises(ValueError):
                        cli.fetch_jdks(SimpleNamespace(from_dir=assets,jdk='17',jdk_tag=None,toolchain=toolchain))
                    self.assertFalse((root/'orbis-openjdk/output/jdk17').exists())

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
