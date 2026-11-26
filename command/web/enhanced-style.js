// Enhanced offline cartography style generator (Issue #95).
// Derived from the operator-accepted Issue #88/#92 POC cartography: identical
// palette, layers, label hierarchy and zoom behaviour. Production differences:
// tiles are served from Command-owned Format 2 storage, fonts are embedded
// assets, absent products drop their layers honestly, and the coverage extent
// outline is generated from validated package metadata instead of a bundle file.
// Noto Sans (OFL) font faces; no external requests of any kind.
const Z = ["zoom"];
const exp = (stops, add = 0) => ["interpolate", ["exponential", 1.5], Z, ...stops.flatMap(([z, v]) => [z, v + add])];
const lin = (...stops) => ["interpolate", ["linear"], Z, ...stops];
const cls = (...c) => ["match", ["get", "class"], c, true, false];
const R = "Noto Sans Regular", M = "Noto Sans Medium", I = "Noto Sans Italic";
const INK = "#2b3137", HALO = "rgba(255,255,255,0.92)";

const ROADS = {
  // class: [fill, casing, width stops, casing extra px, minzoom]
  motorway: ["#9fb0d6", "#6f80a8", [[6, 0.9], [10, 2.4], [13, 5], [16, 13], [18, 28]], 2, 6],
  trunk: ["#a6b4d4", "#7787ad", [[6, 0.9], [10, 2.2], [13, 4.6], [16, 12], [18, 26]], 2, 6],
  primary: ["#b2bedb", "#8291b4", [[8, 0.8], [10, 1.8], [13, 4.2], [16, 11], [18, 24]], 1.8, 8],
  secondary: ["#c7d0e3", "#97a3c0", [[9, 0.6], [11, 1.4], [13, 3.4], [16, 9.5], [18, 21]], 1.6, 9],
  tertiary: ["#ffffff", "#b6bdca", [[10, 0.5], [12, 1.2], [14, 3.4], [16, 8], [18, 18]], 1.4, 10],
  minor: ["#ffffff", "#c9ced7", [[12, 0.4], [14, 2], [16, 6], [18, 14]], 1.2, 12],
  service: ["#ffffff", "#d3d7de", [[14, 0.6], [16, 2.8], [18, 7]], 1, 14],
};

function fontFaces() {
  const f = (weight, style) => [
    { url: `/fonts/noto-sans-latin-${weight}-${style}.woff2`, "unicode-range": ["U+0000-00FF", "U+0131", "U+0152-0153", "U+02BB-02BC", "U+02C6", "U+02DA", "U+02DC", "U+2000-206F", "U+20AC", "U+2122", "U+2212"] },
    { url: `/fonts/noto-sans-latin-ext-${weight}-${style}.woff2`, "unicode-range": ["U+0100-02AF", "U+1E00-1EFF", "U+20A0-20C0", "U+2C60-2C7F", "U+A720-A7FF"] },
  ];
  return { [R]: f(400, "normal"), [M]: f(600, "normal"), [I]: f(400, "italic") };
}

function roadLayers() {
  const casings = [], fills = [];
  const order = ["service", "minor", "tertiary", "secondary", "primary", "trunk", "motorway"];
  for (const c of order) {
    const [fill, casing, stops, extra, minzoom] = ROADS[c];
    const filter = ["all", ["==", ["get", "class"], c]];
    casings.push({ id: `road-${c}-casing`, type: "line", source: "osm", "source-layer": "transportation", minzoom: Math.max(minzoom, c === "minor" || c === "service" ? 13 : minzoom), filter,
      layout: { "line-cap": "round", "line-join": "round" },
      paint: { "line-color": casing, "line-width": exp(stops, extra), "line-opacity": ["case", ["has", "tunnel"], 0.45, 1] } });
    fills.push({ id: `road-${c}`, type: "line", source: "osm", "source-layer": "transportation", minzoom, filter,
      layout: { "line-cap": "round", "line-join": "round" },
      paint: { "line-color": fill, "line-width": exp(stops), "line-opacity": ["case", ["has", "tunnel"], 0.6, 1] } });
  }
  return [...casings, ...fills];
}

// buildStyle(mapID, products, coverage) generates the enhanced cartography for
// one immutable Format 2 revision. Tiles are served read-only from Command-owned
// storage; zoom ranges and coverage come from the validated package metadata.
// products: enhanced doc products map; coverage: [w,s,e,n] union bounds.
export function buildStyle(mapID, products, coverage) {
  const tiles = (product) => [`/local/maps/${encodeURIComponent(mapID)}/tiles/${product}/{z}/{x}/{y}`];
  const vp = products.vector, lp = products.landcover, dp = products.dem;
  const [w, s, e, n] = coverage;
  const extentRing = [[w, s], [e, s], [e, n], [w, n], [w, s]];
  const style = {
    version: 8,
    name: "GNSS enhanced standard",
    "font-faces": fontFaces(),
    sources: {
      ...(vp ? { osm: { type: "vector", tiles: tiles("vector"), minzoom: vp.minzoom, maxzoom: vp.maxzoom, attribution: "© OpenStreetMap contributors" } } : {}),
      ...(lp ? { landcover: { type: "raster", tiles: tiles("landcover"), tileSize: 256, minzoom: lp.minzoom, maxzoom: lp.maxzoom, attribution: "ESA WorldCover 2021 (CC BY 4.0)" } } : {}),
      ...(dp ? { dem: { type: "raster-dem", tiles: tiles("dem"), tileSize: 256, encoding: "terrarium", minzoom: dp.minzoom, maxzoom: dp.maxzoom, attribution: "Copernicus DEM GLO-30 © DLR/Airbus, ESA/EU" } } : {}),
      extent: { type: "geojson", data: { type: "Feature", properties: {}, geometry: { type: "Polygon", coordinates: [extentRing] } } },
    },
    layers: [
      { id: "background", type: "background", paint: { "background-color": "#f3f2ee" } },
      ...(lp ? [{ id: "landcover-raster", type: "raster", source: "landcover", paint: { "raster-resampling": "linear", "raster-fade-duration": 0, "raster-opacity": lin(8, 1, 15, 0.85) } }] : []),
      { id: "landuse-farmland", type: "fill", source: "osm", "source-layer": "landuse", filter: cls("farmland"), paint: { "fill-color": "#eeedd9", "fill-opacity": 0.7 } },
      { id: "landcover-grass", type: "fill", source: "osm", "source-layer": "landcover", filter: cls("grass"), paint: { "fill-color": "#dcebcb", "fill-opacity": lin(9, 0.3, 14, 0.55) } },
      { id: "landcover-wood", type: "fill", source: "osm", "source-layer": "landcover", filter: cls("wood"), paint: { "fill-color": "#b9dcad", "fill-opacity": lin(8, 0.35, 13, 0.6) } },
      { id: "landcover-rock", type: "fill", source: "osm", "source-layer": "landcover", filter: cls("rock"), paint: { "fill-color": "#e7e3dc", "fill-opacity": 0.7 } },
      { id: "landcover-ice", type: "fill", source: "osm", "source-layer": "landcover", filter: cls("ice"), paint: { "fill-color": "#ffffff", "fill-opacity": 0.9 } },
      { id: "landuse-park", type: "fill", source: "osm", "source-layer": "landuse", filter: cls("park"), paint: { "fill-color": "#c6e6bb", "fill-opacity": 0.8 } },
      { id: "landuse-residential", type: "fill", source: "osm", "source-layer": "landuse", filter: cls("residential"), paint: { "fill-color": "#ebe7e2", "fill-opacity": lin(9, 0.6, 13, 0.9) } },
      { id: "landuse-industrial", type: "fill", source: "osm", "source-layer": "landuse", filter: cls("industrial"), paint: { "fill-color": "#e8e5ee", "fill-opacity": 0.9 } },
      ...(dp ? [{ id: "hillshade", type: "hillshade", source: "dem", paint: {
        "hillshade-exaggeration": lin(8, 0.32, 12, 0.3, 16, 0.22),
        "hillshade-shadow-color": "#5d6d5f", "hillshade-highlight-color": "#ffffff", "hillshade-accent-color": "#879587" } }] : []),
      { id: "water", type: "fill", source: "osm", "source-layer": "water", paint: { "fill-color": "#a7d3f3" } },
      { id: "waterway", type: "line", source: "osm", "source-layer": "waterway", layout: { "line-cap": "round", "line-join": "round" },
        paint: { "line-color": "#a7d3f3", "line-width": ["interpolate", ["exponential", 1.5], Z, 8, ["match", ["get", "class"], "river", 0.8, 0.2], 12, ["match", ["get", "class"], "river", 2, 0.5], 16, ["match", ["get", "class"], "river", 7, 2]] } },
      { id: "cliff", type: "line", source: "osm", "source-layer": "cliff", paint: { "line-color": "#b4a796", "line-width": lin(13, 0.6, 17, 1.4) } },
      { id: "building", type: "fill", source: "osm", "source-layer": "building", minzoom: 13,
        paint: { "fill-color": "#dcd5cc", "fill-outline-color": "#c4bbaf", "fill-opacity": lin(13, 0, 14, 1) } },
      { id: "path-track", type: "line", source: "osm", "source-layer": "transportation", filter: cls("track"), layout: { "line-cap": "butt" },
        paint: { "line-color": "#b4a283", "line-width": lin(12, 0.6, 16, 1.8), "line-dasharray": [3, 1.5] } },
      { id: "path", type: "line", source: "osm", "source-layer": "transportation", filter: cls("path"), layout: { "line-cap": "round" },
        paint: { "line-color": "#8c8172", "line-width": lin(13, 0.6, 17, 1.6), "line-dasharray": [0.3, 2] } },
      ...roadLayers(),
      { id: "railway", type: "line", source: "osm", "source-layer": "railway", paint: { "line-color": "#9a9a9a", "line-width": 1.2, "line-dasharray": [3, 3] } },
      { id: "aerialway", type: "line", source: "osm", "source-layer": "aerialway", paint: { "line-color": "#6c6c6c", "line-width": lin(12, 0.6, 16, 1.2), "line-dasharray": [4, 2] } },
      { id: "boundary", type: "line", source: "osm", "source-layer": "boundary", paint: { "line-color": "#a497b8", "line-width": lin(6, 0.8, 12, 1.8), "line-dasharray": [4, 2, 1, 2] } },
      { id: "extent", type: "line", source: "extent", paint: { "line-color": "rgba(33,111,86,0.35)", "line-width": 1.2, "line-dasharray": [3, 3] } },
      // ---- labels (MapLibre collision detection; sort keys give hierarchy) ----
      { id: "waterway-label", type: "symbol", source: "osm", "source-layer": "waterway", minzoom: 13, filter: ["has", "name"],
        layout: { "symbol-placement": "line", "text-field": ["get", "name"], "text-font": [I], "text-size": lin(13, 10.5, 17, 13), "symbol-spacing": 350 },
        paint: { "text-color": "#3d7fb4", "text-halo-color": HALO, "text-halo-width": 1.2 } },
      { id: "road-label", type: "symbol", source: "osm", "source-layer": "transportation", minzoom: 13, filter: ["all", ["has", "name"], cls("motorway", "trunk", "primary", "secondary", "tertiary", "minor")],
        layout: { "symbol-placement": "line", "text-field": ["get", "name"], "text-font": [R], "text-size": lin(13, 10, 17, 12.5), "symbol-spacing": 300,
          "text-max-angle": 30, "symbol-sort-key": ["match", ["get", "class"], "motorway", 1, "trunk", 1, "primary", 2, "secondary", 3, "tertiary", 4, 5] },
        paint: { "text-color": "#565d66", "text-halo-color": HALO, "text-halo-width": 1.4 } },
      { id: "road-shield", type: "symbol", source: "osm", "source-layer": "transportation", minzoom: 9, filter: ["all", ["has", "ref"], cls("motorway", "trunk", "primary", "secondary")],
        layout: { "symbol-placement": "line", "symbol-spacing": lin(9, 250, 14, 400), "text-field": ["get", "ref"], "text-font": [M], "text-size": 10,
          "icon-image": "shield", "icon-text-fit": "both", "icon-text-fit-padding": [1, 4, 1, 4], "text-rotation-alignment": "viewport", "icon-rotation-alignment": "viewport",
          "symbol-sort-key": ["match", ["get", "class"], "motorway", 1, "trunk", 1, "primary", 2, 3] },
        paint: { "text-color": "#3a3426" } },
      { id: "water-label", type: "symbol", source: "osm", "source-layer": "water_name",
        layout: { "text-field": ["get", "name"], "text-font": [I], "text-size": lin(10, 10, 15, 13), "text-max-width": 7, "symbol-sort-key": ["-", 0, ["get", "area"]] },
        paint: { "text-color": "#3d7fb4", "text-halo-color": HALO, "text-halo-width": 1.2 } },
      { id: "peak-major", type: "symbol", source: "osm", "source-layer": "peak", minzoom: 10.5, maxzoom: 12.5, filter: [">=", ["coalesce", ["get", "ele"], 0], 2800],
        layout: { "icon-image": ["match", ["get", "class"], "saddle", "saddle", "peak"], "icon-size": 1,
          "text-field": ["case", ["has", "ele"], ["format", ["get", "name"], {}, "\n", {}, ["concat", ["to-string", ["get", "ele"]], " m"], { "font-scale": 0.85 }], ["get", "name"]],
          "text-font": [R], "text-size": lin(10, 10, 15, 11.5), "text-anchor": "top", "text-offset": [0, 0.55], "text-max-width": 8,
          "symbol-sort-key": ["-", 0, ["coalesce", ["get", "ele"], 0]] },
        paint: { "text-color": "#6a5640", "text-halo-color": HALO, "text-halo-width": 1.3 } },
      { id: "peak", type: "symbol", source: "osm", "source-layer": "peak", minzoom: 12.5,
        layout: { "icon-image": ["match", ["get", "class"], "saddle", "saddle", "peak"], "icon-size": 1,
          "text-field": ["case", ["has", "ele"], ["format", ["get", "name"], {}, "\n", {}, ["concat", ["to-string", ["get", "ele"]], " m"], { "font-scale": 0.85 }], ["get", "name"]],
          "text-font": [R], "text-size": lin(10, 10, 15, 11.5), "text-anchor": "top", "text-offset": [0, 0.55], "text-max-width": 8,
          "symbol-sort-key": ["-", 0, ["coalesce", ["get", "ele"], 0]] },
        paint: { "text-color": "#6a5640", "text-halo-color": HALO, "text-halo-width": 1.3 } },
      { id: "poi", type: "symbol", source: "osm", "source-layer": "poi",
        layout: { "icon-image": ["concat", "poi-", ["get", "class"]], "icon-size": lin(13, 0.8, 16, 1),
          "text-field": ["coalesce", ["get", "name"], ""], "text-font": [R], "text-size": lin(13, 10.5, 17, 12), "text-max-width": 9,
          "text-variable-anchor": ["left", "right", "top", "bottom"], "text-radial-offset": 0.9, "text-justify": "auto", "text-optional": true,
          "symbol-sort-key": ["match", ["get", "class"], "viewpoint", 1, "hut", 2, "health", 2, "attraction", 3, "lodging", 5, 4] },
        paint: { "text-color": ["match", ["get", "class"], ["viewpoint", "attraction"], "#7b3fb3", "lodging", "#c2357d", "hut", "#8a5a33", "camp", "#2b7a4b",
          "park", "#2b7a4b", ["health", "emergency"], "#c5352c", "water", "#2f6fae", "#5d646c"], "text-halo-color": HALO, "text-halo-width": 1.3 } },
      { id: "place-minor", type: "symbol", source: "osm", "source-layer": "place", filter: cls("hamlet", "locality", "isolated_dwelling", "neighbourhood", "suburb"),
        layout: { "text-field": ["get", "name"], "text-font": [R], "text-size": lin(12, 10.5, 16, 13), "text-max-width": 8,
          "symbol-sort-key": ["match", ["get", "class"], "suburb", 1, "hamlet", 2, 3] },
        paint: { "text-color": ["match", ["get", "class"], ["locality", "isolated_dwelling"], "#6c737a", "#3e454c"], "text-halo-color": HALO, "text-halo-width": 1.5 } },
      { id: "place-village", type: "symbol", source: "osm", "source-layer": "place", filter: cls("village"),
        layout: { "text-field": ["get", "name"], "text-font": [M], "text-size": lin(10, 11, 14, 14, 17, 16), "text-max-width": 8, "symbol-sort-key": ["-", 0, ["get", "pop"]] },
        paint: { "text-color": INK, "text-halo-color": HALO, "text-halo-width": 1.6 } },
      { id: "place-town", type: "symbol", source: "osm", "source-layer": "place", filter: cls("town", "city"),
        layout: { "text-field": ["get", "name"], "text-font": [M], "text-size": lin(7, 12, 11, 15, 15, 19), "text-max-width": 8, "symbol-sort-key": ["-", 0, ["get", "pop"]] },
        paint: { "text-color": "#1f252b", "text-halo-color": HALO, "text-halo-width": 1.8 } },
    ],
  };
  // A valid package may omit the vector product (missing layers are disclosed
  // at import): raster cartography still renders honestly, vector-dependent
  // layers are simply absent rather than aborting the whole activation.
  if (!vp) {
    delete style.sources.osm;
    style.layers = style.layers.filter((l) => l.source !== "osm");
  }
  return style;
}
