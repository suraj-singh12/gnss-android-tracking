#!/usr/bin/env bash
# CI wrapper: capture the real debug APK on a booted emulator in Day/Night at 1.0 and 1.5
# font scale. Output: $OUT/<appearance>-<scale>-<screen>.png/.xml plus device.txt.
set -euo pipefail
cd "$(dirname "$0")/.."
export OUT="${OUT:-build/ui-inspect}"
mkdir -p "$OUT"
I=tools/ui-inspect.sh
{
  adb shell getprop ro.build.version.release
  adb shell getprop ro.product.model
  adb shell wm size
  adb shell wm density
} > "$OUT/device.txt"
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
status=0
for scale in 1.0 1.5; do
  $I font "$scale"
  $I tour "day-$scale-" || status=1
  $I night
  $I tour "night-$scale-" || status=1
  $I night
done
$I font 1.0
adb logcat -d -t 2000 > "$OUT/logcat.txt" || true
exit "$status"
