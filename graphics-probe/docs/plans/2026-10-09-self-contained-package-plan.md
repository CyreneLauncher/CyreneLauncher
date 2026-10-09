# Self-contained package implementation plan

Goal: install all provided code inside the PKG; keep downloaded content in /data.
Spec: ../specs/2026-10-09-self-contained-package.md. Execute inline.

- [ ] Add failing installed-layout/default/profile/assembly tests.
- [ ] Implement explicit runtime payload inventory, hash verification and clean staging.
- [ ] Implement installed runner defaults and exclusive first-start settings seeding.
- [ ] Implement direct JDK discovery, launcher-only bootstrap and reversible runtime roots.
- [ ] Wire dedicated release assets/stage/package Make targets using existing exports.
- [ ] Run host regressions, independent review, and build/validate a local release PKG.
- [ ] Record artifact hashes and console acceptance steps; preserve old exports and state.

Review focus: read-only installed paths; no-game fresh launch; version/profile mixing;
existing settings preservation; stale or unexpected staged payloads; transaction rollback.
