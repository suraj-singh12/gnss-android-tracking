# GNSS Android Tracking

An offline-first field-tracking system. Android phones measure GNSS positions, store
them durably and send them over a local Wi-Fi network to **GNSS Command**, a portable
desktop application. Command receives and stores every observation, decides which
fixes are credible, builds tracks and travelled distance, records operator evidence and
presents a local map dashboard with recording, playback, Travel Back, SOS and phone
controls. Field operation needs no internet, SIM, cloud service or online map.

**Current release:** v1.0.0 (pre-release). See [Releases](https://github.com/suraj-singh12/gnss-android-tracking/releases)
and [Known limitations](#known-limitations).

## Components

| Directory | Responsibility |
| --- | --- |
| [android/](android/README.md) | Kotlin Android app: GNSS collection, Room durability, tracking service, reliable sender, SOS |
| [command/](command/README.md) | Portable Go Command app: durable receipt/ACK, SQLite history, quality, recording, maps, dashboard |
| [protocol/](protocol/protocol-v1.md) | Versioned Android ↔ Command wire contract and shared fixtures |
| [test-tools/](test-tools/README.md) | Phone simulator, mock receiver, contract tests and browser/bridge integration suites |
| [docs/](docs/architecture.md) | Architecture, design system, UI acceptance and approved designs |

The two applications communicate only through [Protocol v1](protocol/protocol-v1.md).
Android owns measurement, local durability and transmission. Command owns receipt,
interpretation, recording, horizontal travelled distance and presentation.

```text
Android: GNSS → foreground tracking service → Room (stored before send) → single Sender
         → local Wi-Fi → POST :8080/api/v1/messages
Command: validate → SQLite commit (before ACK, deduplicated) → quality policy → tracks,
         recordings, SOS → local dashboard http://127.0.0.1:8081
```

## Download

Each release publishes assets built by CI from the same `main` commit:

| Asset | Platform |
| --- | --- |
| `GNSS-Tracker-debug.apk` | Android 8.0 (API 26) or newer, debug-signed |
| `GNSS-Command-macos-arm64` | macOS on Apple silicon, unsigned |
| `GNSS-Command-windows-x64.exe` | Windows x64, unsigned |
| `GNSS-Command-linux-x64` | Linux x64 |
| `manifest.json`, `SHA256SUMS` | Source revision, build details and checksums |

Verify downloads with `sha256sum -c SHA256SUMS` (or `shasum -a 256 -c` on macOS).

## Quick start

1. **Command:** run the binary for your laptop (on macOS/Linux, `chmod +x` it first). Open
   `http://127.0.0.1:8081` in a browser on the laptop. Allow inbound TCP port 8080 in the
   laptop firewall on the trusted field LAN.
2. **Android:** install the APK, then in **Settings** set the party ID/name and the Command
   base URL, e.g. `http://192.168.1.10:8080` (the laptop's LAN address). Grant precise
   location and notifications, then tap **Start Tracking**.
3. Parties appear in Command's Parties panel as observations arrive.

Command flags and storage locations are in the [Command guide](command/README.md#build-and-run).
For a first real phone → Command run, follow [device acceptance](android/DEVICE-ACCEPTANCE.md).

## Build and test from source

Requirements: Go 1.24.7+, JDK 17, Android SDK platform 35 and build-tools 35.0.0.

```sh
cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain
cd command && go test -race ./... && go vet ./...
cd test-tools && go test -race ./... && go vet ./...
test-tools/integration/run.sh --console=plain   # real Android Room/Sender → Command bridge
```

Browser suites (Playwright) are described in [integration tests](test-tools/integration/README.md).
CI ([workflow](.github/workflows/issue-6-matched-assets.yml)) runs on pushes to `main` and on
pull requests to `integration/combined-upgrades`, and uploads the release assets above.

## Documentation

- [Architecture](docs/architecture.md): authorities, data flow, recording, quality, SOS, maps
- [Protocol v1](protocol/protocol-v1.md): normative wire contract
- [Android guide](android/README.md) and [Android UI development](android/UI-DEVELOPMENT.md)
- [Command guide](command/README.md)
- [Design system](docs/design-system.md), [UI acceptance](docs/ui-acceptance.md),
  [UI preservation matrix](docs/ui-preservation-matrix.md)
- [Project handoff](docs/project-handoff.md): full state and planning context
- [Agent and contributor rules](AGENTS.md)

The current interfaces follow the approved designs: Command V5.14 (mode picker, Parties,
map, Details, recording/playback rail, centred windows) and Android v3 (Tracking,
Settings, Diagnostics).

## Branches and contributions

- `main`: latest released code. New work branches from `main`.
- `integration/combined-upgrades`: combined testing branch. Feature PRs target it; the
  operator promotes it to `main` after validation.

Work is issue-first, one `devX` branch per approved issue. See [AGENTS.md](AGENTS.md).

## Known limitations

v1.0.0 is a pre-release. CI verifies software behaviour on Linux and an Android emulator,
but these are not yet verified:

- Native macOS and Windows Command runtime, including the 20+1 phone Windows run ([#23](https://github.com/suraj-singh12/gnss-android-tracking/issues/23))
- Physical Android field acceptance: GNSS accuracy, Doze/OEM behaviour, multi-day offline
  sync, enrollment, alerts and SOS volume key ([#7](https://github.com/suraj-singh12/gnss-android-tracking/issues/7))
- Physical SOS speaker output on the Command laptop
- Live map-provider downloads (the `dem-provider` CI job fails on provider refusals and limits)
- Builds are unsigned (Command) and debug-signed (Android); there is no installer

Physical-key SOS works only while the Android app is in the foreground; locked-screen
activation is unsupported.

## License

Proprietary. Copyright (c) 2026 suraj-singh12. All rights reserved. See [LICENSE](LICENSE).
