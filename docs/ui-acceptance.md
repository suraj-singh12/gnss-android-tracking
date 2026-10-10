# Issue #6 integrated UI acceptance

## Automatic DEM follow-up on PR #18

`feature/issue6-automatic-dem` starts at verified PR #18 SHA
`4d676aef51b61fd9d981d2f44bb0a10babafe7b3`, targeting
`feature/issue6-terrain-appearance`. No Android/GNSS/SOS/recording engine redesign.
Existing PRs/issues stay open and unmerged. Source ancestry and frozen build SHA
are recorded in the focused PR and Actions manifest.

Enter/search a geographic centre, retain default 500×500 m or choose bounds,
optionally select hillshade/contours/elevation, Preview and Download for Offline Use.
Vectors save first; AWS Open Data Skadi acquisition then uses the existing HGT
pipeline. Failure warns and retains vectors/previous terrain. Select the saved map
and **Download / retry selected terrain** without duplicating vectors. Layers start
OFF and toggle locally; provenance/attribution/datum/spacing survive restart/export.
Provider coverage/credentials/pricing/quotas/limits are in Command README.

Real acceptance: `dem-acquisition.cjs` downloads/decodes Mussoorie
(30.4598,78.0644); `dem-offline-acceptance.cjs` uses the actual OSM/DEM downloader UI,
checks centre bounds and every saved sample against independently downloaded HGT,
checks rendered shade pixel centres/contour coordinates/pointer readouts, then
restarts Command with provider networking blocked. Failed offline retry must retain
the exact terrain export. Actual screenshots and JSON are Actions evidence.
This verifies georeferencing, not surveyed accuracy or physical field acceptance.

```sh
GNSS_COMMAND_BINARY=/path/to/command GNSS_PLAYWRIGHT_MODULE=/path/to/playwright \
GNSS_TERRAIN_PROVIDER_REPORT=/path/to/report.json \
node test-tools/integration/dem-offline-acceptance.cjs
```

## Historical PR #18 terrain/appearance refinement on PR #17

Branch `feature/issue6-terrain-appearance` starts at verified PR #17 SHA
`9eee532869c3fdcc258bcaa4bc51ccc0f5eecbe7`, targeting
`correction/issue6-operational-ux`. Existing PRs remain open/unmerged.

Native Android Day/Night uses early Activity configuration and semantic resources,
with Settings selection and independent persistence. Command themes every surface;
header contact/count sits under identity, About beside SOS, and Start requires a
mode modal defaulting Current Time. Existing actions/engines are preserved.

Optional terrain supports real operator-supplied square SRTM HGT and bounded binary
export/re-import. One DEM subset supplies selected shading/contours/elevation; local
SQLite migration and map deletion preserve original vector bytes and authoritative
tracking state. A small nonmodal Layers popover toggles availability/OFF/ON locally.
Provider-independent fixture processing, real local storage and offline restart are
distinct from real DEM acquisition. Direct candidate requests here returned proxy
CONNECT HTTP 403. That baseline did not implement automatic acquisition. The
follow-up above uses the separately verified AWS Skadi source, not those candidates.

Run `node test-tools/integration/terrain-test.cjs` alongside existing suites.
`ui-dashboard.cjs` now exercises default/cancel/error/both recording modes, exact
About content, header placement, terrain availability/defaults/layer order, unchanged
view/selection/distance, local DEM upload, Night persistence and Command restart with
provider requests denied. Native visual tests render both themes at 360/480 dp and
1.5× font scale, retaining all Physical Button and SOS assertions. Synthetic HGT is
labelled as fixture evidence in screenshots, not real provider terrain.

Actual screenshots for this refinement are saved under `shared/terrain-before`,
`shared/terrain-after` and `shared/terrain-android`, with final same-source after
renders uploaded through the reused workflow. Screenshot-first audit found the
permanent selector/status placement to change; render inspection tightened layer
spacing/Night contrast. Detailed passes/skips/failures, final SHA/CI/artifacts and
signing compatibility are recorded in the new PR only after verification.

Initial focused regression failures were corrected in their owning layers: native
theme override moved to attachBaseContext before resource access; session availability
exposed read-only by the existing compact dashboard view; isolated renderer test
fixture declares optional terrain/theme state. No assertion weakening/test exclusion.
Native visual GPS fixtures update the Activity's themed service shadow as well as
the app shadow. Appearance is above the connection form. Raster cell centres align
with projected DEM nodes; contour complexity is bounded without hiding other products.

Physical device/OEM Wi-Fi/locked GNSS/SOS/speaker/TalkBack/picker and genuine DEM
geographic alignment remain acceptance requirements. Existing live OSM providers can
also fail independently of offline regression/builds. GeoTIFF/mosaics unsupported.

## Source and compatibility

The corrective implementation branch `correction/issue6-operational-ux` starts
from the validated PR #16 head `77254123061decb934694b9f78251cba0760b114`,
targeting `integration/issue6-physical-button` without rewriting that branch.
The original UI/physical experiment ancestry below is retained. Corrections are
presentation/gesture adapters plus offline-map storage in the existing Command DB;
GNSS/SOS delivery, protocol and projection/recording engines are unchanged.

Integration branch `integration/issue6-physical-button` starts from PR #14
`f996767f34557da4716ff6f626aa03945bc615f7` and reuses the tested PR #15
controller/journal source `d00e2e8e344f0e071698ec3b0add44920c06eaab`, both of
which descend from combined PR #12 `b23b5f58845c896e7fb2c5800e577202667c1e3f`.
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
integration and Chromium interactions, then produces three independent artifacts:

- `GNSS-Tracker-Android-debug` → `GNSS-Tracker-debug.apk` (launcher GNSS Tracker).
- `GNSS-Command-macOS-arm64` → `GNSS-Command-macos-arm64` (CGO-free).
- `GNSS-Command-Windows-x64` → `GNSS-Command-windows-x64.exe` (CGO-free).
- A separate acceptance-evidence artifact holds manifest, checksums, signer,
  setup and rendered visual evidence; it does not bundle the three executables.

Exact application hashes are emitted in the successful job log and manifest. APK DEX
and Go VCS metadata are verified against `GITHUB_SHA`. Artifact ZIP hashes are separate
from the extracted application hashes. A failed or pending run is not a matched build.

## Setup and launch

1. Download and extract each platform artifact and the evidence artifact from the
   same successful Actions run. Check
   each extracted application against `SHA256SUMS` (`sha256sum` on Linux,
   `shasum -a 256` on macOS, `Get-FileHash -Algorithm SHA256` on Windows).
2. macOS: `chmod +x GNSS-Command-macos-arm64`, then
   `./GNSS-Command-macos-arm64`. Windows: run
   `.\GNSS-Command-windows-x64.exe` from PowerShell. Command uses a persistent
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

The debug signer can change between runners. Compare the artifact's
`android-signing.txt` certificate SHA-256 with the installed normal tracker before
installing; an incompatible signer cannot upgrade in place. Export/preserve pending
observations and SOS evidence before any operator-decided uninstall, or keep the
old tracker and use a separate acceptance handset. The experimental
`org.gnss.tracking.buttontest` app is a different package: no data migration or
uninstall is performed. macOS binaries are portable but not notarized; use only the
verified trusted asset and follow the OS's explicit security approval flow.

## Reproduce automated checks

```sh
(cd command && go test -race ./... && go vet ./...)
(cd test-tools && go test -race ./... && go vet ./...)
(cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug)
test-tools/integration/run.sh --console=plain
node test-tools/integration/map-test.cjs
node test-tools/integration/sos-alarm-test.cjs
node test-tools/integration/terrain-test.cjs
```

Browser checks require externally installed test-only Playwright and axe-core/playwright;
set `GNSS_COMMAND_BINARY`, `GNSS_PLAYWRIGHT_MODULE`, `GNSS_AXE_MODULE`, optionally
`GNSS_CHROMIUM`, and `GNSS_SCREENSHOT_DIR`, then run `sos-dashboard.cjs` and
`ui-dashboard.cjs` under `test-tools/integration`. They use the actual embedded
Command frontend/server and real HTTP/read-model state with synthetic protocol fixtures.
Android `UiNavigationVisualTest` renders the actual native Activity with Room flows and
Robolectric native Skia, with synthetic operational telemetry. This is equivalent
software rendering evidence, not an emulator or attached physical device.
The Actions test-report artifact retains the full Android suite and separate
cross-language integration reports. Count each unique test once; an integration
rerun is not an additional unit-test pass.

## Corrective verification boundaries

### Rendered inspection and corrections

The baseline is `77254123061decb934694b9f78251cba0760b114` (PR #16), not
a superseded physical-button experiment. The correction preserves its PR #14 UI
and updated PR #15 controller ancestry. Local before/after screenshot directories
are `shared/gnss-android-before`, `shared/gnss-android-after`,
`shared/gnss-ui-before` and `shared/gnss-ui-after` in the execution workspace.
Actions publishes same-revision after-renders in the acceptance evidence artifact.

Actually inspected Android renders include Tracking stopped/active/stale/offline,
healthy GNSS with unreachable Command, SOS hold progress/pending state, Settings
identity/connection and expanded emergency/readiness, Diagnostics summary,
advanced evidence and Physical Button inactive/listening/observed/interrupted
states. Command inspection includes zero/five parties, 720/800/900-pixel desktop
heights and 430/760-pixel narrow layouts, map-dominant mode, quality/map dialogs,
offline/invalid/restored maps, provider-fixture area preview, delayed/multiple SOS,
located event observation and ordinary/SOS Event History. Native enlarged-font
content scrolls independently while navigation and SOS remain fixed.

Inspection discovered and corrected narrow-header clipping, low-contrast native
diagnostic text, zero-width narrow map layout, polling that could collapse focused
history evidence, tiny saved maps rounding to misleading zero KB, missing map
coordinates silently becoming zero and changed-area/stale-response preview races.
The primary phone SOS status also retains the latest save/failure/debounce notice
beside older queued-event receipt status, so an old pending SOS cannot hide a new
storage failure. Command workspace outage and recovery have independent labels
and warning/connected colours, tested with an actual stopped/restarted server.
Expanded
native-section captures explicitly scroll to the section under inspection. Browser
tests assert viewport overflow, geometric recording-control centring and map width;
axe checks workspace, quality, maps, Event History and emergency drawers.

### Recorded local checks

The full Android run completed `compileDebugUnitTestKotlin`, `testDebugUnitTest`,
`lintDebug` and `assembleDebug`: **192 discovered, 167 passed, 25 skipped, zero
failures/errors**. The 25 skipped tests require the separately launched real Command
bridge and are not counted as passing ordinary unit tests. Lint reports **zero
errors, 27 warnings** (including localization/accessibility warnings); it is not
claimed warning-free. Five Physical Button test methods, six hold-gesture methods
and three SOS Activity methods passed. Separate bridge results and the final
same-revision CI results are recorded in the PR and Actions logs; repeated runs
must not be added together as new unique tests.

Node map/alarm checks and both actual-server browser suites pass. A Go live-marker
VM smoke fixture initially lacked the new optional `locatedSOS` binding; adding
that fixture binding preserves all existing live/history assertions. Local SDK,
JDK/proxy trust and native test setup are environment-only and are not application
workarounds.

Before/after native Android renders cover 360/480 dp and 1.5× font scale;
Command covers 1280×720, 1280×800, 1440×900 and narrow widths. Native Skia and
Chromium screenshots are software evidence, not handset/speaker/target-OS acceptance.
Focused gesture tests retain early-release/motion/pointer/cancel/lifecycle safety;
existing Physical Button tests and cross-language durable delivery tests remain.
Map tests exercise validated import, persisted library, provider conversion/limits
with a local HTTP fixture, and rendering without external internet. Real Nominatim
and Overpass access from this execution returned **proxy CONNECT HTTP 403** on
10 October 2026. A successful real online place search/area download therefore
remains an external acceptance requirement unless the same-revision Actions
`map-online-acceptance.json` records both real-provider checks as passed. This
separate check makes one search and one 500 m area request, saves and rereads real
features; it does not turn provider availability into a flaky offline regression.
GeoTIFF and OSM
multipolygon relations are not supported.

## Physical acceptance remains required

Use [Android device acceptance](../android/DEVICE-ACCEPTANCE.md) for native GNSS,
GPSTest A/B stall evidence, screen off/Doze/OEM power, real Wi-Fi recovery, persistence,
backlog recovery and SOS priority. UI changes do not resolve the known physical GNSS
stall or expand locked-screen key privileges. Verify speaker audibility on both target
OSes, installer/debug-signature compatibility, actual map alignment with surveyed
coordinates, font scaling/TalkBack on device, and the document export picker.

Retain matched APK/Command diagnostic ZIPs, map provenance and screenshots. No physical
pass, issue closure, release or merge is implied by automated software results.

## Physical Button Test integration

Diagnostics now has one expandable **Physical Button Test** entry beneath
`R.id.diagnostic_tools`. It reuses `PhysicalButtonTestController` and the existing
bounded `DiagnosticJournal`; it neither adds a key detector nor changes GNSS, Room,
SOS persistence, sender or Command behavior. While listening, only public
Activity-delivered events are observed and the legacy Activity triple-Volume-Up SOS
adapter is suppressed. Stop, Activity pause and a new process restore it. The report
is included as `physical-button-report.json`; retained evidence survives navigation,
pause/resume and process recreation, while listening never survives a restart.

Background, locked-screen and screen-off volume observation are **MECHANISM
UNAVAILABLE**. The application does not create a MediaSession, Accessibility Service,
root/privileged hook, audio playback, extra foreground service or OEM-specific API.
Follow [Generic Android Physical Button Acceptance](../android/DEVICE-ACCEPTANCE.md)
on actual hardware. Keep PRs #11, #12, #14 and #15 unmerged pending review and do
not infer a physical-device pass from the rendered or JVM evidence.
