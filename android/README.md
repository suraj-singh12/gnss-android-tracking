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

`TrackingService` owns `PlatformLocationSource`, `LatestLocation`, reporting and
`Sender`. Room stores one installation/configuration row plus immutable JSON
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
