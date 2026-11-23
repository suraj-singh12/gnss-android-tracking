# GNSS Android Tracking — Project Handoff (state of `integration/combined-upgrades`)

> Prepared 23 Nov 2026 for a planning / brainstorming agent. The source of truth is always the repository at the SHA below, the current GitHub issues and `AGENTS.md`; this document is a map, not an authority. Verify any specific before acting on it.

- **Repository:** `suraj-singh12/gnss-android-tracking`
- **`main`:** latest released code. On 2026-11-23 `integration/combined-upgrades` was fast-forwarded into `main` and published as the **v1.0.0 pre-release**. The architecture below describes source at `bb0a056` (plus docs, licence and version updates after it).
- **`integration/combined-upgrades`:** combined testing branch. New work branches from `main`, PRs target integration, and the operator promotes integration to `main`. All other branches were deleted on 2026-11-23.
- **Licence:** proprietary, all rights reserved (`LICENSE`).
- **Owner/operator:** suraj-singh12. Operator approval gates design, merges, issue closure and releases.

---

## 1. What the product is and why it exists

This is an **offline-first field-tracking system** for teams in the field ("parties") who have no SIM, internet or cloud:

- **Android phones** (one per party) measure GNSS positions, store them durably on the phone and send them over a **local Wi-Fi LAN** to a laptop.
- **Command** is a portable Go application on that laptop (macOS / Windows / Linux, single executable, embedded SQLite and web assets). It receives and durably stores every observation, decides which fixes are credible, builds tracks and travelled distance, records operator-controlled evidence, and presents a local browser dashboard: live map, recording, playback, Travel Back, SOS and phone controls.

**Why it is built this way.** The design premise is truthfulness under bad conditions: intermittent Wi-Fi, phones offline for days, GNSS noise, clock anomalies and emergencies. Hence:

- Nothing is fabricated: no invented fixes, distance across gaps, "synced because HTTP 200", fresh-looking old data or fake provider results.
- Original data is immutable and retained. Quality decisions are explainable and re-derivable.
- Store before ACK on both sides. Idempotent retries.
- An SOS is durable and prioritised. *Received by Command* and *acknowledged by a human* are separate facts.
- The phone count is variable. **20 simultaneous phones is the benchmark** (plus a 21st joining), never a hard-coded cap.
- Field operation needs no internet. Online map/terrain acquisition is optional, bounded and consent-gated.

---

## 2. Repository layout

| Path | What lives there |
|---|---|
| `android/` | Single Kotlin app module `org.gnss.tracking` (minSdk 26, target/compile 35, Room, OkHttp, coroutines, native Views, no Play Services) |
| `command/` | Go 1.24.7 module; `cmd/party-tracker/main.go`; all logic in `internal/core/` (~24.6k lines incl. tests); browser UI in `web/` (vanilla JS/CSS/HTML, embedded via `assets.go`, no build step) |
| `protocol/` | `protocol-v1.md` (normative wire contract) + `fixtures/` |
| `test-tools/` | Go contract tests, `phone-simulator`, `mock-receiver`, and `integration/` (real Android Room/Sender ↔ Command bridge `run.sh` + ~27 Node/Playwright browser suites `*.cjs`) |
| `docs/` | `architecture.md`, `design-system.md`, `ui-acceptance.md`, `ui-preservation-matrix.md`, `design/approved/*` (approved prototypes), `evidence/` |
| `.github/workflows/issue-6-matched-assets.yml` | The single CI workflow (see §9) |
| `AGENTS.md` | Repository working rules for any agent (see §11) |

Key canonical docs, in their `AGENTS.md` reading order: `AGENTS.md` → the current issue/PR → `README.md` → `docs/architecture.md` → `protocol/protocol-v1.md` → component READMEs (`android/README.md`, `command/README.md`, `test-tools/integration/README.md`, `android/DEVICE-ACCEPTANCE.md`, `android/UI-DEVELOPMENT.md`). UI work also needs `docs/design-system.md`, `docs/ui-acceptance.md` and `docs/ui-preservation-matrix.md`.

---

## 3. Authority model (the most important concept)

Each concern has exactly one owner. New work must extend the owner, never add a competing database, queue, recorder, map engine or GNSS source.

| Concern | Owner |
|---|---|
| GNSS measurement, tracking session, phone-side durability, outbound transmission | **Android** (`TrackingService`, `LocationSource`, Room `Store`, single `Sender`) |
| Wire format and compatibility | **`protocol/protocol-v1.md`** |
| Durable receipt / ACK / dedupe, raw history | **Command** SQLite (`store.go`, `raw`, `observation_identity`) |
| Quality policy, chronological projection, segments, distance | **Command** (`track.go`, `history.go`, `projection_*`) |
| Recording windows (Start/Pause/Resume/Stop/Save/Discard) | **Command** (`/local/recording`, `presentation*.go`, `evidence.go`) |
| Presentation evidence (what the browser actually showed) | **Command** presentation journal (same SQLite) |
| Saved recordings / playback | **Command** (`library.go`, `playback.go`) — read-only consumers of the evidence |
| Travel Back | **Command** `travelView` — ephemeral read-only reconstruction from raw |
| SOS alert + human acknowledgement | Android SOS engine creates it; Command projects it and owns operator ACK |
| Phone instructions / alerts | Command control plane (`control.go`) → Android `CommandControl.kt` journal |
| Offline maps / terrain | Command (`offline_maps.go`, `map_*`, `terrain.go`, `dem_*`) — never feeds GNSS, recording or distance |
| UI | Presentation only: Android `MainActivity`/`NativeUi`; Command `web/*.js` (`workspace.js` is presentation-only) |

---

## 4. End-to-end data flow

```text
Android: GPS_PROVIDER (~1 s, foreground service) → LatestLocation
  → [optional #24 adaptive selector, OFF by default] → Room transaction
    (allocate per-device sequence + immutable envelope + outbox row)  ← durable BEFORE send
  → single Sender: SOS first → fresh LIVE → oldest history block → status
  → OkHttp over the Wi-Fi Network (no cellular fallback, 6 s call / 3 s connect, 1/2/4/8/16/30 s retries)
Command: POST /api/v1/messages (individual) or negotiated history batch (/api/v1/history, /api/v2/messages, /api/v1/capabilities)
  → strict Protocol v1 validation → SQLite WAL synchronous=FULL commit
    (raw wire bytes, identity, first received_at, device/contact state, projection work)  → ACK stored|duplicate
  → projection worker: quality policy → qualified points/segments/distance (checkpointed, re-derivable)
  → live state, recording windows, SOS alerts → dashboard (127.0.0.1:8081) via /local/* APIs
```

- **Ports:** phones POST to `:8080` (LAN, ingestion only). The dashboard/control listener is loopback `127.0.0.1:8081`. Flags: `-db`, `-ingest-listen`, `-dashboard-listen`, `-history-batch-bytes`. The DB defaults to `os.UserConfigDir()/party-tracker/command.sqlite`.
- **SQLite driver:** `github.com/ncruces/go-sqlite3 v0.28.0` (WASM + wazero, pure Go, CGO off).

---

## 5. Protocol v1 (wire contract) essentials

- **Transport:** one immutable JSON message per `POST /api/v1/messages`, max 64 KiB, UTC `YYYY-MM-DDTHH:mm:ss.SSSZ`, WGS84. Unknown path version → 404; wrong body version → 426; malformed → 400. Unknown fields are ignored, and duplicate JSON keys are rejected.
- **Envelope:** `protocol_version=1`, `type` ∈ `location|status|sos`, `device_id` (installation UUID, ownership key not credential), `party{id,name}` (labels, not keys), `message_id` (stable across retries), `sequence` (per-device strictly increasing, gaps legal, never reset), `captured_at`, `config_state`, `health`, `fix` (non-null for location, null for status, nullable for SOS), `sos{event_id=message_id, triggered_at}`.
- **Fix:** `observed_at` (measurement time), `fix_age_ms` (frozen at capture), lat/lon, nullable accuracy/altitude/speed/bearing (respect platform `has*`). There are no invented positions; if age can't be established, a status message is sent instead.
- **ACK:** HTTP 200 only after a durable commit: `{device_id,message_id,sequence,result: stored|duplicate,received_at,config}`. Dedupe keys are `(device_id,message_id)` and `(device_id,sequence)`. Conflicting reuse → 409 `identity_conflict`. Android removes from pending only on a matching valid ACK.
- **Extensions (additive, negotiated):**
  - #4 native-history batches (≈500 B…64 KB adaptive, ≤128 records)
  - #21/#22 complete retained history and fast foreground Sync All (incl. Tracking OFF)
  - **#25 authenticated control** (enrollment with a private credential, Keystore-sealed, replay protection, per-operation ordering; sent/received/attempted/applied/confirmed are separate facts; unknown stays unknown)
  - **#26 phone alerts** (normal/urgent, sound/vibration, durable recipient ACK)
  - #48 operator identity presentation
- Current Android ignores unauthenticated ACK `config` and needs authenticated #25 instructions. Legacy v1 clients keep legacy behaviour.

---

## 6. Command internals

### 6.1 Quality policy (revision 1 defaults)

| Rule | Default |
|---|---|
| Minimum forward / backward movement | 2 m / 2 m |
| Max horizontal accuracy | 25 m (unknown accuracy rejected while enabled) |
| Max fix age at capture | 30 s |
| Clock tolerance (UTC vs monotonic) | 5 s |
| Max uncertainty-adjusted speed | 12 m/s |
| Max gap from last accepted point | 120 s (opens a new subsegment, no connector) |
| Uncertainty multiplier | 1 (effective floor = `max(floor, k·hypot(acc_prev, acc_cand))`) |

- Every rule has an enable switch plus a retained threshold. Candidates are compared with the **last accepted** point. Direction comes from a rolling window of ≤4 accepted points.
- A policy edit re-derives the **full current recording** from unchanged raw under a new revision.
- Distance is horizontal haversine, R = 6,371,008.8 m, with longitude wrap, no altitude and no edge rounding. The policy intentionally undercounts across unknown or invalid intervals.

### 6.2 Recording model

- Recording/segment IDs are Command UUIDs; phones never know them.
- Windows are half-open `[start, stop)` in `observed_at` time. Two start modes:
  - `from_now`
  - `from_beginning`: all retained history; the alias `session_beginning` is legacy
- Pause closes the window while GNSS, LIVE, ACK and SOS continue. Resume opens a new window without a connector.
- Stop is final. Save/Discard is persisted across restarts.
  - **Save** seals an immutable snapshot (version 2 headers, participation registry, included originals) and can be repeated.
  - **Discard** retires only the unsaved selection.
- Late history measured inside a closed window rebuilds it. Cleared recordings never resurrect.

### 6.3 Presentation evidence (#27)

- A single authorised browser holds a lease: a random credential and fencing epoch, 15 s leases and 2 s heartbeats.
- The browser freezes what it actually rendered (SVG geometry, viewport, layers, panels, status, quality, SOS), confirmed after a rendering opportunity (two animation frames plus a task).
- Evidence is content-addressed with delta checkpoints and SHA-256 verified. Map/terrain responses are pinned before delivery.
- Evidence does **not** claim OS scanout or human attention. Storage limits become explicit "unverified", never silent truncation.

### 6.4 Playback and Travel Back

- **Saved playback (#37/#60):** read-only, frame-by-frame from the frozen evidence. It is ordered by presentation time, not GNSS time. Interruption groups take 4 playback seconds each. Missing assets and limitations stay visible.
- **Travel Back (#34/#61):** `/local/travel-back` reads retained raw plus the current policy in one read transaction and runs the same `Derive`. It is ephemeral, cannot be saved as evidence, and is held until Refresh. Read limits fail explicitly.
- **SOS Locate:** applies quality and clock rules to originals.
- **Locking:** an active or paused recording locks historical navigation until final Stop or Discard.

### 6.5 Other subsystems

- **SOS (#5):** projected in the raw-ingestion transaction. Operator ACK of exactly `(device_id,event_id)` is local, and the first ACK time is retained. A shared one-second audible alarm sounds until acknowledged; dialogs and notices can't acknowledge. Events are retained indefinitely.
- **Maps (#28/#49):** a WGS84 radius-circle plan, chunked (240″ grid, ≤2,048 chunks, 256 MiB quota). Sources in order:
  1. exact verified chunks
  2. Overpass
  3. Geofabrik regional PBF crop (bounded disk-partitioned node index)
  - Transfer ledger: a 5 MB initial allowance; anything larger needs explicit consent (HTTP 409 `transfer_approval_required`); 1 GB hard ceiling.
  - `.gmap.zip` standalone import/export.
  - The renderer has a shared 100k-coordinate budget.
- **Terrain/DEM:** AWS Skadi HGT tiles → `GTERR001` binary crops → hillshade, contours and elevation. Bounded budgets. Local HGT import.
- **Control (#25/#26):** `/local/control/{enroll,issue,revoke,cancel}`. Instructions are persisted before delivery. Phones poll over the same transport (Android opens no port).
- **History API:** `/local/history?device_id&layer=qualified|raw&cursor`. Pages are generation-pinned, and 409 on a generation change means restart from the first page.
- **Other `/local/*` endpoints:** `state`, `live`, `events`, `recording`, `quality`, `override`, `sos/acknowledge`, `sos-location`, `field-report` (ZIP evidence), `runtime-diagnostics`, `map-*`, `maps`, `presentation/*` (claim/confirm/status/summary/saved/library/participants/playback), `travel-back`.
- **SQLite tables (single file):**
  - core: `raw`, `observation_identity`, `receipt_roles`, `state`, `retired`, `field_evidence`
  - projection: `projection_{state,work,chunks,device_chunks}`
  - control: `control_{commands,enrollment,nonces}`
  - maps/terrain: `offline_{maps,map_chunks,map_transfers,osm_sources,osm_nodes,osm_ways,terrain}`
  - evidence: `presentation_*` (journal, blobs, assets, gc, …)
  - saved recordings: `saved_*` (recordings, presentation, originals, assets, visibility, library, library_revisions, gc…)

---

## 7. Android internals

- **Service:** a user-started location foreground service (`TrackingService`) with a timeout-bounded partial wake lock, ~1 s GPS and a 30 s native freshness rule. There is no WorkManager, background location permission or Play Services.
- **Start/Stop:** Start needs a visible user action plus preflight (`FieldPreflight`: precise location, notifications, GPS on, Battery Saver off). Stop needs confirmation. Reboot or force-stop needs Start again.
- **Room schema v8.** Tables:
  - `installation`
  - `outbox` + `outbox_summary` (derived counters)
  - `tracking_sessions`
  - `command_journal` + `command_watermark` (#25)

  Identity, sequence and envelope are written in one transaction before send. There are no destructive migrations, and ACKed rows are retained.
- **Sender:** one instance. Priority is SOS > fresh LIVE > oldest history > status. Adaptive batch ramp with RTT/goodput feedback. "Sync now" accelerates without resetting the ramp. A user-started dataSync service (`RetainedTransferService`) can drain with Tracking OFF.
- **#24 adaptive persistence:** OFF by default; consent is local or an authenticated #25 instruction. Selection rules: ≤5 s while moving, 30 s while stationary, with dense fallback on any uncertainty.
- **SOS:** a protected 1.2 s hold (`SosHoldGesture`) plus an opt-in foreground triple Volume-Up. 3 s coalescing. Locked-screen activation is unsupported.
- **UI (current = v3, #68, merged via PR #69 at `bb0a056`):**
  - Bottom tabs: Tracking · Settings · Diagnostics.
  - Tracking: one status card plus GNSS and Delivery lists.
  - Settings: a flat grouped list (Party & Command, Command access `FLAG_SECURE`, Tracking policy, Field readiness, Emergency, Appearance System/Day/Night).
  - Diagnostics: Health / Activity / Tools segments.
  - Approved prototype: `docs/design/approved/android-v3-simplified-interactive.html`. The v3 contract supersedes V4.6 where they differ.
  - Visual regression baselines and the approval ledger live in `android/app/.../visual-baseline/APPROVALS.md` + `docs/ui-acceptance.md`.

---

## 8. Command UI (current = V5.14, #66, merged via PR #67 `54b4845` + docs PR #70 `d410028`)

- **Design authority:** approved prototype `command-v5.14-interactive.html` (SHA-256 `5594847d…aec81`, approved in #34).
- **Layout:**
  - A 58 px header: identity, a centred mode picker (Live / Record / View Recording / Travel Back), Maps · Records · Tools, a Day/Night SVG icon and an SOS counter.
  - Parties panel (242 px, search, All/Online/Attention), map, Details inspector (270 px) and a bottom recording/playback rail.
- **Breakpoints:** ≤1250 the header actions go into a ☰ menu; ≤1190 Details becomes a drawer; ≤800 Parties becomes a drawer; ≤520 the identity text is hidden.
- **Windows** (`.v514-modal`, centred 720 px): Records, Tools, Maps & Layers (Get a Map / Import / My Maps / Display), Map layers, SOS emergencies, Event history, Phone controls (Send instruction / Enrollment / Results).
- **Read-only 640 px sheets:**
  - **History:** Qualified / Original tabs, paginated, raw JSON under "Page data".
  - **Recording facts:** one sheet for live, saved and Travel Back, with folded raw sections Recording state / Diagnostics / Underlying facts.
- **Travel Back** opens a Capture range window on entry: a date picker plus 24 h time per end, Exclude offline, Show all observations and inline validation.
- **Files:**
  - `index.html`, `style.css` (V5.14 layer at the end), `workspace.js` (shell/presentation)
  - `app.js` (live, map, history, quality), `recording.js`, `playback.js`, `library.js`, `control.js`, `map.js`, `terrain.js`, `sos-alarm.js`
  - The CSP forbids inline styles.
- **Accessibility:** axe WCAG 2.1 AA in the browser suites. Day secondary text is `#596970` (the prototype's `#6d7d80` failed contrast).

---

## 9. Verification and CI

**Local entry points:**
```sh
cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain   # unit tests ~15–20 min (forkEvery=1)
cd command && go test -race ./... && go vet ./...
cd test-tools && go test -race ./... && go vet ./...
test-tools/integration/run.sh --console=plain       # real Android Room/Sender → Command bridge (skips ≠ pass)
node test-tools/integration/<suite>.cjs             # Playwright; env GNSS_COMMAND_BINARY, GNSS_PLAYWRIGHT_MODULE, GNSS_AXE_MODULE, GNSS_CHROMIUM, GNSS_SCREENSHOT_DIR
```

**CI** (`issue-6-matched-assets.yml`, on pushes and PRs to `integration/combined-upgrades`):

| Job | What it proves |
|---|---|
| `integrated` | Ancestry/cleanliness; Android tests/lint/APK; Go tests; measured simultaneous sync; control under saturated history + SOS/LIVE; the real bridge; cross-builds; browser suites; checksummed manifest |
| `map-software-acceptance` | Deterministic map acquisition, failure/recovery, offline restart |
| `dem-provider` | **Live** public provider acquisition (DEM, Overpass, Geofabrik) |
| `android-emulator-ui` | Emulator capture tour |
| Devin Review | Automated code review |

- **Artifacts** from one SHA: the Android debug APK; Command macOS-arm64, Windows-x64 and Linux-x64 binaries; acceptance evidence (manifest, checksums, screenshots); test reports.
- **Known CI behaviour:**
  - `dem-provider` fails persistently because the providers refuse connections, return HTTP 502 or exceed the 30 MB CI transfer budget. This is external and has been seen on many PRs.
  - Android `SosActivityTest.protectedAccessibilityAttemptCancelsOnPauseButCommittedActivationSurvivesDestroy` has timed out intermittently in CI.
  - #55 tracks a different SOS retry-jitter test.

---

## 10. History: how we got here

| Wave | Issues → PRs (merged into integration unless noted) |
|---|---|
| Foundations (Oct 6–7) | #1 architecture/protocol (PR #8), #3 Command core (#9), #2 Android core (#10) — these reached `main` |
| Reliability & SOS (Oct 9–15) | #4 end-to-end reliability, #5 SOS, #21 historical reconstruction, #6 operational UI/maps/terrain (closed); reconciliation merges `d78ef85` |
| Sync & capacity (Oct 17) | #22 fast durable Sync All (PR #35), #23 LIVE health/polling (PR #36) |
| Phone control (Oct 20–21) | #24 adaptive persistence (PR #40), #25 authenticated control (PR #41), #26 phone alerts (PR #42) |
| UI + maps (Oct 22–26) | #28 automatic offline maps (PR #43, #45), #39 Android redesign (PR #44), #49 offline maps + V3 popup (PR #50), #47 switch flicker (PR #51), #48 identity/enrollment UX (PR #52) |
| Evidence (Oct 27–Nov 3) | #27 recording evidence (PR #53), #55-adjacent SOS retry determinism (PR #56), #37 saved recordings + playback (PR #60), #34 operational modes + Travel Back (PR #61) |
| Latest UI (Nov 21–23) | #66 Command V5.14 (PR #67, docs PR #70) — **#66 closed**; #68 Android v3 (PR #69) — **#68 closed** |

---

## 11. Working rules any agent must follow (from `AGENTS.md`)

- Branch per issue from the **latest** `integration/combined-upgrades`. PRs target that branch, **never `main`**.
- **No merges, issue closures, releases, force-pushes or branch deletions without the operator's explicit, specific authorisation.** "Proceed" or green CI is not authorisation for `main`.
- **UI:** design first, show it, get operator visual approval, then implement. Update the owning docs (`design-system`, `ui-acceptance`, `ui-preservation-matrix`) rather than creating parallel docs.
- **Reporting:** keep native/physical/field limitations explicit. A Linux cross-build or browser run is not macOS, Windows or Android field proof. A skipped bridge test is not a pass.
- **Performance:** benchmark at 20 phones (+1 late join), with measured baselines, not speculation.

---

## 12. Current state: what is open

### 12.1 Open issues (4, after the 2026-11-23 triage)
| # | Topic | Status |
|---|---|---|
| 7 | Cross-platform field acceptance, endurance, release gates | Umbrella. Holds the remaining physical/native/provider acceptance from the closed software-merged issues #24–#28, #34, #37, #39, #47–#49 |
| 23 | Windows Command native acceptance (20+1 fleet) | Software fix merged (PR #36); native 2-hour 20+1 run not done |
| 38 | Portable recording packages (offline map assets, export/import, encryption) | Not started |
| 13 | Optional read-only LAN feed of qualified locations | Deferred, not started |

All other issues were closed on 2026-11-23 with comments linking the PRs that completed or replaced them.

### 12.2 Open PRs
None. Stale PRs #17–#20, #32, #33, #58 and #63 were closed as superseded on 2026-11-23.

### 12.3 Unverified / release blockers
1. Native **macOS and Windows** Command runtime (only cross-builds plus Linux browser runs exist).
2. **Physical Android** acceptance: GNSS field accuracy (dense vs adaptive), screen-off/Doze/OEM kill, 1–7-day offline sync, enrollment/Keystore, alerts audio/vibration, SOS volume key, Large fonts/TalkBack (`android/DEVICE-ACCEPTANCE.md` lists each as NOT TESTED).
3. **Physical SOS speaker** output on the Command laptop.
4. **Live map-provider downloads** (`dem-provider`).
5. **Sustained 20+1 fleet / multi-day endurance and storage growth** (no retention policy; evidence journal growth unmeasured at multi-day scale).
6. No signing, installers or release automation; v1.0.0 ships a debug-signed APK (`versionName 1.0.0`) and unsigned Command binaries.

### 12.4 Small known UI items (Command V5.14)
- At ≤520 px the map toolbar wraps to two rows.
- At 390 px History rows put the distance on a second line.
- "Catching up history…" can flicker while data arrives.
- The Travel Back invalid-time message was seen persisting until Apply; recheck it.
- Playback party filters treat "has a qualified position at the playhead" as Online. This product decision is still open.

---

## 13. Suggested planning themes (for brainstorming)

1. **Release readiness:** v1.0.0 is a pre-release. Next: release signing (APK keystore, macOS notarisation, Windows code signing), installers and a tag-driven release workflow (#7). Decide the gating set: native Windows/macOS runs, physical phone runs, 20+1 endurance.
2. **Acceptance tracking:** turn the #7 umbrella into a concrete, scripted field test plan (see theme 4).
3. **CI health:** make `dem-provider` non-blocking or replace it with a scheduled job. Fix the flaky `SosActivityTest`.
4. **Field acceptance campaign:** a scripted physical test plan from `DEVICE-ACCEPTANCE.md` covering #24/#25/#26/#22/#5, plus a Windows native 20+1 run (#23).
5. **#38 portable recording packages:** the next evidence feature (export/import of saved recordings with pinned map assets, plus encryption).
6. **Storage lifecycle:** retention/export policy for raw, evidence and maps; disk monitoring on both sides (currently "future work").
7. **Map reliability:** provider fallbacks and an offline-first map distribution story (pre-packaged `.gmap.zip`).
8. **Small UX polish:** the items in §12.4.

---

## 14. Glossary
- **Party:** a field unit/phone (label only; `device_id` is the key).
- **Raw / Valid / Track (Qualified):** every received observation / quality-passing / accepted movement points.
- **Projection:** the derived qualified track (checkpointed, re-derivable).
- **Recording window:** a half-open observation-time interval owned by Command.
- **Presentation evidence:** a journal of what the browser actually rendered.
- **Travel Back:** an ephemeral reconstruction from raw over a capture range.
- **LIVE:** the current newest-measurement view.
- **SOS received vs acknowledged:** transport receipt vs human operator ACK.
- **GTERR001:** the Command terrain container format.
- **`.gmap.zip`:** a standalone offline map archive.
