# Issue #6 functionality preservation matrix

## PR #17 extension · starting SHA 9eee532

All original rows below remain applicable. No acquisition/storage/delivery/quality/
recording/SOS ownership changes are introduced by terrain or themes.

| Capability | Previous location | Extended location | Authoritative API/state | Regression |
| --- | --- | --- | --- | --- |
| Recording start modes | Permanent toolbar selector | Explicit Start modal, Current Time default | Existing recording action/modes/windows; read-only session-availability flag | Browser cancel/errors/both modes; original Go/bridge recording tests |
| Stop/Resume/Clear | Recording toolbar | Same centred toolbar | Existing recording state machine | Existing browser, Go and bridge suites |
| Vector maps/import/export | Map & layers | Same library/downloader/import/export | Existing offline_maps/renderer; vector bytes unchanged | Existing OSM fixture/browser tests; terrain isolation/export test |
| Terrain (new) | Not available | Local DEM preparation and Layers beside Focus selected | Additive offline_terrain binary grid; no tracking dependency | HGT guards/roundtrip/restart/delete, Node processing and browser offline restart |
| Appearance (new) | Day only | Android Settings; Command header | Independent native/browser preference | Native resources/renders, browser persisted Night restart/axe |
| Header status/party count | Separate top-right status | Beneath identity, independent connected/total parties | Existing contact policy/state | Browser DOM placement and server outage/reconnect |
| About (new) | Not available | Header beside SOS/Day-Night | Static exact project/developer information | Browser content/Escape/accessibility |
| SOS/Physical Button/diagnostics | Existing UI | Same functions in both palettes | Existing engine/journal/adapter/alarm/ACK | All previous tests retained; native Day/Night renders |

Automatic DEM acquisition remains explicitly unavailable; local import/fixtures are
not a substitute claim of external provider acceptance. See Command README.

## Corrective audit · baseline 7725412

The corrective branch starts from the validated PR #16 head. The existing rows
below remain the feature inventory. The following mappings guide the correction;
final regression evidence is recorded in `ui-acceptance.md`.

| Capability | Current location | Corrective location | API/state | Regression |
| --- | --- | --- | --- | --- |
| Protected SOS and local/durable receipt | Tracking long-click dock | Tracking hold-progress dock | activateSos / Room observeSos | SosHoldGestureTest, SosActivityTest, CommandIntegrationTest |
| Settings, readiness, permissions, battery exceptions, reenrollment | Flat Settings list | Labelled native sections and advanced enrollment | FieldPreflight / repository.settings | FieldPreflightTest, UiNavigationVisualTest |
| Diagnostics, sender/storage/queues, SOS details, retry, export | Technical text and tools | Readable summaries, expandable raw evidence, tools | existing recorder / Room flows | DiagnosticsExportApi26/28/35Test, PhysicalButton tests |
| Parties, selected evidence, override, visibility | Permanent left/right panels | Collapsible sidebar and contextual detail | /local/state, /local/override | ui-dashboard.cjs |
| Recording modes/lifecycle/duration | Right-aligned controls | Geometrically centred toolbar | /local/recording, authoritative windows | ui-dashboard.cjs, Go recording tests |
| Active SOS / audio / locate / per-event ACK | Large top banner | Header counter, overlay drawer and arrival toast | sos_alerts / /local/sos/acknowledge | sos-alarm-test.cjs, sos-dashboard.cjs |
| Ordinary evidence and acknowledged SOS | Export / selected disclosure | Separate Event History drawer + export | existing field_evidence / sos_alerts | event history API and browser tests |
| Quality eight rules / raw observations / dot interval | Quality and map dialogs | Header tools, existing dialogs | /local/quality, raw_points | ui-dashboard.cjs |
| Blank/offline map, import/opacity/coverage | Map dialog, memory only | Map preparation/library dialog, same renderer | GeoMap / existing Command SQLite | map-test.cjs, offline_maps_test.go |
| Live / stale / historical / provisional locations | Circle markers and paths | Course arrow only for credible moving live fixes | existing fix bearing_deg / speed_mps | marker tests / browser pan/zoom |

Rendered baseline evidence: Command empty, five parties, recording, quality/map
dialogs, invalid map, mixed stale/offline state and delayed SOS. The SOS banner
reduces map height; recording controls are not centred; selected details repeat
static/technical information. Android's native long-click adapter delegates timing
and cancellation to Button, without a visible hold state or explicit pause cleanup.
Command's audio is throttled to ten seconds, and map imports do not survive restart.

Baseline: `b23b5f58845c896e7fb2c5800e577202667c1e3f`; branch
`issue-6-integrated-ui` directly descends from PR #12 (`issue-5-sos`), which
contains PR #11 native observation/history work. Neither dependency is merged.

| Existing action/state | Authoritative implementation | Redesigned location |
| --- | --- | --- |
| Start/Stop phone tracking; permission-gated pending Start | MainActivity preflight → TrackingService / finishTracking | Tracking primary action + setup guidance |
| Party ID/name, Command URL, local interval | repository.settings transaction | Settings |
| Effective interval and Command override | persisted config / ACK | Settings / Diagnostics |
| Precise location, GPS, notifications, battery/background settings | FieldPreflight / Android Settings intents | Tracking setup guidance |
| Retry saved messages | repository.retryDelivery | Diagnostics, confirmed |
| Different Command enrollment | repository.settings(reenroll=true) | Settings, confirmed |
| Native GNSS freshness/accuracy/age; link/ACK; battery/queues/errors | Operational + Room flows | Tracking summary; detailed evidence in Diagnostics |
| Automatic evidence / ZIP document export | IncidentRecorder / ACTION_CREATE_DOCUMENT | Diagnostics |
| Hold SOS / accessible long click | app.activateSos(SCREEN) | Persistent Tracking emergency control |
| Opt-in triple Volume Up in foreground | existing TripleVolumeUp / central SOS engine | Settings; foreground scope explicit |
| SOS saved/pending/durable receipt; Command-only human ACK | observeSos + sosDescription | Tracking SOS status |
| Recording Start/Stop/Resume/Clear | POST /local/recording | Persistent Command recording toolbar |
| From now / Android session beginning | ActionMode | Recording start selector |
| Stable colour / show-hide / dot interval | backend colour; presentation only | Party overview / canvas tools |
| Live marker, qualified history, provisional segments | /local/state existing derived points | Geographic canvas |
| Raw unfiltered diagnostic observations | raw_points; qualified total_m unchanged | Canvas tools |
| Eight quality rules: enable/value | POST /local/quality → deterministic reprojection | Quality dialog |
| Reporting override set/clear + convergence | POST /local/override → next ACK | Selected party details |
| Contact, GNSS, battery, latest fix, qualified distance | /local/state device read model | Compact overview + selected details |
| History completeness, pending counts and age of count | history read model | Overview + selected details |
| Report/raw/useful counts, cadence/delayed evidence | field_evidence | Selected party Diagnostics disclosure |
| SOS event/receipt/operator ACK | sos_alerts / POST /local/sos/acknowledge | Persistent header counter / emergency drawer / retained history |
| Audible SOS enable/test, blocked status, recurring alarm | existing AudioContext SOS loop | Persistent toolbar control + emergency status; no competing delivery state |
| Command diagnostic ZIP | GET /local/field-report | Global Diagnostics link |
| Blank geographic view | existing coordinates; presentation projection | Default canvas mode |
| Pan/zoom/fit/focus/point coordinate inspection | presentation only | Canvas controls / keyboard / point inspector |
| Offline georeferenced background | RFC 7946 GeoJSON WGS84, existing renderer | Map dialog; validated import / preparation / persisted library / metadata / opacity / warnings |

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
