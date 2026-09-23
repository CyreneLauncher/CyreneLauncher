#!/usr/bin/env python3
"""Ensure Orbis signal diagnostics describe the final handling decision."""

from pathlib import Path


source = Path("../src/hotspot/os/posix/signals_posix.cpp").read_text()

diagnostic = 'orbis-jvm: signal handler sig=%d code=%d pc=%p addr=%p handled=%d'
handler = 'signal_was_handled = PosixSignals::pd_hotspot_signal_handler'

assert diagnostic in source, "signal diagnostic must report handled state"
assert source.index(diagnostic) > source.index(handler), (
    "signal diagnostic must be emitted after HotSpot signal handling"
)

print("Orbis signal diagnostic contract passed")
