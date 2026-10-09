# GitHub-hosted release implementation plan

**Goal:** Build all three PS4 JDKs, native drivers and the final PKG using GitHub-hosted Ubuntu 24.04 runners, with no paths or caches from Wonderfied’s computer.

**Architecture:** Provision versioned, SHA-256 checked upstream SDK archives and build the pinned .NET 8 PKG tool from source. Run JDK 17/21/25 as a matrix and native/desktop compilation independently; transfer compressed artifacts with file inventories into a package job, which checks provenance and creates a draft release.

**Constraints:** Preserve native radeonsi/RADV and reject Zink. Preserve upstream licensing/authors. Never upload credentials or preconfigured player accounts. No console deployment. Retain the same CLI for local builds.

## Tasks

- [x] Compare OpenOrbis and payload SDK distributions with local installations; record exact upstream versions and any required differences.
- [x] Add toolchain lock and provisioning script with hash checks, safe archive handling, a pinned PKG source revision and exported runner environment; test corrupt archives and path traversal.
- [x] Add single-JDK CLI selection and assets/seal stages, with tests for partial builds and provenance validation.
- [x] Replace the self-hosted workflow with hosted JDK matrix, assets and packaging jobs; preserve file modes in tar archives and validate incoming inventories.
- [x] Update documentation, validate workflow syntax/actions, run orchestration tests and request independent review.
- [ ] Publish and dispatch the hosted pipeline; follow build failures through correction and report the actual build result.

## Review focus

SDK content hashes must match the accepted ABI. Artifacts must retain executable permissions. Matrix artifacts must not overwrite each other. Packaging must reject modified or mismatched exports. Hosted steps must not use local SDK/cache paths.

Verified: 20 orchestration tests pass; actionlint v1.7.12 passes; real JDK17 transfer preserves mode/content and passes runtime provenance checks. Stock SDK provisioning and source PKG build/SFO commands pass locally. Independent review found no critical/important findings. Hosted end-to-end verification remains pending.

Hosted probes corrected exact Adoptium LTS identifiers, the SDK’s absolute LLVM_CONFIG requirement, and Mesa’s required glslangValidator host program. Nested build diagnostics are collected and the CLI streams CI compiler output. The final full PKG run remains under verification.
