// Focused regression tests for the enhanced offline map integration (issue #95).
// Pure logic only — browser behaviour is verified live per AGENTS.md.
const test = require("node:test");
const assert = require("node:assert/strict");

async function load() {
  return await import("../../command/web/enhanced-style.js");
}

const products = {
  vector: { bounds: [0, 40.9, 5.6, 45.0], minzoom: 6, maxzoom: 14 },
  dem: { bounds: [0.7, 42.0, 2.1, 43.0], minzoom: 6, maxzoom: 12 },
  landcover: { bounds: [0.7, 42.0, 2.1, 43.0], minzoom: 6, maxzoom: 12 },
};
const coverage = [0, 40.9, 5.6, 45.0];

test("full style carries vector/raster sources and layers", async () => {
  const { buildStyle } = await load();
  const s = buildStyle("map-x", products, coverage);
  assert.ok(s.sources.osm, "vector source present");
  assert.ok(s.layers.length > 40, "full cartography");
  assert.ok(s.layers.some((l) => l.id === "hillshade"));
  // same-origin tiles only — no external requests possible
  for (const src of Object.values(s.sources))
    if (src.tiles) for (const u of src.tiles) assert.ok(u.startsWith("/local/maps/"), u);
});

test("vectorless package omits osm source and vector layers, keeps raster", async () => {
  const { buildStyle } = await load();
  const { vector, ...rest } = products;
  const s = buildStyle("map-x", rest, coverage);
  assert.equal(s.sources.osm, undefined);
  assert.ok(!s.layers.some((l) => l.source === "osm"), "no vector-dependent layers");
  assert.ok(s.sources.landcover, "raster product still renders");
  assert.ok(s.layers.some((l) => l.id === "hillshade"), "hillshade survives");
});

test("viewport coverage accounts for visible edges, not just centre", async () => {
  const { viewportOverflowsCoverage } = await load();
  const R = 6378137;
  const lat = 42.57, lon = 1.48;
  const cx = lon * R * Math.PI / 180;
  const cy = R * Math.log(Math.tan(Math.PI / 4 + (lat * Math.PI / 180) / 2)); // Arinsal
  // small overzoomed viewport well inside coverage: no report
  assert.equal(viewportOverflowsCoverage(cx, cy, 8, 800, 600, coverage), false);
  // viewport centre inside coverage but eastern edge beyond it: must report
  const edgeCx = (5.5 * Math.PI / 180) * R; // centre lon 5.5, inside cov east 5.6
  assert.equal(viewportOverflowsCoverage(edgeCx, cy, 0.05, 1440, 900, coverage), true);
  // centre far outside coverage: must report
  const farCx = (10 * Math.PI / 180) * R;
  assert.equal(viewportOverflowsCoverage(farCx, cy, 1, 1440, 900, coverage), true);
  // degenerate inputs never falsely report
  assert.equal(viewportOverflowsCoverage(cx, cy, 0, 800, 600, coverage), false);
  assert.equal(viewportOverflowsCoverage(cx, cy, 1, 800, 600, null), false);
});
