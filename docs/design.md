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
- No Rust in the app. The backup decrypt is ported to Kotlin and verified against
  test vectors produced by `scp-core` (`testdata/`).

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
(*OidcPending).Poll() (*OidcResult, error) // Done(), Token()

// Transport
DialDirect(host string, port int, timeoutMs int) (*Conn, error)
DialBoundary(controllerURL, token, targetID string) (*BoundarySession, error)
BoundarySession: Conn(), ExpirationUnixMs(), ConnectionsLeft(), Close()

// SSH
OpenShell(conn *Conn, user string, privateKey []byte, alias string,
          hk HostKeyVerifier, out OutputSink, cols, rows int) (*Shell, error)
Shell: Write([]byte), Resize(cols, rows), Close()

// Keys
GenerateEd25519() (*KeyPair, error) // PrivateOpenSSH, PublicAuthorizedKey
Fingerprint(authorizedKey []byte) (string, error) // "SHA256:…"
```

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
- **Backup import:** SAF file picker + passphrase. Format v1:
  Argon2id (`argon2id-19`) + AES-256-GCM, header checked for
  `version == 1`, `kdf == "argon2id-19"`, `cipher == "aes-256-gcm"`
  (see SCP `crates/scp-core/src/backup/crypto.rs`). Supplies servers plus
  secrets (OIDC username/password/OTP secret) into Keystore.
- Merge rule: Git sync wins for shared data; backup supplies secrets.
- Device-local desktop fields (executable paths, Caddy, MCP) are ignored.

## Boundary OIDC

- Browser mode: `StartOidc` → open `AuthURL` in Custom Tabs → `Poll` until token.
- Automated mode: hidden `WebView` + `evaluateJavascript` fill script
  (username, password, TOTP), mirroring the desktop
  `boundary_oidc_init_script`.
- Token kept in memory and Keystore-encrypted storage. Session status cards
  show `Expiration` and `ConnectionsLeft`, and the app re-authorizes before expiry.

## Android platform notes

- Foreground service (type `specialUse`) keeps Boundary sessions and shells
  alive in the background.
- Do not exec bundled binaries (W^X on Android 10+). Everything runs in-process.
- Ship arm64-v8a (App Bundle splits if more ABIs are needed). The gomobile AAR
  adds roughly 10–20 MB per ABI.
- Secrets: Keystore-backed AES key (or Tink). Do not use the deprecated Jetpack
  `security-crypto` library.

## Delivery slices

1. **Go core:** `DialDirect`, `OpenShell` with `HostKeyVerifier`,
   `GenerateEd25519`, `Fingerprint`. Tested against an in-process
   `x/crypto/ssh` test server.
2. **Go Boundary:** `StartOidc`/`Poll`, `DialBoundary` over an in-memory
   listener. Tested against a dev Boundary (`boundary dev`).
3. **App MVP:** backup import, server list, TOFU screen, one terminal screen.
4. Automated OIDC, Git sync, ics-openvpn AIDL status card, foreground service.

## Testing

- Go: `go test ./...` in `go/` (strict TDD; failing test first).
- Kotlin: JUnit unit tests (backup decrypt vs `testdata/` vectors, known_hosts
  store, merge rules); instrumented/Compose UI tests for TOFU and terminal flows.
