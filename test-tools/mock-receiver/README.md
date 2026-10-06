# Android-independent mock receiver

Standard-library Go test tool. No Command tracks, SQLite, dashboard or phone
control channel. **ACKs use test-memory storage and do not survive restart.**

From `test-tools/` (Go 1.22+):

```sh
go test ./...
go test -race ./mock-receiver/...
go vet ./...
go run ./mock-receiver/cmd/mock-receiver -listen 127.0.0.1:8080
# Real phone: explicitly bind your computer's LAN IP, and allow its firewall port.
go run ./mock-receiver/cmd/mock-receiver -listen 192.168.1.10:8080
```

The CLI emits captured valid requests/results as JSON lines on stdout (redirect
to a file for inspection), with startup/errors on stderr. Request bodies are raw
JSON objects, not base64. Capture output is a test log, not durable Command storage.

Only `POST /api/v1/messages` is exposed. Validation rejects malformed known fields,
duplicate JSON keys, unsupported versions, oversized bodies and identity conflicts.
Dedupe compares known semantic content, ignoring key order, whitespace, additive
fields and equivalent numeric spellings. Duplicate ACKs preserve first receipt
and return current config. Different devices have separate identity namespaces.

`-authority` selects a stable test UUID. `-script path.json` loads per-device
initial configs and a sequence of fault/ACK steps; startup errors exit nonzero.
Each structurally valid request consumes one step. After steps end, normal store +
ACK continues. No HTTP configuration endpoint exists.

Example script (replace the config key with the phone's device UUID from captured
requests; a step config can test any device without knowing its UUID):

```json
{
  "steps": [
    {"status": 503},
    {"drop_after_store": true},
    {"config": {"authority_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "version":1, "reporting_interval_override_s":30}},
    {"config": {"authority_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "version":2, "reporting_interval_override_s":null}},
    {"config": {"authority_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "version":1, "reporting_interval_override_s":30}}
  ]
}
```

A step config is a one-response replay, allowing deterministic stale/equal/new
versions or different-authority ACKs. `configs` is a device UUID to config map;
it sets defaults after scripted replay ends. `status:429` supports `retry_after`
delta seconds. `status:409` forces an identity-conflict error. Actual changed
identity content also returns 409. `drop_after_store` stores, then closes the
connection without a response; retry returns duplicate. Version 0 requires null
override. Equal-version conflicting overrides are valid test replays.

Go harness: `New(authority, clock)`, `SetConfig(device, config)`, `Queue(steps...)`,
`Captures()`, `Count()`. Controls are local Go APIs, not a phone protocol. Captures
include immutable request bytes, result, first receipt and response status for
assertions. Clock injection freezes receipt time. Handler and harness are
concurrency-safe. Tests cover golden requests, dedupe, response loss, 503/429,
identity conflicts, config replay, capture immutability and malformed input.
Android's tests use the same canonical fixtures for ACK/parser/serializer behavior
and independently cover save-first, retry identity, config and current-first recovery.

See [fixtures](../../protocol/fixtures/README.md) and
[Protocol v1](../../protocol/protocol-v1.md). This tool is not a production receiver.
