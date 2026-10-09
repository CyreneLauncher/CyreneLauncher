# Orbis repository boundaries

The user approved extracting the modified dependencies into their own repositories,
starting with Mesa. Success means independently tracked sources with recoverable
history, exact integration pins, and identical effective build inputs.

## Ownership

- mesa-orbis: upstream Mesa history, launcher patch history, ordered Orbis patch series.
- lwjgl-orbis: version profiles, JNI and Java adapters, bundled dyncall/libffi adaptations.
- openal-soft-orbis: AudioOut backend and OpenAL-specific build adaptations.
- sdl-orbis: SDL threading adaptations and gamepad adapter.
- orbis-runtime-support: runner PRX runtime sources and linker-script preparation.
- jdk17u, jdk21u, jdk25u: existing independent Java ports, retained without source changes.
- orbis-launcher: app, launcher, packaging, integration recipes and acceptance tests.

## Integration contract

Use sibling repositories and a committed repositories.lock.json. Relative compatibility
links preserve current paths. A verification/bootstrap tool checks exact revisions,
clean tracked inputs and link destinations. New repositories have no invented published
remote: local seeds or recovery bundles bootstrap them until publication is requested.
Each port records its provenance. OpenAL and SDL initially remain source overlays against
hash-pinned upstream archives; the complete build recipes move with their overlays.
Recipes receive their integration root explicitly, allowing standalone repository ownership
without silently deriving the root from their new file location.

Mesa keeps its known-good base revision and byte-identical patch series; the existing
cached build checkout continues to use that base. Patch content, ordering and shader-cache
identity must remain unchanged. Its own repository carries both upstream and patch history.

Shared runner runtime helpers move together with local headers. JDK runtime helpers remain
version-specific. JDK21 uses .orbis-git; restore normal Git discovery through a .git pointer
without deleting or replacing its existing history.

## Verification and recovery

Record original tracked file hashes before extraction. Verify all compatibility paths
against those hashes afterward, except intentionally adapted recipe wrappers. Run existing
host regression tests, validate the complete Mesa patch series in a temporary checkout,
and check bootstrap/mismatched revision/dirty source failure behavior. Preserve existing
builds and deployed binaries; this migration performs no Mesa/JDK rebuild or deployment.
Make local Git bundles of extracted histories and record pins and migration results.
Do not stage unrelated README/bookkeeping edits or publish repositories.
