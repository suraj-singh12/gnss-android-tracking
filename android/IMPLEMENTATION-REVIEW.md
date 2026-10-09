# Issue #2 reliability self-review

The Issue #2 matrix below records its original baseline. The dated Issue #4
amendment supersedes snapshot-only history, the ACK eligibility gate and the
original prohibition on session/batch metadata. Current behavior is documented in
[architecture](../docs/architecture.md), [Android usage](README.md) and
[device acceptance](DEVICE-ACCEPTANCE.md). These are
implementation/automated-test findings, not claims of physical acceptance.

| Check | Finding/evidence |
| --- | --- |
| A: Screen off | User-started location foreground service owns GPS callbacks/reporting; ongoing notification; hardware/OEM acceptance remains required. |
| B: Scheduling | No WorkManager, alarms or boot auto-start. Issue #4 adds a service-owned, timeout-bounded partial wake lock renewed only during active tracking. Reporting and one sender run under the same service. |
| C: Save first | Room transaction validates/serializes, allocates sequence and inserts outbox. Rollback-on-insert-failure test verifies both writes roll back; transport receives only committed rows. |
| D: Sequence | Single installation row, transactional allocation, wire bound check, concurrent allocation/reopen tests; reset creates new UUID. Cloud/device transfer excluded to prevent restoring an old sequence. |
| E: Retry identity | Sender posts stored JSON; drop-response/duplicate test verifies unchanged payload, ID, sequence, timestamp and config. |
| F: Current priority | Reserved SOS first, newest current above delivered boundary, oldest eligible backlog; new reports interrupt drain. Recovery saves current if stale. Connectivity restoration clears persisted retry deadlines only for pending, non-quarantined rows under the sender mutex before priority selection; fresh saved current is reused. Normal backoff and permanent pauses remain intact. One HTTP call has a total 10-second deadline. |
| G: Local live state | Only native location callbacks update LatestLocation, ordered by monotonic observation time. ACK/backlog never update local GNSS. |
| H: ACK safety | Strict parser rejects duplicate keys, malformed receipt, wrong identity/version/sequence/result. Repository validates identity again. Only valid HTTP 200 receipt clears pending. |
| I: Config versions | New applies transactionally; old ignored; equal conflict visible; invalid config preserves prior state. Override/local fallback and restart tests cover this. |
| J: Authority | Different authority rejected until explicit confirmed re-enrollment. Address edit retains authority. Late successful/error responses are gated by endpoint generation. |
| K: Cadence | Local and remote fields persisted separately; effective recomputed. UI shows all applicable values; newer applied config reschedules future reports without catch-up bursts. |
| L: Nullability | Native has-flags, finite/range validation and elapsed measurement age. Missing/stale fixes produce status; all nullable wire fields present. Golden fixtures cover location/status/SOS reservation. |
| M: Android rules | Precise + coarse request together; location FGS permission/type; immediate foreground promotion; API 26–28 overload guard; optional notifications on 33+; no background-location permission. Lint checks supported API access. |
| N: Dependencies | One module; Room/KSP, coroutines, Gson, OkHttp only for persistence, execution, wire format and total HTTP deadline. Native Views/GPS; test-only JUnit/Robolectric. |
| O: Boundaries | Product files only android; Go mock only test-tools/mock-receiver. Command, shared contracts/tests, docs and root README untouched; #3 branch never inspected. |
| P: Wire semantics | Golden fixture roundtrip, ACK parsing and Go contract regression. No recording/session IDs, invented health, control polling or protocol changes. SOS workflow remains reserved for #5. |

ACKed snapshots are retained and pending/quarantined snapshots are never evicted.
Storage exhaustion is an explicit operational error; retention/export controls
remain future work. UI is English and operational, with 48 dp controls, labels,
scalable text and inset handling. Last ACK reflects local successful contact time;
first remote receipt time remains separate delivery metadata.

Build/unit/lint results are reported in the PR. Follow
[device acceptance](DEVICE-ACCEPTANCE.md) for locked-screen, 30+ minute, Wi-Fi and
process/OEM validation. No device/emulator is attached in the cloud environment.
