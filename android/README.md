# Android GNSS tracking core

One Kotlin application module, Android 8.0/API 26 through current platform rules
(target/compile API 35). Native Views keep the operational UI small. Native
`LocationManager` GPS/GNSS requires no Google Play Services, SIM or internet.

## Build and test

Install JDK 17+ and Android SDK platform 35/build tools 35.0.0. Set `ANDROID_HOME`
or an untracked `local.properties` with `sdk.dir`. From this directory:

```sh
./gradlew assembleStandardDebug testStandardDebugUnitTest lintStandardDebug
./gradlew assembleButtonTestDebug lintButtonTestDebug
```

The wrapper pins Gradle 8.11.1; AGP 8.9.2, Kotlin 2.1.20 and KSP 2.1.20-1.0.32
are pinned. Room 2.7.1 provides transactional SQLite persistence/schema checking;
coroutines 1.10.2 separate reporting and bounded sending; Gson 2.12.1 provides
explicit JSON serialization with strict streaming validation. OkHttp 4.12.0 gives
HTTP a total call deadline (including writes), bounded response bodies and explicit
Wi-Fi socket/DNS routing. Robolectric is a
JVM test dependency for the actual Room database, including reopen/transaction
rollback tests. No product DI, maps, cloud or background scheduler dependencies.
Generated APKs/build output are ignored. No release workflow is provided.
The isolated physical-button experiment has a branch-scoped GitHub Actions
workflow that uploads a debug-only field APK and checksum manifest; it is not a
production release.

## Operation

Configure Party ID/name, a Command base URL (e.g. `http://192.168.1.10:8080`), and
local reporting seconds (5–86400, multiples of 5). Save or Start Tracking.
Grant **precise** location. Notification permission on Android 13+ is requested
for notification visibility. The visible Start preflight requires notifications
and GPS enabled and Battery Saver off; notification refusal needs Settings before
that preserved Start intent can continue. Android itself allows an FGS without
notification permission, but that is not healthy field setup.
Start Tracking is a visible user action. Stop Tracking requires confirmation and
stops collection/sending; pending messages remain for the next start. Reopening
the Activity does not restart or stop tracking. Reboot and force-stop require
another explicit Start Tracking. Sticky service recreation can recover tracking
and outbox after process death when Android permits it; it is not a bypass of
foreground-start restrictions. Background location permission is not needed for
a user-started location foreground service and is not requested.

The service registers GPS immediately after foreground promotion and owns a
timeout-bounded partial CPU wake lock while tracking. Its loop renews the lock;
stop and registration failure release it. This does not bypass OEM restrictions
or deep Doze, and increases battery use; physical acceptance remains required.
Activity destruction never stops or refreshes the source.

The service continuously requests native GPS updates at approximately 1 second
independently of reporting cadence. Freshness uses platform elapsed-realtime
measurement time; fixes older than 30 seconds are shown as stale/unknown and new
reports carry status/null fix. Unavailable measurements/health remain nullable.
This local freshness threshold is operational presentation, not Command's track
quality policy. GNSS accuracy/altitude/speed/bearing use platform `has*` flags.
Wi-Fi RSSI is nullable (including on older platforms/redacted callbacks); no
SSID/BSSID/MAC access is required.

## Durability and recovery

`TrackingService` owns `PlatformLocationSource`, reporting and the foreground
sending loop. `TrackingApp` retains the shared `LatestLocation`, SOS engine and one
`Sender`; while tracking is stopped its visible-Activity loop selects SOS only. Room stores one installation/configuration row plus immutable JSON
snapshots and delivery metadata. Snapshot validation, sequence allocation, identity
and outbox insertion occur in one transaction **before** transport. No destructive
migration or backup restore is enabled; reinstall/data reset creates a new device
identity. Label/endpoint edits retain identity and never reconstruct saved packets.
Last ACK is local contact time, separate from the receiver's first receipt timestamp
(which remains stored with the delivered row). ACKed records remain locally available; pending or quarantined records are never
automatically evicted. Full storage surfaces an explicit save error. Disk capacity
must be monitored; retention/export controls are future work.

One sender prioritizes SOS, fresh live observations, oldest historical blocks and
routine status. The same durable GNSS row supplies both live and history; no new fix
copy is created on reconnect. Every live attempt grants historical work an opportunity.
Historical eligibility needs no unrelated ACK. Negotiated atomic batches adapt from
one observation through approximately 500 B/1/2/4/8/16/20 KB; permanent invalid entries
remain unresolved while valid history continues. Old queued individual v1 envelopes
remain deliverable. Missing capability falls back to individual envelopes with a warning.
Transport uses 6-second calls and 3-second connections, network-bound DNS/sockets,
invalidation on route changes/IO failure and 1/2/4/8/16/30-second bounded retries.
Wi-Fi availability and last proven Command communication are independent.

ACK validation rejects duplicate JSON keys, malformed receipt fields, noninteger
identity fields, mismatched identities and unsupported versions. Valid stored or
duplicate receipt updates delivery and configuration in one local transaction.
Config errors preserve delivery but keep previous applied config with a visible
error. Older versions cannot roll back; different authority/equal-version conflicts
cannot silently apply. Remote overrides survive restart/local edits; explicit null
restores the current local interval. Endpoint address changes retain authority.
“Enroll with a different Command” explicitly clears authority/override for the
next valid ACK. In-flight responses from an edited endpoint generation are ignored.
Historical packets retain their captured applied configuration. “Retry saved messages”
releases delivery errors after explicit confirmation without clearing authority,
override or message identity.

HTTP is intentionally supported for a manually configured LAN receiver. Android's
static network-security XML cannot enumerate arbitrary operator-configured hosts,
so cleartext is enabled in the manifest; the only transport validates HTTP(S) base
URLs, rejects credentials/query/path/fragment, disables redirects and opens requests
using sockets and DNS on an attached Wi-Fi `Network` (including networks without internet validation).
There is no fallback to cellular/default internet and no global process network
binding. Use an isolated trusted LAN as specified by Protocol v1. HTTPS keeps
normal platform trust validation. No authentication is added to the frozen protocol.

Screen-off collection uses the normal location foreground service, not WorkManager,
an alarm scheduling loop, or a permanent wake lock. Android/OEM power policies can
suspend or kill applications; no app can promise recovery after force-stop or an
OEM kill. Field validation must check the actual hardware, including Doze and
battery optimization. No real-device result is claimed by JVM tests.

Run the [real Command integration suite](../test-tools/integration/README.md) in
addition to the build/unit/lint commands above. It uses production Room/Sender and
Command HTTP/SQLite, substituting only the physical Wi-Fi adapter.

See [device acceptance](DEVICE-ACCEPTANCE.md), the
[mock receiver](../test-tools/mock-receiver/README.md), and
[Protocol v1](../protocol/protocol-v1.md). Command recording/tracks remain
Command-owned; SOS operation is described below.

## SOS operation and limitations (Issue #5)

Save the Party/Command settings before field use. **Hold “SOS — hold to activate”**
briefly (Android's normal long-press threshold). A tap explains how to activate;
there is no additional confirmation dialog. Accessibility users can invoke the
button's long-click action. Immediate “trigger detected” changes to “saved on phone”
only after durable Room save. A storage failure explicitly says **NOT SAVED**.
The display retains the latest five event statuses, with older events still stored.

“Received by Command” requires a valid matching transport ACK. “Retrying” or
“waiting for Wi-Fi” is not success. Operator acknowledgement is **Command-only**;
Protocol v1 has no return channel for it. A received emergency remains on Command
until the operator acknowledges it, and acknowledgement retains the event.
Activate again after three seconds for a distinct deliberate event; rapid duplicate
activations coalesce, even across engine/database recreation. Do not use repeated
triggers as a delivery test: the saved event already retries unchanged.

SOS immediately uses the freshest credible observation from the existing native
listener. Missing GNSS produces a null fix and truthful health; it never waits for
satellites. A last-known fix includes its original measurement time, horizontal
accuracy (or unknown), and age at activation. Old coordinates remain last-known,
including when GPS is disabled. Delivery hours later never changes activation time,
measurement time, age at capture or the saved JSON.

Tracking should be active for hidden-Activity/background field operation. SOS is
independent of **Command Recording**. With Tracking stopped it can still be saved
and sent while the phone app is open, but there is no background-lifetime guarantee
without the tracking service. Stop Tracking retains all emergencies. Offline saves
survive database reopening; on reconnect eligible SOS precedes current tracking
and old backlog. Existing bounded 6-second in-flight requests can delay selection once;
SOS retries back off and allow ordinary reports between failed attempts.
Force-stop, reboot and OEM termination can prevent execution/transmission; reopen
and explicitly Start Tracking when required. No Wi-Fi path means no transmission.

### Physical Button Test (diagnostics experiment)

Open **Diagnostics → Physical Button Test**, then Start Test. It records only
whitelisted Android key codes delivered to the resumed Activity, including
key-down, key-up and repeat count. The chronological results use the existing
bounded Diagnostics timeline and are included in `physical-button-report.json`
in the diagnostics ZIP. Clear Results hides earlier test results from this
report; the underlying bounded diagnostics timeline remains governed by the
existing retention limit. The test is off after process restart.

`LISTENING` means the Activity adapter is ready, not that Android will route a
key to it. Ordinary apps have no passive global volume-key listener. Background
and screen-off volume observation are `MECHANISM UNAVAILABLE` in this experiment.
No MediaSession, audio focus, Accessibility Service or extra foreground service
is created: a MediaSession can redirect headset media buttons away from their
current player, and a session would not guarantee screen-off volume delivery.
Use the exact foreground/background/locked-screen checklist in
[device acceptance](DEVICE-ACCEPTANCE.md); an absent event in an unsupported
state is not evidence of `NOT DETECTED`.

The test has no SOS-engine or sender dependency. While it is explicitly active,
the existing Activity triple-Volume-Up SOS adapter is suspended so the three
presses in the diagnostic checklist cannot accidentally create an SOS. Volume
events continue through Android's normal handling and any previously saved SOS
continues delivery. Stop Test restores the normal SOS adapter; the experiment
does not change the SOS engine or saved-event sender. Other GNSS, tracking and
SOS behavior is unchanged outside this explicitly active test mode. The
`buttonTestDebug` APK uses a separate application ID, so it installs beside an
existing GNSS app without replacing its data.

### Physical-key scope and public API investigation

The checkbox enables **three short Volume Up press/release pairs within 1.5 seconds**
while this Activity is resumed. Each press must release within 0.5 seconds. Holds,
autorepeat, canceled/mixed keys, app backgrounding and interrupted sequences reset
the pattern. Keys still go to normal Android volume handling; a single press never
activates SOS. The UI clearly labels foreground-only support. This adapter is
limited, and **screen locked/off or another app foregrounded is unsupported**.
Use the on-screen fallback after opening/unlocking the phone. Rugged-device/PTT
input remains a future adapter. No private APIs, root or device-owner assumption.

[Activity.dispatchKeyEvent](https://developer.android.com/reference/android/app/Activity#dispatchKeyEvent(android.view.KeyEvent))
intercepts this window's key events; a foreground service has no global Activity
key stream. An [AccessibilityService.onKeyEvent](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#onKeyEvent(android.view.KeyEvent))
can observe system key events through public APIs (API 18+), but requires explicit
user enablement and the [filter capability/flag](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_REQUEST_FILTER_KEY_EVENTS).
That capability does not establish a hardware guarantee that every locked-screen
volume event will be routed to this app. Services can compete for filtering; OEM
input/power behavior requires hardware evidence. This implementation therefore
requests no AccessibilityService privileges, reads no other apps' text/content and
makes no locked-screen guarantee. Android 12+ restricts background foreground-service
starts; Android 14+ also enforces while-in-use location permission on creation
([official restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)).
The adapter does not attempt to start a new location FGS from a hidden Activity.
API 26 through target API 35 are the supported application range; physical input
acceptance must identify the actual Android version and OEM.

### Automatic SOS evidence

The existing **Export diagnostics** ZIP now includes `sos-report.json` and typed
SOS transitions in its retained timeline: trigger/source, multi-press evaluation,
debounce, save/failure/elapsed save time, priority placement/selection with competing
reports, attempts, Wi-Fi unavailable, backoff, accepted/rejected ACK and database
restoration. Correlation uses a 16-hex SHA-256 prefix of the event UUID, excluding
coordinates, Party labels, device identity, raw JSON and arbitrary error text.
Multi-press entries have no event reference until a valid pattern activates SOS.

`PASS` requires explicit evidence; `FAIL` requires an explicit failing transition;
missing, pruned or crash-unflushed evidence is `INCONCLUSIVE`. Android can prove
save, priority selection, offline→matching ACK and restoration from retained
transitions across different process generations. Same-process reads and missing
generation evidence cannot prove restart survival. Dedupe and human ACK require the Command report. “Competing reports=1”
is a lower-bound presence marker, not a guessed queue count. The recorder's existing
seven-day/six-incident/32-MiB bounds and dropped/IO-failure flags still apply;
operational SOS rows have no automatic deletion. Export soon after a field run.
Command-owned; SOS triggers remain future work.

## Native-resolution history and sessions

Room v2 adds per-session observation numbering, reliable measurement deduplication,
session lifecycle metadata and ordering indexes to the existing outbox. All sequences
commit atomically; status/SOS do not consume GNSS sequences. Existing schema-1 identity,
configuration and envelopes migrate unchanged. Older APKs cannot safely downgrade v2.

A 1,024-entry nonblocking memory handoff isolates callbacks from SQLite. Temporary
storage failure retries the same held observation. Overflow is known loss and visible;
it does not permanently stop the provider after temporary pressure. Exported counters
separate submitted/processed handoffs, distinct committed observations, outstanding RAM,
storage errors and overflow. Normal service stop leaves accepted handoffs draining;
process death before commit remains a limitation. Raw/ACKed observations are retained.

At approximately 1 Hz, about 3,600 distinct observations/hour are stored, plus routine
status envelopes at configured cadence. Live delivery reuses those observations.
Batch transfer reduces HTTP requests, not source resolution. Battery/storage capacity
and unattended locked-screen Wi-Fi recovery require the real-device acceptance run.

Session startup and Stop are durably ordered. Native callbacks accepted while Room
opens retain the original operation's session promise; Stop releases the GPS listener
immediately and finishes that metadata transaction in application-owned work before
closing the session. The next Start stays disabled until Stop is saved. Sticky
recreation checks that tracking was still active before registering GPS, and resumes
its existing durable session rather than creating a new operation.

The sender reschedules the next live opportunity from its last live attempt when the effective interval changes. A 30→5 override does not wait for the old 30-second deadline; clearing 5→30 prevents another live send at the old five-second cadence. Historical delivery remains independent.
