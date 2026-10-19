#!/usr/bin/env bash
# Runs the Android device tests module by module on one device, printing each test as it starts and ends (✓ passed,
# ✗ failed, – skipped: the biometric tests skip unless tool/android-biometric-test.sh runs them), then the module's
# result. The same tests as CI's android-device job. The device must be unlocked since boot: a locked one with a PIN
# refuses to start the test app ("not encryption aware").
# Usage: tool/android-device-test.sh [device-id] [module…]
set -uo pipefail

device="${1:-emulator-5554}"
shift || true
if [[ $# -gt 0 ]]; then modules=("$@"); else modules=(sinew-security sinew-storage sinew-network); fi
root="$(cd "$(dirname "$0")/.." && pwd)"
adb=(adb -s "$device")
export ANDROID_SERIAL="$device"

failed=()
for module in "${modules[@]}"; do
  printf '\n=== %s: building and installing…\n' "$module"
  "${adb[@]}" logcat -c
  (
    "${adb[@]}" logcat -v raw -s TestRunner:I |
      while IFS= read -r line; do
        case "$line" in
          started:*) printf '  … %s\n' "${line#started: }"; outcome="" ;;
          failed:*) printf '  ✗ FAILED %s\n' "${line#failed: }"; outcome=failed ;;
          "assumption failed:"*) printf '  – skipped %s\n' "${line#assumption failed: }"; outcome=skipped ;;
          finished:*) [[ -z "${outcome:-}" ]] && printf '  ✓ %s\n' "${line#finished: }" ;;
          "run finished"*) printf '  %s\n' "$line" ;;
        esac
      done
  ) &
  watcher=$!
  disown "$watcher"                                         # no "Terminated" notice when it's stopped
  if "$root/gradlew" -p "$root" ":$module:connectedAndroidDeviceTest" --console=plain -q >"/tmp/sinew-$module-device.log" 2>&1; then
    result="PASSED"
  else
    result="FAILED (log: /tmp/sinew-$module-device.log)"
    failed+=("$module")
  fi
  sleep 1
  pkill -P "$watcher" 2>/dev/null; kill "$watcher" 2>/dev/null
  printf '=== %s: %s\n' "$module" "$result"
done

printf '\n'
if [[ ${#failed[@]} -eq 0 ]]; then echo "All modules passed."; else echo "Failed: ${failed[*]}"; exit 1; fi
