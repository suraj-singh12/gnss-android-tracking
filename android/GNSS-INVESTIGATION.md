# Issue #4 GNSS continuity investigation

PR #11; Android API 26–35, target SDK 35. **The physical failure is not proven
resolved.** Authoritative evidence is healthy Command contact while GNSS ages,
often recovering after opening the Activity. No diagnostic trace from that incident
exists. This report records the code/platform audit, not a hardware result.
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
- `GnssDiagnostics` / `TrackingApp`: public-API power snapshots, live diagnostic state,
  and a private atomic file, at most 64 samples and 128 KiB. One sample/minute plus
  exceptional start/permission failure evidence; one conflated pending sample prevents
  disk stalls from building a queue. No coordinates, identities, labels, endpoint or
  protocol fields. Tests cover minimum API unavailable values, API 35 power flags,
  callback-silence semantics, bounded retention/reopen, concurrent replacement
  service writers without lost samples, and malformed-file reset. Writers are
  serialized across service generations because cancellation cannot interrupt
  synchronous file IO and AtomicFile is not multi-writer safe.
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
wire serialization and private bounded diagnostic journal are unchanged. The canonical
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
