# GNSS Android Tracking

A local field-tracking system: Android devices measure GNSS positions and send
locally durable observations over Wi-Fi to a Command computer. Command interprets
observations, records tracks and presents a local dashboard. Field operation
requires no internet, SIM, cloud service or online map.

Two independent applications communicate only through
[Protocol v1](protocol/protocol-v1.md). Android owns measurement, durability and
transmission. Command owns interpretation, recording, horizontal travelled
distance and presentation. Command uses Go and local SQLite and targets macOS,
Windows and Linux from one source tree, with a portable executable and bundled
web assets; no separate runtime/database installation is intended.

| Directory | Responsibility |
| --- | --- |
| [android/](android/README.md) | Kotlin Android leader application boundary |
| [command/](command/README.md) | Portable Go Command application boundary |
| [protocol/](protocol/protocol-v1.md) | Versioned wire contract and shared fixtures |
| [test-tools/](test-tools/README.md) | Simulator/mock interfaces and contract checks |
| [docs/](docs/architecture.md) | Architecture, recording and UI decisions |

Development order: [#1 architecture/protocol](https://github.com/suraj-singh12/gnss-android-tracking/issues/1)
first; after review and merge, [#2 Android core](https://github.com/suraj-singh12/gnss-android-tracking/issues/2)
and [#3 Command core](https://github.com/suraj-singh12/gnss-android-tracking/issues/3)
in parallel; then #4 end-to-end reliability, #5 SOS, #6 UI/diagnostics/offline map,
and #7 hardening/releases.

This baseline contains contracts and scaffolding only. It does not yet run either
product. Read [architecture](docs/architecture.md), [design semantics](docs/design-system.md)
and [test-tool contracts](test-tools/README.md) before implementing them.

Contributor validation (Go toolchain only):

```sh
cd test-tools
go test ./...
```

No dependencies, product build system or release workflows are introduced here.
