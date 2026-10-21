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
  const ingest = `127.0.0.1:${await port()}`;
  command = spawn(
    process.env.GNSS_COMMAND_BINARY,
    ["-db", db, "-dashboard-listen", local.slice(7), "-ingest-listen", ingest],
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
              const savedBytes = await (
                await fetch(
                  local +
                    `/local/maps/${saved.id}/chunks/${chunk.key}?layer=terrain&format=gterrain`,
                )
              ).arrayBuffer();
              fs.mkdirSync(path.dirname(out), { recursive: true });
              fs.writeFileSync(
                path.join(
                  path.dirname(out),
                  `real-${c.name.replaceAll(" ", "-")}-${chunk.key}.gterrain`,
                ),
                Buffer.from(savedBytes),
              );
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

  if (process.platform === "linux") {
    report.command_memory_after_preparation = fs
      .readFileSync(`/proc/${command.pid}/status`, "utf8")
      .split("\n")
      .filter((line) => /^Vm(HWM|RSS):/.test(line));
  }
  report.stored_database_bytes = fs.statSync(db).size;
  report.source_cache_bytes = fs.existsSync(db + ".map-cache")
    ? fs
        .readdirSync(db + ".map-cache")
        .reduce(
          (n, f) => n + fs.statSync(path.join(db + ".map-cache", f)).size,
          0,
        )
    : 0;
  await realVisualAcceptance(local, ingest);

  async function realVisualAcceptance(local, ingest) {
    for (const c of report.cases)
      c.visual = { status: "NOT TESTED", reason: "no stored DEM acquired" };
    const cases = report.cases.filter((c) => c.layers.terrain?.completed > 0);
    if (!cases.length) return;
    let browser;
    try {
      const { chromium } = require(
        process.env.GNSS_PLAYWRIGHT_MODULE || "playwright",
      );
      if (command.exitCode === null) {
        command.kill("SIGTERM");
        await once(command, "exit");
      }
      command = spawn(
        process.env.GNSS_COMMAND_BINARY,
        [
          "-db",
          db,
          "-dashboard-listen",
          local.slice(7),
          "-ingest-listen",
          ingest,
        ],
        {
          stdio: "ignore",
          env: {
            ...process.env,
            HTTP_PROXY: "http://127.0.0.1:9",
            HTTPS_PROXY: "http://127.0.0.1:9",
          },
        },
      );
      for (let i = 0; i < 100; i++) {
        try {
          if ((await fetch(local + "/local/maps")).ok) break;
        } catch {}
        await new Promise((r) => setTimeout(r, 100));
      }
      browser = await chromium.launch({
        headless: true,
        executablePath: process.env.GNSS_CHROMIUM || undefined,
        args: ["--no-sandbox"],
      });
      const images =
        process.env.GNSS_SCREENSHOT_DIR ||
        path.join(path.dirname(out), "real-radius-visual");
      fs.mkdirSync(images, { recursive: true });
      for (const c of cases) {
        let context;
        try {
          context = await browser.newContext({
            viewport: { width: 1440, height: 900 },
          });
          await context.route("**/*", (r) =>
            r.request().url().startsWith(local) ? r.continue() : r.abort(),
          );
          const page = await context.newPage(),
            errors = [];
          page.on("pageerror", (e) => errors.push(e.message));
          await page.goto(local);
          await page.locator("#open-map").click();
          await page.waitForFunction(
            (id) =>
              Array.from(document.querySelector("#saved-maps").options).some(
                (o) => o.value === id,
              ),
            c.id,
          );
          await page.locator("#choose-saved-map").click();
          await page.locator("#saved-maps").selectOption(c.id);
          await page.locator('[data-close="map-settings"]').click();
          const prefix = c.name.replaceAll(" ", "-");
          await page.screenshot({
            path: path.join(
              images,
              prefix + "-real-source-coverage-offline.png",
            ),
            fullPage: true,
          });
          for (let i = 0; i < 5; i++) {
            await new Promise((r) => setTimeout(r, 800));
            if (!(await page.locator("#layer-hillshade").isDisabled())) break;
            await page.locator("#zoom-in").click();
          }
          await page.waitForFunction(
            () => !document.querySelector("#layer-hillshade").disabled,
            null,
            { timeout: 30000 },
          );
          await page.locator("#open-layers").click();
          await page.locator("#layer-hillshade").check();
          if (!(await page.locator("#layer-contours").isDisabled()))
            await page.locator("#layer-contours").check();
          await page.locator('[data-close="layers"]').click();
          assert.ok(
            (await page.locator('[data-layer="hillshade"]').count()) > 0,
            "stored real DEM did not render",
          );
          c.visual = {
            status: "PASS",
            offline_restarted: true,
            contour_segments: await page
              .locator('[data-layer="contours"] polyline')
              .count(),
            warnings: await page.locator("#map-detail-status").textContent(),
            screenshots: images,
          };
          await page.screenshot({
            path: path.join(images, prefix + "-real-terrain-offline.png"),
            fullPage: true,
          });
          await page.locator("#open-layers").click();
          await page.locator("#layer-hillshade").uncheck();
          await page.locator('[data-close="layers"]').click();
          assert.equal(
            await page.locator('[data-layer="hillshade"]').count(),
            0,
          );
          await page.screenshot({
            path: path.join(images, prefix + "-real-hillshade-off.png"),
            fullPage: true,
          });
          await page.locator("#open-layers").click();
          if (!(await page.locator("#layer-contours").isDisabled()))
            await page.locator("#layer-contours").uncheck();
          await page.locator('[data-close="layers"]').click();
          assert.equal(
            await page.locator('[data-layer="contours"]').count(),
            0,
          );
          assert.deepEqual(errors, []);
        } catch (error) {
          c.visual = { status: "FAIL", error: String(error) };
        } finally {
          await context?.close();
        }
      }
    } catch (error) {
      for (const c of cases)
        c.visual = { status: "FAIL", error: String(error) };
    } finally {
      await browser?.close();
    }
  }
  report.passed = report.cases.every(
    (c) =>
      ["vector", "terrain"].every((k) => c.layers[k]?.status === "PASS") &&
      c.visual?.status === "PASS",
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
