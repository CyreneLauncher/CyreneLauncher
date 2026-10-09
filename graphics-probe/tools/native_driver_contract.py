"""Verify native AMD graphics from the configured build and linked ELF."""
import json
import subprocess
from pathlib import Path


def verify(elf, mesa_build):
    options = json.loads((Path(mesa_build) / 'meson-info/intro-buildoptions.json').read_text())
    options = {entry['name']: entry['value'] for entry in options}
    if options.get('gallium-drivers') != ['radeonsi'] or options.get('vulkan-drivers') != ['amd']:
        raise ValueError('native graphics requires radeonsi only for OpenGL and RADV for Vulkan')
    output = subprocess.check_output(['llvm-nm', '--defined-only', str(elf)], text=True)
    symbols = {line.split()[-1] for line in output.splitlines() if line.split()}
    if any(name.startswith('zink_') or name == 'zink_driver_descriptor' for name in symbols):
        raise ValueError('Zink driver symbols are present in the linked graphics platform')
    required = {'radeonsi_driver_descriptor', 'radv_GetInstanceProcAddr', 'vkGetInstanceProcAddr'}
    if not required <= symbols:
        raise ValueError('missing native AMD driver symbols: ' + ', '.join(sorted(required - symbols)))
    return {'opengl': 'radeonsi', 'vulkan': 'radv', 'zink_compiled': False}
