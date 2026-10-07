# Android → Command field acceptance

**Not executed in cloud.** This is the canonical Issue #4 real-device runbook.
Use a trusted local Wi-Fi LAN, outdoor GNSS sky view and reasonably correct Android
and laptop date/time. No SIM/mobile data or internet is required. Do not record
passwords or secrets. Repeater roaming and the 4+ hour/OEM campaign remain Issue #7.

## Run record

Copy/fill this table for each run. Keep screenshots/timestamps with the record.

| Item | Value |
| --- | --- |
| Operator / date / evidence directory | |
| Phone model / Android version | |
| Battery optimisation setting | |
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
reporting interval **10 seconds**, then Start Tracking from the visible app.
Grant precise location and notification permission; confirm the ongoing notification.
Record the battery optimisation setting; use unrestricted battery operation for the
acceptance run if the OEM requires it. Start while the Activity is visible (modern
Android restricts background location-FGS starts). Do not force-stop or swipe away
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

## Execute and record

Run the sustained screen-off stage on battery with USB disconnected; charging
and ADB can change sleep behavior. Preserve Command evidence before attaching
ADB to diagnose a failure, since attaching can wake the phone.

Enter **PASS/FAIL and brief evidence for every row**. Record phone and Command
observation times/coordinates, last ACK, queue count, applied intervals, recording
state and distances as relevant. A screenshot alone cannot prove unchanged retries.
For retry evidence stop Command and inspect a **copy** of SQLite offline if needed:
`raw` contains device/message/sequence, original `wire` and first `received`.
Never add/expose raw inspection endpoints on the phone listener. Do not edit the
operational database. A browser can save `http://127.0.0.1:8081/local/state` snapshots
before/after events as local evidence of device/config/track state.

| Stage | Procedure and expected evidence | PASS/FAIL + evidence |
| --- | --- | --- |
| 1 Discovery | Start Command then phone as above. New device appears dynamically with correct Party labels; record device UUID. | |
| 2 Outdoor fix | Obtain GNSS fix. Compare phone saved GNSS coordinates (extraction above) with Command `location.fix` in local state and timestamps. Accuracy/unavailable fields must be honest. | |
| 3 Local cadence | Keep default 10 s as a baseline, then save local **5 s**. Observe ≥5 reports at each cadence, increasing observation/capture times and successful ACKs. GNSS callbacks must not flood packets. | |
| 4 Activity closed + screen off GNSS | Close Activity using Back, then lock/switch screen off; remain outdoors with sky view and move for **at least 30 minutes** at local 5 s. Do not reopen the app during this period. Retain intervening Command state snapshots: `gnss_condition` remains fresh, native `observed_at` advances, capture/ACK/contact remain healthy, no unexplained gaps. Unlock without opening Activity and check again on Command. Reopening must not be needed to restore fixes. Record battery use and start/end times. | |
| 5 Remote override | Save local 15 s, close Activity and lock screen. On Command device card Set **5 s**. Confirm ACK adoption, effective 5 s and eventual settings-applied state; observe ≥5 fresh GNSS reports at 5 s without opening Activity. Also check existing 30 s override. Delivery waits for the next request/ACK. | |
| 6 Local edit + Clear override | With the 5 s override active, change phone local to 15 s: effective stays 5. Close Activity/lock again. Command device-card Clear override: effective returns to 15, reports resume that cadence and settings converge. Record both configurations. | |
| 7 Offline movement | Start Recording before moving. Note current distance/queue. Break or disable phone Wi-Fi for several minutes while moving; confirm GNSS continues and phone pending queue grows. | |
| 8 Recovery priority | Restore Wi-Fi without internet. Observe current position restored on Command **before** old backlog drains; then queue falls. Capture closely timed local-state snapshots/screenshots and phone queue/ACK. | |
| 9 Reconciled route | After drain, accepted history follows observation-time movement. Check sensible turns/reversals, no backwards arrival-order edge, inflated/doubled distance or spurious connector across bad GNSS. Provisional distance may be revised during rebuild. | |
| 10 Command restart | Stop Command process while phone continues tracking/moving; verify queue growth. Restart same executable/database/address. Verify live recovery/backlog and matching original stored identities/first receipts on retries, no duplicate raw rows/distance. | |
| 11 Stop Recording | Stop current Recording; continue moving/reporting. Live coordinate changes while recorded geometry/distance stays stopped once pre-stop backlog is drained. Late pre-stop observations may legitimately reconcile closed history. | |
| 12 Resume | Resume and move again. Same recording gains a new segment. First resumed point adds no connector/distance from stopped position; subsequent movement adds only within-segment distance. | |
| 13 Clear Recording | Clear, cancel once to verify confirmation; Clear and confirm. Tracks/distance disappear; live device and remote configuration remain. Subsequent reports/old backlog cannot resurrect cleared recording. | |
| 14 Retained state | Reopen Activity; verify no second service and retained identity/settings. If safely testing process interruption, record separately; permitted sticky recovery is platform-dependent. Force-stop/reboot require explicit Start Tracking. | |

A stage fails if reports stop unexpectedly, pending data disappears, a current fix
is falsely fresh, config never converges, identity changes, or distance duplicates/
connects stopped periods. Record the failure and precise timestamps; retain database
copies only after stopping Command and note the app queue state. For a GNSS
continuity failure record whether Command contact stayed healthy and whether
unlocking versus opening Activity restored fixes. Capture local developer evidence
without restarting the app: `adb shell dumpsys location`,
`adb shell dumpsys activity services org.gnss.tracking`, and
`adb shell dumpsys power` (look for `gnss:tracking`). Redact unrelated device/app
data; never record Wi-Fi passwords. These distinguish missing GPS registration,
foreground-service loss and power-policy suspension. A wake lock is CPU protection,
not a guarantee against OEM GNSS restrictions or Android deep Doze. Stop Tracking on
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
