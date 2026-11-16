#!/usr/bin/env bash
# Tests for build-aar.sh target selection. Uses SCP_DRY_RUN=1, which validates the
# target list and prints it without needing the Android SDK, NDK or JDK.
# Usage: go/test-build-aar.sh
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/build-aar.sh"
fails=0

# check NAME EXPECTED_EXIT EXPECTED_STDOUT_SUBSTRING EXPECTED_STDERR_SUBSTRING [ENV=VAL...]
# Set script_args=(...) before a call to pass arguments to build-aar.sh; it is reset afterwards.
script_args=()
check() {
  local name="$1" want_rc="$2" want_out="$3" want_err="$4"
  shift 4
  local out err rc
  err="$(mktemp)"
  out="$(env -u SCP_TARGETS SCP_DRY_RUN=1 "$@" "$script" "${script_args[@]}" 2>"$err")"
  rc=$?
  if [[ $rc -ne $want_rc ]] \
    || [[ -n "$want_out" && "$out" != *"$want_out"* ]] \
    || [[ -n "$want_err" && "$(cat "$err")" != *"$want_err"* ]]; then
    echo "FAIL $name: rc=$rc (want $want_rc) stdout=[$out] stderr=[$(cat "$err")]"
    fails=$((fails + 1))
  else
    echo "ok   $name"
  fi
  rm -f "$err"
  script_args=()
}

# The default output must be the directory the Gradle module reads (app/app/libs, see
# `files("libs/scpmobile.aar")` in app/app/build.gradle.kts).
check "default output dir is the app module libs" 0 "out=$(cd "$here/.." && pwd)/app/app/libs" ""
script_args=(/tmp/custom-out)
check "explicit output dir" 0 "out=/tmp/custom-out" ""

# NDK auto-detection must pick the newest version-numbered NDK, not a legacy directory such as
# android-ndk-r15c (which sorts last by name).
fake_sdk="$(mktemp -d)"
trap 'rm -rf "$fake_sdk"' EXIT
mkdir -p "$fake_sdk"/ndk/{android-ndk-r15c,16.1.4479499,21.0.6113669,30.0.14904198}
check "ndk autodetect ignores legacy dirs" 0 "ndk=$fake_sdk/ndk/30.0.14904198" "" ANDROID_HOME="$fake_sdk"
check "explicit ANDROID_NDK_HOME wins" 0 "ndk=/opt/my-ndk" "" ANDROID_HOME="$fake_sdk" ANDROID_NDK_HOME=/opt/my-ndk

# Release default must stay arm64-only.
check "default is arm64 only" 0 "targets=android/arm64" ""
check "explicit arm64" 0 "targets=android/arm64" "" SCP_TARGETS=android/arm64
check "dev dual ABI" 0 "targets=android/arm64,android/amd64" "" SCP_TARGETS=android/arm64,android/amd64
check "emulator only" 0 "targets=android/amd64" "" SCP_TARGETS=android/amd64
check "unknown target rejected" 2 "" "unsupported target" SCP_TARGETS=android/arm
check "ios rejected" 2 "" "unsupported target" SCP_TARGETS=ios/arm64
check "empty entry rejected" 2 "" "unsupported target" SCP_TARGETS=android/arm64,
check "empty value rejected" 2 "" "SCP_TARGETS is empty" SCP_TARGETS=

[[ $fails -eq 0 ]] || { echo "$fails failed" >&2; exit 1; }
echo "all passed"
