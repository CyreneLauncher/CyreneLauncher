#!/usr/bin/env python3
"""Bind crtlib's constructor boundaries to the JVM's actual init array."""
from pathlib import Path
import sys

source, output = map(Path, sys.argv[1:])
script = source.read_text()
old = '.init_array : {\n\t\t*(.init_array);\n\t}'
assert script.count(old) == 1, 'Unexpected OpenOrbis linker script; inspect init array'
script = script.replace(old, '''.init_array : {
        __init_array_start = .;
        KEEP(*(SORT_BY_INIT_PRIORITY(.init_array.*)))
        KEEP(*(.init_array))
        __init_array_end = .;
    }''')
output.write_text(script)
