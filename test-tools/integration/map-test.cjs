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
for (const fix of [{ speed_mps: 0, bearing_deg: 90 }, { speed_mps: 2 }, { speed_mps: 2, bearing_deg: 360 }, { speed_mps: 2, bearing_deg: -1 }]) assert.equal(map.liveHeading(fix, true), null);
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
