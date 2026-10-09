"""Compile and exercise the actual provider's chain construction on the PC."""
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

class ChainFence(unittest.TestCase):
    def test_construction(self):
        mesa = ROOT / 'build/sources/mesa'
        relative = 'src/amd/common/ac_orbis_drm.c'
        baseline = subprocess.check_output(['git', '-C', str(mesa), 'show', 'HEAD:' + relative], text=True)
        with tempfile.TemporaryDirectory() as directory:
            copied = Path(directory) / relative
            copied.parent.mkdir(parents=True)
            copied.write_text(baseline)
            for patch in sorted((ROOT / 'patches/mesa').glob('*.patch')):
                subprocess.run(['git', 'apply', '--include=' + relative, str(patch)],
                               cwd=directory, check=True, capture_output=True)
            source = copied.read_text()
        start = source.index('/* Chain-to-fence construction,')
        end = source.index('/* End chain-to-fence construction. */', start)
        prelude = r"""
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#define ARRAY_SIZE(a) (sizeof(a)/sizeof((a)[0]))
#define ORBIS_CHAIN_MAX_DEPTH 4096
#define ORBIS_PM4_TYPE3(op, body) ((3u << 30) | ((((body)-1)&0x3fffu)<<16) | ((uint32_t)(op)<<8))
static uint64_t orbis_va_base, orbis_va_end;
"""
        def function(name, next_name):
            start = source.index('static uint32_t *\n' + name)
            end = source.index('\n}', start) + 2
            return source[start:end] + '\n'
        emit = function('orbis_emit_eop', None)
        pad = function('orbis_pad_ib', None)
        boot_start = source.index('static void\norbis_selftest_chain(void)')
        boot_end = source.index('\n}', boot_start) + 2
        boot = source[boot_start:boot_end]
        prelude += r"""
#include <stdlib.h>
#include <stdio.h>
#define ORBIS_FENCE_CB_BYTES (2u*1024u*1024u)
#define ORBIS_FENCE_CB_PAD_DW 8
#define ORBIS_IT_EVENT_WRITE_EOP 0x47
#define ORBIS_EV_CACHE_FLUSH_AND_INV_TS 20
#define ORBIS_EI_EOP 5
#define ORBIS_EOP_TCL1_ACTION_EN (1u << 16)
#define ORBIS_EOP_TC_ACTION_EN (1u << 17)
#define ORBIS_EOP_DST_SEL_MC (0u << 16)
#define ORBIS_EOP_DATA_SEL_32BIT (1u << 29)
#define ORBIS_EOP_INT_SEL 2
#define ORBIS_EOP_INT_SEL_SHIFT 24
#define mesa_logi(...) do { printf(__VA_ARGS__); putchar('\n'); } while (0)
#define mesa_loge(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
"""
        with tempfile.TemporaryDirectory() as directory:
            c = Path(directory) / 'chain.c'
            exe = Path(directory) / 'chain'
            c.write_text(prelude + emit + pad + source[start:end] + boot + (ROOT / 'tests/orbis_chain_fence.c').read_text())
            subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror',
                            '-fsanitize=address,undefined', str(c), '-o', str(exe)], check=True)
            for case in ('large', 'padding', 'padding-long', 'truncated-nop', 'cycle', 'bounds', 'slot', 'short', 'zero', 'overlap', 'payload', 'depth', 'boot'):
                with self.subTest(case=case):
                    result = subprocess.run([str(exe), case], capture_output=True, text=True)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    if case == 'boot':
                        self.assertIn('CHAIN FENCE SELF-TEST OK', result.stdout)
                        self.assertEqual(result.stderr, '')

if __name__ == '__main__':
    unittest.main()
