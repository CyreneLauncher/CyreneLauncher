# Orbis launcher

Minecraft (and ordinary Java applications) on the PS4, as one application independent of the Java
runtimes it runs on.

- `orbis-java-app/`: the PS4 application. The runner eboot, its Java bootstrap (`app.jar`), the
  screen, keyboard, mouse and controller services, and PKG packaging (`make -C orbis-java-app package`).
- `graphics-probe/`: the graphics and game stack the runner loads from the graphics bundle. That
  covers Mesa and the platform PRX, the LWJGL 3.2.2 and 3.3.3 natives with the Orbis GLFW facade,
  OpenAL, the Minecraft launcher (`graphics-probe/launcher`) and its Fabric compatibility mod.

## Java runtimes

The JDK ports are separate sibling projects that only export runtimes: `../jdk17u`,
`../jdk21u` and `../jdk25u`, each with its own `orbis-export/` (`make -C orbis-export jdk-export ...`).
Upload an export's contents to `/data/orbis-jdk/<name>/release-desktop/`.

- JDK 17 (`/data/orbis-jdk/jdk/release-desktop`) is the default runner: `runner.conf`'s
  `java_home` names it, and the launcher always starts on it.
- JDK 21 (`/data/orbis-jdk/jdk21/release-desktop`) is an optional runner. When it is installed,
  the launcher also offers Minecraft 1.20.5 to 1.21.11 (Java 21, LWJGL 3.3.3), and a launch of such a
  version writes its `java_home` for that one start; the game restores `runner.conf` as it starts,
  so the next start is on JDK 17 again.

The launcher recognises a runtime by its `release` file (`JAVA_VERSION`) and `lib/server/libjvm.prx`.
The application talks to a runtime only through the JNI invocation API and the export layout.

Build outputs (`orbis-java-app/build-*`, `graphics-probe/build/`) and notes (`docs/`,
`graphics-probe/results/`) stay out of the repository.
