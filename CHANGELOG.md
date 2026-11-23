# Changelog

All notable changes to this project are recorded here. Versions follow
[Semantic Versioning](https://semver.org/).

## [1.0.0] — 2026-11-23 (pre-release)

First release of the offline-first GNSS field-tracking system.
[Release assets](https://github.com/suraj-singh12/gnss-android-tracking/releases/tag/v1.0.0)
were built by CI from `release/v1.0.0` at `f08f8c3`.

### Android (GNSS Tracker 1.0.0)
- Native `LocationManager` GNSS collection in a foreground tracking service.
- Room storage before send, a single reliable sender with idempotent retries and
  Wi-Fi-bound LAN transport.
- Optional adaptive GNSS persistence, SOS with physical-key activation, Command alerts
  and authenticated Command control.
- v3 interface: Tracking, Settings and Diagnostics.

### Command
- Protocol v1 receiver that commits to SQLite before ACK, with duplicate detection.
- Quality policy, chronological tracks and horizontal travelled distance.
- Recording windows with pause, resume, stop, save and discard; saved-recording
  library and read-only playback; Travel Back reconstruction from retained originals.
- SOS receipt with separate operator acknowledgement, phone alerts and phone controls.
- Offline vector maps and terrain with custom-radius acquisition.
- V5.14 workspace: mode picker, Parties, map, Details, recording/playback rail and
  centred windows.

### Known limitations
- Native macOS and Windows runtime, physical Android field behaviour and live map
  provider downloads are not yet verified.
- The APK is debug-signed; Command binaries are unsigned and have no installer.
