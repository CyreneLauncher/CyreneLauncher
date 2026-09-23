# Orbis Java application

Build 2.06 is the current Java 17 runtime probe candidate. It retains the
existing POSIX condition-variable clock selection and reports the Orbis clock
attribute lookup and setup result over UART during JVM startup, allowing timed
wait behavior to be measured. Orbis timed ParkEvents now poll the atomic unpark
token against the monotonic clock with short `sceKernelUsleep` slices, avoiding
the target's non-expiring `pthread_cond_timedwait` behavior while keeping
interrupt/unpark response bounded. Candidate 2.01 added one-shot UART markers
around the first timed ParkEvent's sleep and clock calls to identify any
remaining Orbis syscall boundary. Candidate 2.02 added one-shot markers through
`JVM_Sleep`, `JavaThread::sleep`, the VM transition, and the event park call so
the current sleep stall could be located before changing behavior again.
Candidate 2.05 uses the Orbis kernel's monotonic clock ID (4). The toolchain
header defines the Linux ID (1), which selects FreeBSD's virtual CPU clock
on the console and caused 120 ms waits to take minutes. It also restores the
race-safe ParkEvent transition and checks monotonic time against wall time in
the timing probe.
Candidate 2.06 decodes the native 120-byte Orbis `stat` ABI explicitly. The
JDK build headers describe a different 128-byte layout, which shifted file
size, ownership, and timestamp fields and made path-based `Files.size()` read
the wrong bytes.
The timing probe validates both `Thread.sleep`
and a timed `Thread.join`, and logs their boundaries. It adds a final-ELF
native-boundary audit, wraps the unsupported libc `_exit` route, and includes
focused native-allocation pressure and fatal-termination modes. A failed HotSpot
native allocation records `errno` and a direct `mmap` control result. The Orbis
heap-expansion hook uses bounded 1 MiB arenas instead of libc's exponentially
growing mappings. It writes bounded
records to `/data/orbis-jdk/logs/orbis-java.log` and rotates the active file to
`.1` at 256 KiB. UART receives lifecycle and fatal diagnostics; detailed probe
records stay in the file log.

The ZIP filesystem probe records its operation boundaries in that file so a
target failure can be assigned to archive creation, entry I/O, close, or cleanup.

Native PRX loader traces are written separately to
`/data/orbis-jdk/logs/orbis-native.log`, also capped and rotated at 256 KiB.

The probe covers VM properties, argument forwarding, class and resource lookup,
reflection, file round trips, directory enumeration, threads, executor tasks,
JAR/ZIP entry access, moderate allocation, SHA-256, a 30-second endurance loop,
and a loopback socket. It also exercises `java.util.logging`, XML parsing, the
HTTP client, JDBC and XA APIs, JNDI names, GSS and SASL providers, XML
signature providers, rowsets, scripting APIs, RMI identifiers, extended
charsets, locale data, random generators, `sun.misc.Unsafe` availability, and
the ZIP filesystem provider. The expanded headless checks cover timed
synchronization, filesystem state transitions, 100 repeated loopback socket
exchanges, stable platform properties, the compiler API, the lightweight HTTP
server, DNS and RMI naming providers, extended
network and mapped-file APIs, JDK authentication and GSS helpers, DOM CSS, and
Dynalink. Each check is marked `PASS`, `SKIP`, or `FAIL`;
`probe.summary` contains the totals.

The package contains native SELF modules for the JVM startup chain: `jvm`,
`java`, `net`, `nio`, `zip`, `verify`, and `jimage`. The launcher loads
`/app0/sce_module/libjvm.prx`; the other converted modules retain their JDK
`.so` filenames. Package validation rejects raw ELF libraries.

The default runtime image contains 28 modules: the six core modules plus
`java.naming,java.security.jgss,java.security.sasl,java.sql.rowset,java.xml.crypto,java.scripting,java.rmi,jdk.charsets,jdk.localedata,jdk.random,jdk.unsupported,jdk.zipfs,java.compiler,jdk.httpserver,jdk.naming.dns,jdk.naming.rmi,jdk.net,jdk.nio.mapmode,jdk.security.auth,jdk.security.jgss,jdk.xml.dom,jdk.dynalink`.
Desktop/graphics, process/attach/instrumentation, management/JFR,
compiler implementations/tools, debugger/agent, and incubator modules remain
outside this headless package. Set `RUNTIME_MODULES` to a comma-separated module list when
an application needs a different set; each selected module must exist in the
selected JDK image.
Process creation and asynchronous channels remain unsupported by the current
Orbis port.

The default `JDK_IMAGE` points at the JDK image built from the sibling
`jdk17u` checkout. Override it when using a different build:

```sh
make JDK_IMAGE=/path/to/jdk package
```

The package is written to `build/output/`.

The launcher uses `demo.Main` by default. Select another class with
`--main=package.Class`; dots are converted to JVM slash notation and the
control argument is removed before Java receives its arguments. Add
`--no-park` to let the process exit normally after `DestroyJavaVM`; without it,
the debug parking loop keeps the process available for inspection.
Use `--allocator-probe` to allocate, verify, and release 48 MiB through the
process libc allocator before starting the JVM. Use `--fatal-allocation-probe`
to verify that a primary allocation failure terminates without a secondary
`SIGSYS`.
