#!/usr/bin/env bash
# Runs a module's iOS tests that need the Keychain or Face ID (classes named *AppHostTest) as an app on a booted
# simulator. Kotlin/Native's own runner spawns a bare binary, which gets no Keychain.
#
# The test binary is linked with an embedded keychain entitlement (-Psinew.iosAppHost), wrapped in a minimal app
# (tool/ios-app-host/Info.plist), installed and launched. Face ID is enrolled first, and a test drives it by printing:
#   SINEW_FACE match | nomatch | enroll | unenroll
# Usage: tool/ios-app-test.sh <module> [simulator-udid]
set -euo pipefail

module="$1"
device="${2:-booted}"
root="$(cd "$(dirname "$0")/.." && pwd)"

"$root/gradlew" -p "$root" ":$module:linkDebugTestIosSimulatorArm64" -Psinew.iosAppHost=true --console=plain -q

app="$root/$module/build/ios-app-host/SinewTests.app"
rm -rf "$app"
mkdir -p "$app"
cp "$root/$module/build/bin/iosSimulatorArm64/debugTest/test.kexe" "$app/SinewTests"
cp "$root/tool/ios-app-host/Info.plist" "$app/Info.plist"
codesign --force --sign - "$app" >/dev/null 2>&1
xcrun simctl install "$device" "$app"

notify() { xcrun simctl spawn "$device" notifyutil "$@" >/dev/null; }
enrolled() { notify -s com.apple.BiometricKit.enrollmentChanged "$1"; notify -p com.apple.BiometricKit.enrollmentChanged; }
enrolled 1

log="$(mktemp)"
trap 'rm -f "$log"' EXIT
xcrun simctl launch --console --terminate-running-process "$device" com.srctool.sinew.tests \
  --ktest_filter='*AppHostTest*' 2>&1 |
  while IFS= read -r line; do
    printf '%s\n' "$line"
    case "$line" in
      *"SINEW_FACE match"*) sleep 1.5; notify -p com.apple.BiometricKit_Sim.pearl.match ;;
      *"SINEW_FACE nomatch"*) sleep 1.5; notify -p com.apple.BiometricKit_Sim.pearl.nomatch ;;
      *"SINEW_FACE unenroll"*) enrolled 0 ;;
      *"SINEW_FACE enroll"*) enrolled 1 ;;
    esac
  done | tee "$log"
enrolled 1

grep -q '\[  PASSED  \]' "$log" && ! grep -q '\[  FAILED  \]' "$log"
