# Protocol v1 — canonical Android ↔ Command contract

Normative words **must**, **must not**, and **may** apply to both implementations.
This document freezes interchange for [Issue #1](https://github.com/suraj-singh12/gnss-android-tracking/issues/1).
[Architecture](../docs/architecture.md) freezes recording/track interpretation.
[Fixtures](fixtures/README.md) are regression examples, not an alternative spec.

## Transport and versioning

Android sends one immutable message per `POST /api/v1/messages` to a manually
configured Command base URL on the local Wi-Fi LAN. UTF-8 JSON, request and response
`Content-Type: application/json`, no compression required. Maximum request body
65,536 bytes. No batching, MQTT, WebSocket or secondary command protocol in v1.
HTTP on a trusted isolated LAN is the baseline; HTTPS can use the same contract.
V1 does not supply authentication/encryption or internet exposure. `device_id` is
an ownership key, not a credential. Command UI/API controls are local to Command;
this endpoint is the only phone-to-Command contract.

The path and `protocol_version` must both select integer `1`. No implicit fallback
or interpretation as another version. Unknown path version returns 404; unsupported
body version on the v1 path returns 426. Unknown `type` or malformed known fields
returns 400. Receivers ignore unknown object fields for additive compatibility;
senders must not depend on ignored fields. Changes to required fields, units,
meaning or behavior require a new protocol version and explicit architecture
review. Older incompatible versions are rejected, not silently upgraded.

All documented object fields are required unless explicitly described otherwise;
nullable means **present with null**, not omitted, empty string, sentinel -1 or NaN.
JSON numbers must be finite. Integers must be actual integer JSON values in
`0..9007199254740991` unless a tighter bound below applies. Reject duplicate JSON
object keys. UUIDs are canonical lower-case hyphenated UUID strings. Strings are
Unicode; labels are nonempty, at most 80 Unicode code points. All times use exactly
`YYYY-MM-DDTHH:mm:ss.SSSZ`, valid UTC Gregorian dates. No local offsets. Time comparisons
parse instants, not locale-formatted strings. Lat/lon use WGS84 decimal degrees.

## Request envelope

| Field | Type | Meaning |
| --- | --- | --- |
| `protocol_version` | integer, 1 | Wire version |
| `type` | string enum | `location`, `status`, or `sos` |
| `device_id` | UUID | Stable installation/enrollment identity |
| `party` | object | `id` and `name`, editable strings; operator labels, not keys |
| `message_id` | UUID | Unique logical message, unchanged on retry |
| `sequence` | integer ≥1 | Per-device strictly increasing durable allocation across every message type |
| `captured_at` | UTC timestamp | When Android snapshots envelope/health/config; immutable |
| `config_state` | object | Applied remote snapshot plus current local/effective cadence |
| `health` | object | Snapshot of available device diagnostics |
| `fix` | object or null | Required nonnull for location, required null for status, nullable for SOS |
| `sos` | object | Required only for `type=sos`; must be absent otherwise |

Generate `device_id` once for the installation/enrollment and persist it. Reboot,
label edit or endpoint changes do not create a new device. Reinstallation or loss
of identity/sequence durability requires a new `device_id`, never reuse a sequence
under the old identity. Atomically allocate sequence and save the immutable message
before transmission; gaps are legal, wrap/reset is not. Devices and party labels
are dynamic and unbounded in count. Multiple devices may share a party label.
`device_id` alone is authoritative for dedupe, ownership, live state and tracks.

`message_id` identifies the immutable snapshot, not an individual transmission or
GNSS callback. Multiple packets may contain the same latest GNSS measurement;
Command preserves them raw, but tie handling in architecture prevents duplicate
measurement instants creating movement. Never generate a new ID/sequence to retry
an existing logical message. Remote/local setting or party edits affect **new**
messages, not queued envelopes.

Recording/session/segment IDs are Command-owned UUIDs stored with derived tracks.
Android does not know Command recording state and sends no `recording_id` or
`segment_id`. Membership is assigned from `observed_at` against recording windows,
not a phone-supplied ID or packet receipt order.

## GNSS `fix`

| Field | Type/range | Unit/meaning |
| --- | --- | --- |
| `observed_at` | UTC timestamp | GNSS observation measurement time, not serialization or receipt |
| `fix_age_ms` | integer ≥0 | Monotonic elapsed time from measurement to `captured_at` snapshot |
| `latitude` | number [-90,90] | degrees north |
| `longitude` | number [-180,180] | degrees east |
| `horizontal_accuracy_m` | number ≥0 or null | Android horizontal accuracy estimate; unavailable means quality unknown |
| `altitude_m` | number or null | metres above WGS84 ellipsoid, no assumed mean-sea-level conversion |
| `altitude_accuracy_m` | number ≥0 or null | Vertical accuracy estimate; null if altitude unavailable |
| `speed_mps` | number ≥0 or null | metres/second reported by platform |
| `bearing_deg` | number [0,360) or null | degrees clockwise from true north |

Coordinates are all-or-nothing: no fix means `fix:null`, not zero coordinates.
Platform `has*` availability must govern nullable values. A structurally valid
fix with unknown accuracy or large age remains raw; it is not automatically valid
or accepted track. Never promise altitude/speed/bearing/accuracy on every device.
GNSS measurement timestamp comes from the platform fix; age uses elapsed-realtime
measurement data when available. If elapsed age cannot be established reliably,
do not claim a fresh fix: emit status (or SOS with null fix). No invented positions.

`observed_at` means measured; Command's `received_at` means first request ingress time, durably persisted.
Android never supplies `received_at`. `fix_age_ms` is frozen at capture, **not**
queue/transmission age and must not grow on retry. UTC clock changes can disagree
with monotonic age; preserve both, flag clock anomalies and exclude unsafe track
candidates rather than changing observation time. For live freshness consider
capture age and elapsed wall time to now with clock plausibility checks; capture
age alone cannot make a delayed packet current. For historical freshness assess
age at capture, not time spent in backlog.

## Device `health`

All fields are present and nullable where specified:

| Field | Type/range | Meaning |
| --- | --- | --- |
| `battery_percent` | integer [0,100] or null | Platform battery estimate |
| `charging` | boolean or null | Charging state where available |
| `wifi_connected` | boolean or null | Wi-Fi attachment, not proof Command is reachable |
| `wifi_rssi_dbm` | integer [-127,0] or null | Signal where permitted; do not expose platform sentinels |
| `gnss_status` | enum | `fix`, `no_fix`, `disabled`, `unknown` |
| `satellites_used` | integer ≥0 or null | Satellites used in fix when platform callback exposes it |

Location requires `gnss_status=fix`; status may report any enum (including a fix
that cannot be serialized reliably). SOS may carry an older last known fix even
if GNSS is now disabled/no_fix. Health describes capture time, not observation time.
No required SSID, BSSID, MAC address, hardware ID, cellular data, router topology,
link throughput or permission-restricted telemetry. Null means unavailable, not
failure. Command infers contact/ACK health; Android Wi-Fi attachment cannot prove it.

## SOS event reservation

For `type=sos`, `sos` is `{ "event_id": <UUID>, "triggered_at": <UTC timestamp> }`.
One saved trigger is one event/message; use `event_id=message_id` in v1. Retries
retain both. Distinct deliberate triggers get distinct identities. `triggered_at`
is detection time, `captured_at` snapshot time, `fix.observed_at` measurement time.
Send immediately with priority, saved first, through the same endpoint/outbox.
Use the freshest credible available fix; null honestly represents no usable fix.
Command transport ACK means **received**, never **human acknowledged**. Operator
acknowledgement is Command-local event state; no separate phone control channel
is specified. Hardware triggers, audible UI and SOS engine are future Issue #5.
An SOS fix never becomes a track point merely because it accompanies an alert.

## Durable ACK and deduplication

On first accepted wire message return HTTP 200 with the following ACK **only after**
the raw message/identity and first receipt time are durably committed. Track processing
may happen later; quality rejection is not transport rejection.

| Field | Type | Meaning |
| --- | --- | --- |
| `protocol_version` | integer 1 | ACK version |
| `device_id` | UUID | Echo sender |
| `message_id` | UUID | Echo logical message |
| `sequence` | integer ≥1 | Echo sequence |
| `result` | enum | `stored` or `duplicate` |
| `received_at` | UTC timestamp | Command ingress time of first durably stored request, unchanged on duplicate |
| `config` | object | Current desired complete per-device remote snapshot |

Unique logical key is `(device_id,message_id)`; also enforce unique
`(device_id,sequence)`. Retry with semantically identical known fields returns
`duplicate`, the original `received_at`, and **current** config (ACK bodies need
not be byte-identical). Ignore unknown fields and JSON key order/whitespace in
semantic equality. Reuse of either key with changed known content or different
paired ID/sequence returns 409 `identity_conflict`, no successful ACK or overwrite.
Different devices can use the same message UUID/sequence independently.
Duplicates never create another raw row, SOS alert, track point or distance edge.
Idempotency survives Command restart; do not expire dedupe before the corresponding
raw record. Raw records remain retained after Clear Recording in v1.

Android removes a message from pending delivery only after matching a valid 200
ACK version, device, message ID and sequence with result stored/duplicate.
Partial/malformed/mismatched ACK or timeout leaves it pending. Config failure after
a valid receipt ACK does not undo receipt; retain prior configuration and report
a visible config error until resolved. It is safe to deliver later messages without
waiting for a lower sequence ACK; sequence is identity/order, not cumulative ACK.

## Remote reporting configuration

Reporting intervals are integer seconds in `5..86400`, divisible by 5 (examples
5, 10, 15, 20, 30, 60, 120); the finite upper bound allows consistent validation. Local
default is 10 s. Command display-dot interval is independent and never transmitted
as a reporting override. This range/step is the deliberate Issue #4 physical-acceptance
amendment of 2026-10-07; the wire version and message structure remain v1.

ACK `config` contains exactly these known required fields:

| Field | Type | Meaning |
| --- | --- | --- |
| `authority_id` | UUID | Stable ID of Command configuration store/enrollment authority |
| `version` | integer ≥0 | Monotonic per-device remote configuration version |
| `reporting_interval_override_s` | interval or null | Explicit override; null is explicit clear |

`config_state` on each message contains the same three fields plus
`local_reporting_interval_s` and `effective_reporting_interval_s`. Before the first
Command ACK its authority is null, version 0, override null; effective=local.
ACK authority is always nonnull. Initial Command snapshot has version 0 and no
override. Every desired change (including clear) increments persisted version;
version 0 must have null override. Clearing never means omit the field or send
zero. Persist the last applied snapshot on Android; its override survives reconnect,
restart and local edits until explicitly cleared or enrollment is reset.

Effective interval = nonnull override, else **current** local interval. Changing
local interval under an active override preserves it for fallback. On an ACK from
the enrolled authority, Android adopts a strictly newer complete snapshot atomically,
recomputes effective interval and reschedules future ticks without creating catch-up
packets. Initial authority-null state adopts the first valid ACK even at version 0.
Equal versions with equal values are no-ops; equal version with different values
is a config error. Older versions must not roll back applied state. Echo actual
applied state on the next new message; historical queued snapshots stay immutable.
Command evaluates convergence from the newest `captured_at`/sequence snapshot,
not an old backlog echo. Receipt ACK is not evidence config was applied.

One configured Command authority owns the remote override for a device. Android
binds the first successful configured receiver's authority. Authority changes
require explicit operator re-enrollment/receiver change, which resets cached remote
state to null/version 0 and restores current local interval. Do not silently adopt
another authority from an ACK. Command backup/restore must preserve authority ID
and versions; lost/reset config state requires explicit re-enrollment, not rollback.
A receiver address change to the same authority retains applied config. No remote
config polling/control endpoint is needed: next report/retry ACK delivers changes;
there is no guarantee of immediate delivery while offline or at long intervals.

## Failures, retries and reconnect

Errors use `{ "protocol_version":1, "error":<code>, "message":<diagnostic> }`
with no receipt ACK/config. Diagnostics are text for logs, not UI decision keys.

| HTTP | `error` | Sender behavior |
| --- | --- | --- |
| 400 | `invalid_message` | Retain/quarantine locally; show error, no hot-loop retry |
| 404 | `unsupported_endpoint` | Retain pending; correct receiver/version configuration |
| 405 | `method_not_allowed` | Fix method; include `Allow: POST` |
| 409 | `identity_conflict` | Retain/quarantine; resolve identity corruption, never re-ID same message |
| 413 | `message_too_large` | Retain/quarantine; fix serialization |
| 415 | `unsupported_media_type` | Correct Content-Type |
| 426 | `unsupported_protocol` | Retain pending; require compatible implementation |
| 429 | `busy` | Retry, honor valid `Retry-After` delta seconds |
| 500/503 | `storage_unavailable` | No durable ACK; retry |

Unexpected HTTP failures are not ACKs; retain and surface diagnostics. Timeout,
connection failure, 429 and 5xx retry the unchanged message with exponential delay
1,2,4,8,16,30 s capped at 30 s; jitter may vary scheduling, not content. Use a finite
request timeout (current default 6 s overall, 3 s connection); no limit on transient retry count. Do not
block new current snapshots or SOS behind indefinitely failing backlog. Serialization
and storage failures must be visible rather than quietly losing observations.

Android preserves every distinct structurally valid native Location measurement in
its existing outbox. Reporting cadence controls live delivery/status, not history
resolution. Live/history reuse the same identity. SOS preempts; live attempts grant
oldest history an opportunity; there is no unrelated ACK gate. Recovery selects a
fresh committed observation when available, never creates another copy of it. A stale
observation remains historical. Satellite-status callbacks are not locations. The
negotiated batch and session extension below is the authorized Issue #4 amendment.

Command uses newest measurement for live location, newest capture for health/label/
config echo, and original `observed_at` ordering for historical tracks. Sort/rebuild
rules and half-open recording windows are frozen in
[architecture](../docs/architecture.md). `received_at` is never geographic track
order, observation time, or recording membership. Missing messages are never
interpolated. Duplicate delivery must not inflate travelled distance.

## Issue #4 collection and historical transfer amendment (2026-10-09)

Native GPS observations are collected independently of the reporting interval.
That interval controls fresh live opportunities and routine health/config reports,
not retained resolution. One original observation has one immutable message and
observation identity; selecting a delivery role never rewrites its envelope.

A new `location` may add `observation`:

```json
{"observation_id":"00000000-0000-4000-8000-000000000001","tracking_session_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","session_started_at":"2026-10-06T12:00:00.000Z","observation_sequence":1,"measurement_elapsed_ms":1000}
```

Observation ID equals message ID. Observation sequences start at 1 per durable
Android tracking session and commit with the observation. Envelope sequence
remains device-wide; status/SOS do not consume observation sequences. Session
starts survive sticky restart; explicit Stop then Start creates another session.
Distinct coordinates are not required. Repeated callbacks deduplicate only by
matching provider elapsed measurement and observation time within that session.

Routine status may add `history_progress`: tracking_session_id,
session_started_at, latest_committed_sequence, oldest_pending_sequence (nullable),
pending_observations, unresolved_sequences, known_collection_loss and measured_at.
Counts describe the phone at measured_at, not its present state at later receipt.
All UTC stamps retain v1 precision; integers retain v1 safe-integer bounds.
These extensions are optional; old queued v1 envelopes are not rewritten.

### Negotiated historical batch capability, version 1

`GET /api/v1/capabilities` advertises `historical_batch_versions:[1]`,
`observation_identity_version:1` and `maximum_batch_bytes:65536`.
Older receivers may return 404; Android then delivers original individual v1
messages and reports the missing session/completeness capability. Original wire
extensions retained by an older Command are validated/backfilled when upgraded.
There is no assumption that an older Command understands a batch.

`POST /api/v1/history`, application/json, takes:

The canonical complete request/ACK/error examples are in
[`fixtures/history-batch-v1/`](fixtures/history-batch-v1/).
The request root contains integer `batch_version:1` and `messages`, an array of
1..128 complete observation envelopes from one device/session/recovery block, within
64 KiB. Android targets one observation, 500 B, 1/2/4/8/16/20 KB; a complete
observation may exceed a small target. Never split an observation or wait to fill
an otherwise ready batch. Transport defaults are a 6-second call and 3-second
connection timeout. Individual v1 and SOS endpoints remain available.

Success: `{"batch_version":1,"acks":[...]}`, with an ordinary exact-identity v1
ACK for each entry, in request order. All entries commit atomically before ACK;
duplicate entries retain their original first received_at. Android validates
all ACKs before its atomic delivery-state update. Lost responses retry original
identities; derived quality rejection does not reject raw delivery.

Permanent entry errors return HTTP 422 with batch_version, error, entry_index,
observation_id and message. No entries of that failed batch commit. Android
retains the offending original row/error as unresolved and retries the others.
Unidentified validation failures isolate batches deterministically down to one
entry. Timeouts, malformed ACKs, 429 and 5xx are temporary, never permanent loss.
The LAN ingestion listener does not expose dashboard/control/export endpoints.

`X-GNSS-Delivery-Role: live|history` labels an individual transfer without changing
identity. History batches are historical. A role never overrides actual freshness.
Historical eligibility does not depend on another current or status ACK. Recovery
blocks are contiguous outstanding observation sequences separated by delivered
or unresolved outcomes; blocks are scheduling boundaries, not movement segments.
