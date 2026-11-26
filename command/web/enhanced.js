// Enhanced offline map LIVE integration (Issue #95, approved design v2).
//
// Architecture: the existing production camera (`viewport` in app.js) remains
// the single authority. The MapLibre canvas sits beneath the operational
// overlay SVG inside .canvas and follows the production camera every frame
// through the mathematically identical Web-Mercator transform, so overlay
// alignment is exact by construction. No interaction changes: drag, wheel,
// keyboard, zoom/fit/focus controls drive the same viewport math they always
// have. The layer activates only when (a) Command was launched with the
// default-off `-enhanced-map` opt-in, (b) an enhanced (Format 2) map is the
// active map and (c) map mode is offline. Every other state is exactly the
// legacy experience. Rendering is flat mercator, no world copies.
import { buildStyle, viewportOverflowsCoverage } from "/enhanced-style.js";

const R = 6378137;
const WORLD = 2 * Math.PI * R;

// nightize derives the night variant from the same generated style — no second
// asset set. Operator decision: night roads stay subdued so operational
// overlays remain prominent; road fills/casings are remapped to dark blue-grey
// and earth tones rather than their bright day colours.
function nightize(style) {
  const s = JSON.parse(JSON.stringify(style));
  const map = {
    "#f3f2ee": "#17211d", "#a7d3f3": "#1d3d52", "#eeedd9": "#232b23",
    "#dcebcb": "#1f2f22", "#b9dcad": "#24402a", "#e7e3dc": "#2c2c2e",
    "#c6e6bb": "#203626", "#ebe7e2": "#262a28", "#e8e5ee": "#282730",
    "#dcd5cc": "#3a3f44", "#ffffff": "#3c4448",
    // road fills + casings subdued for night (operator decision)
    "#9fb0d6": "#33415a", "#6f80a8": "#232c3d",
    "#a6b4d4": "#35435c", "#7787ad": "#252e40",
    "#b2bedb": "#374560", "#8291b4": "#273041",
    "#c7d0e3": "#39465e", "#97a3c0": "#2a3344",
    "#b6bdca": "#2c3136", "#c9ced7": "#2e3338", "#d3d7de": "#30353a",
    "#b4a283": "#4a4136", "#8c8172": "#3d382f",
  };
  const ink = "#c8d4cd", halo = "rgba(20,30,26,0.92)";
  const visit = (v) => {
    if (typeof v === "string" && map[v]) return map[v];
    if (typeof v === "string" && v === "#2b3137") return ink;
    if (typeof v === "string" && v === "rgba(255,255,255,0.92)") return halo;
    if (Array.isArray(v)) return v.map(visit);
    if (v && typeof v === "object") { for (const k of Object.keys(v)) v[k] = visit(v[k]); }
    return v;
  };
  for (const layer of s.layers) {
    if (layer.paint) layer.paint = visit(layer.paint);
    if (layer.layout) layer.layout = visit(layer.layout);
    if (layer.type === "raster") {
      layer.paint = layer.paint || {};
      layer.paint["raster-brightness-max"] = 0.52;
      layer.paint["raster-saturation"] = -0.25;
    }
    if (layer.type === "hillshade") {
      layer.paint = layer.paint || {};
      layer.paint["hillshade-shadow-color"] = "#0a1410";
      layer.paint["hillshade-highlight-color"] = "#3d4f45";
      layer.paint["hillshade-accent-color"] = "#152420";
      layer.paint["hillshade-exaggeration"] = 0.5;
    }
  }
  return s;
}

const Enhanced = {
  capabilityEnabled: false, // launch-time operator opt-in confirmed
  available: false,         // opt-in confirmed AND WebGL usable
  active: false,            // an enhanced map is currently rendered beneath the overlays
  failed: "",               // permanent failure cause, honestly disclosed
};
globalThis.EnhancedMap = Enhanced;

let capResolve;
globalThis.EnhancedMapReady = new Promise((resolve) => (capResolve = resolve));

const state = {
  capability: false,
  map: null,
  gl: null,
  maplibre: null,
  activeID: "",
  coverage: null,
  styleDay: null,
  styleNight: null,
  tilesLoading: false,
  firstPainted: false,
  booted: false,
  labelsOn: true,
  hillshadeOn: true,
  failedIDs: new Set(),
  activating: false,
  // Capture ledger (#96): bounded record of tile requests and outcomes.
  // Inflight keys are exactly the tiles requested but not yet rendered — the
  // only non-deterministic availability factor for immutable local packages.
  inflight: new Map(),
    failed: new Map(),    // HTTP-failure tile outcomes (response-status evidence)
  errored: new Map(),
  lastMutation: 0,
};

function webglUsable() {
  try {
    const c = document.createElement("canvas");
    return !!(c.getContext("webgl2") || c.getContext("webgl"));
  } catch {
    return false;
  }
}

// coverageUnion computes the union of validated product bounds from the
// immutable Format 2 descriptor. Coverage honesty comes from this package
// metadata — never from runtime tile errors (a missing tile is legitimately
// "no data" in tile semantics).
function coverageUnion(doc) {
  let out = null;
  for (const p of Object.values(doc.products || {})) {
    if (!p.bounds) continue;
    out = out
      ? [Math.min(out[0], p.bounds[0]), Math.min(out[1], p.bounds[1]), Math.max(out[2], p.bounds[2]), Math.max(out[3], p.bounds[3])]
      : [...p.bounds];
  }
  return out;
}

function nightNow() {
  return document.documentElement.dataset.appearance === "night";
}

function viewportLonLat(vp) {
  return [
    (vp.cx / R) * (180 / Math.PI),
    (2 * Math.atan(Math.exp(vp.cy / R)) - Math.PI / 2) * (180 / Math.PI),
  ];
}

function currentViewport() {
  // `viewport` is the production camera: a top-level `let` in app.js, i.e. a
  // global lexical binding shared across scripts (not a window property).
  return typeof viewport !== "undefined" ? viewport : null;
}

function outsideCoverage() {
  const vp = currentViewport();
  if (!vp || !Number.isFinite(vp.cx) || !state.coverage) return false;
  const tracks = document.getElementById("tracks");
  const w = tracks ? tracks.clientWidth : 0, h = tracks ? tracks.clientHeight : 0;
  return viewportOverflowsCoverage(vp.cx, vp.cy, vp.scale, w, h, state.coverage);
}

// statusText contributes the enhanced layer's persistent honesty states to the
// existing projection-status surface. Production draw code merges them; when no
// enhanced state is active this returns "" and production text is untouched.

function statusText() {
  if (Enhanced.failed)
    return "Enhanced map unavailable: " + Enhanced.failed + " Legacy map experience active — positions, tracks and controls are unchanged.";
  if (!Enhanced.active) return "";
  if (outsideCoverage())
    return "Enhanced map coverage incomplete here — positions, tracks and controls remain exact; background may be missing.";
  if (state.tilesLoading)
    return "Enhanced map tiles loading… positions remain exact; background may be incomplete.";
  return "";
}
Enhanced.statusText = statusText;

function applyLayerVisibility() {
  if (!state.map || !state.styleDay) return;
  for (const layer of state.styleDay.layers) {
    const id = layer.id;
    if (layer.type === "symbol") {
      const on = state.labelsOn;
      try { state.map.setLayoutProperty(id, "visibility", on ? "visible" : "none"); } catch {}
    }
    if (id === "hillshade") {
      try { state.map.setLayoutProperty(id, "visibility", state.hillshadeOn ? "visible" : "none"); } catch {}
    }
  }
}

// Layer preferences persist per saved map, following the existing terrain
// layer pattern. Labels default ON (operator decision); hillshade defaults ON
// as core cartography. Both read live from the Layers dialog switches.
function readLayerPrefs(id) {
  state.labelsOn = localStorage.getItem(`gnss-layer-${id}-enhanced-labels`) !== "false";
  state.hillshadeOn = localStorage.getItem(`gnss-layer-${id}-enhanced-hillshade`) !== "false";
  const labels = document.getElementById("layer-enhanced-labels");
  const hillshade = document.getElementById("layer-enhanced-hillshade");
  if (labels) labels.checked = state.labelsOn;
  if (hillshade) hillshade.checked = state.hillshadeOn;
}

async function ensureMap(doc, id) {
  if (!state.maplibre) {
    const [m] = await Promise.all([
      import("/vendor/maplibre-gl.mjs"),
    ]);
    state.maplibre = m;
    if (!document.getElementById("enhanced-embed-css")) {
      const link = document.createElement("link");
      link.id = "enhanced-embed-css";
      link.rel = "stylesheet";
      link.href = "/vendor/maplibre-gl.css";
      document.head.append(link);
    }
  }
  const coverage = coverageUnion(doc);
  const styleDay = buildStyle(id, doc.products, coverage);
  const fresh = !state.map || state.activeID !== id;
  state.coverage = coverage;
  state.styleDay = styleDay;
  state.styleNight = nightize(styleDay);
  state.activeID = id;
  state.firstPainted = false;
  state.tilesLoading = true;
  readLayerPrefs(id);
  if (!state.gl) {
    const canvas = document.querySelector(".canvas");
    const tracks = document.getElementById("tracks");
    if (!canvas || !tracks) throw Error("workspace unavailable");
    const gl = document.createElement("div");
    gl.id = "enhanced-gl";
    canvas.insertBefore(gl, tracks);
    state.gl = gl;
  }
  if (!state.map) {
    // Tile identity is product-scoped (the tile URL carries the product),
    // while MapLibre events carry the style source id. Resolve source ->
    // product from the style's own tile templates once per activation so
    // ledger keys are consistent across request, loaded and error paths.
    state.sourceProducts = {};
    const activeStyle = nightNow() ? state.styleNight : state.styleDay;
    for (const [sid, s] of Object.entries(activeStyle?.sources || {})) {
      const m = /\/tiles\/([a-z0-9-]+)\//i.exec((s.tiles || [""])[0] || "");
      if (m) state.sourceProducts[sid] = m[1];
    }
    state.map = new state.maplibre.Map({
      container: state.gl,
      style: nightNow() ? state.styleNight : state.styleDay,
      center: [0, 0],
      zoom: 1,
      interactive: false, // camera authority stays with the existing UI
      attributionControl: { compact: true },
      fadeDuration: 0,
      maxZoom: 24, // operator decision: overzoom permitted; operational zoom range unchanged
      renderWorldCopies: false, // flat, no world copies
      transformRequest: (url, resourceType) => {
        // Tile-request ledger (#96 capture evidence). Keys are deterministic
        // per product; recording outcomes does not alter the request itself.
        if (resourceType === "Tile") {
          const key = tileKeyFromURL(url);
          if (key) {
            state.inflight.set(key, performance.now());
            if (state.inflight.size > 4096) state.inflight.delete(state.inflight.keys().next().value);
            state.lastMutation = performance.now();
          }
        }
        return { url };
      },
    });
    state.map.on("sourcedataloading", (e) => {
      if (e.isSourceLoaded !== true) state.tilesLoading = true;
      state.lastMutation = performance.now();
    });
    state.map.on("sourcedata", (e) => {
      // A source reporting loaded means its outstanding tile work for the
      // current view completed: clear matching inflight keys. Unconfirmed keys
      // remain honestly inflight.
      if (e.isSourceLoaded === true && e.sourceId) {
        const product = state.sourceProducts?.[e.sourceId] || e.sourceId;
        const prefix = product + "/";
        for (const key of state.inflight.keys())
          if (key.startsWith(prefix)) state.inflight.delete(key);
      }
      state.lastMutation = performance.now();
    });
    state.map.on("error", (e) => {
      // Tile-level failures are recorded honestly for capture. Best effort:
      // the event does not always carry the tile coordinate, in which case the
      // inflight key remains until the bounded ledger evicts it.
      const t = e?.tile;
      if (t?.tileID?.canonical && e?.sourceId) {
        const c = t.tileID.canonical;
        const key = `${state.sourceProducts?.[e.sourceId] || e.sourceId}/${c.z}/${c.x}/${c.y}`;
        state.inflight.delete(key);
        state.errored.set(key, performance.now());
        if (state.errored.size > 1024) state.errored.delete(state.errored.keys().next().value);
      }
    });
    state.map.on("idle", () => {
      state.tilesLoading = false;
      state.firstPainted = true;
      repaintProduction();
    });
    state.map.on("sourcedataloading", () => repaintProduction());
    // Runtime WebGL context loss after successful initialisation: fail openly
    // — never silently — with the operational overlays unaffected by
    // construction. The context is not recoverable through MapLibre.
    const contextLost = () => {
      if (!Enhanced.active) return;
      deactivate();
      if (state.activeID) state.failedIDs.add(state.activeID);
      Enhanced.failed = "WebGL context lost.";
      repaintProduction();
    };
    // Registered directly on the drawing canvas: event delivery for real GPU
    // loss must not depend on container bubbling behaviour.
    const glCanvas = state.gl.querySelector("canvas");
    if (glCanvas) glCanvas.addEventListener("webglcontextlost", contextLost);
    state.gl.addEventListener("webglcontextlost", contextLost);
    state.map.on("error", () => {
      // Individual tile absence is legitimate "no data"; sustained rendering
      // failure still must not corrupt the operational overlays, which are
      // independent of this canvas by construction. No silent state change.
    });
    // Appearance follows the existing Day/Night control, derived variant only.
    new MutationObserver(() => {
      if (!state.map || !state.styleDay) return;
      state.map.setStyle(nightNow() ? state.styleNight : state.styleDay, { diff: true });
      applyLayerVisibility();
    }).observe(document.documentElement, { attributes: true, attributeFilter: ["data-appearance"] });
  } else if (fresh) {
    state.map.setStyle(nightNow() ? state.styleNight : state.styleDay);
  }
  applyLayerVisibility();
}

function deactivate() {
  Enhanced.active = false;
  if (state.gl) state.gl.hidden = true;
}

function repaintProduction() {
  if (typeof draw === "function") draw();
}

async function activate(doc, id) {
  if (state.activating) return; // the rAF sync loop must not re-enter in flight
  state.activating = true;
  try {
    await ensureMap(doc, id);
    state.failedIDs.delete(id);
    Enhanced.failed = "";
    state.gl.hidden = false;
    Enhanced.active = true;
    repaintProduction();
  } catch (e) {
    deactivate();
    state.failedIDs.add(id); // this map stays failed; other enhanced maps retry cleanly
    Enhanced.failed = (e && e.message) || "initialisation failed.";
    repaintProduction();
  } finally {
    state.activating = false;
  }
}

function boot() {
  if (state.booted) return;
  state.booted = true;
  let lastKey = "";
  function sync() {
    // Activation state follows the existing mode machinery: enhanced rendering
    // shows only when an enhanced map is the active map and map mode is offline.
    const em = typeof enhancedMap !== "undefined" ? enhancedMap : null;
    const modeEl = document.getElementById("map-mode");
    const shouldShow = !!em && state.capability && !state.failedIDs.has(em.id) && modeEl && modeEl.value === "offline" && !window.HistoricalPlayback?.active;
    if (shouldShow && (!Enhanced.active || state.activeID !== em.id)) activate(em.doc, em.id);
    else if (!shouldShow && Enhanced.active) deactivate();
    // Frame-coherent camera follow: x = (px - cx) * scale + w/2 and
    // y = h/2 - (py - cy) * scale is exactly MapLibre's own Web-Mercator
    // transform when scale = 512 * 2^z / WORLD, so alignment is exact.
    // (MapLibre's world is 512 px wide at zoom 0 — verified against the
    // vendored build — not 256.)
    if (Enhanced.active && state.map) {
      const vp = currentViewport();
      if (vp && Number.isFinite(vp.cx) && Number.isFinite(vp.scale) && vp.scale > 0) {
        const z = Math.log2((vp.scale * WORLD) / 512);
        const [lon, lat] = viewportLonLat(vp);
        const tracks = document.getElementById("tracks");
        const w = tracks ? tracks.clientWidth : 0, h = tracks ? tracks.clientHeight : 0;
        const key = [z.toFixed(5), lon.toFixed(7), lat.toFixed(7), w, h].join(",");
        if (key !== lastKey) {
          lastKey = key;
          state.lastMutation = performance.now();
          state.map.jumpTo({ center: [lon, lat], zoom: Math.max(0, Math.min(24, z)) });
        }
      }
    }
    requestAnimationFrame(sync);
  }
  requestAnimationFrame(sync);
}

// tileKeyFromURL parses the deterministic tile identity out of our own tile
// URLs: /local/maps/{id}/tiles/{product}/{z}/{x}/{y}.
function tileKeyFromURL(url) {
  const m = /\/tiles\/([a-z0-9-]+)\/(\d+)\/(\d+)\/(\d+)(?:\.\w+)?(?:\?|$)/i.exec(url || "");
  return m ? `${m[1]}/${+m[2]}/${+m[3]}/${+m[4]}` : "";
}
Enhanced.tileKeyFromURL = tileKeyFromURL;

// Per-tile outcome evidence (#96): tile rendering success is never inferred
// from source completion — vector 404s are empty loads in the renderer and
// emit no error. The deterministic same-origin tile URLs make response
// statuses authoritative, so exactly those responses are observed through a
// fetch wrapper. The wrapper only inspects status and never alters the
// request or response; non-tile traffic passes through untouched.
if (!globalThis.__enhancedTileEvidenceInstalled) {
  globalThis.__enhancedTileEvidenceInstalled = true;
  const originalFetch = globalThis.fetch.bind(globalThis);
  globalThis.fetch = async function enhancedTileEvidenceFetch(input, init) {
    const response = await originalFetch(input, init);
    try {
      const url = typeof input === "string" ? input : input?.url;
      const key = tileKeyFromURL(url || "");
      if (key && state.inflight) {
        state.inflight.delete(key);
        if (!response.ok) {
          state.failed.set(key, performance.now());
          if (state.failed.size > 4096) state.failed.delete(state.failed.keys().next().value);
          state.lastMutation = performance.now();
        } else {
          // A later success is authoritative recovery evidence: prior failure
          // records for the same tile are cleared so playback never gates
          // geography that was demonstrably visible at capture.
          state.failed.delete(key);
          state.errored.delete(key);
        }
      }
    } catch { /* evidence bookkeeping never interferes with rendering */ }
    return response;
  };
}

// captureState is the recording evidence surface (#96): what the enhanced
// renderer actually presented at the capture moment, honestly. Positions never
// wait on this — it is read synchronously inside the existing capture pump.
const MISSING_BOUND = 64;
Enhanced.captureState = function captureState() {
  if (!Enhanced.active || !state.map) return null;
  const now = performance.now();
  const missing = [];
  let truncated = false;
  const seen = new Set();
  const consider = (key, kind) => {
    if (seen.has(key + kind)) return;
    if (missing.length >= MISSING_BOUND) { truncated = true; return; }
    seen.add(key + kind);
    const parts = key.split("/");
    missing.push({ product: parts[0], z: +parts[1], x: +parts[2], y: +parts[3], kind });
  };
  // Visible-tile geometry shared by eviction and deterministic absence: the
  // integer tile pyramid coordinates covering the current viewport.
  const vp0 = currentViewport();
  const tracks0 = document.getElementById("tracks");
  const w0 = tracks0 ? tracks0.clientWidth : 0, h0 = tracks0 ? tracks0.clientHeight : 0;
  const visibleKeys = new Set();
  let coverageRect = null;
  if (vp0 && Number.isFinite(vp0.cx) && w0 > 0) {
    const R = 6378137, WORLD = 2 * Math.PI * R;
    const z = Math.max(0, Math.min(14, Math.floor(Math.log2((vp0.scale * WORLD) / 512))));
    const n = 2 ** z;
    const xAt = (mx) => ((mx / R) * (180 / Math.PI) + 180) / 360 * n;
    const yAt = (my) => {
      const lat = (2 * Math.atan(Math.exp(my / R)) - Math.PI / 2) * (180 / Math.PI);
      return (1 - Math.log(Math.tan(lat * Math.PI / 180) + 1 / Math.cos(lat * Math.PI / 180)) / Math.PI) / 2 * n;
    };
    const x0 = Math.floor(xAt(vp0.cx - w0 / 2 / vp0.scale)), x1 = Math.floor(xAt(vp0.cx + w0 / 2 / vp0.scale));
    const y0 = Math.floor(yAt(vp0.cy + h0 / 2 / vp0.scale)), y1 = Math.floor(yAt(vp0.cy - h0 / 2 / vp0.scale));
    if ((x1 - x0 + 1) * (y1 - y0 + 1) <= 256) {
      coverageRect = { z, x0, x1, y0, y1, n };
      for (let x = Math.max(0, x0); x <= Math.min(n - 1, x1); x++)
        for (let y = Math.max(0, y0); y <= Math.min(n - 1, y1); y++)
          visibleKeys.add(`${z}/${x}/${y}`);
    }
  }
  const inView = (key) => visibleKeys.has(key.split("/").slice(1).join("/"));
  for (const [key, at] of state.inflight) {
    // Evidence of tiles still inside the viewport never expires: the renderer
    // caches empty outcomes and stops re-requesting, but the absence persists.
    if (now - at > 30000 && !inView(key)) { state.inflight.delete(key); continue; }
    consider(key, "loading");
  }
  for (const [key, at] of state.errored) {
    if (now - at > 30000 && !inView(key)) { state.errored.delete(key); continue; }
    consider(key, "error");
  }
  for (const [key, at] of state.failed) {
    if (now - at > 30000 && !inView(key)) { state.failed.delete(key); continue; }
    consider(key, "unavailable");
  }
  // Deterministic absence (#96): tile existence inside an immutable revision
  // is fixed by the validated package coverage — tiles whose centre falls
  // outside coverage are definitively absent at capture for every product,
  // regardless of how the renderer reports their outcome. This evidence is
  // computed from metadata, never inferred from source completion.
  if (coverageRect && state.coverage) {
    const [cw, cs, ce, cn] = state.coverage;
    const { z, x0, x1, y0, y1, n } = coverageRect;
    const lonAtX = (x) => (x + 0.5) / n * 360 - 180;
    const latAtY = (y) => {
      const t = Math.PI * (1 - 2 * (y + 0.5) / n);
      return Math.atan(Math.sinh(t)) * 180 / Math.PI;
    };
    for (const product of ["vector", "landcover", "dem"]) {
      for (let x = Math.max(0, x0); x <= Math.min(n - 1, x1); x++)
        for (let y = Math.max(0, y0); y <= Math.min(n - 1, y1); y++) {
          const lon = lonAtX(x), lat = latAtY(y);
          if (lon < cw || lon > ce || lat < cs || lat > cn)
            consider(`${product}/${z}/${x}/${y}`, "unavailable");
        }
    }
  }
  const vp = currentViewport();
  const tracks = document.getElementById("tracks");
  const w = tracks ? tracks.clientWidth : 0, h = tracks ? tracks.clientHeight : 0;
  const overflow = vp && state.coverage ? viewportOverflowsCoverage(vp.cx, vp.cy, vp.scale, w, h, state.coverage) : false;
  // Settlement: loaded AND idle AND no camera/style/source mutation inside the
  // stability window. Never blocks capture — the flag is evidence, not a gate.
  const settled = state.firstPainted && !state.tilesLoading && missing.length === 0 && now - state.lastMutation > 250;
  const availState = Enhanced.failed
    ? "failed"
    : overflow
      ? "coverage-incomplete"
      : missing.some((m) => m.kind === "error" || m.kind === "unavailable")
        ? "degraded"
        : missing.length || state.tilesLoading
          ? "loading"
          : "ready";
  const em = typeof enhancedMap !== "undefined" ? enhancedMap : null;
  return {
    renderer: "maplibre-gl",
    // The vendored bundle does not re-export its version; the constant is
    // pinned here beside the vendored artifact it describes (vendor/
    // maplibre-gl.mjs — update both together).
    renderer_version: state.maplibre?.version || "6.19.0",
    map_id: em?.id || state.activeID,
    revision: em?.doc?.revision || "",
    style: { day: state.styleDay, night: state.styleNight },
    layers: { labels: state.labelsOn, hillshade: state.hillshadeOn },
    availability: {
      state: availState,
      settled,
      missing,
      truncated,
      detail: statusText(),
    },
  };
};

// Capability discovery: the launch-time operator opt-in. Without it the module
// never loads MapLibre, never activates and changes nothing.
fetch("/local/enhanced-map-capability")
  .then((r) => (r.ok ? r.json() : { enabled: false }))
  .then((cap) => {
    state.capability = !!cap.enabled;
    Enhanced.capabilityEnabled = state.capability;
    if (state.capability && webglUsable()) {
      Enhanced.available = true;
    } else if (state.capability) {
      Enhanced.failed = "WebGL initialisation failed.";
    }
    boot();
    capResolve();
  })
  .catch(() => {
    capResolve();
  });

// Layers dialog switches apply live, consistent with the existing terrain
// layer switches, and persist per saved map.
addEventListener("change", (e) => {
  if (e.target?.id !== "layer-enhanced-labels" && e.target?.id !== "layer-enhanced-hillshade") return;
  const id = state.activeID;
  if (!id) return;
  if (e.target.id === "layer-enhanced-labels") {
    state.labelsOn = e.target.checked;
    localStorage.setItem(`gnss-layer-${id}-enhanced-labels`, String(e.target.checked));
  } else {
    state.hillshadeOn = e.target.checked;
    localStorage.setItem(`gnss-layer-${id}-enhanced-hillshade`, String(e.target.checked));
  }
  applyLayerVisibility();
});
