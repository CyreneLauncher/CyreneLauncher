#!/usr/bin/env python3
"""Bind crtlib's constructor boundaries to the JVM's actual init array.

With --prx-tls, also collect -fdata-sections TLS (.tdata.*/.tbss.*) into .tls and
export the image bounds used by orbis_prx_tls.c. JDK modules keep the default script.
"""
import argparse
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('source', type=Path)
parser.add_argument('output', type=Path)
parser.add_argument('--prx-tls', action='store_true')
arguments = parser.parse_args()
script = arguments.source.read_text()
old = '.init_array : {\n\t\t*(.init_array);\n\t}'
assert script.count(old) == 1, 'Unexpected OpenOrbis linker script; inspect init array'
script = script.replace(old, '''.init_array : {
        __init_array_start = .;
        KEEP(*(SORT_BY_INIT_PRIORITY(.init_array.*)))
        KEEP(*(.init_array))
        __init_array_end = .;
    }''')
if arguments.prx_tls:
    tls = '.tls : {\n\t\t*(.tdata);\n\t\t*(.tbss);\n\t}'
    assert script.count(tls) == 1, 'Unexpected OpenOrbis linker script; inspect .tls'
    # Section-relative symbols: absolute ones would be relocated by PIC references.
    script = script.replace(tls, '''.tls : {
        *(.tdata .tdata.*)
        *(.tbss .tbss.*)
    }
    __orbis_tls_image_start = ADDR(.tls);
    __orbis_tls_image_end = ADDR(.tls) + SIZEOF(.tls);''')
arguments.output.write_text(script)
