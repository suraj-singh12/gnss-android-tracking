# System architecture — v1 and Issue #4 amendments

Issue #1 established the responsibility boundaries. The dated Issue #4 amendment
adds native-resolution collection, negotiated history batches and current-policy
reconstruction; the
[wire contract](../protocol/protocol-v1.md) freezes interchange. Future changes to
these semantics require an explicit architecture/protocol decision.

## One system, two independent applications

```text
Android: GNSS -> foreground tracking service -> latest fix
         -> local Room/SQLite persistence -> packet creation -> reliable sender
         -> local Wi-Fi -> Command
Command: protocol receiver -> validation / durable ACK / dedupe -> raw store
         -> validity assessment -> track acceptance -> live device state
         -> recording engine -> SQLite -> local web dashboard
```

These are responsibility boundaries, not separate deployable services or a rigid
call chain: raw persistence precedes ACK, and live reception continues independently
of recording. Android owns measurement, local durability and transmission.
Command owns interpretation, recording, accepted-track generation, distance
calculation and presentation. Neither application knows the other's internals.
Protocol v1 and its negotiated authenticated control extension define their contract. Both can use shared examples and independent
mocks to develop in parallel after Issue #1 is reviewed and merged.

Android's foreground service requests GPS_PROVIDER observations at about one second,
independently of Activity visibility, Wi-Fi and reporting cadence. By default every
structurally valid distinct measurement enters one bounded IO handoff and the existing
Room outbox. Issue #24 adds an explicitly enabled local persistence selector at that
handoff, OFF for fresh/upgrade/absent consent. It retains only actual originals;
Command qualification/reconstruction is unchanged. Versioned local effective policy
uses one atomic preference record, separate from reporting overrides; unknown or
failed behavior falls back densely. Issue #25 extends this local policy through authenticated remote control below.
One pending original provides boundary lookback; abrupt process death before commit
can lose it, just as it can lose the existing memory handoff. LIVE opportunities and
SOS boundaries promote real candidates through the same writer, independently of
native collection. See Android README for thresholds, diagnostics and limitations.
The same immutable observation ID and per-session sequence are used for live/history.
Explicit Start creates a durable Android session; sticky restart preserves it; Stop
ends it. Status and SOS retain separate envelope numbering. Reporting cadence controls
fresh-live opportunities and routine status snapshots, not retained GNSS resolution.
Accepted callbacks become durable only after Room commit; failures/overflow and known
loss are explicit. No raw-observation deletion is enabled. Process death before commit
can lose memory. Activity operations do not acquire or restart GNSS.

Command's receiver validates the wire shape and writes each unique envelope,
original geographic observation and first `received_at` transactionally before
ACK. Low-quality fixes are still raw, not transport errors. Malformed input is
not GNSS and may be logged separately without ACK. Validity assessment attaches
reasons; track acceptance derives significant movement; live state uses newest
measurement/snapshot ordering rather than last packet arrival. Recording assigns
observations to Command-owned time windows. SQLite persists raw, derived state,
configuration and lifecycle events; the local dashboard is a presentation/control
client of that same core. No internet, cloud, online maps or SIM is needed.

Go is the Command core choice. One source tree targets macOS, Windows and Linux
with a portable binary, local SQLite and embedded/served local assets. No macOS-only
frameworks, Python/Node runtime, Docker or database daemon is required for field
operation. Issue #3 chooses and tests a portable SQLite driver, rather than freezing
an untested dependency now. Kotlin + Room/SQLite is the Android direction.

## Raw, valid, track

- **Raw:** every structurally valid unique received observation, including stale,
  inaccurate and implausible fixes. Preserve original coordinates and metadata.
  Retries have one logical raw row; receipt diagnostics may have multiple attempts.
- **Valid:** raw observations assessed as sufficiently accurate, fresh _at capture_,
  and physically plausible under explicit Command quality settings.
- **Track:** significant movement accepted from valid observations within a recording
  segment. Track rejection never deletes raw data. Store reasons and policy revision
  with derived results so deterministic rebuilds are explainable. An explicit quality edit
  reprojects the full current recording from unchanged raw observations with one
  new policy revision. Preserve recording Start/Pause/Resume/Stop boundaries; remove
  obsolete policy-only boundaries. This is an intentional Issue #4 field-driven
  amendment of the earlier frozen-window policy, not a wire-format change.

Backlog network delay alone does not invalidate a fix that was fresh when captured.
For live display, account for both capture age and elapsed time since capture;
never present an old backlog fix as current. Unreliable clocks must be flagged;
never silently substitute receipt time for measurement time.

Every movement candidate is compared to the **last accepted track point**, not
previous raw fix. Default forward/backward minimum floors are **2 m / 2 m**,
configurable in Command. Horizontal uncertainty must raise effective required
movement above these floors when displacement is unreliable. Assess horizontal
accuracy, fix age, impossible jumps/speed and motion below uncertainty; stationary
jitter and small forward/back oscillations must not accumulate false distance.

Infer recent direction from a small rolling window of **accepted** points, not
one GNSS bearing. With no established direction, apply a neutral displacement
rule. Direction classification is only a noise aid: credible turns, curves,
lateral movement, 90-degree changes and genuine reversals must pass. Never impose
a straight corridor, planned route or shape. Reset direction/last accepted point
at segment boundaries; no speed/distance comparison bridges a stopped interval.
The concrete quality thresholds/window size are Issue #3 policy, not wire fields;
freeze them with deterministic tests then. Use no ML, invented points or opaque
smoothing. Quality gaps/implausible jumps must not become bridging distance: the
engine may create an explicitly reasoned subsegment before the next credible point.

## Chronology and reconnect

Within an Android session, historical order is its original observation sequence;
source sessions use `(session_started_at, session_id)` between sessions. Queued legacy
history precedes session-aware collection and uses `(observed_at, envelope_sequence, message_id)`.
Live measurement ordering always uses observed time, independent of that history order. Identical native
coordinates do not deduplicate measurements. Repeated native measurement delivery
uses observation identity; legacy same-timestamp duplicates retain their tie rule.
Missing observations are never interpolated or claimed received.

One serialized sender serves eligible SOS first, fresh live work next, then the
oldest historical block. A live attempt grants a historical opportunity even when
new callbacks continue. Status has an independent cadence. Historical batches are
negotiated separately from individual v1/SOS, and adapt from one complete observation
to about 64 KB without waiting for fill, bounded by negotiated bytes/counts and local safety caps. One in-flight request per phone limits SQLite contention; independent phones make progress concurrently. No unrelated current/status ACK gate exists.
Recovery blocks are contiguous pending sequences separated by delivery outcomes,
not movement segments. Original envelopes and permanently unresolved errors remain
retained. Android/OEM owns physical Wi-Fi reassociation; code recovers when a network
is exposed, with application reachability, network-bound transport invalidation and
bounded retries. Public internet validation is irrelevant.

Command updates live location only for a newer non-future measurement key; device health
and party label use the newest non-future `captured_at` snapshot key, with
sequence/message ID ties. Clock-anomalous future observations remain raw but cannot
pin the operational marker or snapshot ahead of later genuinely current data.
A status packet without a fix can change current health without erasing the last
known location. Every successfully processed request updates last contact, including
retry; contact freshness and location freshness are different.

A late raw observation is assigned by observation time to its original recording
window, then the affected device segment is deterministically rebuilt from raw
candidates in the order above using its current recording policy revision. Replace derived
points and recompute segment distance atomically; do not append an arrival-order
edge or add a guessed distance correction. Rebuilds may revise provisional geometry
and distance; duplicate deliveries must not revise them. Track chronology and live
state are independent. Issue #4 proves arrival permutations yield the same final
accepted track and distance.

## Recording lifecycle and ownership

Command creates UUID `recording_id` and `segment_id` values; these never originate
on Android and are not required in phone packets/ACKs. Track ownership is
`(recording_id, device_id, segment_id)`; labels do not own tracks. Device count is
dynamic, with no default party count. Two participating devices yield two tracks;
five yield five. A newly discovered device can join during recording, and its
first accepted point in that recording is its recording start.

Command persists UTC lifecycle boundaries with sufficient precision for ordered
operations. Active windows are half-open `[start, stop)` in `observed_at` time.
Two start modes share the same authoritative raw store: `from_now` selects
observations measured on/after the operator's persisted Start instant;
`from_beginning` selects **all retained received history** for the initial
window, including observations predating Start and source sessions not yet known.
It has no lower observation-time bound and can start with zero connected devices.
The former `session_beginning` request value is a compatibility alias. New
source sessions and delayed history are discovered without relying on a currently
reported live session or inventing continuity. Pause/Stop limit the current window from above; Resume after Pause creates a new lower time boundary. Stop is final.

| Action          | Effect                                                                                                                                                                                    |
| --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Start Recording | Create active recording/window; From Now or From Beginning. Presentation always starts at the operator press. |
| Pause | Close active window; GNSS, LIVE, ACK, SOS and history continue. |
| Resume | Open a new window in the same operator-paused recording; never connect across the pause. |
| Stop | Finally close recording; persist the Save/Discard choice across dismissal/restart. Resume is rejected. |
| Save | Seal an immutable snapshot without interrupting active/paused capture; after Stop preserve the Stop endpoint. |
| Discard | Confirm and retire only the unsaved selection; retain original GNSS, saved snapshots, devices and SOS. |

For `from_now` and every Resume window, observations measured before that
window's Start or after its Stop have no membership even if received later.
The first `from_beginning` window alone includes observations before Start.
Late delivery can rebuild a closed window if measured within its true boundaries;
Stop is not a ban on recovering historical data. A cleared recording must never
be resurrected automatically by backlog/restart; retained raw can be used by a
**new** From Beginning recording. Resume's first point is independent and carries
the previous accepted cumulative distance without inventing a connector. SOS event
locations are telemetry, not track points; only location messages enter track
candidate processing. No recording state is transmitted to control phone tracking.

A projection worker separately derives chronologically qualified sections from
durably received raw. A gap in original Android observation sequence can yield
separate provisional-length **qualified sections**, rather than suppressing every
point after a missing sequence. When missing real observations arrive, affected
sections are reprocessed deterministically. Projection failure is visible and
never relabelled successful by a transport ACK. Diagnostic raw geometry remains
inside the chosen recording windows, is requested only for the unfiltered view,
and never changes quality totals. The dashboard reports durable receipt, recording
eligibility and exclusion, projection/quality results and actual rendered points
as distinct stages.

## Distance and rendering

Cumulative travelled distance is the sum of **horizontal geographic distances**
between consecutive accepted chronological points _within valid segments_. Sum
segment totals per device per recording; segment first points add zero distance.
Altitude contributes **nothing** to v1 distance. It remains telemetry. Use WGS84
lat/lon and a documented, deterministic geographic distance function (v1 rendering
must not redefine it); a spherical haversine with radius 6,371,008.8 m is the initial
Command distance convention. Normalize longitude deltas across the antimeridian.
Do not round individual edges; round for display only.

For a small local area, derive an equirectangular metric view about a shared recording
origin `(lat0, lon0)` selected from the earliest accepted point across devices
by `(observed_at, device_id, sequence, message_id)`:
`x = R * wrap(lon-lon0) * cos(lat0)`, `y = R * (lat-lat0)` with radians and the
same radius. If backlog changes that earliest point, recompute the whole derived
view consistently; geographic geometry/distance remain authoritative. Preserve
source lat/lon; use equal x/y screen scale, north up, and
flip y only for screen coordinates. This approximation is for local drawing, not
travelled distance. For large areas/polar operations choose a suitable projection
later without changing stored geographic points or Protocol v1. No fixed route
shapes. An optional offline map goes beneath the renderer using the same coordinates;
blank canvas works without map data. No offline map is implemented here.

Display-dot interval is a separate Command presentation setting, positive multiples
of 10 s. Per device segment, show the first point, then the first accepted point at
or after each elapsed interval boundary from that first point; skip empty buckets,
never synthesize a point or show a point more than once. E.g.
0/10/20/30/40/50/60 with 30 s shows 0/30/60. The line
uses **all** accepted points, including hidden dots; distance and storage are
unchanged by dot density or show/hide. Hover shows cumulative distance to that point
from the device's recording start across valid segments; otherwise show total.

## Implementation seams and verification

No feature implementation, schema, production UI or release infrastructure belongs
in #1. The [fixtures](../protocol/fixtures/README.md) and
[test tools](../test-tools/README.md) let #2/#3 prove serialization, ACK/config and
identity independently. Their later deterministic suites must cover jitter,
uncertainty, turns/reversals, stale fixes, jumps, last-accepted comparison, geographic
distance, dynamic join, Stop/Resume/Clear, retries, arrival permutations, config
convergence and restart. #4 adds real LAN integration; #5 implements SOS triggers;
#6 adds diagnostics/UI/offline map; #7 proves field acceptance and portable releases.

## Issue #5 SOS implementation

The shared SOS engine accepts on-screen and supported physical-key adapters.
It immediately snapshots the existing `LatestLocation`/device health and commits
one immutable v1 SOS envelope through the same Repository/Room transaction and
installation sequence allocator. It never acquires another GNSS fix. The app
owns one engine and one Sender; TrackingService owns the existing native source,
foreground lifetime and sending loop. While the Activity is open with tracking
stopped, the same Sender can drain saved emergencies. Without the tracking FGS,
background lifetime/delivery is best effort and opening the app resumes delivery.
Activity destruction does not cancel an activation handed to the app scope.

Selection checks eligible SOS before current-recovery capture and before ordinary
backoff. Each SOS retains its own durable retry deadline. A failing emergency
therefore permits ordinary reporting between retries; a new emergency bypasses
ordinary backlog delay. A request already in flight may take its existing bounded
6-second timeout (3-second connection timeout). After SOS receipt, a genuinely fresh
original observation receives the next live opportunity; history does not need a current ACK. No extra transport, identity, outbox, control
channel, GNSS listener or runtime dependency is introduced.

| State                 | Authoritative evidence / meaning                                                   |
| --------------------- | ---------------------------------------------------------------------------------- |
| TRIGGER_DETECTED      | Local immediate feedback; not proof of storage or delivery                         |
| SAVED_LOCALLY         | Room transaction returned after commit; immutable identity and activation time     |
| WAITING_FOR_NETWORK   | Saved, no Wi-Fi; cannot transmit without a LAN path                                |
| SENT / RETRYING       | Attempt evidence and durable retry metadata; still awaiting receipt                |
| COMMAND_RECEIVED      | Matching validated v1 stored/duplicate ACK committed to Room                       |
| OPERATOR_ACKNOWLEDGED | Command-only persisted acknowledgement of device + event; not transmitted to phone |

One successful activation is one event/message (`event_id=message_id`). Retries
never allocate new IDs. Within three seconds of the last saved activation,
adapters coalesce via a Room transaction, including concurrent adapters and
reopening the database. A deliberate activation after that window creates another
independent emergency even if the earlier event is unacknowledged. Persistent
debounce uses activation wall time; abnormal wall-clock edits can alter this
window, so field clock checks remain required. Short press/release evaluation is
monotonic and excludes holds, key repeats, canceled keys and mixed keys.

Command adds the alert projection to its existing persisted state in the same
raw-ingestion transaction. Legacy reserved SOS envelopes are projected during
additive database opening; no raw data is rewritten. Identical retries return the
first receipt and cannot reset human acknowledgement. A local-only, same-origin
JSON control acknowledges exactly `(device_id,event_id)` in a transaction, retaining
the first acknowledgement time. Recording Start/Pause/Resume/Stop/Save/Discard never changes
alerts. Neither acknowledgement nor any phone control cancels/deletes an event.
All operational SOS and raw dedupe rows are retained indefinitely under the existing
store policy, including ACKed Android rows; storage exhaustion must be corrected
explicitly. Diagnostic history alone follows the existing bounded retention.

The foreground triple Volume Up adapter is opt-in. Locked-screen/other-Activity
activation is unsupported in this implementation. Future rugged-device/PTT inputs
can call this engine through an authorized adapter without changing delivery.
The unresolved Issue #4 GNSS stall remains unresolved; an SOS with unavailable or
stale location is still saved and delivered truthfully.

## Final Issue #4 reliability amendment (2026-10-09)

Raw SQLite commit precedes ACK and schedules restart-safe projection work. Derivation
failure cannot undo an already committed receipt. A worker checkpoints the existing
accepted-anchor engine; ordinary append resumes it and late arrivals recalculate from
the preceding checkpoint through dependent points. Policy/lifecycle changes invalidate
appropriate projection state. Projection pending/error is explicit. Full chronological
derivation remains the deterministic oracle for points, segments and distance.

Fresh live markers do not consult old historical movement anchors. Incomplete native
history creates provisional live sections with zero starting section distance. Late
history reconciles those sections through canonical derivation, never adding provisional
distance twice. A quality-rejected junction does not invalidate later candidates.

Every quality rule has an enabled switch and retained numeric threshold. Disabled
movement floors contribute zero; the uncertainty threshold applies only when enabled.
Unknown accuracy is rejected when maximum accuracy is enabled; disabling it allows
structurally valid coordinates with unknown accuracy. Structural validation and live
freshness remain mandatory. All policy edits reconsider retained raw data while preserving
recording lifecycle windows. An unfiltered diagnostic view changes neither policy nor
authoritative distance. No accuracy circles are drawn.

Recording can start from Command now or from the identified current Android session.
The session mode selects that source session; late delivery does not change original
membership. Contact, GNSS, live availability and history completeness are separate.
Phone queue counts carry measurement time; disconnected phone state is unknown.
Received-through means actual contiguous raw receipt; processed-through may include
explicit unresolved outcomes and must not be called complete.

The original GPS_PROVIDER/Looper/foreground-service acquisition and 30-second native
freshness rule are unchanged. No listener restart workaround is introduced. Issue #5
retains SOS ownership, Issue #6 offline-map scope, and Issue #7 extended endurance.

## Corrective operational UI adapters

The deterministic Android hold adapter owns only one foreground pointer/timer and
progress/haptic feedback. Completion calls the existing app-scoped SOS engine;
pause/navigation/destruction cancel incomplete gestures. The tested physical-key
experiment remains the existing Activity/controller/journal path, with no new
background interception or effect on saved SOS delivery.

Command consumes the same authoritative recording, device, SOS and quality state.
The shared audible scheduler tests the persisted unacknowledged condition; dialogs
and arrival notices cannot acknowledge an event. Event History reads the existing
`field_evidence` journal (latest 500 on screen; existing full export retained).
Offline-map preparation uses fixed, bounded Nominatim/Overpass requests and adds
an `offline_maps` library table to the existing Command SQLite file. The PR #17
extension adds an associated `offline_terrain` table in that same file: bounded
binary DEM crops, not GeoJSON raster samples. One source grid supports the selected
hillshade, contours and elevation inspection; all use the existing map projection.
Legacy automatic acquisition uses fixed public AWS Open Data Skadi HTTPS tiles (maximum
four per bounded crop), stitching via existing HGT crops into the same binary package/renderer.
Vectors save first; terrain errors/retries retain vectors and previous terrain.
DEM gzip decoding streams from the provider response: a deadline/cancellation while
reading is reported as an interrupted transfer, not proof of corrupt DEM data.
DEM acquisition now has dedicated TCP/TLS 10-second, response-header 20-second,
tile-attempt 120-second and overall six-minute budgets; decoding/cropping is bounded
to ten seconds and SQLite save to five seconds. The existing OSM 25-second deadline
and ordinary server/ingestion 30-second write deadline remain unchanged. Only the
terrain-download response extends its write deadline to six minutes plus 30 seconds.
The existing request streams progress and propagates disconnect/cancellation;
there is no background job queue. Complete validated terrain is upserted atomically.
The existing field-evidence journal retains the latest 20 terrain download reports;
other evidence is not pruned. One completed, not-yet-saved grid may be reused for
15 minutes (bounded to 257² samples); no full DEM tile is cached or persisted.
Provider I/O never holds the Store mutex. No background download or new storage
engine. Local HGT import remains available. Map
import/display/preparation never feeds reconstruction, raw observations, Recording,
live/SOS state or qualified distance. There is no parallel database or projection.

## Issue #22 synchronization changes

Android retains one Room/outbox/Sender authority. Room v5 indexes retry deadlines and native measurement-time selection; v4 adds transactional derived
queue counters; session progress uses bounded examples plus exact counts. Normal
sync grows progressively with ACK RTT/goodput feedback. Local manual drain coalesces
requests and accelerates discovery without resetting a healthy ramp or adding
another engine. History-specific backoff does not suppress fresh LIVE. Endpoint
receipt applicability and durable Room ACK writes return one atomic outcome.
A user-started dataSync foreground lifetime can drain retained history with Tracking
OFF and no GNSS acquisition. Process death/reboot still require operator reopening;
OS/OEM budgets are not durability guarantees. See android/README.md for exact policy.

Command still ACKs only committed SQLite raw/identity/receipt/work. Bounded history
admission prevents an entire fleet queuing directly ahead of urgent traffic. The
existing derived cache now stages per-device generation chunks and publishes pointers
atomically. Large devices feed the same chronological Derivation in indexed keyset
pages, closing readers between pages. Interrupted staging preserves the prior cache;
legacy caches migrate without loading full geometry, and missing manifests rebuild
from raw. Metadata distinguishes latest raw receipt from the projection's source set.

Independent lightweight LIVE/SOS and bounded history-preview endpoints replace full
history polling in the production dashboard. Full-resolution geometry/decisions and
original envelopes remain inspectable through generation-pinned pages. Display LOD
never changes qualified distance or raw authority. Large diagnostic ZIPs stream
separate evidence files with explicit INCONCLUSIVE whole-corpus assessments. See
command/README.md and protocol/protocol-v1.md for bounds and compatibility.

Guidance remains identical to documentation-only branch chore/codex-repo-instructions
at ca7b091e9032ac54e3ddd8d89a6b9ec414fb9f91; only AGENTS.md was transferred.

## Issue #25 durable authenticated Command → Android control

Control extends the existing configured endpoint, persistent authority UUID, local
Command browser API, receiver, SQLite, Android enrollment Settings, Room and single
Sender. See the [normative authenticated extension](../protocol/protocol-v1.md)
for provisioning, cryptographic framing, states and limits. V1 GNSS/SOS remains
compatible and unauthenticated; legacy clients are explicitly unavailable for
secure remote operations. Android opens no listening port.

Command snapshots explicit phone recipients and persists instructions before
network delivery. Android durably records actual receipt before execution, uses
per-operation ordering and immutable IDs for deduplication, then returns actual
effective outcomes. Command commits those results before authenticating matching
custody confirmation. Sent, received, attempted, applied and result-confirmed are
separate facts. Unknown stays unknown after an interrupted nontransactional effect;
no automatic repeat pretends to provide power-loss atomicity for Android OS APIs.

Reporting reuses existing desired revisions and effective Room settings. Current
Android installs/upgrades ignore unauthenticated ACK configuration from first contact,
without losing GNSS receipt; older v1 clients retain their legacy wire behavior. Adaptive ON/OFF
uses #24's actual local policy surface, never a second selector. Sync invokes #22's
same drain and counts pending originals through the received instruction's saved
sequence; Tracking OFF never starts collection. Tracking Start requires visible
Android and the existing preflight; Stop verifies teardown and durable session end.
Existing GNSS, quality/projection, recording, SOS and human SOS ACK authorities do
not change. Polling uses the same bounded Wi-Fi transport after priority selection.

Room v6 additively adds authorization metadata, `command_journal` and per-operation
watermarks. The canonical journal powers Diagnostics → Command Instructions and
sanitized existing ZIP export. Credential material is outside diagnostic models,
sealed in Android Keystore-backed preferences; Command keys stay outside exported
State/events. Operator credential transfer is private and deliberate; IP/UUID alone
cannot bootstrap trust. Authority/credential replacement requires re-enrollment.

Issue #26 adds `alert` to this exact #25 path. Existing SQLite instructions and Room
v6 journal fields hold per-recipient UUID, validated mode/message/bounds, output
attempts, local recipient ACK, pending cancellation and result revision/custody.
No schema migration is required; all v6 data and earlier migrations remain intact.
Normal and urgent notification channels use independent per-command tags/actions;
only native local notification/Tracking/Diagnostics buttons write recipient ACK. Existing
Sender SOS/LIVE/history scheduling, GNSS capture and SOS operator ACK are preserved.
The permitted app loop owns bounded best-effort repeats; deadlines combine absolute
TTL and monotonic duration. Restart stops/reconciles uncertain output without replay.
See protocol/protocol-v1.md for authoritative bounds, uncertainty and cancellation
races. Diagnostic ZIP redacts message content while retaining canonical alert events.

### Issue #28 radius acquisition extension

`offline_maps` owns a persisted ellipsoidal WGS84 circle plan. Additive
`offline_map_chunks(map_id,chunk_key)` owns per-chunk GeoJSON, existing GTERR001 and
layer evidence. There is no new receiver, database, geographic renderer, DEM engine
or terrain format. An explicit browser sequence calls one bounded chunk endpoint;
no autonomous background downloader runs after browser exit. Committed chunks
survive exit, failure and response loss. Request identity is deterministic from the
centre/radius. Additional product selections reuse that map and its native samples. Child asset + success evidence commit in one transaction;
failed/interrupted attempts cannot publish a validated asset. Legacy maps remain readable.

A 240-arc-second geographic grid bounds native crops and splits antimeridian regions.
GeographicLib's WGS84 direct geodesic defines the requested circle; optimized longitude
extrema bound it independently of Mercator. Conservative envelope chunks are deliberately
supersets, never claimed as an exact source-data percentage. A polar/projection limit,
2,048-chunk plan limit, 256 MiB child-asset quota, disk reserve and viewport rendering
budget produce explicit reasons instead of silent radius/tile truncation. Source
cache I/O, HTTP, decode and encode run outside the receiver mutex; only bounded child
transactions use it. Existing raw/ACK, quality, SOS, LIVE, controls and projections
are untouched. Source cache is disposable, SHA-verified and bounded to four full tiles;
regions can contain more than four tiles through sequential reuse and eviction.

The existing browser renderer progressively loads saved vectors from every visible
chunk with a fair shared 100,000-coordinate budget, retaining source vertices for
one-pixel line overview and exact polygon rings. Deferred features are explicit and
zoom restores detail; a ninth chunk cannot clear all geographic features. Superseded
local reads are cancelled. DEM remains independently bounded to eight visible grids,
shares contour levels, clips halos at core bounds and preserves void transparency. All overlays share its authoritative WGS84
positions. Source/coverage metadata and render availability remain separate. See the
Command README for API/resource/operator details and UI acceptance for evidence limits.


### Issue #28 automatic acquisition and standalone map portability

The existing radius map job is the sole acquisition owner. Its map-owned child transfer ledger
persists cumulative approved/reserved/received body bytes across all providers and
retries; no second queue, renderer or map library is introduced. The initial 5 MB
allowance cannot silently escalate. HTTP bodies reserve bounded bytes before reads,
and announced large fallback requires explicit additional approval. See Command
README for exact accounting and resource limits.

Exact verified chunks and covering source indexes precede Overpass. Failure triggers
bounded machine-readable Geofabrik discovery with polygon/hole checks and a single
regional attempt. Publisher-declared PBF extent/revision/ways-only scope is distinct
from actual saved chunk envelopes and browser terrain derivation. Streaming PBF
framing uses the published generated OSM protobuf schema. Full extent retains bounded
two-pass SQLite node indexing. Crop extraction uses disposable 24-byte node records
in bounded disk partitions (512 MiB aggregate, 8 MiB each, 32 open writers, 512 cached 3 KiB lookup
pages (1.5 MiB)), then publishes intersecting original way geometry and its actual crop
scope into the same `offline_osm_sources/ways` tables. Both paths use the same sparse/
dense decoding and reference/geometry validation. Crops preserve crossing ways even
when neither endpoint lies inside the crop. Immutable source SHA plus crop bounds
separate partial source coverage from full-source identity. Only completed source rows are reused; unpromoted index
rows are cleaned on startup/retry. Original GNSS/SOS tables are never modified by map
operations. Node writes yield every 256 rows; way lookup/geometry work uses the
existing read connection outside the receiver mutex.

Standalone `.gmap.zip` validates bounded entries, canonical requested coverage,
asset checksums/formats and missing-layer disclosure before atomic map-library
publication. Verified chunks/GTERR001 and compatible terrain derivation remain the
operational assets. This archive is not frozen recording evidence; #38 retains that
separate ownership. UI uses one Map & Layers dialog with Download New Map, Use
Existing Map and Import Map, existing Saved Maps selection and Layers controls.

### Issue #39 Android presentation boundary

MainActivity retains the existing three top-level destinations, with a small native
parent-route map for focused detail screens. NativeUi supplies shared view/spacing/
color primitives; OperationalUi supplies read-only health facts. These views consume
Installation, FieldPreflight, #24 policy, Operational/Room queue flows and #25/#26
command journal. CommandPresentation formats canonical reasons/evidence; it does not
execute commands. No additional navigation framework, database, service, settings
store or journal is introduced.

Draft text/caret/focus, route, selected records, filter and page state use Activity
saved instance state. Credentials remain excluded. Only 20 matching journal/SOS rows
render per page; journal ordering remains off-main, list controls are retained for
unchanged data, and list rendering is deferred outside its destinations. Tracking
snapshots the existing Operational state after suspended diagnostic reads and uses
that same snapshot for its status, fix and control labels. Alert menus
are rebuilt only when canonical alert records change. Diagnostic rendering/exports do
not block GNSS acquisition, SOS, Sender or database commits. Existing journal query
and retention bounds remain authoritative.

TrackingNotification enters the original visible Sync/confirmed Stop handlers. The
consumed action is saved across recreation. NotificationAppearance reads the existing
preference, retains notification identity/actions and remaining timeout, and recolors
with only-alert-once; it is not an execution or acknowledgement authority. Tracking/
transfer services retain their existing lifetimes. User-facing pause is removed;
internal OS/lifecycle pause remains. SOS still uses the original 1.2-second hold and
engine. A retained instruction/result timestamp is labelled as evidence rather than
substituted for an unrecorded empty-poll contact time. Full-app presentation now follows
the supplied Review V3 PDF plus four written V4 overrides.

Map consent is an explicit acquisition state, not a network failure. The same
transfer ledger powers API HTTP 409 `transfer_approval_required` responses and UI
manifest/confirmation. A pending requirement describes the currently attempted
source's actual cumulative requirement; switching sources cannot enlarge the
approved allowance, and a rejected regional response must not contaminate a later
DEM requirement. Non-success HTTP responses retain their provider status rather
than being reclassified by their error-page Content-Length. Browser acquisition
pauses at consent before starting the next independent layer. Tests exercise
these handlers and source adapters through local providers, immutable pinned real
HGT/PBF inputs, actual browser confirmation and offline production restart.
Public-provider acceptance has its own job and truthful failure reports; it is
independent of the deterministic software and controlled recovery gates.

Issue #49 admits declared local coverage/plan feasibility before indexing and checks
regional disk/source-cache capacity before a budgeted response can request consent.
The later disk recheck remains. Approved regional retries resume that verified source
path without re-running Overpass. The 5 MB initial ledger and 1 GB hard ceiling remain
enforced; impossible cumulative requirements cannot produce an approval prompt.
Unpromoted temporary crop/upload/download work older than eleven minutes can be
removed during subsequent map admission; validated HGT/catalog/SQLite assets are
never included. Existing source publication, map transactions and GNSS/SOS/LIVE
ownership remain unchanged.

## Recording presentation foundation (Issue #27)

The recording projection still uses the existing raw SQLite observations, identity,
quality policy and gap-safe windows. A separate **presentation journal in that same
SQLite database** records what the authorized browser applied. Original observed,
Command receipt, browser applied/rendering-opportunity, durable confirmation and
operator press/receipt times are distinct. Late observations cannot be inserted into
an earlier presentation checkpoint. Both start modes begin the presentation timeline
at Start; From Beginning only changes the original-history projection selection.

The local dashboard acquires a server-mediated random credential and fencing epoch.
SQLite serializes claims; fifteen-second leases and two-second heartbeats fence old
publishers, including after restart. Refresh/pagehide releases ownership. Lease expiry
alone is uncertain rendering/ownership loss, not proof of closure. Backgrounding
reports unverified presentation while retaining heartbeats. Process heartbeats are
independent of browser liveness; restart records the last durable process heartbeat
to startup as an explicitly uncertain downtime bound. Active intention survives;
operator Pause and final Stop remain authoritative.

The browser freezes the source model after synchronous map application, together with
actual SVG geometry, selection/visibility, viewport, layers, panel contents, status,
quality and SOS. Two animation frames and a subsequent task bracket a rendering
opportunity; intervening display changes invalidate confirmation and create an
unverified event. This establishes browser-applied state at a completed rendering
opportunity. It does **not** establish OS pixel scanout, pixel-perfect screenshots or
human attention. Chromium integration deliberately delays animation callbacks and
asserts no confirmation from receipt alone. An additional instrumented oracle blocks actual synchronous map application after
a durable receipt and checks the journal still identifies the old displayed revision
until application and its rendering opportunity complete. OS compositor scanout
remains outside the evidence claim.

Confirmed events have a browser epoch/revision, server ordinal and SHA-256 checkpoint
identity. Exact retry is idempotent; conflicting or older revisions fail. Only FULL
SQLite commit produces success. Content-addressed byte deltas share unchanged payload spans, with a full checkpoint
at most every 32 links. SHA-256 verification reconstructs the exact document bytes;
copy bounds, expansion limits, chain depth and scope are checked. The request limit
is 4 MiB; stored compressed checkpoint/delta content has a 64 GiB admission cap and
a 136 MiB free-space guard protecting the receiver reserve. Storage limits are explicit
unverified capture conditions, never silent evidence truncation. Quota/storage errors leave GNSS receipt independent and report
unconfirmed evidence. Journal/event growth and multi-day capacity still require the
full workload acceptance; no retention/deletion policy silently drops evidence.

Save seals version 2 immutable headers, ordered selected-party display references,
complete participation metadata and included-party original wire/receipt records in
one frozen WAL read snapshot. Data is staged with short individual writes, then the
header/request mapping/Save fact publish in one atomic transaction. Unpublished rows
are inaccessible through receipts and reclaimed after failed publication or restart.
No excluded-party detailed checkpoints or original rows are copied.
Descriptive date never changes original times. Multiple Saves have independent IDs;
a request ID makes a lost-response retry idempotent. Final Stop bounds the snapshot;
dismissal leaves its choice durable. Discard retires the unsaved selection and retains
original GNSS, saved snapshots, pinned assets and SOS. SQLite triggers reject changes
to sealed tables. Diagnostics list saved receipts and recompute stream hashes.

Successful original map, terrain, manifest and chunk responses are content-addressed
and pinned **before their exact bytes are delivered**. The browser records the response
hashes it loaded, and each confirmed checkpoint references those immutable assets.
Concurrent map replacement cannot retarget an earlier read. Terrain JSON responses
are a durable equivalent copy of original samples/metadata; no renderer-generated
pixels are substituted for the original source. Missing pin/revision references remain
explicit map-fidelity gaps. Pinned copies survive ordinary library deletion.

Snapshots currently always carry incomplete-evidence limitations: frame opportunities
can be invalidated or lost, and exact OS scanout/physical attention is not inferred.
Save scans a frozen read transaction without holding the receiver mutex; writes yield
between staging records. A local test receives and commits another observation while
600 originals are being staged and verifies it cannot expand that snapshot. This is
software concurrency evidence, not sustained multi-day fleet/native certification.
#37 playback/library and #38 recording packages remain outside this implementation.


Start/Resume can atomically retain the browser's last stable pre-action rendering
opportunity when the browser verifies the applied state is still identical at the
press. The checkpoint records both its original rendered/opportunity timestamps and
its effective recording boundary; the timeline never starts before Start. If no
stable proof exists (including rapid changes or background throttling), the boundary
is explicitly unverified until the next confirmed display. No backend response is
substituted for that evidence.

Saved display references resolve through immutable bounded checkpoint/delta graphs.
Subset graphs use a namespace derived from the included identity set; they cannot
inherit an all-party graph containing excluded details. Repeated all-party Saves can
share the original immutable display blobs. Version 1 full checkpoints and inline
saved states remain readable without rewriting historical snapshots.
`GET /local/presentation/saved?id=UUID&event_cursor=0` pages one frozen event at a time
and resolves its filtered checkpoint with hash verification. It reads no later GNSS.
The default ID diagnostic recomputes the immutable reference stream hash. Payload
storage bytes count compressed blobs; receipt reference bytes are reported separately.

Recovery availability intervals end at the first confirmed rendering opportunity,
not ownership claim/HTTP success. A paused browser may confirm presence without
creating a presentation clip. Known pagehide gaps are split around Command downtime;
an owner that might still be open is treated as unverified, never declared closed
from heartbeat expiry or process restart alone. Save preserves a trailing unverified
active-clip interval when no later presentation was durably confirmed at its boundary.
Supporting projection policy/raw/projection versions are frozen as audit-only metadata.

Asset identities attach when vector/terrain data is applied, not when response headers
arrive. Partial or failed source pinning remains explicit in the frame, Save form and
receipt. The bounded saved diagnostic can retrieve a referenced asset with
`&asset=SHA256`; it checks snapshot membership and recomputes the exact stored hash.

The Save party picker reads the durable participation registry, rather than today's
fleet: a phone joining after final Stop cannot become a member of that recording.
During an active form, newly displayed late joins appear unchecked for an explicit
operator choice. Party label updates remain tied to the existing device identity;
Stop freezes the final participation labels. Snapshot receipts report included count
against the actual total manifest, including parties not selected by the operator.

Publishing endpoints also reject arbitrary hostname origins to prevent DNS rebinding;
use localhost or the Command IP address for the presentation console. This protection
supplements same-origin JSON, the random owner capability and restart/lease fencing.
It does not claim attestation against an OS administrator who controls the private
Command database or the authorized browser itself.
