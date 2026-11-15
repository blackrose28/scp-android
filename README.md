# scp-android

Android companion to SuperControlPanel: SSH terminal to SCP servers (direct dial or Boundary) without Termux.

See [docs/design.md](docs/design.md).

Layout:

- `go/scpmobile/` - Go networking library (Boundary auth/proxy, SSH), bound with gomobile
- `app/` - Kotlin/Compose Android app
- `testdata/` - backup test vectors produced by SCP `scp-core`
