/* Actual public Skadi acquisition, separate from fixture tests. */
const fs = require("node:fs"), zlib = require("node:zlib"), crypto = require("node:crypto");
(async () => {
  const url = "https://elevation-tiles-prod.s3.amazonaws.com/skadi/N30/N30E078.hgt.gz";
  const r = await fetch(url, {signal: AbortSignal.timeout(25000)});
  if (!r.ok) throw Error(`DEM HTTP ${r.status}`);
  const compressed = Buffer.from(await r.arrayBuffer());
  const data = zlib.gunzipSync(compressed, {maxOutputLength: 3601*3601*2});
  if (data.length !== 3601*3601*2) throw Error("Unexpected Skadi dimensions");
  const lat=30.4598, lon=78.0644, row=Math.round((31-lat)*3600), col=Math.round((lon-78)*3600);
  const elevation=data.readInt16BE((row*3601+col)*2);
  if (elevation < 1000 || elevation > 2500) throw Error(`Unexpected Mussoorie height ${elevation}`);
  const report={source_revision:process.env.GITHUB_SHA||"local-unfrozen",checked_at:new Date().toISOString(),url,http_status:r.status,compressed_bytes:compressed.length,decoded_bytes:data.length,sha256:crypto.createHash("sha256").update(data).digest("hex"),dimensions:[3601,3601],crs:"EPSG:4326",vertical_datum:"EGM96",known_coordinate:{lat,lon,row,col,elevation},real_acquisition_passed:true};
  if(process.env.GNSS_TERRAIN_PROVIDER_REPORT) fs.writeFileSync(process.env.GNSS_TERRAIN_PROVIDER_REPORT,JSON.stringify(report,null,2));
  console.log(JSON.stringify(report,null,2));
})().catch(e=>{console.error(e);process.exitCode=1;});
