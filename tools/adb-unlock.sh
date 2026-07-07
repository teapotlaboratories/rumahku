#!/usr/bin/env bash
# Reliably unlock the bench Pixel 6 (oriole) over adb, then optionally launch an app.
#
# The PIN is NOT stored here. It is read from (in order):
#   1) $PIXEL_PIN
#   2) the file in $PIXEL_PIN_FILE (default: ~/.config/rumahku/pixel-pin, mode 600)
# Keep the PIN out of git.
#
# Why this is reliable (the ad-hoc keyevent approach was flaky):
#   - Phone is in GESTURE nav mode, so a swipe near the bottom edge is eaten as a
#     system gesture -> we swipe from MID-screen (y=1600).
#   - The bouncer has a raise animation; digits sent during it are dropped -> we
#     run the whole sequence ON THE DEVICE with real sleeps between steps.
#   - Reset (sleep->wake) each attempt so exactly the PIN's digits are sent
#     (no partial/overflow -> no wrong-PIN lockout).
#   - deviceLocked / screencap read stale -> poll the flag until it settles.
#
# Usage:
#   tools/adb-unlock.sh                 # just unlock
#   tools/adb-unlock.sh <package>       # unlock, then launch <package>
set -u

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
SERIAL="${PIXEL_SERIAL:-192.168.7.229:5555}"
PIN_FILE="${PIXEL_PIN_FILE:-$HOME/.config/rumahku/pixel-pin}"
LAUNCH_PKG="${1:-}"

PIN="${PIXEL_PIN:-}"
if [ -z "$PIN" ] && [ -r "$PIN_FILE" ]; then PIN="$(cat "$PIN_FILE")"; fi
if [ -z "$PIN" ]; then
  echo "No PIN: set \$PIXEL_PIN or create $PIN_FILE" >&2; exit 2
fi

# Map digit chars -> Android keycodes (0=7, 1=8, ... 9=16)
pin_keys() { local s="$1" out=() c; for ((i=0;i<${#s};i++)); do c="${s:i:1}"; out+=($((c + 7))); done; echo "${out[*]}"; }
PIN_KEYS="$(pin_keys "$PIN")"

a() { "$ADB" -s "$SERIAL" "$@"; }
"$ADB" connect "$SERIAL" >/dev/null 2>&1
is_unlocked() { a shell dumpsys trust 2>/dev/null | grep -q 'deviceLocked=0'; }

unlocked=0
for attempt in 1 2 3; do
  # Don't re-lock a device that a previous (undetected) attempt already unlocked.
  if is_unlocked; then echo "UNLOCKED (attempt $attempt)"; unlocked=1; break; fi
  a shell "input keyevent 223; sleep 0.5; input keyevent 224; sleep 0.8; \
           input swipe 540 1600 540 500 200; sleep 1.0; \
           input keyevent $PIN_KEYS; input keyevent 66" >/dev/null 2>&1
  # Poll ~5s (the deviceLocked flag settles to 0 slower than the actual unlock).
  for _ in $(seq 1 12); do is_unlocked && break; a shell 'sleep 0.4' >/dev/null 2>&1; done
  if is_unlocked; then echo "UNLOCKED (attempt $attempt)"; unlocked=1; break; fi
  echo "attempt $attempt failed, retrying..." >&2
done

if [ "$unlocked" != 1 ]; then
  echo "FAILED to unlock after 3 attempts" >&2
  exit 1
fi

if [ -n "$LAUNCH_PKG" ]; then
  a shell monkey -p "$LAUNCH_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  echo "launched $LAUNCH_PKG"
fi
