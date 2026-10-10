/* Explicit, bounded real-provider acceptance. Not part of offline regression.
 * External failure is recorded rather than masquerading as a fixture pass.
 */
"use strict";
const fs = require("node:fs"),
  os = require("node:os"),
  path = require("node:path"),
  net = require("node:net");
const { spawn } = require("node:child_process"),
  { once } = require("node:events");
const work = fs.mkdtempSync(path.join(os.tmpdir(), "gnss-online-map-"));
const result = {
  checked_at: new Date().toISOString(),
  source_revision: process.env.GITHUB_SHA || "local",
  real_providers: true,
  passed: false,
};
let command;
async function port() {
  const s = net.createServer();
  s.listen(0, "127.0.0.1");
  await once(s, "listening");
  const p = s.address().port;
  await new Promise((r) => s.close(r));
  return p;
}
(async () => {
  const phone = await port(),
    local = await port(),
    address = `http://127.0.0.1:${local}`;
  command = spawn(
    process.env.GNSS_COMMAND_BINARY,
    [
      "-db",
      path.join(work, "command.sqlite"),
      "-ingest-listen",
      `127.0.0.1:${phone}`,
      "-dashboard-listen",
      `127.0.0.1:${local}`,
    ],
    { stdio: "ignore" },
  );
  for (let i = 0; i < 100; i++) {
    try {
      if ((await fetch(address + "/local/state")).ok) break;
    } catch {}
    await new Promise((r) => setTimeout(r, 100));
  }
  async function post(route, body) {
    const r = await fetch(address + "/local/" + route, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(29000),
    });
    const data = await r.json();
    if (!r.ok)
      throw Error(`${route}: HTTP ${r.status}: ${JSON.stringify(data)}`);
    return data;
  }
  // One explicit search and one 500 x 500 m request; no retries/bulk download.
  try {
    const places = await post("map-search", { text: "India Gate, New Delhi" });
    if (!Array.isArray(places) || !places.length)
      throw Error("provider returned no places");
    result.search = { passed: true, count: places.length };
  } catch (e) {
    result.search = { passed: false, error: e.message };
  }
  try {
    const data = await post("map-preview", {
      lat: 28.6129,
      lon: 77.2295,
      width: 500,
      height: 500,
    });
    if (!data.features?.length || !data.attribution?.includes("OpenStreetMap"))
      throw Error("missing geographic features/attribution");
    const map = await post("maps", {
      source: "Real OSM acceptance: India Gate 500 m",
      data,
    });
    const reread = await (
      await fetch(address + "/local/maps/" + map.id)
    ).json();
    if (JSON.stringify(reread) !== JSON.stringify(data))
      throw Error("saved map differs from downloaded map");
    result.download = {
      passed: true,
      features: data.features.length,
      bytes: map.bytes,
      attribution: data.attribution,
    };
  } catch (e) {
    result.download = { passed: false, error: e.message };
  }
  result.passed = result.search.passed && result.download.passed;
})()
  .catch((e) => {
    result.error = e.message;
  })
  .finally(async () => {
    if (command && command.exitCode === null) {
      command.kill("SIGINT");
      await once(command, "exit");
    }
    const out = process.env.GNSS_MAP_ACCEPTANCE_REPORT;
    if (out) fs.writeFileSync(out, JSON.stringify(result, null, 2) + "\n");
    console.log(JSON.stringify(result, null, 2));
    if (!result.passed)
      console.log(
        "LIVE PROVIDER ACCEPTANCE BLOCKED — offline regression results remain separate.",
      );
    fs.rmSync(work, { recursive: true, force: true });
  });
