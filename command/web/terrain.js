/* DEM processing only. Geographic placement uses the existing GeoMap renderer. */
"use strict";
const Terrain = (() => {
  function elevation(g, lon, lat) {
    const [w, s, e, n] = g.bounds;
    if (lon < w || lon > e || lat < s || lat > n) return null;
    const x = ((lon - w) / (e - w)) * (g.columns - 1),
      y = ((n - lat) / (n - s)) * (g.rows - 1);
    const ix = Math.min(g.columns - 2, Math.floor(x)),
      iy = Math.min(g.rows - 2, Math.floor(y)),
      dx = x - ix,
      dy = y - iy;
    const weights = [
        (1 - dx) * (1 - dy),
        dx * (1 - dy),
        (1 - dx) * dy,
        dx * dy,
      ],
      indices = [
        iy * g.columns + ix,
        iy * g.columns + ix + 1,
        (iy + 1) * g.columns + ix,
        (iy + 1) * g.columns + ix + 1,
      ];
    let result = 0;
    for (let k = 0; k < 4; k++) {
      const v = g.samples[indices[k]];
      if (weights[k] > 1e-10) {
        if (v === null) return null;
        result += v * weights[k];
      }
    }
    return result;
  }
  function hillshade(g) {
    const values = new Uint8ClampedArray(g.columns * g.rows * 4),
      dy = (g.arc_seconds / 3600) * 111320,
      dx = dy * Math.cos((((g.bounds[1] + g.bounds[3]) / 2) * Math.PI) / 180);
    // Horn 3x3 gradient; illumination NW at 45 degrees. No detail upsampling.
    for (let y = 1; y < g.rows - 1; y++)
      for (let x = 1; x < g.columns - 1; x++) {
        const a = [];
        for (let j = -1; j <= 1; j++)
          for (let i = -1; i <= 1; i++)
            a.push(g.samples[(y + j) * g.columns + x + i]);
        if (a.some((v) => v === null)) continue;
        const gx =
            (a[2] + 2 * a[5] + a[8] - (a[0] + 2 * a[3] + a[6])) / (8 * dx),
          gy = (a[6] + 2 * a[7] + a[8] - (a[0] + 2 * a[1] + a[2])) / (8 * dy);
        const light =
          (-gx * -0.5 + gy * 0.5 + Math.SQRT1_2) /
          Math.sqrt(1 + gx * gx + gy * gy);
        const shade = Math.round(255 * Math.max(0, light)),
          k = (y * g.columns + x) * 4;
        values[k] = values[k + 1] = values[k + 2] = shade;
        values[k + 3] = 255;
      }
    return values;
  }
  function contours(g) {
    const valid = g.samples.filter((v) => v !== null);
    let low = Infinity,
      high = -Infinity;
    for (const v of valid) {
      low = Math.min(low, v);
      high = Math.max(high, v);
    }
    const wanted = Math.max(g.arc_seconds > 1 ? 10 : 5, (high - low) / 12),
      power = 10 ** Math.floor(Math.log10(wanted)),
      interval = [1, 2, 5, 10].map((v) => v * power).find((v) => v >= wanted);
    const segments = [],
      [w, s, e, n] = g.bounds,
      coord = (x, y) => [
        w + (x / (g.columns - 1)) * (e - w),
        n - (y / (g.rows - 1)) * (n - s),
      ];
    for (
      let level = Math.ceil(low / interval) * interval;
      level < high;
      level += interval
    )
      for (let y = 0; y < g.rows - 1; y++)
        for (let x = 0; x < g.columns - 1; x++) {
          const v = [
            g.samples[y * g.columns + x],
            g.samples[y * g.columns + x + 1],
            g.samples[(y + 1) * g.columns + x + 1],
            g.samples[(y + 1) * g.columns + x],
          ];
          if (v.some((a) => a === null)) continue;
          const corners = [
              [x, y],
              [x + 1, y],
              [x + 1, y + 1],
              [x, y + 1],
            ],
            hits = [];
          for (let k = 0; k < 4; k++) {
            const j = (k + 1) % 4;
            if (v[k] >= level === v[j] >= level) continue;
            const t = (level - v[k]) / (v[j] - v[k]);
            hits.push(
              coord(
                corners[k][0] + t * (corners[j][0] - corners[k][0]),
                corners[k][1] + t * (corners[j][1] - corners[k][1]),
              ),
            );
          }
          if (
            hits.length === 4 &&
            v.reduce((a, b) => a + b, 0) / 4 >= level !== v[0] >= level
          )
            hits.push(hits.shift());
          for (let k = 0; k + 1 < hits.length; k += 2) {
            segments.push({ level, points: [hits[k], hits[k + 1]] });
            if (segments.length > 16384)
              throw Error(
                "Contour complexity limit exceeded; use a smaller DEM area.",
              );
          }
        }
    return { interval, segments };
  }
  function prepare(g) {
    let contour = null,
      contour_error = null;
    if (g.components.contours) {
      try {
        contour = contours(g);
      } catch (error) {
        if (!error.message.includes("Contour complexity limit")) throw error;
        contour_error = error.message;
      }
    }
    return {
      ...g,
      shades: g.components.hillshade ? hillshade(g) : null,
      contour,
      contour_error,
    };
  }
  return { elevation, hillshade, contours, prepare };
})();
if (typeof module !== "undefined") module.exports = Terrain;
