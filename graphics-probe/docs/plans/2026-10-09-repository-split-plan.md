# Repository split implementation plan

> Implement inline using superpowers:executing-plans, one verified component at a time.

**Goal:** Separate modified native dependencies while preserving working build inputs.

**Architecture:** Sibling Git repositories, relative compatibility links, exact revision lock.

**Tech stack:** Git subtree history extraction, Python bootstrap/verification, existing host tests.

**Spec:** ../specs/2026-10-09-repository-boundaries.md

## Constraints

Preserve upstream and port history, cached/deployed artifacts and unrelated working edits.
No rebuild, deployment or remote publication. Keep all dependency pins and patch bytes.

## Tasks

- [x] Snapshot file hashes, source revisions and build artifact hashes; verify baseline host checks.
- [x] Extract Mesa patch history, attach it to a standalone upstream Mesa repository and verify all patches.
- [x] Extract LWJGL history, OpenAL/SDL overlays and recipes; create shared runtime repository with provenance.
- [x] Restore JDK21 Git discovery, retaining .orbis-git and checking worktree status.
- [x] Add relative compatibility links, repositories.lock.json and safe bootstrap/verification tool.
- [x] Test missing sources, incorrect pins, dirty tracked sources, invalid links and recovery bootstrap.
- [x] Run existing host checks and prove original inputs/artifacts retained; commit migration and bundle histories.

Review focus: links into nested sibling repos; recipe root resolution; uninitialized repos;
dirty source rejection; preserved Mesa patch order/cache identity. Integration recipes that
move must retain their previous CLI through launcher wrappers.

## Execution record

- Approved repository design executed inline on repository-split, preserving unrelated edits.
- Ruling: preserve the donors' available Git histories and shallow boundaries. A real
  bundle restore exposed missing boundary metadata; .bundle.shallow sidecars and a
  failing-then-passing regression now cover that case.
- Ruling: direct recipes serve maintainer builds; pinned integration builds use launcher
  wrappers. Their documentation makes the distinction explicit.
- Independent whole-change and focused recovery reviews found no blocking findings.
- Results and limitations: 2026-10-09-repository-split-results.md.
