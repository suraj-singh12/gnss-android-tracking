# Issue #95 design proposal — corrected integration prototype (v2)

## Correction round: what was wrong with the first prototype

The first prototype re-created the Command workspace shell from the V5.14
reference instead of reusing the running application. Comparison against the
actual current `dev` application (build `e4b742c`, populated with live synthetic
parties through the real ingestion path) showed the surrounding UI had
regressed in at least these ways:

1. Header: wrong colour, wrong controls, invented tabs ("Records Tools Map
   Appearance SOS"); real header is compact dark-green with the LIVE mode
   pill, Records/Tools/Maps links, theme glyph and emergency SOS button.
2. Parties panel: wrong structure — real panel has search, All/Online/
   Attention filter tabs, checkbox rows with per-party status lines.
3. Selected-party details panel missing entirely; real panel carries SOS
   acknowledgement banner, freshness, accuracy, sync, evidence and Phone
   controls.
4. Context bar invented ("Enhanced interactive background · …" banner does
   not exist in production).
5. Map toolbar: wrong styling and missing History control.
6. Map tag: wrong position and content vs. the real compact tag.
7. Canvas background, legend and empty-state treatment diverged.
8. Operational overlays were re-implemented approximations, not the
   production rendering (label collision handling, SOS diamond, bearing
   markers, accuracy indicators, stale/live styling).
9. Prototype-harness noise inside the app frame (scenario dropdown, banner).

## The corrected prototype

The corrected prototype does not re-create anything. It uses the production
assets **byte-identical** (`index.html`, `style.css`, `app.js` and every other
script copied verbatim from `dev` @ `e4b742c`) against the **real Command
daemon** with parties ingested through the real protocol path. The only
additions:

- two lines in the copied `index.html` (shim stylesheet + shim script),
- `issue95-shim.js` / `issue95-shim.css` — the demonstrated integration seam.

The seam itself also changed architecturally for the better: **the production
camera remains the single authority.** The existing drag, wheel, keyboard
(arrows/+/−/F), zoom/fit/focus controls drive the exact same `viewport` math
they always have; the MapLibre canvas underneath follows it every frame using
the identical Web-Mercator transform, so overlay alignment is exact by
construction, not approximation. No interaction changes at all — decision
point 1 from the first proposal (smooth gestures vs. stepped emulation) is
thereby resolved in favour of zero interaction change, unless the operator
explicitly wants gesture upgrades.

Rendering states demonstrated (all against real production chrome):

- Populated live day — enhanced cartography beneath unchanged overlays
  (verified: ridge/village/road detail exactly aligned with party markers).
- Pan alignment — dragging the canvas with the existing drag interaction moves
  cartography and overlays as one rigid body; verified frame-exact.
- Night — derived night variant of the same generated style, switched by the
  existing appearance control.
- Coverage-incomplete — panning beyond the package coverage (using the
  production drag interaction) raises the disclosure in the existing
  projection-status surface; positions/tracks unaffected. Production detection
  uses the Format 2 coverage metadata — immutable and validated at import —
  not runtime tile errors (a 404 tile is legitimately "no data" in tile
  semantics).
- Tiles loading — transient disclosure on the same surface while tiles stream.
- WebGL failure (`?webgl=fail` review forcing) — enhanced layer never
  activates; exactly the current legacy blank-canvas experience with an
  explicit cause message, all overlays and controls unchanged.

## Prototype limitations vs. genuine integration problems

Prototype limitations (review harness only, not design decisions):

- WebGL-failure forcing via a query parameter; production detection is the
  real WebGL initialisation path. Coverage detection in the review build uses
  the POC bundle's coverage bounding box in the role of the Format 2 coverage
  metadata.
- The map-mode tag text is overridden by the shim; production would set it in
  `app.js` where the legacy "Blank canvas / Offline geographic map" text lives.
- The Layers menu is not yet extended with enhanced-map layer toggles
  (labels/hillshade) — an open design decision, not an oversight.
- Tiles served from the POC bundle directory; production serves them from
  Format 2 storage through the existing `/local/maps/...` endpoints.

Genuine integration considerations (verified or noted):

- WebGL canvas composites correctly beneath the transparent operational SVG —
  verified visually.
- The single-browser occupancy lease applies to the dashboard regardless of
  the enhanced layer; unchanged.
- Zoom depth: vector tiles reach z14, tile pyramid z6–14; beyond that
  MapLibre overzooms — resolution softens but alignment never degrades.
  Operational zoom ceiling (scale ≤ 100 px/m ≈ z24) can go far beyond tile
  depth; whether to cap enhanced-mode zoom at depth or accept overzoom is a
  real decision for implementation.
- Attribution: required OSM/ESA/DEM attribution renders inside the canvas
  corner via MapLibre's control, matching the existing canvas legend voice.
