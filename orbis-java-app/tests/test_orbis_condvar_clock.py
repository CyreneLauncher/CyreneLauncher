from pathlib import Path


source = Path("../src/hotspot/os/posix/os_posix.cpp").read_text()
start = source.index("void os::Posix::init(void)")
end = source.index("void os::Posix::init_2(void)", start)
init = source[start:end]

assert "orbis-jvm: condattr_setclock symbol=%p" in init
assert "orbis-jvm: condattr_setclock result=%d" in init
assert "orbis-jvm: condattr_setclock result=0 clock=MONOTONIC" in init
assert "orbis-jvm: condattr_setclock unavailable; default clock" in init
assert "#if !defined(__NetBSD__)" in init
assert "_pthread_condattr_setclock(_condAttr, hotspot_monotonic_clock)" in init
print("Orbis condition-variable clock diagnostics contract: PASS")
