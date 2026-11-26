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
import { buildStyle } from "/enhanced-style.js";

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
  const [lon, lat] = viewportLonLat(vp);
  const c = state.coverage;
  return lon < c[0] || lon > c[2] || lat < c[1] || lat > c[3];
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
  if (state.tilesLoading && !state.firstPainted)
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
    });
    state.map.on("sourcedataloading", (e) => {
      if (e.isSourceLoaded !== true) state.tilesLoading = true;
    });
    state.map.on("idle", () => {
      const wasFirst = !state.firstPainted;
      state.tilesLoading = false;
      state.firstPainted = true;
      if (wasFirst) repaintProduction();
    });
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
          state.map.jumpTo({ center: [lon, lat], zoom: Math.max(0, Math.min(24, z)) });
        }
      }
    }
    requestAnimationFrame(sync);
  }
  requestAnimationFrame(sync);
}

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
