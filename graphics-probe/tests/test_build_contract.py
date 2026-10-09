"""Safety contracts for the isolated Mesa cross build."""

import json
import importlib.util
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build"


class BuildContract(unittest.TestCase):
    def test_libc16_link_order_and_missing_object_gate(self):
        BUILD.mkdir(exist_ok=True)
        spec = importlib.util.spec_from_file_location("build_mesa", ROOT / "tools/build_mesa.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            compat = Path(temp) / "orbis-compat"
            objects = compat / "build/libc16"
            objects.mkdir(parents=True)
            names = ("mbrtowc", "mbtowc", "regcomp", "regexec", "vfscanf")
            for name in names:
                (objects / f"libc16_{name}.o").write_bytes(name.encode())
            selected = module.libc16_objects(compat)
            self.assertEqual([p.name for p in selected], [f"libc16_{name}.o" for name in names])
            template = "c_link_args = ['-lorbis-compat','-lc']\ncpp_link_args = ['-lorbis-compat','-lc']\n"
            augmented = module.augment_cross_template(template, compat, selected)
            self.assertEqual(augmented.count(str(selected[0])), 2)
            self.assertLess(augmented.index("--whole-archive"), augmented.index(str(selected[0])))
            self.assertLess(augmented.index(str(selected[-1])), augmented.index("'-lc'"))
            tokens = module.augment_probe_link(["-lorbis-compat", "-lc"], compat, selected)
            self.assertEqual(tokens[0], "-Wl,--whole-archive")
            self.assertEqual(tokens[1], str(compat / "build/liborbis-compat.a"))
            self.assertEqual(tokens[3:8], [str(path) for path in selected])
            self.assertEqual(tokens[8], "-lc")
            (objects / "libc16_regexec.o").unlink()
            with self.assertRaisesRegex(ValueError, "libc16"):
                module.libc16_objects(compat)

    def test_probe_link_and_package_provenance_require_libc16(self):
        link = (ROOT / "tools/build_probe.sh").read_text()
        package = (ROOT / "tools/package_probe.py").read_text()
        self.assertIn('libc16_*.o', link)
        self.assertIn('"mesa_libc16_sha256"', package)
        self.assertIn('check-wchar-link.sh', link)

    def test_standalone_wchar_audit_is_saved_and_hashed(self):
        link = (ROOT / "tools/build_probe.sh").read_text()
        self.assertIn('probe-wchar-audit.log', link)
        spec = importlib.util.spec_from_file_location("package_probe", ROOT / "tools/package_probe.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp)
            (target / "probe-why-extract.txt").write_text("safe archive extraction\n")
            with self.assertRaisesRegex(ValueError, "wchar audit"):
                module.wchar_audit_provenance(target)
            (target / "probe-wchar-audit.log").write_text(
                "check-wchar-link: no 16-bit wchar_t libc.a member extracted\n")
            records = module.wchar_audit_provenance(target)
            self.assertEqual(set(records), {"probe-why-extract.txt", "probe-wchar-audit.log"})
            for name in records:
                self.assertEqual(records[name], module.digest(target / name))

    def run_script(self, name, *args):
        return subprocess.run(
            [sys.executable, str(ROOT / "tools" / name), *map(str, args)],
            cwd=ROOT, text=True, capture_output=True,
        )

    def test_rejects_moving_branch_reference(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            lock = Path(temp) / "lock.json"
            lock.write_text(json.dumps({"sources": {"mesa": {
                "url": "https://example.invalid/mesa", "revision": "orbis"}}}))
            result = self.run_script("prepare_sources.py", "--lock", lock,
                                     "--output", Path(temp) / "sources")
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("moving revision", result.stderr)
            self.assertFalse((Path(temp) / "sources").exists())

    def test_relative_seed_uses_lock_directory_and_retains_canonical_remote(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            base = Path(temp)
            repo = base / 'seed'
            subprocess.run(['git', 'init', '-q', repo], check=True)
            subprocess.run(['git', '-C', repo, '-c', 'user.name=Probe',
                            '-c', 'user.email=probe@example.invalid', 'commit',
                            '-qm', 'Seed', '--allow-empty'], check=True)
            revision = subprocess.check_output(['git', '-C', repo, 'rev-parse', 'HEAD'], text=True).strip()
            lock_dir = base / 'config'
            lock_dir.mkdir()
            lock = lock_dir / 'lock.json'
            canonical = 'https://example.invalid/canonical.git'
            lock.write_text(json.dumps({'sources': {'mesa': {
                'url': canonical, 'seed': '../seed', 'revision': revision}}}))
            output = base / 'sources'
            result = self.run_script('prepare_sources.py', '--lock', lock, '--output', output)
            self.assertEqual(result.returncode, 0, result.stderr)
            origin = subprocess.check_output(['git', '-C', output / 'mesa', 'remote', 'get-url', 'origin'], text=True).strip()
            self.assertEqual(origin, canonical)

    def test_rejects_mismatched_revision(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            repo = Path(temp) / "repo"
            repo.mkdir()
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            subprocess.run(["git", "-C", str(repo), "-c", "user.name=Probe",
                            "-c", "user.email=probe@example.test", "commit", "-q",
                            "--allow-empty", "-m", "fixture"], check=True)
            lock = Path(temp) / "lock.json"
            lock.write_text(json.dumps({"sources": {"mesa": {
                "url": str(repo), "revision": "0" * 40}}}))
            result = self.run_script("prepare_sources.py", "--lock", lock,
                                     "--output", Path(temp) / "sources")
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("revision mismatch", result.stderr)

    def test_rejects_output_outside_build_root(self):
        with tempfile.TemporaryDirectory() as temp:
            result = self.run_script("prepare_sources.py", "--lock",
                                     ROOT / "deps.lock.json", "--output", temp)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("outside build root", result.stderr)
            self.assertEqual(list(Path(temp).iterdir()), [])

    def test_rejects_host_link_input(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            inputs = Path(temp) / "link-inputs.json"
            inputs.write_text(json.dumps(["/usr/lib/x86_64-linux-gnu/libdrm.so"]))
            result = self.run_script("audit_link.py", "--check-link-inputs", inputs)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("host link input", result.stderr)

    def test_rejects_host_search_directory(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            inputs = Path(temp) / "link-inputs.json"
            inputs.write_text(json.dumps(["-L/usr/lib/x86_64-linux-gnu"]))
            result = self.run_script("audit_link.py", "--check-link-inputs", inputs)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("host link input", result.stderr)

    def test_rejects_host_path_in_response_file(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            response = Path(temp) / "link.rsp"
            response.write_text("-L/usr/lib/x86_64-linux-gnu")
            inputs = Path(temp) / "link-inputs.json"
            inputs.write_text(json.dumps(["@" + str(response)]))
            result = self.run_script("audit_link.py", "--check-link-inputs", inputs)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("host link input", result.stderr)

    def test_accepts_sdk_input_in_response_file(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            response = Path(temp) / "link.rsp"
            response.write_text("/home/wonder/PS4Toolchain/lib/libc.a")
            inputs = Path(temp) / "link-inputs.json"
            inputs.write_text(json.dumps(["@" + str(response)]))
            result = self.run_script("audit_link.py", "--check-link-inputs", inputs)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_rejects_changed_tracked_source(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            repo = Path(temp) / "repo"
            repo.mkdir()
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            source = repo / "meson.build"
            source.write_text("clean\n")
            subprocess.run(["git", "-C", str(repo), "add", "meson.build"], check=True)
            subprocess.run(["git", "-C", str(repo), "-c", "user.name=Probe",
                            "-c", "user.email=probe@example.test", "commit", "-qm",
                            "fixture"], check=True)
            revision = subprocess.check_output(["git", "-C", str(repo),
                                                 "rev-parse", "HEAD"], text=True).strip()
            source.write_text("modified\n")
            spec = importlib.util.spec_from_file_location("build_mesa", ROOT / "tools/build_mesa.py")
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            with self.assertRaisesRegex(ValueError, "dirty source"):
                module.checked_source(repo, revision)

    def test_rejects_unverified_wheel(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            artifact = Path(temp) / "fake.whl"
            artifact.write_bytes(b"tampered")
            lock = Path(temp) / "lock.json"
            lock.write_text(json.dumps({"python_wheels": {artifact.name: "0" * 64}}))
            result = self.run_script("bootstrap_deps.py", "--lock", lock,
                                     "--output", Path(temp) / "deps", "--verify-only",
                                     "--wheels", temp)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("hash mismatch", result.stderr)

    def test_rejects_host_archive_as_target(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            source = Path(temp) / "host.c"
            source.write_text("int host_only(void) { return 1; }\n")
            obj = Path(temp) / "host.o"
            archive = Path(temp) / "libhost.a"
            subprocess.run(["clang", "-c", str(source), "-o", str(obj)], check=True)
            subprocess.run(["llvm-ar", "rcs", str(archive), str(obj)], check=True)
            result = self.run_script("audit_link.py", "--archive", archive)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("wrong target", result.stderr)

    def test_failure_writes_manifest(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            output = Path(temp) / "target"
            result = self.run_script("build_mesa.py", "--sources", Path(temp) / "missing",
                                     "--sdk", "/home/wonder/PS4Toolchain", "--output", output)
            self.assertNotEqual(result.returncode, 0)
            manifest = json.loads((output / "build-manifest.json").read_text())
            self.assertEqual(manifest["status"], "failed")
            self.assertIn("missing", manifest["error"])

    def test_failure_replaces_previous_success_manifest(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            output = Path(temp) / "target"
            output.mkdir()
            (output / "build-manifest.json").write_text('{"status":"success"}')
            result = self.run_script("build_mesa.py", "--sources", Path(temp) / "missing",
                                     "--sdk", "/home/wonder/PS4Toolchain", "--output", output)
            self.assertNotEqual(result.returncode, 0)
            manifest = json.loads((output / "build-manifest.json").read_text())
            self.assertEqual(manifest["status"], "failed")

    def test_rejects_altered_compat_archive(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            archive = Path(temp) / "liborbis-compat.a"
            archive.write_bytes(b"altered")
            result = self.run_script("build_mesa.py", "--check-compat", archive)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("compat archive hash mismatch", result.stderr)

    def test_rejects_stale_bootstrap_manifest(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            deps = Path(temp) / "deps"
            deps.mkdir()
            (deps / "bootstrap-manifest.json").write_text('{"python_wheels":{}}')
            result = self.run_script("build_mesa.py", "--verify-deps", deps)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("bootstrap manifest mismatch", result.stderr)

    def test_rejects_altered_installed_host_tool(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            deps = Path(temp) / "deps"
            (deps / "host/usr/bin").mkdir(parents=True)
            (deps / "bootstrap-manifest.json").write_text(
                (BUILD / "deps/clean/bootstrap-manifest.json").read_text())
            (deps / "host/usr/bin/bison").write_bytes(b"altered")
            result = self.run_script("build_mesa.py", "--verify-deps", deps)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("installed dependency hash mismatch", result.stderr)

    def test_rejects_outside_wheel_dir_before_writing(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory() as outside, tempfile.TemporaryDirectory(dir=BUILD) as temp:
            wheel_dir = Path(outside) / "created-by-bootstrap"
            result = self.run_script("bootstrap_deps.py", "--output", Path(temp) / "deps",
                                     "--wheels", wheel_dir)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(wheel_dir.exists())

    def test_rejects_host_B_and_rpath_equals(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            for token in ("-B/usr/lib", "-Wl,-rpath=/usr/lib"):
                inputs = Path(temp) / "link-inputs.json"
                inputs.write_text(json.dumps([token]))
                result = self.run_script("audit_link.py", "--check-link-inputs", inputs)
                self.assertNotEqual(result.returncode, 0, token)
                self.assertIn("host link input", result.stderr)

    def test_rejects_host_library_path_and_R(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            for token in ("-Wl,--library-path=/usr/lib", "-R/usr/lib"):
                inputs = Path(temp) / "link-inputs.json"
                inputs.write_text(json.dumps([token]))
                result = self.run_script("audit_link.py", "--check-link-inputs", inputs)
                self.assertNotEqual(result.returncode, 0, token)
                self.assertIn("host link input", result.stderr)

    def test_rejects_modified_bison_support_file(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            host = Path(temp) / "host"
            shutil.copytree(BUILD / "deps/clean/host", host, symlinks=True)
            (host / "usr/share/bison/skeletons/yacc.c").write_text("altered")
            result = self.run_script("build_mesa.py", "--verify-support-tree", host)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("support tree hash mismatch", result.stderr)

    def test_accepts_sdk_imports_in_built_probe(self):
        probe = BUILD / "target/gllinkprobe.elf"
        if not probe.is_file():
            self.skipTest("target link probe has not been built")
        result = self.run_script("audit_link.py", "--elf", probe)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_rejects_sdk_imports_missing_from_selected_sdk(self):
        probe = BUILD / "target/gllinkprobe.elf"
        if not probe.is_file():
            self.skipTest("target link probe has not been built")
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            result = self.run_script("audit_link.py", "--sdk", temp, "--elf", probe)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("SDK import", result.stderr)


if __name__ == "__main__":
    unittest.main()
