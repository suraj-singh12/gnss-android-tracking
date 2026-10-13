# Independent test-tool contracts

Independent development tools plus the real Android/Command integration suite.

- [Phone simulator](phone-simulator/README.md): tests Command without Android.
- [Mock receiver](mock-receiver/README.md): tests Android without Command.
- [Protocol fixtures](../protocol/fixtures/README.md): shared known messages/ACKs and scenarios.
- [Integration](integration/README.md): production Android Room/Sender ↔ real Command HTTP/SQLite.
- `contract/`: Go standard-library regression checks for fixture shape, ranges,
  ACK correspondence, config precedence, scenario references and local Markdown links.

Run from this directory with a Go 1.22+ contributor toolchain:

```sh
go test ./...
go vet ./...
```

This isolated module is test tooling, not a product dependency or shared application
runtime. No third-party packages or schema tooling. Both applications also
use these fixtures in their own serialization/parser tests. Go shape checks alone
cannot prove interoperability; run `integration/run.sh` for real boundaries.
Keep wire behavior in the canonical protocol rather than duplicating architecture
in simulator internals. New incompatible behavior requires a reviewed contract change.
