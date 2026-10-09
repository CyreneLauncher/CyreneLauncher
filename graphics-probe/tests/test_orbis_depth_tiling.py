"""Exercise the actual gfx7 depth descriptor builder with both driver policies."""
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MESA = ROOT / 'build/sources/mesa'
GENERATED = ROOT / 'build/vulkan-mesa/mesa'


class OrbisDepthTiling(unittest.TestCase):
    def test_radv_depth_uses_standard_layout(self):
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            relatives = ('src/amd/common/ac_descriptors.c', 'src/amd/common/ac_descriptors.h')
            for relative in relatives:
                copied = tmp / relative
                copied.parent.mkdir(parents=True, exist_ok=True)
                copied.write_bytes(subprocess.check_output(['git', '-C', str(MESA), 'show', 'HEAD:' + relative]))
            for patch in sorted((ROOT / 'patches/mesa').glob('*.patch')):
                subprocess.run(['git', 'apply', *(('--include=' + r) for r in relatives), str(patch)],
                               cwd=tmp, check=True, capture_output=True)
            source = (tmp / relatives[0]).read_text()
            def function(name):
                # Compile the exact source body with its actual structs and register definitions.
                start = source.index('\n' + name + '(')
                start = source.rfind('\n', 0, start) + 1
                end = source.index('\n}', start) + 2
                return source[start:end] + '\n'
            c = tmp / 'depth.c'
            c.write_text('''#include "ac_descriptors.h"
#include "amdgfxregs.h"
#include "util/u_math.h"
#include <assert.h>
static bool alternate = true;
bool ac_orbis_alt_tiling(void) { return alternate; }
''' + function('ac_tile_mode_index') + function('ac_init_gfx6_ds_surface') + '''
int main(void) {
    struct radeon_info info = {.family = CHIP_GLADIUS, .gfx_level = GFX7};
    struct radeon_surf surf = {0};
    surf.u.legacy.level[0].nblk_x = 2048;
    surf.u.legacy.level[0].nblk_y = 1024;
    /* Standard pipe 8, bank shape 1/1/1; alternate pipe 18, shape 2/2/2. */
    info.si_tile_mode_array[0] = S_009910_PIPE_CONFIG(8) | (18u << 27);
    info.cik_macrotile_mode_array[0] = S_009990_BANK_HEIGHT(1) |
        S_009990_MACRO_TILE_ASPECT(1) | S_009990_NUM_BANKS(1) |
        (2u << 8) | (2u << 10) | (2u << 12);
    const struct ac_ds_state radv = {.surf = &surf, .num_samples = 1};
    struct ac_ds_surface ds = {0};
    ac_init_gfx6_ds_surface(&info, &radv, 0, 0, &ds);
    assert(G_02803C_PIPE_CONFIG(ds.u.gfx6.db_depth_info) == 8);
    assert(G_02803C_BANK_HEIGHT(ds.u.gfx6.db_depth_info) == 1);
    assert(G_02803C_MACRO_TILE_ASPECT(ds.u.gfx6.db_depth_info) == 1);
    assert(G_02803C_NUM_BANKS(ds.u.gfx6.db_depth_info) == 1);
    struct ac_ds_state radeonsi = radv;
    radeonsi.orbis_alt_depth = true;
    radeonsi.htile_enabled = true;
    memset(&ds, 0, sizeof(ds));
    ac_init_gfx6_ds_surface(&info, &radeonsi, 0, 0, &ds);
    assert(G_02803C_PIPE_CONFIG(ds.u.gfx6.db_depth_info) == 18);
    assert(G_02803C_BANK_HEIGHT(ds.u.gfx6.db_depth_info) == 2);
    assert(G_02803C_MACRO_TILE_ASPECT(ds.u.gfx6.db_depth_info) == 2);
    assert(G_02803C_NUM_BANKS(ds.u.gfx6.db_depth_info) == 2);
    assert(G_028040_TILE_SURFACE_ENABLE(ds.db_z_info) == 1);
    assert(G_028ABC_FULL_CACHE(ds.u.gfx6.db_htile_surface) == 1);
    /* Driver switching inside one process must not leak the alternate policy. */
    memset(&ds, 0, sizeof(ds));
    ac_init_gfx6_ds_surface(&info, &radv, 0, 0, &ds);
    assert(G_02803C_PIPE_CONFIG(ds.u.gfx6.db_depth_info) == 8);
    alternate = false;
    memset(&ds, 0, sizeof(ds));
    ac_init_gfx6_ds_surface(&info, &radeonsi, 0, 0, &ds);
    assert(G_02803C_PIPE_CONFIG(ds.u.gfx6.db_depth_info) == 8);
    alternate = true;
    info.family = CHIP_BONAIRE;
    memset(&ds, 0, sizeof(ds));
    ac_init_gfx6_ds_surface(&info, &radeonsi, 0, 0, &ds);
    assert(G_02803C_PIPE_CONFIG(ds.u.gfx6.db_depth_info) == 8);
    return 0;
}
''')
            executable = tmp / 'depth'
            includes = [tmp / 'src/amd/common', GENERATED / 'src/amd/common',
                        MESA / 'src/amd/common', GENERATED / 'src', MESA / 'src', MESA / 'include']
            subprocess.run(['cc', '-std=gnu11', '-Wall', '-Wextra', '-Werror',
                            '-DHAVE_ORBIS_PLATFORM', '-DHAVE_ENDIAN_H',
                            '-DHAVE_FUNC_ATTRIBUTE_PACKED', '-DHAVE_FUNC_ATTRIBUTE_CONST',
                            '-DHAVE_FUNC_ATTRIBUTE_PURE', '-DHAVE_FUNC_ATTRIBUTE_FORMAT',
                            '-DHAVE_FUNC_ATTRIBUTE_UNUSED', '-DHAVE___BUILTIN_CLZ',
                            *(arg for inc in includes for arg in ('-I', str(inc))),
                            '-fsanitize=address,undefined', str(c), '-o', str(executable)], check=True)
            result = subprocess.run([str(executable)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
