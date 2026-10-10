/* Deterministic UI evidence: provider/DEM responses here are labelled fixtures. */
"use strict";
const assert = require("node:assert/strict"),
  fs = require("node:fs"),
  os = require("node:os"),
  path = require("node:path"),
  net = require("node:net");
const { spawn } = require("node:child_process"),
  { once } = require("node:events");
const { chromium } = require(
  process.env.GNSS_PLAYWRIGHT_MODULE || "playwright",
);
const AxeBuilder = require(
  process.env.GNSS_AXE_MODULE || "@axe-core/playwright",
).default;
const work = fs.mkdtempSync(path.join(os.tmpdir(), "gnss-map-ui-")),
  out = process.env.GNSS_SCREENSHOT_DIR || work;
let command, browser;
async function port() {
  const s = net.createServer();
  s.listen(0, "127.0.0.1");
  await once(s, "listening");
  const p = s.address().port;
  await new Promise((r) => s.close(r));
  return p;
}
(async () => {
  fs.mkdirSync(out, { recursive: true });
  const dashboard = await port(),
    ingest = await port(),
    local = `http://127.0.0.1:${dashboard}`;
  command = spawn(
    process.env.GNSS_COMMAND_BINARY,
    [
      "-db",
      path.join(work, "command.sqlite"),
      "-dashboard-listen",
      `127.0.0.1:${dashboard}`,
      "-ingest-listen",
      `127.0.0.1:${ingest}`,
    ],
    { stdio: "ignore" },
  );
  for (let i = 0; i < 350; i++) {
    try {
      if ((await fetch(local + "/local/state")).ok) break;
    } catch {}
    await new Promise((r) => setTimeout(r, 100));
  }
  browser = await chromium.launch({
    headless: true,
    executablePath: process.env.GNSS_CHROMIUM || undefined,
    args: ["--no-sandbox"],
  });
  const context = await browser.newContext({
      permissions: ["clipboard-read", "clipboard-write"],
      viewport: { width: 1280, height: 720 },
    }),
    page = await context.newPage();
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  const lat = 27.3739,
    lon = 88.7618,
    dy = 500 / 111320,
    dx = 500 / (111320 * Math.cos((lat * Math.PI) / 180));
  const fixture = {
    type: "FeatureCollection",
    requested_bounds: [lon - dx, lat - dy, lon + dx, lat + dy],
    features: [
      {
        type: "Feature",
        properties: {
          source: "Synthetic UI fixture — not provider acceptance",
          highway: "path",
        },
        geometry: {
          type: "LineString",
          coordinates: [
            [lon - dx, lat],
            [lon + dx, lat],
          ],
        },
      },
    ],
  };
  await page.route("**/local/map-preview", (route) =>
    route.fulfill({ json: fixture }),
  );
  await page.goto(local);
  await page.locator("#open-map").click();
  assert.equal(
    await page.locator("#current-map-title").textContent(),
    "Current Map",
  );
  await page.locator("#map-preparation > summary").click();
  await page.locator("#map-lat").fill(String(lat));
  await page.locator("#map-lon").fill(String(lon));
  await page.locator("#map-area").selectOption("1000");
  assert.equal(await page.locator("#map-width").inputValue(), "1000");
  assert.equal(await page.locator("#map-height").inputValue(), "1000");
  assert.equal(await page.locator("#custom-map-dimensions").isVisible(), false);
  for (const k of ["hillshade", "contours", "elevation"])
    await page.locator("#download-" + k).check();
  await page.locator("#preview-map").click();
  await page.waitForFunction(
    () => !document.querySelector("#download-map").disabled,
  );
  let savedID;
  const report = {
    fixture: true,
    provider: "Synthetic slow-provider fixture",
    dataset: "Skadi format fixture",
    centre_longitude_latitude: [lon, lat],
    area_width_height_metres: [1000, 1000],
    attempts: [
      {
        attempt: 1,
        compressed_bytes_received: 2516582,
        transfer_ms: 120000,
        decompression_ms: null,
        http_status: 200,
      },
    ],
    outcome: "failed",
    stage: "downloading",
    error: "DEM downloading timed out",
  };
  await page.route("**/local/maps/*/terrain-download", async (route) => {
    savedID = route.request().url().split("/").at(-2);
    await route.fulfill({
      contentType: "application/x-ndjson",
      body: [
        { stage: "connecting" },
        { stage: "downloading", received_bytes: 2516582, elapsed_ms: 120000 },
        {
          stage: "failed",
          error: report.error,
          report: { ...report, map_id: savedID },
        },
      ]
        .map((e) => JSON.stringify(e) + "\n")
        .join(""),
    });
  });
  await page.locator("#download-map").click();
  await page.waitForFunction(() =>
    document
      .querySelector("#map-preparation-status")
      .textContent.includes("Terrain unavailable"),
  );
  assert.equal((await (await fetch(local + "/local/maps")).json()).length, 1);
  await page.locator("#download-diagnostics > summary").click();
  assert.match(
    await page.locator("#download-report-text").textContent(),
    /2516582/,
  );
  await page.locator("#copy-download-report").click();
  assert.match(
    await page.evaluate(() => navigator.clipboard.readText()),
    /Synthetic slow-provider fixture/,
  );
  const file = page.waitForEvent("download");
  await page.locator("#export-download-report").click();
  await (await file).saveAs(path.join(out, "download-report-fixture.txt"));
  await page.locator("#download-diagnostics > summary").click();
  for (const theme of ["day", "night"]) {
    if (theme === "night") {
      await page.locator('[data-close="map-settings"]').click();
      await page.locator("#appearance").click();
      await page.locator("#open-map").click();
    }
    for (const size of [
      { width: 1280, height: 720 },
      { width: 1280, height: 800 },
      { width: 1440, height: 900 },
    ]) {
      await page.setViewportSize(size);
      await page.locator("#map-settings").evaluate((e) => (e.scrollTop = 0));
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollHeight > innerHeight,
        ),
        false,
      );
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth > innerWidth,
        ),
        false,
      );
      await page.screenshot({
        path: path.join(
          out,
          `map-layers-${theme}-${size.width}x${size.height}-fixture.png`,
        ),
        fullPage: true,
      });
      await page.locator("#download-progress").scrollIntoViewIfNeeded();
      await page.screenshot({
        path: path.join(
          out,
          `map-failure-${theme}-${size.width}x${size.height}-fixture.png`,
        ),
        fullPage: true,
      });
      const result = await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
        .analyze();
      assert.deepEqual(result.violations, []);
    }
  }
  await page
    .getByText("Acquire or import terrain for this saved map", { exact: true })
    .click();
  for (const k of ["hillshade", "contours", "elevation"])
    await page.locator("#prepare-" + k).check();
  await page.unroute("**/local/maps/*/terrain-download");
  await page.route("**/local/maps/*/terrain-download", async (route) => {
    await new Promise((r) => setTimeout(r, 700));
    try {
      await route.fulfill({
        contentType: "application/x-ndjson",
        body:
          JSON.stringify({ stage: "failed", error: "delayed fixture" }) + "\n",
      });
    } catch {}
  });
  await page.locator("#download-terrain").click();
  await page.locator("#cancel-download").waitFor({ state: "visible" });
  await page.locator("#cancel-download").click();
  await page.waitForFunction(() =>
    document.querySelector("#terrain-status").textContent.includes("cancelled"),
  );
  assert.equal((await (await fetch(local + "/local/maps")).json()).length, 1);
  assert.equal(await page.locator("#download-terrain").isEnabled(), true);
  await page.locator("#download-diagnostics > summary").click();
  await page.screenshot({
    path: path.join(out, "map-download-cancelled-fixture.png"),
    fullPage: true,
  });
  assert.deepEqual(errors, []);
  console.log(
    "PASS: exact-coordinate UI workflow, three viewports/two themes, no document scrolling, accessibility, progress/error/report copy/export, cancellation/retry and vector preservation. Synthetic fixture evidence, NOT provider acceptance.",
  );
})()
  .catch((e) => {
    console.error(e);
    process.exitCode = 1;
  })
  .finally(async () => {
    if (browser) await browser.close();
    if (command) {
      command.kill("SIGINT");
      await once(command, "exit");
    }
  });
