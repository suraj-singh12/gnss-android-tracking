#!/bin/sh
# Launch the offline MapLibre POC (loopback only) and open it in the default browser.
# Usage: maplibre/launch.sh [bundle-dir] [port]
BUNDLE="${1:-$HOME/gnss-poc-88-work/maplibre-bundle}"
PORT="${2:-8770}"
cd "$(dirname "$0")" || exit 1
[ -d "$BUNDLE/tiles" ] || { echo "Bundle not found: $BUNDLE (build it with maplibre/build.py)"; exit 1; }
python3 serve.py --bundle "$BUNDLE" --port "$PORT" &
PID=$!
sleep 1
open "http://127.0.0.1:$PORT/" 2>/dev/null || xdg-open "http://127.0.0.1:$PORT/" 2>/dev/null
echo "Serving on http://127.0.0.1:$PORT/  (Ctrl+C to stop)"
trap 'kill $PID' INT TERM
wait $PID
