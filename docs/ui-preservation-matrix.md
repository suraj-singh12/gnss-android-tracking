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

## Issue #39 · Android Settings and Diagnostics

| Surface/contract | Change and preserved authority | Software evidence |
| --- | --- | --- |
| Tracking / SOS dock / service | Existing layout, controls, hold, sync and operational rendering preserved | Baseline/final native-render comparison; full startup/SOS/lifecycle suite |
| Settings editing | Drafts survive refresh/recreation, selection/focus retained; Save after transaction; Cancel reloads persisted form | SettingsDiagnosticsTest; existing startup/appearance tests |
| Reporting/adaptive | Local/requested/outcome/applied/revision shown separately; same immediate saved OFF-default #24 switch | SettingsDiagnosticsTest, AdaptiveSettingsTest, existing policy tests |
| Command trust | Existing endpoint, binding, private enrollment and confirmed re-enrollment; unknown contact stays unknown | CommandPresentation assertions, existing CommandControlTest |
| Health | Separate fix/Room/ACK/sync/SOS/storage/trust and recovery text | Native render matrix; unchanged persistence/Sender regressions |
| Command console / phone alerts | Same journal, actual duplicate stages, filters/pages, expandable native controls, local ACK | CommandConsoleUiTest, SettingsDiagnosticsTest, existing #25/#26 tests |
| Retry/export/Physical Button Test | Existing tools and confirmation/sanitization preserved in Advanced Diagnostics | Existing export/physical-button tests and native renders |
| Device acceptance | No native/physical success inferred from software evidence | Real TalkBack, keyboard/rotation/OS/OEM and LAN endurance remain outstanding |

### #39 operational visual refinement

| Surface | Refined presentation | Preserved contract |
| --- | --- | --- |
| Settings | Compact identity/endpoint/interval form; primary Save, quiet Cancel; optional details | Existing local transaction, draft lifecycle, immediate OFF-default adaptive policy and protected enrollment |
| Diagnostics | Seven informational health rows; Instructions/Alerts/Advanced disclosures | Independent GNSS, durability, ACK, sync, SOS, storage and trust facts |
| Instructions | Concise rows; optional filters/stages/copy; stable focus and saved page | Same bounded authenticated journal, outcomes and result custody |
| Tools | Advanced contains health/SOS/technical evidence, retry, export and Physical Button Test | Existing confirmation, sanitization, key/SOS suppression and recovery behavior |
| Tracking / Command web | No visual or functional redesign | Existing layout, controls, GNSS/SOS/Room/Sender/service/protocol authorities |


### Issue #39 replacement scope — V4 progress and visual-source blocker

This supersedes prior #39 rows claiming Tracking visuals must remain unchanged.
The entire app is now authorized for Figma Review V3 redesign. The 44 reference
frames are inaccessible (Starter quota and blocked direct view; no local exports),
so full Figma fidelity/navigation/screen acceptance remains pending.

| Area | Implemented V4 correction | Preserved owner / evidence |
| --- | --- | --- |
| Tracking header | Accessible moon/sun toggle, existing saved preference | Draft/recreation and no automatic GNSS start tests |
| Emergency family | Shared Day/Night burgundy text, action and surface tokens | Original 1.2 s hold and SOS engine; contrast/native renders |
| History UI | Remove exposed pause controls and transfer notification Pause | Existing queue, retry, data retention and OS/lifecycle budgets |
| Tracking notification | Native Sync Now / Stop Tracking actions | Existing visible sync and confirmed stop; immutable intents, no GNSS from Sync, no replay on recreation |
| Phone-alert notifications | Coordinated color; retained ACK identity and timeout | Original output/cancel/ACK journal and receiver; no new acknowledgement |
| Remaining full-app Figma work | Blocked pending visual references | No claim that 44 frames were inspected or matched |
