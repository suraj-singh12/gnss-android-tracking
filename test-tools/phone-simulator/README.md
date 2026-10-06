# Phone simulator interface (implementation in #3)

Portable developer tool taking a Command base URL and a scenario JSON path (fixture
paths are relative to the scenario). Emit each referenced request at its scripted
`after_ms` delay using `POST /api/v1/messages`, unchanged. Check ACK identity/result
against expected outcomes; allow ACK config to change on retry. Report failures and
exit nonzero. Never send to a network destination merely by running contract tests.

Input contract: `{ "name": string, "steps": [{ "after_ms": nonnegative integer,
"request": relative JSON path, "ack": relative JSON path }] }`. Delays are measured
from the previous step, not measurement time. A fixture ACK supplies expected receipt
semantics; real Command sets actual receipt timestamps and authority/config, so do
not compare those deployment-specific values byte-for-byte.

Support arbitrary fixture `device_id` values, location/status/SOS, explicit duplicate
steps, current-first backlog and config echoes. Add scripted transport loss/retry
controls when implementing. Keep observation time distinct from wall execution time;
provide a consistent optional whole-scenario timestamp shift for recording tests.
Never synthesize geographic points. [Fixtures](../../protocol/fixtures/README.md)
are golden examples; [wire contract](../../protocol/protocol-v1.md) wins.

## Running

From `test-tools`, with a development Go toolchain:

```sh
go run ./phone-simulator -url http://127.0.0.1:8080 -scenario ../protocol/fixtures/scenario-reconnect.json
```

Each scenario assumes a fresh Command database. `-device-id` replaces device UUIDs
throughout a scenario; omit it to preserve multiple devices. `-shift 6h` shifts all
request timestamps consistently without changing geography or fix age. Add
`-config-controls http://127.0.0.1:8081` for config fixtures: the harness changes
desired snapshots through the local API and substitutes the actual authority in
new config echoes. Without it, current ACK config is validated but deployment
values are not compared. Retry payloads are cached and remain immutable.

A step may add `"drop_response": true` to discard its first successful ACK and
retry unchanged, expecting `duplicate`. Transient network/429/5xx failures retry
up to `-retries` (default 3) at `-retry-delay` (default 1 s). Nontransient errors,
malformed/mismatched ACKs and changed duplicate receipt times exit nonzero.
HTTP timeout is 10 s. This bounded test runner is not Android's persistent sender.
