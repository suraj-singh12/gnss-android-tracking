#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
(cd "$repo/command" && go test -race -c -o "$work/command-bridge" ./internal/core)
cd "$repo/android"
GNSS_COMMAND_BRIDGE="$work/command-bridge" ./gradlew --rerun-tasks testDebugUnitTest --tests 'org.gnss.tracking.CommandIntegrationTest' "$@"
