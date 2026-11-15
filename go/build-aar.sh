#!/usr/bin/env bash
# Build the scpmobile Android AAR with gomobile (version pinned via go.mod `tool`).
# Usage: go/build-aar.sh [output-dir]   (default: app/libs)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here/../app/libs}"

: "${ANDROID_HOME:=$HOME/Android/Sdk}"
: "${JAVA_HOME:=$HOME/.local/jdk-21}"
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
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
  -target=android/arm64 \
  -androidapi 23 \
  -javapkg=run.scp.mobile \
  -o "$out/scpmobile.aar" \
  ./scpmobile
echo "Built $out/scpmobile.aar"
