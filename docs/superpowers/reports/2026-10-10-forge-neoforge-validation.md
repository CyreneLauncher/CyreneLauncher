# Forge and NeoForge validation — 2026-10-10

Forge and NeoForge installation, instance creation, exact-version Modrinth packs, mod browsing, and managed game startup are implemented. This report describes host evidence; PS4 game rendering remains unverified.

## Runtime scope

Forge is offered for Minecraft 1.18.2–1.21.11; NeoForge for 1.20.2–1.21.11. Games select the exact installed Java major: Java 17 through 1.20.4, Java 21 from 1.20.5. Installer tools can request a newer runtime independently. A dependency class-version failure also requests a restart, including when reflection wraps the failure. Restart preserves the requested instance name or the verified local modpack archive and completes the original operation.

The packaged Java 17 module image was inspected. Module bootstrap is supported; java.instrument is absent, so Java-agent mods are not supported. Foreign platform native libraries remain incompatible. Forge-family releases requiring Java 25 are not offered without an audited upstream profile and actual bootstrap verification.

## Real upstream installs and embedded-JVM bootstrap

| Loader | Minecraft | Exact build | Host JVM | Result |
| --- | --- | --- | --- | --- |
| Forge | 1.20.1 | 47.4.0 | 17 | Verified patched client and bootstrap to game target |
| NeoForge | 1.21.1 | 21.1.172 | 21 | Verified patched client and bootstrap to game target |
| Forge | 1.21.11 | 61.2.1 | 21 | Verified patched client and bootstrap to game target |
| NeoForge | 1.21.11 | 21.11.45 | 21 | Verified patched client, game content classloader and Minecraft data initialization |

Commands: `python3 graphics-probe/tests/forge_integration.py --install` and `python3 graphics-probe/tests/forge_integration.py --bootstrap`. Installation uses official installer artifacts and real processors, verified output hashes, and no player credentials. Bootstrap calls JNI_CreateJavaVM with the production native option normalizer and custom system-loader shape. Probes stop before console graphics; they do not establish hardware compatibility.

The initial NeoForge 1.21.11 probe incorrectly accepted an exception mentioning ModLoader and exposed a stale Gson on the parent classpath. It was corrected to parse metadata with the runner's dependency-free JSON reader, load Gson from the installed game libraries, require successful game initialization, and reject dependency linkage errors. Only the corrected four-case run is evidence of bootstrap success.

## Regression checks

- Focused tests: `python3 -m unittest discover -s graphics-probe/tests -p test_forge_neoforge.py -v`; production launcher compilation with Java 17, -Xlint:all and -Werror; official profiles, processor execution and isolation, output verification, class-version continuation, restart context, exact-build packs, loader menu choices and offline launch arguments.
- UI regression harness: `python3 /tmp/test-cyrene-launcher.py 8 6000`; deterministic existing launcher behavior plus 48,000 random screen actions. Random actions are not 48,000 game launches.
- Native checks: `python3 orbis-java-app/tests/test_runner_config.py` and `python3 orbis-java-app/tests/test_runner_startup.py`; module paths, capacity bounds and reserved user options.
- Package: `./orbis package --version 01.08 --jobs 1`; source rebuild and package/runtime audits. Local artifact and console instructions are in `dist/`.

## Broad Python suite limitations

The final broad graphics-probe discovery run executed 112 tests: 95 passed, four skipped, three failures and ten errors remain in the same baseline fixture/environment categories found before this feature. These are reported rather than represented as a green suite. The feature-specific, native and launcher checks are separate.

Failures:

- `test_minecraft_prepare.PrepareTests.test_local_http_manifest_libraries_assets_and_atomic_tree`: existing module-name expectation mismatch.
- `test_stb_contract.STBContract.test_build_and_inputs`: unavailable build/audit fixture.
- `test_stb_contract.STBContract.test_host_fixture_semantics`: unavailable host fixture.

Errors:

- `test_graphics_bundle`: fixture-dependent import.
- `test_lwjgl_compat_contract.CompatibilityContract.setUpClass`: missing /tmp/lwjgl-audit.
- `test_lwjgl_facade`: fixture-dependent import.
- `test_lwjgl_natives`: fixture-dependent import.
- `test_lwjgl_optional.OptionalContract.test_official_lwjgl_java`: fixture/build environment.
- `test_lwjgl_optional.OptionalContract.test_prx_audit_and_exports`: fixture/build environment.
- `test_lwjgl_optional.OptionalContract.test_source_checkout_contract_does_not_need_private_audit_json`: fixture/build environment.
- `test_lwjgl_smoke_contract`: fixture-dependent import.
- `test_platform_prx`: fixture-dependent import.
- `test_sdl_gamepad`: fixture-dependent import.

## Independent review and decisions

One independent whole-change review reported three important findings: dependency class-version continuation, the bootstrap false positive, and loss of the originating installation operation across restart. All entered the fix pass with regression coverage; no minor findings were deferred. Review was not repeated after fixes.

Decisions:

1. Use the existing checkout on a temporary branch to retain SDK caches, then integrate directly into main as requested. A mistake could overlap other local work; the final diff and repository status are checked before integration.
2. Use complete JNI `--module-path=...` options rather than reserved `-Djdk.module.path` properties. Real embedded-JVM startup and native tests cover the decision; a mistake would prevent loader bootstrap.
3. Provide a local test PKG while leaving PS4 startup/rendering pending. Host probes cannot prove console graphics; the cost is an unobserved hardware-specific failure. No public release is published.
4. Do not claim Java-agent or foreign-native-mod compatibility. The packaged runtime lacks instrumentation and platform natives differ; unsupported mods may fail. The README states the supported scope.
5. Continue integration with the documented existing broad-suite fixture failures, as the feature plan requires distinguishing baseline failures from regressions. Those unrelated areas remain incompletely checked; dedicated loader/launcher/native/package verification supplies the relevant evidence.
