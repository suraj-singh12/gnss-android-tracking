/* Real candidate access evidence, not a terrain extraction implementation. */
const fs = require("node:fs");
(async () => {
  const report = {
    checked_at: new Date().toISOString(),
    source_revision: process.env.GITHUB_SHA || "local-unfrozen",
    real_provider_probe: true,
    implemented_acquisition: false,
    extraction_passed: false,
    reason:
      "No verified automatic bounded DEM adapter; use documented local SRTM import.",
    candidates: [],
  };
  for (const url of [
    "https://copernicus-dem-30m.s3.amazonaws.com/",
    "https://portal.opentopography.org/API/globaldem",
  ]) {
    try {
      const r = await fetch(url, {
        method: "HEAD",
        signal: AbortSignal.timeout(15000),
      });
      report.candidates.push({ url, http_status: r.status, reachable: r.ok });
    } catch (e) {
      report.candidates.push({
        url,
        error: e.message,
        cause: e.cause?.message,
      });
    }
  }
  if (process.env.GNSS_TERRAIN_PROVIDER_REPORT)
    fs.writeFileSync(
      process.env.GNSS_TERRAIN_PROVIDER_REPORT,
      JSON.stringify(report, null, 2),
    );
  console.log(JSON.stringify(report, null, 2));
  console.log(
    "Automatic DEM acceptance NOT COMPLETE. Fixture/local import evidence is separate.",
  );
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
