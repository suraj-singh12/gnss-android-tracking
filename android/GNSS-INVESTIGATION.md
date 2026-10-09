# Issue #4 GNSS continuity investigation

PR #11; Android API 26–35, target SDK 35. **The physical failure is not proven
resolved.** Authoritative evidence is healthy Command contact while GNSS ages,
often recovering after opening the Activity. The new A/B test is decisive evidence that GPSTest activation can restore fresh
coordinates to our still-running listener; it does not yet identify the platform cause. This report records the code/platform audit, not a hardware result.
The field procedure remains [DEVICE-ACCEPTANCE.md](DEVICE-ACCEPTANCE.md).

## Assessment

**CONFIRMED:** the supplied network-alive/GNSS-stale symptom and the code failure
paths reproduced below. No single cause of the on-phone incident is confirmed.

| Cause investigated | Assessment and evidence |
| --- | --- |
| FGS declaration/promotion/notification | **FIXED PREVIOUSLY / RULED OUT as a current declaration defect.** Location type, FGS and location-FGS permissions, fine/coarse permission, ongoing notification, immediate promotion and registration are present. API 26 uses two-argument promotion; API 29+ explicitly promotes location type. No Activity pause/stop/destroy demotion or source cleanup exists. Notification denial does not grant location permission or stop a valid FGS. Android 15's data-sync/media-processing time limits are not location-service limits. |
| Async visible Start → hidden Activity before service creation | **FIXED IN THIS RUN; incident attribution STILL UNKNOWN.** Saving settings suspends on Room; the Activity scope survives onStop. Previously completion could start the service from the background. Android 30–33 can allow a service without effective location access, while networking continues; API 34+ may throw at creation. Foregrounding can change access. Explicit start now rechecks Activity visibility after storage/permission work. Opening/resuming alone never starts or restarts GNSS; the later final-candidate change below preserves an explicit pending Start through prerequisite resolution. A transition after the final visibility check is still subject to Android enforcement. |
| Background-location permission | **RULED OUT as a requirement for ordinary tracking.** A user-visible location FGS may continue when Home/lock hides the Activity. No background location collection entrypoint or boot receiver exists. Adding background permission would expand scope and conceal the startup defect. Fine permission alone does not prove while-in-use access is currently effective. |
| Full service/process death | **Unlikely sole cause of this incident; DEVICE/OEM DEPENDENT.** Continued Command contact contradicts complete death throughout the failure. Reclaim/recreation can still happen between observations. START_STICKY and null-intent persisted-state restoration remain deliberate. System sticky restarts are exempt from the API 31 general FGS background-start restriction; this is not a universal promise of location permission, OEM restart or GNSS delivery. New generation/start-reason diagnostics distinguish recreation. |
| CPU sleep/lock renewal | **FIXED PREVIOUSLY + FIXED IN THIS RUN.** Partial non-reference-counted lock, 10-minute timeout, renewal after 5 minutes and finally-release are retained. Renewal was coupled to Room/reporting; blocked startup or a dead reporting coroutine could let it expire. Independent service maintenance now renews before any power/provider check and never waits for Room/network. Held does not mean effective during Doze/Low Power Standby. |
| Doze / App Standby | **DEVICE/OEM DEPENDENT.** Active FGS avoids ordinary App Standby treatment, not every Doze restriction. Deep Doze can ignore wake locks and network access; continuous healthy networking makes sustained full deep-Doze less compelling than a location-specific policy, but does not rule out intermittent idle windows. Device-idle/interactive/allowlist diagnostics cannot prove a vendor's hidden policy. |
| Battery Saver / location power-save / Low Power Standby / thermal | **DEVICE/OEM DEPENDENT; actual incident state STILL UNKNOWN.** Public location-power modes can disable GPS/all location with screen off or throttle delivery; foreground-only mode permits location FGS operation. Low Power Standby can ignore locks/network even for an FGS. Thermal/low-battery restrictions must not be bypassed. Public state is recorded where API supports it; enabled policy is not proof it was actively suppressing this app. |
| OEM optimization/freezer/task killer | **DEVICE/OEM DEPENDENT.** Foregrounding commonly changes process/power eligibility. No model/settings trace was supplied to distinguish OEM GNSS restriction, background denial, autostart or app freeze. Document operational setup instead of private APIs or automatic exemptions. |
| Native registrations / optional satellite telemetry | **FIXED PREVIOUSLY + FIXED IN THIS RUN.** Idempotent registration and partial-failure rollback remain. A false return from optional satellite-status registration previously aborted GPS collection; GPS now proceeds with satellite telemetry unavailable. Thrown registration/permission failures still fail safely. This startup defect alone does not explain loss after many valid fixes. |
| Main Looper / callback thread | **RULED OUT as Activity ownership; scheduling risk STILL UNKNOWN.** Main Looper belongs to the process, not an Activity. Callbacks perform short measurement validation/state updates, not Room/HTTP/UI work. Activity destruction does not quit it. Moving to a HandlerThread cannot fix permission/OEM policies; no evidence justifies another thread. Sample/loop gaps help detect scheduling stalls next time. |
| GNSS status vs observation semantics | **FIXED IN THIS RUN for late callbacks; existing freshness RULED OUT as fake refreshing.** All provider/status callbacks now ignore delivery after stop; satellite data clears on engine/provider stop. Engine-start/first-fix/satellite callbacks never create coordinates or refresh measurement time. A recent genuine observation may remain usable for the frozen 30-second freshness window even when satellite telemetry stops. |
| Callback starvation/recovery | **STILL UNKNOWN physically; diagnostics added.** Location callback delivery time, GNSS callback delivery time, engine state and observation time are distinct. Five minutes without either stream while logically registered is flagged as callback silence, not a diagnosis or a recovery trigger. Indoors/no sky, throttling and foreground gating can produce silence. No blind listener restart was justified. |
| Activity/process importance | **FIXED IN THIS RUN for start race; RULED OUT as direct acquisition coupling.** No Activity lifecycle code starts/stops/re-registers an existing source. Activity reads shared repository/operational flows and handles explicit user commands. Process importance and raw fine-location AppOp are captured; MODE_FOREGROUND is conditional policy, not evidence access is currently allowed. |
| Wi-Fi / sender interactions | **RULED OUT as direct source mutation; FIXED IN THIS RUN for exception handling.** Network callback only resets retry timing; sender never touches native registration. Connectivity storage exceptions are now caught/surfaced rather than escaping a supervisor child to Android's uncaught-exception handler. The production failure test leaves source and lock active. |
| Coroutine/reporting failure | **FIXED IN THIS RUN.** Previously reporting/sending were children of one startup coroutine, so its ordinary child Job could couple failures despite the outer SupervisorJob. Initial reporting state read was outside retry handling. Roots now share the supervisor, reporting initialization is retried inside its loop, network failures are isolated, and unexpected failures explicitly request service shutdown with an error rather than a misleading half-running service. These defects need not match a location-only loss; diagnostics can distinguish loop stalls. |
| Room/disk/startup blocking | **FIXED PREVIOUSLY + FIXED IN THIS RUN.** Native registration precedes Room. Lock/diagnostic maintenance now also precedes and is independent of Room. Startup cleanup no longer tries another write to the store that failed before stopping. A disk stall can still prevent save/send; save-before-send is preserved, not bypassed. Diagnostic writes are independent, bounded, and may also fail on broken/full storage. |
| Provider toggles / precise permission changes | **FIXED IN THIS RUN for observable permission-loss handling; normal provider behavior RULED OUT as missing registration.** Disabled GPS reports disabled; enabled GPS resumes the retained request without Activity work. Maintenance checks provider state, marks query failures unknown, and stops/releases on precise-permission loss with a grant-and-explicit-restart instruction. Android may kill the process on revocation before this check. No automatic permission/restart promise. |
| Swipe / force-stop / reboot | **DEVICE/OEM DEPENDENT, outside normal screen-off acceptance.** No stopWithTask or Activity-driven service stop. Ordinary task removal normally leaves a started FGS; OEM task managers may differ. Force-stop intentionally prevents automatic recovery; no boot restart is implemented. Both require explicit visible Start Tracking. Sticky reclaim simulation is not an OS/OEM kill test. |

## Changes and deterministic evidence

- `MainActivity`: guard the existing user start after asynchronous settings/permission
  work. Test hidden completion, resume without automatic start and explicit visible start.
- `TrackingService`: independent maintenance/lock renewal; root supervised sender and
  reporter; state-read failure handling; isolated connectivity storage error; startup
  shutdown independent of failed storage; precise-permission loss handling; cleanup
  attempts all resources even if a binder fails. Tests hold a real Room transaction
  across startup/renewal, inject a real SQLite update failure on connectivity recovery,
  destroy/recreate the actual service with null intent, and downgrade precise permission.
- `LocationSource`: optional satellite registration is independent of GPS fixes;
  post-stop callback guards and separate elapsed callback/rejection/engine diagnostics.
  Native-boundary tests inject partial registration failure, false satellite registration,
  provider off/on, acquisition/stopped events and unusable measurement timestamps.
- `GnssDiagnostics` / `TrackingApp`: the original 64-sample atomic journal is
  superseded by the incident recorder below. Public power and live diagnostic seams
  remain; acquisition ownership and callback scheduling are unchanged.
- Activity adds one actionable field-setup warning and a standard app-settings button.
  It does not request allowlisting, launch itself, or display a developer console.

Previous immediate registration, idempotence and startup rollback are correct and
retained; optional telemetry failure handling and cleanup were strengthened. Previous
FGS configuration is correct and unchanged. The bounded lock was correct but its
renewal scheduling was insufficiently isolated. No evidence supports fake refresh,
Activity keepalive, dedicated callback thread, permission expansion, polling another
location provider, or automatic service/listener restart loops. Protocol v1 (including
5-second steps), sender/outbox, Command, track engine and LAN security are unchanged.

## Platform evidence and limits

Reviewed Android's public requirements, not an OEM firmware implementation:

- [FGS background/while-in-use restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Foreground/background location access](https://developer.android.com/develop/sensors-and-location/location/permissions)
- [Android 8 background location limits](https://developer.android.com/about/versions/oreo/background-location-limits)
- [Service lifecycle and sticky restarts](https://developer.android.com/reference/android/app/Service)
- [LocationManager callbacks](https://developer.android.com/reference/android/location/LocationManager)
- [PowerManager modes, standby and thermal APIs](https://developer.android.com/reference/android/os/PowerManager)
- [Doze/App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Runtime and one-time permission lifecycle](https://developer.android.com/training/permissions/requesting)

OEM setup guidance reflects public battery-management concepts, whose menu names
vary by model/firmware: [Samsung sleeping apps](https://www.samsung.com/us/support/answer/ANS10003442/),
[Xiaomi background autostart](https://www.mi.com/global/support/faq/details/KA-497677/),
[Realme app battery management](https://www.realme.com/global/support/kw/doc/2085394),
and [Motorola Battery Saver](https://en-us.support.motorola.com/app/answers/detail/a_id/188970/~/battery-saver-mode).
Oppo/Realme/OnePlus, Vivo/iQOO and other vendors may expose background activity,
autostart, auto-freeze or high-background-power controls. No reliable universal
public API reports or disables all such controls.

Automated tests prove app lifecycle/state/failure handling and real Android↔Command
logic, not real satellites, Doze, effective while-in-use policy, OEM freezing,
GNSS power draw, or physical restart behavior. The exact incident cause remains
unknown until a sustained unplugged run captures these diagnostics. A passing
hardware run, not this audit, is required for Issue #4 physical acceptance.

## Automated verification of the GNSS hardening baseline (`1f3ecbed`)

- Focused GNSS/lifecycle tests: 18 passed, including the 3 retained lifecycle tests.
  Final full run also covers overlapping journal writers; 15 new tests in total.
- `./gradlew testDebugUnitTest assembleDebug lintDebug`: passed. 46 ordinary
  tests passed; 16 opt-in integration tests skipped here and executed below.
  Debug APK built; lint reports zero issues. Tested API 26/28/35 boundaries in
  Robolectric, not a physical Android version/OEM matrix.
- `test-tools/integration/run.sh`: passed, 16 tests, zero skips/failures/errors,
  ~5m15s. Real Android Protocol/Room/Sender and race-instrumented Command process;
  delivery/dedupe/permutations/GNSS quality/config/5-second contract/recording/both
  persistence restorations and routing security retained.
- Command and test-tools `go test ./...`, `go vet ./...`, `go test -race ./...`:
  passed. Protocol/fixture/document consistency also rerun without test cache.
- `git diff --check`, integration shell syntax and artifact review: passed.
  No Protocol, Command, test-tools, manifest or build-configuration change in this
  hardening update; prior PR #11's explicitly authorized 5-second amendment remains.
  Go cross-build/browser results from the previous update are unchanged, not rerun.
- One earlier integration attempt was canceled by an execution-client disconnect;
  the detached complete rerun above exited 0. No failed application assertion was
  reported by that canceled run; its partial execution is not counted as a pass.

Confidence: **READY FOR TARGETED PHYSICAL RETEST.** Known code failure paths are
addressed; no further speculative acquisition/recovery change is justified without
the new physical trace. This is not a claim that screen-off GNSS is solved, nor
permission to field-accept Issue #4 before its sustained hardware run passes.

## Final candidate: startup, preflight and stale display

A separate startup regression was reproduced against `1f3ecbed`: a requested
precise-permission result delivered after Activity stop cleared `startAfterPermission`
before the visibility guard rejected service creation. Resume had no remaining
intent, so a second Start was required. Asynchronous settings-save completion while
hidden could similarly lose the request. This explains a code-level second-tap
path; without a phone-side startup trace it does not identify which path occurred
in the reported incident.

The Activity now retains one explicit pending Start until settings are committed,
permissions/provider/notifications/Saver are ready and it is resumed. Loading
settings disables Start; Starting disables repeat submissions. Service startup
reports Active only after its durable initial state/snapshot and network registration
complete; native GNSS registration still happens immediately after FGS promotion.
Startup failures are actionable and clear the Starting state. Activity restoration
retains pending prerequisite resolution; an interrupted settings save is explicitly
canceled to avoid using uncertain settings. Closing the Activity cancels unsubmitted
intent. Neither screen visibility nor Settings return alone starts an existing or
new source.

Field readiness independently reads precise permission, GPS provider, runtime/app/
Tracking-channel notification permission, Battery Saver and the Android optimization
allowlist on every resume. Supported Settings actions resolve non-requestable states;
no exemption is requested automatically and OEM Unrestricted/background/autostart
requirements still need human verification.

The UI now retains last-observation accuracy and labels stale age/accuracy as last
known. `LatestLocation.currentFix()`, the 30-second boundary, native callback strategy,
wire serialization were unchanged in that update. The later recorder update below
evolves only private diagnostic persistence/export. The canonical
runbook includes the GPSTest comparison before reopening our Activity.


Final-candidate verification: 30 focused tests passed (23 added startup/display
executions plus 7 retained lifecycle tests). Full Android suite: 69 passed, zero
failures/errors; 16 opt-in integration tests skipped there and all 16 passed
separately with zero skips/failures/errors in the race-instrumented real bridge.
`assembleDebug` and `lintDebug` passed, zero lint issues. Command/test-tools
unit tests, vet and race checks passed; uncached protocol/fixture/document checks
passed. Robolectric UI tests now include actual Android resources. Scope/diff and
artifact review passed: this candidate changes ten Android source/test/build/doc
files, with no Command/Protocol/test-tools changes or committed APK/database.


## Self-preserving incident recorder

The latest physical A/B result is **CONFIRMED**: service/5-second ACKs stayed alive,
precise permission/provider/notifications were present, Saver was off and Android
optimization exemption was present, while GNSS became stale. Opening GPSTest
obtained a coordinate and our existing listener immediately received fresh fixes.
The actual stack/OEM cause remains **UNKNOWN**. This update records evidence and
adds **no acquisition/recovery change**: same GPS_PROVIDER request, main Looper,
foreground ownership, wake policy, 30-second freshness and Protocol v1.

`TrackingApp` owns one independent supervised recorder worker. Native callbacks
only update existing in-memory source state and nonblocking-enqueue typed events;
no file/Room/network access occurs there. The bounded FIFO holds 512 messages;
overflow is counted, not silently conflated. Writer failures are contained and
counted; tracking loops, native registration and wake renewal remain independent.
Export is queued behind prior records, creates one temporary ZIP on that worker,
then copies to Android's selected document on separate Activity IO. Slow external
storage does not block callbacks; a slow/private disk can drop queue entries and
those counts are explicit. A process kill can lose unprocessed entries; a
crash-safe file is not a promise that every queued event already reached disk.

Private layout: `files/diagnostics/timeline-*.jsonl` and
`incident-*/{summary.json,pre.jsonl,events-*.jsonl}`. Append-only UTF-8 JSONL uses
complete lines and fsync; small summaries/pre-context use AtomicFile. Startup
repairs malformed/torn records while preserving valid evidence. Open incidents
from a prior process are marked interrupted, not retrospectively recovered.
Normal samples persist every 30 seconds; five-second samples and immediate events
populate a five-minute memory pre-buffer. State/power changes are saved when
observed by the one-second maintenance loop, even between coarse samples.

An incident freezes the pre-buffer on stale measurement (>30 s), independent
≥5-minute Location/GNSS-status silence (status only if registered), provider off,
precise permission lost, registration/foreground failure, sticky restart, or loop
failure. Silence/indoors is evidence, not proof of stack failure, and invokes no
restart. An ordinary user stop/start is not an unexpected recreation trigger.
Native events cover registration attempt/result/unregister, provider changes,
Location acceptance/rejection/measurement age, GNSS engine start/stop/first-fix/
satellites, wake acquire/renew/release, Activity visible/resumed/background,
service create/start(reason)/foreground/destroy and loop failure. Samples carry
separate counters/ages, current versus last-known accuracy, permission/AppOp,
process importance, screen/idle/Saver/location-power/standby/thermal/optimization,
lock, report/sender/snapshot progress, effective interval, cached Wi-Fi and delivery
state. ACK age is time since observing a changed persisted ACK, not corrected
Command time; it stays unavailable at restart until a new change. Pending count now comes from the existing Room DAO Flow collected by a service-owned
IO child; diagnostic snapshots only read the volatile cached value. Initial/read
failure is null (never invented zero), with `pendingOutboxError` set on failure;
a bounded five-second re-subscription permits recovery. Count/availability changes
are enqueued immediately to the same recorder. No Activity, native callback,
wake-lock renewal or one-second maintenance queries Room. Diagnostic write failures
cannot cancel acquisition/reporting/sending. The Android manifest now identifies
full Git HEAD for correlation with Command's field report.

Incidents sample every 2 seconds plus native/lifecycle transitions until a real
accepted Location callback with ≤30-second measurement age arrives with provider
and precise permission present. `FIRST_LOCATION_AFTER_STALE` and `GNSS_RECOVERED`
include exact callback UTC/elapsed timestamps and counters; engine/satellite
callbacks never invent recovery. Summary `before`/`after` contains the requested
source/power/loop evidence. Stale duration runs from the measured 30-second boundary,
not the later detection/export time; completed stale episodes are summed if the
same incident relapses. Post-recovery capture lasts five minutes;
relapse is retained within the same incident and restarts that window. If only
Location resumes while satellite telemetry remains silent, the evidence shows it.

Limits: six recent incidents, seven days; 256 KiB rotating chunks; 4 MiB coarse
timeline and 4 MiB recent event history per incident; pre-context ≤1 MiB/1200
records; total ≤32 MiB. Start/pre-context/summary survive event rotation; truncated
middle history is flagged. Count/byte/age rotation may remove old incidents;
export promptly after a run and retain its ZIP. Active incidents are not aged out.
A stopped service finalizes evidence without claiming recovery. No automatic
clock correction or diagnosis such as “OEM killed GNSS” is stored.

**Export diagnostics** uses Android ACTION_CREATE_DOCUMENT, works offline and
includes only manifest/build/source revision and retained typed recorder files.
It does not alter tracking or clear evidence. Sanitization removes arbitrary error
text and unknown/injected keys. No coordinates/altitude, Party/device UUID,
Command URL/IP, SSID/password, raw protocol or database data enter the bundle.
Service-generation UUIDs are process-local diagnostic IDs, never device identity.
See [DEVICE-ACCEPTANCE.md](DEVICE-ACCEPTANCE.md) for the field export and GPSTest
sequence. ADB is optional developer corroboration, not the primary collection path.

Automated verification for this recorder update is recorded with the PR. JVM tests
cover retention/transitions/privacy/failures and actual service/source/export seams;
real GNSS, GPSTest activation, screen-off/Doze/OEM scheduling, battery/storage costs
and the phone's document picker still require the unplugged physical retest.


Initial incident-recorder verification (4b2bc3d): full `testDebugUnitTest` **101 passed, zero failures/errors**;
16 opt-in bridge cases skipped there and **all 16 passed separately** using the
race-instrumented real Command process. The final full run includes **50**
recorder/export/source/service checks: 23 incident cases, six document-export cases
(APIs 26/28/35), and retained/expanded native lifecycle and power tests. The old
64-sample journal contract is replaced by bounded incident/reopen/rotation/privacy
coverage; previous startup, display, sender, Room and Protocol assertions remain.
`assembleDebug` passed; final `lintDebug` reports **zero issues**. Uncached shared
contract/fixture/document checks: **44 tests/subtests passed**. `git diff --check`
and scope/artifact review passed. No Command, Protocol, test-tools or GNSS
acquisition/recovery changes; no APK/database/generated files are committed.

Expanded API testing exposed Robolectric 4.14's native fonts ZIP/SQLite runtime
contamination across SDK/shadow sandboxes. Each test class now has a fresh Gradle
fork; document-export APIs have separate test classes, and app-owned recorder IO
is drained on teardown. The satellite builder boundary runs on API 30, where that
public API first exists. This keeps all assertions; it is test isolation, not a
phone workaround. Early failing test-runner attempts are not counted as passes.
No hardware/GPSTest/screen-off acceptance was executed by this verification.

Pending-outbox completion: four additional deterministic checks use the real Room
Flow/ACK path and service without an Activity, suspended observation/cached reads,
and failure injection (null/error, never guessed zero). One recorder check verifies
immediate count transitions and later export. Count/error are one immutable volatile
snapshot; publications use the existing main diagnostic path while Room collection
stays on IO. Service cancellation ends the observer; writer/publication failure does
not stop tracking. See Command README and DEVICE-ACCEPTANCE for paired persistent
field evidence and full-revision ZIPs. GNSS acquisition/recovery is unchanged.

Field-evidence verification: Android **106 passed**, zero failures/errors; the
16 opt-in bridge cases skipped in that full run **all passed separately** against
the real race-instrumented Command. The full run includes **55** diagnostics/export/
source/service checks. `assembleDebug` passed and `lintDebug` found zero issues.
Command **77 tests/subtests passed**, including 22 new field-evidence checks;
Command race/vet passed. Test-tools **69 tests/subtests passed**, including all 44
contract checks; race/vet passed. Command cross-builds for Darwin arm64, Windows
amd64 and Linux amd64, dashboard JS syntax and diff checks passed. These are
application-boundary checks, not physical GNSS/Wi-Fi/Doze acceptance.

## Latest field exports: run 3 (2026-10-08)

Both latest `3-` manifests identify `8fbcabbc2443aae75178a50160ee1248830611dd`;
the earlier exports are cumulative context, not independent trials. In this run:

- **Confirmed:** native callbacks continued through the ~927-second Command receipt
  gap (12:35:31 → 12:50:58 UTC). After initial acquisition the phone's current-fix
  flag stayed true until service destruction; one source registration, no rejected
  observations, Saver/idle/standby off, provider and wake lock present. No GNSS
  acquisition change is justified by this run.
- **Confirmed:** during the current recording Command received 757 delayed routine
  statuses and only one delayed location report. Current positions arrived, but
  thousands of Android envelopes remained pending (4,227 at stop). Earlier no-fix
  status history competed with the desired offline route. No pending data was
  proved lost; the rectangle cannot be reconstructed from these coordinate-free
  exports or certified complete before history delivery.
- **Confirmed code defects:** the dashboard drew only recorded track points; live
  source validity consulted historical track decisions. Quality edits applied to
  new windows only. A status-only current-first verdict and old cadence estimate
  could misleadingly suggest tracking success. These are corrected at their owners.
- **Unknown / device dependent:** radio reassociation delay while locked. Previously
  exported Wi-Fi availability does not establish radio enablement/association.
  A healthy GNSS stream does not prove route/HTTP availability. New diagnostics
  distinguish radio, route, internet validation, IP-family availability and ACKs.
- **Ruled out for the final long gap:** the service was explicitly destroyed at
  12:53:39 UTC, with source unregister and wake release. No later service samples
  precede the 14:20 export; this is not evidence a running sender failed for 87 min.

Recorder additions reuse the isolated writer: asynchronously cached Room pending,
blocked and delivered counts by type; opaque saved/send/retry/valid-ACK references;
cached network state and monotonic sender attempt/ACK/retry timestamps. Network
link changes and actual IO failures invalidate network-bound pooled sockets/DNS.
Wi-Fi selection still permits an offline router with no internet validation and
never falls back to mobile data. No association forcing or GNSS recovery is added.
No addresses, SSIDs, coordinates, Party/device UUID or raw envelopes enter Android
diagnostics. Recording traffic can shorten bounded timeline retention; truncation,
drops and IO errors remain explicit. Export both bundles after each trial.

The final amended scheduler is SOS → fresh live observation → oldest pending GNSS
block → bounded routine status. Every live attempt grants history an opportunity,
including failed attempts. Only the newest native measurement is eligible for live;
ACKing it cannot relabel older pending history as current. There is no unrelated
current/status ACK gate. Native GPS_PROVIDER/1 s/main Looper acquisition remains
unchanged. Physical Wi-Fi reassociation and GNSS/OEM behavior require the field run.

### Native callback durability / capacity amendment

The application-owned `ObservationPersistence` worker drains a bounded 1,024-entry
memory handoff into the existing outbox; it never writes on the native callback.
UUID/capture time/fix age are frozen on submission; retry of uncertain commit is
idempotent by that UUID. Latest live state ignores older callbacks, but their raw
observations are still saved. Only invalid coordinates or unrepresentable elapsed
measurement ages are structurally rejected. Freshness/accuracy filtering is not
local raw retention. There is no listener restart, new provider or satellite-
measurement recording. Normal service stop leaves accepted writes draining.

Room v1→v2 adds observation-time scheduling, immutable observation/session identity,
per-session committed sequence and loss metadata, plus indexes. Existing identity,
config and envelope JSON migrate unchanged. New envelopes carry authorized optional
v1 observation/progress fields; negotiated history batches use a separate versioned
endpoint. Old Command can accept individual envelopes while ignoring extensions,
but cannot certify native session completeness. Its retained wire metadata is
validated/backfilled on upgrade. No destructive downgrade fallback exists: an older
APK cannot open v2. Export field evidence before changing app versions.

`collection.submitted/saved/committedObservations/awaitingCommit/writeFailures/overflow` separates callback
submission from actual durable commit. Storage failures retry the held callback
with the same ID; subsequent callbacks use bounded memory. Exhaustion is explicitly
reported as known loss without permanently stopping collection after temporary pressure. Process
death can lose uncommitted memory; neither pending RAM nor an overflow is durable.
No Android app can guarantee writes when storage/process is unavailable.

At the requested native ~1 Hz and 5 s current cadence, expect roughly 3,600 distinct location
envelopes/hour plus status, versus 720 current-only envelopes/hour previously. JSON, indices,
WAL, persisted receipts and rebuilt state add storage beyond payload bytes; there
is no eviction. Assess actual DB size and battery consumption during the physical
run. More SQLite commits/HTTP backlog work increase power and throughput demand;
automated bounded-load tests do not establish four-hour capacity or OEM battery
impact. Command now checkpoints the existing engine and reprocesses affected projections;
Issue #7 owns sustained capacity testing. Exports distinguish nonadvancing historical packets even when
they arrive within clock tolerance, excluding them from current cadence.

### Final session/batch evidence (2026-10-09)

Collection now uses immutable observation identity and per-session sequences. Duplicate
provider measurements do not create new observations; equal coordinates at different
measurement times do. Live/history reuse the same row. No listener/recovery change.
Counters additionally distinguish distinct committed observations from callback handoffs.
Room migration adds session identity/sequence metadata and indexes while preserving all
old envelopes. A bounded overflow is recorded as loss, never successful persistence.

Historical batches have explicit capability negotiation, atomic ACK identity validation,
bounded transfer/adaptation, and permanent-entry isolation. Export Command's field report
alongside Android diagnostics: received/processed prefixes, timestamped phone pending data,
permanent errors, delivery roles and projection pending/error complement callback/IO state.
No status-only contact certifies current GNSS. No software test proves OEM reassociation.


Historical correctness is tested against the full chronological engine after
interrupted/permuted delivery. Receipt commits persist projection work independently
of derivation. The worker uses per-device work generations, an arrival cursor and
accepted-anchor checkpoints; late observations re-evaluate dependent suffixes. Live
snapshots do not wait for a busy reconstruction. Projection pending/error, provisional
sections and timestamped phone progress are explicit in Command/export; neither a
healthy contact nor quality rejection certifies complete history. Raw callbacks,
status snapshots and SOS retain distinct counts and identities.

Android's session boundary uses service creation time for an explicit start, before
early callback registration/Room initialization. Sticky restart reuses the committed
session. Late writer commits retain their captured session promise even after Stop
and a subsequent Start. Known buffer loss remains associated with that source session.

Collection-loss metadata is aggregated by captured session and retried in application-owned IO, so a service Stop cannot cancel its persistence. Diagnostic snapshots include the cached durable current-session loss count alongside process-lifetime writer counters. Neither path performs Room work on native callbacks.
