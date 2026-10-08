# Issue #2 reliability self-review

Source review against the frozen Protocol v1 and Issue #2 checklist. These are
implementation/automated-test findings, not claims of physical acceptance.

| Check | Finding/evidence |
| --- | --- |
| A: Screen off | User-started location foreground service owns GPS callbacks/reporting; ongoing notification; hardware/OEM acceptance remains required. |
| B: Scheduling | No WorkManager, alarms or boot auto-start. Issue #4 adds a service-owned, timeout-bounded partial wake lock renewed only during active tracking. Reporting and one sender run under the same service. |
| C: Save first | Room transaction validates/serializes, allocates sequence and inserts outbox. Rollback-on-insert-failure test verifies both writes roll back; transport receives only committed rows. |
| D: Sequence | Single installation row, transactional allocation, wire bound check, concurrent allocation/reopen tests; reset creates new UUID. Cloud/device transfer excluded to prevent restoring an old sequence. |
| E: Retry identity | Sender posts stored JSON; drop-response/duplicate test verifies unchanged payload, ID, sequence, timestamp and config. |
| F: Current priority | Reserved SOS first, newest current above delivered boundary, oldest eligible backlog; new reports interrupt drain. Recovery saves current if stale. Connectivity restoration clears persisted retry deadlines only for pending, non-quarantined rows under the sender mutex before priority selection; fresh saved current is reused. Normal backoff and permanent pauses remain intact. One HTTP call has a total 10-second deadline. |
| G: Local live state | Only native location callbacks update LatestLocation, ordered by monotonic observation time. ACK/backlog never update local GNSS. |
| H: ACK safety | Strict parser rejects duplicate keys, malformed receipt, wrong identity/version/sequence/result. Repository validates identity again. Only valid HTTP 200 receipt clears pending. |
| I: Config versions | New applies transactionally; old ignored; equal conflict visible; invalid config preserves prior state. Override/local fallback and restart tests cover this. |
| J: Authority | Different authority rejected until explicit confirmed re-enrollment. Address edit retains authority. Late successful/error responses are gated by endpoint generation. |
| K: Cadence | Local and remote fields persisted separately; effective recomputed. UI shows all applicable values; newer applied config reschedules future reports without catch-up bursts. |
| L: Nullability | Native has-flags, finite/range validation and elapsed measurement age. Missing/stale fixes produce status; all nullable wire fields present. Golden fixtures cover location/status/SOS reservation. |
| M: Android rules | Precise + coarse request together; location FGS permission/type; immediate foreground promotion; API 26–28 overload guard; optional notifications on 33+; no background-location permission. Lint checks supported API access. |
| N: Dependencies | One module; Room/KSP, coroutines, Gson, OkHttp only for persistence, execution, wire format and total HTTP deadline. Native Views/GPS; test-only JUnit/Robolectric. |
| O: Boundaries | Product files only android; Go mock only test-tools/mock-receiver. Command, shared contracts/tests, docs and root README untouched; #3 branch never inspected. |
| P: Wire semantics | Golden fixture roundtrip, ACK parsing and Go contract regression. No recording/session IDs, invented health, control polling or protocol changes. SOS workflow remains reserved for #5. |

ACKed snapshots are retained and pending/quarantined snapshots are never evicted.
Storage exhaustion is an explicit operational error; retention/export controls
remain future work. UI is English and operational, with 48 dp controls, labels,
scalable text and inset handling. Last ACK reflects local successful contact time;
first remote receipt time remains separate delivery metadata.

Build/unit/lint results are reported in the PR. Follow
[device acceptance](DEVICE-ACCEPTANCE.md) for locked-screen, 30+ minute, Wi-Fi and
process/OEM validation. No device/emulator is attached in the cloud environment.

## Issue #5 SOS self-review

This independent branch starts at the frozen Issue #4 commit
`8fbcabbc2443aae75178a50160ee1248830611dd`; PR #11 and its temporary build/field
asset branch remain untouched and unmerged. No Issue #5 APK was installed or deployed.
The original GNSS stall remains unresolved. The final Issue #5 SHA and validation
results are reported with the Issue #5 PR; they are not a physical acceptance claim.

| Review | Result / limits |
| --- | --- |
| Architecture | One app-owned central SOS engine and one existing Sender/LanTransport. One installation/sequence allocator and Room outbox; existing Command HTTP handler/raw/state/evidence transactions. No new persistence table, wire field, independent control channel, GNSS acquisition or product dependency. |
| Lifecycle | App scope owns activation so Activity destruction cannot cancel the handed-off save. TrackingService retains native registration, wake resources and reporting. A stopped visible Activity selects SOS only; no extra ordinary reporting. Startup inventories SOS before new engine saves. |
| Concurrency | Room transactions serialize trigger debounce/allocation; engine and sender mutexes bound their critical sections. Network does not hold the engine mutex or save transaction. One finite HTTP request; SOS bypasses ordinary backoff, uses durable per-event deadlines and allows tracking between failed attempts. Endpoint generation/binding rules remain. |
| Truthful location/time | Existing LatestLocation only. Fresh, stale/disabled last-known, unavailable, unknown accuracy and clock anomalies stay explicit. Original trigger time is captured before coroutine handoff; snapshot/measurement/receipt/human ACK times are separate. No wait for a fix and no fabricated coordinate. |
| ACK semantics | Strict existing v1 validation and Repository identity/generation recheck. Attempt, timeout, malformed/wrong ACK or rejected HTTP cannot mark delivered. Duplicate retry preserves first receipt and cannot reset Command human ACK. Phone cannot know human ACK; UI marks it Command-only. |
| Recording | SOS never becomes a track point. Start/Stop/Resume/Clear retain alerts, raw identities, receipt and independent per-event human acknowledgement. |
| Evidence/privacy | Typed nonblocking existing Android recorder, retained JSONL/ZIP and new SOS assessments. Command evidence shares the operational transaction; SOS export identities use a bounded SHA-256 prefix. No emergency coordinates/Party/raw payloads in diagnostic exports. Existing ordinary Command identifier contract remains. Unknown Wi-Fi cannot prove offline; same-process reads cannot prove restart; dropped/pruned/crash-unflushed records remain explicit/inconclusive. |
| Security | Existing trusted local LAN HTTP(S), Wi-Fi socket/DNS routing, finite bounded responses and strict schema/ACK rules. Local-only same-origin JSON acknowledgement pairs device+event; text-node UI rendering avoids label HTML injection. No root/private API/device-owner/accessibility privileges or unrelated-app content collection. |
| UI/accessibility | Prominent hold-to-activate screen control, accessible long-click action, immediate detection/save distinction, retained event status and explicit key limitations. Command event cards preserve keyboard focus, keep unacknowledged events first, and expose blocked audio. Human ACK is idempotent, retained and timestamped. |
| Retention/capacity | No automatic operational SOS/raw/outbox deletion, including delivered/acknowledged events. Existing diagnostic retention remains bounded; full storage surfaces errors. High-volume/endurance capacity remains Issue #7. |
| Physical scope | Volume adapter is opt-in and foreground-only; three short release pairs, holds/repeats/mixed/canceled events reset, normal volume still works. Locked/off screen and other foreground apps are unsupported. Hardware keys, GNSS stall, actual Wi-Fi routing/Doze/OEM recovery, document picker, speaker audibility and target-OS binaries need physical acceptance. |

The canonical [device acceptance](DEVICE-ACCEPTANCE.md) has the minimum separate SOS
field run. Do not perform it by replacing the frozen Issue #4 physical-test APK.
Protocol v1 fixtures retain their original envelopes and ACK-piggyback configuration.
The test-only browser/harness additions introduce no field runtime dependencies.

### Issue #5 automated validation report (2026-10-08)

All checks below passed on this implementation. Go 1.24.7, JDK 21, Android SDK 35 /
build-tools 35.0.0, Gradle wrapper and a real Chromium browser were used in cloud.
These results establish software behavior, not hardware acceptance.

| Check / reproduction | Final result |
| --- | --- |
| Android `testDebugUnitTest --tests 'org.gnss.tracking.Sos*Test' --tests 'org.gnss.tracking.SenderTest' --tests 'org.gnss.tracking.RepositoryTest'` | 39 passed; zero failures/errors/skips |
| Android `testDebugUnitTest` | 124 passed; zero failures/errors; 18 opt-in bridge cases skipped here and executed separately below |
| Android `assembleDebug` / `lintDebug` | Both passed; lint reports zero issues |
| `test-tools/integration/run.sh --console=plain` | All 18 passed; zero failures/errors/skips. Every original tracking scenario remains included |
| Command `go test -json ./...` / `go test -race ./...` / `go vet ./...` | 83 tests/subtests passed; race and vet passed. The test-only bridge process adapter skips in ordinary Go tests and runs in the bridge above |
| Test-tools `go test -json ./...` / `go test -race ./...` / `go vet ./...` | 69 tests/subtests passed, including 44 protocol/fixture/document checks; race and vet passed |
| Command `CGO_ENABLED=0 go build ./cmd/party-tracker` with target GOOS/GOARCH | Darwin arm64, Windows amd64, Linux amd64 passed |
| `test-tools/integration/sos-dashboard.cjs` against a built Command binary | Real HTTP/SQLite/embedded dashboard passed keyboard ACK/focus, blocked audio indication, duplicate/multiple SOS, reload/process restart and Recording Clear |
| `node --check command/web/app.js` and `node --check test-tools/integration/sos-dashboard.cjs` | Both passed |
| `gofmt`, protocol fixture/schema diff, `git diff --check`, artifact/privacy/scope review | Passed; original v1 fixtures/schema unchanged; no build artifacts or credentials committed |

The bridge uses production SosEngine, immutable Room rows, priority selection,
real HTTP ingestion/SQLite commit and strict ACK parsing. It verifies a saved SOS
with 100 competing reports and stale GNSS, offline creation, Room reopening, a lost
real ACK after Command commit, identical duplicate retry, remote five-second
configuration adoption, Stop/Resume/Clear independence, two distinguishable events,
per-event idempotent human ACK, both persistence boundaries and resumed ordinary
tracking. Android and Command assessments correlate through the opaque event
reference; missing evidence remains INCONCLUSIVE. A second scenario rejects a
wrong ACK and proves later duplicate delivery cannot reset an existing human ACK.

Focused tests additionally cover concurrent/reversed trigger handoffs, debounce
across reopen, insert rollback/sequence rollback, unknown health, null/stale/disabled
GNSS, ordinary backoff bypass and tracking progress between failed SOS attempts,
Activity destruction/hidden Activity with service active, same-process restoration
rejection, bounded diagnostics and identity/coordinate exclusions. Command tests
cover conflicting duplicates, wrong device/event pairing, acknowledgement races,
additive legacy projection and SOS-only aggregate privacy during recording controls.

The debug APK and Command binaries are rebuilt after the final commit to identify
that exact clean source SHA; the SHA and PR are recorded in the PR/final handoff.
No APK installation or deployment occurred. Real Android Wi-Fi routing, foreground
key interception/timing, locked-screen delivery/Doze/OEM behavior, native GNSS
freshness during the unresolved Issue #4 stall, OS service/process recovery, speaker
audibility, document-picker export and target-OS execution remain physical-only.
Locked/off-screen physical-key activation is unsupported, and force-stop/reboot/OEM
kill offer no automatic transmission guarantee. Follow the separate eight-step
[SOS device acceptance](DEVICE-ACCEPTANCE.md#issue-5-sos-acceptance--separate-from-issue-4-physical-testing).
