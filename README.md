# scp-android

Android companion to SuperControlPanel: SSH terminal to SCP servers (direct dial or Boundary) without Termux.

See [docs/design.md](docs/design.md).

Layout:

- `go/scpmobile/` - Go networking library (Boundary auth/proxy, SSH), bound with gomobile
- `app/` - Kotlin/Compose Android app
- `testdata/` - backup test vectors produced by SCP `scp-core`

## Toolchain

- Go 1.27+ (`~/.local/go`)
- JDK 21 (`JAVA_HOME`, default `~/.local/jdk-21`)
- Android SDK (`ANDROID_HOME`, default `~/Android/Sdk`) with `platforms;android-37.0`,
  `build-tools;37.0.0`, and an NDK (`ndk;30.0.16248370`)

## Build and test

```sh
cd go
go test -race ./scpmobile/   # Go networking core
./build-aar.sh               # -> app/libs/scpmobile.aar (arm64-v8a, minSdk 23)
```
