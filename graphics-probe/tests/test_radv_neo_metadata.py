"""Exercise the Gladius color-metadata policy from the actual Mesa patch."""
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATCH = ROOT / 'patches/mesa/0048-radv-orbis-gladius-color-metadata-safety.patch'


class NeoMetadata(unittest.TestCase):
    def test_neo_requires_explicit_fast_clear_opt_in(self):
        self.assertTrue(PATCH.is_file(), 'RADV Gladius metadata safety patch is missing')
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source = tmp / 'src/amd/vulkan/radv_image.c'
            source.parent.mkdir(parents=True)
            source.write_bytes(subprocess.check_output(['git', '-C', str(ROOT / 'build/sources/mesa'),
                                                      'show', 'HEAD:src/amd/vulkan/radv_image.c']))
            subprocess.run(['git', 'apply', str(PATCH)], cwd=tmp, check=True)
            test = tmp / 'test.c'
            test.write_text('''#define CHIP_GLADIUS 42
#include "src/amd/vulkan/radv_orbis_metadata.h"
#include <assert.h>
int main(void) {
    unsetenv("ORBIS_FAST_CLEAR");
    assert(!radv_orbis_color_metadata_enabled(CHIP_GLADIUS));
    assert(radv_orbis_color_metadata_enabled(7));
    setenv("ORBIS_CMASK", "1", 1);
    assert(!radv_orbis_color_metadata_enabled(CHIP_GLADIUS));
    setenv("ORBIS_FAST_CLEAR", "1", 1);
    assert(radv_orbis_color_metadata_enabled(CHIP_GLADIUS));
    setenv("ORBIS_FAST_CLEAR", "10", 1);
    assert(!radv_orbis_color_metadata_enabled(CHIP_GLADIUS));
    setenv("ORBIS_FAST_CLEAR", "0", 1);
    assert(!radv_orbis_color_metadata_enabled(CHIP_GLADIUS));
    return 0;
}
''')
            subprocess.run(['cc', '-std=gnu11', '-Wall', '-Wextra', '-Werror', str(test), '-o', str(tmp / 'test')], check=True)
            subprocess.run([str(tmp / 'test')], check=True)
            patched = source.read_text()
            self.assertIn('#include "radv_orbis_metadata.h"', patched)
            # A generic compression override must not bypass the console's safety rule.
            for name in ('radv_image_use_fast_clear_for_image_early', 'radv_image_use_fast_clear_for_image'):
                start = patched.index(name + '(')
                body = patched[start:patched.index('\n}', start)]
                self.assertLess(body.index('radv_orbis_color_metadata_enabled'), body.index('RADV_DEBUG_FORCE_COMPRESS'))


if __name__ == '__main__':
    unittest.main()
