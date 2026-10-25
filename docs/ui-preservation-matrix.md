# Issue #6 functionality preservation matrix

## PR #17 extension · starting SHA 9eee532

All original rows below remain applicable. No acquisition/storage/delivery/quality/
recording/SOS ownership changes are introduced by terrain or themes.

| Capability                      | Previous location          | Extended location                                      | Authoritative API/state                                                      | Regression                                                                       |
| ------------------------------- | -------------------------- | ------------------------------------------------------ | ---------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| Recording start modes           | Permanent toolbar selector | Explicit Start modal, Current Time default             | Existing recording action/modes/windows; read-only session-availability flag | Browser cancel/errors/both modes; original Go/bridge recording tests             |
| Stop/Resume/Clear               | Recording toolbar          | Same centred toolbar                                   | Existing recording state machine                                             | Existing browser, Go and bridge suites                                           |
| Vector maps/import/export       | Map & layers               | Same library/downloader/import/export                  | Existing offline_maps/renderer; vector bytes unchanged                       | Existing OSM fixture/browser tests; terrain isolation/export test                |
| Terrain (new)                   | Not available              | Local DEM preparation and Layers beside Focus selected | Additive offline_terrain binary grid; no tracking dependency                 | HGT guards/roundtrip/restart/delete, Node processing and browser offline restart |
| Appearance (new)                | Day only                   | Android Settings; Command header                       | Independent native/browser preference                                        | Native resources/renders, browser persisted Night restart/axe                    |
| Header status/party count       | Separate top-right status  | Beneath identity, independent connected/total parties  | Existing contact policy/state                                                | Browser DOM placement and server outage/reconnect                                |
| About (new)                     | Not available              | Header beside SOS/Day-Night                            | Static exact project/developer information                                   | Browser content/Escape/accessibility                                             |
| SOS/Physical Button/diagnostics | Existing UI                | Same functions in both palettes                        | Existing engine/journal/adapter/alarm/ACK                                    | All previous tests retained; native Day/Night renders                            |

Automatic DEM acquisition now reuses HGT processing and the same terrain table;
vectors save before the independent terrain acquisition/retry. Real provider,
alignment and offline evidence is separate from local import fixtures. See Command README.

## Corrective audit · baseline 7725412

The corrective branch starts from the validated PR #16 head. The existing rows
below remain the feature inventory. The following mappings guide the correction;
final regression evidence is recorded in `ui-acceptance.md`.

| Capability                                                         | Current location             | Corrective location                                | API/state                               | Regression                                                  |
| ------------------------------------------------------------------ | ---------------------------- | -------------------------------------------------- | --------------------------------------- | ----------------------------------------------------------- |
| Protected SOS and local/durable receipt                            | Tracking long-click dock     | Tracking hold-progress dock                        | activateSos / Room observeSos           | SosHoldGestureTest, SosActivityTest, CommandIntegrationTest |
| Settings, readiness, permissions, battery exceptions, reenrollment | Flat Settings list           | Labelled native sections and advanced enrollment   | FieldPreflight / repository.settings    | FieldPreflightTest, UiNavigationVisualTest                  |
| Diagnostics, sender/storage/queues, SOS details, retry, export     | Technical text and tools     | Readable summaries, expandable raw evidence, tools | existing recorder / Room flows          | DiagnosticsExportApi26/28/35Test, PhysicalButton tests      |
| Parties, selected evidence, override, visibility                   | Permanent left/right panels  | Collapsible sidebar and contextual detail          | /local/state, /local/override           | ui-dashboard.cjs                                            |
| Recording modes/lifecycle/duration                                 | Right-aligned controls       | Geometrically centred toolbar                      | /local/recording, authoritative windows | ui-dashboard.cjs, Go recording tests                        |
| Active SOS / audio / locate / per-event ACK                        | Large top banner             | Header counter, overlay drawer and arrival toast   | sos_alerts / /local/sos/acknowledge     | sos-alarm-test.cjs, sos-dashboard.cjs                       |
| Ordinary evidence and acknowledged SOS                             | Export / selected disclosure | Separate Event History drawer + export             | existing field_evidence / sos_alerts    | event history API and browser tests                         |
| Quality eight rules / raw observations / dot interval              | Quality and map dialogs      | Header tools, existing dialogs                     | /local/quality, raw_points              | ui-dashboard.cjs                                            |
| Blank/offline map, import/opacity/coverage                         | Map dialog, memory only      | Map preparation/library dialog, same renderer      | GeoMap / existing Command SQLite        | map-test.cjs, offline_maps_test.go                          |
| Live / stale / historical / provisional locations                  | Circle markers and paths     | Course arrow only for credible moving live fixes   | existing fix bearing_deg / speed_mps    | marker tests / browser pan/zoom                             |

Rendered baseline evidence: Command empty, five parties, recording, quality/map
dialogs, invalid map, mixed stale/offline state and delayed SOS. The SOS banner
reduces map height; recording controls are not centred; selected details repeat
static/technical information. Android's native long-click adapter delegates timing
and cancellation to Button, without a visible hold state or explicit pause cleanup.
Command's audio is throttled to ten seconds, and map imports do not survive restart.

Baseline: `b23b5f58845c896e7fb2c5800e577202667c1e3f`; branch
`issue-6-integrated-ui` directly descends from PR #12 (`issue-5-sos`), which
contains PR #11 native observation/history work. Neither dependency is merged.

| Existing action/state                                               | Authoritative implementation                              | Redesigned location                                                                            |
| ------------------------------------------------------------------- | --------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| Start/Stop phone tracking; permission-gated pending Start           | MainActivity preflight → TrackingService / finishTracking | Tracking primary action + setup guidance                                                       |
| Party ID/name, Command URL, local interval                          | repository.settings transaction                           | Settings                                                                                       |
| Effective interval and Command override                             | persisted config / ACK                                    | Settings / Diagnostics                                                                         |
| Precise location, GPS, notifications, battery/background settings   | FieldPreflight / Android Settings intents                 | Tracking setup guidance                                                                        |
| Retry saved messages                                                | repository.retryDelivery                                  | Diagnostics, confirmed                                                                         |
| Different Command enrollment                                        | repository.settings(reenroll=true)                        | Settings, confirmed                                                                            |
| Native GNSS freshness/accuracy/age; link/ACK; battery/queues/errors | Operational + Room flows                                  | Tracking summary; detailed evidence in Diagnostics                                             |
| Automatic evidence / ZIP document export                            | IncidentRecorder / ACTION_CREATE_DOCUMENT                 | Diagnostics                                                                                    |
| Hold SOS / accessible long click                                    | app.activateSos(SCREEN)                                   | Persistent Tracking emergency control                                                          |
| Opt-in triple Volume Up in foreground                               | existing TripleVolumeUp / central SOS engine              | Settings; foreground scope explicit                                                            |
| SOS saved/pending/durable receipt; Command-only human ACK           | observeSos + sosDescription                               | Tracking SOS status                                                                            |
| Recording Start/Stop/Resume/Clear                                   | POST /local/recording                                     | Persistent Command recording toolbar                                                           |
| From now / Android session beginning                                | ActionMode                                                | Recording start selector                                                                       |
| Stable colour / show-hide / dot interval                            | backend colour; presentation only                         | Party overview / canvas tools                                                                  |
| Live marker, qualified history, provisional segments                | /local/state existing derived points                      | Geographic canvas                                                                              |
| Raw unfiltered diagnostic observations                              | raw_points; qualified total_m unchanged                   | Canvas tools                                                                                   |
| Eight quality rules: enable/value                                   | POST /local/quality → deterministic reprojection          | Quality dialog                                                                                 |
| Reporting override set/clear + convergence                          | POST /local/override → next ACK                           | Selected party details                                                                         |
| Contact, GNSS, battery, latest fix, qualified distance              | /local/state device read model                            | Compact overview + selected details                                                            |
| History completeness, pending counts and age of count               | history read model                                        | Overview + selected details                                                                    |
| Report/raw/useful counts, cadence/delayed evidence                  | field_evidence                                            | Selected party Diagnostics disclosure                                                          |
| SOS event/receipt/operator ACK                                      | sos_alerts / POST /local/sos/acknowledge                  | Persistent header counter / emergency drawer / retained history                                |
| Audible SOS enable/test, blocked status, recurring alarm            | existing AudioContext SOS loop                            | Persistent toolbar control + emergency status; no competing delivery state                     |
| Command diagnostic ZIP                                              | GET /local/field-report                                   | Global Diagnostics link                                                                        |
| Blank geographic view                                               | existing coordinates; presentation projection             | Default canvas mode                                                                            |
| Pan/zoom/fit/focus/point coordinate inspection                      | presentation only                                         | Canvas controls / keyboard / point inspector                                                   |
| Offline georeferenced background                                    | RFC 7946 GeoJSON WGS84, existing renderer                 | Map dialog; validated import / preparation / persisted library / metadata / opacity / warnings |

## Earlier pre-Issue-6 findings (historical context)

Android puts setup, emergency explanation, all settings and diagnostics ahead of
tracking controls in a single long scroll. Command repeats technical details for
every device, has no selected-party context, and refits the viewport on each poll.
History queue freshness is available and must remain explicit. Contact and GNSS
are already separate backend dimensions and should remain separate labels. SOS
cards deliberately reuse nodes to preserve focus; keep that implementation.
Existing native service, Room flows, recording/quality HTTP actions, reconstruction,
stable backend colours, export and SOS engine are reusable and authoritative.

## Corrective parity reconciliation

All inventory rows above retain their owning API/state. The correction changes
native presentation and gesture routing, the embedded browser workspace and map
preparation/library presentation only. It does not alter `GPS_PROVIDER`, tracking
service ownership, Android Room/outbox schema, GNSS identities, sender priority,
historical batching/retries/deduplication, route reconstruction, quality or recording
calculations, SOS persistence or operator ACK transactions. The map table is an
additive table in Command's existing SQLite database; map imports never mutate
tracking state. Existing diagnostic ZIP and physical-button JSON formats remain.

Regression evidence: the native navigation render test retains all three tabs,
settings/admin/permissions and diagnostic tools; gesture/Activity tests verify
cancel and one offline durable SOS; existing Android-to-Command tests cover real
delivery, recording/history and durable SOS ACK. Browser checks exercise all eight
quality switches, raw observations, overrides, recording modes/lifecycle, pan/zoom,
visibility and selection, independent contact/GNSS/history states, SOS/history and
map import/preparation/library/restart. The Go smoke fixture was extended with the
new optional presentation marker rather than weakening its live/history assertions.

Issue #22 adds Android-local **Sync All Now / Pause history sync** to Tracking,
using the existing app Sender and Room outbox. Start/Stop and protected SOS retain
their independent authorities. History's queued/ACKed/unresolved facts and
Diagnostics payload-byte/retry details do not claim Command route completion.
Tracking OFF remains zero collection; user-started dataSync work has a visible,
bounded lifetime and no unrestricted execution guarantee. Coverage: HistoryDeliveryTest, TrackingLifecycleTest local
control assertion, real CommandIntegrationTest twenty-phone/late-join recovery,
and existing rendered navigation/large-font checks.

## Issue #24 · optional adaptive persistence

| Surface / capability | Change / authority | Verification |
| --- | --- | --- |
| Tracking page / navigation / Start/Stop / SOS dock / Sync All | Existing views and actions preserved | Existing native visual/navigation, lifecycle, hold and sync suites |
| Settings | One collapsed GNSS persistence group; explicit saved opt-in; OFF default | AdaptiveSettingsTest: OFF → ON → Activity recreation → OFF, no service start |
| Diagnostics | Effective/requested policy, counters, pending/ACKed evidence and storage | Existing journal/sample/export; no per-skipped-fix durable log |
| Native GNSS / LIVE / SOS | Original listener/latest fix; pending originals promoted via same writer | TrackingLifecycleTest adaptive/SOS regression; real bridge dense/adaptive comparison |
| Room / identity / Sender / Command | Existing schema/allocator/outbox/priority/quality remain authoritative | Migration, failed-write, retry/ACK loss, tracking-OFF and reconstruction regressions |

No Settings/Diagnostics redesign (#39), Tracking redesign, remote control (#25), or
Command quality UI change is included. Rendered software checks are not TalkBack,
OEM/OS, native GNSS or physical field acceptance.

## Issue #25 control and audit preservation

| Surface / authority | Required preserved behavior / added acceptance |
| --- | --- |
| Android Tracking / SOS | Existing Start/Stop, saved-history UI and SOS unchanged; Sync control never starts GNSS. |
| Android Settings | Existing endpoint/identity/re-enrollment reused; explicit private verified credential transfer; local disable; secure enrollment never downgrades silently. |
| Android Diagnostics | Command Instructions filters/expand/copy and actual canonical stages; pending state survives restart; existing ZIP sanitizes records, never keys. |
| Command live party selection | Individual/selected/all explicit identities, frozen targets, high-impact confirmation and independent result/error/unknown. |
| Command recording/history | Historical objects and view filters never target phone instructions; quality/distance and recording APIs unchanged. |
| Legacy GNSS / SOS | Frozen v1 receipt and human SOS ACK retained; unsupported control shown honestly. |
| Native acceptance | Physical Android background/OEM/Keystore and native Windows/macOS remain NOT TESTED until operator evidence. #24 stays OPEN. |

## Issue #26 alert preservation

| Surface / authority | Behavior / evidence |
| --- | --- |
| Command Phone controls | Existing one/selected/all identity snapshot and confirmation; Normal/Urgent, concise message, validated TTL/duration/repeat; no fleet cap. |
| Command per-phone history | Output attempted/blocked, human recipient ACK, pending Cancel versus phone-confirmed Stop; actual expiry/effective result. Browser control/accessibility suite. |
| Android notification | Separate normal/urgent channels, unique immutable per-command action/tag; local Acknowledge / Stop Alert. Notification action test is simulated OS evidence only. |
| Android Command Instructions | Existing filters/details/copy plus local ACK action; durable event/result custody; same sanitized ZIP with message redacted. |
| Room / SQLite / Sender | Existing v6 schema, enrollment/HMAC/nonces/order/TTL/dedupe/result digests; active alerts excluded from resolved pruning. Real bridge 1/5/20 plus late join. |
| SOS / LIVE / bulk Sync | Existing authorities/controls/notification IDs unchanged; same Sender priority and bounded control request; saturation/bridge regression. |
| Physical/native acceptance | Real audio, DND/mute, locked/OEM/Doze, native macOS/Windows and real offline fleet/endurance NOT TESTED until operator session. #24/#25 remain OPEN. |

## Issue #28 preservation

| Surface | Owning mechanism | Verification |
| --- | --- | --- |
| Radius/centre, map selection, units, requested circle | Existing Download Map and GeoMap projection | Ellipsoid analytic references, antimeridian and polar tests; radius browser fixture |
| Partial vector/DEM, restart, source evidence | Existing SQLite library, additive child chunks, GTERR001 | Empty OSM, immutable complete retry, noncontiguous cancel/resume, failed-write rollback |
| Contours/hillshade/elevation, voids and seams | Existing Terrain/GeoMap, common visible contour levels | Independent native sample assertions; Chromium layer switches/offline restart |
| Durable GNSS/SOS/LIVE and 21st party | Unchanged receiver/raw/projection/alerts | Stalled-provider fleet test; existing saturated writer controls/alert regressions |
| Legacy import/rectangle terrain and UI | Existing paths retained explicitly | Existing map/DEM and browser suites |

No Android Settings (#39), recording export packages (#38), GNSS quality/distance,
recording/Travel Back or remote-control authority changes belong to this extension.


| #28 extension surface | Existing owner reused | Verification |
| --- | --- | --- |
| Automatic fallback and 5 MB cumulative consent | Map job/chunk persistence and bounded provider clients | Known/unknown lengths, 4+4 MB, 200 MB fallback gate, timeout/429/502/503/504 and restart tests |
| PBF crop/full extent and source cache | Same SQLite, GeoJSON chunks and viewport renderer | Sparse/dense nodes, missing references, full extent, polygon holes, cache reuse and bounded index/fleet test |
| Standalone map package | Existing saved vectors/GTERR001 and terrain algorithms | Partial round trip to clean DB, idempotence, corrupt/malicious archive and write-failure rollback |
| Simplified map lifecycle | Same Map & Layers/Saved Maps/Layers controls | Chromium package/PBF/import/terrain/offline restart and axe; existing dashboard/SOS/control regressions |

## Issue #39 · Full Android native redesign

The operator's replacement scope supersedes the old Tracking pixel-preservation
restriction. Review V3's supplied 44-frame PDF plus four explicit V4 overrides govern
presentation. [Acceptance](ui-acceptance.md) maps every
Day/Night reference to its native screen and records deliberate OS/accessibility
adaptations. Command web and the wire contract remain unchanged.

| Surface / contract | Presentation change | Preserved authority / evidence |
| --- | --- | --- |
| Tracking | App/party hierarchy, independent pills, fresh/stale fix, balanced Room/ACK metrics, stable action order, contextual alerts | Original acquisition/session identity, queue/progress projection; startup/GNSS/lifecycle tests |
| SOS | Anchored subdued dock, same 1.2 s hold, chronological history/details | Original SosHoldGesture/engine/durable outbox; separate delivery and Command operator ACK; SOS regressions |
| Large text / landscape | Facts scroll; the compact responsive footer keeps Sync/Start/Stop, SOS and urgent ACK reachable | Native matrix, SOS bounds, scalable text and equal text navigation |
| Settings | Five destinations, focused Save/Cancel forms, parent Back retains drafts | Existing Installation transaction and Activity draft/caret/focus restoration; lifecycle tests |
| Reporting / adaptive | Separate local/requested/applied/revision, native ON/OFF Switch | Existing #24 policy, OFF default, originals unchanged; policy tests |
| Trust / enrollment | Current/draft endpoint, contact evidence, confirmed re-enrollment/disable | Same private credential binding and #25 authorization; credential excluded from saved state/export |
| Readiness | Permission, location, notification, battery and service limits in relevant cards | Existing FieldPreflight/preflight/start flow and Android system settings |
| Health | Seven independent informational rows | Fix freshness, Room commits, actual ACK, unresolved sync, SOS, device space and trust remain separate |
| Instructions / alerts | Chronological pages, compact outcome filters, focused details and Full record | Existing bounded #25/#26 journal, verification/reason/stages/attempt/result custody and human ACK; console/control tests |
| Recovery / tools | Saved data & sync, Diagnostic tools, Physical Button Test, advanced logs | Existing retry confirmation, sanitized ZIP, key/SOS test suppression and foreground scope |
| Pause UI | No exposed history pause, including transfer notification | Internal OS/lifecycle budgets and retained queue unchanged |
| Notifications / theme | Native Sync/confirmed Stop, retained alert ACK, icon-only saved theme toggle | Immutable distinct Activity intents, consumed-action restoration, original alert receiver/timeout/channel; notification tests |
| Command | Existing browser screens/windows included in screenshot artifact | No Command source/UI/recording/maps/protocol changes |
| Native acceptance | Honest distinction between rendered software evidence and devices | Real TalkBack/IME/OEM/Doze/locked GNSS/SOS/alert/LAN and native desktop acceptance outstanding |

## Issue #49 · V3 control preservation

The existing controls are moved in place, retaining their original IDs and backend
handlers; task tabs wrap the same native dialog. The table records implemented
locations. Existing functional/accessibility checks retain their assertions, with
navigation updated to the task tabs. The exact supplied [V3 reference](evidence/issue-49/v3-reference/IMPLEMENTATION-HANDOFF.md)
is preserved verbatim. Current issue/operator instructions supersede the handoff's
historical Figma prerequisite; no populated Figma artifact is claimed.

| Existing controls | Implemented V3 destination | Preserved owner |
| --- | --- | --- |
| Current map, `map-mode` | Current-map status / My Maps display tools | Selection and preferences |
| Search/results, WGS84 coordinates, centre picker | Get a Map location | Existing search and geographic plan |
| Radius, m/km, legacy rectangle and dimensions | Get a Map coverage / advanced | Existing plan controls |
| Preview, coverage/resource details, acknowledgement, download | Get a Map preview / primary action | Existing map job and consent |
| Download hillshade/contours/elevation | Get a Map optional terrain | Independent terrain acquisition |
| Progress, cancel, retry, Save & Use | Active task / recovery | Existing durable job and validated partial assets |
| Additional transfer approval | Active task approval | Existing cumulative transfer ledger |
| Saved list/select, export, confirmed delete | My Maps library/contextual tools | Existing SQLite library |
| Source manifest, attribution, coverage | My Maps source details | Existing evidence/manifest |
| HGT/.gterrain, terrain products and export | My Maps terrain tools | Existing terrain preparation |
| PBF/GeoJSON/portable package, crop/full controls | Import | Existing validation/import endpoints |
| Legacy GeoJSON | Import compatibility details | Existing `map-file` |
| Opacity, diagnostic observations, dot interval | My Maps display settings | Existing display controls |
| Diagnostics/copy/export | Active task details | Existing download report |
| Separate Layers popup | Existing quick Layers | Existing independent visibility/availability |
