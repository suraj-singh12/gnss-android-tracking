# Shared operational design system

Issue #6 builds on combined GNSS + SOS source
`b23b5f58845c896e7fb2c5800e577202667c1e3f`. The interfaces consume existing
service/backend state. Typography and semantics are shared; Android uses native
controls and Command uses accessible HTML controls.

## Operator UI/UX quality gate — 15 reusable rules

These operator-established, cross-screen rules come from approved/refined Command V5.4–V5.14 and Android V4-series design reviews. Apply them to every future proposed UI-affecting change **before** requesting operator design approval. They supplement, not override, a pinned approved screen-specific design. Cross-project reference: [Design Quality Rules in Systems Library](https://app.notion.com/p/40e85dbdf080819297d0dcc92f7f0cf0).

1. **One function, one clear interface:** no duplicate screens, competing buttons or unnecessary navigation.
2. **Match control to intent:** switches for binary state, exclusive selection controls for modes, buttons for actions, chevrons for navigation.
3. **Put controls at point of use:** do not create extra pages for a setting already owned by the current task screen.
4. **Keep geometry stable:** opening pickers, switching tabs, status/telemetry refresh cannot unexpectedly reflow the workspace.
5. **Contain transient UI:** popovers and calendars remain within their parent dialog/workspace and never block required actions.
6. **Remove clutter:** redundant prose, grey explanation cards, repeated titles and excess controls need justification.
7. **Place separators only between peer rows:** not directly above/below a note, button, single content block or unrelated section.
8. **Use measured hierarchy:** compact, consistent typography, rows, space and alignment; avoid empty oversized containers.
9. **Make actions semantically distinct:** normal, affirmative and destructive actions follow consistent emphasis and confirmation.
10. **Preserve intent and drafts:** Cancel cancels, unsaved edits are protected, and switch/filter selections persist as intended.
11. **Do not reset active interactions:** refreshing live data preserves open menus, tabs, filters, focus, scroll and in-progress edits.
12. **Write precise labels:** clear task-oriented verbs/nouns; avoid ambiguous labels and gratuitous trailing ellipses.
13. **Tell the operational truth:** queued ≠ delivered, received ≠ applied, SOS transport ACK ≠ human ACK, output attempt ≠ audible sound.
14. **Test real workflows:** navigation, cancellation, failure, night/day, compact screens, overlays and state transitions—not only first screenshots.
15. **Preserve useful capability when simplifying:** remove duplicate presentation, never working security/recovery/evidence functionality.

**Mandatory design self-review:** inventory affected screens and existing controls; trace the real source/action for each state; identify redundant competing UI; render and interact with main/nested/dialog states at representative sizes; review contrast/accessibility and platform conventions; reject the proposal if any rule fails. Stop for explicit operator approval before production UI implementation. Physical-device acceptance must be distinguished from simulated browser verification.

## Mandatory anti-regression audit before showing a UI proposal

Check the 15 core rules first, then screen the affected workflow against the 52 historical failure checks below. Scope checks to applicable surfaces but explicitly review *adjacent screens, dialogs and the whole task path*. Do not mark a prototype complete solely because it renders or click handlers execute.

**Required design quality gate:** (a) map every control to one clear purpose and owner, (b) challenge duplication, unnecessary prose, oversized actions and wrong control types, (c) verify stable layout and bounded transient surfaces, (d) compare Day/Night, narrow/short widths, larger text and empty/error/offline states, (e) exercise cancellation, drafts, real-status semantics and adjacent journeys, (f) check no previously working capability disappears, (g) record applicable exceptions and test evidence *before asking the operator to review*. When there is a genuine conflict with the pinned approved UI, ask for scope-specific approval instead of silently changing it.

These are **examples of past mistakes to proactively avoid**, not instructions to reproduce outdated screen content. The approved design and actual authoritative runtime state still govern.

### Command — 26 observed corrections

1. Avoid verbose technical prose and debug information cluttering the map-first workspace.
2. Do not render Capture Range or Diagnostics as naïve expanding boxes on top of the map.
3. Move debug and technical evidence into appropriate secondary disclosures.
4. Mode selection must be a compact anchored dropdown, not an oversized modal.
5. Eliminate redundant Library, Capture Range and Refresh entry-point buttons.
6. Use one contextual Phone Controls interface, not duplicated top-bar actions.
7. Keep map-dominant workspace; side panels compact, collapsible, organized.
8. Present actionable operational status before detailed technical evidence.
9. Unify Maps & Layers navigation, acquisition/import and saved-map management.
10. Do not add unnecessary map actions; put Details before Delete.
11. Keep selected states, neutral/positive/destructive actions and confirmation styles consistent.
12. Avoid oversized or inconsistently sized task dialogs.
13. Switching Tool tabs must not resize/reflow the parent dialog.
14. Use compact result rows rather than repeated explanatory grey cards.
15. Present Phone Control results in consistent condensed rows, not large cards.
16. Stop Recording must stop capture immediately and open Save Recording, not a confusing Save & Stop confirmation.
17. Preserve an unsaved stopped recording until the operator saves or explicitly confirms discard.
18. Follow Original must be a small, labeled switch, not an oversized button.
19. Avoid browser-default date/time picker styling that clashes with Command.
20. Opening a calendar must not push Observation Filters or other form elements downward.
21. Avoid huge empty space created by inline-expanded pickers.
22. Constrain calendar size and placement to its parent dialog, including on short screens.
23. Never let a picker obstruct Apply, Cancel or other required footer actions.
24. Tools → SOS Sound uses a right-aligned binary switch, not a large Enable Sound button.
25. SOS Emergencies audio control belongs in the content/status area as a switch, not the event-action footer.
26. Live/playback updates must preserve open menus, tabs, disclosures, speed choices and drafts.

### Android — 26 observed corrections

27. Reporting interval picker must represent valid legacy/custom values instead of silently coercing them.
28. Protected on-screen SOS requires a mandatory 1.2-second hold; do not offer a disabling switch.
29. Remove unreachable prototype-only Appearance and Notification Preview pages; keep actual controls/notifications.
30. Do not lose existing security/recovery/diagnostic functions when simplifying navigation.
31. Put Volume-key SOS shortcut switch on Emergency Controls, not behind another page.
32. Keep SOS History in Diagnostics; do not duplicate its Settings entry.
33. Use consistent switches, not checkboxes, for the approved instruction-filter selections.
34. Remove redundant Filter History control when the existing filter workflow already covers it.
35. Consolidate Physical Button Test controls and history into one owned interface.
36. Remove Test Volume + and Test Volume − screen buttons; an armed test observes actual physical keys.
37. A screen tap/synthetic event is never proof of genuine hardware-key observation.
38. Do not introduce a duplicate Physical Button Test destination when Diagnostic Tools can own the interface.
39. Do not create competing Advanced Diagnostics and Export Diagnostic ZIP interfaces for the same function.
40. Remove separators directly above notes, buttons, lone blocks or unrelated sections; separate peers only.
41. Remove redundant Command Contact explanation when honest state labels convey it.
42. Remove GNSS Details prose about Command-owned quality filters/derived distance when unnecessary.
43. Stop Tracking should be a pale-red, softly bordered caution action, not a generic neutral button.
44. Use the identical operator-approved pale-red Stop Tracking treatment in Day and Night modes.
45. Place dropdown chevrons at a deliberate consistent inset; avoid awkward excessive padding.
46. Remove repetitive Wi-Fi/IP trust disclaimer once the trust boundary is clear from the design.
47. Show Command authority UUID as read-only credential-derived identity, not an editable field or status.
48. Use a short compact Recheck action, not an oversized Recheck Authenticated Contact button.
49. Treat Enrollment Steps as quiet secondary help, not a competing full-sized primary action.
50. Do not mix badge sizes, font families, input styles and row patterns within the identity/status area.
51. Remove redundant Command Enrollment heading inside a page already titled Command Access.
52. Unenrolled and enrolled Command Access states must use the same read-only identity/status row grammar.

### Enforcement and maintenance

- **Before design:** owner inventory, redundancy scan and data/lifecycle semantics.
- **Before sharing design:** reject and fix any applicable listed failure; inspect real rendered flows, not isolated component screenshots.
- **Before implementation:** freeze approved prototype and record deliberate exceptions in the relevant issue.
- **Before PR completion:** attach test evidence for interaction, state persistence, visual parity and preservation; distinguish simulated rendering from native/device behavior.
- **On further operator correction:** update this same canonical rules/checklist section and the cross-project Systems Library record; do not scatter new design-rules files.

## Foundations

Neutral surfaces `#F3F5F4`, white cards, ink `#24343B`, secondary ink `#607077`,
borders `#DCE3DF`, primary action `#17654C`. Amber denotes degraded conditions;
Command red `#A51621` is reserved for SOS and critical errors. Android uses the
restrained V4 burgundy family: Day text `#843E4B`, action `#71333F`, tint `#F6E9EC`;
Night text `#D5A6B0`, action `#77434E`, tint `#38252D`. White emergency-action labels
exceed 7:1 contrast; emergency text on its tint exceeds 6:1. Android offline/off status pills use amber, distinct from emergency burgundy. Stable party colours come from Command's
existing device-ID palette; they do not encode health. Provisional paths are dashed,
qualified history muted, fresh GNSS filled, last-known GNSS hollow and labelled.

Use system sans-serif; native scalable sp on Android, 14–16 px desktop body,
12–13 px secondary information, 24–32 px primary state/distance. Tabular numerals
on desktop. Spacing follows 4/8/12/16/20/24/32 units. Group through alignment and
restrained borders, without decorative panels competing with the canvas.

One primary action per group. Start/Stop Tracking is a single native control;
Cancel Start is available while permission/configuration gating is pending. Command
Recording is separate from Android Tracking. Discard Recording and enrollment/retry
have explicit confirmations. Forms validate numeric ranges; failed backend requests
leave state unchanged and show an error. Polling preserves focused reporting edits
and event-specific SOS buttons. Quality application shows progress and consumes
backend projection state.

## Shared meanings

| Label | Meaning |
| --- | --- |
| Tracking | Android foreground service actively collects GNSS |
| Stopped / Starting / Error | Actual service startup/lifecycle/error state |
| GNSS Fresh | Recent usable original observation, independent of communication |
| GNSS Stale | Last observation is no longer current |
| Command Connected | Application communication succeeds; Wi-Fi alone is insufficient |
| Offline | Command communication unavailable |
| Reconnecting | Wi-Fi is available but Command communication is unavailable/unknown |
| Synchronizing | Outstanding historical observations are being delivered |
| History Incomplete | Unresolved observations or known collection loss remain |
| SOS Pending | Saved emergency awaiting durable Command receipt |
| SOS Received | Command durably stored and transport-ACKed the event |
| SOS Acknowledged | Human operator acknowledged the event on Command |

Never substitute contact for GNSS freshness or count a last-known point as live.
Last reported queue counts carry their freshness; unknown totals never get fabricated
percentages. Unknown battery/accuracy/communication remains unavailable, never zero.
Original observation, capture, receipt and human acknowledgement times remain separate.
Backend contact grace periods follow each device's effective reporting interval.

## Android navigation and flows

Three persistent destinations: **Tracking · Settings · Diagnostics**. Tracking shows
large lifecycle state, one Start/Stop control, GNSS accuracy/age, application contact
and last ACK, concise GNSS queue state, battery and a protected hold-to-activate SOS dock that stays visible above navigation. Tracking exposes local Sync Now. History-pause UI and notification actions are removed; internal OS/lifecycle pause mechanisms remain. Saved-history automatic delivery is available while visible with Tracking OFF. User-started manual drain also has a bounded dataSync foreground lifetime; neither action starts collection. The History card separates pending, durable ACK and unresolved counts, with reconstruction explicitly Command-owned.
Check setup opens Settings in one interaction. Settings starts with the Party and
Command form, then expandable emergency and permissions/battery sections. It contains field readiness
and permission/system-setting actions, identity labels, receiver address, reporting
interval, enrollment and foreground volume shortcut. Diagnostics contains technical
readable operational summaries and tools, retry and a secondary ZIP export button;
raw transport objects and SOS evidence are behind labelled expandable sections.

Start saves configuration, performs the existing permission/preflight gate and
requests the existing foreground service. Navigating or recreating the Activity
never registers GNSS or restarts collection. Stop is confirmed and retains pending
messages. Local interval accepts 5–86400 s in steps of 5; remote overrides remain
visible in Diagnostics and use the original ACK protocol.

SOS requires a continuous 1.2-second hold with progress/haptic feedback, or its
TalkBack long-click action. Ordinary
taps explain activation without saving an event. The opt-in triple Volume Up trigger
remains foreground-only; screen lock/other apps are unsupported. Phone receipt does
not imply human ACK; human acknowledgement remains Command-only.

## Command workspace

The workspace is viewport-bound, with no document scroll. Only party/event lists
and dialogs scroll. With selected details closed and sidebar open, the geographic
canvas occupies about 82.5% at 1280 px and 84.4% at 1440 px. Both secondary panels
collapse; narrow layouts use bounded overlays. Recording controls use the approved compact horizontal rail, state/duration left. Start opens the mode-selection dialog; no permanent
Start From selector remains.

Left: compact selectable parties, stable colour, independent contact/GNSS/history
labels, SOS attention and track visibility. Centre: dominant geographic canvas with
live, historical, provisional and diagnostic raw layers. Right: selected party's
coordinates, original observation time, accuracy, last contact, battery, queue state,
qualified distance and reporting override. Technical evidence is in a disclosure.

Persistent compact recording rail has Start, Pause/Resume, separate Save and final Stop, with both start modes. Stop retains a durable Save/Discard choice. Save requires a name and at least one party; excluded identities remain participation metadata. Day/Night references are preserved in `docs/design/issue27/`.
Duration is supplied by Command's backend recording windows. Quality dialog exposes
all eight enabled/value/unit controls and short descriptions; applying invokes the
existing full reprojection. Raw mode is clearly diagnostic and never changes qualified
distance. Display-dot interval affects presentation only.

Map tools: pointer pan, wheel/buttons zoom, Fit visible parties, Focus selected, point
inspection; canvas keyboard arrows pan, +/− zoom, F fits. Geographic projection uses
WGS84 coordinates in Web Mercator for both modes; authoritative geographic distance
continues to use the existing backend. No accuracy circles are introduced.

The persistent SOS header counter opens an emergency drawer; compact arrival notices
are dismissible without acknowledgement. A separate Event History drawer retains
acknowledged SOS and ordinary operation evidence. Event cards distinguish activation, snapshot,
Command receipt and human acknowledgement. Existing AudioContext alerts repeat while
unacknowledged after explicit enablement from the persistent toolbar, including before an SOS arrives; blocked/unavailable sound is shown visibly.
One shared immediate/one-second cadence continues until every event has a durable
human ACK. Recording, toast dismissal and history filtering cannot dismiss SOS.

## Exactly two map modes

**Blank canvas** is the default and invalid-map fallback, requiring no asset.
**Offline geographic map** accepts local RFC 7946 GeoJSON vector data in WGS84
longitude/latitude (EPSG:4326); display projection is EPSG:3857. Supports Point,
MultiPoint, LineString, MultiLineString, Polygon (holes), MultiPolygon and
GeometryCollection in Feature/FeatureCollection or geometry documents. Background
opacity, source filename, CRS, coverage and coordinate count are exposed. Out-of-coverage
GNSS remains visible with a warning. Coordinates and distances are never calibrated.

Limit: 20 MB / 100,000 coordinates, nesting depth 16. Reject invalid JSON, empty data,
invalid coordinates/rings, legacy CRS declarations, latitudes beyond ±85.05112878°,
and antimeridian-spanning bounds; export/split into compatible RFC 7946 first.
GeoTIFF, MBTiles, arbitrary raster images and manual calibration are not supported.
Online access is restricted to optional Nominatim/Overpass vector preparation;
there is no online operational map mode or bulk tile download. Imported/downloaded
maps persist in the existing Command SQLite library (64 maps / 256 MB, no automatic
deletion), remain exportable and reopen offline after restart. See Command README
for attribution, provider bounds, cooldowns and unsupported OSM relations.
Map failure cannot interrupt ingestion or recording.

## Accessibility and supported layouts

Native Android controls have 48 dp minimum touch targets (primary 56 dp, SOS 64 dp),
scalable text, labelled text fields, scrollable pages and selected navigation semantics.
Desktop controls have visible focus, text status cues, proper labels and modal focus
handling. No essential function depends on hover; points support focus/click and SOS
supports keyboard acknowledgement. Respect contrast at 4.5:1 body / 3:1 essential UI.

Target Android sizes: 360×800 dp and 480×960 dp, with 1.5× font scaling. Command:
1280×720, 1280×800 and 1440×900; at narrow widths selected details and party lists
become collapsible bounded overlays, without document scrolling. Secondary content scrolls;
SOS remains reachable. Verification evidence and remaining limits are recorded in
[UI acceptance](ui-acceptance.md); hardware behavior is separate from synthetic rendering.

### Extensible diagnostic tools

Diagnostics includes a named **Diagnostic tools** native vertical group
(`R.id.diagnostic_tools`) containing delivery retry and diagnostic export. Additional
tested tools can be appended to that group without adding a destination or changing
Tracking. The integrated Physical Button Test reuses the PR #15 controller/journal;
foreground routing and SOS suppression/restoration remain independent of appearance.
Background/locked-screen/screen-off observation is mechanism unavailable. Dependency
PRs remain unmerged without approval.

## Day/Night and terrain refinement

Day retains existing neutral surfaces. Night uses background `#111B22`, surfaces
`#1B2831`, ink `#E5EDF1`, secondary `#B2C2CC`, green `#8DDBBD`, warning `#F2C46D`
and alert `#FF9BA5`. Filled primary and SOS actions retain dark green/red with white
labels. Native Android resources and Command CSS semantic tokens apply throughout;
each app stores its independent choice. Command keeps About and Day/Night beside SOS;
connection and connected/total parties sit beneath identity. No Local workspace label.

Start dialog defaults Current Time, requires confirmation, and explains unavailable
session metadata. About/appearance never acknowledge events or silence alarms.
Layers beside Focus selected is a compact nonmodal popover, disabled for missing
components, with local instant toggles. DEM availability is not inferred from checkbox
selection. Existing geographic renderer orders hillshade, vectors, contours, tracks,
live positions, SOS and controls; points/coordinates/distance remain authoritative.
See Command README for actual local DEM formats, provenance, bounds and provider limits.

## Issue #25 remote instructions

Phone controls reuse Command's live party selection, native browser controls,
semantic states and local same-origin API. Scope confirmation enumerates frozen
identities; queued/requested/sent-unknown/effective/result custody are distinct.
The individual party shortcut opens the same shared control dialog. Legacy reporting
forms remain for unenrolled v1 peers; enrolled phones use authenticated controls.
No historical recording/import/Travel Back object can issue live instructions.

Android Settings reuses endpoint/enrollment and adds explicit private provisioning,
visible phone UUID and local Disable. Diagnostics → Command Instructions uses the
same canonical Room journal for chronological stages, filter/expand/copy and ZIP.
Security-denied attempts carry local event IDs; absent offline commands have no
invented phone record. Existing Tracking and SOS geometry/behavior stays intact;
#39 refines Settings/Diagnostics as documented below.

### Radius acquisition in the existing map workflow

Geographic circle is the default requested coverage shape. Radius input is always
editable, with metres/km and no compulsory preset; the legacy rectangle is explicit.
Preview labels the ellipsoidal requested circle and conservative source envelope.
Saved Maps reports chunk-specific source availability and offers resumable partial
acquisition. Coverage outlines and processing errors do not alter original GNSS
coordinates. At overview scales exceeding the visible detail budget, explain the
required zoom instead of presenting a partial detail set as complete coverage.


The #28 extension presents three progressively disclosed choices in the existing
Map & Layers dialog: Download New Map, Use Existing Map, Import Map. Active map and
actual completeness stay above them. The existing selector activates a saved map;
there is no per-row Use or Reuse button. Network/resource details are expandable,
while approval amounts and missing coverage remain visible. Retry Missing,
Save & Use and Cancel preserve verified data. The toolbar Layers dialog remains
independent and distinguishes available/hidden/visible/processing failure.

## Issue #39 native Android redesign

The operator supplied **GNSS-Tracker-Review-V3-native.pdf** (44 vector frames,
390 × 842, SHA-256 `db4a5e1e10ac9ea6ef432966034a662f3a396c44d9982385934f9d85581730a3`).
All frames were inspected. It supplies the Review V3 visual references after Figma
MCP quota exhaustion. Its sample data is visual reference only. The four written V4
requirements override its pause control, illustrative notifications, bright emergency
reds and text-labelled Tracking theme control. The newer full-app scope supersedes
Tracking pixel preservation; its operational contracts remain protected.

Android now uses the reference's cool neutral surfaces, teal positive states, quiet
blue secondary actions, amber offline/off states and restrained burgundy emergency
family. Day background `#F4F7F8`, ink `#182C36`, surface white; Night background
`#101820`, surface `#192833`, ink `#EAF4F5`. Day secondary ink `#5D727D` and positive
ink `#07796F` are slightly darker than the PDF to meet 4.5:1 normal-text contrast.
All semantic text/surface pairs have automated contrast checks. Typography is native
sans-serif: 23 sp app title, 22 sp screen title, 16–19 sp card titles, 12–14 sp
metadata/actions, 25 sp observation metrics. Native actions have 48 dp minimum targets.
Cards have restrained 1 dp edges, 16 dp corners, 14–16 dp inner padding and a
12–18 dp vertical rhythm, inside 20 dp page margins.

Tracking groups the party identity separately from state pills, GNSS and two balanced
Room/ACK metrics. Sync/Start/Stop retain one order across states; contextual alert
content follows the facts. The protected 1.2-second SOS dock stays above equal-width
text navigation. Large text keeps the same action row above SOS while facts scroll; landscape shares
a compact actions/SOS footer and keeps urgent acknowledgement reachable. Native status, lifecycle and queue data drive
all labels; there are no demonstration values in production.

Settings has five calm destination rows. Configuration, trust, readiness, policy,
appearance and enrollment have focused native screens. Readiness actions live in
the relevant card. Save/Cancel operate on the existing shared draft and transaction;
refresh does not overwrite edits. Caret restoration follows framework hierarchy
restoration. Adaptive storage is the real #24 ON/OFF Switch, OFF by default, with one
future-observations caption. Revision/request evidence is secondary.

Diagnostics separates seven independent health facts from four destinations:
Instructions, Phone Alerts, Saved data & sync and Diagnostic tools. Instructions use
compact outcome filters and chronological, bounded pages; dedicated details show
verification, attempts, stages and result custody. Full canonical evidence is optional.
Phone alerts and SOS history use actual persisted records; output attempts never
imply human acknowledgement. Advanced logs retain technical evidence. Physical Button
Test, retry and sanitized ZIP export remain discoverable in Diagnostic tools only.

App-owned dialogs and OS-owned notifications use native layouts. Tracking notification
Sync and confirmed Stop enter existing Activity workflows; alert ACK retains its
original receiver/journal. Android owns system surfaces and background restrictions.
Command web and its existing visual system remain unchanged.

## Issue #59 Command map-first layout

Command groups existing header actions into Workspace, Records, Tools and View;
the SOS counter, browser alarm readiness and connection remain directly visible.
Map actions remain direct canvas controls, with Layers at the top right. Parties
collapses and restores through edge arrows; selection opens Details. Narrow layouts
use bounded overlay panels and a compact header. Recording status is a compact
disclosure. Playback remains read-only, uses the existing map cell and exposes
unverified presentation intervals with the recorded reason and bounds when
available. Browser sound readiness does not establish speaker volume or audibility.

Live map labels use deterministic collision avoidance with selected and unacknowledged SOS parties prioritized. Markers remain selectable and keyboard accessible; a visible density notice explains omitted labels and suggests zoom or selection. Historical Follow Original retains the recorded presentation rather than reflowing immutable evidence.

Operator amendments to #59: Parties opens by default and collapses/restores through edge arrows. Details opens through party/marker selection and closes through its X. Map controls are direct, with Layers at the canvas top right. SOS and Event History share the responsive Details width. SOS cards retain event-specific actions and a persistent structured evidence disclosure across polling.

Visual correction to #59: direct +/−, Fit, Focus, Inspect and Layers retain the
right-aligned original order, with Layers at the upper right and the direction /
map-mode label shown when the map cell has room. Menu triggers omit redundant
chevrons; ordinary evidence disclosures retain their native single indicator.
Playback uncertainty appears as informational text immediately beneath the frozen
view status, with reasons and intervals in its disclosure. Narrow right panels
share a compact width and are bounded between the map controls/context and dock,
with internal scrolling. Superseded screenshot provenance is in
`docs/design/issue59/README.md`.

Final #59 density correction: at narrow widths (up to 1100 px), only Parties and
Selected Party Details use reduced padding, card gaps and restrained 12–15 px
text, retaining 40 px party-selection and dismissal targets. Historical inspection
facts use compact, wrapping rows; panel widths and dock-aware scroll bounds are
unchanged. Saved Recordings shares `--command-detail-width` at every breakpoint,
with wrapping names, sorting and management actions inside its existing drawer.
