/* Real public providers. A failed provider is a failure, never a fixture pass. */
const fs = require("node:fs"),
  os = require("node:os"),
  path = require("node:path"),
  net = require("node:net"),
  assert = require("node:assert/strict");
const { spawn, execFileSync } = require("node:child_process"),
  { once } = require("node:events");
const work = fs.mkdtempSync(path.join(os.tmpdir(), "gnss-radius-online-")),
  db = path.join(work, "command.sqlite");
const out =
  process.env.GNSS_RADIUS_PROVIDER_REPORT ||
  path.join(work, "radius-real-provider.json");
const report = {
  source: process.env.SOURCE_REVISION || "working-tree",
  category: "real providers",
  passed: false,
  cases: [],
};
let command;
async function port() {
  const s = net.createServer();
  s.listen(0, "127.0.0.1");
  await once(s, "listening");
  const n = s.address().port;
  await new Promise((r) => s.close(r));
  return n;
}
(async () => {
  const local = `http://127.0.0.1:${await port()}`;
  command = spawn(
    process.env.GNSS_COMMAND_BINARY,
    [
      "-db",
      db,
      "-dashboard-listen",
      local.slice(7),
      "-ingest-listen",
      `127.0.0.1:${await port()}`,
    ],
    { stdio: "ignore" },
  );
  for (let i = 0; i < 100; i++) {
    try {
      if ((await fetch(local + "/local/maps")).ok) break;
    } catch {}
    await new Promise((r) => setTimeout(r, 100));
  }
  async function post(route, body) {
    const r = await fetch(local + "/local/" + route, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      }),
      data = await r.json();
    if (!r.ok) throw Error(JSON.stringify(data));
    return data;
  }
  for (const c of [
    { lat: 28.6139, lon: 77.209, radius_m: 373, name: "Delhi flat local" },
    {
      lat: 27.9999,
      lon: 88.9999,
      radius_m: 1234,
      name: "Himalayan degree boundary",
    },
    {
      lat: 27.95,
      lon: 88.95,
      radius_m: 7317,
      name: "Himalayan larger multi-chunk",
    },
  ]) {
    const evidence = { ...c, layers: {}, started: new Date().toISOString() },
      start = Date.now();
    report.cases.push(evidence);
    try {
      const plan = await post("map-plan", c),
        saved = await post("map-jobs", {
          ...c,
          confirmed: true,
          components: { hillshade: true, contours: true, elevation: true },
        });
      evidence.chunks = plan.chunks.length;
      evidence.source_tiles = plan.source_tiles.length;
      evidence.id = saved.id;
      for (const layer of ["vector", "terrain"]) {
        let completed = 0;
        try {
          for (const chunk of plan.chunks) {
            await post(`maps/${saved.id}/chunks/${chunk.key}`, { layer });
            completed++;
            if (layer === "vector") {
              const data = await (
                await fetch(
                  local +
                    `/local/maps/${saved.id}/chunks/${chunk.key}?layer=vector`,
                )
              ).json();
              assert.deepEqual(data.requested_bounds, chunk.bounds);
              if (completed < plan.chunks.length)
                await new Promise((r) => setTimeout(r, 10050));
            } else {
              const g = await (
                await fetch(
                  local +
                    `/local/maps/${saved.id}/chunks/${chunk.key}?layer=terrain`,
                )
              ).json();
              assert.equal(g.arc_seconds, 1);
              assert.equal(g.vertical_datum, "EGM96");
              const tiles = g.provenance.tiles.map((t) => {
                const name = t.url.split("/").pop().replace(".gz", ""),
                  match = /([NS])(\d+)([EW])(\d+)\.hgt/.exec(name),
                  raw = fs.readFileSync(path.join(db + ".map-cache", name));
                assert.equal(raw.length, 3601 * 3601 * 2);
                return {
                  raw,
                  lat: Number(match[2]) * (match[1] === "S" ? -1 : 1),
                  lon: Number(match[4]) * (match[3] === "W" ? -1 : 1),
                };
              });
              for (let y = 0; y < g.rows; y++)
                for (let x = 0; x < g.columns; x++) {
                  const lon = g.bounds[0] + x / 3600,
                    lat = g.bounds[3] - y / 3600,
                    values = [];
                  for (const t of tiles) {
                    const col = Math.round((lon - t.lon) * 3600),
                      row = Math.round((t.lat + 1 - lat) * 3600);
                    if (row >= 0 && row <= 3600 && col >= 0 && col <= 3600)
                      values.push(t.raw.readInt16BE(2 * (row * 3601 + col)));
                  }
                  const v = g.samples[y * g.columns + x];
                  assert.ok(
                    values.includes(v === null ? -32768 : v),
                    "independent raw HGT sample mismatch",
                  );
                }
            }
          }
          evidence.layers[layer] = { status: "PASS", completed };
        } catch (error) {
          evidence.layers[layer] = {
            status: "FAIL",
            completed,
            error: String(error),
          };
        }
      }
      evidence.manifest = await (
        await fetch(local + `/local/maps/${saved.id}/manifest`)
      ).json();
    } catch (error) {
      evidence.error = String(error);
    }
    evidence.elapsed_ms = Date.now() - start;
    await new Promise((resolve) => setTimeout(resolve, 10050));
  }
  report.passed = report.cases.every((c) =>
    ["vector", "terrain"].every((k) => c.layers[k]?.status === "PASS"),
  );
  if (!report.passed) process.exitCode = 1;
})()
  .catch((e) => {
    report.error = e.stack;
    process.exitCode = 1;
  })
  .finally(() => {
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, JSON.stringify(report, null, 2));
    console.log(
      JSON.stringify({
        passed: report.passed,
        cases: report.cases.map((c) => ({ name: c.name, layers: c.layers })),
        report: out,
      }),
    );
    command?.kill("SIGTERM");
  });
