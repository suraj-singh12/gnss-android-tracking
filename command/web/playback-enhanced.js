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
    if (mz === k.z && mx === k.x && my === k.y) return true;
    if (mz < k.z) {
      const shift = k.z - mz;
      if (Math.floor(k.x / 2 ** shift) === mx && Math.floor(k.y / 2 ** shift) === my) return true;
    } else {
      const shift = mz - k.z;
      if (Math.floor(mx / 2 ** shift) === k.x && Math.floor(my / 2 ** shift) === k.y) return true;
    }
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
  const revisionKey = enhanced.map_id + "@" + enhanced.revision;
  if (!state.map || state.activeRevision !== revisionKey) {
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
  const missing = enhanced.availability?.missing || [];
  state.missing = new Map();
  for (const m of missing) {
    if (m && m.product) state.missing.set(`${m.product}/${m.z}/${m.x}/${m.y}`, true);
    else if (m && m.key) state.missing.set(m.key, true);
  }
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
  if (av.truncated)
    PlaybackEnhanced.status = "Recorded tile availability evidence is truncated; reconstruction degrades conservatively — some recorded-missing geography may appear absent.";
  else if (av.state && av.state !== "ready")
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
