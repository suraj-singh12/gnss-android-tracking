# Android → Command integration

Automatic DEM follow-up: `dem-acquisition.cjs` performs an actual public Skadi GET
and gzip decode. `dem-offline-acceptance.cjs` requires `GNSS_COMMAND_BINARY` and
Playwright (`GNSS_PLAYWRIGHT_MODULE`; optional `GNSS_CHROMIUM`), drives the real
OSM/DEM download UI at Mussoorie, independently validates every geographic DEM node,
rendered shading/contours/elevation, then restarts with Command provider traffic
blocked and denies external browser requests. Failed offline retry preserves exact
terrain bytes. `GNSS_TERRAIN_PROVIDER_REPORT` sets the JSON evidence path;
`GNSS_SCREENSHOT_DIR` retains actual rendered screenshots. These are mandatory
real-provider checks in matched-assets Actions, distinct from deterministic fixtures.

Corrective UI acceptance also runs `sos-alarm-test.cjs`, `sos-dashboard.cjs`,
`ui-dashboard.cjs` and `map-test.cjs`. The browser uses the real embedded Command
HTTP/state and SQLite paths. Only public map-provider search/preview boundaries
are substituted with explicitly synthetic GeoJSON fixtures in the UI test;
`TestOfflineMapProviderAndLimits` separately exercises real HTTP provider decoding,
query bounds and cooldowns against a local fixture server. Browser checks include
map save/reload/Command restart without provider requests, three SOS events,
partial/final ACK, dismissal safety, credible course arrows and viewport-bound
1280×720/800, 1440×900 and narrow layouts. These are not live OSM or speaker/device
evidence; see `docs/ui-acceptance.md` for external and hardware acceptance gaps.

From the repository root, with Go 1.24.7+, JDK 17+ and Android SDK 35/build tools
35.0.0 installed (`ANDROID_HOME` set):

```sh
test-tools/integration/run.sh
```

The script compiles Command's test-only process adapter with the race detector,
then runs `CommandIntegrationTest` under Android's existing JUnit/Robolectric setup.
It exits nonzero on any failed scenario. Gradle XML/HTML results are in
`android/app/build/test-results/testDebugUnitTest` and
`android/app/build/reports/tests/testDebugUnitTest`. Temporary process binaries and
Command SQLite files are removed on exit; Room databases are deleted after each
scenario. No harness dependency is added to either product.

Without `GNSS_COMMAND_BRIDGE`, the integration class explicitly skips; ordinary
`testDebugUnitTest` requires no Go installation. **A skipped integration class is
not an integration pass.** Run the script in addition to normal Android validation.
The script forces execution, so prior Gradle results cannot masquerade as a pass.

## Real boundaries

Android production `Protocol.encode`/`receipt`, Room `Repository`/outbox and `Sender`
execute unchanged. A test `Transport` uses localhost OkHttp instead of Android's
physical `Network` socket/DNS adapter, posting the exact saved bytes with production
`Endpoint.messages` and JSON Content-Type. Every attempt checks the committed Room
row before posting. No mock outbox, mock ACK generator or phone simulator is used.

Command production `IngestHandler`, `Parse`, `Store`, SQLite transaction, ACK
creation, `LocalHandler`, configuration and track/recording engine execute unchanged
in a separate Go process. Configuration and recording use real local HTTP APIs.
A small `android_bridge_test.go` adapter supplies ephemeral loopback listeners and
serialized test-clock/fault/SQLite inspection via **stdin/stdout only**. Go process parallelism is capped at two for predictable startup in CPU-quota
containers; bounded reads/timeouts surface failures instead of hanging. No diagnostic
route is exposed on phone ingestion or compiled into the Command product.

Response loss closes the HTTP connection after the real ingestion handler commits.
Storage failure aborts the real SQLite state write and must roll back raw insertion.
429 is an explicitly injected adapter response because Command currently has no
rate limiter; it proves Sender's Retry-After handling, not Command overload behavior.
Malformed/mismatched ACK injection alters a real Command response at the adapter.
Receipt field/Content-Type mutations cannot mark delivered or enroll. The maximum
wire sequence is echoed exactly and subsequent allocation cannot wrap.

Restart kills/exits and launches the Go process against the same SQLite file.
Android restoration closes/reopens the actual file-backed Room database and creates
new Repository/Sender instances; it proves persistent application state, **not** OS
process/foreground-service recovery. Arrival permutations reuse one empty recording
SQLite checkpoint (process closed first), so window IDs and point identities can be
compared exactly, including segment UUIDs and projected coordinates.

## Automated scenarios

| Scenario                                  | Assertions                                                                                                                                                                                                                      |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Normal location/status + first enrollment | Exact ACK identity, one local allocation/raw row, dynamic party metadata, preserved measurement/capture times, status retains location, authority persists and is echoed                                                        |
| Override/local edit/clear                 | Real local controls → ACK → Room config → new echo → convergence; local 10 → override 30, local edit 20 retains 30, explicit clear falls back to 20; queued JSON stays immutable                                                |
| Five-second amendment                     | Local 5 → real Command override 5 → ACK adoption/Room reopen → local edit 15 retains effective 5 → Clear falls back to 15/converges; local/control reject 1, 6, 86405                                                           |
| Lost response                             | Exact saved retry, duplicate with original first receipt, one row/track effect/distance effect per observation                                                                                                                  |
| Current-first recovery                    | Sender wire order D A B C, live D never rolls back, history A B C D; failed retry deadline cleared on restore                                                                                                                   |
| Stale recovery + reserved SOS             | New current saved before old backlog; SOS precedes current and never enters tracks; complete trigger/operator workflow also covered below                                                                                       |
| Stale recovery + reserved SOS             | Legacy status recovery retry retained; native live observation precedes oldest history when available; reserved SOS precedes ordinary work and never enters tracks                                                              |
| Arrival permutations                      | A B C D / D A B C / C A D B / D A B B C yield identical point IDs, geometry, segments, cumulative distance; old backlog stays historically valid while live is stale                                                            |
| Bad GNSS                                  | Raw retained with explicit jitter/poor/unknown/stale/jump rejection reasons, recovery opens segment without bridge; turn, sideways and reversal accepted; altitude does not add distance                                        |
| Recording                                 | Stop preserves live reception without extending recording; Resume adds segment without connector; unconfirmed Clear rejected; confirmed Clear retains raw/device/config; late backlog/restart/new recording cannot resurrect it |
| Five devices                              | Independent Room installations, IDs/sequences/configs/track segments/totals and late join during active recording                                                                                                               |
| Command process restart                   | Original receipt/dedupe, authority/version/override, recording/windows/geometry survive; exact pending retry then normal sending                                                                                                |
| Android Room restoration                  | Identity/sequence/pending payload/local setting/authority/override/delivery metadata survive; exact retry and subsequent sequence continue                                                                                      |
| Failure/security boundaries               | Real 503 rollback, invalid ACK stays pending then duplicate succeeds, actual changed-envelope 409, 429 timing; controls/state/assets absent from phone listener                                                                 |
| Clocks                                    | Production monotonic fix age; stale latest fix omitted; UTC/elapsed mismatch and future capture stored raw/rejected honestly; receipt never replaces observation time                                                           |

## Compatibility audit of merged cores

The original integration audit used latest merged main `931cc8cb32ec8a1dc2847aac1837c720bd11bd8b` against
[Protocol v1](../../protocol/protocol-v1.md). Issues #1–#3 were closed; #4's frozen
constraints and #5–#7 future scope were read before implementation. The tables
below include the deliberate 2026-10-07 Issue #4 reporting-interval amendment.

| Android send → Command parse                                                                              | Result                                                                                                                                                        |
| --------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `protocol_version`, `type`                                                                                | Integer 1; location/status/sos enum matches                                                                                                                   |
| `device_id`, `message_id`, `sequence`                                                                     | Canonical UUIDs; durable positive sequence through 9007199254740991; exact echoed identity                                                                    |
| `party.id`, `party.name`                                                                                  | Both actual Android settings emit nonblank labels of ≤80 Unicode code points; Command accepts these without changing identity                                 |
| `captured_at`                                                                                             | Both enforce valid UTC with exactly three fractional digits                                                                                                   |
| `config_state.authority_id`, `version`, `reporting_interval_override_s`                                   | Initial null/0/null; enrolled UUID/version/nullable override; version bounded by wire integer                                                                 |
| `config_state.local_reporting_interval_s`, `effective_reporting_interval_s`                               | Integers 5..86400 in steps of 5; override wins, otherwise current local value                                                                                 |
| `health.battery_percent`, `charging`, `wifi_connected`, `wifi_rssi_dbm`, `gnss_status`, `satellites_used` | Required fields, explicit null where unavailable; matching ranges/enums. Android's native satellite count is an Int, safely within Command's wire-bound int64 |
| `fix.observed_at`, `fix_age_ms`, `latitude`, `longitude`                                                  | Exact UTC; integer elapsed capture age; finite WGS84 bounds                                                                                                   |
| `fix.horizontal_accuracy_m`, `altitude_m`, `altitude_accuracy_m`, `speed_mps`, `bearing_deg`              | Explicit nulls; finite/nonnegative accuracy/speed; bearing [0,360); altitude accuracy null without altitude                                                   |
| `sos.event_id`, `triggered_at`                                                                            | Reserved only for SOS; event ID equals message ID; exact UTC; nullable fix; same endpoint/dedupe and no track effect                                          |

| Command ACK → Android parse                                       | Result                                                                                                                                                        |
| ----------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `protocol_version`, `device_id`, `message_id`, `sequence`         | Valid integer 1 and exact identity required before delivery; Repository rechecks identity                                                                     |
| `result`, `received_at`                                           | stored/duplicate both succeed; original first receipt in exact UTC survives retry/restart                                                                     |
| `config.authority_id`, `version`, `reporting_interval_override_s` | Nonnull authority UUID; bounded version; explicit null clears; zero version has no override                                                                   |
| Authority/version application                                     | First valid snapshot enrolls even at version 0; newer applies atomically; stale/equal match no-op; equal conflict/different authority error retain old config |
| Invalid config with valid receipt                                 | Delivery retained separately, old config preserved with visible error, per frozen contract                                                                    |

Transport matches: `POST /api/v1/messages`, UTF-8 application/json, 65536-byte
limit. Duplicate keys/missing nullable fields rejected; additive fields ignored.
Command compares known semantic content and commits before returning HTTP 200;
Android sends saved JSON, never a reconstructed retry. 409 quarantines without
new identity; timeout/429/5xx remain pending with backoff. Command doesn't currently
emit 429, but Android supports it. Endpoint generation guards late responses;
address changes preserve authority unless the operator explicitly re-enrolls.

No production interoperability defect or protocol contradiction was found in this
baseline. Internal model differences alone require no compatibility layer. The
track algorithm is unchanged; Issue #4 physical acceptance explicitly amends
reporting intervals to 5-second steps, with default 10 s. The existing anchor engine remains the oracle; worker checkpoints and affected suffixes
avoid full derivation on every receipt. Extended endurance remains #7.

Physical GNSS, Wi-Fi Network routing, screen-off/OEM behavior and routers cannot be
proved here. Execute the canonical [field acceptance runbook](../../android/DEVICE-ACCEPTANCE.md).

Android `LocationSourceTest` separately exercises the native LocationManager seam:
registration survives Activity destruction, simulated platform callbacks refresh observations,
stopping unregisters, missing callbacks become stale, and registration can restart
without an Activity. An actual TrackingService/MainActivity lifecycle test checks foreground promotion,
single registration across repeated starts, Activity destruction and service stop.
Service resource tests cover bounded CPU-lock renewal and
release on stop/failure. These are deterministic lifecycle assertions, not proof
of physical GNSS, screen-off radio operation, deep Doze or OEM policies. The source
review found no Activity-driven GPS registration; absence of CPU wake protection
was a concrete lifecycle gap. The precise trigger on the reported phone remains
unconfirmed until the updated physical retest and diagnostic evidence.

## Issue #5 SOS extension

The existing bridge now additionally drives the production central SOS engine into
real Room/Sender and real Command HTTP/SQLite. Offline creation competes with 100
tracking envelopes; a stale GNSS snapshot is retained truthfully. The suite checks
transactional debounce, exact priority payload, phone database reopen, actual lost
response after durable Command storage, immutable duplicate/original receipt,
remote interval change, recording Stop/Resume/Clear independence, event-specific
operator ACK, a second emergency, Command process restart, phone restoration and
browser-state reload through the real local handler. A wrong matching-identity ACK
mutation also proves the phone remains pending until a valid duplicate ACK, even
when a human has already acknowledged the event on Command.

All prior tracking scenarios still run. This bridge proves software boundary and
storage behavior, not physical Wi-Fi availability, native GNSS, locked-screen key
routing, OS process recovery or browser-speaker playback. Follow the separate SOS
section in the canonical device-acceptance runbook after Issue #4 physical acceptance.

An optional real-browser check uses a built Command binary, Node and an external
Playwright installation (test dependencies only; Command adds no runtime dependency):

```sh
GNSS_COMMAND_BINARY=/absolute/path/to/party-tracker \
GNSS_PLAYWRIGHT_MODULE=/absolute/path/to/playwright \
GNSS_CHROMIUM=/absolute/path/to/chromium \
node test-tools/integration/sos-dashboard.cjs
```

It verifies the actual embedded dashboard, blocked audio status, keyboard ACK and
focus stability, duplicate delivery, multiple SOS, browser reload, real Command
restart and Recording Clear persistence. It does not establish speaker audibility.
Issue #4 field regressions additionally exercise 100 old status envelopes before an
offline rectangle: current location first, lost ACK, phone/Command persistence
reopen, duplicate ACK, GNSS history before statuses, 80 m chronological geometry
and unchanged distance after statuses. Continuous new current reports must still
yield historical progress. Command tests cover full-policy reprojection, legacy
policy-only boundaries, live markers with no recording, current-first status
INCONCLUSIVE, expired cadence, per-type evidence and drawing smoke checks.
These tests replace only the physical Android Wi-Fi adapter; they cannot establish
locked-screen Wi-Fi reassociation or OEM radio behavior.

The approved native-resolution regression separately persists 21 one-second
observations around a rectangle while live reporting is 30 seconds. Reopen Room,
send one current report, synchronize all intermediates, assert 21 distinct raw
observations/100 m geometry, change policy and retain the same source history.
Android tests cover additive v1→v2 migration, idempotent callback commit, bounded
nonblocking handoff/failure evidence, and older/poor-accuracy native callbacks.

The native history batch regression negotiates the real capabilities endpoint,
drives Room's original identities through real atomic Command ingestion, loses a
response after commit, retries, reconstructs a 21-point/100 m intermediate rectangle
at 30-second live cadence, and echoes zero pending. No current-fix copy is created.
Test-only inspection permits responses larger than the production 64-KiB phone ACK
limit; this does not widen LAN APIs. Bridge restarts preserve their reader executor.

Focused Room tests cover per-session sequence/measurement identity, live exclusion
of already-ACKed newest observations, recovery blocks, adaptive success/failure/reset,
indexed permanent failure and preserved invalid rows. Command tests include atomic
rollback, per-device restart-safe projection work, multiple outages against the
full oracle, rejected provisional junction/recovery, policy switches, session modes,
late joins, stale queue/explicit unresolved outcomes, old receiver metadata backfill,
and live display while projection is busy. Shared native/batch fixtures are consumed
by both production parsers.

Combined SOS reconciliation additionally holds a real native-history request in flight, saves a no-fix SOS in Room, then proves SOS is the next HTTP request after release. Its immutable retry after both restarts produces one alert and no route/distance. Five-device performance includes SOS requests during recovery; latencies are measured rather than hardware guarantees.

The native two-outage comparison retains 100 one-second measurements per phone
at 30-second live cadence. Continuous delivery and 41–49 / 52–79 recovery blocks
produce identical logical point order, segment membership and geometry. Real Sender
delivers 80 current first, then the two oldest blocks without regressing live state;
all original Room identities are retained. A poor-accuracy raw point is reconsidered
by full policy reconstruction, and the final zero-pending report certifies receipt
and completed projection independently.

## Issue #21 complete retained history

The real Room/Sender/HTTP/SQLite suite adds separate simulated 1-, 2- and 7-day
outages across five installations and ten retained tracking sessions. Command is
stopped during Room capture, then starts From Beginning with zero device inventory.
Original saved rows survive Room reopening, newest observations precede older
blocks, one late device reports no fix, projection fails after durable ACK, Command
restarts before recovery, and a policy edit runs during backfill. Sender drains
original outboxes through negotiated history; exact duplicates add no observation.
The test-only bridge's independent membership selector and one-pass `Derive` engine
compare exact accepted IDs, boundaries, decisions and unrounded cumulative distance.
Final restart retains the same geometry. Logged duration covers receipt plus
reconstruction; Go tests separately measure projection-only passes over 2,240 rows, and variable
three-/twenty-device populations (twenty devices, forty sessions, 1,180 observations).
These are measured software cases, not a fixed device limit or a hardware capacity promise.

The existing browser suites still run. `history-dashboard.cjs` additionally drives
real HTTP/SQLite and Chromium with five devices/ten sessions, scrambled old blocks,
a missing sequence/recovery, quality-filtered empty route, unknown offline inventory
and From Now exclusions. It compares the actual SVG qualified point/section counts
to the read model; fixture GNSS is not physical field acceptance.

```sh
GNSS_COMMAND_BINARY=/absolute/path/to/party-tracker \
GNSS_PLAYWRIGHT_MODULE=/absolute/path/to/playwright \
GNSS_AXE_MODULE=/absolute/path/to/axe-core/playwright \
GNSS_CHROMIUM=/absolute/path/to/chromium \
node test-tools/integration/history-dashboard.cjs
```

Follow the genuine 24-hour offline procedure in the existing
[device acceptance runbook](../../android/DEVICE-ACCEPTANCE.md) for hardware evidence.

## Issue #22 measured synchronization

`CommandIntegrationTest.trackingOffTwentyPhonesAndLateJoinResumeOriginalRowsAfterLostResponses`
uses 21 independent real Room databases, production Sender, HTTP and SQLite. Twenty
phones enter backlog delivery before the 21st joins. Responses are lost after
Command commits; Room and Command reopen, then only original outstanding rows drain
with Tracking OFF and zero capture calls. Every saved ID/JSON survives, and qualified
points/decisions/segments/unrounded distance match the chronological reference.
This is software lifecycle evidence, not Android process death, physical GNSS or
screen-off acceptance. The ordinary unit invocation skips the bridge; separately
run `run.sh` and require zero skipped bridge methods.

The Command module also supplies an opt-in streaming **real protocol/HTTP/SQLite**
benchmark (not a mocked receiver and not an Android performance measurement):

```sh
cd command
GNSS_SYNC_BENCH=1 GNSS_SYNC_DEVICES=20 GNSS_SYNC_DAYS=7 \
GNSS_SYNC_SAMPLES=4200 GNSS_SYNC_BATCH=64000 GNSS_SYNC_PRIORITY=1 GNSS_SYNC_PATTERN=bursts \
go test ./internal/core -run '^TestSyncFleetPerformance$' -count=1 -timeout=30m -v
```

`SAMPLES` is observations **per phone**, distributed over `DAYS`; 4,200 over seven
days is a defined sparse corpus, not dense native capture or evidence for Issue #24's
future adaptive policy. `PATTERN=bursts` models retained walking bursts (one-second
samples), stationary jitter, vehicle motion/turns, poor accuracy and long known
observation gaps. The default uniform pattern is a simpler scaling fixture. Both
preserve original synthetic measurement/elapsed times and immutable IDs; neither
claims real field sampling statistics or changes Android capture. For 1/2/7-day dense 1 Hz use 86,400 / 172,800 / 604,800 samples
per phone respectively. Repeat for 1/5/20 phones; no production device limit exists.
The generator retains one batch per phone in memory; final production reconstruction
and the independent one-pass oracle still need resources proportional to history.
Do not run a corpus whose retained SQLite/evidence/geometry cannot fit the host.

`BATCH=20000` reproduces the original target; 64000 exercises the existing 64 KiB
ceiling. Every request uses original stable device/message/session identities and
checks every returned ACK; a separate streaming SQL pass accounts for every original device/message/sequence tuple, alongside exact raw count and chronological accepted IDs/decisions/
segments/distance are verified. `PRIORITY=1` adds one actual fresh live fix and one
SOS per phone during backlog, reporting their durable ACK latencies separately.
The same live/SOS identities are retried periodically during continued backlog,
including its final stages; printed per-phone latencies are maxima, with separate aggregate per-request p50/p95/p99 for live and SOS. Retries must
not create extra observations or alerts. This does not prove operator reaction time
or physical speaker audibility.

Output separates source/request/ACK payload bytes, request count/concurrency,
transfer including durable receipt, receiver handler time (sum across requests,
including queue/parse/SQLite commit), residual reconstruction flush after transfer,
dashboard read-model time, full chronological reference time, ACK RTT percentiles
and Go allocations/heap. Worker reconstruction overlaps transfer, so residual flush
is **not** total reconstruction CPU time. Handler time is **not** isolated SQLite
fsync time. HTTP headers, physical Wi-Fi, Android Room throughput and native laptop
CPU must be measured separately; do not promote localhost numbers to a capacity SLA.
Use `-cpuprofile=/outside/repository/sync.pprof -o /outside/repository/sync.test` and
`go tool pprof` for CPU evidence; profiling and competing tests affect wall times.

The matched-assets workflow checks out the exact PR head for all jobs, runs a
20-phone sparse benchmark with live/SOS, and uploads its log with existing reports.
It independently fails a skipped real bridge suite. External DEM/provider acceptance
runs in parallel and publishes its own same-source artifact; it cannot delay or
skip independent GNSS/Android checks. A provider failure still fails the workflow
and is reported separately from successfully built matched applications. SQLite disk-full regression
uses `max_page_count` to produce real `SQLITE_FULL`: no ACK/partial raw commit, then
successful immutable retry after the cap is removed. Native slow disks, radios,
Android OS restrictions and the full dense fleet remain separate acceptance work.

### Measured local evidence — 2026-10-15

Sequential before/after runs on this Linux cloud host used a two-CPU quota,
`GOMAXPROCS=2`, 8 GiB memory limit, local disk, real loopback HTTP and SQLite
`synchronous=FULL`. Baseline application: `d78ef85e689ca43281127599e814b04cb99aefc8`;
candidate application: `7a6f926749b8a765530c5d591fb66d5da3015c58`. Subsequent changes
add benchmark identity/latency reporting only. The same burst corpus and immutable
IDs were used on both revisions, with 20,000 / 64,000-byte history targets and one
in-flight request per phone. Times are host-specific; they exclude Android Room
seeding and physical Wi-Fi. Reconstruction below is the residual flush after
transfer; concurrent worker CPU is not added twice.

| Phones | Source days | Observations/phone | Before receipt (s) | After receipt (s) | Before / after residual reconstruction (s) |
| --- | --- | --- | --- | --- | --- |
| 1 | 1 | 600 | 0.436 | 0.298 | 0.000 / 0.005 |
| 1 | 2 | 1,200 | 1.053 | 0.561 | 0.000 / 0.006 |
| 1 | 7 | 4,200 | 8.544 | 2.076 | 0.046 / 0.063 |
| 5 | 1 | 600 | 1.474 | 1.292 | 0.000 / 0.050 |
| 5 | 2 | 1,200 | 4.318 | 2.774 | 0.000 / 0.087 |
| 5 | 7 | 4,200 | 26.927 | 10.798 | 0.252 / 0.292 |
| 20 | 1 | 600 | 6.550 | 5.253 | 0.000 / 0.135 |
| 20 | 2 | 1,200 | 16.418 | 11.018 | 0.309 / 0.413 |
| 20 | 7 | 4,200 | 91.586 | 49.185 | 2.151 / 2.524 |

The twenty-phone seven-day sparse case retained **84,000 historical observations**,
plus twenty original fresh live fixes and twenty SOS events. Source history was
83,734,960 bytes. Requests fell from 4,340 to 1,340; total request payloads were
84,217,360 / 84,121,360 bytes and ACK payloads 26,665,360 / 26,578,360 bytes. Every
original identity was retained, with identical **59,660 qualified points**, exact
section membership/decisions and unrounded distance against chronological derivation.
Candidate throughput was 1,708.6 raw messages/s; retained Go heap at measurement
was 566,326,312 bytes and cumulative allocation 18,579,044,056 bytes (not peak RSS).
These data show remaining allocation/reconstruction scaling costs; no universal
30-second or dense-fleet promise is made.

A separate dense one-phone one-day **86,400 observations at 1 Hz** case passed:
85,050,522 source-history bytes, 85,415,248 total request payload bytes, 27,422,890
ACK bytes, 1,348 history requests, 56.664 s receipt, 1.037 s residual reconstruction,
86,402 retained raw messages including live/SOS, and 86,401 qualified points. The
source generator streamed bounded batches; the production read model and oracle
still retain data proportional to history. Full dense 2/7-day and twenty-phone
12.1-million-observation capacity are **not certified** by these measurements.

An additional baseline dense one-day run completed receipt in 207.778 s and
residual reconstruction in 1.003 s (4,320 requests, identical source bytes).
A subsequent candidate two-day dense stress completed receipt but failed while
persisting the projection: the single JSON value exhausted embedded SQLite's
256 MiB runtime allowance. That failed run is not a pass. The resulting bounded
projection-chunk fix has restart/order/rollback regression coverage. Its subsequent
two-day dense rerun passed: 172,800 original history records, 170,288,865 source
bytes, 109.676 s receipt, 1.031 s residual reconstruction and exact geometry.
The seven-day rerun passed: 604,800 history records, 596,921,886 source bytes,
473.435 s receipt, 4.482 s residual reconstruction and 604,801 qualified points
(including LIVE). All original identities and the chronological oracle matched.
LIVE ACK p99 was 206.473 ms; SOS p99 13.477 ms (1,009 requests each). Cold dashboard
materialization took 29.424 s; this exposed ingestion-mutex contention, subsequently
removed using one bounded read-only WAL connection. The large read-model cost
itself remains. These are local stress results, not full dense-fleet certification.

At projection-chunk commit `47633ec`, a controlled twenty-phone/4,200-observation
rerun passed in 49.362 s receipt / 0.771 s residual reconstruction. Peak process
RSS was 647,456 KiB; CPU time 72.127 s user / 6.614 s system for the Go test command.
The one-phone dense one-day rerun passed in 49.556 s receipt / 0.572 s residual,
peak RSS 814,196 KiB; CPU 70.692 s user / 6.844 s system. These CPU figures include
the harness/oracle. They precede the independent dashboard-reader change.

The real Room/Sender bridge completed **29 tests, zero skips/failures/errors** in
564.306 s. Its twenty-plus-late-phone Tracking OFF case took 45.736 s including
Room setup, forced lost replies, reopen/restart and oracle checks: **2,016 original
observations, 285 history attempts, zero new captures**. This duration is not a
pure network throughput measurement. An earlier local attempt exhausted this host
while overlapping Gradle workloads; it failed and was superseded by the isolated
1-GiB-JVM run, never counted as passed.

A final 20-phone / 600-observation latency/accounting probe additionally passed
streaming SQL identity checks and verified one retained SOS alert per phone despite
retries. Live ACK p50/p95/p99: 340.823 / 707.911 / 787.319 ms; SOS: 150.794 /
396.852 / 717.281 ms (40 requests of each). It ran alongside an Android build and
is reported separately from the controlled comparison. SQLite-only fsync duration
was not isolated; receiver timings include queue/parse/transaction costs. Actual
Android radios, OEM screen-off behavior and native macOS/Windows remain untested.
