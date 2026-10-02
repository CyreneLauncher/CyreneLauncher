#!/usr/bin/env python3
"""Contract tests for reliable one-shot HotSpot mapping trace delivery."""

from pathlib import Path

root = Path(__file__).resolve().parents[1]
launcher = (root / "launcher.c").read_text()
runtime = (root.parent / "src/hotspot/os/orbis/os_orbis.cpp").read_text()
native = (root / "tools/native_dlfcn.c").read_text()
readme = (root / "README.md").read_text()

assert '"/data/orbis-jdk/runner/vmmap-probe.txt"' in runtime
assert '"/data/orbis-jdk/logs/orbis-vmmap.log"' in native
assert 'strncmp(text, "orbis-vmmap ",' in native
assert '"/data/orbis-jdk/logs/orbis-vmmap.log"' in launcher
assert '::unlink(marker_path)' in runtime
assert '"one-shot HotSpot mapping trace armed"' in launcher
assert 'setenv("ORBIS_VM_MAP_TRACE"' not in launcher
assert 'getenv("ORBIS_VM_MAP_TRACE")' not in runtime
assert "orbis-vmmap.log" in readme
assert "size < 64 * 1024" in runtime
assert "success ? 1024 : 256" in runtime
assert "trace-limit-reached" in runtime
assert "size < 1024 * 1024" not in runtime
assert "caller=0x%" in runtime
trace_body = runtime[runtime.index("void orbis_vm_map_trace("):runtime.index("// NOTE: Bsd kernel")]
assert 'success && strncmp(operation, "commit-", 7) == 0' in trace_body
assert 'op=commit-successes-suppressed count=%u' in trace_body
assert "commit_success_events" in trace_body

print("HotSpot mapping trace trigger and dedicated log contract passed")
