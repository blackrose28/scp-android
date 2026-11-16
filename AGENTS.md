# Agent guide

Android companion to SuperControlPanel: SSH terminal to SCP servers (direct dial or Boundary) without Termux. Design: `docs/design.md`.

## Sibling repositories

Related repos are always checked out as sibling folders of this one (`../`):

- `../supercontrolpanel`: the desktop app and source of truth for `servers.json`/`config.json` formats, Boundary OIDC behavior (`boundary_oidc_init_script`), and the encrypted backup format (`crates/scp-core/src/backup/crypto.rs`). Read it; do not modify it from here.
- `../ics-openvpn`: the OpenVPN fork that supplies the VPN, controlled through its AIDL remote API (`IOpenVPNAPIService`). OpenVPN is never embedded in this app.

If a sibling is missing, say so instead of guessing its contents.

## Layout

- `go/scpmobile/`: Go networking library (SSH, later Boundary), bound with gomobile.
- `app/`: Gradle/Kotlin/Compose app consuming `app/libs/scpmobile.aar`; see `app/README.md`.
- `testdata/`: backup test vectors produced by SCP `scp-core`.

## Workflow

Strict TDD for every change: write a failing test, confirm it fails for the expected reason, implement minimally, rerun, then run the full suite. Never commit code without tests. Keep changes focused; prefer explicit errors.

## Toolchain and commands

Go 1.27+ (`~/.local/go`), JDK 21 (`~/.local/jdk-21`), Android SDK (`~/Android/Sdk`, platform 37, build-tools 37, NDK 30). Put Go on `PATH` and set `JAVA_HOME`/`ANDROID_HOME` if not already set.

- Go tests: `cd go && go test -race ./scpmobile/`
- Rebuild AAR (needed after any Go API change): `cd go && ./build-aar.sh` -> `app/libs/scpmobile.aar` (arm64-v8a only; git-ignored)
- Kotlin tests: `cd app && ./gradlew :app:testDebugUnitTest`
- Debug APK: `cd app && ./gradlew :app:assembleDebug` (run on an arm64 device; x86_64 emulators cannot load the AAR)

## Invariants

- Host keys use TOFU only: unknown/changed keys need explicit user confirmation; persist only on accept; timeouts and UI failures reject.
- One Ed25519 device key, generated in Go, stored Keystore-encrypted. Never log or persist private keys, tokens, or passwords in plaintext.
- No exec of bundled binaries (W^X); everything runs in-process.
- gomobile surface rules: functions return `T`, `error` or `(T, error)`; wrap multi-value results in types.
- Writing to the SCP Git sync repo is out of scope.
- Update `README.md` / `docs/design.md` for user-facing or architectural changes.

## Status

Done: Go slice 1 (direct dial, shell, keys) and app MVP (TOFU, connect, line-oriented terminal). Next: Go Boundary (`StartOidc`/`Poll`/`DialBoundary`), backup import, server list, automated OIDC, Git sync, foreground service.

## Code intelligence (Ripwire)

Use [Ripwire](https://github.com/redhat-et/ripwire) from the repository root before broad code searches or whole-file reads. It is a dev tool, not a runtime dependency; it keeps its incremental cache outside the repo.

- Install (Linux/macOS): download `scripts/install.sh` from the Ripwire repo, review it, then `RIPWIRE_REPO=redhat-et/ripwire bash install.sh`; ensure `~/.local/bin` is on `PATH`; verify with `ripwire --version`.
- Orient: `ripwire . --for="<task>" --legend=compact`.
- Inspect: `ripwire . --expand="<symbol>" --legend=compact`.
- Before editing an existing symbol: `--impact="<symbol>"` and `--uses="<symbol>"`; report callers, affected paths and risk, and warn before high-risk edits.
- Select tests: `--affected="<symbol>"`.
- Before committing: inspect `git diff` and run `ripwire . --quality-delta --legend=compact`.

Static analysis is approximate; verify dynamic dispatch and cross-language boundaries (Go <-> Kotlin via gomobile, AIDL) in source and tests.
