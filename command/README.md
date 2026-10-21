# Command

One Go application for macOS, Windows and Linux: LAN Protocol v1 receiver,
local SQLite, deterministic recording/track engine, and embedded offline dashboard.
Field operators need only the executable and a browser already on their laptop.
No Node, Python, Docker, external WebAssembly runtime or database service is needed.

## Custom-radius maps (Issue #28)

In **Map & layers → Download Map**, choose a centre by exact WGS84 coordinates,
existing place search, or **Pick centre on current map**. Enter any finite positive
radius in metres or kilometres; the value is preserved. The old rectangle controls
remain an explicitly selected compatibility option. Preview is local and displays
an ellipsoidal WGS84 geodesic circle, centre, radius, conservative geographic/source
envelope, source tile count and resource bounds. The circle is not a Mercator pixel
circle. Longitude wraps at the antimeridian. A request reaching the poles or outside
Web Mercator coverage receives a specific refusal, without truncation.

Review and explicitly confirm multi-chunk/source costs, then Download. Preparation
sends geographic chunk bounds to Overpass (OSM ways only) and, if selected, uses the
existing Skadi/HGT adapter. A valid empty OSM response is successfully acquired sparse
coverage; missing `elements`, incomplete remarks, malformed geometry, timeouts and
HTTP errors are failures. No alternative provider is silently contacted. Public
providers have no SLA. HTTP 429/503 Retry-After extends the local cooldown; explicit
retry is required. Skadi retains the existing bounded three-attempt transient retry,
CRC/size checks and network/processing deadlines. Provider outages do not erase assets.

The existing SQLite `offline_maps` row owns the request. Additive
`offline_map_chunks` child rows contain validated GeoJSON and unchanged GTERR001
native crops; legacy `offline_terrain` maps/imports remain supported. Each 240-arc-second
chunk plus sample halo fits the existing 257² decoder. Regions may use more than four
source tiles; the four-tile bound applies only to an individual halo crop. Cells are
never upsampled. Source bounds, EGM96 datum, 1-arc-second native spacing, attribution,
hashes and actual transfer attempts remain in each saved DEM's metadata/evidence.
The horizontal CRS is WGS84; source resolution/accuracy varies and is not survey grade.
Retain OSM ODbL and the complete Skadi multi-source notice when redistributing.

Budgets are explicit: 2,048 plan chunks, 256 MiB total chunk assets, 20 MiB/100,000
coordinates per vector chunk, 257² samples per terrain crop, four verified full HGT
source files (about 99 MiB) in `<database>.map-cache`, and a 128 MiB free-disk reserve
plus 40 MiB working allowance before each acquisition. A large envelope exceeding
plan/DEM storage bounds is refused with the actual chunk count; there is no preset
radius substitution. Source transfer sizes are unknown until observed; the preview
shows per-chunk source-request upper bounds (including possible cache eviction), not invented sizes or ETAs. The disposable cache validates
SHA-256 and HGT byte count before reuse; validated map products remain in SQLite.
Keep the SQLite DB/backups private and on a writable local filesystem. Removing the
disposable source cache while Command is stopped cannot remove saved map products.

One network/decode/save task runs at a time. Network and raster work hold no receiver
mutex. Each SQLite asset/evidence update is a short atomic transaction with a five-second
context. Cancel aborts the active request; completed chunks remain usable. **Saved Maps
→ Acquire or import terrain → Resume missing map / terrain chunks** retries missing
work, including after restart or lost responses. Identical centre/radius requests
resolve to one deterministic saved-map identity. Selecting additional terrain products
updates that entry; validated native DEM samples are reused without provider calls. Completed chunks are immutable on
retry. An interrupted request is not labelled complete; a failed replacement cannot
replace earlier validated assets. A physical full disk may prevent even error evidence
from being saved; existing SQLite transactions retain earlier data.

**Save source & coverage manifest** exposes requested geometry separately from actual
vector/DEM chunk envelopes, errors, attempts, timestamps, bytes, source metadata and
valid/void cell counts. Cell counts include sampling halos and are not percentages
of the circle. No geographic percentage is invented from sparse OSM features or DEM
samples. Unsupported DEM latitude chunks remain explicit gaps while vectors operate.
Contours/hillshade require valid cell neighborhoods and successful browser processing;
the Layers controls and rendering warnings report their availability independently.
Source acquired does not prove that a product rendered successfully.

Offline reopening reads only stored assets. The same geographic renderer positions
vectors, DEM, contours, hillshade and original GNSS/SOS. Coverage outlines show the
requested circle and acquired/missing chunk envelopes. Adjacent visible DEM chunks
share contour levels and clip sampling halos at their authoritative core bounds.
Void neighborhoods stay transparent. At most eight visible chunks and 100,000 vector
coordinates load into the browser; zoom in when the visible envelope exceeds this
budget. This bounds display work without claiming missing overview detail is acquired
terrain. Pan/zoom loads other stored chunks locally. Each raw chunk remains accessible
through `/local/maps/{id}/chunks/{key}?layer=vector` or `layer=terrain`; adding
`format=gterrain` to terrain exports the existing binary format. The single-file
GeoJSON/terrain export controls remain for legacy single-grid maps; radius maps use
the source manifest and these native chunk downloads. No recording export package
is created here.

Issue #28 acceptance evidence is recorded in `docs/ui-acceptance.md`. Linux cross-builds
and synthetic browser surfaces are not native macOS/Windows or real-provider acceptance.


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

| Setting                                       | Default   |
| --------------------------------------------- | --------- |
| Minimum forward / backward movement floors    | 2 m / 2 m |
| Maximum horizontal accuracy                   | 25 m      |
| Maximum fix age at capture                    | 30 s      |
| UTC versus monotonic capture-age tolerance    | 5 s       |
| Maximum uncertainty-adjusted horizontal speed | 12 m/s    |
| Maximum time gap from last accepted point     | 120 s     |
| Uncertainty multiplier                        | 1         |

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
deltas, no altitude component and no edge rounding. The backend retains its original local metric projection for derived views. The
Issue #6 renderer projects original WGS84 coordinates to Web Mercator for both blank
and offline GeoJSON modes, north up. Pan/zoom persists through polling; Fit/Focus
are explicit actions. Projection never influences acceptance or travelled distance.
See [map format and alignment inspection](../docs/design-system.md).

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

### Appearance, header, About and recording

Day/Night persists per browser. The header's **Command Connected/Disconnected** is
browser-to-local-server contact; **connected/total Parties** uses existing Android
contact policy. About identifies **Lt Suraj Singh**, **surajsingh5092@gmail.com**;
opening it never silences outstanding SOS. Start opens **Start Recording from**,
default **Current Time** on every opening. Confirm uses `from_now` or
`from_beginning`; Cancel/Escape is a no-op. From Beginning selects all retained
source sessions and works before any phone has connected; it does **not** require
`recording_session_available`. Server validation/errors remain authoritative.
Stop/Resume/Clear and recording distance calculation retain their established semantics.

### Automatic terrain preparation and provider boundary

The current automatic provider is [AWS Open Data Terrain Tiles](https://registry.opendata.aws/terrain-tiles/)
(Mapzen/Tilezen), not Copernicus/OpenTopography. Public unsigned HTTPS GET requires
no AWS account, API key or paid subscription. Endpoint:
`https://elevation-tiles-prod.s3.amazonaws.com/skadi/{N|S}{lat}/{N|S}{lat}{E|W}{lon}.hgt.gz`.
This is tile-bounded acquisition: Command obtains only intersecting 1° tiles plus a
one-sample processing border (maximum four), then reuses the HGT crop/renderer.
The provider has no documented app-specific quota or availability SLA. HTTP
403/404/429/5xx, missing tiles and outages remain possible; there is no background
poller. Each tile has at most three attempts for transient transport failures and
HTTP 408/429/500/502/503/504. Backoff is 1 then 2 seconds; longer `Retry-After` up to
30 seconds is respected. Longer/invalid cooldowns stop automatic retries. Permanent
403/404/401/400, coverage, invalid gzip/CRC and invalid elevation errors are not
retried. TCP/TLS budgets are 10 seconds each, response headers 20 seconds, a complete
tile attempt 120 seconds, and the overall acquisition six minutes. In-memory
decoding/cropping has a ten-second processing limit, SQLite save five seconds.
The extended response deadline applies only to terrain downloads; OSM, ingestion,
GNSS, SOS and recording deadlines are unchanged. Cancel aborts the actual request.
Compressed input is capped at 26 MiB and decoded input at 25,934,402 bytes; gzip CRC
is verified after a complete transfer, independently of network error reporting.

### Map & Layers workflow and download evidence

**Current Map** shows selection/layers and offers Blank Canvas. Display-dot interval,
Use All Observations, opacity, CRS/coverage and attribution remain in **Display &
geographic details**. **Download Map** follows Location → Area → Terrain → Preview →
Download: place search or exact centre coordinates, 500 m / 1 km / custom dimensions,
three optional terrain components, bounded OSM preview, then one save action.
**Saved Maps** lists selection, coverage and downloaded components. Import/manage
retains GeoJSON import/export/delete; terrain tools retain online retry, local HGT
or .gterrain import, preparation and export. Toolbar **Layers** changes visibility
only and shows not downloaded / downloaded hidden / downloaded visible, offline.

Download progress reports connecting, actual received bytes/time, processing,
saving and ready (no guessed percentage). Vectors save before terrain. Failures and
Cancel retain existing vectors/terrain and never create duplicate vectors on retry.
A complete validated acquisition that fails to save is kept in memory for up to
15 minutes, so a storage retry does not repeat it. Rendering retries reuse the
saved grid; quitting Command clears the unsaved bounded cache.

Open **Download diagnostics** to Copy diagnostics or Export download report without
developer tools. Reports include source revision, provider/dataset, centre/bounds/
metre dimensions, components, each attempt, observable connection/first-byte timing,
status/bytes, transfer, gzip decoding, terrain processing, SQLite save, elapsed time,
failure stage and outcome. `null` means unavailable, not zero. Browser generation
time/warnings are appended to the on-screen export when measured. The latest 20
backend reports persist in the existing field-evidence journal, including Cancel;
reopening a saved map restores its latest report. Existing diagnostics export also
includes these events. No authorization headers, keys or provider response bodies
are recorded. Connection time includes connection-pool/proxy/TLS establishment
where observable, not an asserted physical link measurement.

Mac field verification: enter **27.3739, 88.7618**, select **1 × 1 km** and all three
terrain options, Preview then Download. If it fails, export that map's download
report, including the attempt/stage timings. The previously verified defect was
misclassification of network read deadlines as corrupt gzip; the operator's slow
network/provider cause remains unverified. Codex Cloud provider CONNECT HTTP 403
is a separate access-policy blocker, not reproduction of the field timeout.

[Skadi format](https://github.com/tilezen/joerd/blob/master/docs/formats.md):
WGS84/EPSG:4326, EGM96 integer metre heights, north-first rows, big-endian int16,
−32768 voids, gzip-compressed 3601² grid. Native 1 arc-second spacing is ~31 m N/S
(E/W narrows with latitude); [composite source resolution/accuracy varies](https://github.com/tilezen/joerd/blob/master/docs/data-sources.md).
Grid spacing is not surveyed accuracy or uniform bare-earth detail. No voids are
fabricated; an adjacent valid seam sample at the same geographic node may be used.
Automatic coverage is conservatively limited to 56°S–60°N. Legacy rectangle requests do not cross the antimeridian; custom-radius requests split it into geographic chunks.
Cross-tile stitching is supported automatically, not by manual multi-file upload.
A 500 m area still downloads full source tiles: the verified Mussoorie tile is
18,131,689 compressed bytes / 25,934,402 decoded bytes. Full tiles are not persisted.

The composite is **not** labelled wholly public domain. Its complete multi-source
[attribution/terms notice](https://github.com/tilezen/joerd/blob/master/docs/attribution.md),
provider, UTC acquisition timestamp, exact tile URLs and decoded SHA-256 hashes
are retained in terrain metadata/export and shown in Layers. Retain these notices
when redistributing. No EGM2008 conversion, mandatory credentials or paid dependency.

For the legacy rectangle option, select a location/coordinate **centre**, default 500×500 m, choose optional terrain,
Preview, then Download for Offline Use. Vectors commit first, independently of DEM
success. A terrain failure warns and retains vectors and any previous terrain.
Select that saved map, open **Acquire or import terrain for this saved map**, select
components and press **Download / retry selected terrain**; no duplicate vector map
is created. No manual HGT is needed. Unchecked terrain makes no DEM request.
Local HGT/`.gterrain` import remains available for legacy single-grid maps. Loopback-only same-origin JSON
`POST /local/maps/{id}/terrain-download` takes `components`; bounds come from the
saved map. Network I/O never holds the Store lock. Storage/schema/GTERR001 remain
compatible; optional provenance metadata is additive. The previous format limits
and independent OFF/ON layer behavior below are unchanged.

`dem-offline-acceptance.cjs` uses actual OSM and Skadi acquisition through the UI,
compares every saved node with independently decoded raw HGT, verifies rendered
hillshade pixel centres, contour geographic coordinates and pointer elevations,
then restarts Command with a dead provider proxy and blocks browser external requests.
A failed offline retry must retain byte-identical terrain. JSON/screenshots are
separate real-provider evidence, not fixture or survey/physical field acceptance.

#### Historical PR #18 provider investigation (superseded by Skadi above)

[Copernicus GLO-30 public](https://registry.opendata.aws/copernicus-dem/) supplies
30 m Cloud Optimized GeoTIFF tiles, no AWS account, under its
[licence](https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM).
It is a surface model (including buildings/vegetation), EGM2008; public coverage has
exclusions. The bucket is not this app's small-area extraction API. OpenTopography
is a subset-service candidate, but eligibility, authentication/quotas/payment were
not verified. Actual endpoint requests here failed **proxy CONNECT HTTP 403**.
**At that baseline, automatic DEM acquisition was not implemented**. No credential, paid
service, unsupported COG decoder or terrain data was fabricated. CI separately
records reachability; a HEAD response is not successful DEM extraction.

The [official Copernicus licence/citation guidance](https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM)
requires source notices for distribution and adapted products; it is not public
domain. A future GLO-30 adapter must retain that notice and EGM2008 metadata rather
than relabelling heights as the EGM96 supported by the current HGT path.

Supported fallback: operator-supplied **uncompressed SRTM HGT**, square 1201×1201
(3 arc sec, ~93 m north–south) or 3601×3601 (1 arc sec, ~31 m) signed int16 big-endian
metres. Filename `N28E077.hgt` is the southwest tile corner; first row is north.
WGS84 / EGM96; −32768 marks void. See [USGS specifications](https://www.usgs.gov/centers/eros/science/usgs-eros-archive-digital-elevation-shuttle-radar-topography-mission-srtm).
Obtain licensed real data separately through supported provider access; imported
provenance is operator-supplied, not authenticated. Heights estimate radar terrain/
surface, not receiver GNSS altitude or surveyed ground. Compressed/rectangular HGT,
cross-tile mosaics, GeoTIFF/DTED/NetCDF and arbitrary images are unsupported.
Extremely complex contours stop at a bounded segment limit with an explicit disabled
Contours control; the saved DEM, hillshade/elevation and export remain available.
Use a smaller map area rather than treating omitted contours as surveyed evidence.
Retain the original SRTM dataset citation and distributor terms when sharing a map.
[USGS copyright policy](https://www.usgs.gov/information-policies-and-instructions/copyrights-and-credits)
permits free use of USGS-produced public-domain data but does not waive rights in
third-party material; the importer cannot certify an arbitrary supplied file's licence.

Download an offline map still defaults to centre-based **500×500 m** OSM vectors,
optional 1 km or 100–2000 m custom bounds. Preview displays W/S/E/N requested bounds;
whole intersecting ways may extend outside. Existing natural/land-use features remain.
At the PR #18 baseline, optional terrain used one covering local HGT file. The
automatic path now described above requires no file. Local import remains under
**Acquire or import terrain for this saved map**. Validation/storage failures retain saved vectors
and earlier terrain. After a lost response, reopen the saved map to inspect its
persisted result; a network error cannot promise that a committed save was rolled back.

One cropped grid with one sample border is stored per map in additive
`offline_terrain`, in the **existing SQLite DB**. Old IDs/vector bytes and all tracking
data remain. Full tiles are not retained. Limits: one serialized preparation, HGT
25,934,402 bytes, JSON body 36 MiB, grid 257×257, metadata 8 KiB, package 1 MiB,
terrain library 16 MiB; existing vector limits 64 maps/256 MiB. Delete selected map
removes its terrain, not GNSS/recordings/SOS.

**Layers**, beside Focus selected, distinguishes unavailable/disabled, prepared OFF
and visible ON. Preparation resets visibility OFF; preferences persist per map/browser.
Toggling uses local data only, preserving view/selection. Stacking: Horn 3×3 NW/45°
hillshade; vectors; marching-square contours; history/provisional tracks; live markers;
SOS/controls. Voids remain transparent. Contour intervals follow relief/source spacing
with 1/2/5 rounding (minimum 5 m at 1 arc sec, 10 m at 3); at most 16,384 segments.
Contours are interpolated, not survey-grade. Elevation ON gives rounded metre
estimates at pointer/point inspection, with datum/spacing and unavailable/coverage
status. Bilinear interpolation does not fill nodata. No DEM affects GNSS or distance.

GeoJSON export remains unchanged, **excluding terrain**. **Save terrain file** exports
`.gterrain`: `GTERR001`, big-endian uint32 JSON metadata length, metadata without
raster values, then big-endian int16 samples (−32768 void). Re-import for a saved map
within that coverage; component flags/georeference/spacing/source/datum survive.
Visibility is separate. Export both files for portability. Raster is not GeoJSON.

Loopback-only local controls: `GET /local/maps/{id}/terrain`,
`GET /local/maps/{id}/terrain-file`, `POST /local/maps/{id}/terrain` (filename,
base64 data, selected components), `POST /local/maps/{id}/delete`. Same-origin JSON,
bounds/storage validation apply. None is available on phone ingestion.

Offline acceptance: prepare genuine DEM, stop Command, disconnect internet, restart
the same DB, select/toggle layers, inspect known coordinates in both themes. Fixtures
prove processing/storage/alignment contracts, **not provider extraction or surveyed
alignment**. Existing OSM provider availability remains an external dependency.

Loopback listener only by default; no CORS. Mutations require JSON and same-origin
browser requests. These are Command-local controls, never a second phone protocol.

| Endpoint                             | Purpose                                                                                                                                                                      |
| ------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET /local/state?dot_interval_s=30` | Current recording, devices and projected geographic points                                                                                                                   |
| `POST /local/recording`              | `{ "action": "start" / "stop" / "resume" / "clear", "confirmed": true, "mode": "from_now" / "from_beginning" }` (mode selected on Start; confirmation required for Clear) |
| `POST /local/override`               | `{ "device_id": UUID, "reporting_interval_override_s": 30 / null }`                                                                                                          |
| `POST /local/quality`                | Complete quality snapshot using the field names in `GET /local/state`                                                                                                        |

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
The Web Mercator display targets local areas; polar coordinates are clamped and
antimeridian-spanning offline datasets require splitting before import.
Cross-build success does not prove physical execution on macOS/Windows. The
[automated Android integration](../test-tools/integration/README.md) proves
application boundaries; [physical LAN acceptance](../android/DEVICE-ACCEPTANCE.md)
remains required. Issue #6 supplies the workspace and offline GeoJSON maps.
Installers/production signing and release workflows remain separate. Issue #5 SOS behavior is documented below.
The Issue #4 Protocol v1 amendment permits reporting intervals of 5–86400 s in
5-second steps (default 10 s); Android/Command responsibilities are unchanged.

## Issue #4 field evidence

Use **Export diagnostics** on the local dashboard after testing (also
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
**SOS emergencies** drawer (header SOS counter) identifies the Device/Party and specific event, original
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
cue repeats every **one second** from one shared scheduler while any event is
unacknowledged. Multiple events do not create overlapping loops. Dismissing the
arrival notice, closing a drawer, filtering history and acknowledging only some
events do not silence remaining emergencies. The final durable operator ACK stops
the tone/timer immediately after the updated state is received. Refresh/restart
restores the pending condition from Command, but browser audio may need enabling again. Visible alerts
remain authoritative, including when the browser/network is unavailable. No internet
or external audio asset is used. Use keyboard Tab/Enter for each event's labelled
acknowledgement button; polling preserves that button and its captured identity.

**Events** opens a separate history drawer containing all retained SOS events,
including acknowledged events, and the latest 500 entries of the existing operation/
receipt journal. Expand an event for original occurrence, first receipt and operator
ACK details; full retained diagnostic evidence remains available through export.
**Locate event observation** focuses the event coordinates, not an inferred live
location. Arrival-notice dismissal never acknowledges an event.

### Offline map preparation and library

Map & layers offers exactly Blank canvas and Offline geographic map. Import
validated RFC 7946 WGS84 GeoJSON (20 MB / 100,000 coordinates), or expand **Download
an offline map** while online: search a place with Nominatim or enter a WGS84 center,
choose 500 × 500 m (default), 1 × 1 km, or bounded custom 100–2000 m dimensions,
Preview features/size, then **Download for Offline Use**. The preview fetches OSM
ways from Overpass, not tiles: roads/paths, buildings, waterways, natural features
and land use where available. Whole intersecting ways may extend beyond the
requested rectangle. Multipolygon relations and raster/GeoTIFF are explicitly
unsupported; use robust GeoJSON import for those vector features. Attribution is
retained; OSM data is © OpenStreetMap contributors, ODbL. Search is user-triggered,
limited to five results and at least one second between requests. Area preparation
has a ten-second cooldown, a 25-second provider query timeout and 20 MB response cap.
Provider failures leave saved maps intact. No public tile bulk download is used.

Imported/downloaded maps share an additive `offline_maps` table in the existing
Command SQLite file (64 maps / 256 MB maximum, no automatic deletion). Saved maps
can be selected and exported as GeoJSON, survive Command restart and need no
internet to display. Browser selection/mode preferences are presentation-only.
The same GeoMap WGS84/Web Mercator renderer serves both modes; map operations do
not modify observations, live/SOS coordinates, qualified distances or Recording.
Blank canvas remains available on invalid/missing maps. Direction arrows use only
fresh credible existing bearing/speed (≥0.5 m/s); stationary/unknown/stale fixes
remain neutral markers, never history-derived headings.

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

Start Recording accepts `mode: "from_now"` (default) or `"from_beginning"`.
From Beginning has no lower observation-time boundary for its initial window,
includes all retained received observations across every Android tracking session,
and can begin with zero known phones. The old API value `"session_beginning"`
remains an alias. From Now uses the persisted Command operator-time boundary and
excludes earlier measurements regardless of when they arrive. Stop closes the
active window, while delayed qualifying history can still be received and rebuilt
inside that window. Resume creates a separate time-bounded segment of the same
recording. Clear retains original raw GNSS, device identities and configuration.
A missing sequence or session gap never creates an invented distance connector.

The phone listener adds GET /api/v1/capabilities and POST /api/v1/history. Batch version 1
is atomic and durably ACKed; malformed/conflicting entries identify an index/observation.
The local dashboard reports contiguous received/processed prefixes and separately labeled,
timestamped phone queue state. A permanent protocol rejection is unresolved, not received;
a GNSS quality rejection is stored history. Exports include these distinctions and projection
state without copying coordinates, raw envelopes, databases or secrets.

Derived arrays remain cached in the same authoritative SQLite database. Issue #22's
versioned per-device chunks, bounded streaming reconstruction and independent LIVE/SOS
presentation are detailed below. Raw identities, recording/quality policy and durable
receipt remain independent of cache publication. Pending/error cannot certify
completion, and unknown phone inventory stays unknown.

History exports contain each identified Android session. `received_through` is an
actual contiguous identity prefix. `processed_through` additionally admits explicit
unresolved phone outcomes, never asserting receipt of those identities. Queue data
is a timestamped last report; disconnected/stale phone state is unknown. History
synchronization PASS requires a recent zero-pending report, no known collection loss,
contiguous receipt and completed projection. Projection-update evidence records
counts/distances after derivation; raw ACK evidence explicitly labels pending work.

Historical-only late joins and earlier Android sessions become eligible for From
Beginning as their original observations arrive. No current-session inventory or
fresh live fix is needed. The first From Beginning window accepts the full retained
observation history (before its Stop boundary); Resume always starts at the new
Command-clock boundary. Source sessions, missing original sequences and recording
windows cannot share a distance connector. Successful qualified partial route sections
appear even beyond unresolved sequence gaps and are reconciled on later delivery.
Unfiltered / Diagnostic shows all received observations *inside the recording
boundaries*, independent of GNSS-quality acceptance. The dashboard requests raw
geometry only when that diagnostic layer is enabled, retaining the same map/terrain
renderer and geographic origin. No historical backlog becomes a fresh live marker.



### Correct historical reconstruction and acceptance

The receiver first persists each unique valid GNSS observation and its original
measurement/session/sequence in the existing SQLite raw store, then commits a durable
ACK. Reconstructing the recording and displaying its geographic points are separate
steps. The per-device dashboard distinguishes durable GNSS receipt, eligibility,
recording-boundary exclusions, projection pending/failed/complete, accepted and
rejected fixes, known missing identities, qualified route sections and drawing count.
A recent empty Android queue is not proof of complete historical reconstruction.

The tested October 2026 Android/Command field report used the **old**
`session_beginning` semantics; it received multi-session backlog but excluded five
later sessions of a phone from its active recording. This repair explicitly selects
all retained sessions for the first From Beginning window. The modern Issue #6
map-first UI, SOS, Day/Night, saved map library, automatic DEM, terrain layers,
downloader and associated tests remain preserved.

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

## Issue #6 operational interface

See [UI setup and acceptance](../docs/ui-acceptance.md) and [shared design system](../docs/design-system.md) for the three-destination Android interface, Command workspace, recording/quality controls, protected SOS and exactly two offline canvas/map modes. The existing tracking and SOS engines remain authoritative.

## Issue #22 bounded history receipt and presentation

The default capability remains 65536 bytes/128 historical records. The bounded
`-history-batch-bytes` option permits the measured candidates 65536, 131072, 262144,
524288, 1048576 or 2097152; the record ceiling is max(128, bytes/512), capped at 4096.
The default was retained because larger requests did not establish a sustained fleet
throughput benefit and increased urgent-request exposure. Do not select the largest
candidate merely because it reduces request count. Older v1 clients remain valid.

Two HTTP history admission slots bound decoded request RAM independently of device
count. One historical waiter reaches the SQLite writer, allowing LIVE/SOS to contend
with one bounded history transaction instead of an entire fleet. Concurrent requests
for the same device are rejected with transient 429; there is no 20-device maximum.
Raw/identity/receipt/work still commit atomically under SQLite FULL before ACK.
Disk-full never earns an ACK. Long-reader WAL pressure above a 128 MiB budget attempts
a nonwaiting checkpoint; history-only 429 backpressure leaves urgent envelopes
eligible. `journal_size_limit=64 MiB` controls reuse after reset, not a hard bound
against external readers or a guarantee of available disk space.

`projection_state` now publishes generation pointers into the existing derived
cache's `projection_device_chunks`. Each value contains at most 512 points and
512 decisions; ordinal -1 is its bounded presentation preview. Ordinary updates
replace only the affected device, stage chunks in short transactions, then atomically
publish the pointer and metadata. Interrupted staging cannot replace the prior good
snapshot. Cleanup uses the *current* published pointers and bounded deletions.
Missing chunk manifests queue reconstruction from unchanged raw history on reopen.
Legacy monolithic/ordered-chunk caches migrate by streaming values before pointer
publication; the old cache stays published until that boundary. Older Command
binaries cannot interpret the new derived format: back up while stopped and rebuild
from raw with a supported version; binary downgrade is not certified.

The accepted-anchor `Derivation.Feed`, canonical chronology, quality policies,
recording windows and distances are unchanged. Small histories retain the existing
incremental checkpoints with an aggregate limit of 100000 cached candidates or 128 MiB
of encoded known envelopes. A device switches to streaming at 64 MiB of known
envelopes, retaining that decision while the process lives. These source-byte budgets
bound caching; decoded Go/SQLite overhead is additional and measured separately.
Above 20000 raw observations/device (scaled down by recording-window count),
reconstruction uses indexed keyset pages and the same derivation,
not full-history arrays. Readers close between pages. Consistent captured prefixes
can publish during continuous ingestion, while newer work remains explicitly pending.
A durable logical last-served counter gives queued devices fair projection turns,
independent of source/receiver clock jumps. A background turn handles one device; explicit inspection boundaries drain a finite
captured inventory. The expression index is checked against the canonical comparator.

Production dashboard reads use bounded query-only connections and deadlines.
`GET /local/live` independently refreshes contact, LIVE and SOS without history arrays.
`GET /local/state` preserves exact small views; large maps receive at most 2048
presentation points, fairly sampled across received devices. This is **presentation
only**, not the complete qualified route. Full distances remain derived from every
qualified original. The browser labels previews, bounds SVG work, independently polls
LIVE/SOS and rejects older receipt-state responses. Quality/recording transitions
clear stale presentation. No ACK or preview proves all history complete.

`GET /local/history?device_id=<uuid>&cursor=...` returns up to 512 full-resolution
points and decisions, pinned to a published generation. A generation or policy/window
change returns 409; restart inspection. `layer=raw` returns up to 16 original retained
envelopes with capture/measurement/receipt times, in original transport-sequence order.
The Inspect history dialog exposes both layers and pagination. Raw diagnostic map
mode is bounded too; authoritative geometry and raw originals are never decimated
in SQLite. Queue reports retain freshness and unresolved-count distinctions.

Explicit diagnostic inspection can still materialize complete arrays in internal
`Snapshot`/`FieldReport` calls used by the oracle/tests; ordinary browser endpoints
never request those for large histories. Production ZIP export above 50000 raw rows
uses schema 3: bounded streamed receipts/events/qualified-decision NDJSON and a small
manifest/report. It explicitly leaves whole-corpus automated assessments INCONCLUSIVE;
it does not claim a PASS from a sample. Existing schema-2 small reports are preserved.
Export readers close between pages, maintain SOS redaction, and abort on cancellation,
cache-generation loss or write failure rather than return a valid truncated archive.
Clients consuming schema 3 must process its NDJSON files. Native platform runtime,
physical Android and dense-fleet capacity remain separate acceptance evidence.

Projection failure status is conditional on durable queued work. An idle reader
timeout cannot invent pending history. Startup detects older pending-without-work
state and enqueues the existing reconstruction worker; it does not claim completion
from an ACK or structurally valid cache alone. Original raw envelopes stay unchanged.

## Issue #23 responsiveness and Windows acceptance

Browser health is separate from phone contact. Successful `/local/live` polling
shows **Command Connected**; a failed LIVE request shows **Command LIVE/SOS
unavailable**, without modifying any phone's last contact or GNSS state. A history
read failure appears as a history-refresh error and cannot disconnect healthy
phones. Both polling paths have a four-second browser deadline and remain
independent. A history reply started before a newer LIVE read cannot replace its
contact/age metadata, including at equal receipt versions. Later history reads
still refresh age/contact if LIVE polling fails. Receipt ordering and the additive
`projection_version` prevent old recording geometry (including raw previews) from
crossing a recording/policy change.

Unchanged history/offline-map/terrain SVG nodes survive polling. Their cache key
includes bounded geometry, viewport/dimensions, party styles/labels, selection,
appearance and layer controls. Pan/zoom, selection and changed geometry invalidate
the key; LIVE and located-SOS markers refresh independently. This is a browser
presentation cache, not another projection or storage authority. Original data,
qualified distance and full-resolution inspection remain unchanged.

**Events → Export responsiveness timings** downloads a small JSON containing
backend and browser timings, even while SQLite is busy. Existing **Export
diagnostics** ZIPs retain their deterministic durable-evidence contract. The local-only
`GET /local/runtime-diagnostics` needs neither the ingestion mutex nor a SQLite
read. It includes build/OS/architecture, Go heap/system memory (not process RSS),
goroutines, writer connection-pool wait counters, active HTTP counts and the latest
observed projection-device backlog/time. Fixed stages cover HTTP duration/bytes/
failures, history admission/gate wait, application writer-mutex wait, receipt
transaction, commit and projection-device processing. Counts/totals/maxima cover
this process lifetime; p50/p95/p99 cover only the last 128 completed samples per
stage. Browser draw/render/fetch timings likewise retain at most 128 samples.
Nothing is persisted per request; counters reset on restart. No request content,
coordinates, identifiers, addresses, filenames, browser URL or error text is copied.

Stage durations overlap: receipt includes writer wait and transaction, transaction
includes SQLite execution/lock waiting and commit, and commit includes driver/OS
work. They do not isolate physical fsync, TCP/firewall time or Android ACK
application. `receipt_live` includes individual v1 requests (including legacy
history/status/SOS); the batch path is `receipt_history`. Backlog is the last
successful dashboard observation, not a guaranteed current queue depth. Use the
existing durable receipt/projection/SOS journal for identity/accounting evidence;
use Android diagnostics to distinguish sender/network delay. A slow stage alone
does not establish that the SQLite driver or Windows networking is defective.

Native acceptance is **BLOCKED / NOT TESTED** in the Linux implementation session.
Cross-compilation/Chromium is software evidence only. On a genuine Windows AMD64
laptop, use the exact PR-head workflow executable and checksum manifest:

1. Back up the existing SQLite database while Command is stopped. Keep the tested
   baseline executable. Run the candidate on a writable local disk, recording its
   exact SHA, Windows/browser versions, storage type and actual launch/database
   configuration privately. Retain defaults for source-run development too.
2. Start `.\GNSS-Command-windows-x64.exe` in PowerShell, open
   `http://127.0.0.1:8081`, and configure trusted-LAN phone ingestion on port 8080.
   Inspect normal Windows firewall permission/profile and LAN reachability; record
   any permission failure separately from application-contact failure. Do not
   infer phone contact from Wi-Fi presence. Use Task Manager/Resource Monitor or
   `Get-Process GNSS-Command-windows-x64 | Select CPU,WorkingSet64,PrivateMemorySize64,Handles`
   to record process CPU/RAM/handles alongside exported timings.
3. Run for **at least two hours**. Begin with a short 1/5-phone diagnostic interval,
   then sustain 20 real phones sending fresh data with a bounded retained backlog.
   Add phone 21 while history is outstanding; there is no configured fleet cap.
   Reuse #22's corpus/fixtures rather than generating multi-day dense history.
4. During backlog, exercise pan/zoom, party selection, history inspection, Stop/
   Resume and SOS plus operator ACK. Record Android activation/save/ACK evidence,
   Command first receipt and visible-alert times separately. Export timings at
   idle, backlog, any symptom and recovery; record observation counts and API
   bytes/latencies. Watch CPU/RAM, disk response and browser long tasks separately.
5. Take one phone offline, verify only its actual contact ages into loss, then
   rejoin and verify automatic fresh LIVE/SOS and immutable history recovery.
   Pause/resume history, retry duplicates, deliver delayed observations and restart
   Command. Compare retained identities, recording chronology, rejection reasons,
   gap-safe distance and SQLite integrity after shutdown. Refresh/disconnect only
   the browser and confirm it never changes phone contact authority.
6. Preserve two-hour start/end times, exports, any browser performance capture,
   counts/checksums, actual SOS latency and unexplained loss/freeze events. Mark
   failed/incomplete steps explicitly. Filesystem/antivirus interference, socket
   failures, firewall/profile changes and Wi-Fi/repeater handoff require native
   evidence; no such cause was established by the Linux tests. Repeat native
   launch/restart, offline maps, LIVE/SOS and controls on macOS ARM64 separately.

## Authenticated phone controls (Issue #25)

Use **Phone controls** in the toolbar or selected party detail. Verify a phone UUID
at Android before generating its private enrollment credential. Transfer it directly
into Android Settings, explicitly authorize, then await authenticated contact. The
ordinary configured address and persistent authority UUID are reused; they do not
prove trust. Old clients explicitly show remote control unavailable. Revocation/
replacement invalidates the old per-phone enrollment; unknown in-flight effects are
not retroactively undone. Protect the private SQLite file/backups as credentials;
application storage uses private directory/file permissions where the OS supports them.
No key is included in ordinary state, history, events or diagnostics exports.

Select one party, check selected identities, or select all currently eligible phones.
Review operation, values, TTL and the frozen recipient list before issuing. Late
joins do not inherit earlier all-phone requests. Reporting interval/null default,
#24 adaptive ON/OFF, #22 Sync All and guarded Tracking Start/Stop share one durable
per-phone journal. Expand history for requested versus effective values, actual
attempt/result and errors. Automatic retries retain identity; **Review new request**
is a deliberate new instruction. Safe cancellation distinguishes queued cancellation
from uncertain already-sent work. Desired config or HTTP success never proves effect.
Recording/playback, map architecture and quality/distance controls are unchanged. Remote controls belong only to this live local application, never to
historical data objects. Native Windows/macOS execution remains separate from CI
cross-build evidence. See the normative protocol extension for trust/race limits.

### Phone alerts (#26)

In the existing Phone controls choose **Phone alert**, Normal attention or Urgent
repeating, optional message, and one/selected/all eligible enrolled phones. Review
actual recipient IDs/count, payload and TTL before confirming. All freezes that
list; late joiners are excluded and there is no 20-phone cap. Eligibility comes from
fresh enrollment state even when the independent LIVE inventory is delayed; newly
enrolled phones remain visible by UUID. Issuance waits for recipient selection to
finish. Defaults: TTL 300 s;
urgent 60-second duration / 10-second repeats. TTL range 5–3600 s; urgent duration
5–120 s; cadence 5–30 s. Normal attempts once, notification available until expiry.

History distinguishes queued/offline, durable receipt, output attempted (heard/felt
unknown), OS blocked, human recipient ACK, expiry, failure/unknown, operator cancel
requested and Android cancellation confirmed. Expand to see request, timings,
expiry, effective result and authenticated result revision. An operator cannot
produce recipient ACK. Cancel after send/output stays pending while the phone is
offline; TTL/duration and local Stop remain phone-owned. Human ACK survives a late
Cancel, duplicate, restart or reconnect. Interrupted output is not automatically
replayed. Older phones report unsupported. These alerts share #25 security,
SQLite, transport and result custody and never acknowledge Android SOS or control
Command's SOS siren. Native Windows/macOS, real LAN fleet/endurance and actual
Android audio remain NOT TESTED until the deferred combined acceptance session.
