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


## Acceptance binary provenance from linked worktrees

Use the existing matched-assets workflow and its pinned Go 1.24.7 for acceptance
builds. That Go version detects Git roots by a `.git` directory; a linked worktree's
`.git` file can yield binaries without VCS fields even with `-buildvcs=true`.
Check `go version -m BINARY` for the exact `vcs.revision` and `vcs.modified=false`
before calling assets source-matched.

For a local acceptance build, reuse the existing issue branch in a clean, full build
clone, detached at the verified commit. This leaves the implementation worktree and
its branch untouched and does not create another implementation branch:

```sh
git clone --shared --no-checkout --single-branch --branch "$issue_branch" "$source_repo" "$build_checkout"
git -C "$build_checkout" checkout --detach "$source_sha"
```

Use an existing local repository for `source_repo` and an isolated build directory.
Build there using the workflow's normal commands, then verify both Command revisions,
APK DEX revision/signature and the checksum manifest. Keep exact-source test results,
local builds, CI results and physical/native acceptance distinct in
[the canonical ledger](../docs/ui-acceptance.md).
