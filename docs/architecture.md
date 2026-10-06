# System architecture — v1 freeze

Issue #1, including its frozen clarifications, is authoritative. This document
freezes responsibility and recording semantics; the
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

Android's foreground service collects actual GNSS observations even with screen
locked/off subject to platform permissions/OEM limitations. Latest fix is a fresh
in-memory view for health, minimal UI and future SOS. The reporting interval is
not the sampling interval: location callbacks need not all become packets. At a
reporting tick Android snapshots the latest fix and health into a durable immutable
location message, or sends status when no fix exists. The local store owns identity,
sequence allocation, settings and outbox recovery. Packet serialization is explicit;
the reliable sender removes only ACKed outbox work, with current-first reconnect
and priority SOS through the same path. Local retention policy is an Android
implementation detail; unACKed messages must not be silently evicted.

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
- **Valid:** raw observations assessed as sufficiently accurate, fresh *at capture*,
  and physically plausible under explicit Command quality settings.
- **Track:** significant movement accepted from valid observations within a recording
  segment. Track rejection never deletes raw data. Store reasons and policy revision
  with derived results so deterministic rebuilds are explainable. Freeze quality
  settings at each segment start; a setting change during recording opens a new
  reasoned subsegment at Command time, rather than silently reinterpreting history.

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

Per device, sort location candidates by `(observed_at, sequence, message_id)`;
sequence belongs to stable installation identity and message ID breaks any remaining
tie. Equal `observed_at` candidates use the lowest sequence (then message ID) as
the sole track candidate for that instant; preserve others raw with a tie reason.
Never compute a speed using a zero time delta. Missing sequence values are not
lost-track placeholders and never cause interpolation.

Android restores current live state first, then older backlog (SOS has priority).
Command updates live location only for a newer measurement key; device health and
party label use the newest `captured_at` snapshot key, with sequence/message ID ties.
A status packet without a fix can change current health without erasing the last
known location. Every successfully processed request updates last contact, including
retry; contact freshness and location freshness are different.

A late raw observation is assigned by observation time to its original recording
window, then the affected device segment is deterministically rebuilt from raw
candidates in the order above using its frozen policy revision. Replace derived
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

| Action | Effect |
| --- | --- |
| Start Recording | From no current recording, create recording + active segment/window. While already active, no-op. While stopped, use Resume. |
| Stop Recording | Close active window; stop extending live-time recording, continue positions/status/SOS reception. Already stopped is a no-op. |
| Resume | Same recording, new segment/window; no line or distance edge across stop. Already active is a no-op. |
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
between consecutive accepted chronological points *within valid segments*. Sum
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
