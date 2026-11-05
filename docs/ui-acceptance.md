# UI acceptance evidence

## Issue #39 V4.6 — environment repair and baseline gate (2026-11-04)

Authorized source: `9547985efd84c7229025b0b1c56812099589dc6b`, freshly fetched
`integration/combined-upgrades`. The mandatory baseline gate passed before production
implementation began. This section records the exact unchanged-source baseline and
environment work; final implementation evidence is recorded separately below.

- `/usr/bin/go` is a desktop Go board game (`--help` describes local two-player
  play), not the compiler. An isolated official Go **1.24.7 linux/amd64** toolchain
  was selected using PATH. Its downloaded archive SHA-256 matched the official
  release index: `da18191ddb7db8a9339816f3e2b54bdded8047cdc2a5d67059478f8d1595c43f`.
  The existing bridge script was not changed.
- Direct external DNS is unavailable in this proxy-only environment. Java did not
  consume HTTP_PROXY/HTTPS_PROXY automatically. Explicit Java HTTP/HTTPS proxy
  properties enabled the unchanged wrapper to download **Gradle 8.11.1** and
  enforce its existing SHA-256
  `f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6`.
- The initial Debian Java **21.0.12.1** installation lacked JAVA_COMPILER (`javac`).
  Installed isolated Temurin **17.0.21+9**, verifying publisher archive SHA-256
  `8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711`.
  JAVA_HOME selects that full JDK; existing system CA trust remains enabled.
- Installed Android platform **35 revision 2**, Build Tools **35.0.0** and Platform
  Tools **37.0.1** under an isolated ANDROID_HOME, checking archives against the
  official Android repository metadata. No build dependency or SDK target changed.
- Gradle's forked Robolectric JVMs also need proxy/CA properties. GRADLE_OPTS alone
  fixed wrapper/dependency downloads but not those forks. The initial unit attempt
  failed framework resolution with UnknownHostException and was stopped; that is
  infrastructure failure, not application regression evidence. JAVA_TOOL_OPTIONS
  now propagates the same proxy/CA properties to the test JVMs.
- Baseline `./gradlew testDebugUnitTest lintDebug assembleDebug --max-workers=2
  --console=plain`: **BUILD SUCCESSFUL**, 9m46s. XML: **278 tests, 242 passed,
  36 bridge-dependent skipped, zero failures/errors**. Lint XML: **zero errors,
  112 warnings**. Debug APK assembled. This tests unchanged application source at
  the pinned integration SHA; concurrent changes were setup documentation only.
- Baseline `test-tools/integration/run.sh --max-workers=2 --console=plain` with the
  repaired environment: **BUILD SUCCESSFUL**, 19m42s. The scoped
  `CommandIntegrationTest` XML reports **36/36 passed, zero skipped/failures/errors**,
  1115.644s test time. These are the same 36 methods skipped by the ordinary run,
  not additional unique tests. Real Kotlin/Room/Sender and Go HTTP/SQLite ran.

### V4.6 Day text contrast — operator approved minimal correction

During native token implementation, the exact immutable V4.6 `--muted: #697C82`
was checked against its approved Day surfaces. WCAG relative-luminance contrast is
**4.3695:1 on #FFFFFF**, **4.0208:1 on #F3F6F6** and **4.1608:1 on #F7FAF9**.
The artifact uses this token for small secondary text. The existing
`NativeUiAccessibilityTest.semanticTextPairsMeetNormalTextContrastInBothThemes`
requires at least 4.5:1, as does the existing design-system accessibility contract.
Exact token parity and that requirement cannot both hold for those text pairs.

Operator-approved narrowly scoped exception: Day secondary text **#61747A**, the first
passing shade when subtracting the same integer RGB amount from the approved
token (eight per channel). Ratios: **4.5071:1 on #F3F6F6**, **4.8980:1 on white**.
The operator replied **“Approve minimal contrast correction”** in this implementation
session. The production Day secondary-text token uses that shade. The immutable
approved artifact, test threshold and approved typography remain unchanged.

### Implemented contract and final verification ledger

The existing #39 branch was reconciled against the latest fetched integration parent,
not `main`: `devX/issue-39-android-settings-diagnostics` descends from `9547985e…`;
its historical PR #44 work at `5307c79…` is already integrated. Canonical agent guidance
is synchronized from `9e237edfc7ce0b9218b93ecd661d83203a40a0e5`, file only. PR #64
remains draft/unmerged; its 15 rules/52 corrections were read as requirements, not code.

V4.6 is the pinned artifact at `8d95eafe6fdcd1423027194ea128ae7a8b91236d`.
The [60-group matrix](ui-preservation-matrix.md) records production ownership and
preservation. Advanced diagnostics/history/configuration remain contextual; acknowledged
View alert history opens the canonical retained journal. No Command production,
protocol, approved design or dependency/wrapper changes are included.

Room 7→8 adds stop-request and cancellation-return timestamps to the existing command
journal. Local stop commits intent before cancelling repetition/OS output; recipient ACK
is independent. API cancellation failure leaves completion unconfirmed and retryable.
Local completion does **not** revise an already sealed Command `unknown` wire result;
its exact revision/digest remain unchanged until an explicit recipient ACK. The real
bridge regression exercises stop-only, OS failure, custody, retry/restart and subsequent ACK.

First implementation run at `a222313fef6cefa2af3fe48d994d37e8175defd9`:
`testDebugUnitTest lintDebug assembleDebug --max-workers=2 --console=plain` stopped
with **286 tests: 247 passed, 3 failed, 36 bridge-dependent skipped**, 9m31s.
Failures were AppearanceTest (pre-V4.6 palette/picker), DialogVisualTest (removed
routes), and NotificationVisualTest (native alert-action ellipsis at 1.5×). Lint/APK
completion and bridge were not established by that failed run. The old test routes
were replaced with real current affordances, and alert actions use compact independent
**ACK alert** / **Stop output** labels. No geometry assertion was weakened.

The correction also keeps Start/Stop key-test controls in one compact stable row,
disables empty Clear results, retains detailed device/mechanism evidence in Advanced
logs, and moves selected-document export onto the existing application work scope.
Picker/cancel/write/progress/failure status has one Operational owner and is visible
at every export entry; rotation retains the job. Process termination can interrupt a
document provider write; after a new process no completed export is inferred.
The recorder, sanitizer, URI/document operation and observation ownership are unchanged.

The `bbf2902151f482387c46bc044814f0e7869600f5` full run was deliberately
interrupted after 370.709s (client exit 143) for the pending-consent hardening; it is
**not** a completed unit/lint/APK result and its bridge was not started. The correction
clears the existing private review candidate on pause/dismiss, blocks repeated review
opening, and checks endpoint/binding generation under CommandControl's existing mutex
before private-key storage. The Tracking save caption now reads writer commitment,
pending work and failures instead of inferring saved observations from Tracking ON.

### Approved protected SOS accessibility clarification

The operator explicitly approved replacing immediate ACTION_LONG_CLICK activation with
an explicit protected accessibility action, with **the full 1200 ms delay**, accessible
pending feedback and Cancel, cancellation on navigation/deliberate focus change/pause,
no duplicate/bypass, and the existing SOS engine/durability/delivery unchanged. The
operator also required that ordinary reader announcements/focus updates not cancel it
and authorized proceeding **without another design review**.

The adapter now exposes a labelled protected long-click action and changes the standard
accessible Click action to **Cancel SOS activation** while pending. Touching Cancel or
using the focused keyboard action cancels without starting another hold. Repeated
accessibility actions do not reset the timer or duplicate activation; after completion,
another accessibility attempt requires leaving/refocusing the control. Announcements,
content updates and clear/reacquire notifications on the same node are ignored by the
cancellation monitor; actual focus on another control cancels. Native input-focus
restoration to its starting target is tolerated. Window loss, explicit navigation keys,
Activity pause/destruction and outside touch cancel unfinished attempts. Continuous
1200 ms touch behavior is preserved; Enter/Space/DPAD-center also require a full hold.
The optional existing triple-volume shortcut retains its separately approved pattern.
Only the original app SOS engine can commit/transmit an event.

Native framework-action tests cover minimum delay (no activation at 1199 ms), Cancel,
interruption/disposal, duplicate actions, keyboard hold/release, focus on another
control, ordinary announcements/same-node focus, global navigation and post-activation
Activity destruction. These are software tests, **not real TalkBack or Switch Access**.
ADB reports no attached device; those physical checks remain explicitly pending.

A separate reproduced Custom-interval regression (`35 → type first digit 5 of 50`)
hid the editor as if a preset had been selected. The fix preserves editing focus and
explicit Custom choice across recreation, while explicit preset selection still hides
Custom. The Party interval mirror also follows the canonical editor's loading/enabled
state, preventing early edits from being overwritten by initial storage loading.

The complete Android command at `1adb1d4e77d23653c61bbccb9ea5caf86d989a69` passed:
**290 tests, 254 passed, 36 bridge-dependent skipped, zero failures/errors; lint zero
errors/fatals and 131 warnings; debug APK built**, 9m59s. Its APK contains that exact
source SHA in DEX; SHA-256 `87396ed175569513f5a25f13bf4c8a910756ef6f66ae5fbd9b85401c63479fe5`.
The follow-on bridge invocation was interrupted during setup for the above input fixes;
it is not integration evidence. Cached XML left by that interruption is excluded from
counts. The later committed-source results are recorded separately below.

Protected-input correction targeted run: **39 tests passed, zero failures/errors/skips**
(`SosHoldGestureTest`, `SosActivityTest`, `V46WorkflowTest`, `MainActivityStartupTest`,
`PhoneAlertNotificationTest`), before the final commit. Real TalkBack/Switch Access
was not executed. Final committed-source verification is recorded below.
The `98c37224390ea90f4045a7dc9382a5db471d91e3` full invocation was
intentionally stopped after 403.739s (exit 143) during self-review; it supplies no
completed final-suite evidence. A new regression reproduced a stop-only defect after
cold-start recovery: an already confirmed `unknown` alert had no local-stop intent,
and requesting Stop Output revised its sealed result. The fix persists local intent
and OS cancellation evidence without revising **any** pre-existing `unknown` result,
including one awaiting custody. The regression checks unchanged wire result and
confirmation, durable local timestamps, retained unresolved status and subsequent
explicit recipient ACK. The corrected CommandControl class passed all 26 cases,
zero failures/errors/skips (`recovered-stop-fixed.log`), before the final commit.
The real bridge additionally covers recovered alerts in the
5/20-phone fleets, alongside the existing stop/retry/OS-failure scenario.

The local `beb5d258be13cf43a262754624ee7a05354311cd` verification process was
lost during the managed-environment restart before it recorded completion; no local
pass is claimed for it. The same exact commit's [CI run 41871320929](https://github.com/suraj-singh12/gnss-android-tracking/actions/runs/41871320929)
completed ordinary Android tests: **296 total, 259 passed, one failed, 36 skipped**;
`PhoneAlertNotificationTest` still expected revision 3 instead of 2 after the sealed-result
fix. Lint/APK completion was not established; subsequent Go checks and the real
bridge were skipped after that test failure. A local
single-case reproduction confirmed `expected 3 but was 2` (42s). The corrected test
explicitly checks that native Stop Output preserves the original result and that native
ACK advances exactly once; it no longer expects the removed stop revision. The
combined notification/CommandControl targeted run passed **29 tests, zero failures,
errors or skips**, 49s (`recovered-notification-fixed.log`), before committing the
test-only correction.
The same CI run's deterministic map-software job passed; public-provider acquisition
failed due to timeouts/transfer ceilings. Command code and those limits are unchanged.

Intermediate working-tree runs caught and corrected reused layout-parameter geometry,
large-font SOS clipping, stale retained-history filters, filter event-queue timing and
sealed terminal-result semantics. They are not relabelled as final-commit evidence.
The last full navigation-render probe before final polish passed all 14 theme/size
cases in 2m41s. These probes remain historical; the committed-source results below
are the final acceptance evidence.

### Completed Android run and final message correction — 2026-11-05 UTC

At `30570210215411081632a611381d737b9c4cfdd7`, the complete Android command
passed in **640.384s**: **296 total, 260 passed, 36 bridge-dependent skipped,
zero failures/errors**; lint **0 errors/fatals, 128 warnings**; debug APK
**3,616,745 bytes**, SHA-256
`56e5eda8e9de2782fb3807e2ec166f5d25222528e48ec074a43eb73550d574dd`, exact
source SHA present in DEX. All **796 native renders** were generated. These are
valid results for that source, not evidence for a later revision. Its
[CI run 41956645618](https://github.com/suraj-singh12/gnss-android-tracking/actions/runs/41956645618)
also passed Android, Go/protocol and both measured stress steps. The next source push
cancelled its active bridge step; subsequent assets/browser steps were skipped. The
deterministic map job passed, public-provider job failed on timeouts/transfer ceilings.
This is not an overall-green CI or integration-pass claim.

Its real bridge invocation was deliberately interrupted after **775.131s**, exit 143,
for a final truthful-failure-message correction; no bridge pass is claimed. If local
recipient ACK is already durable or in Command custody but an OS cancellation retry
fails, the old combined failure banner must not claim Command ACK is unconfirmed.
The message now directs the user to the retained acknowledgement and delivery status;
underlying storage, sender and ACK/stop semantics are unchanged. The existing three
PhoneAlertNotificationTest cases passed, zero failures/errors/skips, **42s**, before
commit. No new test mirrors this low-impact literal-message change.

Frozen implementation source is now **`e9b7691dab9765dfae0864575b9c45d3c8dbb114`**.
Complete Android/lint/APK and the separate real bridge execution against this exact
revision **PASSED**. The bridge ran all 36 methods skipped by the ordinary invocation.

### Final committed-source execution ledger

| Check at `e9b7691dab9765dfae0864575b9c45d3c8dbb114` | Actual result |
| --- | --- |
| `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug --max-workers=2 --console=plain` | **PASS**, exit 0, **607.263s** (10m7s); **296 total, 260 passed, 36 bridge-dependent skipped, zero failures/errors** |
| Lint XML | **0 Error, 0 Fatal, 128 Warning**; not warning-free |
| Debug APK | **Built**, **3,616,756 bytes**; exact source SHA present in DEX; SHA-256 `cba104ca2a281f956e862c81d8dacc6d2e35b018c96cdc3373f003bd59bcbcd5` |
| `test-tools/integration/run.sh --max-workers=2 --console=plain` | **PASS**, exit 0, **1147.289s** (19m05s Gradle output); **36/36 passed, zero skipped/failures/errors**, real Kotlin/Room/Sender → race-enabled Go HTTP/SQLite |
| Native render/geometry evidence | **796 generated images**, Day/Night, narrow/wide, portrait/landscape and 1×/1.5×; 20 representative final-source images retained |
| Physical Android / TalkBack / Switch Access / field audio/GNSS | **NOT TESTED**, ADB lists no attached devices |

The bridge's 36 cases are the same methods skipped in the ordinary run: **296
cases executed across the two invocations**, not 332 distinct passing cases. Its
expanded real-alert fleet case covers local stop, OS failure, Command custody, retry,
process recovery of an already sealed `unknown` result, unchanged wire revision/digest,
and subsequent explicit recipient ACK in 1/5/20-phone fixtures. Stop output does not
acknowledge SOS or create recipient ACK.

[Exact commands/source trees](evidence/issue-39/v46/verification.json),
[per-class counts, lint and APK identity](evidence/issue-39/v46/summary.json),
[bridge XML archive](evidence/issue-39/v46/bridge-test-results.zip) and
[bridge log](evidence/issue-39/v46/bridge.log.gz) retain the final local evidence.

[Native render inventory and hashes](evidence/issue-39/v46/native-renders.json)
records the local run's 796 filenames/dimensions/hashes and which 20 images are retained.
The [Android XML archive](evidence/issue-39/v46/android-test-results.zip),
[Gradle log](evidence/issue-39/v46/android.log.gz) and
[lint XML](evidence/issue-39/v46/lint-results-debug.xml.gz) retain actual outputs.
Native controls use test-only Room/Operational/input fixtures, not production demo
records, physical GNSS or hardware-key evidence. Final-source Night Tracking and
1.5× native urgent-notification renders were manually reviewed; the earlier review
of unchanged layout covered enrollment, policy, filters, peer key-test controls and
separate ACK/Stop actions. Geometry/contrast assertions passed in the complete suite.
Representative images: [Tracking Day](evidence/issue-39/v46/android-day-390x842-1.0-tracking-active.png),
[Tracking Night](evidence/issue-39/v46/android-night-390x842-1.0-tracking-active.png),
[Diagnostics](evidence/issue-39/v46/android-day-390x842-1.0-diagnostics.png),
[physical-test tools](evidence/issue-39/v46/android-day-390x842-1.0-diagnostic-tools.png),
[large-text native alert](evidence/issue-39/v46/android-day-360-1.5-notification-urgent.png).

CI [run 41959230200](https://github.com/suraj-singh12/gnss-android-tracking/actions/runs/41959230200)
at this exact source initially failed five API-26 framework-resolution cases:
`DiagnosticsExportApi26Test` (three), `GnssDiagnosticsTest.unavailablePowerApisStayNullOnMinimumSdk`
and `MainActivityStartupTest.readySingleTapStartsExactlyOnceAndBecomesActiveAfterDurableStartup[26]`.
Each failed in **MavenArtifactFetcher**, caused by an HTTP IOException, before its
application assertions; **296 total, 255 passed, five failed, 36 skipped**, 9m19s.
These are recorded failures, not skipped or passed tests. The available job log does
not expose the underlying HTTP status. The report artifact was fetched through the
GitHub app, but its signed storage URL is blocked from the execution workspace (proxy
403); no network-policy/TLS/checksum bypass was attempted. The same API-26 cases
passed locally from the legitimate framework cache. A job-specific retry of the failed
integrated job was requested (original job `127021663503`); no dependency or wrapper
version changed.
The independent deterministic map job passed; public-provider failures remain separate.

The retry passed Android (**296 total, 260 passed, 36 bridge skips**), Go tests/vet and
protocol checks, both measured stress steps, and real integration (**36/36, zero
skips/failures/errors**). Portable macOS/Windows compilation succeeded. Browser checks
passed SOS dashboard, UI dashboard and recording foundation, then failed
`recording-playback.cjs:879`: after switching to 1440 px, `headerAction` tried to click
the intentionally hidden mobile header summary. Command's existing desktop menu groups
are exposed directly. This is a scoped test-helper defect; **Command production is
unchanged**. Checksum verification and asset uploads were skipped after that failure.
[Job/step states](evidence/issue-39/v46/ci-e9-jobs.json),
[complete integrated log](evidence/issue-39/v46/ci-e9-integrated-retry.log.gz) and
[separate provider log](evidence/issue-39/v46/ci-e9-provider.log.gz) retain that evidence.

The helper now opens the mobile summary only when visible, then uses the existing
visible group/control. No assertion is removed and no DOM state is simulated.
The full affected playback suite **passed locally** against real Command/SQLite and
Chromium: 21 parties / 20 included, immutable map/terrain after deletion, deterministic
rewind, process restart, delayed history redraw, read-only historical SOS and real
pause overlap. This was a **pre-commit working-tree** run, explicitly labelled
`e9b7691-plus-uncommitted-desktop-helper` in its
[report](evidence/issue-39/v46/playback-helper-working-tree.json) and
[log](evidence/issue-39/v46/playback-helper-working-tree.log); not an exact-new-commit
CI pass. JavaScript syntax check also passed. Only this browser test helper changed;
Android, Command and protocol source trees remain byte-for-byte at the tested e9
revision. CI must validate the new helper commit and remaining browser/asset stages.

Local browser setup: official npm Playwright **1.64.1** installed externally.
Its bundled Chromium download was blocked by the configured proxy (`cdn.playwright.dev`,
403 Domain forbidden). Used the existing supported `GNSS_CHROMIUM` option with system
**Chromium 151.0.7922.173 (Debian 13)**; Node **24.19.0**, real locally built Command
from the unchanged source. No network policy, TLS, dependencies or production code
were changed to mask this setup restriction. These are Linux browser results, not
Android or native macOS/Windows execution.



Reproduction environment used in this session (paths are disposable tool installations):

```sh
export JAVA_HOME=/workspace/scratch/gnss-toolchains/jdk-17.0.21+9
export ANDROID_HOME=/workspace/scratch/gnss-toolchains/android-sdk
export PATH="$JAVA_HOME/bin:/workspace/scratch/gnss-toolchains/go/bin:$PATH"
export GRADLE_OPTS="-Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 -Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080 -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
export JAVA_TOOL_OPTIONS="$GRADLE_OPTS"
# From android/ (ordinary run deliberately skips the bridge-dependent methods):
GNSS_SCREENSHOT_DIR=/workspace/scratch/gnss-toolchains/final-native ./gradlew testDebugUnitTest lintDebug assembleDebug --max-workers=2 --console=plain
# From repository root; real Kotlin/Room/Sender → Go/SQLite, race-enabled bridge:
test-tools/integration/run.sh --max-workers=2 --console=plain
```

Use the environment's configured proxy/CA paths on another machine; no TLS or checksum
bypass is permitted. The localhost bridge does not use the external Java proxy path.
No exact-parent CI run was available during the baseline; local verification repaired
that gate. The existing integration-targeted workflow can validate the draft PR head.

### Visual/accessibility and anti-regression boundary

Native renders use real MainActivity/native controls with test-only Room/Operational
fixtures, never production demo records. Coverage: Day/Night, 360×800, 480×960, 390×842,
1×/1.5× font scales, 800×360/960×480 landscape, nested screens and dialog states.
Global SOS remains fixed while content scrolls; native 48 dp touch targets accommodate
Android accessibility even where the HTML's visual control is 40–43 px. Android owns
notification/permission/document-picker geometry. Software rendering does not prove
TalkBack, actual sound/vibration, physical GNSS or key delivery.

| Quality rules / historical corrections | Implementation and verification boundary |
| --- | --- |
| Rules 1–3; corrections 27–39 | Same authorities/drafts; direct five Settings and seven Diagnostics destinations; mandatory hold; one Filter history entry with binary switches and contextual canonical categories; one genuine physical-test interface; no screen-key proof |
| Rules 4–9; corrections 40–45 | Fixed SOS/native scroll containment, compact task actions, grouped peer menus/separators, concise GNSS/contact, identical pale-red Stop in both themes, consistent chevrons/icons; native geometry/render and contrast checks |
| Rules 10–15; corrections 46–52 | Read-only public UUIDs, compact Authorize/Recheck/help, protected private input, cancel-safe review, saved ordinary drafts/routes/query/filter/page/selection, unchanged-view focus preservation, actual lifecycle/Room/ACK/trust states and contextual evidence |
| Corrections 1–26 | Command-specific map/recording/calendar/audio changes are outside this Android scope; shared containment, interaction preservation and operational-truth principles apply. Command V5.14 is untouched |

Physical acceptance remains **NOT TESTED**: actual Android permission/locked-screen/
Doze/OEM/service recovery, GNSS freshness/accuracy in the field, DND/mute/channel sound
and vibration, hardware buttons, TalkBack/switch access and real offline/LAN multi-phone
operation. Native macOS/Windows execution is also not established by Linux integration.
Use the existing [device acceptance](../android/DEVICE-ACCEPTANCE.md) procedure.
For this V4.6 candidate, supplement its historical SOS cases with the current protected
input contract (these steps are **NOT TESTED** on hardware):

1. On Tracking, Settings and nested Diagnostics, touch-hold SOS continuously for
   1200 ms; release/move away before completion must save no SOS. Verify an actual
   completed hold creates one durable event and retains delivery/operator-ACK separation.
2. With TalkBack, invoke “Activate SOS with 1.2-second protection.” Confirm pending
   feedback and the exposed Cancel action; no event may appear before the full delay.
   Exercise Cancel, deliberate focus on another control, navigation, window loss and
   Activity pause before completion. Verify no event after each interrupted attempt.
3. While pending, allow ordinary announcements/content updates and same-node focus
   clear/reacquire; a legitimate unchanged attempt must complete. Repeat the activation
   action during the delay and immediately afterwards: no timer reset or duplicate SOS.
   Deliberately leave/refocus before beginning a new attempt.
4. Repeat with Switch Access where supported, including action-menu selection,
   cancellation and scan focus. Check that incidental reader/menu transitions do not
   cancel, while deliberately moving to a different control does. Also verify keyboard
   Enter/Space/DPAD-center needs the full hold and early key release cancels.
5. For phone alerts, independently verify Stop output vs recipient ACK, restart and
   failed cancellation, DND/mute/channel behavior and retained acknowledged history.
   Record device/Android/reader versions, timestamps and Android/Command exports;
   output API return alone cannot prove audibility or human receipt.

No field-stable/release claim, merge, release or Issue #39 closure is authorized.

## DEM reliability and Map & Layers follow-up on PR #19

`feature/issue6-dem-reliability-map-ui` starts at PR #19's verified
`2f5e79879fed967bb66ab3def014ae0ae3b76f9d`, retaining the uncommitted diagnostic
classification correction from the exact-location investigation. One focused PR
targets `feature/issue6-automatic-dem`; no existing PR is merged or issue closed.
Android/GNSS/recording/distance/SOS delivery architecture is unchanged.

Verified defect: streaming gzip errors wrapped a response-body timeout as invalid
DEM. Separating bounded network reading from gzip decoding removes that ambiguity.
The Mac field-network cause remains unconfirmed; Codex Cloud's provider proxy
CONNECT 403 is a separate BLOCKED result. Exact timeout/retry/cache/report policy
and operator instructions are in Command README. Reports persist in the existing
evidence journal (20 download reports), never a new database or worker service.

Screenshot-first inspection found download actions below unrelated import tools
and long technical text. Current Map, Download Map and Saved Maps now group tasks;
advanced details scroll internally. Existing controls are preserved:

| Previous capability | Reachable location |
| --- | --- |
| Blank/offline map, saved selection | Current Map / Saved Maps |
| Opacity, CRS, coverage, attribution | Current Map → Display & geographic details |
| Use All Observations, display-dot interval | Same disclosure; distance remains qualified |
| Search/results, geographic-centre coordinates | Download Map → Location |
| 500 m, 1 km, custom width/height | Download Map → Area (custom fields when selected) |
| Hillshade, contours, elevation selection | Download Map → Terrain |
| Preview and separate offline save | Download Map → Preview / Download |
| GeoJSON import/export/delete | Saved Maps → Import & manage maps |
| Automatic terrain retry, component choice | Saved Maps → Acquire or import terrain |
| HGT/.gterrain import, local preparation/export | Same tool → Local DEM import & terrain export |
| Terrain visibility, hidden/visible/unavailable states | Existing toolbar Layers; never downloads |
| Progress, Cancel, error details, copy/export | Download feedback / Download diagnostics |

All previous HTML control IDs are retained with no duplicates. Rendered checks
cover Day/Night at 1280×720, 1280×800 and 1440×900, no document-level scrolling,
keyboard/accessibility, report export, Cancel/retry and vector preservation.
`map-download-ui.cjs` uses explicitly synthetic provider/progress fixtures, not
real download acceptance; Go handler tests exercise actual streaming/storage with
deterministic providers. Existing dashboard/SOS/projection/map checks remain.

The reused matched-assets workflow adds this branch and browser check. Its real
DEM UI acceptance is parameterized for **27.3739, 88.7618, 1 km × 1 km**, compares
every persisted grid node to the real downloaded tile, inspects rendered alignment
and restarts with outbound provider access blocked. Source-specific Actions results
must be verified separately; never substitute prior Mussoorie results for this case.
No real provider result is inferred from fixture tests. The Mac executable is for
operator field verification and export of complete attempt diagnostics.

```sh
GNSS_COMMAND_BINARY=/path/to/command GNSS_PLAYWRIGHT_MODULE=/path/to/playwright \
GNSS_AXE_MODULE=/path/to/axe-core/playwright GNSS_SCREENSHOT_DIR=/path/to/screens \
node test-tools/integration/map-download-ui.cjs
```

Before/after captures are preserved with the task evidence; the workflow's
visual-evidence artifact contains the final rendered checks. These are UI and
geographic alignment checks, not survey accuracy or physical field acceptance.

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
   visibility, Fit/Focus and quality settings. Pause/Resume retains original boundaries;
   Stop is final with a durable Save/Discard choice. Discard requires confirmation
   and retains raw observations, saved snapshots, settings and SOS.
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

## Issue #22 synchronization controls and bounded history

Sync All Now activates a distinct, coalesced manual drain on the existing Sender;
Pause affects future history selection without erasing data or cancelling a durable
in-flight ACK. Tracking OFF remains zero collection. A user-started dataSync service
provides a visible notification/Pause action and a bounded execution lifetime after
screen lock/backgrounding, subject to Android/OEM restrictions. Reboot/process death
requires reopening. Automatic visible OFF delivery needs no tracking service.
History separates pending, Room ACK and unresolved records from Command reconstruction.

Command independently polls LIVE/SOS while bounded previews load. Large previews
are labelled and capped at 2048 points; full qualified distance is unchanged. Inspect
history pages original full-resolution geometry/decisions or retained envelopes with
capture and receipt times. Quality/recording changes invalidate obsolete inspection
cursors. Preserve party filtering, time boundaries, keyboard focus, SOS notification,
separate human acknowledgement and raw-diagnostic qualification labels.

Acceptance includes real Room/Sender→HTTP/SQLite tests and Chromium under backlog.
These do not certify native Android screen-off/Doze/radio or desktop hardware; use
android/DEVICE-ACCEPTANCE.md. Remote Sync All remains Issue #25.

## Issue #24 Settings/Diagnostics acceptance

Fresh/upgrade: Settings → GNSS persistence is collapsed and Adaptive is unchecked.
Explicitly check it; confirm saved requested ON and effective Adaptive. Recreate the
Activity and restart the process: saved intent remains; collection requires the
existing permitted lifecycle. Uncheck: subsequent callbacks use dense capture.
A failed preference write must show NOT SAVED/dense fallback. Unknown schema/revision
must never enable selection. Policy changes must neither restart the location service
nor edit old rows. Tracking layout/navigation/SOS/sync controls remain as before.
Diagnostics differentiates valid callback candidates, selected/in-memory candidates,
actual Room commits, intentional omissions, source gaps, pending/ACKed history and
save failures; journal bounds still apply. A runtime fallback must remain visible
even with requested ON. Physical TalkBack and OEM/OS behavior: NOT TESTED in JVM CI.

## Issue #25 software and physical control acceptance

The live Command Phone controls dialog uses existing semantic controls, local API,
selected-party identity and accessible labels. It provides enrollment/revoke,
one/selected/all scope, immutable recipients, confirmation, TTL, requested/effective
values, per-phone states, safe cancellation and deliberate new-request review.
No recording/history object supplies control recipients. Android keeps its three
existing destinations and Tracking layout; Settings adds explicit authorization,
and Diagnostics adds a bounded filterable/expandable Command Instructions console,
copy ID and sanitized existing ZIP export backed by the same Room journal.

Automated production-handler/Room/Sender/browser evidence is recorded in the #25 PR.
Physical Android OS/background/Keystore and native macOS/Windows acceptance remain
NOT TESTED; use the device checklist. #24 remains OPEN, with physical adaptive testing
deferred. #40's external Overpass overall CI failure remains a separate visible
failure until independently resolved; no map implementation is changed here.

### #26 phone alerts

Verify Phone controls Normal/Urgent, optional message, one/selected/all checked
identities, actual count/IDs in confirmation, validated expiry and bounded urgent
cadence/duration. Enroll 20 and a late 21st; late join never enters an issued snapshot.
Expand history during refresh: queued versus receipt/output versus local human ACK
and Cancel pending versus confirmed must remain distinct. Confirm no browser action
can ACK a phone on behalf of its recipient. Run control-ui.cjs plus existing SOS/UI
suites and axe checks; capture the real UI without claiming hardware execution.
Android notification action and Command Instructions local action must persist a
single recipient event and leave SOS untouched; invalid permissions show truthful
blocked/unknown outcomes. Apply the physical checklist for actual output/locked/OEM
behavior; synthetic browser/Robolectric evidence does not satisfy those gates.

## Issue #28 custom-radius acceptance (2026-10-21)

Starting integration: `22afce482b4c18d553652d377917390006acc53d`.
Working branch: `devX/issue-28-custom-map-acquisition`. Working/canonical instruction
blob: `3b6e0d42935c04c68a4332f82064d82b0301cc04`.
PR #17/#18/#19/#20 heads are ancestors of this integration and their map/terrain
mechanisms are retained. #32/#33 are not ancestors; their focused diagnostics and
provider findings were inspected. No outstanding PR was merged/cherry-picked or rewritten.
The unverified secondary provider from #33 was not adopted. Existing real-provider
assertions remain; selecting the legacy rectangle explicitly preserves those tests.

Run with Go 1.24.7+, Node, Playwright and Chromium:

```sh
cd command
go test -race ./... && go vet ./...
go build -o /tmp/gnss-command ./cmd/party-tracker
cd ..
GNSS_COMMAND_BINARY=/tmp/gnss-command node test-tools/integration/map-radius-ui.cjs
GNSS_COMMAND_BINARY=/tmp/gnss-command node test-tools/integration/map-radius-online.cjs
```

Use the existing `GNSS_PLAYWRIGHT_MODULE`, `GNSS_AXE_MODULE`, `GNSS_CHROMIUM` and
`GNSS_SCREENSHOT_DIR` overrides when installed outside Node's normal resolution.
The real-provider runner writes `GNSS_RADIUS_PROVIDER_REPORT`; it exits nonzero
on provider/data failures. The existing matched-assets workflow runs these checks
and uploads the separate source/provider/visual evidence; green offline tests never
turn a failed real-provider result into a pass.

Deterministic evidence:

- PASS: a wholly void source crop no longer aborts a valid neighbouring crop;
  no surface is filled across the void. Entirely void acquisitions persist source
  evidence and remain unavailable. Contour/hillshade controls reflect valid cell
  neighborhoods independently. Valid-cell envelopes remain distinct from full bounds.
- PASS: one saved job persists native crops from 16 separate source tiles while
  keeping four source files (103,737,608 raw bytes) in cache. The fixture transferred
  2,575,766 compressed bytes and stored 1,485,904 bytes in 4.37 s. This is explicitly
  16/1,764 chunks, not whole-circle completeness or a public-provider pass.
- PASS: antimeridian hillshade uses only acquired native neighbour nodes; ±180°
  elevation lookup and saved-request identity share the same geographic position.
- PASS: partial edge chunks intersect the exact requested envelope rather than
  overfetching full grid cells. Valid circles at ±85.05° retain their whole coverage.
- PASS: source-query bounds round outwards rather than omitting border features;
  abandoned cache-publication temporary files are removed on retry/restart.
- PASS: independent equatorial WGS84 analytic east-distance references; radii
  373 / 12,345 / 151,731 m produce conservative envelopes of 4 / 16 / 1,764 chunks
  and 4 / 4 / 16 possible source tiles at centre 0°,0°. No four-tile region truncation.
- PASS: Delhi and Himalayan centres, degree crossing, antimeridian splitting,
  polar/projection restrictions and explicit excessive-resource refusal. The
  151,731 m region is geometry/planning evidence, not a downloaded 16-tile dataset.
- PASS: independent signed big-endian source samples, native north-first rows,
  EGM96 metadata/spacing, exact saved DEM node alignment, voids, existing multi-tile
  seam/CRC/gzip/error tests, empty valid OSM acquisition and complete-chunk reuse.
- PASS: noncontiguous saved vectors survive stalled-provider cancellation; retry
  fills the missing chunk. Identical request identity survives restart/response loss.
  Additional contour/hillshade selections reuse the saved native source and map ID.
  SQLite trigger-injected disk-write failure rolls back replacement bytes. This is
  transactional fault injection, not physical disk exhaustion or OS crash testing.
- PASS: injected saved-terrain read failure retains validated vectors; injected
  vector read failure retains terrain. Source and rendering failures remain distinct.
- PASS: actual Chromium/SQLite fixture screenshots for requested circle, vectors,
  contours+hillshade, each independent switch, void transparency and offline restart.
  `map-radius-ui.cjs` reports zero axe violations. Its georeferenced hill/valley is
  explicitly synthetic. It is not a real mountainous-source visual comparison.
- Measured non-race native fixture: one 156,608-byte compressed source, 25,934,402
  decoded bytes, 841 saved samples / 3,100 encoded bytes; acquisition+save
  180 ms, reload+decode 0.148 ms in one Linux run. Times vary with environment/cache.
  One Chromium visible-chunk fetch/processing run measured 306 ms. These are not
  native-platform capacity guarantees or peak-memory measurements.
- PASS: stalled provider while 21 devices durably submit GNSS and SOS (162 ms in
  one non-race run; 818 ms in a separate race run). Existing saturated-writer test
  with authenticated controls/alerts passed: fresh-priority p95/worst 419 ms with
  21 uploads and 20 concurrent control polls. This does not establish a field SLA.

Real-provider evidence (separate FAIL/BLOCKED category):

| Case | Centre / radius | Planned chunks | Actual result |
| --- | --- | --- | --- |
| Flat local | 28.6139, 77.209 / 373 m | 1 | Overpass and Skadi proxy Forbidden; 0 completed |
| Hilly degree boundary | 27.9999, 88.9999 / 1,234 m | 4 | Overpass and Skadi proxy Forbidden; 0 completed |
| Larger hilly | 27.95, 88.95 / 7,317 m | 9 | Overpass and Skadi proxy Forbidden; 0 completed |

Direct provider probes also returned CONNECT HTTP 403 for Nominatim/Overpass/Skadi.
No provider bytes, geographic completeness percentage or real terrain visual pass
is claimed. Real source quality, native offline display, real-provider long transfer
and slow-network recovery remain acceptance gates. Historical thin/incomplete terrain
reproduction: NOT TESTED (exact failing source revision/settings unavailable).

Local Android build/lint/tests and real Room→Command bridge: BLOCKED before tests
by Gradle bootstrap `UnknownHostException: downloads.gradle.org`; no local Android
pass is claimed. The workflow still owns matched APK/macOS ARM64/Windows AMD64 assets
and exact-revision checksums. Cross-build success is not native execution. Physical
macOS/Windows/Android, physical full disk, power-loss crash recovery, sustained
native memory/disk pressure and two-hour fleet field acceptance: NOT TESTED.

Native operator steps (both macOS ARM64 and Windows AMD64):

1. Use matched exact-source assets/checksums and the existing signing/data-preservation
   procedure. Record SHA, OS, browser and writable disk/free space. Keep the earlier DB
   backup and executable. Never uninstall a tracker with unsynchronised data.
2. Enter a non-preset radius via exact coordinates, search and map selection; verify
   units preserve the request. Include a known flat place, a known hill/valley,
   degree/antimeridian boundaries and a resource-refused request. Review the actual
   source envelope, transfer upper bounds and confirmation before acquiring.
3. Cancel during vector transfer, DEM transfer, decode and save; restart and Resume.
   Export the manifest. Compare completed/failed chunks, raw HGT samples/hash, voids,
   datum and native resolution. A retry of complete chunks must make no provider call.
4. Toggle contours/hillshade independently, pan/zoom/resize across chunk boundaries,
   inspect known reference heights and source voids. Check 1/5/20 devices and device
   21 joining, SOS/operator ACK, LIVE/history/controls and original GNSS positions.
5. Exit Command, disconnect internet, restart the same DB, select Saved Maps and
   inspect source/coverage evidence and all available layers. No external request
   may be required for display. Zoom in when overview detail exceeds browser limits.
6. Preserve screenshots, actual bytes/tile/chunk counts, process peak RAM/CPU/disk,
   save/reload/interaction timings and failures. Mark source gaps and untested steps
   explicitly. Keep #24/#25/#26 open for physical testing; do not merge or release.

Local software regression summary for #28: Command full Go race suite and vet pass;
test-tools protocol/simulator/receiver race suite and vet pass; Node map/terrain/SOS
checks pass; actual-server Chromium dashboard, map-download, radius, SOS and phone
controls checks pass. Optional Go browser-seed test skips in an ordinary run and is
executed explicitly by the browser runner. Earlier renderer smoke/fixture failures
were corrected before final verification; no assertions were weakened. Exact final
source/checksum/CI identifiers are reported in the issue PR, avoiding self-referential
commit hashes in this document.


### Issue #28 automatic-map extension acceptance (continuation of PR #43)

Software evidence is separate from public-provider and native acceptance. The owning
Command README documents transfer consent/accounting, provider/source/PBF limits and
the version-1 standalone map archive. Deterministic tests exercise timeout and
429/502/503/504 fallback, polygon holes and boundaries, exact-source reuse, 4+4 MB
cumulative consent, unknown sizes, 200 MB fallback escalation, persistent allowances,
PBF node/reference validation, partial package parity/idempotence, malicious archives
and injected database publication failure. Browser evidence covers local full-extent
PBF, portable terrain round trip, offline restart, independent layer failures,
three viewports/two themes and accessibility. See PR #43 for exact tested SHA and
final CI/artifact results rather than treating earlier runs as extension evidence.

A Linux 192,000-node / 32-block PBF fixture (2,681,751 bytes) indexed during real HTTP
receipt of 21 parties' LIVE and 21 SOS messages. One race-instrumented run took
4.13 s, sampled process heap peak 9,716,352 bytes; 42 durable HTTP ACK p50 8.93 ms,
p95 11.89 ms, worst 17.38 ms. This finite fixture is not a 1 GB country-file, native
memory ceiling or field SLA. Preserve exact-run logs and use CI for final revisions.

Supplemental Linux local-file acceptance used the historical Delaware PBF shipped
in `github.com/paulmach/osm@v0.9.0/testdata` (7,812,234 bytes; SHA-256
`71ce7bd152d68cb9bdb11d9de4548db86d103f664bb8620a210ddee433534647`).
The production import indexed 723,870 nodes / 65,674 supported ways, explicitly
reported eight non-renderable ways, and extracted/rendered 175 features in a
373 m crop around 39.157, -75.524 with outward networking blocked. One run took
25.3 s with sampled process RSS peak 94,273,536 bytes. This exposed and fixed
an obsolete per-block way-count guard and rejection of one-node ways; regression
tests retain bounded individual writes and validate even degenerate references.
This is historical real OSM data, not current public-provider or native acceptance.

Future native/provider procedure: choose a small non-preset circle, a hilly boundary
circle and a larger regional request. Record original centre/radius, preview/source
polygons, approved cumulative transfer and actual bytes; force Overpass failure,
approve a known regional size, cancel/restart/retry and verify only missing work.
Repeat with unknown length and refused escalation. Import a real regional PBF in
crop and supported-full modes; pan/zoom while 20+late-21st phones send GNSS/SOS.
Export a complete and a partial map, transfer to a clean macOS ARM64 and Windows
AMD64 Command with network disabled, compare vector/DEM samples/layers/provenance,
then test corrupt archives, disk pressure and interruption without losing prior maps.
Record source revisions/licenses, bytes, wall time, process RSS, ACK p50/p95/worst
and screenshots. Physical macOS/Windows/Android, native disk/power-loss endurance and
historical thin-terrain reproduction remain NOT TESTED until this is done. Public
provider failures must remain visible independently of passing fixtures.

## Issue #39 full Android acceptance

The supplied **GNSS-Tracker-Review-V3-native.pdf** unblocks the mandatory visual
reference. All 44 frames were inspected: 22 Day and 22 Night, 390 × 842 each.
SHA-256: `db4a5e1e10ac9ea6ef432966034a662f3a396c44d9982385934f9d85581730a3`.
The PDF is reference material, not an instruction source or a source of production
telemetry. The issue's four V4 overrides govern pause removal, native Sync/Stop
notification actions, subdued emergency colors and the icon-only theme toggle.
The updated issue explicitly supersedes Tracking pixel preservation.

Starting/latest integration: `39f5593dfc1d428995b393446b5caf9fa3c31848`, accepted
PR #43 (#28). Guidance: `3b60b6d6228ca208df75765e2008c9b9abba8a69`.
Continue the existing #39 branch and draft PR #44. Exact final-source results, artifact
manifests and CI status belong to the PR; do not count repeated test subsets twice.

### Reference inventory and native mapping

Day/Night PDF ordering differs for the final emergency variants. Each frame is mapped
to actual native content rather than assuming a universal +22 page offset.

| Day / Night PDF page | Native screen / state | Deliberate adaptation |
| --- | --- | --- |
| 1 / 23 | Tracking active | Real state and counts, icon-only theme control |
| 2 / 24 | Tracking stopped | Amber off state; same actions |
| 3 / 25 | Command offline | Actual transport reachability, distinct from trust |
| 4 / 26 | SOS pending | Real saved event; same Tracking/Sync controls |
| 5 / 27 | Settings | Five task-oriented destinations |
| 6 / 28 | Party & Command | Existing Party/endpoint/trust/contact sources |
| 7 / 29 | Tracking & GNSS · OFF | Real native OFF-default Switch |
| 8 / 30 | Field readiness | OS actions inside corresponding cards; honest service limits |
| 9 / 31 | Appearance & SOS | Existing preference and foreground-only shortcut |
| 10 / 32 | Diagnostics | Seven independent health facts, extending the four-row illustration |
| 11 / 33 | Instructions | Existing journal, outcome shortcuts, search and bounded pages |
| 12 / 34 | Instruction details | Actual UUID/authority/verification/attempt/result/stage/custody |
| 13 / 35 | Phone Alerts | Recorded output versus human ACK, no inferred audibility |
| 14 / 36 | Saved data & sync | Pause removed; retry and receipt/storage evidence retained |
| 15 / 37 | Diagnostic tools | Existing recovery/export/test/log/SOS owners |
| 16 / 38 | Physical Button Test | Real listening/key events and existing SOS suppression |
| 17 / 39 | Change trusted Command | Saved/current draft endpoint, protected existing re-enrollment |
| 18 / 40 | Native notifications | Framework RemoteViews, real Sync/confirmed Stop and alert ACK |
| 19 / 41 | Compact Tracking | Same responsive dashboard, no duplicate compact-mode authority |
| 20 / 44 | Urgent alert on Tracking | Original alert ACK, stable Sync/Start/Stop and SOS dock |
| 21 / 42 | SOS details | Actual saved location/fix age/delivery; operator ACK remains at Command |
| 22 / 43 | Tracking & GNSS · ON | Persisted #24 switch, future observations only |

### Automated evidence and reproduction

`./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain` is the complete
Android gate. Set `GNSS_SCREENSHOT_DIR` for native Skia Activity/window captures.
The matrix includes 360 × 800 and 480 × 960 dp at 1× and 1.5× in both themes, plus
800 × 360 and 960 × 480 landscape in both themes. A 390 × 842 reference-size case
supports side-by-side PDF comparison. Top/bottom captures preserve scrolling content.
App-owned appearance, retry, re-enrollment, authorization, disable, Stop, invalid-input
and dirty-edit dialogs render in both themes/fonts. Native notification-content
renders configure OS Day/Night as well as the application preference. Android owns
notification geometry; these are not physical notification-shade captures.

SettingsDiagnosticsTest checks draft restoration before Room, refresh/recreation and
caret/focus, parent Back with retained edits, explicit Cancel, failed/successful Save,
filters/pages/recreation and trust/request/applied/ACK separation. CommandConsoleUiTest
checks accessible native row activation, clipboard, unchanged-row focus, live detail
refresh, real alert ACK and sanitized export. AdaptiveSettingsTest verifies OFF/ON
persistence through immediate recreation without starting GNSS. Notification tests
verify immutable action identity, correct existing handlers, no GNSS from Sync,
protected Stop and no action replay. Semantic text/surface pairs meet 4.5:1 in both
themes. Native layout tests check navigation label fit, protected SOS and urgent ACK reachability,
stable action bounds and matching Tracking state/action snapshots. Stopped GNSS is
labelled last-known with age at the last update; it never implies continuing freshness.
Existing acquisition, original-observation identity, Room/Sender/SOS, service lifetime,
phone-alert and command result-custody regressions remain gates.

Representative current native views: [Tracking Day](evidence/issue-39/pdf-tracking-day.png),
[Tracking Night](evidence/issue-39/pdf-tracking-night.png),
[Settings](evidence/issue-39/pdf-settings.png),
[Policy](evidence/issue-39/pdf-policy.png),
[Readiness](evidence/issue-39/pdf-readiness.png),
[Diagnostics](evidence/issue-39/pdf-diagnostics.png),
[Instructions](evidence/issue-39/pdf-instructions.png),
[SOS details](evidence/issue-39/pdf-sos-details.png).
The final screenshot artifact includes the full matrix, app windows, native
notifications, PDF comparison sheets and the previously captured unchanged Command
screens/windows, with per-image hashes and provenance.

### Outstanding native acceptance

Automated views use labelled synthetic test fixtures, never production demonstration
transactions. Real TalkBack traversal, hardware keyboard/IME, system permission/settings/
document-provider windows, actual OEM notification shade, physical rotation, GNSS,
SOS, audibility/vibration, DND/Doze/locked/background and power/storage faults remain
**NOT TESTED on a physical device**. Native macOS/Windows window chrome and OS file
pickers are not represented by Linux Chromium screenshots. The 20-phone LAN fleet plus
late 21st remains field acceptance. Local bridge-only skips are reported as skips;
prior real Room→Command results are historical evidence. External DEM/provider CI is
independent, unchanged and reported separately from Android gates.

### Issue #28 acquisition reliability follow-up (2026-10-23)

Starting integration HEAD: `1c8049ede4c806ca2cefce33eecf764e035583f4` (merged #43
and #44). Branch: `devX/issue-28-acquisition-reliability`. Original failing run:
https://github.com/suraj-singh12/gnss-android-tracking/actions/runs/39876771277.
Skadi HTTP 200 decoded 25,934,402 bytes from 18,131,689 compressed bytes; this was
not a DEM decode failure. Live vector calls timed out awaiting headers or returned
HTTP 502. Smallest covering regional fallback requested 227,876,423 / 50,306,338
cumulative bytes. The old radius API acceptance never approved transfer, and the
legacy rectangle browser acceptance waited 390 seconds after a concrete Overpass
error. Production's safety stop was working. A stale pending regional requirement
could additionally mislabel a later independent DEM source.

Deterministic acceptance now uses the production map job, transfer ledger,
provider adapters, persistence and browser. Verified original SRTMGL1 samples at
58.6101,-133.8101, radius 373 m produce an independently decoded nearest elevation
894 m (not surveyed ground truth), native 1-arc-second WGS84/EGM96 samples,
aligned hillshade pixel centres and contour coordinates. Real OSM Delaware PBF
fallback/crop/reimport/offline reuse is checked separately; controlled Alaska
vector geometry is explicitly synthetic. Source files, immutable upstream
revisions, hashes and licensing are recorded in Command testdata provenance.

Local verification: API approval refusal/insufficient/repeated acceptance,
32 KiB interrupted reservation, restart accounting, zero body reads before known
oversize consent, no silent allowance enlargement, and independent source
requirements PASS. Real SRTM every persisted native node and real OSM PBF crop,
reimport/restart PASS. An independent upstream PBF decoder also verifies all
216 cropped real ways against 1,703 original referenced nodes. Controlled HTTP
502, header timeout, truncated/malformed
files and SQLite disk-write failure preserve completed vectors/previous maps.
Browser refusal leaves transfer unchanged; Save & Use while approval is pending
preserves the job; real confirmation accepts the exact cumulative request;
original terrain survives process termination and offline production restart.
Screenshots show requested circle, native contours/hillshade and exact readout.

CI config explicitly caps automated approval at 30,000,000 cumulative bytes per
map and leaves the production default 5,000,000 bytes untouched. Requests for
50–228 MB fail with exact amounts. Local live-provider access is blocked by the
managed environment's HTTP destination policy (CONNECT 403); this is an
environment access failure, not proof of provider outage or a successful skip.
Local full Command Go/race/vet, test-tools race/vet, map-download and radius UI,
and Node map/terrain/SOS/CI-budget checks PASS. Remote exact-SHA workflow
results/artifacts are recorded in the follow-up PR.
Historical thin-terrain location and native macOS/Windows physical field
acceptance remain NOT TESTED; cross-builds are not native acceptance.

Live follow-up evidence at `7c5db227ac9a57c0732a0c070226bb0296d9d00e`:
real public browser acquisition PASS at 27.3739,88.7618, radius 373 m, 13 real
OSM features; 61,637 vector bytes before DEM approval; requested/explicitly approved
cumulative 19,188,488 bytes; 19,126,851 compressed DEM bytes; every native node,
hillshade/contour alignment and offline production restart PASS. The raw Skadi
probe independently passed (18,131,689 compressed bytes). Radius cases accurately
failed the 30 MB ceiling at 227,876,423 / 50,306,338 regional bytes and 37,721,900 /
33,058,591 cumulative multi-tile DEM bytes, while preserving completed chunks.
The legacy Overpass probe timed out awaiting headers. Its prior report-only
nonzero-result/zero-exit mismatch was discovered in CI and corrected: a failed
real-provider report now always produces a failed process/check. These are
independent outcomes, not a successful skip. Final follow-up CI is linked in PR #45.

## Issue #49 · V3 popup and offline map correction

Starting integration: `5cc1c263557a2fd6baea962400df5c9b58b61271`.
Issue branch: `devX/issue-49-offline-maps-v3`. Canonical instructions:
`3b60b6d6228ca208df75765e2008c9b9abba8a69`; the application branch matches it.
Merged #43/#45 are dependencies. The draft PR targets integration; no merge,
issue closure, release or native field-stability claim is authorized.

### Approved reference and rendered review

The exact supplied [V3 reference](evidence/issue-49/v3-reference/) is retained
verbatim. Original ZIP SHA-256:
`49d003b6f45fd7dec75486cd83572403f4fb0e6eb921f73bc9786835c6fd5dcd`.
The handoff's historical Figma prerequisite is superseded by the current issue and
operator approval of this ZIP; no populated Figma artifact is claimed.

The [rendered captures](evidence/issue-49/rendered/) are complete application
windows, separate from the design references. Six primary states have Day/Night
captures at 1600×900, 1280×720 and 390×844. Review metadata identifies the controlled
provider and actual persisted map state. The complete saved-map library example
uses the immutable original Delaware PBF; the acquisition/approval/recovery examples
use the existing controlled provider, not current public services. The preview
shows requested geography, never the reference's illustrative invented road tiles.
No screenshot-baseline suite, pixel-diff gate or second browser framework is added.
Operator visual approval remains outstanding.

The dialog remains 720 px maximum, 768 px tall where space permits, and 688 px tall
at 1280×720, with an internal scrolling body and persistent actions. The existing
[control preservation matrix](ui-preservation-matrix.md)
records every old control and its implemented destination. Existing functional and
accessibility checks retain behavior assertions. Navigation uses the three task
tabs; preview invalidation is checked against the authoritative prepared state,
since Preview & Download now also validates an unprepared selection.

### Corrected product defects

- Full PBF coverage feasibility previously followed whole-source indexing; it now
  precedes indexing. Regional disk/cache admission precedes any additional transfer
  approval. An impossible cumulative request above 1 GB cannot offer approval.
- Crop imports previously hit the whole-source five-million-node SQLite budget.
  A disposable compact disk index resolves original references with bounded pages,
  while retaining only genuinely covered source geometry in the existing SQLite
  library. Full extent remains separately admitted and retains its original limit.
- A ninth visible chunk previously threw before loading any geography. Vectors now
  load progressively under the shared coordinate budget, independently of the DEM
  budget. Deferred detail is explicit; stored geometry is unchanged.
- Approved regional retries no longer repeat Overpass or stop at its local cooldown.
  The UI reads durable allowance before prompting, including after a lost response.
  Original provider failure history survives retry/restart (latest 64 records with
  an explicit omitted count). Provider queue headroom is separate from the legacy
  synchronous HTTP deadline; explicit short client deadlines remain respected.
- Map selection and conflicting actions remain locked during acquisition/import.
  Import uses the existing abort controller; Stop import cancels its request and
  preserves prior maps. Empty acquisition is not labeled an available partial map.
  Changed selections clear stale previews. Library counts refresh after acquisition;
  the current-map strip uses the saved source name, without a stale size copied
  from a pre-acquisition dropdown label. Task transitions and tab navigation reset
  the shared body scroll so source/budget and recovery messages remain visible
  after working in advanced details.

### Engineering evidence and limits

Local Linux / Go 1.24.7 evidence, before exact-head Actions qualification:

| Check | Evidence/result |
| --- | --- |
| Full Command race suite | PASS, 450.822 s; subsequent source-history regression PASS, 33.738 s |
| Command/test-tools vet; test-tools race | PASS |
| Existing dashboard/download/radius browser and accessibility | PASS in local runs; radius now asserts nine-chunk overview without zoom |
| Controlled production acquisition | PASS: refused and committed consent, every native HGT sample, alignment, offline process restart; explicitly not public-provider acceptance |
| Original regional PBF | 7,812,234-byte Delaware original, 723,870 nodes; independent verification of all 216 requested ways against 1,703 source nodes |
| Real PBF browser durability | PASS: local UI import, SIGKILL, production restart with dead provider proxy, identical stored geometry and offline rendering; original fixture, not a live download |
| Generated large-node workload | 5,100,000 nodes, 15,315,683 input bytes, 122,400,000 compact node bytes; extraction 2.030 s; process peak RSS 98,236 KiB. Generated framing is not an actual 148 MB Uttar Pradesh acceptance run |
| 21-party crop concurrency | 42 durable LIVE/SOS HTTP ACKs under race; p95 78.239 ms, worst 215.326 ms; Linux fixture, not native or field evidence |
| macOS ARM64 / Windows AMD64 | Cross-build checks passed; native runtime NOT TESTED |

The early compact prototype repeatedly decoded entire partitions on real-world ID
patterns and exceeded the race-suite deadline. Paged binary lookup corrected that
measured defect; the real-PBF race regression passed in approximately 32 s.

Per the operator's latest decision, **all real public-provider acceptance runs on
GitHub-hosted Ubuntu Actions**, in the existing `map-software-acceptance` and
`dem-provider` workflow jobs. Both record and assert the checked-out PR HEAD SHA.
The provider job adds the Mathura small-area case and verifies stored vector hashes
and offline rendering independently when DEM is unavailable. The existing 30 MB
cumulative CI ceiling remains enforced. A failed provider or oversized fallback is
reported as failure/blocked evidence, never a synthetic pass. Exact-head statuses,
logs and uploaded artifacts must be inspected and reported in the draft PR before
live acceptance is considered complete.

First runner qualification, source `5a28a3e2d8bd40de90685d82126431c57caff777`,
[run 40153556918](https://github.com/suraj-singh12/gnss-android-tracking/actions/runs/40153556918):
software acceptance passed; downloaded artifact source and controlled approval,
sample/alignment and offline-restart evidence were inspected. Public Delhi and
Mathura 373 m maps acquired 208 and 123 feature occurrences respectively, with
terrain and cold offline rendering passing. Four-chunk boundary and nine-chunk
larger maps acquired vectors and rendered offline, but their terrain acquisition
remained incomplete: required cumulative allowances of 37,785,251 and 33,313,447
bytes exceeded the unchanged 30 MB CI ceiling. The provider job therefore failed;
these scenarios are budget-blocked, not complete terrain passes. The separate
public DEM/UI and bounded online-map probes passed. No public-provider outage was
observed in these results. Exact-head requalification remains required after edits.

The initial integrated run passed Android tests/lint/APK and Command race (221.807 s),
then the repository Markdown contract rejected an anchor link in this section.
The link was corrected to the existing file without weakening the contract.
Later integrated steps did not run on that attempt and are not claimed passed.

Historical Codex Cloud probes returned proxy CONNECT 403 before reaching providers;
those results do not establish an upstream outage or GitHub-run acceptance. The
actual large Uttar Pradesh source remains unverified unless the Actions evidence
substantiate it within authorized transfer/resource limits. Physical Android and
native macOS/Windows acceptance remain separately tracked in #23/#7.


## Issue #27 Recording foundation acceptance

Approved original control images are in `docs/design/issue27/` and linked from #27.
`recording-foundation.cjs` uses production Command HTTP, SQLite and Chromium for
Start, repeated Pause/Resume, active/paused subset Save, exclusive ownership,
refresh/browser return, SIGKILL/restart, final Stop dismissal and Discard. Its
Day/Night screenshots are running-app evidence; approved mockups are not tests.
Delayed animation callbacks cannot produce confirmation at backend receipt time.
A second oracle deliberately blocks actual map application after receipt and checks
the old revision remains authoritative until map application and confirmation. This
establishes browser-applied state, not OS compositor scanout. Exact pinned asset read
revisions, failed publication/retry and concurrent receipt while Save stages are tested.
Long-running 20+ fleets, sustained large-Save latency and native/physical
acceptance remain open. No automated pixel-diff baseline is introduced.

Running-app review at code source `cead27f3a373c5d1135357cb7f79f67fd2845a2e`:
[Day recording](evidence/issue27/rendered/issue27-day-recording.png),
[Night paused](evidence/issue27/rendered/issue27-night-paused.png),
[Day Save](evidence/issue27/rendered/issue27-day-save.png) and
[Night Save](evidence/issue27/rendered/issue27-night-save.png) were inspected against
the approved originals. The rail occupies 44 px; party cards in Save use inline
checkboxes and compact names, with Cancel/Save separated. These are synthetic
five-phone runtime fixtures, not physical GNSS or native OS acceptance. The linked
browser report records delayed actual map application, ownership, recovery and
snapshot results; no test reads these images as pixel-diff baselines.

The expanded Recording browser oracle also drops a confirmation ACK while changing
the viewport, injects a real SQLite display-commit failure, and verifies a saved map
asset after ordinary library deletion. It pages the sealed event stream and compares
reconstructed selected-party checkpoints against the instrumented browser-submitted
presentation sequence, with unique ordered ordinals. This is semantic/geometry evidence,
not pixel-diff baselines or human-attention proof.

## Issue #37 · Saved recordings and playback

`recording-playback.cjs` uses real persisted #27 browser confirmations and Command
SQLite, 21 parties with a 20-party saved subset, actual hide/reveal, pinned offline
vector/terrain, real browser closure and Command SIGKILL/restart overlapping Pause,
and SOS. It verifies deterministic geometry after rewind,
hidden restrictions, Follow Original inspection, four-second notices at 1×/20×,
read-only quality/SOS, descriptive edit/restart and map-library deletion survival.
Runtime screenshots cover populated Day/Night library and playback at 1280×720,
1440×900 and 960×720, open/collapsed panels, inspection and interruption. The timeline's
bounds are checked against the actual map and panel rectangles. `playback-clips.cjs` is an explicit component fixture for legacy omitted SVG
clipping definitions, pinned native core bounds, dateline wrap and labelled
unavailable layers. The main playback suite remains real persisted evidence.
Existing UI, SOS,
control, history and #27 browser suites remain regression gates. No pixel baselines
are introduced.

Real SQLite Go tests cover clock-based delayed redraw/rewind, restart and rejected
historical writes, visibility response filtering, connected interruption overlap/ties,
shared/unshared reclamation and retained operational data. The 1,200-confirmed-state
fixture reports indexed-seek elapsed time, largest response and peak heap growth for
40 seeks; this is bounded-reader software evidence, not multi-day fleet certification.
Integrated Actions runs the new browser suite alongside exact-source race/vet,
Android/bridge and existing map/enrollment/settings/GNSS/SOS checks and matched assets.

Rendering-opportunity confirmations cannot establish OS scanout/human attention.
Unverified intervals, absent pins and uncertain process-death boundaries remain
labelled. Local browser evidence is Linux Chromium; physical Android, native macOS and
Windows operation, sustained dense multi-day capture and actual field SOS/audio remain
operator acceptance work. Public-provider network/transfer failures must be reported
separately from deterministic #37 failures.


## Issue #34 operational modes and Travel Back

Implementation continues on `devX/issue-34-travel-back`, from verified integration
parent `6f435942394b805d5e81129bafa3607a41ae0290` and approved design reference
`cfb0dfdd26bda4259bb60497470bb71bba48aaa4`. Working AGENTS.md matched canonical
guidance `3b60b6d6228ca208df75765e2008c9b9abba8a69`; integration was re-fetched
and remained unchanged before delivery.

The production UI reuses the saved player, sealed presentation source, GNSS
derivation and Command tokens. One mode/context strip selects LIVE, RECORDING
LIVE, VIEWING RECORDING or TRAVEL BACK. Recording-active/paused and Save Snapshot
locks, read-only historical inspection, original visibility gates and Follow
Original remain distinct from capture-time reconstruction. SOS uses retained
capture-quality originals and never promotes a rejected latest report or
acknowledges an emergency during Locate/Go Back.

[Review screenshots and measured browser evidence](design/issue34/final-review/README.md)
cover Day/Night at mobile, tablet and desktop widths. These are genuine application
renderings driven by deterministic protocol fixtures, not native/field GNSS
acceptance. Manual screenshot review checked the approved compact hierarchy,
shared transport, contextual range disclosure, palette, responsive panels and
separate time authorities.

Verified browser entry points: `ui-dashboard.cjs` (including axe),
`sos-dashboard.cjs`, `recording-foundation.cjs`, `playback-clips.cjs`,
`recording-playback.cjs` and `travel-back.cjs`. Saved playback exercises 21 parties
with a 20-party sealed subset, immutable maps/terrain, original visibility,
rewind, process restart and four-second interruption behavior. New assertions
cover recording locks and real SOS return with speed/playhead/Follow Original.
Travel Back covers a 20-phone snapshot, a late 21st phone and missing original
arrivals appearing only after manual Refresh View. Go fixtures additionally
cover 1/5/21 parties, recording OFF/paused history, rejected quality, source
sequence gaps and stale/clock-anomalous SOS location selection.

Android `testDebugUnitTest lintDebug assembleDebug` passed in the managed Linux
environment: 278 reported cases, zero failures/errors, 36 bridge-dependent skips
(the separate real bridge run is required and is reported independently).
Native macOS/Windows runtime, physical Android background/audio and real LAN/field
acceptance remain unperformed. Cross-builds/debug APKs are development acceptance
assets, not release/signature or field-stability approval.

Initial environment setup required local Go/SDK/JDK and the configured proxy CA.
An overloaded parallel verification attempt exhausted memory and invalidated its
browser/Gradle runs; those were rerun sequentially. The initial Command race run
also hit a latency assertion and the default ten-minute suite timeout. A clean
retry passed in 564.382 seconds; final source validation is recorded alongside
the delivered evidence. Failed setup/overload attempts are not counted as passes.

The historical interactive range has an explicit 32 MiB original-envelope budget
and 30-second read deadline; choose a narrower capture range if exceeded.
Exclude Offline uses the contact snapshot at acquisition, not guessed historical
connectivity. Unknown backlog, rejected observations and missing evidence are not
claimed complete or converted into fresh LIVE positions. ZIP/import remains #38.

Final automated verification for the implementation worktree:

| Check | Result |
| --- | --- |
| Command `go test -race ./... -timeout 30m` | PASS, 429.170 s |
| Command authority/SOS priority race cases | PASS, including a held historical read connection |
| Command `go vet ./...` | PASS |
| test-tools `go test -race ./...` and `go vet ./...` | PASS |
| Android unit/lint/debug APK | PASS; 242 executed unit cases, 36 bridge-dependent skips, no failures/errors |
| Real Room/Sender → Command bridge | PASS; 36 cases, zero skips/failures/errors, 21m 47s |

The bridge run replaces the ordinary unit run's bridge skips with actual execution;
it is not counted as a skipped integration pass. The final Go retry used the
workspace temporary volume after the small `/tmp` volume fell below the existing
3 GB map-source reservation. Installer archives and superseded builds were removed;
no storage or transfer guard was weakened.

Travel Back's policy, receipt counter, contact filter and party styles come from
one read transaction. Later receipt cannot hide a pending change through a stale
browser counter. Startup waits for the recording state, active intention remains
locked during unverified presentation, and Go Back is also locked if a recording
is started/paused while temporarily away in LIVE. Selected-range metadata may be
listed before a phone's first fix; no future position is rendered.
