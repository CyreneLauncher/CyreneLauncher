# Forge and NeoForge support

## Outcome and constraints

Players can choose Forge or NeoForge when creating an instance, install the official loader, and use Play with the correct runtime, libraries, patched client, and arguments. Mod browsing and Modrinth pack installation respect the selected loader. Existing Vanilla, Fabric, Quilt, account ownership checks, download recovery, and launch rollback remain functional.

The owner requested an investigation of the Orbis JDK, implementation in the launcher and its launch methods, and direct upstream pushes without pull requests. Local build and host verification precede integration. Console compatibility is reported separately from host verification.

## Findings

The packaged runtime is OpenJDK 17.0.20. Inspection of its actual module image confirms java.base, java.logging, jdk.naming.dns, and jdk.unsupported. Its release file lists jdk.zipfs and java.desktop, but does not list java.instrument. Instrumentation-dependent mods cannot be assumed compatible. This feature does not claim support for arbitrary desktop-native mods or Java agents.

The Orbis ProcessImpl port deliberately rejects process creation. However, the official Forge installer invokes processor main methods through an isolated URLClassLoader rather than requiring subprocesses. Implement the corresponding in-process client processor flow; do not invoke desktop installer GUIs or shell scripts.

Research fixtures downloaded from official Maven repositories:

- Forge 1.20.1-47.4.0 installer: BootstrapLauncher entry point, module path, add-modules, add-opens, add-exports, client mapping/splitting/remapping/binary patch processors, and declared output hashes.
- NeoForge 21.1.172 installer for Minecraft 1.21.1: BootstrapLauncher entry point and similar module setup and processors. Output declarations differ, so never require a field merely because the Forge sample contains it.

The native launcher passes runtime options directly to JNI_CreateJavaVM. Java launcher switches such as `-p` cannot simply be copied into that list. Its current 32-option, 512-byte-per-option storage is too small for realistic loader module paths. The game currently starts through reflection on the runner's custom system class loader, while module-path classes belong to the JVM's module loader. Both installation metadata and startup must change.

Sources: [Forge Java requirements](https://github.com/MinecraftForge/MinecraftForge/blob/26.3/docs/README.md), [NeoForge user guide](https://docs.neoforged.net/user/docs/), [Forge processor implementation](https://github.com/MinecraftForge/Installer/blob/2.0/src/main/java/net/minecraftforge/installer/actions/PostProcessors.java).

## Supported scope

Start with Forge for Minecraft 1.18.2 and newer and NeoForge for 1.20.2 and newer, within the launcher's existing Minecraft/LWJGL range. This covers Java 17 and Java 21 releases. Year-numbered Java 25 releases require inspecting their real installer profiles and exercising their launch format before being offered; reject unimplemented formats clearly rather than displaying an unusable install.

Older Forge releases depend on older Java and loader assumptions. Do not advertise them as supported by merely substituting JDK 17. Do not offer NeoForge 1.20.1 initially, consistent with upstream's recommendation to use Forge there.

Determine Java requirements from Mojang metadata and the loader profile. Select a matching supported major runtime for Forge/NeoForge rather than assuming that any higher major is compatible. Preserve existing runtime selection for the other loaders. Processor class versions must also fit the installation JVM. If a selected loader needs a newer installation JVM, use a persisted, credential-free installation continuation and the existing application relaunch mechanism; never attempt ProcessBuilder. Restore runner settings before resuming the launcher.

## Chosen approach

Extend the existing install pipeline with a focused installer-profile adapter and processor executor. Reusing the full official installer would couple the console to desktop launcher layouts and UI; requiring a desktop to prepare every instance would defeat the requested player flow. Reading official profiles retains upstream's patching steps while adapting paths and startup to the console.

### Catalog and installation

Add FORGE and NEOFORGE loader values. Read official Forge promotions/Maven metadata and NeoForge Maven metadata; select builds whose installer-declared Minecraft version matches the chosen release. Prefer recommended/stable builds and validate version identifiers. Exact loader versions declared by modpacks must be honored, not silently replaced with the latest.

Download installers and dependency artifacts through the existing verified, resumable cache. Read bounded installer ZIP entries and JSON. Resolve Maven coordinates including classifiers and extensions; confine all output paths to staging or managed libraries. Support installer data tokens, client-side selection, embedded resources, and manifest-declared processor entry points. Reject unresolved tokens and unsupported schema versions before executing anything.

Download client processor dependencies, execute processors sequentially with isolated dependencies and restored thread context class loaders, and validate declared output hashes. For generated artifacts without upstream hashes, record their actual hashes and sizes for the existing runner's verified artifact resolver. Reuse completed work only when its inputs match and applicable hashes validate. Write the installed record last; failed installation remains retryable and cannot become a launchable instance.

Merge vanilla and loader metadata into flattened game/boot profiles. Keep loader overrides, patched client/resource artifacts, JVM settings, game arguments, and the existing audited Orbis LWJGL substitution. Do not place module-path bootstrap dependencies on the application's class path as duplicate copies. Keep launcher dependencies available for authentication before game startup.

### JVM and game startup

Expand bounded native option storage and option count with explicit overlength rejection. Normalize module-path arguments to the JNI-compatible `--module-path=...` option; normalize supported add-modules, add-opens, and add-exports options into JNI-compatible single-option forms. Validate paths and supported options, and reject profile attempts to replace runner-managed configuration or add agents.

Apply loader JVM settings before JVM creation through the existing preparation transaction. Restore global settings as the launcher consumes its intent. Locate the module-loaded bootstrap main class with its owning module loader; preserve context class loader and service discovery behavior expected by the loader. Inspect the real bootstrap's class-path handling against the custom runner loader and verify it in a separate host JVM before declaring startup compatible.

Construct loader game arguments in memory alongside account/instance arguments. Expand known placeholders, apply supported argument rules, and reject unknown mandatory placeholders. User arguments cannot override loader launch targets or managed identity. Credentials remain absent from persistent launch requests and installer continuations.

Fabric/Quilt-only compatibility mod injection stays limited to those loaders. Loader-specific native or early-window behavior is tested with Orbis graphics substitution; unsupported early display must be disabled using a verified upstream setting or adapted explicitly. Do not claim that a host bootstrap test establishes PS4 rendering compatibility.

### UI and modpacks

Expose both loaders through the existing instance version selector, with progress for processor stages and useful installation errors. Set Modrinth filters to forge or neoforge as appropriate. Parse both dependencies in .mrpack files, retain the requested loader version, and report conflicting loader dependencies. Existing Fabric/Quilt dependency behavior remains intact.

Update player documentation with supported ranges, runtime download behavior, installation duration, mod-loader compatibility, and console test limitations.

## Verification and integration

Use real installer JSON fixtures and failing-first tests for catalogs, coordinate/token resolution, side selection, path confinement, argument normalization, output verification, exact pack versions, and interrupted installation. Exercise actual processors for representative Forge/Java 17 and NeoForge/Java 21 installs on the host; fake processors alone are insufficient.

Launch representative real loader bootstraps in separate host JVMs using the runner's class-loader model and generated module options. Check startup reaches loader discovery without class-loader/module resolution errors; report any missing game/native environment separately. Run existing launcher/account/pack/rollback regression coverage and native runner option tests, then build and validate a local PKG.

Review the final diff and push verified changes directly to main. Provide the new PKG and a PS4 checklist for fresh installation, restart/retry, owned/offline accounts, Forge/NeoForge game launch, and log sharing. Console startup and rendering remain pending until observed on hardware.
