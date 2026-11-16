# scp-android

Android companion to SuperControlPanel: SSH terminal to SCP servers (direct dial or Boundary) without Termux.

See [docs/design.md](docs/design.md).

Layout:

- `go/scpmobile/` - Go networking library (Boundary auth/proxy, SSH), bound with gomobile
- `app/` - Kotlin/Compose Android app (see `app/README.md`)
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
./build-aar.sh               # release AAR -> app/app/libs/scpmobile.aar (arm64-v8a only, minSdk 23)
SCP_TARGETS=android/arm64,android/amd64 ./build-aar.sh   # dev AAR: adds x86_64 for emulators
./test-build-aar.sh          # tests for build-aar.sh target selection
```

Release builds are arm64 only. For development, build the dual-ABI AAR; the debug APK then also runs on
x86_64 emulators, while the release APK stays arm64 only regardless of which AAR is in `app/app/libs/`.

Agents: see [AGENTS.md](AGENTS.md). Sibling repos `../supercontrolpanel` and `../ics-openvpn` are expected next to this one.

## Code intelligence

[Ripwire](https://github.com/redhat-et/ripwire) is used for local code navigation and impact analysis (dev tool only). See the "Code intelligence (Ripwire)" section in [AGENTS.md](AGENTS.md) for install and usage, e.g. `ripwire . --for="SSH shell lifecycle" --legend=compact`.
