# Issue #6 integrated UI acceptance

## Source and compatibility

Dedicated branch `issue-6-integrated-ui` starts directly from combined PR #12
`b23b5f58845c896e7fb2c5800e577202667c1e3f`, including PR #11 native GNSS/history.
No dependency PR is merged or rewritten. Android application `org.gnss.tracking`
version 0.1, minimum Android 26, target SDK 35; Command Protocol v1 plus existing
negotiated native-history extension. UI changes do not alter wire or persistence formats.
Use all three applications from one Actions manifest source revision.

Previous frozen comparison build remains Actions run
[37977149909](https://github.com/suraj-singh12/gnss-android-tracking/actions/runs/37977149909),
source `b23b5f5`; preserve its downloaded files and evidence before replacing apps.
Do not uninstall a phone with outstanding data. Existing acceptance source is unchanged.
The three original Actions artifacts were verified present and unexpired during this
implementation; their current expiry is 23 October 2026. Archive them before that date
if they are not already saved. Their runs, branches and binaries were not replaced.

## Matched builds and checksums

Workflow `.github/workflows/issue-6-matched-assets.yml` runs on the dedicated branch,
checks ancestry, Android tests/lint/build, Go race/vet/protocol checks, actual cross-language
integration and Chromium interactions, then produces one matched artifact package:

- `app-debug.apk` — debug signed Android application.
- `gnss-command-darwin-arm64` — CGO-free macOS Apple Silicon executable.
- `gnss-command-windows-amd64.exe` — CGO-free Windows AMD64 executable.
- `manifest.json`, `SHA256SUMS`, `SETUP.md` and visual evidence.

Exact application hashes are emitted in the successful job log and manifest. APK DEX
and Go VCS metadata are verified against `GITHUB_SHA`. Artifact ZIP hashes are separate
from the extracted application hashes. A failed or pending run is not a matched build.

## Setup and launch

1. Download and extract the matched package from the successful Actions run. Check
   each extracted application against `SHA256SUMS` (`sha256sum` on Linux,
   `shasum -a 256` on macOS, `Get-FileHash -Algorithm SHA256` on Windows).
2. macOS: `chmod +x gnss-command-darwin-arm64`, then
   `./gnss-command-darwin-arm64`. Windows: run
   `.\gnss-command-windows-amd64.exe` from PowerShell. Command uses a persistent
   SQLite file in the OS user configuration directory; `-db` selects another file.
3. Open [local Command](http://127.0.0.1:8081) in a browser. Phone ingestion listens
   on port 8080. Permit that inbound port on the trusted isolated LAN firewall.
4. Install the Android debug APK through the device's supported package installer,
   retaining existing data/signing compatibility. Configure Party ID/name and
   `http://<Command-LAN-IP>:8080` in Settings. Set local reporting interval; Save.
5. Tracking → Start Tracking. Follow precise location, notifications, GPS and power
   guidance. Verify GNSS separately from Command contact, then lock the phone.
6. Command → choose Recording start mode, Start Recording. Use party selection,
   visibility, Fit/Focus and quality settings. Stop/Resume retains original boundaries;
   Clear requires confirmation and retains raw observations/settings/SOS.
7. Map & layers → optionally load RFC 7946 `.geojson`. Inspect CRS/coverage, point
   coordinates and background opacity. Blank canvas needs no map. Missing, incompatible
   or corrupt maps fall back without affecting GNSS data.
8. Enable/test Command audible SOS and check speakers. Phone SOS requires deliberate
   hold; the optional physical shortcut is foreground-only. Command receipt and human
   acknowledgement are separate. Acknowledge the event explicitly on Command.

## Reproduce automated checks

```sh
(cd command && go test -race ./... && go vet ./...)
(cd test-tools && go test -race ./... && go vet ./...)
(cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug)
test-tools/integration/run.sh --console=plain
node test-tools/integration/map-test.cjs
```

Browser checks require externally installed test-only Playwright and axe-core/playwright;
set `GNSS_COMMAND_BINARY`, `GNSS_PLAYWRIGHT_MODULE`, `GNSS_AXE_MODULE`, optionally
`GNSS_CHROMIUM`, and `GNSS_SCREENSHOT_DIR`, then run `sos-dashboard.cjs` and
`ui-dashboard.cjs` under `test-tools/integration`. They use the actual embedded
Command frontend/server and real HTTP/read-model state with synthetic protocol fixtures.
Android `UiNavigationVisualTest` renders the actual native Activity with Room flows and
Robolectric native Skia, with synthetic operational telemetry. This is equivalent
software rendering evidence, not an emulator or attached physical device.
The full Android suite has 180 cases: 155 ordinary cases and 25 opt-in cross-language
cases. The separate integration step executes those 25 against the actual Go core;
their being skipped in the ordinary suite is intentional. The Actions test-report
artifact retains both the full-suite reports and the separate integration reports.

## Physical acceptance remains required

Use [Android device acceptance](../android/DEVICE-ACCEPTANCE.md) for native GNSS,
GPSTest A/B stall evidence, screen off/Doze/OEM power, real Wi-Fi recovery, persistence,
backlog recovery and SOS priority. UI changes do not resolve the known physical GNSS
stall or expand locked-screen key privileges. Verify speaker audibility on both target
OSes, installer/debug-signature compatibility, actual map alignment with surveyed
coordinates, font scaling/TalkBack on device, and the document export picker.

Retain matched APK/Command diagnostic ZIPs, map provenance and screenshots. No physical
pass, issue closure, release or merge is implied by automated software results.

## Parallel physical-button experiment

The Issue #6 UI does not depend on the physical-button experiment. Diagnostics
provides `R.id.diagnostic_tools` for later insertion of its tested Physical Button
Test controls. No key detector or SOS-engine change is introduced by the redesign.
After both branches are ready, deliberately integrate the tested experiment and rerun
Android, GNSS/history and SOS regressions before physical acceptance. Keep both PRs
unmerged pending operator approval; screen-off behavior requires device testing.
