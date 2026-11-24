// Issue #88 POC reference viewer. Dependency-free Web Mercator canvas renderer
// for evaluating POC layers offline. NOT GNSS Command and not a production
// renderer: it exists to show what the prepared data contains.
"use strict";
(() => {
  const canvas = document.getElementById("map");
  const ctx = canvas.getContext("2d");
  const note = document.getElementById("note");
  const TILE = 256;
  const state = { z: 11, cx: 0, cy: 0, mode: "topo", meta: null, data: {}, tiles: new Map(), dpr: window.devicePixelRatio || 1 };
  const metrics = (window.__poc = { draws: [], ready: false });
  const RASTER_MAX = { imagery: 13, landcover: 13, hillshade: 12 };
  const MODES = {
    topo: { rasters: [["landcover", 0.35], ["hillshade", 0.45]], contours: true, vectors: true, labels: true },
    terrain: { rasters: [["hillshade", 0.85]], contours: true, vectors: "light", labels: true },
    satellite: { rasters: [["imagery", 1]], contours: false, vectors: false, labels: false },
    hybrid: { rasters: [["imagery", 1]], contours: false, vectors: "hybrid", labels: true },
  };

  const project = (lon, lat) => {
    const s = Math.sin((lat * Math.PI) / 180);
    return [((lon + 180) / 360) * TILE, (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * TILE];
  };
  const unproject = (x, y) => {
    const n = Math.PI - (2 * Math.PI * y) / TILE;
    return [(x / TILE) * 360 - 180, (180 / Math.PI) * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)))];
  };

  function prep(fc) {
    for (const f of fc.features) {
      const g = f.geometry;
      let minx = Infinity, miny = Infinity, maxx = -Infinity, maxy = -Infinity;
      const conv = (c) => {
        const p = project(c[0], c[1]);
        if (p[0] < minx) minx = p[0];
        if (p[1] < miny) miny = p[1];
        if (p[0] > maxx) maxx = p[0];
        if (p[1] > maxy) maxy = p[1];
        return p;
      };
      const walk = (c, depth) => (depth === 0 ? conv(c) : c.map((x) => walk(x, depth - 1)));
      const depth = { Point: 0, LineString: 1, MultiLineString: 2, Polygon: 2, MultiPolygon: 3 }[g.type];
      f.p = walk(g.coordinates, depth);
      f.b = [minx, miny, maxx, maxy];
    }
    return fc;
  }

  async function load() {
    state.meta = await (await fetch("/meta.json")).json();
    const names = ["water_areas", "landcover", "buildings", "waterways", "roads", "trails", "aerialways", "railways", "places", "peaks", "huts"];
    const urls = Object.fromEntries(names.map((n) => [n, `/data/osm/layers/${n}.geojson`]));
    urls.contours = "/data/dem/contours.geojson";
    urls.circle = "/data/plan/circle.geojson";
    await Promise.all(Object.entries(urls).map(async ([k, u]) => {
      const r = await fetch(u);
      state.data[k] = r.ok ? prep(await r.json()) : { features: [] };
    }));
    const c = state.meta.input ? state.meta.input.centre : [0, 0];
    [state.cx, state.cy] = project(c[1], c[0]);
    const params = new URLSearchParams(location.search);
    if (params.get("z")) state.z = Number(params.get("z"));
    if (params.get("lat")) [state.cx, state.cy] = project(Number(params.get("lon")), Number(params.get("lat")));
    if (params.get("mode")) setMode(params.get("mode"));
    attribution();
    resize();
  }

  function attribution() {
    const L = (state.meta && state.meta.layers) || {};
    const a = ["© OpenStreetMap contributors (ODbL)"];
    if (L.dem && L.dem.attribution) a.push("Copernicus DEM GLO-30 © DLR/Airbus, ESA/EU");
    if (L.landcover && L.landcover.attribution) a.push("ESA WorldCover 2021 (CC BY 4.0)");
    if (L.imagery && L.imagery.attribution) a.push(L.imagery.attribution);
    document.getElementById("attribution").textContent = a.join(" · ");
  }

  function resize() {
    const r = canvas.getBoundingClientRect();
    canvas.width = Math.round(r.width * state.dpr);
    canvas.height = Math.round(r.height * state.dpr);
    draw();
  }

  function tile(layer, z, x, y) {
    const key = `${layer}/${z}/${x}/${y}`;
    let t = state.tiles.get(key);
    if (!t) {
      t = { img: new Image(), ok: false };
      t.img.onload = () => { t.ok = true; schedule(); };
      t.img.onerror = () => { t.ok = false; };
      t.img.src = `/tiles/${key}`;
      state.tiles.set(key, t);
    }
    return t;
  }

  let pending = false;
  function schedule() {
    if (!pending) { pending = true; requestAnimationFrame(() => { pending = false; draw(); }); }
  }

  function draw() {
    const t0 = performance.now();
    const W = canvas.width / state.dpr, H = canvas.height / state.dpr;
    const scale = 2 ** state.z;
    ctx.setTransform(state.dpr, 0, 0, state.dpr, 0, 0);
    ctx.fillStyle = "#eef1ef";
    ctx.fillRect(0, 0, W, H);
    const ox = state.cx * scale - W / 2, oy = state.cy * scale - H / 2;
    const view = [ox / scale, oy / scale, (ox + W) / scale, (oy + H) / scale];
    const mode = MODES[state.mode];
    const notes = [];
    let loaded = true;
    for (const [layer, alpha] of mode.rasters) {
      if (!state.meta || !state.meta.rasters[layer]) { notes.push(`${layer}: not prepared`); continue; }
      const zooms = state.meta.rasters[layer].zooms;
      const zmax = Math.max(...zooms), zmin = Math.min(...zooms);
      const tz = Math.max(zmin, Math.min(Math.floor(state.z), zmax));
      if (state.z > zmax + 0.01) notes.push(`${layer}: overzoomed beyond native z${zmax}`);
      const ts = TILE * 2 ** (state.z - tz);
      const x0 = Math.floor(ox / ts), y0 = Math.floor(oy / ts), x1 = Math.floor((ox + W) / ts), y1 = Math.floor((oy + H) / ts);
      ctx.globalAlpha = alpha;
      ctx.imageSmoothingEnabled = true;
      for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) {
        const t = tile(layer, tz, x, y);
        if (t.ok) ctx.drawImage(t.img, x * ts - ox, y * ts - oy, ts + 0.5, ts + 0.5);
        else if (!t.img.complete) loaded = false;
      }
      ctx.globalAlpha = 1;
    }
    const tr = (p) => [p[0] * scale - ox, p[1] * scale - oy];
    const visible = (f) => !(f.b[0] > view[2] || f.b[2] < view[0] || f.b[1] > view[3] || f.b[3] < view[1]);
    const path = (rings, close) => {
      for (const ring of rings) {
        ring.forEach((p, i) => { const q = tr(p); i ? ctx.lineTo(q[0], q[1]) : ctx.moveTo(q[0], q[1]); });
        if (close) ctx.closePath();
      }
    };
    const lines = (f) => (f.geometry.type === "LineString" ? [f.p] : f.p);
    const polys = (f) => (f.geometry.type === "Polygon" ? [f.p] : f.p);
    const fillLayer = (name, fill, stroke, minz) => {
      if (state.z < minz) return;
      ctx.fillStyle = fill; ctx.strokeStyle = stroke; ctx.lineWidth = 0.6;
      for (const f of state.data[name].features) if (visible(f)) {
        ctx.beginPath(); for (const poly of polys(f)) path(poly, true); ctx.fill("evenodd"); if (stroke) ctx.stroke();
      }
    };
    const lineLayer = (name, styleFn, minz) => {
      if (state.z < minz) return;
      for (const f of state.data[name].features) if (visible(f)) {
        const s = styleFn(f); if (!s) continue;
        ctx.strokeStyle = s[0]; ctx.lineWidth = s[1]; ctx.setLineDash(s[2] || []);
        ctx.beginPath(); path(lines(f), false); ctx.stroke();
      }
      ctx.setLineDash([]);
    };
    if (mode.vectors === true) {
      fillLayer("landcover", "rgba(120,170,110,0.18)", null, 11);
    }
    if (mode.contours && state.z >= 11) {
      lineLayer("contours", (f) => {
        const e = f.properties.ele;
        if (state.z < 13 && e % 100) return null;
        return [e % 100 ? "rgba(140,90,40,0.45)" : "rgba(120,70,30,0.8)", e % 100 ? 0.5 : 1];
      }, 11);
    }
    if (mode.vectors) {
      fillLayer("water_areas", "#9cc7e8", "#5d97c4", 9);
      lineLayer("waterways", (f) => [mode.vectors === "hybrid" ? "#7fb8ff" : "#5d97c4", f.properties.waterway === "river" ? 2 : 0.8], 11);
      if (mode.vectors === true) fillLayer("buildings", "#c9bfb5", "#a0948a", 14);
      lineLayer("railways", () => ["#555", 1.2, [4, 3]], 10);
      lineLayer("aerialways", () => ["#333", 1, [2, 2]], 12);
      const roadW = { motorway: 4, trunk: 3.5, primary: 3, secondary: 2.6, tertiary: 2.2, unclassified: 1.6, residential: 1.4, service: 0.9 };
      lineLayer("roads", (f) => {
        const h = f.properties.highway;
        const w = roadW[h.replace("_link", "")] || 1;
        if (state.z < 12 && w < 2) return null;
        return [mode.vectors === "hybrid" ? "#ffd966" : (w >= 2.2 ? "#e8a33c" : "#fff"), w * (state.z >= 14 ? 1.4 : 1)];
      }, 9);
      if (mode.vectors === true) lineLayer("roads", (f) => (roadW[f.properties.highway] || 1) < 2.2 ? ["#9a9a9a", 0.4] : null, 13);
      lineLayer("trails", (f) => {
        const sac = f.properties.sac_scale || "";
        const red = sac.includes("alpine") || sac.includes("demanding") ? "#b0122a" : "#c0392b";
        return [mode.vectors === "hybrid" ? "#ff8a65" : red, f.properties.highway === "track" ? 1.2 : 1.1, f.properties.highway === "track" ? [6, 2] : [3, 2]];
      }, 12);
    }
    // Requested circle outline.
    ctx.strokeStyle = "rgba(23,101,76,0.9)"; ctx.lineWidth = 1.5; ctx.setLineDash([6, 4]);
    for (const f of state.data.circle ? state.data.circle.features : []) { ctx.beginPath(); path(f.p, true); ctx.stroke(); }
    ctx.setLineDash([]);
    if (mode.labels) labels(tr, visible, mode);
    const scaleText = scaleBar();
    note.textContent = notes.join(" · ");
    const ms = performance.now() - t0;
    metrics.draws.push({ z: state.z, mode: state.mode, ms: Math.round(ms * 10) / 10, tilesLoaded: loaded });
    metrics.ready = loaded;
    metrics.lastView = { z: state.z, centre: unproject(state.cx, state.cy).reverse(), scale: scaleText };
  }

  function labels(tr, visible, mode) {
    const boxes = [];
    const fits = (b) => !boxes.some((o) => !(b[0] > o[2] || b[2] < o[0] || b[1] > o[3] || b[3] < o[1]));
    const halo = mode.vectors === "hybrid" ? "rgba(0,0,0,0.75)" : "rgba(255,255,255,0.9)";
    const ink = mode.vectors === "hybrid" ? "#fff" : "#24343b";
    const put = (text, x, y, size, weight, color) => {
      ctx.font = `${weight} ${size}px -apple-system, system-ui, sans-serif`;
      const w = ctx.measureText(text).width;
      const b = [x - w / 2 - 2, y - size, x + w / 2 + 2, y + 3];
      if (!fits(b)) return false;
      boxes.push(b);
      ctx.lineWidth = 3; ctx.strokeStyle = halo; ctx.strokeText(text, x - w / 2, y);
      ctx.fillStyle = color || ink; ctx.fillText(text, x - w / 2, y);
      return true;
    };
    const rank = { city: 0, town: 1, village: 2, hamlet: 3, locality: 4, isolated_dwelling: 5 };
    const places = state.data.places.features.filter(visible).sort((a, b) => (rank[a.properties.place] ?? 9) - (rank[b.properties.place] ?? 9));
    for (const f of places) {
      const r = rank[f.properties.place] ?? 9;
      if ((r >= 3 && state.z < 13) || (r >= 4 && state.z < 14) || (r === 2 && state.z < 11)) continue;
      const [x, y] = tr(f.p);
      ctx.fillStyle = ink; ctx.beginPath(); ctx.arc(x, y, r <= 1 ? 3 : 2, 0, 7); ctx.fill();
      put(f.properties.name, x, y - 5, r <= 1 ? 14 : r === 2 ? 12.5 : 11, r <= 2 ? 600 : 400);
    }
    if (state.z >= 12) for (const f of state.data.peaks.features.filter(visible)) {
      if (!f.properties.name || f.properties.natural !== "peak") continue;
      const [x, y] = tr(f.p);
      const text = f.properties.ele ? `${f.properties.name} ${Math.round(Number(f.properties.ele))}` : f.properties.name;
      if (put(text, x, y + 14, 10.5, 500, mode.vectors === "hybrid" ? "#fff" : "#6b3d12")) {
        ctx.fillStyle = mode.vectors === "hybrid" ? "#fff" : "#6b3d12";
        ctx.beginPath(); ctx.moveTo(x, y - 4); ctx.lineTo(x + 4, y + 3); ctx.lineTo(x - 4, y + 3); ctx.fill();
      }
    }
    if (state.z >= 14) for (const f of state.data.huts.features.filter(visible)) {
      const [x, y] = tr(f.p);
      ctx.fillStyle = "#8a4b00"; ctx.fillRect(x - 3, y - 3, 6, 6);
      if (f.properties.name) put(f.properties.name, x, y + 13, 10, 400, "#8a4b00");
    }
  }

  function scaleBar() {
    const lat = unproject(state.cx, state.cy)[1];
    const mpp = (156543.03392 * Math.cos((lat * Math.PI) / 180)) / 2 ** state.z;
    const target = mpp * 100;
    const nice = [10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000].find((v) => v >= target) || target;
    const el = document.getElementById("scale");
    el.style.width = `${Math.round(nice / mpp)}px`;
    el.textContent = nice >= 1000 ? `${nice / 1000} km · z${state.z}` : `${nice} m · z${state.z}`;
    return el.textContent;
  }

  function setMode(m) {
    state.mode = m;
    for (const b of document.querySelectorAll("#modes button")) b.setAttribute("aria-pressed", String(b.dataset.mode === m));
    schedule();
  }
  const zoom = (d) => { state.z = Math.max(8, Math.min(17, state.z + d)); schedule(); };
  document.getElementById("modes").addEventListener("click", (e) => { if (e.target.dataset.mode) setMode(e.target.dataset.mode); });
  document.getElementById("zin").onclick = () => zoom(1);
  document.getElementById("zout").onclick = () => zoom(-1);
  canvas.addEventListener("wheel", (e) => { e.preventDefault(); zoom(e.deltaY < 0 ? 1 : -1); }, { passive: false });
  let drag = null;
  canvas.addEventListener("pointerdown", (e) => { drag = [e.clientX, e.clientY]; canvas.setPointerCapture(e.pointerId); });
  canvas.addEventListener("pointermove", (e) => {
    if (!drag) return;
    const s = 2 ** state.z;
    state.cx -= (e.clientX - drag[0]) / s; state.cy -= (e.clientY - drag[1]) / s; drag = [e.clientX, e.clientY]; schedule();
  });
  canvas.addEventListener("pointerup", () => { drag = null; });
  canvas.addEventListener("keydown", (e) => {
    const s = 2 ** state.z, step = 80 / s;
    if (e.key === "ArrowLeft") state.cx -= step; else if (e.key === "ArrowRight") state.cx += step;
    else if (e.key === "ArrowUp") state.cy -= step; else if (e.key === "ArrowDown") state.cy += step;
    else if (e.key === "+" || e.key === "=") return zoom(1); else if (e.key === "-") return zoom(-1); else return;
    e.preventDefault(); schedule();
  });
  window.addEventListener("resize", resize);
  window.__pocView = (lat, lon, z, mode) => { [state.cx, state.cy] = project(lon, lat); state.z = z; if (mode) setMode(mode); draw(); };
  load();
})();
