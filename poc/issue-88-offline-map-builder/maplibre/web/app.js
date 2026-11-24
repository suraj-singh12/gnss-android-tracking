// Offline MapLibre POC runtime (not GNSS Command). All assets are served by the
// local POC server; the page makes no external request.
import * as maplibregl from "/static/maplibre-gl.mjs";
import { buildStyle } from "/style.js";

maplibregl.setWorkerUrl("/static/maplibre-gl-worker.mjs");
const t0 = performance.now();
const metrics = (window.__poc = { styleMs: null, loadMs: null, firstIdleMs: null, errors: [] });

const cfg = await (await fetch("/static/config.json")).json();
document.getElementById("area").textContent = `${cfg.title} ${cfg.radius_km} km`;
document.title = `Offline MapLibre POC · ${cfg.title} · not GNSS Command`;
const map = new maplibregl.Map({
  container: "map",
  style: buildStyle(location.origin),
  center: [cfg.centre[1], cfg.centre[0]],
  zoom: 11,
  minZoom: 8,
  maxZoom: 18,
  maxBounds: cfg.bounds,
  hash: true,
  renderWorldCopies: false,
  attributionControl: { compact: false, customAttribution: "Offline POC · not GNSS Command" },
});
window.map = map;
map.addControl(new maplibregl.NavigationControl({ visualizePitch: false, showCompass: true }), "top-right");
map.addControl(new maplibregl.ScaleControl({ unit: "metric" }), "bottom-left");
map.dragRotate.disable();
map.touchZoomRotate.disableRotation();

// ---- restrained icons drawn locally (no sprite download) ----
const PX = 2;
function canvas(w, h) {
  const c = document.createElement("canvas");
  c.width = w * PX;
  c.height = h * PX;
  const g = c.getContext("2d");
  g.scale(PX, PX);
  return [c, g];
}
const POI_COLORS = { viewpoint: "#8e4fc6", attraction: "#8e4fc6", lodging: "#d6408d", hut: "#9a6a42", camp: "#2f8a55", park: "#2f8a55",
  worship: "#757c84", health: "#d2453b", emergency: "#d2453b", fuel: "#6b7279", water: "#3b80c4", lift: "#6b7279" };
const GLYPH = {
  viewpoint: (g) => { g.fillRect(-3.5, -2, 7, 5); g.beginPath(); g.arc(0, 0.5, 1.6, 0, 7); g.fillStyle = POI_COLORS.viewpoint; g.fill(); g.fillStyle = "#fff"; g.fillRect(-1.5, -3.2, 3, 1.4); },
  attraction: (g) => { g.beginPath(); for (let i = 0; i < 10; i++) { const r = i % 2 ? 1.6 : 4, a = (i * Math.PI) / 5 - Math.PI / 2; g.lineTo(r * Math.cos(a), r * Math.sin(a)); } g.fill(); },
  lodging: (g) => { g.fillRect(-4, -1, 8, 3); g.fillRect(-4, -3, 1.5, 6); g.fillRect(-1.5, -2.5, 3, 1.5); },
  hut: (g) => { g.beginPath(); g.moveTo(-4, 0); g.lineTo(0, -4); g.lineTo(4, 0); g.lineTo(2.8, 0); g.lineTo(2.8, 3.5); g.lineTo(-2.8, 3.5); g.lineTo(-2.8, 0); g.fill(); },
  camp: (g) => { g.beginPath(); g.moveTo(-4, 3.5); g.lineTo(0, -3.5); g.lineTo(4, 3.5); g.fill(); },
  park: (g) => { g.beginPath(); g.moveTo(0, -4); g.lineTo(3.5, 1.5); g.lineTo(-3.5, 1.5); g.fill(); g.fillRect(-0.8, 1.5, 1.6, 2.5); },
  worship: (g) => { g.beginPath(); g.arc(0, 1, 3.2, Math.PI, 0); g.fill(); g.fillRect(-3.2, 1, 6.4, 2.5); g.fillRect(-0.5, -4, 1, 2.5); },
  health: (g) => { g.fillRect(-1.2, -3.5, 2.4, 7); g.fillRect(-3.5, -1.2, 7, 2.4); },
  emergency: (g) => { g.fillRect(-1.2, -3.5, 2.4, 7); g.fillRect(-3.5, -1.2, 7, 2.4); },
  fuel: (g) => { g.fillRect(-3, -3.5, 4.5, 7); g.fillRect(2, -1.5, 1.2, 4); },
  water: (g) => { g.beginPath(); g.moveTo(0, -4); g.quadraticCurveTo(3.8, 0.5, 0, 3.8); g.quadraticCurveTo(-3.8, 0.5, 0, -4); g.fill(); },
  lift: (g) => { g.fillRect(-4, -3, 8, 1); g.fillRect(-2, -2, 4, 5); },
};
function poiIcon(kind) {
  const [c, g] = canvas(18, 18);
  g.translate(9, 9);
  g.beginPath();
  g.arc(0, 0, 7.8, 0, 7);
  g.fillStyle = POI_COLORS[kind] || "#6b7279";
  g.fill();
  g.lineWidth = 1.2;
  g.strokeStyle = "#fff";
  g.stroke();
  g.fillStyle = "#fff";
  (GLYPH[kind] || GLYPH.attraction)(g);
  return c;
}
function peakIcon(saddle) {
  const [c, g] = canvas(10, 9);
  g.beginPath();
  if (saddle) { g.moveTo(1, 2); g.quadraticCurveTo(5, 9, 9, 2); g.lineWidth = 1.6; g.strokeStyle = "#8a7660"; g.stroke(); }
  else { g.moveTo(5, 1); g.lineTo(9.2, 8); g.lineTo(0.8, 8); g.closePath(); g.fillStyle = "#8a7660"; g.fill(); }
  return c;
}
function shield() {
  const [c, g] = canvas(20, 16);
  g.beginPath();
  g.roundRect(1, 1, 18, 14, 3);
  g.fillStyle = "#fbd668";
  g.fill();
  g.lineWidth = 1;
  g.strokeStyle = "#d4a735";
  g.stroke();
  return c;
}
function add(id, c, extra = {}) {
  const g = c.getContext("2d");
  if (!map.hasImage(id)) map.addImage(id, g.getImageData(0, 0, c.width, c.height), { pixelRatio: PX, ...extra });
}
map.setMissingStyleImageResolver((id) => {
  if (id === "shield") add(id, shield(), { stretchX: [[8, 32]], stretchY: [[8, 24]], content: [6, 4, 34, 28] });
  else if (id === "peak" || id === "saddle") add(id, peakIcon(id === "saddle"));
  else if (id.startsWith("poi-")) add(id, poiIcon(id.slice(4)));
});

map.on("styledata", () => { if (metrics.styleMs === null) metrics.styleMs = Math.round(performance.now() - t0); });
map.on("load", () => { metrics.loadMs = Math.round(performance.now() - t0); });
map.once("idle", () => { metrics.firstIdleMs = Math.round(performance.now() - t0); });
map.on("error", (e) => metrics.errors.push(String(e.error && e.error.message || e.error)));
const zoomEl = document.getElementById("zoom");
const showZoom = () => { zoomEl.textContent = `z${map.getZoom().toFixed(1)}`; };
map.on("zoom", showZoom);
map.on("load", showZoom);
