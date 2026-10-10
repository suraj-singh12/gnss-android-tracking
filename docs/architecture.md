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
Protocol v1 is their only contract. Both can use shared examples and independent
mocks to develop in parallel after Issue #1 is reviewed and merged.

Android's foreground service requests GPS_PROVIDER observations at about one second,
independently of Activity visibility, Wi-Fi and reporting cadence. Every structurally
valid distinct measurement enters one bounded IO handoff and the existing Room outbox.
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
  new policy revision. Preserve recording Start/Stop/Resume boundaries; remove
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
to about 20 KB without waiting for fill. No unrelated current/status ACK gate exists.
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
operations. Active windows are half-open `[start, stop)` in `observed_at` time:

| Action          | Effect                                                                                                                                                                                    |
| --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Start Recording | From no current recording, create recording + active segment/window. While already active, no-op. While stopped, use Resume.                                                              |
| Stop Recording  | Close active window; stop extending live-time recording, continue positions/status/SOS reception. Already stopped is a no-op.                                                             |
| Resume          | Same recording, new segment/window; no line or distance edge across stop. Already active is a no-op.                                                                                      |
| Clear Recording | Require UI confirmation; delete current derived tracks/distances and retire its recording/windows. Retain identities, configuration and raw observations. Return to no current recording. |

Points observed before start, during stop, or after stop have no membership in that
window even if received while recording is active. Late delivery may rebuild a
closed window for observations measured while it was active; Stop is not a ban on
recovering its historical data. A cleared recording must never be resurrected by
backlog/restart; raw retained after Clear does not automatically enter a later
recording. New windows determine new membership. Resume's first point is independent;
it carries previous segments' cumulative distance but adds no connector. SOS event
locations are telemetry, not track points; only location messages enter track
candidate processing. No recording state is transmitted to control phone tracking.

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
the first acknowledgement time. Recording Start/Stop/Resume/Clear never changes
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
Automatic acquisition uses fixed public AWS Open Data Skadi HTTPS tiles (maximum
four), stitching via existing HGT crops into the same binary package/renderer.
Vectors save first; terrain errors/retries retain vectors and previous terrain.
Provider I/O never holds the Store mutex. No background download or new storage
engine. Local HGT import remains available. Map
import/display/preparation never feeds reconstruction, raw observations, Recording,
live/SOS state or qualified distance. There is no parallel database or projection.
