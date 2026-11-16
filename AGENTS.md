# Agent guide

Android companion to SuperControlPanel: SSH terminal to SCP servers (direct dial or Boundary) without Termux. Design: `docs/design.md`.

## Sibling repositories

Related repos are always checked out as sibling folders of this one (`../`):

- `../supercontrolpanel`: the desktop app and source of truth for `servers.json`/`config.json` formats, Boundary OIDC behavior (`boundary_oidc_init_script`), and the encrypted backup format (`crates/scp-core/src/backup/crypto.rs`). Read it; do not modify it from here.
- `../ics-openvpn`: the OpenVPN fork that supplies the VPN, controlled through its AIDL remote API (`IOpenVPNAPIService`). OpenVPN is never embedded in this app.

If a sibling is missing, say so instead of guessing its contents.

## Layout

- `go/scpmobile/`: Go networking library (SSH, later Boundary), bound with gomobile.
- `app/`: Gradle/Kotlin/Compose app consuming the `scpmobile` AAR built from `go/`; see `app/README.md`.
- `testdata/`: backup test vectors produced by SCP `scp-core`.

## Workflow

Strict TDD for every change: write a failing test, confirm it fails for the expected reason, implement minimally, rerun, then run the full suite. Never commit code without tests. Keep changes focused; prefer explicit errors.

## Toolchain and commands

Don't assume tool locations or versions; they differ per machine. Discover what is installed first (`PATH`, `JAVA_HOME`, `ANDROID_HOME`, common SDK/JDK locations) and prefer the existing toolchain over installing a new one. Required versions are declared in the repo (`go/go.mod`, `app/app/build.gradle.kts`, the Gradle wrapper); treat those as the source of truth. Go can usually fetch a newer toolchain on its own.

Build and test entry points:

- Go library: run its tests with the race detector from `go/`.
- AAR: rebuild with `go/build-aar.sh` after any Go API change. Confirm the output lands where the app module expects it, and that the script picked a real NDK (not a legacy leftover). It is git-ignored and arm64-only.
- Kotlin app: use the Gradle wrapper in `app/` for unit tests and the debug APK. Gradle needs a JDK that satisfies the wrapper's requirements, which may differ from the shell default.
- Running the APK needs an arm64 device; x86_64 emulators cannot load the AAR.

If a command fails because of environment, fix the environment (env vars, script arguments) rather than the repo, and report repo bugs instead of working around them silently. See `app/README.md` for more.

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

- Install it from the Ripwire repo's documented installer (review any script before running) if it is not already on `PATH`.
- Orient: `ripwire . --for="<task>" --legend=compact`.
- Inspect: `ripwire . --expand="<symbol>" --legend=compact`.
- Before editing an existing symbol: `--impact="<symbol>"` and `--uses="<symbol>"`; report callers, affected paths and risk, and warn before high-risk edits.
- Select tests: `--affected="<symbol>"`.
- Before committing: inspect `git diff` and run `ripwire . --quality-delta --legend=compact`.

Static analysis is approximate; verify dynamic dispatch and cross-language boundaries (Go <-> Kotlin via gomobile, AIDL) in source and tests.
