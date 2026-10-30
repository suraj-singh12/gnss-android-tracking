# Shared operational design system

Issue #6 builds on combined GNSS + SOS source
`b23b5f58845c896e7fb2c5800e577202667c1e3f`. The interfaces consume existing
service/backend state. Typography and semantics are shared; Android uses native
controls and Command uses accessible HTML controls.

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
