# Command

One Go application for macOS, Windows and Linux: LAN Protocol v1 receiver,
local SQLite, deterministic recording/track engine, and embedded offline dashboard.
Field operators need only the executable and a browser already on their laptop.
No Node, Python, Docker, external WebAssembly runtime or database service is needed.

## Build and run

Development requires Go 1.24.7 or newer. From `command`:

```sh
go build -o party-tracker ./cmd/party-tracker
./party-tracker
```

On Windows build/run `party-tracker.exe`. Open `http://127.0.0.1:8081` on the laptop.
Phones POST to `http://<laptop-LAN-address>:8080/api/v1/messages`. Allow that port
in the laptop firewall on the trusted field LAN. Use flags to change either bind:

```sh
./party-tracker -ingest-listen :8080 -dashboard-listen 127.0.0.1:8081 -db /path/to/command.sqlite
```

`-db` defaults to `os.UserConfigDir()/party-tracker/command.sqlite`, so paths follow
OS conventions. The dashboard/control listener defaults to loopback. The LAN
listener exposes only phone ingestion. V1 assumes a trusted isolated LAN, with no
phone authentication or TLS; keep controls local. All assets are embedded and
make no internet requests. Normal operation needs no shell scripts.

SQLite driver: **github.com/ncruces/go-sqlite3 v0.28.0**, with its embedded SQLite
WebAssembly binary and pure-Go wazero runtime. Everything is inside the executable;
CGO and installed native libraries are unnecessary. This dependency is pinned in
`go.mod`/`go.sum`. The original modernc download was inaccessible in the validation
environment; the selected driver passed native tests and the documented build matrix.

## Persistence and responsibilities

`internal/core/protocol.go` validates Protocol v1 and its authorized optional native-history metadata, including required nullable
fields, duplicate keys, actual integers, ranges and additive-field compatibility.
Exact wire-key selection precedes typed decoding, so case-variant additive fields
cannot shadow known fields. Canonical known content defines retry equality, including numeric spelling
normalization; original wire bytes are retained separately. SQLite uniqueness covers
both `(device_id,message_id)` and `(device_id,sequence)`. WAL + `synchronous=FULL`
commits original raw content, identity, first receipt, device/contact state and pending projection work before ACK. Conflicts never overwrite. Duplicate ACKs return the original
receipt and current desired config; retries update last contact but add no track edge.

SQLite retains immutable raw envelope/wire data, identity indexes, configuration,
recording windows and field evidence. Atomic individual/batch ingestion commits raw
and projection work before ACK. Projection failure does not revoke receipt. A separate
worker resumes accepted-anchor checkpoints, revisits affected suffixes after late
history and regenerates caches after restart. Policy edits reprocess the full current
recording. Pending/error indicators prevent a failed projection appearing synchronized.

Live measurement ordering uses `(observed_at,envelope_sequence,message_id)`; health, labels
and config echoes use `(captured_at,sequence,message_id)`. Status without a fix
preserves the location. A newer poor/stale measurement is labelled honestly as such.
Live source quality/freshness is independent of recording movement/speed/window
acceptance; a fresh marker appears even with no recording or incomplete backlog.
Poor accuracy, stale-at-capture and clock anomalies remain explicitly unavailable. Location age includes elapsed wall time and capture age; backlog is never fresh just
because it arrived now. Device colours/line styles are assigned once and persisted,
independent of health (palette/styles cycle for arbitrary device counts).

Desired config has a persisted authority UUID and monotonic per-device version.
Set and explicit-null Clear each increment version. ACK always carries the current
complete snapshot. Convergence compares all desired fields with the newest captured
Android echo. Delivery/application may wait until the next request/ACK exchange.

## Concrete quality policy (revision 1 defaults)

| Setting | Default |
| --- | --- |
| Minimum forward / backward movement floors | 2 m / 2 m |
| Maximum horizontal accuracy | 25 m |
| Maximum fix age at capture | 30 s |
| UTC versus monotonic capture-age tolerance | 5 s |
| Maximum uncertainty-adjusted horizontal speed | 12 m/s |
| Maximum time gap from last accepted point | 120 s |
| Uncertainty multiplier | 1 |

Unknown accuracy is invalid for track derivation. A fix is clock-anomalous if
`abs((captured_at-observed_at)-fix_age_ms/1000) > clock_tolerance_s`. Captures more
than the tolerance into the future relative to original receipt are also invalid.
Backlog duration alone never invalidates historical freshness.

Each candidate compares with the **last accepted** point. Implausible speed means
`max(0, geographic_displacement - accuracy_previous - accuracy_candidate) / dt`
exceeds the speed limit. Reported GNSS speed/bearing are telemetry, not authority.
A quality rejection keeps the credible anchor for subsequent speed checks, then
opens a reasoned subsegment at the next credible point. A gap greater than 120 s
also opens a subsegment. Neither case adds a distance connector. Consequently the
policy conservatively undercounts movement through unknown/invalid intervals.

Significant displacement must be at least
`max(direction_floor, uncertainty_multiplier * hypot(previous_accuracy, candidate_accuracy))`.
For two 3 m accuracy fixes, the effective floor is about 4.24 m, not 2 m.
Direction is the displacement from oldest to newest in a rolling window of up to
four accepted points, established after three accepted points and a net displacement
at least the larger floor. The cosine of the candidate/recent geographic heading difference
above 0.5 is forward; below
-0.5 is backward. Otherwise/no established direction uses the larger floor.
This only selects a movement floor; turns, lateral geometry and reversals remain
free. Direction resets at subsegment boundaries; no smoothing or invented positions.

Settings are explicit in the secondary dashboard form and local API, validated as
finite positive values up to 86400 (uncertainty multiplier must be at least 1).
An explicit quality edit rebuilds the **full current recording** from retained raw
observations under a new revision. It updates closed and active windows; Start/Stop/
Resume boundaries remain. Existing policy-only windows are merged on that edit.
This intentionally replaces the former frozen-window policy after Issue #4 field
acceptance findings; it does not resurrect cleared recordings. Decisions record rejection
reasons and revision; accepted points include segment UUID, boundary reason and
unrounded cumulative distance. Subsegment UUIDs are deterministically generated by
Command from its window/device/first-candidate ownership, never supplied by Android.

## Recording and drawing

Start creates a Command UUID recording and half-open observation-time window.
Stop closes `[start,stop)` but continues ingestion and live status. Backlog measured
inside that closed window still rebuilds it. Resume opens a new window in the same
recording, adding its distances without a connector. Devices join dynamically;
first accepted points add zero. Clear requires explicit UI confirmation and retires
the current recording, dropping derived results while preserving raw/device/config
state. Retired windows never participate in rebuilds or later recordings.
Only location messages enter tracks; SOS fixes remain alert telemetry and never enter tracks.

Distance is horizontal haversine with radius **6,371,008.8 m**, wrapped longitude
deltas, no altitude component and no edge rounding. The renderer uses original
WGS84 accepted coordinates projected about the earliest accepted point across all
devices by `(observed_at,device_id,sequence,message_id)`. Earlier backlog can change
that origin, so projection is recomputed consistently. SVG fits with equal X/Y scale,
north up. A separate geographic overlay allows a later offline map layer underneath.
Projection never influences acceptance or travelled distance.

The canvas dominates the page. Dynamic device cards show totals, independent contact
and GNSS conditions, last seen, location age, battery and reporting/config state.
Show/hide affects lines, markers and labels only. Display-dot interval (10..86400 s,
multiples of 10) selects first point then the first actual point crossing each elapsed
bucket; it never interpolates or changes lines/storage/distance. Point hover and
keyboard focus show device label, local time with timezone, cumulative distance and
accuracy. Polling is every 2 s; reconnect errors are visible. No frontend build step.

Contact policy uses the latest reported **effective** interval `I`: healthy through
`2*I+5 s`, delayed thereafter, contact lost after `4*I+10 s`. Wi-Fi telemetry does
not determine contact health. GNSS has its own quality/age indication.

## Local dashboard API

Loopback listener only by default; no CORS. Mutations require JSON and same-origin
browser requests. These are Command-local controls, never a second phone protocol.

| Endpoint | Purpose |
| --- | --- |
| `GET /local/state?dot_interval_s=30` | Current recording, devices and projected geographic points |
| `POST /local/recording` | `{ "action": "start" / "stop" / "resume" / "clear", "confirmed": true, "mode": "from_now" / "session_beginning" }` (mode selected on Start; confirmation required for Clear) |
| `POST /local/override` | `{ "device_id": UUID, "reporting_interval_override_s": 30 / null }` |
| `POST /local/quality` | Complete quality snapshot using the field names in `GET /local/state` |

## Validation and boundaries

```sh
go test ./...
go vet ./...
go test -race ./...
CGO_ENABLED=0 GOOS=darwin GOARCH=arm64 go build -o /tmp/party-tracker-darwin ./cmd/party-tracker
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -o /tmp/party-tracker.exe ./cmd/party-tracker
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o /tmp/party-tracker-linux ./cmd/party-tracker
```

Tests use temporary SQLite files, restart them, and invoke the real phone simulator
against in-process HTTP listeners. Also run `go test ./...` and `go vet ./...` from
`test-tools` for frozen contract and simulator checks. No binaries belong in git.

Limitations: retained raw data, derived arrays and full evidence export favor correctness over
large-history performance; long-duration field capacity is not yet established.
The equirectangular view targets small local areas, not polar/global operation.
Cross-build success does not prove physical execution on macOS/Windows. The
[automated Android integration](../test-tools/integration/README.md) proves
application boundaries; [physical LAN acceptance](../android/DEVICE-ACCEPTANCE.md)
remains required. Offline maps, polished Issue #6 UI, installers/signing and
release workflows remain their later issues. Issue #5 SOS behavior is documented below.
The Issue #4 Protocol v1 amendment permits reporting intervals of 5–86400 s in
5-second steps (default 10 s); Android/Command responsibilities are unchanged.

## Issue #4 field evidence

Use **Export field-test report** on the local dashboard after testing (also
`GET /local/field-report`). It downloads one `gnss-field-report-<UTC>.zip` with
`manifest.json` and `field-report.json`; no external tooling is required. Export
is read-only, does not stop ingestion/recording, and is absent from the LAN phone
listener. Save this together with Android's **Export diagnostics** ZIP. No live
packet counting, stopwatch, SQLite inspection or failure-time ADB is required.

SQLite `raw` remains the unique-envelope truth; `field_evidence` adds transactionally
ordered metadata for stored/identical retry/conflicting attempts, offered ACK config,
pre/post live observation time, point count/distance, database-open boundaries,
configuration requests and recording/policy operations. Operation snapshots retain
recording IDs, windows/policies, accepted point identities/times/segments, participation
and distances before/after Clear. Resume remains the same recording with another
window. Closed windows can reconcile late backlog; operation snapshots describe
what was known at that operation, while current recording evidence is reconciled.
Raw rows are not duplicated; evidence persists with the same DB through restart.
Existing DBs migrate additively; old raw rows count, but historical retry/operation
metadata is unavailable before recorder installation (explicitly marked legacy).
Evidence is retained with the operational DB, without automatic deletion/rotation.
Back up the DB while stopped; save each exported ZIP. Very large-history export
capacity remains Issue #7.

Counters mean: **Reports received** = unique raw envelopes; **Raw fixes** = distinct
per-device `observed_at` timestamps, matching the engine's repeated-observation
identity (not proof of valid GNSS); **Useful points** = current recording's accepted
points before display-dot filtering. Clear removes current useful points, not reports,
raw fixes, devices, configuration or persisted operation evidence.

Cadence uses median of up to seven positive unique **current-like receipt** gaps,
with at least three gaps required. A capture within the configured clock tolerance
of receipt is current-like; older captures are delayed/backlog, future captures
beyond tolerance are clock-anomalous. The receipt-time classification/tolerance are
persisted so later quality-policy changes do not reinterpret prior evidence.
Config/effective-interval changes and long
receipt gaps reset the sample window. These classes assume reasonably correct phone
and laptop clocks; they are timing evidence, not proof of physical Wi-Fi state.
Duplicate retries and delayed packets never enter live cadence. Receipt chronology follows durable commit ordinals (including equal or backwards
wall times), with the original HTTP ingress UTC retained separately. Legacy rows
have no arrival ordinal and cannot certify reconnect ordering. Receipt-gap recovery
includes first capture/observation time and subsequent older backlog identities;
ongoing gaps retain the last unique receipt and threshold boundary. Duplicate
attempt chronology remains available to distinguish contact from unique-report gaps.
Command never claims an exact Android queue depth.

Assessments are conservative and include evidence/reasons: current-first PASS requires a current-like first capture **and a fresh quality-valid
GNSS observation at receipt**. Fresh status alone is INCONCLUSIVE for position;
delayed first capture is FAIL, clock ambiguity INCONCLUSIVE; backlog live-state checks require recorded before/after timestamps;
config convergence requires the exact authority/version/value echo plus three live
receipt gaps within ±30% of effective interval (otherwise FAIL with enough samples,
INCONCLUSIVE without them). ACK offered records generation, **not phone receipt**;
the later config echo proves adoption. Restart dedupe PASS requires a pre-open-boundary
stored identity retried identically afterward, original receipt preserved, and unchanged
point count/distance during that retry. Stop/Resume checks reconciled first-point
cumulative distance against pre-window geometry; insufficient geometry is INCONCLUSIVE.
Phone queue drain is PASS only for an explicit recent zero-pending report with contiguous durable identities; missing/stale reports are INCONCLUSIVE. Correlate Android's cached timeline for the full outage/drain sequence.
No automatic verdict certifies GNSS hardware, screen-off continuity or the physical LAN.

Exports omit coordinates/altitude, Party labels, endpoint/IP/SSID, credentials, raw
payloads and SQLite contents. Device/message/recording UUIDs are included deliberately
for cross-event correlation; treat bundles as field evidence, not public telemetry.
Build both assets from the same clean final product SHA: Android records full Git HEAD;
Command uses Go's `vcs.revision`/`vcs.modified` build metadata. Use `go build` inside the
Git checkout with VCS stamping enabled; unknown revision is reported honestly if absent.
A temporary workflow-only APK branch must check out the frozen **product SHA** for the
build, so its workflow commit does not become the APK's reported source revision.

## SOS alerts and human acknowledgement

SOS reception works with Recording stopped, active, resumed or cleared. The persistent
**SOS emergencies** panel identifies the Device/Party and specific event, original
activation time, first Command receipt, location and accuracy when supplied, GNSS
status and freshness at activation. Coordinates are an event observation, never a
promise of a current position. Missing, stale/last-known and clock-anomalous observations
are explicit. No SOS observation becomes a recording track point.

**Acknowledge this SOS** records the first human acknowledgement time durably for
that exact Device/event pair. It keeps the event and its first receipt timestamp;
repeated clicks/retries are idempotent. A duplicate phone packet cannot reset an
acknowledgement. Multiple independent events have separate controls/statuses.
Unacknowledged events remain prominent when a device loses contact, on browser
reload and after restarting Command with the same SQLite database. Recording Clear
retains all emergencies. There is no cancellation/delete control. Acknowledged
history remains visible and stored under existing indefinite raw/state retention.

The phone's transport ACK proves Command storage, not operator acknowledgement.
Command alone owns human acknowledgement. No additional phone return channel or
ACK-piggyback configuration field is added. The phone can confirm **Received by
Command**, and does not display a human acknowledgement it has not received.

Choose **Enable / test audible SOS alert**, then check speaker volume. Browser
permissions may block playback until a user gesture, after suspension or on reload;
the panel explicitly shows blocked/unavailable audio. When enabled, a short audible
cue repeats every ten seconds while an event is unacknowledged. Visible alerts
remain authoritative, including when the browser/network is unavailable. No internet
or external audio asset is used. Use keyboard Tab/Enter for each event's labelled
acknowledgement button; polling preserves that button and its captured identity.

### SOS field-report interpretation

The existing **Export field-test report** ZIP captures durable SOS receipt/duplicate/
conflict evidence, original activation/first receipt, human acknowledgement and
restoration after database opening. SOS-specific export evidence uses the same
bounded opaque 16-hex event reference as Android; no event coordinates, Party labels
or raw envelopes are exported. Existing ordinary tracking evidence retains its
previous identifier correlation contract. SOS-only devices use opaque aggregate
keys rather than installation UUIDs. Use the operational alert view to map an
event UUID to its exported reference (first 16 hex characters of SHA-256 of the UUID).

For each event, automatic assessments cover **SOS saved locally**, **SOS preempted
backlog**, **SOS delivered after reconnect**, **SOS retries deduplicated**, **Operator
ACK persisted**, and **SOS survived restart**. Command marks the first three
INCONCLUSIVE because they require Android evidence. Dedupe PASS requires retained
stored + identical duplicate transitions with unchanged first receipt; ACK-persisted
PASS requires the same human acknowledgement timestamp restored at a later database
open; survival PASS requires stored + restoration evidence. A new event with no
restart/retry data remains INCONCLUSIVE. Evidence is automatic: no packet counting,
queue observation or failure-time SQLite inspection is required. Legacy SOS raw
records are upgraded to alerts; missing legacy evidence is never fabricated.
### Field reliability evidence (schema 2)

Reports include per-type unique/delayed counts, last unique receipt, current
projection decisions/reasons and policy. Every receipt contains a SHA-256
`report_reference` of its message UUID, matching Android's opaque `reference` in
SNAPSHOT_SAVED / REPORT_SEND_ATTEMPT / REPORT_ACK_ACCEPTED / REPORT_RETRY events.
Source quality/freshness and the original receipt timing class/clock tolerance
are persisted at ingestion; exported projection decisions use the current policy.
Status and live-location cadence samples are separate; when recent live GNSS is present, health reports and SOS do not enter its gaps. During no-fix reporting the status cadence is used. The recent cadence becomes unavailable if current-like receipts stop beyond
`2*effective_interval+5` seconds; a previous estimate is not current evidence.
Status/SOS insertion cannot create track candidates, so routine ingestion preserves
the existing derivation without unnecessarily rebuilding all GNSS history. Location ingestion schedules projection work after raw commit; policy/lifecycle changes
invalidate projection state for reconstruction.

Android may now send intermediate native observations behind a current report.
Receipt exports flag `nonadvancing_history` when capture time does not advance the
latest current-like capture. Such history, including packets within clock tolerance,
does not enter live cadence/config convergence sampling. Per-type delayed-history
counts include these inferred historical packets; timing classification remains
unchanged. Negotiated senders also report an explicit HTTP delivery role; historical transfers
do not enter live cadence even when their source timestamps are recent. Legacy callers
remain timing-inferred; ambiguous cases cannot prove physical cadence. Original measurement times remain available; native history reconstructs in original per-session observation order.

Live freshness is bounded to 30 seconds (or a stricter configured age). Increasing
historical quality age cannot refresh an old marker or certify reconnect freshness.

Source validity and fresh-GNSS-at-receipt evidence are frozen in the ingestion
transaction. Later quality changes cannot manufacture a current-first PASS. Older
DB receipts without this metadata report `unavailable_at_receipt` and remain
INCONCLUSIVE for that assertion; current projection decisions are exported separately.

## Final reliability controls

Every quality value also has an `enabled` switch (missing switches in older state mean
ON). Disabled forward/backward floors contribute zero, independently of uncertainty.
Live freshness remains mandatory and maximum-accuracy enablement controls its filter.
The default-OFF Use All Observations view is Unfiltered / Diagnostic and leaves qualified
distance/settings unchanged. History dots/lines are subdued; the live marker is distinct.

Start Recording accepts `mode: "from_now"` (default) or `"session_beginning"`.
The latter requires reported durable Android session metadata. Stop/Resume retain the
same recording with separate windows; Clear retains raw/config/device state. There are
no inferred recording connectors across windows or Android sessions.

The phone listener adds GET /api/v1/capabilities and POST /api/v1/history. Batch version 1
is atomic and durably ACKed; malformed/conflicting entries identify an index/observation.
The local dashboard reports contiguous received/processed prefixes and separately labeled,
timestamped phone queue state. A permanent protocol rejection is unresolved, not received;
a GNSS quality rejection is stored history. Exports include these distinctions and projection
state without copying coordinates, raw envelopes, databases or secrets.


Derived arrays live in the existing SQLite projection cache (`projection_state`),
separate from the frequently updated device/receipt state. This is an additive
migration of the old state JSON; raw/wire, configuration, lifecycle and evidence are
retained. ACK commits update only lightweight receipt state and durable projection
work. Ordinary worker updates read new arrival rows for affected devices and resume
accepted-anchor checkpoints; late history reprocesses the dependent suffix. Work
has per-device generations so one busy phone cannot invalidate every other phone.
Caches regenerate from raw after restart. Dashboard live snapshots never wait for
an in-flight reconstruction; pending/error remains visible.

History exports contain each identified Android session. `received_through` is an
actual contiguous identity prefix. `processed_through` additionally admits explicit
unresolved phone outcomes, never asserting receipt of those identities. Queue data
is a timestamped last report; disconnected/stale phone state is unknown. History
synchronization PASS requires a recent zero-pending report, no known collection loss,
contiguous receipt and completed projection. Projection-update evidence records
counts/distances after derivation; raw ACK evidence explicitly labels pending work.

Beginning-of-session recording binds known phones to their reported current source
session; a late joining phone binds the session reported by its first fresh live
observation or current queue progress while the window is active. Resume binds currently reported sessions in a new window at Resume time.
From-now accepts only observations measured at/after the operator boundary. Source
sessions never share an accepted movement connector. Diagnostic raw lines also keep
source-session boundaries. Provisional distance is section-local, starts at zero,
and is never added to authoritative totals. All display layers share one origin.

Historical ordering is the original per-session GNSS sequence, with session-start/ID
ordering between sessions; queued legacy history uses observed time and precedes
session-aware collection. Independent live ordering always uses observed time so
old measurements cannot move the current marker backward. Clock anomalies remain
explainable, and no automatic correction is performed.

Live freshness also requires capture-time fix age within 30 seconds and rejects measurements/captures more than five seconds in the future (a stricter enabled clock tolerance still applies). Disabling or enlarging historical age/clock rules cannot relax these current-position/evidence bounds.

### Short synthetic acceptance profile

The combined PR #12 five-device test retains 600 original one-second observations per
phone (3,000 total), sends current first, retries stored batches, interleaves 30
live retries and raises five no-fix SOS requests while recovery runs. On a cloud
workspace limited to two CPUs, alongside Android validation, without race instrumentation: mean envelope 980 B;
155 history requests; 3,041,865 B history bodies; 13,795 B request headers;
975,940 B ACK bodies; 14,725 B response headers. Catch-up ingestion took 42.13 s,
maximum live durable ACK 736 ms, maximum SOS durable ACK 221 ms, final projection
flush 1.72 s. SQLite allocation increased 23,498,752 B, including raw, indexes,
projection and evidence; it is not network traffic. Total heap allocation was
2,741,584,568 B, with 26,758,424 B retained at measurement. This short profile
exercises increasing recording size; it establishes neither battery consumption
nor the Issue #7 high-volume/endurance capacity. Run
`go test -v ./internal/core -run TestFiveDeviceShortBatchPerformance` to measure
on the intended laptop. Native one-second retention increases phone storage/IO
relative to interval-only snapshots; no automatic raw-data deletion is enabled.
