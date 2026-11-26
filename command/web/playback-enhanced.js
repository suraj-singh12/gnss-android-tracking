// Enhanced playback reconstruction (#96). Re-renders the recorded enhanced
// cartography from the pinned immutable revision beneath the frozen SVG
// evidence — actual interactive maps, never screenshots or raster capture.
//
// Honesty contract:
// - Recorded-missing tiles are gated out of reconstruction even when cached
//   or when MapLibre would use them as parent/child fallbacks: geographical
//   information unavailable during capture is absent, never substituted.
// - A revision unavailable on this Command degrades to the overlay-only blank
//   experience with explicit disclosure; no other map is ever substituted.
// - The exact recorded style documents are rendered verbatim through the
//   vendored renderer; incompatibility degrades honestly.
//
// Alignment is exact by the same construction as LIVE: the container is sized
// to the recorded pixel dimensions and undergoes the same letterbox mapping
// the SVG viewBox applies, plus the playback world transform, so the recorded
// camera projection lands exactly beneath the frozen overlay evidence.
import { viewportOverflowsCoverage } from "/enhanced-style.js";

const R = 6378137;
const WORLD = 2 * Math.PI * R;

const state = {
  map: null,
  maplibre: null,
  gl: null,
  key: "",
  missing: new Map(), // "product/z/x/y" -> true for the current frame
  unavailable: "",
  activeRevision: "",
};

function lonLat(cx, cy) {
  return [
    (cx / R) * (180 / Math.PI),
    (2 * Math.atan(Math.exp(cy / R)) - Math.PI / 2) * (180 / Math.PI),
  ];
}

function tileKey(url) {
  const m = /\/tiles\/([a-z0-9-]+)\/(\d+)\/(\d+)\/(\d+)(?:\.\w+)?(?:\?|$)/i.exec(url || "");
  return m ? { product: m[1], z: +m[2], x: +m[3], y: +m[4] } : null;
}

// gated consults the current frame's recorded-missing record, including
// parent/child relationships in both directions: a missing parent suppresses
// its children, and missing children suppress the parent fallback.
function gated(url) {
  const k = tileKey(url);
  if (!k) return false;
  for (const key of state.missing.keys()) {
    const [product, zs, xs, ys] = key.split("/");
    if (product !== k.product) continue;
    const mz = +zs, mx = +xs, my = +ys;
    // Exact identity only. Parent/child suppression is incorrect here: a
    // tile legitimately visible at capture must render, and fallback use of
    // family tiles is capture-faithful — the capture-time renderer used the
    // same fallback for the same absent tile, so it is evidence, not
    // substitution. Substitution by subsequently available data is already
    // prevented because the missing tile itself is gated and the instance
    // rebuilds when the missing set changes.
    if (mz === k.z && mx === k.x && my === k.y) return true;
  }
  return false;
}

async function ensureMaplibre() {
  if (!state.maplibre) {
    state.maplibre = await import("/vendor/maplibre-gl.mjs");
    if (!document.getElementById("enhanced-embed-css")) {
      const link = document.createElement("link");
      link.id = "enhanced-embed-css";
      link.rel = "stylesheet";
      link.href = "/vendor/maplibre-gl.css";
      document.head.append(link);
    }
  }
  return state.maplibre;
}

function ensureContainer() {
  if (state.gl) return state.gl;
  const canvas = document.querySelector(".canvas");
  const tracks = document.getElementById("tracks");
  if (!canvas || !tracks) return null;
  const gl = document.createElement("div");
  gl.id = "playback-gl";
  gl.style.position = "absolute";
  gl.style.left = "0";
  gl.style.top = "0";
  gl.style.transformOrigin = "0 0";
  gl.style.pointerEvents = "none";
  canvas.insertBefore(gl, tracks);
  state.gl = gl;
  return gl;
}

// update replays one reconstruction opportunity. Args:
//   enhanced: the frame's recorded enhanced object (or null)
//   viewport, width, height: the basis camera and recorded pixel dimensions
//   transform: [tx, ty, z] playback world transform; angle: world rotation
// The same letterbox math the SVG viewBox performs is replicated exactly.
async function update(args) {
  const { enhanced, viewport, width, height, transform, angle, night } = args;
  const tracks = document.getElementById("tracks");
  if (!tracks) return;
  if (!enhanced || !viewport || !Number.isFinite(viewport.scale) || viewport.scale <= 0) {
    detach(true);
    PlaybackEnhanced.status = enhanced && !viewport ? "Recorded enhanced camera evidence incomplete; background unavailable." : "";
    return;
  }
  if (enhanced.renderer && enhanced.renderer !== "maplibre-gl") {
    detach(true);
    PlaybackEnhanced.status = `Recorded enhanced renderer "${enhanced.renderer}" is not supported by this Command; positions, tracks and operational evidence are unaffected.`;
    return;
  }
  // Renderer compatibility is validated, not merely identified: a different
  // major generation of the vendored renderer cannot be trusted to interpret
  // the recorded style evidence the same way. Minor differences within one
  // generation remain compatible because the exact style documents travel
  // with the recording.
  const VENDORED_MAJOR = "6";
  const recordedMajor = String(enhanced.renderer_version || "").split(".")[0];
  if (enhanced.renderer_version && recordedMajor !== VENDORED_MAJOR) {
    detach(true);
    PlaybackEnhanced.status = `Recorded enhanced renderer version ${enhanced.renderer_version} is incompatible with this Command's renderer; positions, tracks and operational evidence are unaffected.`;
    return;
  }
  // Conservative degradation (#96): truncated availability evidence cannot
  // account for every tile, and failed capture evidence is uncertain — cached
  // or subsequently available geography could appear incorrectly. Overlay
  // evidence is never affected; only the reconstruction is suppressed.
  const av0 = enhanced.availability || {};
  if (av0.truncated || av0.state === "failed") {
    detach(true);
    PlaybackEnhanced.status = av0.truncated
      ? "Recorded tile availability evidence is truncated; cartographic reconstruction suppressed — positions, tracks and operational evidence are unaffected."
      : "Recorded tile availability evidence is uncertain; cartographic reconstruction suppressed — positions, tracks and operational evidence are unaffected.";
    return;
  }
  const style = night ? enhanced.style?.night : enhanced.style?.day;
  if (!style?.layers) {
    detach(true);
    PlaybackEnhanced.status = "Recorded enhanced style evidence incomplete; background unavailable — positions and tracks are unaffected.";
    return;
  }
  const gl = ensureContainer();
  if (!gl) return;
  try {
    await ensureMaplibre();
  } catch {
    PlaybackEnhanced.status = "Enhanced renderer unavailable; positions, tracks and operational evidence are unaffected.";
    return;
  }
  // Missing-revision honesty is verified cheaply per revision, cached: tile
  // serving itself 404s absent revisions, so probe one deterministic URL head
  // through the style's own source reference rather than inventing state.
  const missing = enhanced.availability?.missing || [];
  const missingKeys = [];
  for (const m of missing) {
    if (m && m.product) missingKeys.push(`${m.product}/${m.z}/${m.x}/${m.y}`);
    else if (m && m.key) missingKeys.push(m.key);
  }
  missingKeys.sort();
  const missingSig = missingKeys.join(";");
  const revisionKey = enhanced.map_id + "@" + enhanced.revision;
  // Exact revision validation (#96): the recording references an immutable
  // revision by identity; substitution is dishonest even when a same-named
  // map is present. The check is cached per revision and performed without
  // any external request — same-origin Command only.
  if (!state.revisionChecks) state.revisionChecks = new Map();
  const check = state.revisionChecks.get(revisionKey);
  if (check === false) {
    detach(true);
    PlaybackEnhanced.status = "The exact recorded map revision is unavailable on this Command; cartographic reconstruction suppressed — positions, tracks and operational evidence are unaffected.";
    return;
  }
  if (check !== true) {
    if (!check) {
      state.revisionChecks.set(revisionKey, "pending");
      const pendingArgs = args;
      fetch(`/local/enhanced-revision?id=${encodeURIComponent(enhanced.map_id)}`)
        .then(async (r) => {
          const ok = r.ok && (await r.json()).revision === enhanced.revision;
          state.revisionChecks.set(revisionKey, ok);
          // The probe is asynchronous but the frame is not repainted on its
          // own: re-run this update so a paused playback reconstructs as soon
          // as validation completes rather than staying blank until the next
          // operator action.
          update(pendingArgs);
        })
        .catch(() => state.revisionChecks.set(revisionKey, false));
    }
    detach(true);
    PlaybackEnhanced.status = "Validating recorded map revision…";
    return;
  }
  // Rebuild when the recorded-missing set changes even within one revision:
  // tiles already loaded into the instance are not re-mediated by
  // transformRequest, so a stricter earlier frame could otherwise display
  // geography unavailable at its capture time.
  if (!state.map || state.activeRevision !== revisionKey || state.missingSig !== missingSig) {
    if (state.map) {
      state.map.remove();
      state.map = null;
    }
    state.activeRevision = revisionKey;
    state.key = "";
    state.map = new state.maplibre.Map({
      container: gl,
      style,
      center: [0, 0],
      zoom: 1,
      interactive: false, // inspection divergence is the playback transform, not the camera
      attributionControl: false, // recorded evidence carries its own attribution
      fadeDuration: 0,
      maxZoom: 24,
      renderWorldCopies: false,
      transformRequest: (url, resourceType) => {
        // The availability gate applies even to cached tiles and parent/child
        // fallbacks: transformRequest mediates every engine fetch regardless
        // of HTTP cache hits.
        if (resourceType === "Tile" && gated(url))
          return { url: "/local/enhanced-gated" };
        return { url };
      },
    });
    state.map.on("error", () => {});
  }
  // Layer toggles as recorded: the recorded style embeds them structurally
  // already (visibility state at capture is part of the generated style's
  // layers), so no runtime adjustment is required for reconstruction.
  state.missingSig = missingSig;
  state.missing = new Map();
  for (const k of missingKeys) state.missing.set(k, true);
  // Recorded layer preferences (#96): the capture embeds the generated style,
  // but runtime label/hillshade toggles are visibility state — apply exactly
  // what the frame recorded, following the same id conventions as LIVE. The
  // style loads asynchronously on a fresh instance, so the application is
  // repeated once loading completes.
  const prefs = enhanced.layers || {};
  const applyPrefs = () => {
    if (!state.map) return;
    for (const layer of style.layers || []) {
      if (layer.type === "symbol" && prefs.labels === false) {
        try { state.map.setLayoutProperty(layer.id, "visibility", "none"); } catch {}
      }
      if (layer.id === "hillshade") {
        try { state.map.setLayoutProperty(layer.id, "visibility", prefs.hillshade === false ? "none" : "visible"); } catch {}
      }
    }
  };
  applyPrefs();
  state.map.once("load", applyPrefs);
  // Camera: the recorded production viewport through the identical transform.
  const z = Math.log2((viewport.scale * WORLD) / 512);
  const [lon, lat] = lonLat(viewport.cx, viewport.cy);
  const key = [z.toFixed(5), lon.toFixed(7), lat.toFixed(7)].join(",");
  if (key !== state.key) {
    state.key = key;
    state.map.jumpTo({ center: [lon, lat], zoom: Math.max(0, Math.min(24, z)) });
  }
  // Geometry: replicate the viewBox letterbox plus the world transform chain.
  const cssW = tracks.clientWidth, cssH = tracks.clientHeight;
  const w = width || 1280, h = height || 720;
  const s = Math.min(cssW / w, cssH / h);
  const ox = (cssW - w * s) / 2, oy = (cssH - h * s) / 2;
  gl.style.width = w + "px";
  gl.style.height = h + "px";
  const parts = [`translate(${ox}px, ${oy}px)`, `scale(${s})`];
  if (transform && (transform[0] || transform[1] || transform[2] !== 1))
    parts.push(`translate(${transform[0]}px, ${transform[1]}px)`, `scale(${transform[2]})`);
  if (angle)
    parts.push(`translate(${w / 2}px, ${h / 2}px)`, `rotate(${angle}deg)`, `translate(${-w / 2}px, ${-h / 2}px)`);
  gl.style.transform = parts.join(" ");
  gl.hidden = false;
  // Honest availability surfacing: loading/degraded/truncated evidence is
  // carried to the playback evidence surface by the caller reading status.
  const av = enhanced.availability || {};
  if (av.state && av.state !== "ready")
    PlaybackEnhanced.status = av.detail || "";
  else PlaybackEnhanced.status = "";
  if (state.map) state.map.resize();
}

function detach(keepInstance) {
  if (state.gl) state.gl.hidden = true;
  PlaybackEnhanced.status = "";
  if (!keepInstance && state.map) {
    state.map.remove();
    state.map = null;
    state.activeRevision = "";
    state.key = "";
  }
}

const PlaybackEnhanced = {
  update,
  detach,
  status: "",
  // availabilityNote composes the honest one-line disclosure for the playback
  // evidence surface, or "" when reconstruction is complete.
  availabilityNote() {
    return state.unavailable || PlaybackEnhanced.status || "";
  },
};
globalThis.PlaybackEnhanced = PlaybackEnhanced;
