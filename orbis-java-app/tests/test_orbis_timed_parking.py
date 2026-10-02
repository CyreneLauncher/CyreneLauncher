from pathlib import Path

source = (Path(__file__).resolve().parents[2] /
          "src/hotspot/os/posix/os_posix.cpp").read_text()
start = source.index("int os::PlatformEvent::park(jlong millis)")
end = source.index("void os::PlatformEvent::unpark()", start)
park = source[start:end]
transition = park[:park.index("if (v == 0)")]
timed_orbis = park.index("#if defined(ORBIS)")
timed_else = park.index("#else", timed_orbis)
orbis = park[timed_orbis:timed_else]
for token in ("sceKernelUsleep", "os::javaTimeNanos()",
              "Atomic::load(&_event)", "Atomic::xchg(&_event, 0)"):
    assert token in orbis, f"Orbis timed ParkEvent path missing {token}"
for token in ("orbis-jvm: timed park entry",
              "orbis-jvm: timed park before start clock",
              "orbis-jvm: timed park after start clock",
              "orbis-jvm: timed park before usleep",
              "orbis-jvm: timed park after usleep",
              "orbis-jvm: timed park after clock"):
    assert token in orbis, f"missing timed ParkEvent diagnostic: {token}"
assert "pthread_cond_timedwait(" not in orbis, \
    "Orbis timed ParkEvent path must not use the non-expiring timed wait"
assert "for (;;)" in transition, \
    "Orbis event transition must retain the race-safe retry"
assert "Atomic::cmpxchg(&_event, v, v - 1)" in transition, \
    "Orbis event transition must retain the original compare-exchange"
assert "Atomic::xchg(&_event, -1)" not in transition, \
    "Orbis event transition must not overwrite a concurrent unpark"
assert "pthread_cond_timedwait" in park[timed_else:], \
    "non-Orbis POSIX timed ParkEvent path must remain unchanged"

jvm = (Path(__file__).resolve().parents[2] /
       "src/hotspot/share/prims/jvm.cpp").read_text()
sleep_start = jvm.index("JVM_ENTRY(void, JVM_Sleep")
sleep_end = jvm.index("JVM_END", sleep_start)
sleep = jvm[sleep_start:sleep_end]
for token in ("orbis-jvm: JVM_Sleep enter",
              "orbis-jvm: JVM_Sleep before JavaThread::sleep",
              "orbis-jvm: JVM_Sleep after JavaThread::sleep"):
    assert token in sleep, f"missing JVM_Sleep diagnostic: {token}"

thread = (Path(__file__).resolve().parents[2] /
          "src/hotspot/share/runtime/thread.cpp").read_text()
thread_start = thread.index("bool JavaThread::sleep(jlong millis)")
thread_end = thread.index("// ======= Threads ========", thread_start)
thread_sleep = thread[thread_start:thread_end]
for token in ("orbis-jvm: JavaThread::sleep enter",
              "orbis-jvm: JavaThread::sleep before park",
              "orbis-jvm: JavaThread::sleep after park"):
    assert token in thread_sleep, f"missing JavaThread::sleep diagnostic: {token}"
print("Orbis timed ParkEvent source contract passed")
