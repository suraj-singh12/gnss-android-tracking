/* DEM processing only. Geographic placement uses the existing GeoMap renderer. */
"use strict";
const Terrain = (() => {
  function elevation(g, lon, lat) {
    // The display viewport may use unwrapped Mercator longitudes at the dateline.
    if (lon > 180 || lon < -180)
      lon = ((((lon + 180) % 360) + 360) % 360) - 180;
    if (g.grids) {
      for (const grid of g.grids) {
        const value = elevation(grid, lon, lat);
        if (value !== null) return value;
      }
      return null;
    }
    const [w, s, e, n] = g.bounds;
    if (lon === 180 && w === -180) lon = -180;
    else if (lon === -180 && e === 180) lon = 180;
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
  function hillshade(g, neighbors = []) {
    function sample(x, y) {
      if (x >= 0 && x < g.columns && y >= 0 && y < g.rows)
        return g.samples[y * g.columns + x];
      // Native samples across an adjacent crop/dateline, never interpolated or invented.
      const step = g.arc_seconds / 3600;
      let lon = g.bounds[0] + x * step;
      if (lon > 180 || lon < -180)
        lon = ((((lon + 180) % 360) + 360) % 360) - 180;
      const lat = g.bounds[3] - y * step;
      for (const other of neighbors) {
        if (other.arc_seconds !== g.arc_seconds) continue;
        let nativeLon = lon;
        if (nativeLon === 180 && other.bounds[0] === -180) nativeLon = -180;
        else if (nativeLon === -180 && other.bounds[2] === 180) nativeLon = 180;
        const fx = (nativeLon - other.bounds[0]) / step,
          fy = (other.bounds[3] - lat) / step;
        const ix = Math.round(fx),
          iy = Math.round(fy);
        if (
          Math.abs(ix - fx) < 1e-5 &&
          Math.abs(iy - fy) < 1e-5 &&
          ix >= 0 &&
          ix < other.columns &&
          iy >= 0 &&
          iy < other.rows
        ) {
          const value = other.samples[iy * other.columns + ix];
          if (value !== null) return value;
        }
      }
      return null;
    }
    const values = new Uint8ClampedArray(g.columns * g.rows * 4),
      dy = (g.arc_seconds / 3600) * 111320,
      dx = dy * Math.cos((((g.bounds[1] + g.bounds[3]) / 2) * Math.PI) / 180);
    // Horn 3x3 gradient; illumination NW at 45 degrees. No detail upsampling.
    for (let y = 0; y < g.rows; y++)
      for (let x = 0; x < g.columns; x++) {
        const a = [];
        for (let j = -1; j <= 1; j++)
          for (let i = -1; i <= 1; i++) a.push(sample(x + i, y + j));
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
  function contours(g, chosenInterval) {
    const valid = g.samples.filter((v) => v !== null);
    let low = Infinity,
      high = -Infinity;
    for (const v of valid) {
      low = Math.min(low, v);
      high = Math.max(high, v);
    }
    const wanted = Math.max(g.arc_seconds > 1 ? 10 : 5, (high - low) / 12),
      power = 10 ** Math.floor(Math.log10(wanted)),
      interval =
        chosenInterval ||
        [1, 2, 5, 10].map((v) => v * power).find((v) => v >= wanted);
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
  function prepare(g, interval, neighbors) {
    let contour = null,
      contour_error = null;
    let validCells = 0;
    for (let y = 0; y < g.rows - 1; y++)
      for (let x = 0; x < g.columns - 1; x++) {
        const i = y * g.columns + x;
        if (
          [i, i + 1, i + g.columns, i + g.columns + 1].every(
            (k) => g.samples[k] !== null,
          )
        )
          validCells++;
      }
    if (g.components.contours && !validCells)
      contour_error =
        "Source has no valid four-corner DEM cells; contours unavailable.";
    if (g.components.contours && validCells) {
      try {
        contour = contours(g, interval);
      } catch (error) {
        if (!error.message.includes("Contour complexity limit")) throw error;
        contour_error = error.message;
      }
    }
    const shades = g.components.hillshade ? hillshade(g, neighbors) : null;
    const hasShade = shades?.some((v, i) => i % 4 === 3 && v > 0);
    return {
      ...g,
      shades: hasShade ? shades : null,
      hillshade_error:
        g.components.hillshade && !hasShade
          ? "Source lacks valid 3×3 DEM neighborhoods; hillshade unavailable."
          : null,
      contour,
      contour_error,
    };
  }
  return { elevation, hillshade, contours, prepare };
})();
if (typeof module !== "undefined") module.exports = Terrain;
