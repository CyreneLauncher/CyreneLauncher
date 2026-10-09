import importlib.util
import io
import json
from pathlib import Path
import stat
import os
import subprocess
import textwrap
import shutil
import tarfile
import tempfile
import unittest
import zipfile


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parents[1] / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ToolchainTests(unittest.TestCase):
    def test_action_exports_separate_host_version_lines(self):
        root = Path(__file__).parents[2]
        action = (root / '.github/actions/build-host/action.yml').read_text()
        code = textwrap.dedent(action.split("python3 - <<'PYTHON'\n", 1)[1].split('        PYTHON', 1)[0])
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'output'
            lock = Path(directory) / 'orbis-launcher/tools/toolchain.lock.json'
            lock.parent.mkdir(parents=True)
            shutil.copyfile(root / 'tools/toolchain.lock.json', lock)
            subprocess.run(['python3', '-c', code], cwd=directory,
                           env={**os.environ, 'GITHUB_OUTPUT': str(output)}, check=True)
            lines = dict(line.split('=', 1) for line in output.read_text().splitlines())
            self.assertEqual(set(lines), {'jdk17', 'jdk21', 'jdk25', 'dotnet'})
            # Exact versions returned by Adoptium and accepted by setup-java.
            self.assertEqual(lines['jdk21'], '21.0.10+7.0.LTS')
            self.assertEqual(lines['jdk25'], '25.0.2+10.0.LTS')

    def test_zip_keeps_executable_mode_and_rejects_escape(self):
        provision = load('provision_toolchain')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'sdk.zip'
            with zipfile.ZipFile(archive, 'w') as output:
                entry = zipfile.ZipInfo('sdk/bin/compiler')
                entry.external_attr = (stat.S_IFREG | 0o755) << 16
                output.writestr(entry, '#!/bin/sh\n')
            provision.unpack(archive, root / 'good')
            self.assertEqual((root / 'good/sdk/bin/compiler').stat().st_mode & 0o777, 0o755)
            with zipfile.ZipFile(archive, 'w') as output:
                output.writestr('../escape', 'bad')
            with self.assertRaises(ValueError):
                provision.unpack(archive, root / 'bad')
            self.assertFalse((root / 'escape').exists())

    def test_corrupt_sdk_is_rejected_before_extraction(self):
        provision = load('provision_toolchain')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'sdk.tar.gz'
            archive.write_bytes(b'corrupt')
            with self.assertRaises(ValueError):
                provision.verify(archive, '0' * 64)


class ArtifactTests(unittest.TestCase):
    def test_artifact_roundtrip_preserves_mode_and_checks_content(self):
        artifacts = load('build_artifacts')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source'
            component = source / 'orbis-openjdk/output/jdk17'
            component.mkdir(parents=True)
            executable = component / 'java'
            executable.write_bytes(b'host executable')
            executable.chmod(0o755)
            archive = root / 'jdk17.tar.gz'
            artifacts.pack(source, archive, ['orbis-openjdk/output/jdk17'])
            artifacts.unpack(archive, root / 'destination')
            restored = root / 'destination/orbis-openjdk/output/jdk17/java'
            self.assertEqual(restored.read_bytes(), executable.read_bytes())
            self.assertEqual(restored.stat().st_mode & 0o777, 0o755)
            archive.write_bytes(b'corrupt')
            with self.assertRaises(ValueError):
                artifacts.unpack(archive, root / 'other')

    def test_artifact_rejects_overwrite_and_unsafe_members(self):
        artifacts = load('build_artifacts')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source'
            (source / 'component').mkdir(parents=True)
            (source / 'component/file').write_text('original')
            archive = root / 'component.tar.gz'
            artifacts.pack(source, archive, ['component'])
            with self.assertRaises(ValueError):
                artifacts.unpack(archive, source)
            with tarfile.open(archive, 'w:gz') as output:
                item = tarfile.TarInfo('../escape')
                item.size = 3
                output.addfile(item, io.BytesIO(b'bad'))
            archive.with_name(archive.name + '.sha256').write_text(artifacts.digest(archive))
            with self.assertRaises(ValueError):
                artifacts.unpack(archive, root / 'destination')
            self.assertFalse((root / 'escape').exists())


if __name__ == '__main__':
    unittest.main()
