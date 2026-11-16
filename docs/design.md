# scp-android — design

Status: draft (2026-11-16). Android companion to SuperControlPanel (SCP) desktop.

## Goals

- SSH terminal to SCP servers from an Android phone, without Termux.
- Two routes from day one, following `Server.connectionMethod` in `servers.json`:
  - `ssh` (direct dial): TCP to `host:sshPort` over the VPN.
  - `boundary`: Boundary session via an embedded client proxy.
- Boundary authentication via OIDC (browser mode and automated mode).
- Server list from **both** Git sync (`app-config/servers.json`) and SCP encrypted
  backup import.

## Non-goals (for now)

- OpenVPN inside this app. VPN is provided by the separate ics-openvpn fork and
  controlled through its AIDL remote API (`IOpenVPNAPIService`).
- Pinned host keys from Git sync or backup. Host keys are trusted on the phone
  only (TOFU with fingerprint confirmation).
- SFTP/SCP transfers, Caddy, MCP, CC integration.
- Writing anything back to the Git sync repo.

## Architecture

```
┌──────────────── Kotlin / Compose app (app/) ──────────────────┐
│ Server list · Status cards · Terminal (termux terminal-view)  │
│ OIDC (Custom Tabs / WebView) · Keystore · known_hosts store   │
└───────┬───────────────────────┬────────────────────┬──────────┘
   AIDL │                 gomobile bind              │ JGit / SAF picker
        ▼                       ▼                    ▼
  ics-openvpn fork      libscpmobile (go/)      servers.json + backup
  (VPN + TOTP)          ├ boundary api/authmethods, api/targets
                        ├ boundary api/proxy over in-memory listener
                        └ golang.org/x/crypto/ssh → PTY shell channel
```

- Kotlin owns UI, persistence, secrets, and trust decisions.
- Go owns all network protocols. Kotlin only sees byte streams and callbacks.
- No Rust in the app. The backup decrypt lives in the Go library (`DecryptBackup`) and is verified against
  test vectors produced by `scp-core` (`testdata/`). It was first planned as a Kotlin port; Go needs no new
  dependency (`golang.org/x/crypto/argon2` is already in the module), is covered by `go test`, and avoids
  shipping Bouncy Castle, whose `org.bouncycastle` package can clash with the trimmed copy in Android's boot classpath. Kotlin
  only parses the decrypted JSON.

### Why Boundary uses an in-memory listener

`boundary connect` exposes the session on a localhost TCP port. On Android any
app can connect to `127.0.0.1:<port>` while the session is open, and the proxy
does not authenticate local clients. `proxy.WithListener(net.Listener)` lets the
SSH client consume the session in-process with no loopback port.

## Go library API (`libscpmobile`)

gomobile-compatible surface. gomobile only binds functions returning `T`,
`error`, or `(T, error)`, so multi-value results are wrapped in types.
The output sink is passed to `OpenShell` so no early output is lost.

```go
// Boundary auth (OIDC)
StartOidc(controllerURL, authMethodID string) (*OidcPending, error) // AuthURL()
(*OidcPending).Poll() (*OidcResult, error) // Done(), Token(), ExpirationUnixMs()

// Transport
DialDirect(host string, port int, timeoutMs int) (*Conn, error)
DialBoundary(controllerURL, token, targetID string) (*BoundarySession, error)
BoundarySession: Conn() (*Conn, error), ExpirationUnixMs(), ConnectionsLeft(), Close()

// SSH
OpenShell(conn *Conn, user string, privateKey []byte, alias string,
          hk HostKeyVerifier, out OutputSink, cols, rows int) (*Shell, error)
Shell: Write([]byte), Resize(cols, rows), Close()

// Backup
DecryptBackup(data []byte, passphrase string) ([]byte, error) // plaintext JSON payload

// Keys
GenerateEd25519() (*KeyPair, error) // PrivateOpenSSH, PublicAuthorizedKey
Fingerprint(authorizedKey []byte) (string, error) // "SHA256:…"
```

Binding rules learned from the generated Java:

- Go `int` maps to Java/Kotlin `long`.
- gomobile emits public no-arg constructors for every exported struct
  (`new Shell()`, `new Conn()`). Every exported method must handle the zero
  value by returning an error. A Go panic aborts the whole app process.
- Kotlin callback implementations (`HostKeyVerifier`, `OutputSink`) must not
  throw. Catch everything on the Kotlin side.

Callbacks implemented in Kotlin:

```go
type HostKeyVerifier interface {
    // Blocks the handshake until the user decides. Returns true to trust.
    Verify(alias, keyType, fingerprint string, authorizedKey []byte) bool
}
type OutputSink interface {
    Write(p []byte)
    Closed(reason string)
}
```

## Host key policy (TOFU only)

Entries are keyed by an **alias** = the server's real `host:sshPort`, not the
dialed address (Boundary sessions have no meaningful dial address). One trusted
key therefore applies to both routes for the same server.

| Store state for alias | Presented key | Result |
| --- | --- | --- |
| not found | any | TOFU screen: server name, route, key type, SHA256 fingerprint → *Trust & save* / *Cancel* |
| trusted | same key | connect |
| trusted | different key | hard fail: "Host key changed" screen; no one-tap replace. User must delete the entry explicitly, then reconnect to get the TOFU screen again |

The store is app-private and stores OpenSSH `known_hosts` lines with the alias
as the host field.

## SSH client identity

- One Ed25519 key per device, generated in Go on first launch.
- Private key encrypted with an Android Keystore AES-GCM key (biometric-bound).
- Public key shown with copy/share for adding to `authorized_keys`.
- Losing a phone means revoking that device key only.

## Data sources

- **Git sync:** read-only JGit pull of `app-config/servers.json` and
  `app-config/config.json` (Boundary controller URL, OIDC auth method ID).
  Credentials (deploy token or SSH key) stored via Keystore.
- **Backup import:** SAF file picker (`OpenDocument`, `*/*`, read capped at 32 MiB) + passphrase.
  Format v1 (SCP `crates/scp-core/src/backup/crypto.rs`): `SCPBAK01` + u32 LE header length + JSON header
  (salt/nonce are JSON number arrays) + AES-256-GCM, with the exact header bytes as AAD and the key from
  Argon2id v1.3 (`argon2id-19`). `DecryptBackup` mirrors the desktop checks: size, magic, header cap (1024),
  `version == 1`, `kdf`, `cipher`, passphrase 1-4096 bytes, and KDF cost bounds (8-64 MiB, 1-3 passes,
  1 lane) **before** running Argon2, because the header is only authenticated afterwards. The payload is the
  desktop `Archive` (schema 1). Kotlin keeps only: servers (`id, name, host, sshPort, sshUser`, Boundary
  target ID), the shared Boundary settings (`addr`, `authMethodId`, `authKind`, `oidcLoginMode`,
  `oidcUsername`) and the two Boundary OIDC secrets. The raw `config.json` is never stored (it can hold
  legacy plaintext passwords); OpenVPN credentials, per-server SSH passwords and `boundary_app_token` are
  dropped (the phone authenticates with its device key). Servers are stored in an app-private file in the
  phone's own schema; the secrets in a Keystore-sealed file. Importing shows a preview and then replaces
  the whole list; secrets are saved first and rolled back if writing the list fails.
- A Boundary server needs `authKind == "oidc"` and valid IDs; otherwise it stays in the list as unavailable
  with a reason (Boundary password login is not supported). `oidcLoginMode: automated` is carried on the route
  (`automatedLogin`, `oidcUsername`; never the secrets) and selects the hidden-WebView login (see Boundary OIDC). `boundaryListenPort` and `services` are desktop-only and ignored.
- Merge rule: Git sync wins for shared data; backup supplies secrets.
- Device-local desktop fields (executable paths, Caddy, MCP) are ignored.

## Boundary OIDC

- Browser mode: `StartOidc` → open `AuthURL` in the system browser (`ACTION_VIEW`, http/https only;
  Custom Tabs would need the `androidx.browser` dependency and add nothing here, since the IdP calls back
  to the controller and the app only polls) → `Poll` every 2 s until a token, giving up after 5 min.
  `StartOidc`/`Poll` speak the same HTTP API as the desktop app
  (`POST /v1/auth-methods/<id>:authenticate`, commands `start` then `token`).
  `Poll` treats HTTP 400/404 as "login not finished yet" and any other non-200
  as an error. `DialBoundary` authorizes the session on a target ID (`ttcp_…`),
  then runs `api/proxy` over a one-shot in-memory listener (`net.Pipe`), so no
  loopback port is opened. The session ends when the SSH connection over `Conn`
  closes (the proxy is cancelled so the worker is told to tear it down) or when
  `BoundarySession.Close` is called, whichever comes first. The controller URL
  and token always come from the caller, never from `BOUNDARY_ADDR`/`BOUNDARY_TOKEN`.
  `Poll` also treats a 2xx reply without a token (e.g. 202) as still pending.
- Automated mode (implemented): the pending `StartOidc` login is unchanged and still polled; only the screen
  differs. `loginPlan` picks automated, or the browser with a reason (no username, no stored password). An
  invisible, window-attached `WebView` loads `AuthURL`; once a second `evaluateJavascript(oidcFillScript(...))`
  runs the desktop's field-filling logic as a one-shot function (no page timers), with a TOTP computed per call.
  The script returns the action taken (`password`/`otp`/`next`/``), never a credential. Safeguards: injection
  only on https pages whose host is the IdP's (auth URL) or the controller's (`isInjectableUrl`); `FillGovernor`
  stops after 2 password or 2 OTP submissions and hands over to the browser; WebView navigation is https only,
  mixed content and file/content access are off, cookies are cleared on entry and exit; secrets are read from the
  sealed store on demand and live only in the composition. Not handled: login forms inside iframes, a different
  IdP host than the auth URL's, and TOTP clock-skew correction (the desktop's `fetch_time_offset`).
- Token kept in memory (implemented) and Keystore-encrypted storage (not yet). It is passed to the
  connector per connect and never stored in `SshTarget`/`SessionState`. A cached token is reused unless it is
  within a minute of expiry; any failed Boundary connect drops it. Session status cards
  show `Expiration` and `ConnectionsLeft`, and the app re-authorizes before expiry (not yet implemented).

## Android platform notes

- Foreground service (type `specialUse`) keeps Boundary sessions and shells
  alive in the background.
- Do not exec bundled binaries (W^X on Android 10+). Everything runs in-process.
- Ship arm64-v8a (App Bundle splits if more ABIs are needed). The gomobile AAR
  adds roughly 10–20 MB per ABI. For development only, the AAR can also be built
  with x86_64 (`SCP_TARGETS=android/arm64,android/amd64 go/build-aar.sh`) and the
  debug build type packages it, so debug builds run on x86_64 emulators; release
  builds stay arm64-only.
- Secrets: Keystore-backed AES key (or Tink). Do not use the deprecated Jetpack
  `security-crypto` library.

## Delivery slices

1. **Go core:** `DialDirect`, `OpenShell` with `HostKeyVerifier`,
   `GenerateEd25519`, `Fingerprint`. Tested against an in-process
   `x/crypto/ssh` test server.
2. **Go Boundary (done):** `StartOidc`/`Poll`, `DialBoundary` over an in-memory
   listener. Unit tests use a fake controller; an opt-in integration test runs
   `DialBoundary` → `OpenShell` against `boundary dev`.
3. **App MVP:** backup import, server list, TOFU screen, one terminal screen.
4. Git sync, ics-openvpn AIDL status card, foreground service.

## Testing

- Go: `go test -race ./scpmobile/` in `go/` (strict TDD; failing test first).
  Boundary integration (needs the `boundary` binary and Docker, uses ports
  9200-9202): `SCP_BOUNDARY_DEV=1 go test -race -run TestBoundaryDev -v ./scpmobile/`.
- AAR: `go/build-aar.sh [out-dir]` (default `app/app/libs/`, where the Gradle
  module reads it; `SCP_TARGETS` selects ABIs, default arm64). gomobile/gobind
  are pinned through the `tool` directive in `go/go.mod`. `go/test-build-aar.sh`
  tests the target selection.
- Go: backup decrypt vs the `testdata/` vectors plus hostile-header cases (`go test -race`).
- Kotlin: JUnit unit tests (backup payload parser vs `testdata/backup-v1.plain.json`, server store,
  secret store, import flow, known_hosts store, merge rules); instrumented/Compose UI tests for TOFU and terminal flows.
