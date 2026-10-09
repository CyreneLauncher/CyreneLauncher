# Cyrene Launcher Implementation Plan

Goal: publish clean source repositories and a shared CLI/Actions pipeline producing audited PKGs.
Spec: design.md. Execution: inline, as authorized by the user's proceed instruction.

1. Create isolated publication clones; strip only Claude/Anthropic coauthor and session trailers, retaining author identities and upstream notices. Audit tracked paths and update pins to rewritten commits.
2. Test orchestration boundaries before implementation: missing inputs, valid package manifests, hash corruption, safe archive extraction, component collection, and command failure propagation.
3. Add a small standard-library Python CLI reusing the owned recipes; bootstrap dependencies with recorded hashes, log stages, build JDKs serially, generate fresh package inputs and validate final stage. Add status/doctor and dependency import/export.
4. Rename visible launcher title and documentation, preserve package identity/data paths. Add hosted release workflow with manual dispatch, checks, dependency artifacts, and draft publication.
5. Run targeted tests, CLI checks, repository verification and package build with existing audited inputs where feasible; identify fresh-build failures honestly.
6. Create/push the seven public repositories, verify remote heads and readable source bootstrap. Publish only verified release outputs as draft; otherwise leave workflow ready and report remaining prerequisite.
