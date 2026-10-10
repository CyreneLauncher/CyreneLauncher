#!/usr/bin/env python3
"""lwjgl/sdl/orbis_sdl_gamepad.c on the host, against an official libSDL3 of the pinned release (Controlify
bundles linux-x86-64/libSDL3.so; ORBIS_SDL3_LIBRARY points at it) and the pinned SDL headers
(build/sdl3/sources after tools/build_sdl3.py)."""
import os
import subprocess
import sys
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
library = os.environ.get('ORBIS_SDL3_LIBRARY')
headers = next((root / 'build/sdl3/sources').glob('SDL3-*/include'), None)
if not library or headers is None:
    print('SDL gamepad test skipped: set ORBIS_SDL3_LIBRARY and run tools/build_sdl3.py first')
    sys.exit(0)
with tempfile.TemporaryDirectory() as directory:
    tmp = Path(directory)
    (tmp / 'libSDL3.so.0').symlink_to(Path(library).resolve())
    (tmp / 'libSDL3.so').symlink_to(Path(library).resolve())
    test = tmp / 'test'
    subprocess.run(['clang', '-std=gnu11', '-O1', '-Wall', '-Werror', '-I', headers,
                    root.parents[1] / 'sdl-orbis/orbis_sdl_gamepad.c', root / 'tests/sdl/orbis_sdl_gamepad_test.c',
                    '-L', tmp, '-lSDL3', f'-Wl,-rpath,{tmp}', '-o', test], check=True)
    env = dict(os.environ, SDL_VIDEO_DRIVER='dummy', SDL_AUDIO_DRIVER='dummy', SDL_JOYSTICK_HIDAPI='0')
    print(subprocess.run([test], check=True, env=env, stdout=subprocess.PIPE, text=True).stdout.strip())
