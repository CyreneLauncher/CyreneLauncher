#!/usr/bin/env python3
from pathlib import Path


source = Path("src/demo/Main.java").read_text(encoding="utf-8")

required_checks = (
    "probe.timing",
    "probe.filesystemStress",
    "probe.networkStress",
    "probe.platform",
    "timingProbe",
    "filesystemStressProbe",
    "networkStressProbe",
    "platformProbe",
)

for marker in required_checks:
    assert marker in source, f"missing exhaustive probe marker: {marker}"

timing = source.split("private static void timingProbe", 1)[1].split(
    "private static void filesystemStressProbe", 1
)[0]
assert "CountDownLatch" not in timing, "timing probe must avoid AQS latch waits"
assert "sleeper.join" in timing, "timing probe must use the working join path"
for marker in (
    "timing.construct-worker",
    "timing.worker-entered",
    "timing.worker-before-sleep",
    "timing.worker-after-sleep",
    "timing.before-start",
    "timing.after-start",
    "timing.before-join",
    "timing.after-join",
    "timedJoin.before-bounded-join",
    "timedJoin.after-bounded-join",
):
    assert marker in timing, f"missing timing boundary log: {marker}"

check = source.split("void check", 1)[1].split("void summary", 1)[0]
assert 'line("INFO", name, "BEGIN")' in check, (
    "probe checks must log their entry before a native hang"
)

print("Exhaustive probe source contract passed")
