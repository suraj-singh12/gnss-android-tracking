/* Deterministic georeferenced fixture, real SQLite/server, offline Chromium restart. */
const assert = require("node:assert/strict"),
  fs = require("node:fs"),
  os = require("node:os"),
  path = require("node:path");
const { spawn, execFileSync } = require("node:child_process"),
  { once } = require("node:events");
const { chromium } = require(
  process.env.GNSS_PLAYWRIGHT_MODULE || "playwright",
);
const Axe = require(
  process.env.GNSS_AXE_MODULE || "@axe-core/playwright",
).default;
const work = fs.mkdtempSync(path.join(os.tmpdir(), "gnss-radius-")),
  out = process.env.GNSS_SCREENSHOT_DIR || work;
fs.mkdirSync(out, { recursive: true });
const db = path.join(work, "command.sqlite");
let command, browser;
const report = {
  category: "deterministic fixture — NOT real provider",
  passed: false,
  source: process.env.SOURCE_REVISION || "working-tree",
};
async function wait(f) {
  for (let i = 0; i < 150; i++) {
    if (await f()) return;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw Error("timeout");
}
(async () => {
  execFileSync(
    process.env.GNSS_GO || "go",
    [
      "test",
      "./internal/core",
      "-run",
      "^TestRadiusBrowserFixture$",
      "-count=1",
    ],
    {
      cwd: path.join(__dirname, "../../command"),
      env: { ...process.env, GNSS_MAP_FIXTURE_DB: db },
      stdio: "pipe",
    },
  );
  const net = require("node:net");
  async function port() {
    const s = net.createServer();
    s.listen(0, "127.0.0.1");
    await once(s, "listening");
    const n = s.address().port;
    await new Promise((r) => s.close(r));
    return n;
  }
  const local = `http://127.0.0.1:${await port()}`,
    ingest = `127.0.0.1:${await port()}`;
  async function start() {
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
          HTTPS_PROXY: "http://127.0.0.1:9",
          HTTP_PROXY: "http://127.0.0.1:9",
        },
      },
    );
    await wait(async () => {
      try {
        return (await fetch(local + "/local/maps")).ok;
      } catch {
        return false;
      }
    });
  }
  await start();
  browser = await chromium.launch({
    headless: true,
    executablePath: process.env.GNSS_CHROMIUM || undefined,
    args: ["--no-sandbox"],
  });
  const context = await browser.newContext({
      viewport: { width: 1440, height: 900 },
    }),
    page = await context.newPage(),
    errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await context.route("**/*", (r) =>
    r.request().url().startsWith(local) ? r.continue() : r.abort(),
  );
  await page.goto(local);
  await page.locator("#open-map").click();
  await page.locator("#map-preparation").evaluate((e) => (e.open = true));
  await page.locator("#map-lat").fill("27.3739");
  await page.locator("#map-lon").fill("88.7618");
  await page.locator("#map-radius").fill("0.373");
  await page.locator("#radius-unit").selectOption("1000");
  await page.locator("#preview-map").click();
  await page.locator("[data-requested-circle]").waitFor();
  assert.match(
    await page.locator("#map-preparation-status").textContent(),
    /373 m/,
  );
  await page.locator("#map-preview").scrollIntoViewIfNeeded();
  await page.screenshot({
    path: path.join(out, "radius-requested-circle-fixture.png"),
    fullPage: true,
  });
  await page.locator("#download-map").click();
  await wait(async () =>
    (await page.locator("#map-preparation-status").textContent()).includes(
      "Saved locally",
    ),
  );
  const maps = await (await fetch(local + "/local/maps")).json();
  assert.equal(maps.length, 1);
  await page.locator("#saved-maps").selectOption(maps[0].id);
  await wait(async () => !(await page.locator("#layer-contours").isDisabled()));
  await page.locator("#map-preparation").evaluate((e) => (e.open = true));
  await page.locator("#map-lat").fill("90");
  await page.locator("#preview-map").click();
  await wait(async () =>
    (await page.locator("#map-preparation-status").textContent()).includes(
      "pole",
    ),
  );
  assert.equal(await page.locator("#download-map").isDisabled(), true);
  await page.locator("#pick-map-center").click();
  await page.locator("#tracks").click({ position: { x: 500, y: 300 } });
  assert.ok(
    Number.isFinite(Number(await page.locator("#map-lat").inputValue())),
  );
  await page.locator('[data-close="map-settings"]').click();
  await page.locator("#open-layers").click();
  await page.locator("#layer-contours").check();
  await page.locator("#layer-hillshade").check();
  await page.locator("#layers").evaluate((e) => e.close());
  await wait(
    async () =>
      (await page.locator('[data-layer="contours"] polyline').count()) > 0,
  );
  assert.ok((await page.locator('[data-layer="hillshade"]').count()) > 0);
  await page.screenshot({
    path: path.join(out, "radius-terrain-both-fixture.png"),
    fullPage: true,
  });
  await page.locator("#open-layers").click();
  await page.locator("#layer-contours").uncheck();
  await page.locator("#layers").evaluate((e) => e.close());
  assert.equal(await page.locator('[data-layer="contours"]').count(), 0);
  await page.screenshot({
    path: path.join(out, "radius-hillshade-only-fixture.png"),
    fullPage: true,
  });
  await page.locator("#open-layers").click();
  await page.locator("#layer-contours").check();
  await page.locator("#layer-hillshade").uncheck();
  await page.locator("#layers").evaluate((e) => e.close());
  assert.equal(await page.locator('[data-layer="hillshade"]').count(), 0);
  await page.screenshot({
    path: path.join(out, "radius-contours-only-fixture.png"),
    fullPage: true,
  });
  const accessibility = await new Axe({ page }).analyze();
  report.accessibility_violations = accessibility.violations;
  assert.deepEqual(
    accessibility.violations.map((v) => v.id),
    [],
  );
  report.axe = "PASS";
  command.kill("SIGTERM");
  await once(command, "exit");
  await start();
  await page.reload();
  await wait(
    async () =>
      (await page.locator('[data-layer="contours"] polyline').count()) > 0,
  );
  assert.equal(await page.locator('[data-layer="hillshade"]').count(), 0);
  await page.screenshot({
    path: path.join(out, "radius-offline-reopened-fixture.png"),
    fullPage: true,
  });
  await page.route("**/local/maps/*/chunks/*?layer=terrain", (route) =>
    route.fulfill({ status: 503, body: "injected saved terrain read failure" }),
  );
  await page.reload();
  await wait(async () =>
    (await page.locator("#map-detail-status").textContent()).includes(
      "DEM not rendered",
    ),
  );
  assert.ok(
    (await page.locator('[data-layer="offline-map"] polyline').count()) > 0,
    "terrain read failure hid validated vectors",
  );
  assert.equal(await page.locator('[data-layer="contours"]').count(), 0);
  await page.screenshot({
    path: path.join(
      out,
      "radius-terrain-read-failure-vectors-retained-fixture.png",
    ),
    fullPage: true,
  });
  await page.unroute("**/local/maps/*/chunks/*?layer=terrain");
  await page.reload();
  await wait(
    async () =>
      (await page.locator('[data-layer="contours"] polyline').count()) > 0,
  );
  report.terrain_failure_preserves_vectors = true;
  await page.route("**/local/maps/*/chunks/*?layer=vector", (route) =>
    route.fulfill({ status: 503, body: "injected saved vector read failure" }),
  );
  await page.reload();
  await wait(async () =>
    (await page.locator("#map-detail-status").textContent()).includes(
      "vector not rendered",
    ),
  );
  assert.ok(
    (await page.locator('[data-layer="contours"] polyline').count()) > 0,
    "vector read failure hid validated terrain",
  );
  await page.unroute("**/local/maps/*/chunks/*?layer=vector");
  await page.reload();
  await wait(
    async () =>
      (await page.locator('[data-layer="offline-map"] polyline').count()) > 0,
  );
  report.vector_failure_preserves_terrain = true;

  assert.deepEqual(errors, []);
  report.passed = true;
  report.browser_render = await page.evaluate(() => terrainGeneration);
  report.manifest = await (
    await fetch(local + `/local/maps/${maps[0].id}/manifest`)
  ).json();
  report.elapsed_browser_ms = await page.evaluate(() => performance.now());
  console.log(
    JSON.stringify({
      passed: report.passed,
      category: report.category,
      source: report.source,
      browser_render: report.browser_render,
      evidence: out,
    }),
  );
})()
  .catch((e) => {
    report.error = e.stack;
    console.error(e);
    process.exitCode = 1;
  })
  .finally(async () => {
    fs.writeFileSync(
      path.join(out, "radius-ui-result.json"),
      JSON.stringify(report, null, 2),
    );
    if (browser) await browser.close();
    if (command) {
      command.kill("SIGTERM");
    }
  });
