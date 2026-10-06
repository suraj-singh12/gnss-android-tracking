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
