# GNSS Command — Maps & Layers popup V3 (DESIGN REVIEW, not implementation)

## Source-specific fit
Verified against `integration/combined-upgrades` at the time of design:
- `command/web/index.html` — `<dialog id="map-settings">`, current-map header, acquisition, saved maps, import, layer status and terrain controls.
- `command/web/style.css` — `#map-settings { max-width: 720px; padding: 16px 20px }`; global dialogs `max-height: calc(100dvh - 32px)` and `overflow:auto`. Existing sticky heading and Command visual tokens.
- `command/web/app.js` — `showModal()` opening; map preview and saved map activation; provider approval; retry and import.

## Visual contract
- Existing Command dashboard remains visible behind a centered **720px-max native-style modal** with a muted overlay.
- The design uses existing Command colors: white surfaces, `#17654C` accent, `#24343B` ink, subtle green status and `#D9E2DF` borders.
- Modal sections: persistent title/close; compact **Current map** status; three tabs **Get a Map / Import / My Maps**; scrollable body; one persistent bottom action bar.
- At **1600×900**, design modal 720×768. At **1280×720**, design modal 720×688 with interior body scrolling and visible bottom primary action.
- No separate Maps application shell/header. No UI element crosses the modal border.

## Task ownership
- Get a Map: existing place search, map-centre selection, coordinates, 1/5/10/custom radius and m/km; optional terrain and its component products; local plan preview; automatic provider sequence; exact statuses.
- Progress / partial: measured transferred bytes and provider; accurate completed chunk counts (not fictitious whole-job percent), cancel, use validated partial data, retry only missing.
- Import: local PBF (selected/full), GeoJSON and portable .gmap.zip; preserve local terrain HGT/.gterrain imports under contextual management; avoid network-transfer approval on local imports.
- My Maps: saved map list and selection, active map and blank mode, availability versus visibility of terrain components; source manifest, package export/import, retry, deletion with confirmation and advanced settings.
- Approval: contextual, once per **approved cumulative byte ceiling**, never before the chosen large-source fallback has been checked for viability and resource bounds; show the actual required bytes/provider, not the mock illustrative figures.
- Recovery: precise cause and missing areas, keep verified assets, Resume and Use partial, technical diagnostics scoped to details.

## Preservation & implementation limitations
- **Do not replace** existing SQLite map-library/job ownership, geographic rendering, Overpass/Nominatim, Geofabrik, Skadi/DEM, chunk evidence, transfer approval, source provenance, import/export, or GNSS/SOS overlay logic.
- The illustrations are not real downloaded map tiles. Existing `#map-preview` currently previews request geometry; turning it into an interactive geographic preview is a separate verified implementation step, not implied by these mockups.
- Large-PBF import and viewport completeness require engineering improvements, not styling alone.
- Keep current layer availability statuses, unfiltered GNSS observation controls, dot interval, opacity, coordinates/rectangle compatibility, derived terrain controls and diagnostic/export capabilities accessible from task-specific contextual details. Don't delete them for visual cleanliness.
- Use Figma review as a prerequisite before UI implementation. This ZIP contains SVG vector layouts and screenshots, **not** a native Figma component library, a working web app, or changes in the repository.
