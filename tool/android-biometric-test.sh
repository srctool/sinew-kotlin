#!/usr/bin/env bash
# Runs sinew-security's biometric device tests, answering the prompts the tests ask for in logcat (tag
# SinewBiometricTest). On an emulator (a PIN of 1111 and finger 1 enrolled) the script answers them itself; on a phone
# (--device) it tells you what to do, and never touches the lock screen or your fingerprints.
#   TOUCH <n>  touch the sensor with finger n once the prompt shows (1 is enrolled, 2 isn't)
#   BACK       press Back once the prompt shows, dismissing it
#   SKIP       the prompt still showing (one the app cancelled) isn't to be answered
#   ENROLL     enroll another finger in Settings (only with --enroll); the emulator is restored to one finger after
# One-time emulator setup: `adb shell locksettings set-pin 1111`, then enroll finger 1 (Settings > Security >
# Fingerprint, touching with `adb emu finger touch 1`). The Settings taps assume a 1080x2400 screen (Medium Phone).
# Usage: tool/android-biometric-test.sh [--enroll] [--device] [device-id] [Gradle arguments…]
#   e.g. -Pandroid.testInstrumentationRunnerArguments.class=com.srctool.sinew.security.AndroidBiometricVaultDeviceTest#disableDeletesTheSecretAndItsKey
set -euo pipefail

enroll=false
phone=false
while [[ "${1:-}" == --* ]]; do
  case "$1" in
    --enroll) enroll=true ;;
    --device) phone=true ;;
  esac
  shift
done
device="${1:-emulator-5554}"
shift || true
export ANDROID_SERIAL="$device"           # the device the Gradle run uses
root="$(cd "$(dirname "$0")/.." && pwd)"
adb=(adb -s "$device")

say() { printf '\n>>> %s\n' "$*"; }

touch_finger() { "${adb[@]}" emu finger touch "$1" >/dev/null; }


# The focused prompt window's id (Window{<id> …}), empty when no prompt has focus.
prompt_window() { "${adb[@]}" shell dumpsys window | sed -n 's/.*mCurrentFocus=Window{\([0-9a-f]*\) .*BiometricPrompt.*/\1/p' | head -1; }

# Prompts come one after another, and a closing one keeps focus for a moment: each action waits (up to 60 s) for a
# prompt window other than the last one answered. A touch before a prompt shows is lost.
answered=""

await_new_prompt() {
  local window=""
  for _ in $(seq 1 120); do
    window="$(prompt_window)"
    [[ -n "$window" && "$window" != "$answered" ]] && break
    sleep 0.5
  done
  sleep 0.5
  printf '%s' "$window"
}

# An enrolled finger: touch until this prompt is gone, then count it answered.
answer_with_finger() {
  local window
  window="$(await_new_prompt)"
  for _ in $(seq 1 5); do
    touch_finger "$1"
    sleep 1.5
    [[ "$(prompt_window)" == "$window" ]] || break
  done
  answered="$window"
}

# A finger that isn't enrolled: the prompt stays up, still unanswered.
touch_unknown_finger() {
  await_new_prompt >/dev/null
  touch_finger "$1"
  sleep 1
}

answer_with_back() {
  answered="$(await_new_prompt)"
  "${adb[@]}" shell input keyevent 4
  sleep 1
}

# grep reads everything: with -q it would stop at the match, and pipefail would count dumpsys's broken pipe as a miss.
focused() { "${adb[@]}" shell dumpsys window | grep -E "mCurrentFocus=.*($1)" >/dev/null; }

# Waits up to 60 s for a window whose name matches $1 (an extended regex).
await_focus() { for _ in $(seq 1 120); do focused "$1" && return 0; sleep 0.5; done; return 1; }

# Fingerprint enrollment in Settings: confirm the PIN, accept the intro, and touch until the print count goes up.
enroll_finger() {
  local before
  before="$(prints)"
  # A Settings task left in the background would be brought to the front instead of a new enrollment.
  "${adb[@]}" shell am force-stop com.android.settings
  "${adb[@]}" shell am start -a android.settings.FINGERPRINT_ENROLL >/dev/null
  await_focus ConfirmLockPassword
  "${adb[@]}" shell input text 1111
  "${adb[@]}" shell input keyevent 66
  await_focus FingerprintEnrollment
  sleep 2
  "${adb[@]}" shell input tap 912 2242      # "I agree" first scrolls the terms to the end
  sleep 2
  "${adb[@]}" shell input tap 912 2242      # "I agree"
  # A touch before the sensor screen shows is lost, and a loaded emulator can take a while to show it.
  await_focus 'FingerprintEnrollFindSensor|FingerprintEnrollEnrolling'
  sleep 1
  for _ in $(seq 1 40); do
    touch_finger "$1"
    sleep 1.5
    [[ "$(prints)" -gt "$before" ]] && break
  done
  "${adb[@]}" shell input keyevent 3        # home
}

prints() { "${adb[@]}" shell dumpsys fingerprint | grep -o '"count":[0-9]*' | head -1 | cut -d: -f2; }

# Back to one enrolled finger: removing the PIN deletes every print, then the PIN and finger 1 are set up again.
restore_single_finger() {
  "${adb[@]}" shell locksettings clear --old 1111 >/dev/null
  "${adb[@]}" shell locksettings set-pin 1111 >/dev/null
  enroll_finger 1
}

if [[ "$phone" == true ]]; then
  say "Keep the phone unlocked with its screen on until the run ends."
else
# The PIN turns on the lock screen: keep the screen on and unlocked, or the prompt can't show. "Stay on" only holds
# while charging, so the emulator is told it's on AC, and the screen timeout is made as long as possible.
"${adb[@]}" shell dumpsys battery set ac 1
"${adb[@]}" shell settings put system screen_off_timeout 2147483647
"${adb[@]}" shell svc power stayon true
"${adb[@]}" shell input keyevent 224      # wake up
"${adb[@]}" shell wm dismiss-keyguard
sleep 1
# grep reads everything, as in focused().
if "${adb[@]}" shell dumpsys window | grep -E "mDreamingLockscreen=true|isKeyguardShowing=true|mShowingLockscreen=true" >/dev/null; then
  "${adb[@]}" shell input text 1111
  "${adb[@]}" shell input keyevent 66
  sleep 1
fi
fi

"${adb[@]}" logcat -c
(
  "${adb[@]}" logcat -v raw -s SinewBiometricTest:I |
    while IFS= read -r line; do
      if [[ "$phone" == true ]]; then
        # A person reacts to the prompt itself, and OEM prompt windows aren't all named BiometricPrompt.
        case "$line" in
          "TOUCH 1") say "When the prompt shows, touch the sensor with an ENROLLED finger" ;;
          "TOUCH "*) say "When the prompt shows, touch ONCE with a finger that is NOT enrolled, then wait"; sleep 10 ;;
          BACK) "${adb[@]}" shell input keyevent 4 ;;
          SKIP) say "Don't touch: the app cancels that prompt itself" ;;
          ENROLL) say "Now add ANOTHER fingerprint: Settings > Security and privacy > Biometrics > Fingerprints > Add (you have 3 minutes), then come back to the test screen. You can delete it after the run." ;;
        esac
        continue
      fi
      case "$line" in
        "TOUCH 1") answer_with_finger 1 ;;
        "TOUCH "*) touch_unknown_finger "${line#TOUCH }" ;;
        BACK) answer_with_back ;;
        SKIP) window="$(prompt_window)"; [[ -n "$window" ]] && answered="$window" ;;
        ENROLL) enroll_finger 2 ;;
      esac
    done
) &
watcher=$!
trap 'pkill -P "$watcher" 2>/dev/null; kill "$watcher" 2>/dev/null; true' EXIT

status=0
"$root/gradlew" -p "$root" :sinew-security:connectedAndroidDeviceTest --console=plain \
  -Pandroid.testInstrumentationRunnerArguments.sinewBiometrics=true \
  -Pandroid.testInstrumentationRunnerArguments.sinewEnroll="$enroll" "$@" || status=$?

if [[ "$phone" == false && "$enroll" == true && "$(prints)" != "1" ]]; then restore_single_finger; fi
exit "$status"
