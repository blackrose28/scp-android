#!/usr/bin/env bash
# Build the scpmobile Android AAR with gomobile (version pinned via go.mod `tool`).
# Usage: go/build-aar.sh [output-dir]   (default: app/app/libs, where the Gradle module reads it)
#
# SCP_TARGETS selects the ABIs (comma-separated gomobile targets). Default is the
# release build, arm64 only:
#   go/build-aar.sh                                                    # release: android/arm64
#   SCP_TARGETS=android/arm64,android/amd64 go/build-aar.sh            # dev: also run on x86_64 emulators
# Supported targets: android/arm64, android/amd64. Each extra ABI adds roughly 10-20 MB.
# SCP_DRY_RUN=1 validates the targets and prints them without building (used by test-build-aar.sh).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$(cd "$here/.." && pwd)/app/app/libs}"

targets="${SCP_TARGETS-android/arm64}"
[[ -n "$targets" ]] || { echo "SCP_TARGETS is empty; unset it for the default (android/arm64)" >&2; exit 2; }
# read -a drops a trailing empty field, so reject empty entries explicitly.
[[ ",$targets," != *,,* && "$targets" != *, && "$targets" != ,* ]] \
  || { echo "unsupported target '' (empty entry) in SCP_TARGETS=$targets" >&2; exit 2; }
IFS=, read -r -a target_list <<<"$targets"
for t in "${target_list[@]}"; do
  case "$t" in
    android/arm64 | android/amd64) ;;
    *) echo "unsupported target '$t' in SCP_TARGETS (supported: android/arm64, android/amd64)" >&2; exit 2 ;;
  esac
done

: "${ANDROID_HOME:=$HOME/Android/Sdk}"
: "${JAVA_HOME:=$HOME/.local/jdk-21}"
# Newest version-numbered NDK only; skips legacy leftovers such as android-ndk-r15c.
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/[0-9]* 2>/dev/null | sort -V | tail -1 || true)"
fi

if [[ "${SCP_DRY_RUN:-}" == 1 ]]; then
  echo "targets=$targets"
  echo "out=$out"
  echo "ndk=$ANDROID_NDK_HOME"
  exit 0
fi

[[ -d "$ANDROID_HOME" ]] || { echo "ANDROID_HOME not found: $ANDROID_HOME" >&2; exit 1; }
[[ -d "$ANDROID_NDK_HOME" ]] || { echo "NDK not found under $ANDROID_HOME/ndk" >&2; exit 1; }
[[ -x "$JAVA_HOME/bin/javac" ]] || { echo "JDK not found: $JAVA_HOME" >&2; exit 1; }
export ANDROID_HOME ANDROID_NDK_HOME JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "$out"
cd "$here"
# gomobile looks up gobind on PATH; install the pinned version into a private bin dir.
bin="$(mktemp -d)"
trap 'rm -rf "$bin"' EXIT
GOBIN="$bin" go install golang.org/x/mobile/cmd/gobind
export PATH="$bin:$PATH"
go tool gomobile bind \
  -target="$targets" \
  -androidapi 23 \
  -javapkg=run.scp.mobile \
  -o "$out/scpmobile.aar" \
  ./scpmobile
echo "Built $out/scpmobile.aar ($targets)"
