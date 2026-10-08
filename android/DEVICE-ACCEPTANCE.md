# Android → Command field acceptance

**Not executed in cloud.** This is the canonical Issue #4 real-device runbook.
Use a trusted local Wi-Fi LAN, outdoor GNSS sky view and reasonably correct Android
and laptop date/time. No SIM/mobile data or internet is required. Do not record
passwords or secrets. Repeater roaming and the 4+ hour/OEM campaign remain Issue #7.

## Run record

Copy/fill setup details for each run. Keep both exported ZIPs with the record;
receipt/count/config/recording timestamps are captured automatically.

| Item | Value |
| --- | --- |
| Operator / date / evidence directory | |
| Phone model / Android version | |
| Battery optimisation / unrestricted / OEM background/autostart settings | |
| Battery Saver / Low Power Standby / thermal or low-battery warnings | |
| App commit / local reporting interval | |
| Command OS / commit | |
| Command LAN address / SQLite file | |
| Router/repeater setup / SSID (no password) | |
| Mobile data disabled / SIM absent or unused | |
| Phone/laptop clock check | |

## Start

Build using an Android SDK 35/JDK 17+ development machine and install the debug app
on the test phone (not release packaging):

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

With Go 1.24.7+, from `command/` on the laptop:

```sh
go build -o party-tracker ./cmd/party-tracker
./party-tracker -ingest-listen :8080 -dashboard-listen 127.0.0.1:8081 -db ./field-command.sqlite
```

Windows: use `party-tracker.exe`. Record the absolute SQLite path; use the **same
file** after restart. Allow laptop TCP 8080 on the trusted LAN. Keep dashboard
controls on loopback. Open `http://127.0.0.1:8081` on the laptop.

On Android configure Party ID/name, Command base URL
`http://<laptop-LAN-IP>:8080` (**not** localhost, dashboard port or API path), local
reporting interval **10 seconds**. On each open/resume, check **Field readiness**:
precise permission, system GPS, notifications (including the Tracking channel),
Battery Saver and battery background policy are listed independently. Missing
runtime permissions are requested while the Activity is visible; use the listed
Settings actions for GPS, blocked permissions/notifications or power settings.
Returning from Settings refreshes readiness. Battery optimization exemption is
only an Android allowlist signal; it does not certify every OEM's Unrestricted UI.

Wait until **Loading settings…** becomes **Start Tracking**, then tap **once**.
Expect **Starting…**, followed by **Tracking active** after durable service startup.
If a prerequisite is missing, the screen explains the required action; that explicit
Start remains pending through permission/Settings returns and completes only while
the Activity is resumed. Returning without a pending Start never starts tracking.
Stop Tracking cancels pending startup; closing the Activity before service handoff
cancels it. A configuration change while settings are still saving shows an explicit
cancellation instruction rather than starting with possibly unsaved settings.
Confirm the ongoing notification.
Before the run, use the dedicated-phone setup below and record the settings.
Start while the Activity is visible (modern Android restricts background
location-FGS starts). Do not force-stop or swipe away
the service notification during the sustained test.
If precise location is denied, confirm a clear explanation and no tracking start.

The current phone screen shows GNSS quality/age rather than coordinates. To capture
phone-side coordinate evidence after obtaining a fix, use the debug app's saved
snapshot (no production diagnostic endpoint required). Force-stop before copying,
so the database and any WAL file cannot change during extraction:

```sh
adb shell am force-stop org.gnss.tracking
adb exec-out run-as org.gnss.tracking tar -C databases -cf - . > phone-databases.tar
mkdir phone-evidence
tar -xf phone-databases.tar -C phone-evidence
```

Open `phone-evidence/tracking.db` in a local SQLite viewer, keeping any WAL alongside
it. Read `SELECT json FROM outbox ORDER BY sequence DESC LIMIT 1;` and compare the
saved `fix` coordinate/time with Command. Retain this evidence; then reopen the app
and explicitly Start Tracking before the sustained test. This optional developer
evidence tool is not a field product/runtime dependency.

## Required dedicated-phone setup

- Android Settings → Apps → GNSS Tracking → Battery: **Unrestricted** (or allow
  background activity / do not optimize, depending on firmware). The app's
  **Battery/background settings** button opens its standard Android app settings;
  exemptions are never requested/applied automatically. Record actual settings,
  not just whether an app warning disappeared.
- **Battery Saver and Ultra/Extreme Power Saving: Off** throughout acceptance.
  Disable Low Power Standby if available and it restricts this app. Charge before
  the run; keep the phone cool, with adequate storage. Do not bypass thermal safety.
- Allow precise location **while using the app**, enable system Location/GPS, and
  allow notifications. Start Tracking from the visible Activity and verify its
  ongoing notification before closing it. Background-location permission is not
  required for this user-started location FGS and is not requested.
- Vendor controls: Xiaomi/Redmi/POCO—allow background autostart and no battery
  restriction; Oppo/Realme/OnePlus—allow background activity/auto launch and disable
  app freeze; Vivo/iQOO—allow background power use/autostart; Samsung—remove from
  sleeping/deep sleeping apps and use Never sleeping where offered; Motorola/other
  vendors—disable app background restriction/freezer where offered. Labels and
  availability vary; record the phone's actual controls. Autostart permission does
  **not** mean this app implements reboot startup.
- Do not force-stop, reboot, use vendor task cleaners or dismiss tracking through
  a system active-app Stop control during the screen-off stage. Swipe-away behavior
  can differ by OEM: test separately and label it, rather than conflating with Back.
  Force-stop/reboot require reopening and explicitly Start Tracking. Sticky process
  reclaim may recover, but is not guaranteed by this app on every OS/OEM.

The app warns about measured Battery Saver, enabled Low Power Standby, severe
thermal status or battery optimization. No warning is a hardware guarantee, and
an Android optimization allowlist does not describe every OEM power policy.
See [GNSS-INVESTIGATION.md](GNSS-INVESTIGATION.md) for the platform/code assessment.

## Execute and record

Run the sustained screen-off stage on battery with USB disconnected; charging
and ADB can change sleep behavior. Preserve Command evidence before attaching
ADB to diagnose a failure, since attaching can wake the phone.

Perform the actions and retain brief action outcomes/setup notes. The two ZIPs
capture counts, receipt/capture/observation timestamps, queue changes, retries,
configuration convergence and recording boundaries automatically. Assess evidence
afterward as **PASS/FAIL/INCONCLUSIVE**; continuously watching Last seen, ACKs,
counters or SQLite is unnecessary. Screenshots are optional corroboration, especially
for real geometry. Never edit the operational DB or expose raw inspection on the
phone listener. See **Preserve the complete field evidence** below for export.

| Stage | Procedure and expected evidence | PASS/FAIL/INCONCLUSIVE + evidence |
| --- | --- | --- |
| 1 Discovery / startup | Start Command then phone as above. Verify one deliberate Start tap, visible Starting → Active, and no extra service start from repeated taps. Check each readiness action and return from Settings; no automatic start without an explicit pending Start. New device appears dynamically with correct Party labels; record device UUID. | |
| 2 Outdoor fix | Obtain GNSS fix. Compare phone saved GNSS coordinates (extraction above) with Command `location.fix` in local state and timestamps. Accuracy/unavailable fields must be honest. Fresh UI shows Accuracy/Fix age; a >30 s old observation shows Last fix age/Last known accuracy, or Unavailable if never measured. With no observation it shows Last fix: None. Stale metadata must not become a current Command fix. | |
| 3 Local cadence | Keep default 10 s as a baseline, then save local **5 s**. Allow several steady reporting cycles at each setting; verify receipt cadence, advancing times and ACK/queue progress afterward from the ZIPs. GNSS callbacks must not flood packets. | |
| 4 Activity closed + screen off GNSS | Close Activity using Back, then lock/switch screen off; remain outdoors with sky view and move for **at least 30 minutes** at local 5 s. Do not reopen the app during this period. The retained evidence must show: `gnss_condition` remains fresh, native `observed_at` advances, capture/ACK/contact remain healthy, no unexplained gaps. Unlock without opening Activity and check again on Command. Reopening must not be needed to restore fixes. Note battery use; start/end and continuity evidence are retained automatically. | |
| 5 Remote override | Save local 15 s, close Activity and lock screen. On Command device card Set **5 s**. Confirm ACK adoption, effective 5 s and eventual settings-applied state; leave it running for several cycles without opening Activity; analyze receipt cadence/freshness afterward. Also check existing 30 s override. Delivery waits for the next request/ACK. | |
| 6 Local edit + Clear override | With the 5 s override active, change phone local to 15 s: effective stays 5. Close Activity/lock again. Command device-card Clear override: effective returns to 15, reports resume that cadence and settings converge. Both configurations and their adoption/cadence are retained in the exports. | |
| 7 Offline movement | Start Recording before moving. Break or disable phone Wi-Fi for several minutes while moving. Continue the route; Android automatically preserves GNSS state and real pending-count growth. | |
| 8 Recovery priority | Restore Wi-Fi without internet. Continue moving after restoration. Inspect exports later: first post-gap capture should be current-like, then older backlog follows; Android pending must fall to zero. No live queue watching is required. | |
| 9 Reconciled route | After drain, accepted history follows observation-time movement. Check sensible turns/reversals, no backwards arrival-order edge, inflated/doubled distance or spurious connector across bad GNSS. Provisional distance may be revised during rebuild. | |
| 10 Command restart | Stop Command process while phone continues tracking/moving; restart the same executable/database/address. Exports preserve Android queue growth/drain, Command open boundaries, matching retry identities/original receipts, and unchanged unique rows/distance on duplicate ACKs. | |
| 11 Stop Recording | Stop current Recording; continue moving/reporting. Live coordinate changes while recorded geometry/distance stays stopped once pre-stop backlog is drained. Late pre-stop observations may legitimately reconcile closed history. | |
| 12 Resume | Resume and move again. Same recording gains a new segment. First resumed point adds no connector/distance from stopped position; subsequent movement adds only within-segment distance. | |
| 13 Clear Recording | Clear, cancel once to verify confirmation; Clear and confirm. Tracks/distance disappear; live device and remote configuration remain. Subsequent reports/old backlog cannot resurrect cleared recording. | |
| 14 Retained state | Reopen Activity; verify no second service and retained identity/settings. If safely testing process interruption, record separately; permitted sticky recovery is platform-dependent. Force-stop/reboot require explicit Start Tracking. | |

A stage fails if reports stop unexpectedly, pending data disappears, a current fix
is falsely fresh, config never converges, identity changes, or distance duplicates/
connects stopped periods. Missing evidence is **INCONCLUSIVE**, not PASS. Preserve
both exported ZIPs and brief action order/outcomes; no failure-time timestamp or
SQLite extraction is needed. For a GNSS continuity failure note whether unlocking
versus opening Activity restored fixes. Historical evidence is already frozen
automatically; **ADB is not required at the moment of failure**. Follow the GPSTest
comparison below before
opening our Activity. Afterward, open GNSS Tracking → **Export diagnostics** →
choose a local folder in Android Files → Save. Share the resulting
`gnss-diagnostics-<UTC timestamp>.zip` with the run record for analysis. This works
offline and does not stop tracking, restart GNSS or clear saved incidents. Keep the
original ZIP; share via USB later or any operator-selected sharing mechanism.
If export fails, retry after freeing storage/enabling Android Files; incidents
remain private. Canceling the picker does not change tracking.

The bundle contains `manifest.json` (format, app version/source revision, Android
API, retention limits and writer/drop counters), rotating `timeline-*.jsonl`, and
`incident-*/summary.json`, `pre.jsonl`, `events-*.jsonl`. Normal health is sampled
once/30 s; policy/state changes are recorded on observation (maintenance checks
every second) and lifecycle/native transitions immediately. Incidents retain
~5 minutes of pre-context, callbacks/events and 2-second health samples throughout
the failure, then ~5 minutes after a fresh accepted location. Six recent incidents,
seven-day retention, 4 MiB rolling timeline, 4 MiB incident event history and
32 MiB total bound storage. Pre-context is additionally bounded to 1 MiB/1200
records. Long/high-volume incidents retain their start/summary/pre-context and
recent events; `truncated` explicitly flags discarded middle history. Reboot/kill
can lose the in-memory pre-buffer or unflushed queue; `interrupted`, record drops,
IO failures and gaps must be treated as missing evidence, never proof of health.

No coordinates, altitude, Party/device identity, Command address, SSID/password,
raw packets or database contents are exported. Accuracy/satellite counts, app
version, service-generation IDs and timing/policy evidence are included. Command
screenshots/database evidence are separate and may contain operational identities
or coordinates; send only intentionally selected evidence.

Optional developer extraction later (USB/ADB may wake the phone):

```sh
adb exec-out run-as org.gnss.tracking tar -C files -cf - diagnostics > gnss-diagnostics-files.tar
adb logcat -d -s GnssTracking:I > gnss-logcat.txt
adb shell dumpsys location > location-state.txt
adb shell dumpsys activity services org.gnss.tracking > service-state.txt
adb shell dumpsys power > power-state.txt
adb shell cmd appops get org.gnss.tracking FINE_LOCATION > location-appops.txt
```

A live developer copy may end with an incomplete JSONL record; ignore that final
line. The in-app export reads sanitized complete records on the writer. Platform
dumps are optional corroboration and can include unrelated apps: redact those
before sharing. The obsolete single `gnss-diagnostics.json` is not the recorder or
part of the export. Existing installations may retain that old file.

| Question | Evidence / interpretation |
| --- | --- |
| Service alive / recreated? | Ongoing notification, `dumpsys activity services`, `serviceGeneration`, `startReason` user_start/sticky_restart, sample UTC/elapsed times. A history sample proves state at that time, not present liveness. |
| Source requested? | `source.registered`, `source.statusRegistered`, `source.registrations`; compare `dumpsys location` GPS requests. App registration flags are not proof the system is delivering callbacks. |
| CPU protection / power gate? | `wakeLockHeld`, `wakeRenewals`, `power.deviceIdle`, `interactive`, optimization status, Saver, location-power mode, Low Power Standby, thermal status and process importance. Held locks can be ignored by Doze/standby. Modes: 0 unchanged; 1 GPS disabled screen-off; 2 all disabled screen-off; 3 foreground-only; 4 throttle screen-off. Unsupported API values are null. |
| Callback stream alive? | Separate `locationCallbackAgeMs` and `gnssCallbackAgeMs` plus counters; `source.engineRunning`, `satellitesTotal`/`satellitesUsed`; `CALLBACK_SILENCE` records ≥5 min without Location or (when registered) GNSS status independently. Legacy `callbacksQuiet` still reports neither stream. This flag does **not** diagnose a stall or restart anything: no sky view can also cause silence. |
| Measurements usable/fresh? | `fixMeasurementAgeMs`, `fixAvailable`, `source.rejectedObservations`/`lastRejection`. Recent callback with old/bad measurement time is not a fresh fix. Satellite/acquisition callbacks never refresh coordinates. GPS enabled is not a fix. |
| Permission/policy? | `power.precisePermission`, raw `fineLocationAppOp` plus platform appops/location dump. MODE_FOREGROUND (4) is conditional, not a grant of effective access. Logcat may report a background-started FGS without location access. |
| Incident duration / native recovery? | `FIX_BECAME_STALE`, `FIRST_LOCATION_AFTER_STALE`, `GNSS_RECOVERED`; summary before/after counters, `recoveredAt` and elapsed duration. Recovery requires a real accepted Location measurement ≤30 s old with GPS/precise permission present; satellites alone do not recover it. A relapse resets the post-context window; events preserve each transition. |
| Reporter/sender/store alive? | `reportingIterationAgeMs`, `senderIterationAgeMs`, `savedSnapshotAgeMs`, `loopError` / `source.error` (last errors, not necessarily ongoing) versus healthy Command receipts. A maintenance sample gap can be scheduling/process/power/disk trouble, not automatically GNSS failure. |

### GPSTest comparison when GNSS becomes stale

Use GPSTest already installed before the offline run; record its version. This
comparison does not replace the sustained test and does not prove an app/OEM cause.

1. Keep our foreground service running outdoors. When Command shows healthy
   contact but old/no GNSS, note the symptom; Command receipts and Android incidents already preserve the timing/state.
   **Do not reopen GNSS Tracking or restart its service.**
2. Unlock only, then open **GPSTest**, leaving our Activity closed. Note whether
   GPSTest obtains a real fix. Let both run for several minutes; exported native
   callback and receipt chronology will show whether/when our service recovered.
3. Close GPSTest and observe Command again **without reopening our Activity**.
   Note action order. Only after that comparison, open GNSS Tracking, then
   minimize/reopen it if needed. Our Activity transitions/recovery are recorded.
   Do not stop tracking or restart the listener to collect evidence.
4. After ~5 minutes of post-recovery context (or much later the same day), use
   **Export diagnostics**. Compare the frozen incident before/during GPSTest:
   service generation/registrations, Location versus GNSS callback counters/ages,
   engine/satellites, measurement age, provider, lock, power/process/AppOp and
   sender/reporting progress. `FIRST_LOCATION_AFTER_STALE` identifies the native
   callback that restored a fresh measurement; HTTP contact alone does not.
   Attach the ZIP to the Issue #4 test report or analysis conversation, with phone/
   Android/GPSTest versions, brief action order and the Command field-report ZIP. No live ADB
   is needed; opening the Activity later does not overwrite the frozen incident.

Continuous poor fixes or absent callbacks indoors do not justify automatic source
restart. If callbacks vanish outdoors while service/provider/lock remain present,
compare power/AppOp/engine history before attributing the cause to an OEM.
A wake lock is CPU protection, not a GNSS hardware or Doze exemption guarantee.
Stop Tracking on
Android explicitly when finished; saved pending messages remain for next start.

Issue #4 is field-accepted only after the above hardware run passes. JVM tests
prove application state/HTTP behavior, not GNSS radio, Wi-Fi routing, screen-off,
OEM battery management or router/repeater behavior.

## Later Android hardening (Issue #7)

Repeat on supported API/OEM devices, with default and altered battery settings,
notification denial, disabled/obstructed GNSS and recovery, safe low-storage tests,
and process interruption. Verify explicit save failures, retained pending messages,
nullable telemetry, honest stale/no-fix status and no automatic force-stop/reboot
startup promise. Run repeater roaming and 4+ hour endurance separately from this
30-minute integration milestone.

## Preserve the complete field evidence

Build/install Android and Command from the same frozen clean PR #11 product SHA.
Verify Android `build.sourceRevision` and Command `build.source_revision` match
in the ZIP manifests; a workflow-only APK build commit must not replace the product checkout. Do not reuse an older APK.

Run the physical actions above without continuously watching counters or using a
stopwatch. After the run, choose Android **Export diagnostics** and save its ZIP;
choose Command **Export field-test report** on the loopback dashboard and save the
ZIP beside it. Export does not stop tracking, restart GNSS, clear queues/incidents
or change recording. Keep setup details and the two files for analysis, even if the
GNSS failure was noticed long after it began; ADB is optional.

Android automatically records real pending-count changes: offline growth, drain,
zero and unavailable/error. It observes Room asynchronously while the Activity is
closed; null is not zero. Command automatically captures unique receipt chronology,
retry/conflict attempts, first receipt after each gap, capture/observation chronology,
older backlog, monotonic live state, config requests/offered ACKs/echoes and actual
receipt cadence, plus recording operations/windows/accepted segments/distance.
Use a local **30 s → Command override 5 s → Clear → 30 s** cadence exercise, allowing
at least four steady reports in each phase. Clear and restart are persisted evidence,
not operator-written timestamps. Command's automatic assessments are conservative;
phone queue drain needs the Android ZIP. A gap proves missing Command receipts,
not that the physical Wi-Fi radio was off.

Send both ZIPs with phone/OS/network setup and brief action outcomes for analysis.
Android excludes operational identity/location; Command includes device/message/
recording identifiers for correlation, but excludes coordinates, Party labels,
network addresses, raw payloads, database files and secrets. Do not publish bundles
indiscriminately. Recorder retention limits still apply; export promptly after a run.
Real GNSS/GPSTest activation, screen-off/Doze/OEM effects and physical offline/reconnect
behaviour remain physical acceptance; no automated verdict substitutes for these.

## Issue #5 SOS acceptance — separate from Issue #4 physical testing

**Do not install/deploy Issue #5 builds while Issue #4 remains under physical
acceptance.** PR #11 / `8fbcabbc2443aae75178a50160ee1248830611dd` is the frozen Issue #4
candidate. This SOS branch is independently based on it; GNSS stall remains unresolved.
Run the following only after scheduling separate SOS acceptance on an authorized
candidate. Automated results cannot establish physical-key/locked-screen reliability.

Minimum physical SOS tests (retain Android and Command ZIP exports afterwards):

1. Configure Party and the isolated local Wi-Fi receiver; Start Tracking. Tap SOS
   once: no activation. Hold SOS briefly: immediate detection, then saved status,
   then Command-received only after a valid ACK. Confirm the Command event/party/time
   and test audible enablement, blocked audio, speaker volume and keyboard ACK.
2. Enable the foreground triple Volume Up checkbox. One/two presses, holds, slow
   triples and mixed Volume Down must not activate. Three short presses within
   1.5 seconds must activate once. Verify ordinary volume controls still work.
   Record manufacturer/model, Android version, pattern timing and actual outcome.
   Lock/off the screen and foreground another app: this adapter is unsupported
   there; do not depend on it or claim a pass. Open/unlock and use on-screen SOS.
3. Disconnect Wi-Fi, activate SOS, hide the Activity while Tracking stays active,
   and restore Wi-Fi after an extended outage with accumulated ordinary reports.
   Confirm the same event reaches Command before backlog, then current tracking
   and old backlog continue. Use the automatic evidence instead of observing the
   exact reconnect moment. No communication path means no transmission.
4. Disable GPS or wait until the known Issue #4 stall produces stale GNSS; activate
   SOS. It must save/deliver immediately with unavailable or honestly aged last-known
   location, preserving accuracy and original time. Do not open GPSTest to manufacture
   a fresh location result for this test. SOS does not fix that stall.
5. Stop Command during activation, restart it against the same database, and confirm
   delivery eventually occurs. Acknowledge that event, reload the browser and restart
   Command again: the acknowledgement time and first receipt must remain unchanged.
6. Rapidly trigger again within three seconds: no second logical emergency. Activate
   deliberately after three seconds: a distinct event. Start/Stop/Resume/Clear Command
   Recording: both emergency events and individual acknowledgements remain intact.
7. Recreate the phone process using an approved test procedure while an SOS is pending;
   reopen/Start Tracking if Android does not restore the service. Verify exact event
   survival/delivery and record whether OS recovery actually occurred. Repeat with
   screen off/Doze/OEM battery policy on intended hardware. Force-stop/reboot/OEM kill
   provide no automatic-transmission guarantee; explicitly restart when needed.
8. Export both reports soon after the run. Review all six SOS assessments and exact
   evidence; missing data, no competing backlog, no retry, no restart or truncated
   history must remain INCONCLUSIVE. Physical-key scope remains a hardware item;
   exporter file-picker behavior and browser speakers also require a real device.

These tests cover field behavior only within the supported trigger scope. The
original Issue #5 desired locked-screen physical activation remains an explicit
acceptance limitation, not a capability inferred from unit/emulator tests.
