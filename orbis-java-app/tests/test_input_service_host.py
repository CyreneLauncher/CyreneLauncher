#!/usr/bin/env python3
"""Builds orbis_input_service.cpp for the host with the SDK's keyboard headers and fake firmware calls,
and replays scripted sceKeyboardRead batches: same-timestamp records must all apply."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
toolchain = Path(os.environ.get('TOOLCHAIN', '/home/wonder/PS4Toolchain'))
with tempfile.TemporaryDirectory() as directory:
    work = Path(directory)
    include = work / 'include/orbis'
    (include / '_types').mkdir(parents=True)
    for name in ('Keyboard.h', 'Sysmodule.h', 'UserService.h'):
        shutil.copy(toolchain / 'include/orbis' / name, include / name)
    for name in ('keyboard.h', 'errors.h', 'sysmodule.h', 'user.h'):
        shutil.copy(toolchain / 'include/orbis/_types' / name, include / '_types' / name)
    (include / 'libkernel.h').write_text(
        '#pragma once\n#include <stdint.h>\n'
        '#ifdef __cplusplus\nextern "C" {\n#endif\n'
        'int sceKernelDebugOutText(int, const char *);\nint sceKernelUsleep(unsigned int);\n'
        'void *scePthreadSelf(void);\nint scePthreadSetprio(void *, int);\n'
        '#ifdef __cplusplus\n}\n#endif\n')
    source = Path(os.environ.get('INPUT_SERVICE_SOURCE', root / 'tools/orbis_input_service.cpp'))
    binary = work / 'input_service_host_test'
    subprocess.run(['gcc', '-O1', '-c', str(root / 'tests/input_service_host_fakes.c'), '-o', str(work / 'fakes.o')],
                   check=True)
    subprocess.run(['g++', '-std=c++17', '-O1', '-pthread', '-I', str(work / 'include'), '-I', str(root / 'tools'),
                    str(source), str(root / 'tests/input_service_host_test.cpp'), str(work / 'fakes.o'),
                    '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
