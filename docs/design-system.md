# Shared operational design system

Issue #6 builds on combined GNSS + SOS source
`b23b5f58845c896e7fb2c5800e577202667c1e3f`. The interfaces consume existing
service/backend state. Typography and semantics are shared; Android uses native
controls and Command uses accessible HTML controls.

## Foundations

Neutral surfaces `#F3F5F4`, white cards, ink `#24343B`, secondary ink `#607077`,
borders `#DCE3DF`, primary action `#17654C`. Amber denotes degraded conditions;
red `#A51621` is reserved for SOS and critical errors. Offline contact uses neutral
text rather than an emergency colour. Stable party colours come from Command's
existing device-ID palette; they do not encode health. Provisional paths are dashed,
qualified history muted, fresh GNSS filled, last-known GNSS hollow and labelled.

Use system sans-serif; native scalable sp on Android, 14–16 px desktop body,
12–13 px secondary information, 24–32 px primary state/distance. Tabular numerals
on desktop. Spacing follows 4/8/12/16/20/24/32 units. Group through alignment and
restrained borders, without decorative panels competing with the canvas.

One primary action per group. Start/Stop Tracking is a single native control;
Cancel Start is available while permission/configuration gating is pending. Command
Recording is separate from Android Tracking. Clear Recording and enrollment/retry
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
and last ACK, concise GNSS queue state, battery and a protected hold-to-activate SOS dock that stays visible above navigation.
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
collapse; narrow layouts use bounded overlays. Recording controls are geometrically
centred, state/duration left. Start opens the mode-selection dialog; no permanent
Start From selector remains.

Left: compact selectable parties, stable colour, independent contact/GNSS/history
labels, SOS attention and track visibility. Centre: dominant geographic canvas with
live, historical, provisional and diagnostic raw layers. Right: selected party's
coordinates, original observation time, accuracy, last contact, battery, queue state,
qualified distance and reporting override. Technical evidence is in a disclosure.

Persistent recording toolbar has Start/Stop/Resume/Clear and both start modes.
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
