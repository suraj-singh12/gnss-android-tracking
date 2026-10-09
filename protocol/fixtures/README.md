# Protocol v1 regression fixtures

All `.json` files here are valid complete examples. Files prefixed `ack-` are ACKs;
`scenario-` files are test-harness scripts, not wire messages. All other JSON files
are requests. Scenario request/ACK paths resolve relative to this directory.

| Case | Files | Expected semantics |
| --- | --- | --- |
| Normal fix | `location-normal.json`, `ack-stored.json` | Save first, ACK after durable receipt |
| Poor/stale | `location-stale-poor.json` | Preserved raw, not automatically valid/track |
| No fix | `status-no-fix.json` | Honest null fix/optional health |
| Retry | `ack-duplicate.json` | Replay exact normal request, same first receipt, one logical row |
| Remote override/clear | `scenario-config.json` | Version 1 overrides to 30 s; local changes to 20 s; version 2 clears to 20 s fallback |
| Five-second override | `location-five-second.json`, `ack-five-second.json` | Local 15 s, applied override/effective 5 s; default remains 10 s |
| Current-first backlog | `scenario-reconnect.json` | Arrival 5,4,1,1; chronology 1,4,5; repeated 1 adds nothing |
| SOS | `sos-fix.json`, `sos-no-fix.json` | Event timestamp distinct from fix; no fix allowed; transport ACK only |
| Dynamic identity | `scenario-dynamic-sos.json` | New device may reuse sequence/message UUID; label edit keeps device ownership |

The reconnect example has fresh-at-capture old points, unlike the stale-poor
example. Final historical chronology must be identical for any delivery permutation.
Coordinates are illustrative, not guaranteed accepted movement at the future engine's
quality settings. No fixtures assert filtering/distance output before that engine exists.

Static UTC timestamps are intentional. Actual receiver assigns `received_at`; test
harness may inject time or shift a whole scenario consistently. ACKs paired with
requests must echo identity, but config may differ because it is current at ACK time.
Native fixtures carry additive Android tracking-session identity; legacy examples remain unchanged. Each scenario is
an independent test with an empty test store; do not concatenate them as one history.

Run [contract checks](../../test-tools/README.md) to detect field/type/range drift,
config precedence, duplicate identity and scenario reference errors. Both future
applications must consume these examples in serializer/parser regression tests.

`location-native-session.json` and `status-history-progress.json` exercise optional
native identity and timestamped queue progress. `history-batch-v1/` contains the
negotiated batch request, ordered ACK and indexed permanent error; those files are
batch wire examples, not individual messages or simulator scripts.
