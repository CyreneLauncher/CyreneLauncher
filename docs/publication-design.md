# Cyrene Launcher publication design

Approved scope: publish seven repositories under CyreneLauncher, keep Wonderfied as the author, remove AI coauthor/session trailers from publication histories, retain upstream attribution and licenses, rename visible launcher branding while preserving title ID and data paths. Keep the original local repositories intact.

Use isolated publication clones as the integration workspace. Pin all native repositories and the consolidated OpenJDK builder to exact commits. A Python CLI orchestrates source bootstrap, prerequisites, dependencies, JDK builds, native builds, launcher/desktop, audited package inputs and PKG creation. It provides local build and package commands with log files and component resume, plus dependency export/import for reuse.

A manually dispatched release workflow runs entirely on GitHub-hosted Ubuntu 24.04. It provisions locked upstream SDKs and host JDKs and builds the pinned PKG tool from source. Three independent JDK jobs and a native/desktop job upload compressed, inventoried components; the package job verifies provenance and assembles the PKG. Publishing produces a draft release only after the package passes checks. No runner registration or local SDK paths are required. No accounts, credentials, existing binary releases or bookkeeping are committed.
