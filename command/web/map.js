/* Offline presentation only. No transport, reconstruction or distance policy. */
"use strict";
const GeoMap = (() => {
  const radius = 6378137,
    limit = 85.05112878;
  function project(lon, lat) {
    return [
      (radius * lon * Math.PI) / 180,
      radius *
        Math.log(
          Math.tan(
            Math.PI / 4 +
              (Math.max(-limit, Math.min(limit, lat)) * Math.PI) / 360,
          ),
        ),
    ];
  }
  function parse(text, source) {
    if (text.length > 20 * 1024 * 1024)
      throw Error("Map exceeds the 20 MB limit.");
    const data = JSON.parse(text);
    if (data.crs)
      throw Error(
        "Legacy CRS declarations are unsupported. Export RFC 7946 WGS84 GeoJSON.",
      );
    const shapes = [],
      bounds = [Infinity, Infinity, -Infinity, -Infinity];
    let count = 0;
    function point(p) {
      if (
        !Array.isArray(p) ||
        p.length < 2 ||
        !Number.isFinite(p[0]) ||
        !Number.isFinite(p[1]) ||
        Math.abs(p[0]) > 180 ||
        Math.abs(p[1]) > limit
      )
        throw Error(
          "Invalid WGS84 coordinate or latitude outside Web Mercator coverage.",
        );
      if (++count > 100000)
        throw Error("Map exceeds 100,000 coordinate limit.");
      bounds[0] = Math.min(bounds[0], p[0]);
      bounds[1] = Math.min(bounds[1], p[1]);
      bounds[2] = Math.max(bounds[2], p[0]);
      bounds[3] = Math.max(bounds[3], p[1]);
      return project(p[0], p[1]);
    }
    function line(c, polygon = false) {
      if (!Array.isArray(c) || c.length < (polygon ? 4 : 2))
        throw Error("Invalid map line/ring.");
      if (
        polygon &&
        (c[0][0] !== c[c.length - 1][0] || c[0][1] !== c[c.length - 1][1])
      )
        throw Error("Polygon rings must be closed.");
      return c.map(point);
    }
    function geometry(g, depth = 0) {
      if (!g) return;
      if (depth > 16 || g.crs) throw Error("Unsupported map nesting or CRS.");
      switch (g.type) {
        case "Point":
          shapes.push({ type: "point", coordinates: [point(g.coordinates)] });
          break;
        case "MultiPoint":
          for (const p of g.coordinates)
            shapes.push({ type: "point", coordinates: [point(p)] });
          break;
        case "LineString":
          shapes.push({ type: "line", coordinates: line(g.coordinates) });
          break;
        case "MultiLineString":
          for (const c of g.coordinates)
            shapes.push({ type: "line", coordinates: line(c) });
          break;
        case "Polygon":
          shapes.push({
            type: "polygon",
            rings: g.coordinates.map((c) => line(c, true)),
          });
          break;
        case "MultiPolygon":
          for (const c of g.coordinates)
            shapes.push({
              type: "polygon",
              rings: c.map((r) => line(r, true)),
            });
          break;
        case "GeometryCollection":
          for (const c of g.geometries) geometry(c, depth + 1);
          break;
        default:
          throw Error("Unsupported GeoJSON geometry.");
      }
    }
    if (data.type === "FeatureCollection")
      for (const f of data.features) {
        if (f.type !== "Feature" || f.crs)
          throw Error("Invalid GeoJSON feature.");
        geometry(f.geometry);
      }
    else if (data.type === "Feature") geometry(data.geometry);
    else geometry(data);
    if (!count) throw Error("Map has no geographic coordinates.");
    if (bounds[2] - bounds[0] > 180)
      throw Error(
        "Antimeridian-spanning maps require splitting before import.",
      );
    return { source, shapes, bounds, count };
  }
  return { project, parse };
})();
if (typeof module !== "undefined") module.exports = GeoMap;
