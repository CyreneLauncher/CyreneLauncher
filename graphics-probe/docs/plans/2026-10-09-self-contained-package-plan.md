# Self-contained package implementation plan

Goal: install all provided code inside the PKG; keep downloaded content in /data.
Spec: ../specs/2026-10-09-self-contained-package.md. Execute inline.

- [x] Add failing installed-layout/default/profile/assembly tests.
- [x] Implement explicit runtime payload inventory, hash verification and clean staging.
- [x] Implement installed runner defaults and exclusive first-start settings seeding.
- [x] Implement direct JDK discovery, launcher-only bootstrap and reversible runtime roots.
- [x] Wire dedicated release assets/stage/package Make targets using existing exports.
- [x] Run host regressions, independent review, and build/validate a local release PKG.
- [x] Record artifact hashes and console acceptance steps; preserve old exports and state.

Review focus: read-only installed paths; no-game fresh launch; version/profile mixing;
existing settings preservation; stale or unexpected staged payloads; transaction rollback.

Results: [2026-10-09-self-contained-package-results.md](2026-10-09-self-contained-package-results.md). Console acceptance remains pending.
