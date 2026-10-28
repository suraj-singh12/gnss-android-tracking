const { menuAction } = require("./menu-actions.cjs");
const crypto = require("node:crypto");
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
      env: {
        ...process.env,
        GNSS_MAP_FIXTURE_DB: db,
        GNSS_PBF_FIXTURE_PATH: path.join(work, "fixture.osm.pbf"),
      },
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
  await menuAction(page, "open-map");
  await page.locator("#tab-map-preparation").click();
  await page.locator(".map-precise").evaluate((e) => (e.open = true));
  await page.getByRole("button", { name: "Custom", exact: true }).click();
  await page.locator("#map-lat").fill("27.3739");
  await page.locator("#map-lon").fill("88.7618");
  await page.locator("#map-radius").fill("0.373");
  await page.locator("#radius-unit").selectOption("1000");
  await page
    .locator("#preview-map")
    .evaluate((e) => (e.closest("details").open = true));
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
  await page.locator("#confirm-map-cost").check();
  await page.locator("#download-map").click();
  // This fixture intentionally contains source voids. Acquired grids are usable,
  // but the application must disclose partial coverage instead of success.
  await wait(async () =>
    (await page.locator("#map-preparation-status").textContent()).includes(
      "Partial map retained",
    ),
  );
  assert.match(
    await page.locator("#map-preparation-status").textContent(),
    /coverage or layers remain unavailable/,
  );
  report.known_voids_reported_as_partial = true;
  const maps = await (await fetch(local + "/local/maps")).json();
  assert.equal(maps.length, 1);
  await page.locator("#tab-maps-library").click();
  await page.locator("#saved-maps").selectOption(maps[0].id);
  await wait(async () => !(await page.locator("#layer-contours").isDisabled()));
  await page.locator("#tab-map-preparation").click();
  await page.locator(".map-precise").evaluate((e) => (e.open = true));
  await page.getByRole("button", { name: "Custom", exact: true }).click();
  await page.locator("#map-lat").fill("90");
  await page
    .locator("#preview-map")
    .evaluate((e) => (e.closest("details").open = true));
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
  await menuAction(page, "open-layers");
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
  await menuAction(page, "open-layers");
  await page.locator("#layer-contours").uncheck();
  await page.locator("#layers").evaluate((e) => e.close());
  assert.equal(await page.locator('[data-layer="contours"]').count(), 0);
  await page.screenshot({
    path: path.join(out, "radius-hillshade-only-fixture.png"),
    fullPage: true,
  });
  await menuAction(page, "open-layers");
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

  await menuAction(page, "open-map");
  await page.locator("#tab-maps-library").click();
  assert.match(
    await page.locator("#export-map").getAttribute("href"),
    /\/export$/,
  );
  const packageResponse = await fetch(
    local + `/local/maps/${maps[0].id}/export`,
  );
  assert.equal(packageResponse.status, 200);
  const packagePath = path.join(work, "portable.gmap.zip");
  fs.writeFileSync(
    packagePath,
    Buffer.from(await packageResponse.arrayBuffer()),
  );
  await page.locator("#tab-map-import").click();
  await page.locator("#source-map-file").setInputFiles(packagePath);
  await page.locator("#import-map-source").click();
  await wait(async () => {
    report.importStatus = await page
      .locator("#map-import-status")
      .textContent();
    return report.importStatus.includes("Stored in Saved Maps");
  });
  assert.ok(
    (await (await fetch(local + "/local/maps")).json()).some((m) =>
      m.id.startsWith("portable-"),
    ),
  );
  await page.screenshot({
    path: path.join(out, "map-portable-imported-fixture.png"),
    fullPage: true,
  });
  await page
    .locator("#source-map-file")
    .setInputFiles(path.join(work, "fixture.osm.pbf"));
  await page.locator("#source-map-mode").selectOption("full");
  await page.locator("#import-map-source").click();
  await wait(async () => {
    report.importStatus = await page
      .locator("#map-import-status")
      .textContent();
    return report.importStatus.includes("Stored in Saved Maps");
  });
  assert.match(
    await page.locator("#current-map-name").textContent(),
    /Local OSM PBF/,
  );
  const full = await (await fetch(local + "/local/maps")).json();
  assert.equal(full.find((m) => m.id.startsWith("import-")).chunks.vector, 9);
  await page.locator('[data-close="map-settings"]').click();
  // Nine verified chunks must display at the fitted overview, without zooming
  // around the historical eight-chunk failure.
  await page.waitForFunction(
    () =>
      terrainGeneration?.id === activeMapID &&
      terrainGeneration.visible_chunks === 9 &&
      document.querySelectorAll('[data-layer="offline-map"] polyline').length >
        0,
  );
  assert.equal(await page.evaluate(() => terrainGeneration.visible_chunks), 9);
  await page.screenshot({
    path: path.join(out, "map-pbf-full-extent-offline-render.png"),
    fullPage: true,
  });
  await menuAction(page, "open-map");
  await page.screenshot({
    path: path.join(out, "map-pbf-full-extent-fixture.png"),
    fullPage: true,
  });
  // Original, checksum-pinned regional PBF, imported locally through the real
  // UI/backend. Public provider availability is a separate Actions job.
  await page.locator("#tab-map-import").click();
  await page
    .locator("#source-map-file")
    .setInputFiles(
      path.join(
        __dirname,
        "../../command/internal/core/testdata/maps/delaware-latest.osm.pbf",
      ),
    );
  await page.locator("#source-map-mode").selectOption("crop");
  await page.locator("#import-map-lat").fill("39.745");
  await page.locator("#import-map-lon").fill("-75.548");
  await page.locator("#import-map-radius").fill("373");
  const beforeCancelledImport = (
    await (await fetch(local + "/local/maps")).json()
  ).length;
  let releaseImport;
  const heldImport = new Promise((resolve) => {
    releaseImport = resolve;
  });
  const holdImport = async (route) => {
    await heldImport;
    await route.abort("aborted").catch(() => {});
  };
  await page.route("**/local/map-import?**", holdImport);
  await page.locator("#import-map-source").click();
  await page.waitForFunction(() => mapImportPending && !!downloadAbort);
  assert.equal(
    await page.locator("#saved-maps").isDisabled(),
    true,
    "map selection must stay fixed during import",
  );
  await page.locator("#cancel-download").click();
  releaseImport();
  await page.waitForFunction(
    () =>
      !mapImportPending &&
      document
        .querySelector("#map-import-status")
        .textContent.includes("Import stopped"),
  );
  await page.unroute("**/local/map-import?**", holdImport);
  assert.equal(
    (await (await fetch(local + "/local/maps")).json()).length,
    beforeCancelledImport,
  );
  assert.equal(await page.locator("#source-map-file").isDisabled(), false);
  report.local_import_cancel_preserves_maps = "PASS";
  await page.locator("#import-map-source").click();
  await page.waitForFunction(
    () =>
      document
        .querySelector("#map-import-status")
        .textContent.includes("Stored in Saved Maps"),
    null,
    { timeout: 120000 },
  );
  const realID = await page.evaluate(() => activeMapID);
  const realManifest = await (
    await fetch(local + `/local/maps/${realID}/manifest`)
  ).json();
  assert.ok(realManifest.chunks.every((c) => c.vector.status === "complete"));
  const realRoute =
    local +
    `/local/maps/${realID}/chunks/${realManifest.chunks[0].key}?layer=vector`;
  const realData = await (await fetch(realRoute)).text();
  assert.equal(
    JSON.parse(realData).features.length,
    216,
    "original regional crop must retain the independently verified ways",
  );
  command.kill("SIGKILL");
  await once(command, "exit");
  await start();
  await page.reload();
  await wait(
    async () =>
      (await page.locator('[data-layer="offline-map"] polyline').count()) > 0,
  );
  assert.equal(
    await (await fetch(realRoute)).text(),
    realData,
    "crash/restart changed verified source geometry",
  );
  const coldManifest = await (
    await fetch(local + `/local/maps/${realID}/manifest`)
  ).json();
  assert.deepEqual(coldManifest.transfer, realManifest.transfer);
  report.real_pbf_crop_crash_offline = {
    status: "PASS",
    source:
      "immutable original Delaware PBF — not current public-provider acceptance",
    features: 216,
    sha256: crypto.createHash("sha256").update(realData).digest("hex"),
    map_id: realID,
  };
  await page.screenshot({
    path: path.join(out, "real-pbf-crop-offline-after-crash.png"),
    fullPage: true,
  });
  await menuAction(page, "open-map");
  await page.locator("#tab-maps-library").click();
  const panelAxe = await new Axe({ page }).analyze();
  assert.deepEqual(
    panelAxe.violations.map((v) => v.id),
    [],
  );
  report.portable_package_and_full_pbf = "PASS (local fixture, no providers)";
  await page.locator("#tab-maps-library").click();
  await page.locator("#saved-maps").selectOption(maps[0].id);
  assert.deepEqual(errors, []);
  report.browser_render = await page.evaluate(() => terrainGeneration);
  report.manifest = await (
    await fetch(local + `/local/maps/${maps[0].id}/manifest`)
  ).json();
  assert.ok(
    report.manifest.chunks.every(
      (c) => c.vector.status === "complete" && c.terrain.status === "complete",
    ),
  );
  assert.ok(report.manifest.chunks.some((c) => c.terrain.void_dem_cells > 0));
  assert.equal(report.manifest.transfer.approved_bytes, 5000000);
  assert.equal(report.manifest.transfer.received_bytes, 0);
  report.elapsed_browser_ms = await page.evaluate(() => performance.now());
  await page.locator("#saved-maps").selectOption(maps[0].id);
  page.once("dialog", (d) => d.dismiss());
  await page.locator("#delete-map").click();
  assert.equal((await (await fetch(local + "/local/maps")).json()).length, 4);
  page.once("dialog", (d) => d.accept());
  await page.locator("#delete-map").click();
  await wait(
    async () =>
      (await (await fetch(local + "/local/maps")).json()).length === 3,
  );
  await wait(async () =>
    (await page.locator("#current-map-name").textContent()).includes(
      "Blank canvas",
    ),
  );
  assert.equal(await page.locator("#resume-map").isVisible(), false);
  assert.equal(await page.locator("#save-use-map").isVisible(), false);
  assert.deepEqual(errors, []);
  report.confirmed_delete_and_cancel = "PASS";
  report.passed = true;

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
