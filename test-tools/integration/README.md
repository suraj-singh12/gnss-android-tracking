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
