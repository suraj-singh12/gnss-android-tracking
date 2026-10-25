const assert = require("node:assert/strict");
const map = require("../../command/web/map.js");
assert.ok(map.project(0, 0).every((n) => Math.abs(n) < 1e-8));
const json = JSON.stringify({
  type: "FeatureCollection",
  features: [
    {
      type: "Feature",
      properties: { name: "local" },
      geometry: {
        type: "Polygon",
        coordinates: [
          [
            [77, 28],
            [77.01, 28],
            [77.01, 28.01],
            [77, 28.01],
            [77, 28],
          ],
        ],
      },
    },
  ],
});
const parsed = map.parse(json, "test.geojson");
assert.deepEqual(parsed.bounds, [77, 28, 77.01, 28.01]);
assert.equal(parsed.count, 5);
assert.equal(parsed.shapes[0].properties.name, "local");
assert.equal(map.liveHeading({ speed_mps: 2, bearing_deg: 90 }, true), 90);
for (const fix of [
  { speed_mps: 0, bearing_deg: 90 },
  { speed_mps: 2 },
  { speed_mps: 2, bearing_deg: 360 },
  { speed_mps: 2, bearing_deg: -1 },
])
  assert.equal(map.liveHeading(fix, true), null);
assert.equal(map.liveHeading({ speed_mps: 2, bearing_deg: 90 }, false), null);
for (const invalid of [
  "{}",
  "not json",
  JSON.stringify({ type: "Point", coordinates: [1000, 500] }),
  JSON.stringify({
    type: "Point",
    coordinates: [77, 28],
    crs: { name: "EPSG:3857" },
  }),
  JSON.stringify({
    type: "LineString",
    coordinates: [
      [179, 28],
      [-179, 28],
    ],
  }),
  JSON.stringify({
    type: "Polygon",
    coordinates: [
      [
        [0, 0],
        [1, 0],
        [1, 1],
        [0, 1],
      ],
    ],
  }),
])
  assert.throws(() => map.parse(invalid, "invalid"));
console.log(
  "PASS: offline map parser, projection, coverage, invalid CRS/coordinates/rings/antimeridian fallback inputs",
);

const detailedLine = {
  type: "line",
  properties: { highway: "path" },
  coordinates: [
    [0, 0],
    [0.1, 0.1],
    [0.2, 0.2],
    [2, 2],
    [3, 3],
  ],
};
const original = JSON.stringify(detailedLine);
const overview = map.overview([detailedLine], 1, 100);
assert.deepEqual(overview.shapes[0].coordinates, [
  [0, 0],
  [2, 2],
  [3, 3],
]);
assert.equal(
  JSON.stringify(detailedLine),
  original,
  "overview must not alter stored geometry",
);
const bounded = map.overview([detailedLine, parsed.shapes[0]], 0, 5);
assert.equal(bounded.count, 5);
assert.equal(bounded.omitted, 1);
assert.deepEqual(bounded.shapes[0], detailedLine);
const nine = Array.from({ length: 9 }, () =>
  map.overview([detailedLine], 1, Math.floor(100000 / 9)),
);
assert.ok(nine.every((v) => v.shapes.length === 1));
assert.ok(nine.reduce((n, v) => n + v.count, 0) <= 100000);
console.log(
  "PASS: bounded overview preserves source vertices, endpoints, exact polygons and reports deferred features across nine chunks",
);
