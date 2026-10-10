const assert = require("node:assert/strict"),
  Terrain = require("../../command/web/terrain.js"),
  GeoMap = require("../../command/web/map.js");
const grid = {
  bounds: [77, 28, 77 + 2 / 1200, 28 + 2 / 1200],
  columns: 3,
  rows: 3,
  arc_seconds: 3,
  vertical_datum: "EGM96",
  components: { hillshade: true, contours: true, elevation: true },
  samples: [100, 110, 120, 120, 130, 140, 140, 150, 160],
};
assert.equal(Terrain.elevation(grid, 77, grid.bounds[3]), 100);
assert.ok(
  Math.abs(
    Terrain.elevation(
      grid,
      (grid.bounds[0] + grid.bounds[2]) / 2,
      (grid.bounds[1] + grid.bounds[3]) / 2,
    ) - 130,
  ) < 1e-8,
);
assert.equal(Terrain.elevation(grid, 76, 28), null);
const voidGrid = { ...grid, samples: [...grid.samples] };
voidGrid.samples[4] = null;
assert.equal(
  Terrain.elevation(
    voidGrid,
    (grid.bounds[0] + grid.bounds[2]) / 2,
    (grid.bounds[1] + grid.bounds[3]) / 2,
  ),
  null,
);
const shade = Terrain.hillshade(grid),
  flat = Terrain.hillshade({ ...grid, samples: Array(9).fill(100) });
assert.equal(shade.length, 36);
assert.equal(shade[19], 255);
assert.equal(shade[3], 0);
assert.equal(flat[16], Math.round(255 * Math.SQRT1_2));
assert.notEqual(shade[16], flat[16]);
assert.ok(
  shade[16] > flat[16],
  "east/south-rising terrain faces NW illumination",
);
assert.equal(Terrain.hillshade(voidGrid)[19], 0);
const c = Terrain.contours(grid);
assert.equal(c.interval, 10);
assert.ok(c.segments.length > 0);
for (const line of c.segments) {
  assert.equal(line.level % 10, 0);
  for (const point of line.points) {
    assert.ok(point[0] >= grid.bounds[0] && point[0] <= grid.bounds[2]);
    assert.ok(point[1] >= grid.bounds[1] && point[1] <= grid.bounds[3]);
    assert.ok(Math.abs(Terrain.elevation(grid, ...point) - line.level) < 1e-7);
  }
}
const zero = { ...grid, samples: Array(9).fill(100) };
assert.equal(Terrain.contours(zero).segments.length, 0);
const saddle = { ...grid, columns: 2, rows: 2, samples: [0, 100, 100, 0] };
assert.ok(Terrain.contours(saddle).segments.length > 0);
assert.throws(
  () =>
    Terrain.contours({
      ...grid,
      columns: 257,
      rows: 257,
      samples: Array.from({ length: 257 * 257 }, (_, i) =>
        (i + Math.floor(i / 257)) % 2 ? 100 : 0,
      ),
    }),
  /complexity limit/,
);
const tl = GeoMap.project(grid.bounds[0], grid.bounds[3]),
  br = GeoMap.project(grid.bounds[2], grid.bounds[1]);
assert.ok(tl[0] < br[0] && tl[1] > br[1]);
for (const point of [
  [0, 0],
  [77.209, 28.613],
  [-77, -28],
  [15, 84],
]) {
  const restored = GeoMap.unproject(...GeoMap.project(...point));
  assert.ok(
    Math.abs(point[0] - restored[0]) < 1e-8 &&
      Math.abs(point[1] - restored[1]) < 1e-8,
  );
}
assert.equal(
  Terrain.prepare({ ...grid, components: { elevation: true } }).shades,
  null,
);
const complex = Terrain.prepare({
  ...grid,
  columns: 257,
  rows: 257,
  samples: Array.from({ length: 257 * 257 }, (_, i) =>
    (i + Math.floor(i / 257)) % 2 ? 100 : 0,
  ),
});
assert.match(complex.contour_error, /complexity limit/);
assert.equal(complex.contour, null);
assert.ok(complex.shades);
assert.equal(
  complex.samples.length,
  257 * 257,
  "bounded contour failure preserves the DEM",
);
console.log(
  "PASS: synthetic DEM Horn hillshade, marching-square contours, spacing, bilinear elevation/void/coverage and existing projection alignment. Not real provider acceptance.",
);
