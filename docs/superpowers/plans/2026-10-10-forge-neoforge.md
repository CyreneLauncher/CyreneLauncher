# Forge and NeoForge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for native execution, or superpowers:subagent-driven-development if the owner chooses delegated implementation. Steps use checkbox syntax for tracking.

**Goal:** Install and launch official Forge and NeoForge instances on the Orbis runner and support their Modrinth packs.

**Architecture:** Extend VersionStore with a loader installer adapter, isolated processor execution, and persisted loader arguments. Prepare module options before native JVM creation and invoke the bootstrap with its owning loader. Reuse ownership checks, verified downloads, runtime installation, and rollback.

**Tech Stack:** Java 17 source, Gson, ZIP/JAR APIs, JNI JVM embedding, C/C++ runner, Python unittest and Java host harnesses.

**Spec:** `docs/superpowers/specs/2026-10-10-forge-neoforge-design.md`

## Global Constraints

- Forge Minecraft 1.18.2+ and NeoForge 1.20.2+, within audited Minecraft/LWJGL and verified installer-format ranges.
- Match Java major requirements; inspect Java 25 formats before offering them.
- No subprocesses or GUI installer dependencies on Orbis.
- Preserve premium ownership checks and credential-free persisted transactions.
- Verify downloads and generated outputs; write install records last.
- Push verified changes directly to main; no pull requests.

## Review Focus

- An exact pack loader version must not be substituted or reused from a different installed build.
- A processor dependency that needs a newer JVM must produce a recoverable continuation, not strand the launcher in game mode.
- A successful processor return with missing or mismatched output must not publish an installation.
- Module-path bootstraps must use their owning loader and avoid duplicate class-path dependencies.
- Cancelling, failing, or restarting installation must restore runner settings and preserve usable existing instances.

## Files and responsibilities

Create `ForgeInstaller.java` for official catalogs, installer profile/resource parsing, Maven/data resolution, artifact acquisition, and orchestration. Create `InstallerProcessors.java` for isolated client processor invocation/output verification. Create `LoaderLaunch.java` for profile argument expansion, JNI module option normalization, and module-aware main lookup. Create `InstallContinuation.java` for a bounded, credential-free, one-shot installation restart on another runtime.

Modify `VersionStore.java` for loader values, exact-version install overload, merged metadata, installed argument persistence, and artifact validation. Modify `LaunchPreparation.java`, `Launcher.java`, and `LaunchArguments.java` for selected runtime, continuation consumption, module bootstrap, and managed loader arguments. Modify `LauncherScreen.java` and `PackInstaller.java` for loader selection, filtering, exact pack versions, and progress. Modify `orbis-java-app/launcher.c` and `tools/runner_config.c` for bounded JNI-compatible module options.

Create Java tests under `graphics-probe/tests/launcher/orbis/launcher`, JSON fixtures under `graphics-probe/tests/fixtures/loaders`, and `graphics-probe/tests/test_forge_neoforge.py` for focused compilation/runs. Extend existing launcher and native runner tests. Create `graphics-probe/tests/forge_integration.py` as an opt-in, networked real-install/bootstrap harness using temporary host directories, never player credentials.

### Task 1: Profiles, catalogs, and launch argument model

**Interfaces:** `ForgeInstaller.catalog(VersionStore.Loader): Map<String,List<String>>`; `ForgeInstaller.latest(Loader,String): String`; `LoaderLaunch.jvm(JsonObject,Map<String,String>): List<String>`; `LoaderLaunch.game(JsonObject,Map<String,String>): List<String>`; `LoaderLaunch.mainClass(String): Class<?>`. All parsing/acquisition failures surface as IOException.

- [ ] Write `ForgeProfilesTest`: official fixture main/arguments; Forge recommended/latest selection; NeoForge declared Minecraft matching; malformed coordinates/tokens; oversized entries; unknown mandatory placeholders; rule selection; module arguments normalized into complete JNI options. Assert a path escape is rejected and unresolved placeholders are never persisted.
- [ ] Run `python3 -m unittest discover -s graphics-probe/tests -p test_forge_neoforge.py -v`; expect failure from the missing loader/profile behavior.
- [ ] Implement the named interfaces using real installer metadata, bounded inputs, and explicit format validation. Handle coordinate classifier and extension independently. Identify supported Java 25 profiles through actual fixtures before adding catalog coverage.
- [ ] Rerun the focused command; expect all profile tests passing. Commit the profile/catalog deliverable.

### Task 2: Client patching and installation publication

**Interfaces:** `InstallerProcessors.run(JsonObject,Path,Map<String,String>,InstanceStore.Operation): void`; `ForgeInstaller.prepare(Loader,String,String,Path,Path,Path,Operation): JsonObject` consumes the vanilla client, libraries root, and installer staging and returns the loader version profile after processor completion. Add `VersionStore.install(GameVersion,Loader,String,Operation,boolean): Installed`; existing overloads delegate with an unspecified version.

- [ ] Write `InstallerProcessorsTest`: client/server side selection; real tiny processor JAR with isolated dependencies; context loader restored after exception; valid hash output reused; wrong hash/missing output rejected; interrupted stage never publishes install.json. Add exact-version install assertions.
- [ ] Run focused tests; expect missing executor/install behavior failures.
- [ ] Implement artifact downloads/extraction and client processor execution. Resolve installer tokens and record generated artifact hashes. Merge loader overrides and game metadata while retaining Orbis graphics artifacts. Persist JVM/game arguments with backward-compatible defaults for old install records.
- [ ] Run focused tests and actual Forge 1.20.1 and NeoForge 1.21.1 processor chains with `python3 graphics-probe/tests/forge_integration.py --install`; expect verified patched artifacts and complete flattened profiles. Commit this deliverable only after reading results.

### Task 3: Runtime continuation and native module startup

**Interfaces:** `InstallContinuation.prepare(...): void` stores a validated loader/version/install destination and prepares an application restart without credentials; `InstallContinuation.consume(...): boolean` restores settings and resumes once. `LoaderLaunch.mainClass` locates named-module bootstrap classes. Installed loader JVM/game arguments feed existing preparation/build APIs.

- [ ] Add `LoaderStartupTest` and native option tests: module path beyond 512 bytes accepted within a documented bound; excess options/overlong lines rejected; module-loaded main resolved; duplicate bootstrap copies avoided; incompatible runtime majors rejected; newer-runtime continuation consumed once; cancellation/failure restores settings. Ensure loader launch targets cannot be replaced by user arguments.
- [ ] Run focused Java tests and `python3 orbis-java-app/tests/test_runner_config.py`; expect failures for the old native limits and startup behavior.
- [ ] Implement bounded module-aware JNI options and trusted prepared-path validation without opening user override paths. Use existing restart transaction to select the installation and game runtime. Keep compatibility mod injection restricted to Fabric/Quilt. Adapt verified early-display settings where required by real loader startup.
- [ ] Run focused/native tests and `python3 graphics-probe/tests/forge_integration.py --bootstrap`; expect the real Forge and NeoForge bootstrap to reach loader discovery using the custom runner model without module/class-loader failures. Record any subsequent absent host graphics environment separately. Commit the startup deliverable.

### Task 4: Player UI, modpack versions, regression verification, and package

**Interfaces:** LauncherScreen uses the new enum/catalog and exact-version install overload; PackInstaller retains its Source seam and passes the exact declared Forge/NeoForge version through it.

- [ ] Extend `LauncherLogicTest` for both loader choices, mod filters, exact-version .mrpack installation, conflicting dependencies, install progress/errors, continuation recovery, and existing owned/offline launch behavior. Run the host harness and observe failures before UI/pack changes.
- [ ] Implement UI/pack integration and update README with supported ranges/runtime behavior and controller flow. Keep network seams overridable for host UI tests.
- [ ] Run `python3 /tmp/test-cyrene-launcher.py 8 6000`, focused loader tests, native runner option tests, and the broader graphics-probe Python suite. Read and report every failure; distinguish baseline fixture/environment failures from regressions and resolve feature failures before integration.
- [ ] Run `./orbis package --version 01.08 --jobs 1`; expect package/runtime audits and PKG validation to pass. Write local console test instructions and SHA-256 for the resulting artifact.
- [ ] Obtain one independent whole-change review, fix important findings with failing-first tests, and rerun affected verification. Commit, fast-forward main if working on a temporary local branch, push origin main, and verify its remote SHA. Do not publish a release or claim console runtime success without observed hardware results.

## Execution choice

Native execution is recommended because installation, startup, and UI tasks share the same VersionStore and launch metadata interfaces. Implement in this session with a progress ledger and one independent final review. An existing checkout can be used on a temporary local branch to retain ignored build caches; the owner may instead select an isolated worktree or delegated implementation. Direct upstream integration remains unchanged.
