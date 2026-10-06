# Independent test-tool contracts

Issue #1 establishes interfaces and regression material only. No receiver/server,
simulator product, track filter or SQLite implementation is supplied yet.

- [Phone simulator](phone-simulator/README.md): future #3 tests Command without Android.
- [Mock receiver](mock-receiver/README.md): future #2 tests Android without Command.
- [Protocol fixtures](../protocol/fixtures/README.md): shared known messages/ACKs and scenarios.
- `contract/`: Go standard-library regression checks for fixture shape, ranges,
  ACK correspondence, config precedence, scenario references and local Markdown links.

Run from this directory with a Go 1.22+ contributor toolchain:

```sh
go test ./...
go vet ./...
```

This isolated module is test tooling, not a product dependency or shared application
runtime. No third-party packages or schema tooling. Both applications must also
use these fixtures in their own serialization/parser tests; Go shape checks cannot
prove their future implementations or the not-yet-built track engine correct.
Keep wire behavior in the canonical protocol rather than duplicating architecture
in simulator internals. New incompatible behavior requires a reviewed contract change.
