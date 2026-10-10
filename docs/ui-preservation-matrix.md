# Issue #6 functionality preservation matrix

Baseline: `b23b5f58845c896e7fb2c5800e577202667c1e3f`; branch
`issue-6-integrated-ui` directly descends from PR #12 (`issue-5-sos`), which
contains PR #11 native observation/history work. Neither dependency is merged.

| Existing action/state | Authoritative implementation | Redesigned location |
| --- | --- | --- |
| Start/Stop phone tracking; permission-gated pending Start | MainActivity preflight → TrackingService / finishTracking | Tracking primary action + setup guidance |
| Party ID/name, Command URL, local interval | repository.settings transaction | Settings |
| Effective interval and Command override | persisted config / ACK | Tracking summary, Settings / Diagnostics |
| Precise location, GPS, notifications, battery/background settings | FieldPreflight / Android Settings intents | Tracking setup guidance |
| Retry saved messages | repository.retryDelivery | Diagnostics, confirmed |
| Different Command enrollment | repository.settings(reenroll=true) | Settings, confirmed |
| Native GNSS freshness/accuracy/age; link/ACK; battery/queues/errors | Operational + Room flows | Tracking summary; detailed evidence in Diagnostics |
| Automatic evidence / ZIP document export | IncidentRecorder / ACTION_CREATE_DOCUMENT | Diagnostics |
| Hold SOS / accessible long click | app.activateSos(SCREEN) | Tracking emergency card |
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
| SOS event/receipt/operator ACK | sos_alerts / POST /local/sos/acknowledge | Persistent emergency region |
| Audible SOS enable/test, blocked status, recurring alarm | existing AudioContext SOS loop | Emergency region; no competing delivery state |
| Command diagnostic ZIP | GET /local/field-report | Global Diagnostics link |
| Blank geographic view | existing coordinates; presentation projection | Default canvas mode |
| Pan/zoom/fit/focus/point coordinate inspection | presentation only | Canvas controls / keyboard / point inspector |
| Offline georeferenced background (new) | RFC 7946 GeoJSON WGS84, local file only | Map dialog; metadata/opacity/warnings |

## Findings before implementation

Android puts setup, emergency explanation, all settings and diagnostics ahead of
tracking controls in a single long scroll. Command repeats technical details for
every device, has no selected-party context, and refits the viewport on each poll.
History queue freshness is available and must remain explicit. Contact and GNSS
are already separate backend dimensions and should remain separate labels. SOS
cards deliberately reuse nodes to preserve focus; keep that implementation.
Existing native service, Room flows, recording/quality HTTP actions, reconstruction,
stable backend colours, export and SOS engine are reusable and authoritative.
