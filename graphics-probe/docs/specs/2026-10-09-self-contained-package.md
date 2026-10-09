# Self-contained launcher package

Approved outcome: one PKG contains the runner, JDK 17/21/25 and every provided
runtime/graphics/launcher library. Downloads and user state remain under
/data/orbis-jdk. Preserve working exports, native bundles and user data.

Installed layout: /app0/eboot.bin, /app0/app.jar, /app0/runtimes/jdk{17,21,25},
with graphics, desktop support and launcher Java dependencies under each JDK's
lib/orbis; /app0/manifests/launcher.json and runtime-package.json; immutable defaults
under /app0/defaults. Use existing audited exports and native bundles without
rebuilding Mesa/JDK. Materialize all files; the installed package has no symlinks.

The launcher boots independently of Minecraft, using a minimal metadata profile
with the existing exact LWJGL 3.2.2 module/hash gate and bundled Gson. Fresh installs
show an unconfigured instance and require installing/selecting a game before Play.
Existing game profiles/downloads continue using writable game libraries/assets.
JDK discovery supports direct installed homes, retaining explicit legacy export
discovery for development. A packaged runner maps recognized legacy JDK selections
to installed homes and binds provided graphics/desktop libraries to that JDK.

Initialize missing mutable runner settings from packaged defaults exclusively;
never overwrite existing settings or delete user content. Launch transactions
switch Java and its provided-library roots together and restore them together.

Assembly uses an explicit hash manifest of verified export/bundle files and their
provenance. Omit build-info, audit logs, object/archive/debug build outputs, probes
and host sources from runtime payloads. Keep notices/licenses and runtime data.
Stage into a dedicated clean release directory; do not reuse development staging.

Verify on host: source hashes and package inventory, tamper/extra-file rejection,
fresh defaults, installed JDK discovery, bundled launcher classpath/profile gate,
game transaction/recovery and host runner checks. Build and validate a local PKG.
Console installation/GL/Vulkan acceptance remains a user check; preserve old
runtime copies and recovery bundles until that succeeds. No automatic deletion,
Mesa/JDK rebuild or console deployment is part of this change.
