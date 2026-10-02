#!/usr/bin/env python3
"""Check that the opt-in late sampler brackets the former Paper failure."""

from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / "launcher.c").read_text()
readme = (root / "tools/runner-files-README.txt").read_text()

main_call = source.index('env->CallStaticVoidMethod(main_class, main_method, java_args);')
sampler = source.index("run_late_metaspace_probe(", main_call)
destroy = source.index('vm->DestroyJavaVM();', main_call)
assert main_call < sampler < destroy
assert "metaspace-probe.txt" in source
assert "METASPACE_PROBE_SAMPLES 9" in source
assert "METASPACE_PROBE_INTERVAL_US 30000000" in source
probe_impl = source[source.index("static void run_late_metaspace_probe(void) {"):main_call]
assert "orbis_mapping_probe_hotspot_reservation" in probe_impl
assert "orbis_mapping_probe_variant" in probe_impl
assert "variant_sizes[]" in probe_impl
assert "hint_sizes[]" in probe_impl
assert "address_hints[]" in probe_impl
assert "requested_hint=0x%llx returned_address=0x%llx" in probe_impl
assert "PROT_NONE, PROT_READ | PROT_WRITE" in probe_impl
assert 'protection_names[] = {"none", "read-write"}' in probe_impl
assert "sceKernelUsleep(METASPACE_PROBE_INTERVAL_US)" in probe_impl
assert "metaspace-probe.txt" in readme
print("Late metaspace probe source test passed")
