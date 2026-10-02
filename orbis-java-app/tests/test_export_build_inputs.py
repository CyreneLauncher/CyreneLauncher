#!/usr/bin/env python3
"""Exercise real Make dependencies after native/module removals and clean parallel builds."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

APP = Path(__file__).resolve().parents[1]
MAKEFILE = Path(os.environ.get('TEST_MAKEFILE', str(APP / 'Makefile')))


class ExportDependencies(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.export = self.root / 'export-build'
        self.jdk = self.root / 'configuration/jdk'
        self.native_inputs = self.jdk.parent / 'support/native/java.base/libjava'
        self.jvm_inputs = self.jdk.parent / 'hotspot/variant-server/libjvm/objs'
        self.module = self.jdk / 'modules/java.base'
        for directory in (self.native_inputs, self.jvm_inputs, self.module):
            directory.mkdir(parents=True)
        self.object = self.native_inputs / 'removed.o'
        self.object.write_text('object')
        (self.jvm_inputs / 'jvm.o').write_text('jvm object')
        self.resource = self.module / 'Removed.class'
        self.resource.write_text('class resource')
        (self.module / 'Kept.class').write_text('kept resource')
        close_source = self.root / 'src/java.base/bsd/native/libnet/bsd_close.c'
        close_source.parent.mkdir(parents=True)
        close_source.touch()
        toolchain = self.root / 'toolchain'
        toolchain.mkdir()
        self.libc = toolchain / 'lib/libc.a'
        self.libc.parent.mkdir()
        self.libc.write_text('original libc')
        (toolchain / 'link.x').write_text('.init_array : {\n\t\t*(.init_array);\n\t}')
        self.jmod = self.root / 'jmod'
        self.jmod.write_text('''#!/usr/bin/env python3
from pathlib import Path
import sys
source = Path(sys.argv[sys.argv.index('--class-path') + 1])
assert source.is_dir(), 'Missing selected module: ' + str(source)
Path(sys.argv[-1]).write_text('\\n'.join(sorted(str(p.relative_to(source)) + ':' + p.read_text() for p in source.rglob('*') if p.is_file())))
''')
        self.jlink = self.root / 'jlink'
        self.jlink.write_text('''#!/usr/bin/env python3
from pathlib import Path
import sys
source = Path(sys.argv[sys.argv.index('--module-path') + 1])
output = Path(sys.argv[sys.argv.index('--output') + 1]) / 'lib/modules'
output.parent.mkdir(parents=True)
output.write_text('\\n'.join(p.read_text() for p in source.glob('*.jmod')))
''')
        self.jmod.chmod(0o755)
        self.jlink.chmod(0o755)
        self.overrides = self.root / 'overrides.mk'
        self.overrides.write_text('''$(EXPORT_BUILD)/native.stamp:
	mkdir -p $(NATIVE)
	find $(JDK_IMAGE)/../support/native $(JDK_IMAGE)/../hotspot/variant-server/libjvm/objs -type f | sort > $(NATIVE)/inputs
	touch $@
jdk-export:
	@test -f $(EXPORT_BUILD)/runtime-image/lib/modules
''')
        self.args = [f'PROJECT_DIR={APP}', f'EXPORT_BUILD={self.export}', f'BUILD={self.root}/runner',
                     f'JDK_IMAGE={self.jdk}', f'REPO_ROOT={self.root}',
                     f'TOOLCHAIN={toolchain}', f'JMOD={self.jmod}',
                     f'JLINK={self.jlink}', 'RUNTIME_MODULES=java.base']

    def make(self, *targets, overrides=None, check=True):
        command = ['make', '-f', str(MAKEFILE)]
        if overrides is not None:
            command.extend(['-f', str(overrides)])
        return subprocess.run(command + self.args + list(targets), cwd=APP,
                              text=True, capture_output=True, check=check)

    def test_clean_parallel_link_creates_own_directory(self):
        delayed = self.root / 'delayed.mk'
        delayed.write_text('''$(EXPORT_BUILD)/settings:
	sleep 0.2
	mkdir -p $(EXPORT_BUILD)
	touch $@
''')
        result = self.make('-j8', str(self.export / 'jvm-link.x'),
                           str(self.export / 'settings'), overrides=delayed,
                           check=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('__init_array_start', (self.export / 'jvm-link.x').read_text())

    def test_removed_inputs_rebuild_native_and_module_images(self):
        self.make('jdk-export', overrides=self.overrides)
        native_stamp = self.export / 'native.stamp'
        image_stamp = self.export / 'runtime-image.stamp'
        original_native = native_stamp.stat().st_mtime_ns
        original_image = image_stamp.stat().st_mtime_ns
        self.make('jdk-export', overrides=self.overrides)
        self.assertEqual(native_stamp.stat().st_mtime_ns, original_native)
        self.assertEqual(image_stamp.stat().st_mtime_ns, original_image)
        self.libc.write_text('replacement libc')
        os.utime(self.libc, (1, 1))
        self.make('jdk-export', overrides=self.overrides)
        with self.subTest(input='older replacement SDK libc'):
            self.assertNotEqual(native_stamp.stat().st_mtime_ns, original_native)
            self.assertEqual(image_stamp.stat().st_mtime_ns, original_image)
        original_native = native_stamp.stat().st_mtime_ns
        self.object.unlink()
        self.resource.unlink()
        self.make('-j8', 'jdk-export', overrides=self.overrides)
        with self.subTest(input='removed native object'):
            self.assertNotEqual(native_stamp.stat().st_mtime_ns, original_native)
            self.assertNotIn('removed.o', (self.export / 'native/inputs').read_text())
        with self.subTest(input='removed module class'):
            self.assertNotEqual(image_stamp.stat().st_mtime_ns, original_image)
            image = (self.export / 'runtime-image/lib/modules').read_text()
            self.assertNotIn('Removed.class', image)
            self.assertIn('Kept.class', image)
        # Replacing a file with an older mtime must also invalidate its inventory.
        kept = self.module / 'Kept.class'
        kept.write_text('replaced resource')
        os.utime(kept, (1, 1))
        self.make('jdk-export', overrides=self.overrides)
        with self.subTest(input='older replacement module class'):
            self.assertIn('replaced resource', (self.export / 'runtime-image/lib/modules').read_text())
        (self.module / 'Kept.class').unlink()
        self.module.rmdir()
        result = self.make('jdk-export', overrides=self.overrides, check=False)
        self.assertNotEqual(result.returncode, 0, 'Deleted selected module reused a stale image')
        self.assertIn('Missing selected module', result.stderr)


if __name__ == '__main__':
    unittest.main()
