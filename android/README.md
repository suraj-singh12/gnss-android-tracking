# Android GNSS tracking core

One Kotlin application module, Android 8.0/API 26 through current platform rules
(target/compile API 35). Native Views keep the operational UI small. Native
`LocationManager` GPS/GNSS requires no Google Play Services, SIM or internet.

## Build and test

Install JDK 17+ and Android SDK platform 35/build tools 35.0.0. Set `ANDROID_HOME`
or an untracked `local.properties` with `sdk.dir`. From this directory:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
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

## Operation

Settings includes **Appearance: Day/Night**. Native configuration/theme resources
are set while attaching the Activity, before resource access. The independent
preference persists; Day is default. Tracking, Settings, Diagnostics, Physical Button
Test, navigation, protected SOS and native dialogs share semantic palettes. Changing
appearance recreates the Activity, not the tracking service. Any active key diagnostic
uses its existing pause/interruption behavior, retains evidence and restores normal
SOS handling. GNSS/Room/sender/SOS state is untouched. Large fonts, TalkBack and
OS permission/export dialogs still require actual-device acceptance.

Configure Party ID/name, a Command base URL (e.g. `http://192.168.1.10:8080`), and
local reporting seconds (5–86400, multiples of 5). Save or Start Tracking.
Grant **precise** location. Notification permission on Android 13+ is requested
for notification visibility. The visible Start preflight requires notifications
and GPS enabled and Battery Saver off; notification refusal needs Settings before
that preserved Start intent can continue. Android itself allows an FGS without
notification permission, but that is not healthy field setup.
Start Tracking is a visible user action. Stop Tracking requires confirmation and
stops collection. Saved messages automatically synchronize while the app is visible, without restarting GNSS. The Tracking view's Saved GNSS sync bar uses current Room queue counts: acknowledgements since the view/manual-drain baseline divided by those acknowledgements plus **all currently pending GNSS**. If more observations are recorded while sending, the total increases and the percentage can fall; unresolved saved GNSS remains pending. The percentage refers to Android-local durable delivery, **not** Command's completed history reconstruction or any offline phone inventory. No estimated finish time is asserted. **Sync All Now** preserves learned capacity and accelerates the payload ramp using the same Sender; **Pause history sync** stops selecting new history requests, retaining every row. An in-flight request may still finish. SOS and live tracking remain independently eligible. Reopening
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

## Optional adaptive GNSS persistence (Issue #24)

**OFF is the default**, for fresh installs, upgrades and absent/unreadable policy.
Settings → GNSS persistence → Adaptive GNSS persistence explicitly opts in/out.
This separate local policy is schema/revision 1, atomically committed before it is
published, with a change counter and saved-change UTC (`effectiveAt`, assigned
before commit). The existing journal records publication; sampling diagnostics record
the first callback applying that version, so a slow write is not an exact callback
boundary claim. Activity recreation and process
restart preserve consent; only explicit local consent or an authorized #25 instruction can enable it. The existing
reporting/override configuration is separate. A failed setting write is reported
as NOT SAVED and uses dense behavior in that process; a subsequent restart can
restore only the last successfully committed preference. Issue #25 remote control uses this same local ON/OFF surface. No Room schema, original envelope or previously saved evidence changes.

OFF keeps the original dense capture path. ON selects original callback objects
before the same bounded handoff and transactional Room allocator. Credible ordinary
movement targets ≤5 s; ≥10° accumulated heading changes, >1 m local chord deviation,
reversals, rapid speed changes and boundaries preserve adjacent available originals.
Stationarity requires 30 s of low speed (≤0.2 m/s), accuracy ≤5 m and bounded
position jitter; periodic retention then targets 30 s. Entry/exit thresholds differ.
Timing anomalies and GNSS gaps reset stationary confidence, including when the last
pre-gap observation was already durably selected.
Missing speed, moving fixes without bearing, weak/unknown accuracy, questionable
speed/displacement, clock anomalies and unexpected timing retain densely. These are
selection safeguards, not replacements for Command's eight qualification filters.
Unknown policy and selection/write/overflow failures use dense fallback; runtime
failure is latched until process restart and shown separately from saved consent.

The native ~1 s request, LatestLocation, elapsed freshness and SOS selection are
unchanged. One last candidate is held in memory for Stop, provider/GNSS loss,
valid-candidate silence (3 s), recovery, connectivity and SOS boundaries. A turn can promote
that original with its original capture time/age/health. The same writer retries its
UUID after failure. Start/service generation and policy changes retain the next real
fix; nothing invents an endpoint when callbacks are absent. Normal service teardown
flushes the candidate; abrupt process death can lose that one candidate and existing
uncommitted handoffs. Sticky restart preserves session identity and starts selection
conservatively. No guarantee is made after force-stop/reboot/OEM termination.

LIVE opportunities also promote the actual latest candidate into the existing
writer. Sender defers that selection to a later iteration while commit is pending,
without waiting on SQLite or blocking SOS. Thus online reporting can retain more
stationary observations than the nominal 30 s policy. Accuracy and freshness take
precedence over savings. OFF bypasses this hook and preserves legacy selection.
No second LIVE envelope, queue or location engine exists.

Diagnostics shows the effective mode/revision/change boundary, valid callback
candidates, originals actually committed, intentionally unselected candidates,
in-memory boundary candidate, callback gaps, write failures, pending/ACKed GNSS and
free storage. Native callbacks (including rejected callbacks), transport attempts and
ACK evidence remain in the existing bounded journal/export. Counters are labelled
by process/service/durable lifetime; selected/submitted is not committed. Sampling
is not reported as `known_collection_loss`. No automatic observation/SOS deletion is
introduced; disk use still grows, particularly OFF or under uncertainty. Storage
exhaustion remains an explicit capture failure, never a successful save.

Reproduce synthetic route and storage evidence using `AdaptivePersistenceTest` and
the real [Room/Sender → Command bridge](../test-tools/integration/README.md).
Physical GNSS, battery, screen-off/OEM and sustained multi-day LAN acceptance are
**NOT TESTED** by those tests; use [device acceptance](DEVICE-ACCEPTANCE.md).

### Reproducible software evidence (Issue #24)

Deterministic instrumented 1 Hz fixtures through production Room/handoff/Sender and
real Command SQLite, using its unchanged default qualification policy. These are
synthetic references, not survey or handset field acceptance. Maximum deviation is
dense qualified-point distance to retained qualified segments/endpoints; gap connectors
are excluded. All discrete ≥10° turns and tagged boundaries retained their real fixes.

| Reference | Dense → retained | Max deviation (m) | Distance difference (m) |
| --- | ---: | ---: | ---: |
| walking | 301 → 61 | 0.000 | 0.000 |
| vehicle | 301 → 61 | 0.000 | 0.000 |
| stationary_jitter | 3601 → 126 | 0.400 | 0.000 |
| sharp_turns_hairpin | 181 → 41 | 0.000 | 0.000 |
| slow_curve | 181 → 37 | 0.055 | 0.050 |
| reversal | 181 → 39 | 0.000 | 0.000 |
| rapid_acceleration_deceleration | 181 → 42 | 0.000 | 0.000 |
| start_stop_restart_sos | 181 → 39 | 0.000 | 0.000 |
| gnss_loss_recovery | 181 → 39 | 0.000 | 0.000 |
| weak_accuracy | 181 → 87 | 0.000 | 0.000 |
| missing_speed_bearing | 181 → 181 | 0.000 | 0.000 |
| timestamp_anomalies | 181 → 71 | 0.000 | 0.000 |

Ordinary movement intervals were ≤5 s; turn/reversal boundaries retained adjacent
1 s originals. Confident stationary intervals reached 30 s. The outage fixture has
a real 301 s source interval; no policy promises callbacks across it. Missing metadata
retained every callback. Aggregate original payload bytes (not HTTP framing) were
5,806,129 dense versus 822,327 adaptive, for
5,832 versus 824 observations. No general route equivalence is claimed.

Actual one-hour stationary file-backed Room measurement: 3,601 →126 pending originals;
immutable JSON 3,560,682 →124,955 B; allocated SQLite pages 5,988,352 →274,432 B.
All rows remained pending and retained. No byte estimate is presented as measured
seven-day disk usage. The seven-day policy replay (75% stationary /25% walking)
counts 12,096,020 dense versus 913,100 retained callbacks across 20 scaled devices;
stationary-only gives 403,320 and walking-only 2,419,220. Scaling per-device replay
counts is not simultaneous physical-device testing or online LIVE-promotion traffic.

Independent existing race-enabled receiver benchmarks used 600 originals/phone spread
over 1/2/7 days for 1/5/20 simultaneous simulated phones, with interleaved LIVE/SOS.
Measured transfer including durable receipt: 1.10 /5.25 /25.52 s; history ACK p99:
0.113 /0.643 /3.661 s. These single loopback samples ran alongside Android work;
they are not a field throughput/battery SLA or a dense seven-day fleet benchmark.
The real bridge separately checks 20 phones plus a late 21st and delayed 1/2/7-day
sparse history, ACK loss, retries/reordering, restart and SOS/LIVE priority.

Full seven-day fleet disk/endurance, physical GNSS/OEM/background/process termination,
battery and native macOS/Windows execution remain **NOT TESTED**. Source-specific
CI reports and matched APK/Command assets are linked from the issue PR.

## Durability and recovery

`TrackingService` owns `PlatformLocationSource`, reporting and the foreground
sending loop. `TrackingApp` retains the shared `LatestLocation`, SOS engine and one
`Sender`; while tracking is stopped its visible-Activity loop sends retained history and SOS through that same Sender, without generating GNSS or synthetic current snapshots. Closing/backgrounding the UI does not establish a transfer lifetime; reopen to resume, or use the existing user-started tracking service when collection is intended. Room stores one installation/configuration row plus immutable JSON
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
one observation through approximately 500 B/1/2/4/8/16/20/32/64 KB, clamped to negotiated bytes and record counts (local safety caps 65,536 bytes and 128 records); permanent invalid entries
remain unresolved while valid history continues. Old queued individual v1 envelopes
remain deliverable. Missing capability falls back to individual envelopes with a warning.
Transport uses 6-second calls and 3-second connections, network-bound DNS/sockets,
invalidation on route changes/IO failure and 1/2/4/8/16/30-second bounded retries.
Wi-Fi availability and last proven Command communication are independent.

ACK validation rejects duplicate JSON keys, malformed receipt fields, noninteger
identity fields, mismatched identities and unsupported versions. Valid stored or
duplicate receipt commits delivery in one local transaction. Current Android
installs/upgrades ignore the unauthenticated configuration portion, including first
contact; an ACK never enrolls a new trusted authority. Authenticated #25 reporting
instructions reuse the persisted desired/effective revision machinery. Older revisions
cannot roll back; overrides survive restart/local edits and explicit null restores
the current local interval. Endpoint address changes retain trusted authority.
“Enroll with a different Command” clears authority/override and requires a new
privately verified credential before any further remote setting can apply. In-flight responses from an edited endpoint generation are ignored.
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
continuously for **1.2 seconds**. A visible progress bar and percentage show the
hold; completion gives haptic feedback. A tap explains how to activate;
there is no additional confirmation dialog. Accessibility users can invoke the
button's long-click action. Immediate “trigger detected” changes to “saved on phone”
only after durable Room save. A storage failure explicitly says **NOT SAVED**.
The display retains the latest five event statuses, with older events still stored.

Releasing early, moving outside the button plus Android's touch slop, adding/losing
a pointer, cancellation, navigating away or pausing the Activity cancels an
incomplete hold. Small motion inside the button is tolerated. A completed gesture
invokes the existing durable SOS engine once; its three-second debounce is unchanged.
The adapter does not create a second sender, service or event store. Device-specific
touch/haptic reliability still requires physical acceptance.

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

### Physical Button Test (Diagnostics experiment)

Open **Diagnostics → Diagnostic tools → Physical Button Test**, expand the entry,
then choose **Start Test**. It records only whitelisted standard Android key events
actually delivered to the resumed Activity. Key-down, key-up and repeat count are
shown separately; only a non-repeat key-down counts as one physical press. The
bounded history and device manufacturer/model/Android release/API evidence are
stored in the existing Diagnostics journal and exported as
`physical-button-report.json`.

`LISTENING` means the Activity is eligible to observe an event, not that Android
will route every hardware key to it. Background, locked-screen and screen-off
volume-button detection are explicitly **MECHANISM UNAVAILABLE**. No MediaSession,
Accessibility Service, root API, audio playback, foreground service or OEM API is
used. Stopping the test produces an inactive result. Leaving the Activity while it
is listening records an interrupted, inconclusive test and stops it; a process
restart always starts with test mode off while retained evidence remains readable.

While the test is active, its Activity adapter suppresses the existing triple
Volume-Up SOS shortcut so a diagnostic run cannot create an SOS. Android's normal
volume handling is not consumed, previously saved SOS events continue delivery,
and the normal shortcut is restored on stop, pause or process restart. The
protected on-screen SOS action and durable SOS engine are unchanged.

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

With adaptive persistence OFF, approximately 1 Hz produces about 3,600 distinct observations/hour, plus routine
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

## Issue #6 operational interface

See [UI setup and acceptance](../docs/ui-acceptance.md) and [shared design system](../docs/design-system.md) for the three-destination Android interface, Command workspace, recording/quality controls, protected SOS and exactly two offline canvas/map modes. The existing tracking and SOS engines remain authoritative.

### Issue #22 synchronization

Room v5 retains every original envelope, observation/session ID, timestamp and ACKed
row. Version 3's delivery/session index remains. Version 4 adds the derived single-row
`outbox_summary`, seeded transactionally during migration and maintained by SQLite
INSERT/UPDATE/DELETE triggers. Production queue observers read counters rather than
scanning retained history after every fix/ACK. Missing summary initialization falls
back to exact SQL counts; it never fabricates zero. Historical selection uses keyset pages of at most 64 complete rows and respects
negotiated byte/count bounds before accumulating results. Session progress uses scalar
queries; at most 256 unresolved sequence examples accompany an exact optional
`unresolved_sequence_count`. Migration 1→2→3→4→5 never destructively recreates Room;
older APKs cannot downgrade the schema. Version 5 adds delivery/deadline and
measurement-time/sequence indexes. Reconnect/manual resume seeks only nonzero
transient retry deadlines, leaving already-ready rows untouched and preserving
HTTP 429 deadlines. Newest-native selection uses the time index instead of sorting
the retained outbox. Sender completion uses an indexed existence check, not a repeated count of the entire pending history. These indexes do not change envelopes or observation order.

The same Sender/outbox implements both policies. Automatic delivery starts with one
observation, then targets 500/1000/2000/4000/8000/16000/20000/32000/64000 decimal bytes.
It needs one history ACK through 2000 and two thereafter. Manual **Sync All Now**
coalesces repeated presses, preserves the healthy ramp and advances two ladder
levels per healthy ACK (RTT ≤1500 ms), prioritizing history over routine status while
always retaining SOS and fresh LIVE opportunities. Neither policy waits to fill a
batch, nor adds an artificial inter-request delay. Both use one in-flight request;
fleet evidence does not justify additional phone concurrency. Where automatic sync
already saturates safe capacity, manual mode cannot create extra capacity.

Both policies compare RTT and useful-payload goodput. RTT above 1500 ms and twice
the preceding RTT EWMA, combined with goodput below 60% of decaying best, reduces
one level. Transport failures reduce one level rather than resetting to a single
record. Healthy transient reconnects preserve adaptation; after more than two
minutes without a history ACK, reconnection caps the retained target at 32000.
An actual endpoint-generation change starts safely from one observation. Capability
errors retry (15 seconds for an unsuccessful response; bounded retry after an
exception); healthy capabilities refresh after five minutes, unsupported batch
responses after one minute. These are service feedback rules, not RSSI estimation.

Command defaults remain 65536 bytes/128 records. Explicit negotiated peers can use
131072/262144/524288/1048576/2097152-byte upper ladder levels and up to 4096 records.
Individual v1/SOS remain bounded to 65536 bytes. Historical call/read/write deadlines
are 6..14 seconds by payload, connection deadline 3 seconds; urgent calls remain
6 seconds. ACK bodies are independently bounded to 2 MiB for history. Complete
immutable envelopes are sized once for batch construction. Oversize batch/413 can
reduce the transport bound or fall back to individual v1; **413 never quarantines a
valid original**. Indexed 422 poison isolation remains distinct.

History/routine backoff does not block eligible fresh LIVE. Genuine receiver-wide
429 still throttles ordinary traffic; `X-GNSS-Throttle-Scope: history` leaves LIVE
eligible. SOS bypasses ordinary waiting and gets the next request after an in-flight
call, while respecting its own saved retry deadline. Retry delays add 0..25%
positive jitter to the 1/2/4/8/16/30-second base, never advancing numeric Retry-After.
Manual/reconnect resume cannot bypass a retained HTTP 429 deadline, including SOS
and process recreation. The existing persisted retry-reason value is kept stable;
ordinary I/O retry deadlines can still be cleared without altering originals. Pause controls future
selection, including queued steps, and preserves an in-flight receipt.

Request attempts, received responses, validated ACK identities and ACKs actually
committed in Room are distinct. The endpoint-generation check and ACK writes share
one Room transaction, returning an applied/stale result. Only applied receipts count
as successful delivery. Local counters reset with the process; Room counts survive.
Command raw receipt and reconstruction remain separate; no global completion is
inferred from unknown/offline phone inventory.

With Tracking OFF, visible-app automatic transfer collects **zero new fixes**.
User-started Sync All Now also starts a `dataSync` foreground-service lifetime,
with a transfer notification/Pause action and bounded wakelock. The service owns no
provider, queue or second Sender. It can continue after backgrounding/screen lock,
subject to Android/OEM/Doze restrictions, permission/start restrictions and Android
15's aggregate six-hour dataSync budget. It stops on completion, unresolved setup,
Pause after retryable saved SOS is settled, Tracking taking over, OS timeout, or
its own five-hour budget. Pausing history or finishing GNSS drain does not abandon
a saved SOS waiting for its own retry deadline: the same foreground lifetime and
Sender remain active for that emergency, without location acquisition. Unresolved
receiver setup, OS termination and the time budget still stop execution honestly. It is
START_NOT_STICKY: process death, force-stop and reboot require reopening/resuming;
data durability does not imply autonomous execution. Android 13 notification
permission affects notification visibility; the OS can still expose foreground work
through its task manager. Background-start failures explicitly require keeping the
app visible. Physical acceptance remains required.

Issue #25 now invokes this same Sender for authenticated Command-originated Sync
All Now. Synchronization does not alter GNSS acquisition/persistence cadence (#24),
original recording/quality rules or human SOS acknowledgement.

## Authenticated Command instructions (Issue #25)

In existing Settings configure the Command IP:port, then compare this installation's
phone UUID directly at the local Command → Phone controls console. Generate a private
per-phone credential there, enter it under **Authenticated remote control**, and
explicitly authorize. Address/authority first contact never authorizes commands.
Keystore-sealed credentials are excluded from backups and diagnostic exports.
Disable remote control locally to remove authorization; **Enroll with a different
Command** requires new verified provisioning. Current installs and upgrades never adopt remote settings/authority from an
unauthenticated first-contact ACK and never fall back after local Disable. Existing GNSS/SOS uploads
continue; they remain Protocol v1, not authenticated device attestation.

The same Sender polls the configured receiver after its SOS/LIVE/history selection.
Tracking OFF can receive/execute while the Activity or an existing permitted transfer
service runs; it does not create an invisible permanent service or start GNSS. Sync
uses #22's existing aggressive bounded drain. Progress counts saved GNSS through the
command's receipt-time sequence, including unresolved rows. Later measurements and
Command reconstruction are separate. Remote Start requires visible Activity and the
existing preflight; denied OS/permission conditions produce blocked/needs interaction.
Stop verifies service teardown plus durable session end. An interrupted uncertain
OS effect is unknown, never automatically retriggered to fabricate success.

Diagnostics → **Command Instructions** reads the canonical Room v6 command journal:
actual receipt/authentication, attempted execution, effective result, duplicate,
expiry/rejection/block/failure and authenticated result-custody confirmation. Filter
by outcome/action/UUID/UTC date, tap a row to expand stages, and copy its ID. Invalid
control responses are sanitized security events with local event IDs. Offline
commands never received are absent, not fabricated rejects. Pending/unconfirmed
results survive restart and bounded history pruning. Existing Export diagnostics
adds `command-instructions.json` without keys/MACs/raw instruction bodies. No console
operation deletes GNSS/SOS or pending control state. The UI shows 20 matching records per page, newest first; use filters or older/newer
pages to inspect retained records.

See the [wire/security contract](../protocol/protocol-v1.md).
Physical Android background, Keystore/OEM, GNSS and LAN acceptance remain **NOT TESTED**
by JVM tests; #24 remains OPEN for its deferred physical field checks.

## Command-origin phone alerts (#26)

Enrolled phones receive Normal/Urgent alerts over the same #25 Sender conversation.
Normal attempts one notification; urgent requests repeated notification sound and
vibration, default every 10 seconds for at most 60 seconds (configurable 5–30-second
cadence, 5–120-second duration). Absolute TTL (default 300 seconds, range 5–3600)
always bounds output. Android/user settings and scheduling decide actual output.
Separate normal/urgent Android 8+ channels preserve user settings; no volume/DND
bypass, new service, full-screen launch or wake lock. Notifications are private on
the lock screen; content may still be visible according to the user's OS settings.

Use the notification **Acknowledge / Stop Alert**, or the in-app Tracking action and the same action on the received
alert in **Diagnostics → Command Instructions**. The explicit local action is saved
in the existing Room v6 journal before reporting; Command confirms exact result
custody later. Notification shown/API success is never human acknowledgement.
Permission denied/channel disabled is blocked; DND/mute/channel restrictions and
unknown audible/haptic results remain explicit. Notifications have unique immutable
per-command actions/tags, independent of SOS. Dismissing a notification is not ACK.

Output repeats require the existing visible Activity, tracking service or transfer
runtime; receiving an alert does not start background work. Local Stop is durable
and duplicates/reconnect never restart it. Cold start/reboot reconciles active alerts
to interrupted/unknown without playback. TTL and monotonic process deadline are
checked by the app loop; OS timeout bounds notification lifetime if the process
stops. Scheduling is best effort; no precise wake-up or immediate force-stopped phone
reachability is promised. Offline Command cancellation is pending until received;
a remote revocation cannot stop an unreachable phone sooner than its local bound.
ZIP includes sanitized alert stages and timings, redacting message content. Existing
Room v6 columns store the validated alert request; no destructive migration/new DB.
See [physical acceptance](DEVICE-ACCEPTANCE.md); real sound/vibration, DND/mute,
locked/OEM/Doze behavior remain NOT TESTED without actual hardware.

## Settings and Diagnostics field presentation (#39)

Settings groups Party/Command, Tracking & GNSS Policy, field readiness and existing
appearance/emergency preferences. Party, endpoint and interval edits remain drafts
until **Save settings** succeeds locally; **Cancel edits** restores persisted values.
Draft text/focus/selection and disclosures survive Activity recreation and data
refresh. The adaptive switch is explicitly an immediate Apply of #24's existing
policy, OFF by default, affecting future stored observations only. Local cadence,
latest retained Command request/outcome, active override and applied cadence/revision
are distinct. Configuration never starts GNSS by navigation or recreation.

Enrollment remains the protected #25 workflow. Wi-Fi, message ACK and authenticated
control contact are distinct. Enrollment details show the current authority and
actual retained authenticated instruction/result timestamps. Empty authenticated
polls have no historical journal record: when current contact is lost, the last
successful poll time is honestly unknown rather than inferred from Wi-Fi or ACK.
System permission/location/notification/battery actions retain the existing visible
Start preflight. Android/OEM background execution and audible/haptic output are not
guaranteed.

Diagnostics begins with Current Health: fix accuracy/age, Room commit failures and
queue, transport ACK, sync/backlog/unresolved records, SOS delivery versus Command
operator ACK, device storage and remote-control trust. Phone Alerts separately
exposes received/output/blocked/local ACK/cancellation evidence without claiming an
alert was heard. **Advanced Diagnostics** retains SOS evidence, technical details,
Physical Button Test, confirmed retry and sanitized ZIP export.

Command Instructions still reads only the canonical bounded Room journal. Type,
outcome and received-time selectors combine with UUID/reason/stage/UTC-date search.
Duplicate filters include the actual duplicate stage while preserving the original
outcome. Pages show 20 newest-first matching records; open stages, filters and page
survive recreation. Rendering is deferred while the console is collapsed or another
destination is selected. Keys, transport, database schema, command execution and
result custody are unchanged. Physical Android/TalkBack/OEM acceptance remains
separate from JVM native-render tests.
