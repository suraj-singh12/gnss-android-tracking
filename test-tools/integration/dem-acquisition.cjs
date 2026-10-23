/* Actual public Skadi probe, independent of deterministic software acceptance. */
const fs = require("node:fs"), zlib = require("node:zlib"), crypto = require("node:crypto");
const { classify } = require("./map-acquisition-support.cjs");
const report = { source_revision: process.env.SOURCE_REVISION || process.env.GITHUB_SHA || "local-unfrozen",
  checked_at: new Date().toISOString(), category: "real public provider", real_acquisition_passed: false,
  url: "https://elevation-tiles-prod.s3.amazonaws.com/skadi/N30/N30E078.hgt.gz" };
(async () => {
  const r = await fetch(report.url, { signal: AbortSignal.timeout(25000) });
  report.http_status = r.status; report.provider_reachable = true;
  if (!r.ok) throw Error(`DEM HTTP ${r.status}`);
  const parts = []; let bytes = 0;
  for await (const part of r.body) {
    bytes += part.length;
    if (bytes > 26 * 1024 * 1024) throw Error("DEM probe compressed limit exceeded");
    parts.push(part);
  }
  const compressed = Buffer.concat(parts), data = zlib.gunzipSync(compressed, { maxOutputLength: 3601 * 3601 * 2 });
  if (data.length !== 3601 * 3601 * 2) throw Error("Unexpected Skadi dimensions");
  const lat = 30.4598, lon = 78.0644, row = Math.round((31 - lat) * 3600), col = Math.round((lon - 78) * 3600);
  const elevation = data.readInt16BE((row * 3601 + col) * 2);
  if (elevation < 1000 || elevation > 2500) throw Error(`Unexpected Mussoorie height ${elevation}`);
  Object.assign(report, { compressed_bytes: compressed.length, decoded_bytes: data.length,
    sha256: crypto.createHash("sha256").update(data).digest("hex"), dimensions: [3601,3601],
    crs: "EPSG:4326", vertical_datum: "EGM96", known_coordinate: {lat,lon,row,col,elevation},
    real_acquisition_passed: true, outcome: "DEM data acquired" });
})().catch(e => { report.error = e.stack; report.error_cause = e.cause ? String(e.cause) : undefined; report.outcome = classify(String(e)); process.exitCode = 1; })
.finally(() => {
  if (process.env.GNSS_TERRAIN_PROVIDER_REPORT) {
    fs.mkdirSync(require("node:path").dirname(process.env.GNSS_TERRAIN_PROVIDER_REPORT), { recursive: true });
    fs.writeFileSync(process.env.GNSS_TERRAIN_PROVIDER_REPORT, JSON.stringify(report,null,2));
  }
  console.log(JSON.stringify(report,null,2));
});
