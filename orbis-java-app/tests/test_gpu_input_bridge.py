#!/usr/bin/env python3
"""GPU input bridge contract: registered natives match runner.gpu.NativeInput exactly,
the launcher registers them only in GPU mode, and the packet layout is shared."""
from pathlib import Path
import os
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
javap = Path(os.environ.get('BOOT_JDK', '/usr/lib/jvm/java-17-openjdk-amd64')) / 'bin'
bridge = (root / 'tools/orbis_gpu_input_bridge.cpp').read_text()
registered = dict(re.findall(r'\{const_cast<char \*>\("(\w+)"\), const_cast<char \*>\("([^"]+)"\)', bridge))
assert re.search(r'RegisterNatives\(bridge, methods, %d\)' % len(registered), bridge), 'method count mismatch'

with tempfile.TemporaryDirectory() as directory:
    subprocess.run([str(javap / 'javac'), '--release', '17', '-d', directory,
                    str(root / 'src/runner/gpu/NativeInput.java')], check=True)
    listing = subprocess.run([str(javap / 'javap'), '-s', '-p', '-cp', directory, 'runner.gpu.NativeInput'],
                             check=True, text=True, stdout=subprocess.PIPE).stdout
natives = {}
lines = listing.splitlines()
for index, line in enumerate(lines):
    if ' native ' in line:
        name = re.search(r'(\w+)\(', line).group(1)
        natives[name] = lines[index + 1].split('descriptor:')[1].strip()
assert natives == registered, f'Java natives {natives} != registered {registered}'

launcher = (root / 'launcher.c').read_text()
assert 'runner_display_gpu() && orbis_gpu_input_bridge_register(env) < 0' in launcher
desktop = (root / 'tools/orbis_desktop_bridge.cpp').read_text()
assert 'orbis_input_pack_event(&event, words);' in desktop and 'orbis_input_pack_event(&event, words);' in bridge
service = (root / 'tools/orbis_input_service.cpp').read_text()
assert 'ORBIS_KEYBOARD_KEY_F12 && desktop_enabled && focus_toggle_enabled' in service
assert 'orbis_input_set_focus_toggle_enabled(0)' in bridge and 'orbis_display_start' not in bridge
java = (root / 'src/runner/gpu/NativeInput.java').read_text()
packet = (root / 'tools/orbis_input_packet.h').read_text()
assert 'ORBIS_INPUT_PACKET_WORDS 24' in packet and 'EVENT_WORDS = 24' in java
print('GPU input bridge contract passed: ' + ', '.join(sorted(natives)))
