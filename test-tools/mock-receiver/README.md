# Mock receiver interface (implementation in #2)

Portable developer tool bound to an explicit listen address/port (loopback default;
explicit LAN bind for real Android). Implement only `POST /api/v1/messages`, Protocol
v1 validation/dedupe and ACK shape. Capture requests for test assertions, never
pretend to be the Command track engine. Test-memory storage is allowed with an
explicit warning that mock ACKs do not provide production durability across restart.

Input: listen address, stable test `authority_id`, initial per-device config snapshots,
and scripted fault steps. Expose fault/config controls only through a local test
harness interface, **not a second phone protocol**. Required controls in #2:
drop response after storing, transient 503, duplicate replay, identity conflict,
newer override, clear override and stale/equal-version ACK replay. Output: captured
requests, first receipt/duplicate results and assertion failures. Startup/config
errors exit nonzero; deterministic tests inject clock and storage.

Use [fixtures](../../protocol/fixtures/README.md) to check serialized fields/ACK
handling. Test save-before-send, unchanged retry identity, null fix/status,
current-first recovery, config echo after override/clear, and authority mismatch.
Do not add SQLite, track filtering or a dashboard just to mock Android's endpoint.
