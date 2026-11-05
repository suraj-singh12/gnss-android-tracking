# Issue #39 — Android V4.6 production preservation matrix

This is the owning implementation ledger, not a new audit. It retains the **60 IDs**
from [Stage A](https://github.com/suraj-singh12/gnss-android-tracking/issues/39#issuecomment-6624609006).
Parent: `9547985efd84c7229025b0b1c56812099589dc6b`, latest integration fetched before
reusing the issue-linked branch. Canonical agent guidance synchronized from
`9e237edfc7ce0b9218b93ecd661d83203a40a0e5` (file only; not the single-file tree).
Visual authority: pinned V4.6 at `8d95eafe6fdcd1423027194ea128ae7a8b91236d`.
PR #64 rules at `376687fec8f593ce7cb12ae191887fc47f277f5c` remain draft/unmerged.

Coverage: **18 approved destinations + 7 contextual retained destinations = 25 native
routes**, nine approved dialog families, retained retry/error/document-picker flows,
and three existing notification families. The complete control/data grouping is
below; shared export, routing and native cancellation behaviors retain single owners.
Status here describes implementation. **All IDs 01–60 are present once** and have
production owners; implemented/test source is `e9b7691dab9765dfae0864575b9c45d3c8dbb114`.
Executed source-specific results, retained native renders and remaining physical
limitations are in [ui-acceptance.md](ui-acceptance.md); test names alone are not passes.
Application/protocol authorities remain the existing ones. The delivery documentation
and evidence do not replace the exact-source execution ledger.

Approved clarifications: advanced diagnostics/history/evidence/configuration stay in
contextual secondary destinations; acknowledged **View alert history** stays enabled
and opens the retained canonical journal. The operator additionally approved the
minimal Day secondary-text contrast correction to `#61747A`; immutable design unchanged. The operator also approved replacing immediate accessibility long-click with a protected 1200 ms action and explicit Cancel; ordinary announcements/same-node focus must not cancel it.

Source locations: [MainActivity](../android/app/src/main/java/org/gnss/tracking/MainActivity.kt)
(`installV46Screens`, `navigate`, `save`, `onSaveInstanceState`),
[CommandControl](../android/app/src/main/java/org/gnss/tracking/CommandControl.kt),
[Room](../android/app/src/main/java/org/gnss/tracking/Store.kt),
[TrackingApp](../android/app/src/main/java/org/gnss/tracking/TrackingApp.kt),
[Sender](../android/app/src/main/java/org/gnss/tracking/Sender.kt),
[location](../android/app/src/main/java/org/gnss/tracking/LocationSource.kt),
[alert notification](../android/app/src/main/java/org/gnss/tracking/PhoneAlertOutput.kt).
Tests below are in `android/app/src/test/java/org/gnss/tracking/`.

| ID | Approved control / operational value | Implementation owner and actual behavior | Persistence / cancellation / failure contract | Verification coverage / status |
| ---: | --- | --- | --- | --- |
| 01 | Tracking tab | MainActivity.navigate / installV46Screens; route parents, saved route/scroll/editor state; existing appearance preference | Saved route; navigation must not start GNSS | Implemented; UiNavigationVisualTest; SettingsDiagnosticsTest; CommandEnrollmentUiTest; final evidence in acceptance ledger |
| 02 | Settings tab | MainActivity.navigate / installV46Screens; route parents, saved route/scroll/editor state; existing appearance preference | Preserve drafts; selected tab follows root | Implemented; UiNavigationVisualTest; SettingsDiagnosticsTest; CommandEnrollmentUiTest; final evidence in acceptance ledger |
| 03 | Diagnostics tab | MainActivity.navigate / installV46Screens; route parents, saved route/scroll/editor state; existing appearance preference | Refresh records without changing operations | Implemented; UiNavigationVisualTest; SettingsDiagnosticsTest; CommandEnrollmentUiTest; final evidence in acceptance ledger |
| 04 | Nested Back, title/subtitle, parent stack | MainActivity.navigate / installV46Screens; route parents, saved route/scroll/editor state; existing appearance preference | Saved route; correct parent; no destructive discard | Implemented; UiNavigationVisualTest; SettingsDiagnosticsTest; CommandEnrollmentUiTest; final evidence in acceptance ledger |
| 05 | Global Day/Night icon | MainActivity.navigate / installV46Screens; route parents, saved route/scroll/editor state; existing appearance preference | Pref; retain drafts and running services | Implemented; UiNavigationVisualTest; SettingsDiagnosticsTest; CommandEnrollmentUiTest; final evidence in acceptance ledger |
| 06 | Dialog close/X/outside/Back/Escape/focus | MainActivity.navigate / installV46Screens; route parents, saved route/scroll/editor state; existing appearance preference | Native Back/outside/negative cancel has no effect; private enrollment input never saved; execution commits remain app-owned | Implemented; UiNavigationVisualTest; SettingsDiagnosticsTest; CommandEnrollmentUiTest; final evidence in acceptance ledger |
| 07 | Tracking lifecycle text and ON/OFF badge | MainActivity.save / stopTracking → preflight → TrackingService / TrackingApp.finishTracking; existing Installation/session | Starting/stopping/error distinct from active; RAM execution + DB intent | Implemented; MainActivityStartupTest; TrackingLifecycleTest; TrackingNotificationTest; UiNavigationVisualTest; final evidence in acceptance ledger |
| 08 | Start tracking | MainActivity.save / stopTracking → preflight → TrackingService / TrackingApp.finishTracking; existing Installation/session | Visible explicit intent; denied permission/GPS/channel/saver block; no fake active state | Implemented; MainActivityStartupTest; TrackingLifecycleTest; TrackingNotificationTest; UiNavigationVisualTest; final evidence in acceptance ledger |
| 09 | Stop Tracking pale-red action | MainActivity.save / stopTracking → preflight → TrackingService / TrackingApp.finishTracking; existing Installation/session | Same approved pale-red treatment in both themes; action remains stable | Implemented; MainActivityStartupTest; TrackingLifecycleTest; TrackingNotificationTest; UiNavigationVisualTest; final evidence in acceptance ledger |
| 10 | Stop dialog Cancel | MainActivity.save / stopTracking → preflight → TrackingService / TrackingApp.finishTracking; existing Installation/session | No service/session change | Implemented; MainActivityStartupTest; TrackingLifecycleTest; TrackingNotificationTest; UiNavigationVisualTest; final evidence in acceptance ledger |
| 11 | Stop dialog final Stop | MainActivity.save / stopTracking → preflight → TrackingService / TrackingApp.finishTracking; existing Installation/session | Preserve outbox; expose stopping/storage failure; app-owned completion | Implemented; MainActivityStartupTest; TrackingLifecycleTest; TrackingNotificationTest; UiNavigationVisualTest; final evidence in acceptance ledger |
| 12 | GNSS Fresh/Stopped/stale/unavailable | PlatformLocationSource / LatestLocation → Operational; elapsed capture age and nullable accuracy | Freshness ≤30 seconds elapsed; provider/permission/no-fix independent | Implemented; LocationSourceTest; FixDisplayTest; TrackingLifecycleTest; final evidence in acceptance ledger |
| 13 | Accuracy | PlatformLocationSource / LatestLocation → Operational; elapsed capture age and nullable accuracy | Nullable; stale accuracy labelled last-known; never zero-filled | Implemented; LocationSourceTest; FixDisplayTest; TrackingLifecycleTest; final evidence in acceptance ledger |
| 14 | Fix age | PlatformLocationSource / LatestLocation → Operational; elapsed capture age and nullable accuracy | Time advances without callbacks; stopped/unknown explicitly represented | Implemented; LocationSourceTest; FixDisplayTest; TrackingLifecycleTest; final evidence in acceptance ledger |
| 15 | Delivery Connected/No contact | Room queueCounts / durable deliveredAt → Sender; syncAllNow → existing RetainedTransferService when stopped | Wi-Fi ≠ contact; historical receipt ≠ current proof | Implemented; HistoryDeliveryTest; PendingOutboxDiagnosticsTest; TrackingLifecycleTest; CommandIntegrationTest; final evidence in acceptance ledger |
| 16 | Pending locally | Room queueCounts / durable deliveredAt → Sender; syncAllNow → existing RetainedTransferService when stopped | DB; blocked rows remain pending; unavailable ≠ zero | Implemented; HistoryDeliveryTest; PendingOutboxDiagnosticsTest; TrackingLifecycleTest; CommandIntegrationTest; final evidence in acceptance ledger |
| 17 | Durably ACKed | Room queueCounts / durable deliveredAt → Sender; syncAllNow → existing RetainedTransferService when stopped | Only validated, applied receipts count; not attempted sends | Implemented; HistoryDeliveryTest; PendingOutboxDiagnosticsTest; TrackingLifecycleTest; CommandIntegrationTest; final evidence in acceptance ledger |
| 18 | Retention/queue-clear caption | Room queueCounts / durable deliveredAt → Sender; syncAllNow → existing RetainedTransferService when stopped | No claim of completed Command reconstruction | Implemented; HistoryDeliveryTest; PendingOutboxDiagnosticsTest; TrackingLifecycleTest; CommandIntegrationTest; final evidence in acceptance ledger |
| 19 | Tracking Sync now | Room queueCounts / durable deliveredAt → Sender; syncAllNow → existing RetainedTransferService when stopped | Offline queue retained; busy/coalesced; zero disabled; no GNSS start | Implemented; HistoryDeliveryTest; PendingOutboxDiagnosticsTest; TrackingLifecycleTest; CommandIntegrationTest; final evidence in acceptance ledger |
| 20 | Settings five destination rows | MainActivity shared settings draft → CommandControl.changeSettings → Repository.settings; Party interval mirrors canonical editor | Party, Access, Readiness, Policy, Emergency | Implemented; SettingsDiagnosticsTest; V46WorkflowTest; UiNavigationVisualTest; final evidence in acceptance ledger |
| 21 | Party ID input | MainActivity shared settings draft → CommandControl.changeSettings → Repository.settings; Party interval mirrors canonical editor | 1–80-character validation; stable installation UUID | Implemented; SettingsDiagnosticsTest; V46WorkflowTest; UiNavigationVisualTest; final evidence in acceptance ledger |
| 22 | Party name input | MainActivity shared settings draft → CommandControl.changeSettings → Repository.settings; Party interval mirrors canonical editor | Preserve draft, focus/caret; immutable queued messages unchanged | Implemented; SettingsDiagnosticsTest; V46WorkflowTest; UiNavigationVisualTest; final evidence in acceptance ledger |
| 23 | Command address input | MainActivity shared settings draft → CommandControl.changeSettings → Repository.settings; Party interval mirrors canonical editor | Valid base URL; endpoint-generation fencing; address does not establish trust | Implemented; SettingsDiagnosticsTest; V46WorkflowTest; UiNavigationVisualTest; final evidence in acceptance ledger |
| 24 | Party reporting input | MainActivity shared settings draft → CommandControl.changeSettings → Repository.settings; Party interval mirrors canonical editor | Integer 5–86400, steps of 5; retain active override | Implemented; SettingsDiagnosticsTest; V46WorkflowTest; UiNavigationVisualTest; final evidence in acceptance ledger |
| 25 | Save settings | MainActivity shared settings draft → CommandControl.changeSettings → Repository.settings; Party interval mirrors canonical editor | Saving/error/saved-local; retain draft after failure | Implemented; SettingsDiagnosticsTest; V46WorkflowTest; UiNavigationVisualTest; final evidence in acceptance ledger |
| 26 | Party Cancel | MainActivity shared settings draft → CommandControl.changeSettings → Repository.settings; Party interval mirrors canonical editor | Cancel edits only; unavailable storage cannot fabricate saved values | Implemented; SettingsDiagnosticsTest; V46WorkflowTest; UiNavigationVisualTest; final evidence in acceptance ledger |
| 27 | Command Access identity rows | Installation UUID/binding; ControlCrypto.parseCredential → public comparison → CommandControl.bind / existing Keystore | Read-only/selectable; not editable/status placeholders | Implemented; CommandEnrollmentUiTest; CommandControlTest; final evidence in acceptance ledger |
| 28 | Enrollment status badge | Installation UUID/binding; ControlCrypto.parseCredential → public comparison → CommandControl.bind / existing Keystore | Not enrolled/enrolled/disabled; independent of contact | Implemented; CommandEnrollmentUiTest; CommandControlTest; final evidence in acceptance ledger |
| 29 | Authenticated contact row | Installation UUID/binding; ControlCrypto.parseCredential → public comparison → CommandControl.bind / existing Keystore | RAM proof expires at 30 seconds/failure; restart needs fresh proof | Implemented; CommandEnrollmentUiTest; CommandControlTest; final evidence in acceptance ledger |
| 30 | Private credential input | Installation UUID/binding; ControlCrypto.parseCredential → public comparison → CommandControl.bind / existing Keystore | Wrong device/invalid format rejected; no Saved state/export/autofill leakage | Implemented; CommandEnrollmentUiTest; CommandControlTest; final evidence in acceptance ledger |
| 31 | Authorize → review dialog → Cancel/Authorize | Installation UUID/binding; ControlCrypto.parseCredential → public comparison → CommandControl.bind / existing Keystore | Local enrollment only after successful save; contact remains unverified | Implemented; CommandEnrollmentUiTest; CommandControlTest; final evidence in acceptance ledger |
| 32 | Compact Recheck | MainActivity Recheck busy adapter → CommandControl.recheck → existing authenticated step/contact freshness | Checking/offline/auth failure/verified; no replacement credentials or new poller | Implemented; CommandControlTest; CommandEnrollmentUiTest; CommandIntegrationTest; final evidence in acceptance ledger |
| 33 | Enrollment steps / Done | Existing CommandControl bind/disable/changeSettings and Repository re-enrollment; public IDs/evidence in Manage access secondary destination | No automatic pairing; close no mutation | Implemented; CommandEnrollmentUiTest; CommandControlTest; CommandIntegrationTest; final evidence in acceptance ledger |
| 34 | Manage access & trust destination/status | Existing CommandControl bind/disable/changeSettings and Repository re-enrollment; public IDs/evidence in Manage access secondary destination | Keep public IDs, last verified evidence and pending results accessible | Implemented; CommandEnrollmentUiTest; CommandControlTest; CommandIntegrationTest; final evidence in acceptance ledger |
| 35 | Disable → confirmation → Cancel/Disable | Existing CommandControl bind/disable/changeSettings and Repository re-enrollment; public IDs/evidence in Manage access secondary destination | DB local disabled flag; stop local alert output; retain histories/results | Implemented; CommandEnrollmentUiTest; CommandControlTest; CommandIntegrationTest; final evidence in acceptance ledger |
| 36 | Re-enroll → confirmation → Continue | Existing CommandControl bind/disable/changeSettings and Repository re-enrollment; public IDs/evidence in Manage access secondary destination | Preserve phone identity/originals; invalidate old binding/override deliberately | Implemented; CommandEnrollmentUiTest; CommandControlTest; CommandIntegrationTest; final evidence in acceptance ledger |
| 37 | Readiness location/notification/battery rows | FieldPreflight.snapshot → three native rows; guidance delegates existing location/GPS/notification/battery Android intents | Denied, GPS/channel disabled, saver/unknown OEM state | Implemented; MainActivityStartupTest; FieldPreflightTest; UiNavigationVisualTest; final evidence in acceptance ledger |
| 38 | Permission guidance / Close / recovery | FieldPreflight.snapshot → three native rows; guidance delegates existing location/GPS/notification/battery Android intents | Return refresh; only pending explicit Start may continue | Implemented; MainActivityStartupTest; FieldPreflightTest; UiNavigationVisualTest; final evidence in acceptance ledger |
| 39 | Reporting select: 5/10/30/60/Custom | Native predefined/Custom picker → same canonical interval draft → same settings transaction; Policy details retain requested/effective/revision | Opening must not coerce valid legacy values | Implemented; V46WorkflowTest; SettingsDiagnosticsTest; CommandControlTest; final evidence in acceptance ledger |
| 40 | Custom interval and validation error | Native predefined/Custom picker → same canonical interval draft → same settings transaction; Policy details retain requested/effective/revision | Reject blank/noninteger/out-of-range/nonmultiple; retain draft/focus | Implemented; V46WorkflowTest; SettingsDiagnosticsTest; CommandControlTest; final evidence in acceptance ledger |
| 41 | Save policy | Native predefined/Custom picker → same canonical interval draft → same settings transaction; Policy details retain requested/effective/revision | Commit before success; local/effective/remote-requested remain distinct | Implemented; V46WorkflowTest; SettingsDiagnosticsTest; CommandControlTest; final evidence in acceptance ledger |
| 42 | Adaptive storage switch | TrackingApp.requestAdaptive → existing atomic PersistencePolicy preference; prospective selection and dense fallback unchanged | OFF default; immediate prospective save; saving/failure/dense fallback; remote changes converge | Implemented; AdaptiveSettingsTest; AdaptivePersistenceTest; TrackingLifecycleTest; CommandIntegrationTest; final evidence in acceptance ledger |
| 43 | Emergency mandatory hold / Always on | Existing SosHoldGesture (continuous touch/keyboard 1200 ms; approved protected accessibility action 1200 ms, explicit Cancel, focus/pause/navigation cancellation) / volumeSos preference / TripleVolumeUp → same app SOS engine | No disabling preference; cancel incomplete gesture | Implemented with operator-approved protected accessibility action; V46WorkflowTest; SosHoldGestureTest; SosActivityTest; PhysicalButtonActivityTest; final evidence in acceptance ledger |
| 44 | Volume-up SOS shortcut switch | Existing SosHoldGesture (continuous touch/keyboard 1200 ms; approved protected accessibility action 1200 ms, explicit Cancel, focus/pause/navigation cancellation) / volumeSos preference / TripleVolumeUp → same app SOS engine | Optional; 3 presses/1.5 s; foreground only; reset partial pattern on change | Implemented; V46WorkflowTest; SosHoldGestureTest; SosActivityTest; PhysicalButtonActivityTest; final evidence in acceptance ledger |
| 45 | Diagnostics four health rows | Operational / persistence / Sender / authenticated contact → four HealthRow facts; seven diagnostic destinations | Real failure/unknown; never static Ready/Fresh because tracking is on | Implemented; UiNavigationVisualTest; SettingsDiagnosticsTest; PendingOutboxDiagnosticsTest; final evidence in acceptance ledger |
| 46 | Diagnostics seven destination rows | Operational / persistence / Sender / authenticated contact → four HealthRow facts; seven diagnostic destinations | Instructions, Alerts, Saved data, SOS, GNSS, Contact, Tools | Implemented; UiNavigationVisualTest; SettingsDiagnosticsTest; PendingOutboxDiagnosticsTest; final evidence in acceptance ledger |
| 47 | Instruction rows/name/time/outcome | Same Room CommandRecord journal and app-owned existing recorder export / Operational progress; bounded recent summary and full paginated search; transactional group switches; canonical categories under the one Filter history workflow | Received ≠ attempted ≠ applied; no invented offline commands | Implemented; CommandConsoleUiTest; SettingsDiagnosticsTest; V46WorkflowTest; DiagnosticsExportTest; final evidence in acceptance ledger |
| 48 | Export sanitized journal | Same Room CommandRecord journal and app-owned existing recorder export / Operational progress; bounded recent summary and full paginated search; transactional group switches; canonical categories under the one Filter history workflow | Cancel, exporting, failure; success after write; secrets/message redacted | Implemented; CommandConsoleUiTest; SettingsDiagnosticsTest; V46WorkflowTest; DiagnosticsExportTest; final evidence in acceptance ledger |
| 49 | History search & filters destination/search/results/empty state | Same Room CommandRecord journal and app-owned existing recorder export / Operational progress; bounded recent summary and full paginated search; transactional group switches; canonical categories under the one Filter history workflow | Saved query/page/category; refresh preserves input/focus | Implemented; CommandConsoleUiTest; SettingsDiagnosticsTest; V46WorkflowTest; DiagnosticsExportTest; final evidence in acceptance ledger |
| 50 | Filter dialog: Applied/Unknown/Rejected-blocked switches; Cancel/Apply | Same Room CommandRecord journal and app-owned existing recorder export / Operational progress; bounded recent summary and full paginated search; transactional group switches; canonical categories under the one Filter history workflow | Cancel discards draft; Apply updates results; preserve other canonical outcomes | Implemented; CommandConsoleUiTest; SettingsDiagnosticsTest; V46WorkflowTest; DiagnosticsExportTest; final evidence in acceptance ledger |
| 51 | Phone-alert status and View alert | Same trusted alert journal / NotificationPhoneAlertOutput / app-owned shared export; explicit ACK through TrackingApp; retained history opens canonical alert-filtered journal; existing sanitized exporter | Attempted/blocked/stopped-local/unknown/ACK pending/custody confirmed | Implemented; PhoneAlertNotificationTest; V46WorkflowTest; CommandConsoleUiTest; final evidence in acceptance ledger |
| 52 | Alert dialog Stop output | CommandControl.stopAlertOutput mutex → Room v8 durable stop intent → cancel repetition/OS output → durable cancellation-return evidence; recipient ACK stays separate | Room v8 requested/completed timestamps; stop intent survives process death; failed storage/OS cancellation never becomes recipient ACK or completed stop; explicit retry allowed | Implemented; CommandControlTest stop-only/idempotency/restart/race/storage/OS failure cases; PhoneAlertNotificationTest; V46WorkflowTest; final evidence in acceptance ledger |
| 53 | Alert dialog Acknowledge | Same trusted alert journal / NotificationPhoneAlertOutput / app-owned shared export; explicit ACK through TrackingApp; retained history opens canonical alert-filtered journal; existing sanitized exporter | Offline ACK retained; save failure visible; idempotent | Implemented; PhoneAlertNotificationTest; V46WorkflowTest; CommandConsoleUiTest; final evidence in acceptance ledger |
| 54 | Export alert journal | Same trusted alert journal / NotificationPhoneAlertOutput / app-owned shared export; explicit ACK through TrackingApp; retained history opens canonical alert-filtered journal; existing sanitized exporter | Retain identities/stages; redact message/private material | Implemented; PhoneAlertNotificationTest; V46WorkflowTest; CommandConsoleUiTest; final evidence in acceptance ledger |
| 55 | Saved data counts / Sync All Now / export | Shared queue counts / syncAllNow / Sender; existing confirmed retryDelivery and app-owned sanitized ZIP in Transfer recovery | Loading/error/zero/unresolved; no synthetic completion or GNSS start | Implemented; TrackingLifecycleTest; HistoryDeliveryTest; DiagnosticsExportTest; CommandIntegrationTest; final evidence in acceptance ledger |
| 56 | Transfer recovery count / Retry saved messages | Shared queue counts / syncAllNow / Sender; existing confirmed retryDelivery and app-owned sanitized ZIP in Transfer recovery | Preserve IDs/JSON/enrollment; retry request ≠ receipt; confirmation/error retained | Implemented; TrackingLifecycleTest; HistoryDeliveryTest; DiagnosticsExportTest; CommandIntegrationTest; final evidence in acceptance ledger |
| 57 | Tools physical-test status, Start/Stop | One Diagnostic tools physical test; existing PhysicalButtonTestController / dispatchKeyEvent / bounded recorder; confirmed clear affects only test evidence | Start arms real observations; suppress shortcut only while listening; navigation/pause interrupts | Implemented; PhysicalButtonActivityTest; PhysicalButtonControllerTest; PhysicalButtonJournalTest; UiNavigationVisualTest; final evidence in acceptance ledger |
| 58 | Observed events / Clear results → Cancel/Clear / diagnostic ZIP | One Diagnostic tools physical test; existing PhysicalButtonTestController / dispatchKeyEvent / bounded recorder; confirmed clear affects only test evidence | Actual events only; clear only test history; evidence survives restart | Implemented; PhysicalButtonActivityTest; PhysicalButtonControllerTest; PhysicalButtonJournalTest; UiNavigationVisualTest; final evidence in acceptance ledger |
| 59 | GNSS details / Command contact / SOS history values | LatestLocation / Operational; Sender/contact proofs; full Room SOS journal, pagination/details/export; operator ACK remains Command-only | Capture age, receipt and trust separate; SOS human ACK remains Command-only | Implemented; V46WorkflowTest; SosActivityTest; SosEngineTest; CommandControlTest; UiNavigationVisualTest; CommandIntegrationTest; final evidence in acceptance ledger |
| 60 | Global SOS footer; progress/save/delivery states; real notifications | Existing global hold adapter → app-scoped SOS engine → Room/Sender; existing tracking/dataSync notification owners; independent per-alert immutable Stop output and ACK intents | Early release/navigation/pause cancels incomplete hold; committed event survives; retry/offline/no-fix truthful; OS-owned notification geometry; native OS geometry, channel/audio and OEM restrictions require physical acceptance | Implemented with operator-approved protected accessibility action; V46WorkflowTest (23 destinations); SosActivityTest; TrackingNotificationTest; PhoneAlertNotificationTest; CommandIntegrationTest; final evidence in acceptance ledger |

## Preservation and authority boundary

No new database, sender, GNSS listener, tracking service, instruction executor or
alert-output engine. Additive Room **7→8** only records independent local stop intent
and API cancellation return in the existing command journal. Existing `unknown`
wire outcome carries the truthful local reason; no Command/protocol production change.
Stop intent is retained independently of Command result custody until recipient ACK;
normal resolved pruning cannot silently delete that outstanding acknowledgement.
Original outbox JSON/IDs/session sequences, adaptive OFF default and prospective policy,
durable receipt validation, retries/quarantine, SOS transport versus operator ACK,
Keystore enrollment and authenticated freshness, full canonical histories, diagnostics
ZIP redaction and physical evidence ownership are preserved.

Retained secondary destinations: Policy details, Identity & verification details,
canonical instruction categories, Instruction details, Storage health, Advanced logs,
and SOS details. Only one `Filter history` entry exposes the three approved switches;
its Canonical categories action retains older type/outcome/time controls. The physical
button test has one Tools presentation and no screen-key simulation path.

Native dialogs and notification templates are Android-owned; 48 dp touch targets and
scrolling accommodate narrow/landscape/large-font windows. Native rendering, software
integration and physical acceptance remain separate evidence boundaries.

---

## Historical preservation records (retain their original scope and source revisions)

# Issue #6 functionality preservation matrix

## PR #17 extension · starting SHA 9eee532

All original rows below remain applicable. No acquisition/storage/delivery/quality/
recording/SOS ownership changes are introduced by terrain or themes.

| Capability                      | Previous location          | Extended location                                      | Authoritative API/state                                                      | Regression                                                                       |
| ------------------------------- | -------------------------- | ------------------------------------------------------ | ---------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| Recording start modes           | Permanent toolbar selector | Explicit Start modal, Current Time default             | Existing recording action/modes/windows; read-only session-availability flag | Browser cancel/errors/both modes; original Go/bridge recording tests             |
| Pause/Resume/Stop/Save/Discard               | Recording toolbar          | Approved reduced-height rail                                   | Existing recording state machine                                             | Existing browser, Go and bridge suites                                           |
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
| Recording modes/lifecycle/duration                                 | Right-aligned controls       | Approved compact horizontal rail                      | /local/recording, authoritative windows | ui-dashboard.cjs, Go recording tests                        |
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
| Recording Start/Pause/Resume/Stop/Save/Discard                                   | POST /local/recording                                     | Persistent Command recording toolbar                                                           |
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

## Issue #48 · Command identity and guided authorization

Approved source: operator-approved [issue48-final-design-review.zip](design/issue48-final-design-review.zip) (guided-enrollment.svg,
day-night-states.svg, command-phone-controls.svg, proposal.md); final approval given in the
implementation request. Parent `5f33368344cfb1e69cf55a6a051d0a09f333cf64` includes #47.
Canonical guidance `3b60b6d6228ca208df75765e2008c9b9abba8a69` matches this branch.

| Existing capability | Presentation / evidence mapping | Preserved authority |
| --- | --- | --- |
| Command access / Advanced settings | Direct Settings entry opens the same route; compare phone, private entry, public-ID review and existing explicit consent | Existing credential parser, Keystore and bind; no auto-pairing |
| Command trust and diagnostics | Identity & verification details plus expandable canonical reasons; existing trust route retained | Existing journal; Instruction ID is distinct from persistent Command ID |
| Local disable / re-enroll | Protected local disable; guided re-authorization; pending results retained | Same bind/disable and endpoint generation; persisted disabled flag distinguishes fresh install |
| Contact status | Six compact Day/Night states; current process evidence from actual verified responses, including empty polls | Same MAC/nonce/authority/target verifier; monotonic 30-second freshness; historical Room timestamp only |
| Phone controls | Public Command ID from protected /local/control; enrollment separate from accepted phone-request timestamp | Existing authority UUID, per-phone credential/revoke and loopback/same-origin guards |
| Reporting, adaptive, Sync, alerts, SOS, history | Existing controls and owners retained; #47 switch handling untouched | No sender, acquisition, policy, alert or SOS replacement |
| #39 V3/V4 | Native shared components, scalable scrolling and Day/Night; no pause UI, stable actions/SOS, subdued error palette, icon-only theme switch | Existing NativeUi/resources/navigation and protected native notifications |

Verification uses CommandControlTest, CommandEnrollmentUiTest, existing console/settings and
migration tests, Go control tests and actual-server browser/bridge suites. Screenshots are
synthetic-state native software renders and real-server Chromium evidence, not physical
Android or native macOS/Windows acceptance. No pixel-diff baseline is introduced.


## Issue #27 recording foundation

Approved images: `docs/design/issue27/` (original bytes; mockups, not runtime proof).
The rail preserves dashboard/navigation/SOS/map/party panels and Day/Night tokens;
Pause/Resume is primary, Save separate and Stop final. No floating dock is introduced.
`recording-foundation.cjs` covers actual server/SQLite/Chromium flows and captures
Day recording, Night paused and both Save dialogs without pixel-diff baselines.
Exclusive browser fencing protects recording publication. GNSS/ACK/SOS retain their
existing authorities. OS compositor scanout is outside the claim. Asset read revisions and concurrent
Save receipt have targeted tests; sustained large-Save latency and long-running fleet
acceptance remain unverified; see architecture.

## Issue #37 · Saved library and historical playback

| Existing capability | Historical presentation | Preserved authority |
| --- | --- | --- |
| Header / Event History conventions | Saved recordings opens an accessible Command dialog; search/sort, descriptive edit and confirmed Delete | Existing SQLite and immutable #27 receipts |
| Recording strip / live lifecycle | Compact VIEWING RECORDING rail with one centred Follow Original switch and Exit | Live recording state remains separate; historical DOM is never recaptured as live |
| Parties / geographic map / Details | Same grid, time-gated party rows, read-only details/quality and neutral Recording SOS | Frozen browser checkpoint; no GNSS or distance reconstruction |
| Map toolbar / layers | Historical focus/fit and temporary pan/zoom/rotation; pinned terrain inspection | Existing map and terrain helpers; no mutable-library fallback |
| Panel collapse / responsive map | Timeline is bounded by the map cell at all panel combinations | Existing grid and panel controls |
| Live SOS / receipt / synchronization | Existing live header and notifications continue; historical chips have no actions | Existing operational transport/SOS owners |

Approved Day/Night images supplied with #37 are the visual contract. Runtime screenshots
in `docs/design/issue37/` and the integrated workflow demonstrate actual Command/SQLite
fixtures, including populated library, 21-party/20-subset playback, hidden restriction,
interruption and responsive panel states. They are not pixel-diff baselines or physical
field acceptance. #34 navigation/Travel Back and #38 packaging remain out of scope.

## Operational modes / Travel Back (#34)

| Capability | Authority / preserved behavior | Regression |
| --- | --- | --- |
| Active/paused recording | #27 lifecycle and exclusive browser; Snapshot leaves navigation locked | Existing persisted playback browser scenario now asserts lock then final Stop |
| Saved playback | #37 frozen source, original visibility, Follow Original, interruption clock | `recording-playback.cjs`, existing Go playback tests |
| Travel Back | Retained original capture time; existing `Derive` quality/segments/distance | `travel_test.go`, `travel-back.cjs` |
| Later stored history | Immutable response until manual Refresh View; no Android sync | Frozen-map browser comparison and late-arrival Go test |
| SOS Locate/Return | Independent LIVE alarm/ACK; suspend/restore paused historical context | Actual receiver SOS in `travel-back.cjs` |
| Map/panels/themes | Existing renderer, panel sizes and Day/Night tokens; shared transport | Actual application captures in `docs/design/issue34/final-review` |
