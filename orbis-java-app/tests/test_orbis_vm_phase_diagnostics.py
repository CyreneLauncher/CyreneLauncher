#!/usr/bin/env python3
"""Ensure the VM creation path exposes high-level phase boundaries on Orbis."""

from pathlib import Path


source = Path("../src/hotspot/share/runtime/thread.cpp").read_text()
vm_source = Path("../src/java.base/share/classes/jdk/internal/misc/VM.java").read_text()
system_source = Path("../src/java.base/share/classes/java/lang/System.java").read_text()
module_source = Path("../src/java.base/share/classes/jdk/internal/module/ModuleBootstrap.java").read_text()
native_source = Path("../src/java.base/share/native/libjava/VM.c").read_text()
finder_source = Path("../src/java.base/share/classes/jdk/internal/module/SystemModuleFinders.java").read_text()
for marker in (
    "orbis-jvm: vm early vm_version entered",
    "orbis-jvm: vm early vm_version returned",
    "orbis-jvm: vm early tls returned",
    "orbis-jvm: vm early ostream returned",
    "orbis-jvm: vm early os_init returned",
    "orbis-jvm: vm early system_properties returned",
    "orbis-jvm: vm early version_properties returned",
    "orbis-jvm: vm early log_configuration returned",
    "orbis-jvm: vm early arguments_parse returned",
    "orbis-jvm: vm early memtracker returned",
    "orbis-jvm: vm early os_before_ergo returned",
    "orbis-jvm: vm early ergonomics returned",
    "orbis-jvm: vm early os_init_2 returned",
    "orbis-jvm: vm early safepoint returned",
    "orbis-jvm: vm early adjust_after_os returned",
    "orbis-jvm: vm early ostream_log returned",
    "orbis-jvm: vm early agents returned",
    "orbis-jvm: vm phase vm_init_globals entered",
    "orbis-jvm: vm phase vm_init_globals returned",
    "orbis-jvm: vm phase init_globals entered",
    "orbis-jvm: vm phase init_globals returned",
    "orbis-jvm: vm phase initialize_java_lang_classes entered",
    "orbis-jvm: vm phase initialize_java_lang_classes returned",
    "orbis-jvm: vm phase set_init_completed",
    "orbis-jvm: vm phase post_basic_logging returned",
    "orbis-jvm: vm phase service_threads returned",
    "orbis-jvm: vm phase compiler_phase1 returned",
    "orbis-jvm: vm phase compiler_phase2 returned",
    "orbis-jvm: vm phase jsr292 returned",
    "orbis-jvm: vm phase module_phase2 returned",
    "orbis-jvm: vm phase module_phase3 returned",
    "orbis-jvm: vm phase java_loaders returned",
    "orbis-jvm: vm phase management returned",
    "orbis-jvm: vm phase create_vm complete",
):
    assert marker in source, f"missing VM phase marker: {marker}"

print("Orbis VM phase diagnostics contract passed")

for marker in (
    "orbisStartupProbePoint",
    "initPhase2 entered",
    "ModuleBootstrap.boot returned",
    "ModuleBootstrap command line options ready",
    "ModuleBootstrap archived graph checked",
    "ModuleBootstrap systemModules lookup entered",
    "ModuleBootstrap systemModules lookup returned",
    "ModuleBootstrap allSystemModules lookup entered",
    "ModuleBootstrap allSystemModules lookup returned",
    "ModuleBootstrap system finder construction entered",
    "ModuleBootstrap system finder construction returned",
):
    assert marker in vm_source + system_source + module_source
assert "Java_jdk_internal_misc_VM_orbisStartupProbe" in native_source
print("Orbis module phase diagnostics contract passed")
for marker in (
    "SystemModuleFinders descriptors entered",
    "SystemModuleFinders descriptors returned",
    "SystemModuleFinders targets returned",
    "SystemModuleFinders hashes returned",
    "SystemModuleFinders resolutions returned",
    "SystemModuleFinders hash map returned",
    "SystemModuleFinders module references returned",
    "SystemModuleFinders image fallback entered",
    "SystemModuleFinders image fallback returned",
):
    assert marker in finder_source
print("Orbis system-module finder diagnostics contract passed")
