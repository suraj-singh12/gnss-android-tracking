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

`internal/core/protocol.go` validates frozen Protocol v1, including required nullable
fields, duplicate keys, actual integers, ranges and additive-field compatibility.
Exact wire-key selection precedes typed decoding, so case-variant additive fields
cannot shadow known fields. Canonical known content defines retry equality, including numeric spelling
normalization; original wire bytes are retained separately. SQLite uniqueness covers
both `(device_id,message_id)` and `(device_id,sequence)`. WAL + `synchronous=FULL`
commits original raw content, identity, first receipt, device/contact state and derived
results before ACK. Conflicts never overwrite. Duplicate ACKs return the original
receipt and current desired config; retries update last contact but add no track edge.

SQLite contains immutable `raw` rows, one transactional persisted `state` snapshot
(devices, desired configuration, policy, current lifecycle, geographic points and
candidate decisions), and retired recording windows. State reads and mutations are
serialized. Track derivation is a separate pure deterministic function. On new data
or lifecycle changes the current recording is rebuilt and replaced in the same
transaction. This simple first implementation rebuilds the entire current recording,
including closed windows; no arrival-order append or distance corrections exist.
A future indexed per-device/window rebuild can preserve exactly these semantics.

Live measurement ordering uses `(observed_at,sequence,message_id)`; health, labels
and config echoes use `(captured_at,sequence,message_id)`. Status without a fix
preserves the location. A newer poor/stale measurement is labelled honestly as such.
Live validity is assessed independently of recording windows; implausible latest
measurements are marked as such. Location age includes elapsed wall time and capture age; backlog is never fresh just
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
Changing quality while active closes the window
at Command time and opens a new `policy_change` window with a new persisted revision.
Historical windows always retain their original settings. Decisions record rejection
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
Only location messages enter tracks; SOS fixes remain telemetry for future Issue #5.

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
| `POST /local/recording` | `{ "action": "start" / "stop" / "resume" / "clear", "confirmed": true }` (confirmation required for Clear) |
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

Limitations: whole-recording rebuild and a JSON state snapshot favor simplicity over
large-history performance; long-duration field capacity is not yet established.
The equirectangular view targets small local areas, not polar/global operation.
Cross-build success does not prove physical execution on macOS/Windows. The
[automated Android integration](../test-tools/integration/README.md) proves
application boundaries; [physical LAN acceptance](../android/DEVICE-ACCEPTANCE.md)
remains required. SOS alerts/operator acknowledgement, offline maps, polished
Issue #6 UI, installers/signing and release workflows remain their later issues.
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

Assessments are conservative and include evidence/reasons: current-first PASS/FAIL
compares first post-gap capture age to clock tolerance (future ambiguity is
INCONCLUSIVE); backlog live-state checks require recorded before/after timestamps;
config convergence requires the exact authority/version/value echo plus three live
receipt gaps within ±30% of effective interval (otherwise FAIL with enough samples,
INCONCLUSIVE without them). ACK offered records generation, **not phone receipt**;
the later config echo proves adoption. Restart dedupe PASS requires a pre-open-boundary
stored identity retried identically afterward, original receipt preserved, and unchanged
point count/distance during that retry. Stop/Resume checks reconciled first-point
cumulative distance against pre-window geometry; insufficient geometry is INCONCLUSIVE.
Phone pending=0 is always INCONCLUSIVE in Command alone; correlate Android's cache.
No automatic verdict certifies GNSS hardware, screen-off continuity or the physical LAN.

Exports omit coordinates/altitude, Party labels, endpoint/IP/SSID, credentials, raw
payloads and SQLite contents. Device/message/recording UUIDs are included deliberately
for cross-event correlation; treat bundles as field evidence, not public telemetry.
Build both assets from the same clean final product SHA: Android records full Git HEAD;
Command uses Go's `vcs.revision`/`vcs.modified` build metadata. Use `go build` inside the
Git checkout with VCS stamping enabled; unknown revision is reported honestly if absent.
A temporary workflow-only APK branch must check out the frozen **product SHA** for the
build, so its workflow commit does not become the APK's reported source revision.
