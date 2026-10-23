/* Runs the production HTTP/UI pipeline against a test-only controlled provider.
 * Original real SRTM samples are checksum-verified in Go. No production bypass.
 */
"use strict";
const fs = require("node:fs"), os = require("node:os"), path = require("node:path");
const { spawn, execFileSync } = require("node:child_process");
const { once } = require("node:events");
const work = fs.mkdtempSync(path.join(os.tmpdir(), "gnss-controlled-acquisition-"));
const config = path.join(work, "server.json"), db = path.join(work, "command.sqlite");
let harness, child;
(async () => {
  const reference = process.env.GNSS_MAP_REFERENCE_BINARY || path.join(work, "map-reference");
  if (!process.env.GNSS_MAP_REFERENCE_BINARY)
    execFileSync(process.env.GNSS_GO || "go", ["test", "-c", "-o", reference, "./internal/core"],
      { cwd: path.join(__dirname, "../../command"), stdio: "inherit" });
  harness = spawn(reference, ["-test.run=^TestMapAcquisitionBrowserServer$", "-test.timeout=16m", "-test.v"], {
    cwd: path.join(__dirname, "../../command/internal/core"), stdio: "inherit",
    env: { ...process.env, GNSS_ACQUISITION_SERVER_CONFIG: config, GNSS_ACCEPTANCE_DB: db },
  });
  const end = Date.now() + 30000;
  while (!fs.existsSync(config)) {
    if (harness.exitCode !== null) throw Error("Controlled provider harness exited");
    if (Date.now() > end) throw Error("Controlled provider startup deadline");
    await new Promise(r => setTimeout(r, 100));
  }
  const info = JSON.parse(fs.readFileSync(config));
  child = spawn(process.execPath, [path.join(__dirname, "dem-offline-acceptance.cjs")], {
    stdio: "inherit", env: { ...process.env,
      GNSS_ACCEPTANCE_LOCAL: info.local, GNSS_ACCEPTANCE_DB: db,
      GNSS_ACCEPTANCE_RAW_HGT: info.raw, GNSS_ACCEPTANCE_HARNESS_PID: String(info.pid),
      GNSS_DEM_LAT: "58.6101", GNSS_DEM_LON: "-133.8101", GNSS_DEM_RADIUS: "373",
      GNSS_CI_TRANSFER_BUDGET_BYTES: "30000000",
    },
  });
  const [code] = await once(child, "exit");
  if (code !== 0) throw Error(`Deterministic acquisition/approval/alignment/restart failed (${code})`);
})().catch(error => { console.error(error); process.exitCode = 1; })
 .finally(() => { child?.kill("SIGTERM"); harness?.kill("SIGTERM"); });
