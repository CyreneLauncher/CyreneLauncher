import hashlib
import http.server
import io
import json
import re
import shutil
import sys
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import prepare_minecraft
from prepare_minecraft import offline_uuid, prepare, verify_upload_tree


class PrepareTests(unittest.TestCase):
    def test_offline_uuid_is_java_name_uuid(self):
        self.assertEqual('5627dd98-e6be-3c21-b8a8-e92344183641', offline_uuid('Steve'))

    def test_rejects_other_version_and_partial_tree(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with self.assertRaises(ValueError):
                prepare('1.19', root / 'out', root / 'cache', 'Steve')
            out = root / 'out'
            out.mkdir()
            (out / 'launch-manifest.json').write_text(json.dumps({'files': {'runner/target.jar':
                {'sha256': hashlib.sha256(b'jar').hexdigest(), 'size': 3}}}))
            with self.assertRaises(ValueError):
                verify_upload_tree(out)

    def test_rejects_manifest_without_required_game_layout(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for relative in ('runner/target.jar', 'runner/version.json', 'runner/runner.conf',
                             'runner/app-args.txt', 'runner/jvm-options.txt', 'UPLOAD.txt'):
                file = root / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(b'x')
            files = {p.relative_to(root).as_posix(): {'size': 1,
                'sha256': hashlib.sha256(b'x').hexdigest()} for p in root.rglob('*') if p.is_file()}
            (root / 'launch-manifest.json').write_text(json.dumps({'files': files}))
            with self.assertRaises(ValueError):
                verify_upload_tree(root)

    def test_local_http_manifest_libraries_assets_and_atomic_tree(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            serve = root / 'serve'
            serve.mkdir()
            handler = lambda *a, **kw: http.server.SimpleHTTPRequestHandler(
                *a, directory=str(serve), **kw)
            server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            self.addCleanup(server.server_close)
            self.addCleanup(server.shutdown)
            base = f'http://127.0.0.1:{server.server_port}'

            def publish(relative, data):
                file = serve / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(data)
                return {'url': base + '/' + relative, 'sha1': hashlib.sha1(data).hexdigest(),
                        'size': len(data)}

            def miniature_jar(label):
                buffer = io.BytesIO()
                with zipfile.ZipFile(buffer, 'w') as jar:
                    jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n\n')
                    jar.writestr('fixture/module.txt', label + '\n')
                return buffer.getvalue()

            modules = ('lwjgl', 'lwjgl-jemalloc', 'lwjgl-openal', 'lwjgl-opengl',
                       'lwjgl-glfw', 'lwjgl-stb', 'lwjgl-tinyfd')
            libraries = []
            fixture_hashes = {}
            for module in modules:
                relative = f'org/lwjgl/{module}/3.2.2/{module}-3.2.2.jar'
                artifact = publish(relative, miniature_jar(module))
                artifact['path'] = relative
                fixture_hashes[module] = artifact['sha1']
                libraries.append({'name': f'org.lwjgl:{module}:3.2.2',
                    'downloads': {'artifact': artifact,
                    'classifiers': {'natives-linux': {'url': base + '/NEVER-NATIVE'}}}})
            self.assertEqual(7, len(libraries))
            fixture_repo = root / 'fixture-repo'
            source_dir = prepare_minecraft.REPO / 'orbis-java-app/src/runner/lwjgl'
            fixture_dir = fixture_repo / 'orbis-java-app/src/runner/lwjgl'
            fixture_dir.mkdir(parents=True)
            for java in source_dir.glob('*.java'):
                shutil.copyfile(java, fixture_dir / java.name)
            registry = fixture_dir / 'LwjglProfileRegistry.java'
            registry_text = registry.read_text()
            official_hashes = dict(re.findall(r'"org\.lwjgl:(lwjgl(?:-[a-z]+)?)", "([0-9a-f]{40})"',
                                              registry_text))
            self.assertEqual(set(modules), set(official_hashes))
            for module, test_hash in fixture_hashes.items():
                original = f'"org.lwjgl:{module}", "{official_hashes[module]}"'
                self.assertEqual(1, registry_text.count(original))
                registry_text = registry_text.replace(original,
                    f'"org.lwjgl:{module}", "{test_hash}"')
            registry.write_text(registry_text)
            self.assertEqual(7, sum(registry_text.count(hash_value) for hash_value in fixture_hashes.values()))
            patcher = mock.patch.object(prepare_minecraft, 'REPO', fixture_repo)
            patcher.start()
            self.addCleanup(patcher.stop)
            common = publish('common.jar', miniature_jar('common'))
            common['path'] = 'test/common/1/common.jar'
            libraries.append({'name': 'test:common:1', 'downloads': {'artifact': common}})
            asset_bytes = b'indexed asset'
            asset_sha = hashlib.sha1(asset_bytes).hexdigest()
            publish(f'{asset_sha[:2]}/{asset_sha}', asset_bytes)
            index = publish('index.json', json.dumps({'objects': {'sample':
                {'hash': asset_sha, 'size': len(asset_bytes)}}}).encode())
            client = publish('client.jar', miniature_jar('client'))
            metadata = {'id': '1.18.2', 'type': 'release', 'mainClass': 'example.Client',
                        'libraries': libraries, 'downloads': {'client': client},
                        'assetIndex': dict(index, id='1.18'),
                        'arguments': {'game': ['--username', '${auth_player_name}',
                            '--clientId', '${clientid}', '--xuid', '${auth_xuid}']}}
            metadata_bytes = json.dumps(metadata, sort_keys=True).encode()
            declaration = publish('version.json', metadata_bytes)
            publish('manifest.json', json.dumps({'versions': [
                {'id': '1.18.2', 'url': declaration['url'], 'sha1': declaration['sha1']}]}).encode())
            out = root / 'output'
            kwargs = dict(manifest_url=base + '/manifest.json',
                          metadata_sha1=declaration['sha1'], client_sha1=client['sha1'],
                          asset_index_sha1=index['sha1'], metadata_size=declaration['size'],
                          asset_base_url=base)
            first = prepare('1.18.2', out, root / 'cache', 'Steve', **kwargs)
            self.assertEqual(8, len(first['classpath']))
            self.assertNotIn('UPLOAD.txt', first['files'])
            self.assertIn('UPLOAD.txt', first['host_files'])
            self.assertEqual('/data/orbis-jdk/runner/target.jar',
                             first['files']['runner/target.jar']['device_path'])
            self.assertEqual(b'indexed asset', (out / 'assets/objects' / asset_sha[:2] / asset_sha).read_bytes())
            stale_classes = out.parent / (out.name + '-host-classes')
            stale_classes.mkdir()
            (stale_classes / 'interrupted.marker').write_text('keep')
            stale_plan = out.parent / (out.name + '-host-plan.json')
            stale_plan.write_text('incomplete prior plan')
            self.assertEqual(first, verify_upload_tree(out))
            self.assertEqual('keep', (stale_classes / 'interrupted.marker').read_text())
            self.assertEqual('incomplete prior plan', stale_plan.read_text())
            manifest_path = out / 'launch-manifest.json'
            manifest_path.write_text(json.dumps(dict(first, classpath=list(reversed(first['classpath'])))))
            with self.assertRaises(ValueError):
                verify_upload_tree(out)
            manifest_path.write_text(json.dumps(first))
            second = prepare('1.18.2', out, root / 'cache', 'Steve', **kwargs)
            self.assertEqual(first, second)
            backup = out.parent / ('.' + out.name + '-previous')
            out.rename(backup)
            stale_backup_classes = backup.parent / (backup.name + '-host-classes')
            stale_backup_classes.mkdir()
            (stale_backup_classes / 'interrupted.marker').write_text('keep')
            (backup.parent / (backup.name + '-host-plan.json')).write_text('incomplete prior plan')
            with self.assertRaises(ValueError):
                prepare('1.18.2', out, root / 'cache', 'Steve',
                        **dict(kwargs, metadata_sha1='0' * 40))
            self.assertEqual(second, verify_upload_tree(out))
            self.assertFalse(backup.exists())
            self.assertEqual('keep', (stale_backup_classes / 'interrupted.marker').read_text())
            with self.assertRaises(ValueError):
                prepare('1.18.2', out, root / 'cache', 'Steve',
                        **dict(kwargs, metadata_sha1='0' * 40))
            self.assertEqual(second, verify_upload_tree(out))
            (out / 'assets/objects' / asset_sha[:2] / asset_sha).unlink()
            with self.assertRaises(ValueError):
                verify_upload_tree(out)


if __name__ == '__main__':
    unittest.main()
