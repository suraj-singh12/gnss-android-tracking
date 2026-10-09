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

Both product cores are implemented. For the first real Android → Command LAN run,
follow [field acceptance](android/DEVICE-ACCEPTANCE.md). Read
[architecture](docs/architecture.md), [design semantics](docs/design-system.md) and
[automated integration](test-tools/integration/README.md) for system boundaries and verification.

Protocol/test-tool validation (Go toolchain):

```sh
cd test-tools
go test ./...
```

Run [Android validation](android/README.md), [Command validation](command/README.md)
and `test-tools/integration/run.sh` as well. Real-device acceptance remains required;
no release/signing workflow is provided.

Issue #5 adds durable SOS through the same tracking outbox and LAN receiver, with
persistent Command alerts and separate operator acknowledgement. Read the SOS
sections in the existing Android/Command runbooks. Physical volume keys are limited
to the foreground Android Activity; locked-screen activation is unsupported.
Issue #4 remains under physical acceptance on its frozen candidate; do not install
SOS builds during that testing or merge either branch automatically.
