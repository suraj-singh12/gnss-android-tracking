#!/usr/bin/env bash
# Live UI inspection helper for the Android app on an emulator or USB device.
#
# Drives the real debug APK through adb: install/launch, navigate by visible label,
# capture screenshots and UI Automator hierarchy dumps (actual on-screen bounds), and
# switch font scale / display size / app appearance. See android/UI-DEVELOPMENT.md.
#
# Usage: android/tools/ui-inspect.sh <command> [args]
#   install                 Build (if needed) and install the debug APK
#   launch                  Force-stop and start MainActivity
#   shot <name>             Save $OUT/<name>.png (screencap) and $OUT/<name>.xml (bounds)
#   tap <label>             Tap the first node whose text or content-desc starts with <label>
#   back                    Press Back
#   scroll                  Scroll the main content down one page
#   font <scale>            Set system font scale (e.g. 1.0, 1.3, 1.5, 2.0)
#   size <WxH> <dpi>|reset  Override display size and density (e.g. 1080x2400 420)
#   night                   Toggle the app's own Day/Night control
#   tour [prefix]           Capture every destination and nested page at current settings
#   bounds <name>           Print node class, text and bounds from $OUT/<name>.xml
#
# Environment: OUT (default android/build/ui-inspect), ADB (default adb), SERIAL (adb -s).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${OUT:-$HERE/build/ui-inspect}"
ADB_BIN="${ADB:-adb}"
PKG=org.gnss.tracking
mkdir -p "$OUT"

adb_() { if [[ -n "${SERIAL:-}" ]]; then "$ADB_BIN" -s "$SERIAL" "$@"; else "$ADB_BIN" "$@"; fi; }

settle() { sleep "${SETTLE:-1.2}"; }

dump() { # $1 = output xml path
  adb_ shell uiautomator dump /sdcard/gnss-ui.xml >/dev/null
  adb_ pull /sdcard/gnss-ui.xml "$1" >/dev/null
}

shot() {
  local name="$1"
  settle
  adb_ exec-out screencap -p > "$OUT/$name.png"
  dump "$OUT/$name.xml"
  echo "captured $OUT/$name.png"
}

node_center() { # $1 = xml, $2 = label prefix
  python3 - "$1" "$2" <<'PY'
import re, sys, xml.etree.ElementTree as ET
xml, label = sys.argv[1], sys.argv[2]
nodes = list(ET.parse(xml).iter('node'))
# Prefer an exact clickable label (e.g. the "Tracking" tab), then a clickable prefix
# match (rows whose description is "Title. Subtitle. Open"), then any prefix match.
def pick():
    for exact, clickable in ((True, True), (False, True), (False, False)):
        for n in nodes:
            text, desc = n.get('text', ''), n.get('content-desc', '')
            hit = label in (text, desc) or desc.startswith(label + ",") if exact else (text.startswith(label) or desc.startswith(label))
            if hit and (not clickable or n.get('clickable') == 'true'):
                return n
node = pick()
if node is None:
    sys.exit(f"no visible node labelled '{label}'")
l, t, r, b = map(int, re.findall(r'\d+', node.get('bounds')))
print((l + r) // 2, (t + b) // 2)
PY
}

scroll_down() {
  local w h
  read -r w h < <(adb_ shell wm size | sed -E 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/' | tail -1)
  adb_ shell input swipe $((w / 2)) $((h * 6 / 10)) $((w / 2)) $((h * 3 / 10)) 400
  settle
}

tap() { # Off-screen rows are absent from the dump; scroll down up to three times.
  local tmp="$OUT/.tap.xml" pos="" i
  for i in 0 1 2 3; do
    dump "$tmp"
    if pos="$(node_center "$tmp" "$1" 2>/dev/null)"; then break; fi
    pos=""; scroll_down
  done
  [[ -n "$pos" ]] || { echo "no visible node labelled '$1'" >&2; return 1; }
  adb_ shell input tap $pos
  settle
}

cmd="${1:-help}"; shift || true
case "$cmd" in
  install)
    if [[ ! -f "$HERE/app/build/outputs/apk/debug/app-debug.apk" ]]; then (cd "$HERE" && ./gradlew assembleDebug --console=plain); fi
    adb_ install -r -g "$HERE/app/build/outputs/apk/debug/app-debug.apk"
    ;;
  launch)
    adb_ shell am force-stop "$PKG"
    adb_ shell am start -W -n "$PKG/.MainActivity" >/dev/null
    settle
    ;;
  shot) shot "${1:?name}" ;;
  tap) tap "${1:?label}" ;;
  back) adb_ shell input keyevent KEYCODE_BACK; settle ;;
  scroll) scroll_down ;;
  font) adb_ shell settings put system font_scale "${1:?scale}"; settle ;;
  size)
    if [[ "${1:-}" == reset ]]; then adb_ shell wm size reset; adb_ shell wm density reset
    else adb_ shell wm size "${1:?WxH}"; adb_ shell wm density "${2:?dpi}"; fi
    settle
    ;;
  night)
    tmp="$OUT/.tap.xml"; dump "$tmp"
    if node_center "$tmp" "Switch to Night mode" >/dev/null 2>&1; then tap "Switch to Night mode"; else tap "Switch to Day mode"; fi
    ;;
  bounds)
    python3 - "$OUT/${1:?name}.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter('node'):
    label = n.get('text') or n.get('content-desc')
    if label: print(f"{n.get('bounds'):>24}  {n.get('class').split('.')[-1]:<14} {label[:60]}")
PY
    ;;
  tour)
    p="${1:-}"
    "$0" launch
    shot "${p}tracking"; "$0" scroll; shot "${p}tracking-bottom"
    tap Settings; shot "${p}settings"
    for row in "Party & Command" "Command access" "Field readiness" "Tracking policy" "Emergency controls"; do
      tap "$row"; shot "${p}settings-$(echo "$row" | tr 'A-Z &' 'a-z--' | tr -s '-')"; "$0" back
    done
    tap Diagnostics; shot "${p}diagnostics"; "$0" scroll; shot "${p}diagnostics-bottom"
    for row in "Command Instructions" "Phone alerts" "Saved data & sync" "SOS history" "GNSS details" "Command contact" "Diagnostic tools"; do
      tap "Tracking"; tap "Diagnostics"   # fresh root at its top scroll position
      tap "$row"; shot "${p}diagnostics-$(echo "$row" | tr 'A-Z &' 'a-z--' | tr -s '-')"; "$0" back
    done
    tap Tracking
    ;;
  *) sed -n '2,25p' "$0" ;;
esac
