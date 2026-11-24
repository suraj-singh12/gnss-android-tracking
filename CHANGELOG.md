# Changelog

All notable changes to this project are recorded here. Versions follow
[Semantic Versioning](https://semver.org/).

## [1.1.1] — 2026-11-24 (pre-release)

### Changed
- Licence: the project is now open source under the GNU General Public License v3.0
  only (`GPL-3.0-only`), replacing the proprietary licence (#81). v1.0.0 and v1.1.0
  keep the licence they shipped with.
- Added `THIRD_PARTY_NOTICES.md` listing bundled dependencies and their licences, all
  GPL-3.0-compatible.
- Android `versionName` 1.1.1, `versionCode` 3. No product behaviour change.

## [1.1.0] — 2026-11-24 (pre-release)

[Release assets](https://github.com/suraj-singh12/gnss-android-tracking/releases/tag/v1.1.0)
were built by CI from `release/v1.1.0` at `e9221d6`.

### Android (GNSS Tracker 1.1.0, versionCode 2)
- Tracking home: Needs attention row, Position tiles (Fix, Accuracy, Last fix, Battery)
  and one Delivery card; Sync now only while data waits (#78).
- Settings: grouped rows with values; Tracking policy owns the segmented interval,
  Storage and In effect; Command access Identity, Evidence and Change access groups (#73).
- Advanced logs: grouped selectable rows with Copy per section (#73).
- Settings/Diagnostics margin fixes and shorter text (#78).

### CI
- APKs from `main`, `dev` and `release/**` are signed with a permanent field key, so
  later versions install over 1.1.0 (#75).
- Live map-provider checks (`dem-provider`) warn instead of fail (#73).

### Upgrading
- 1.0.0 used a temporary key: sync each phone, uninstall 1.0.0 once, then install 1.1.0.

### Known limitations
- Native macOS and Windows runtime, physical Android field behaviour and live map
  provider downloads are not yet verified.
- The APK is a debuggable build; Command binaries are unsigned and have no installer.

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
