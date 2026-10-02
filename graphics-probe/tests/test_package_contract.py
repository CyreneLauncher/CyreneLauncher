import importlib.util
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/package_probe.py"


class PackageContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("package_probe", SCRIPT)
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def test_distinct_identity_and_exact_payload(self):
        self.assertNotEqual(self.module.TITLE_ID, "OJDK00003")
        self.assertEqual(self.module.TITLE_ID, "OGFX00001")
        self.assertIn(self.module.TITLE_ID, self.module.CONTENT_ID)
        self.assertEqual(self.module.PAYLOAD_FILES, frozenset({
            "eboot.bin", "sce_sys/param.sfo", "sce_sys/icon0.png",
            "sce_sys/about/right.sprx", "sce_module/libc.prx",
            "sce_module/libSceFios2.prx",
        }))
        self.assertFalse(any("jdk" in name.lower() or "runner" in name.lower()
                             for name in self.module.PAYLOAD_FILES))

    def test_stage_checker_rejects_extra_and_missing_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            for name in self.module.PAYLOAD_FILES:
                file = root / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(b"\x4f\x15\x3d\x1d" if name == "eboot.bin" else b"x")
            self.module.check_stage(root)
            (root / "app.jar").write_bytes(b"bad")
            with self.assertRaises(ValueError):
                self.module.check_stage(root)
            (root / "app.jar").unlink()
            (root / "sce_sys/icon0.png").unlink()
            with self.assertRaises(ValueError):
                self.module.check_stage(root)

    def test_isolated_make_target(self):
        makefile = (ROOT / "Makefile").read_text()
        self.assertIn("package:", makefile)
        self.assertNotIn("OJDK00003", makefile)
        self.assertNotIn("upload", makefile.lower())
        self.assertNotIn("install", makefile.lower())

    def test_sdk_override_reaches_link_and_packaging(self):
        dry = subprocess.run(["make", "-n", "-C", str(ROOT), "package",
                              "SDK=/tmp/probe-sdk-override"], capture_output=True,
                             text=True, check=True).stdout
        self.assertIn("build_probe.sh", dry)
        self.assertIn("build/target /tmp/probe-sdk-override", dry)
        self.assertIn("package_probe.py --sdk /tmp/probe-sdk-override", dry)

    def test_mesa_sdk_identity_must_match_package_sdk(self):
        self.module.check_sdk_identity({"sdk": "/tmp/sdk-a"}, pathlib.Path("/tmp/sdk-a"))
        with self.assertRaises(ValueError):
            self.module.check_sdk_identity({"sdk": "/tmp/sdk-a"}, pathlib.Path("/tmp/sdk-b"))

    def test_pkg_listing_has_exact_runtime_files(self):
        valid = "\n".join(sorted({
            "/uroot/eboot.bin", "/uroot/sce_sys/keystone",
            "/uroot/sce_sys/about/right.sprx",
            "/uroot/sce_module/libc.prx",
            "/uroot/sce_module/libSceFios2.prx",
        })) + "\n"
        self.module.check_pkg_listing(valid)
        with self.assertRaises(ValueError):
            self.module.check_pkg_listing(valid + "/uroot/app.jar\n")
        with self.assertRaises(ValueError):
            self.module.check_pkg_listing(valid.replace("/uroot/eboot.bin\n", ""))

    def test_output_inventory_rejects_stale_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            for name in self.module.OUTPUT_FILES:
                (root / name).write_bytes(b"x")
            self.module.check_output(root)
            (root / "old-runner.pkg").write_bytes(b"bad")
            with self.assertRaises(ValueError):
                self.module.check_output(root)


if __name__ == "__main__":
    unittest.main()
