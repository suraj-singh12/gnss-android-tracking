/* REAL public DEM + real Command/UI/storage, not a fixture/HEAD acceptance. */
"use strict";
const assert = require("node:assert/strict"),
  fs = require("node:fs"),
  os = require("node:os"),
  path = require("node:path"),
  net = require("node:net"),
  zlib = require("node:zlib"),
  crypto = require("node:crypto");
const { spawn } = require("node:child_process"),
  { once } = require("node:events");
const { chromium } = require(
  process.env.GNSS_PLAYWRIGHT_MODULE || "playwright",
);
const work = fs.mkdtempSync(path.join(os.tmpdir(), "gnss-real-dem-"));
const report = {
  source_revision: process.env.GITHUB_SHA || "local-unfrozen",
  checked_at: new Date().toISOString(),
  passed: false,
};
let command, browser;
async function port() {
  const s = net.createServer();
  s.listen(0, "127.0.0.1");
  await once(s, "listening");
  const p = s.address().port;
  await new Promise((r) => s.close(r));
  return p;
}
async function wait(f) {
  for (let i = 0; i < 350; i++) {
    if (await f()) return;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw Error("Acceptance wait timed out");
}
(async () => {
  const ingest = await port(),
    dashboard = await port(),
    local = `http://127.0.0.1:${dashboard}`;
  async function start(offline = false) {
    command = spawn(
      process.env.GNSS_COMMAND_BINARY,
      [
        "-db",
        path.join(work, "command.sqlite"),
        "-ingest-listen",
        `127.0.0.1:${ingest}`,
        "-dashboard-listen",
        `127.0.0.1:${dashboard}`,
      ],
      {
        stdio: "ignore",
        env: {
          ...process.env,
          ...(offline
            ? {
                HTTP_PROXY: "http://127.0.0.1:1",
                HTTPS_PROXY: "http://127.0.0.1:1",
                NO_PROXY: "127.0.0.1,localhost",
              }
            : {}),
        },
      },
    );
    await wait(async () => {
      try {
        return (await fetch(local + "/local/state")).ok;
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
    page = await context.newPage();
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(local);
  await page.locator("#open-map").click();
  await page.locator("#map-preparation summary").click();
  const lat = 30.4598,
    lon = 78.0644;
  await page.locator("#map-lat").fill(String(lat));
  await page.locator("#map-lon").fill(String(lon));
  for (const k of ["hillshade", "contours", "elevation"])
    await page.locator("#download-" + k).check();
  assert.equal(await page.locator("#map-width").inputValue(), "500");
  assert.equal(await page.locator("#map-height").inputValue(), "500");
  await page.locator("#preview-map").click();
  await wait(() => page.locator("#download-map").isEnabled());
  const out = process.env.GNSS_SCREENSHOT_DIR || work;
  fs.mkdirSync(out, { recursive: true });
  await page.screenshot({
    path: path.join(out, "real-dem-area-preview.png"),
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
  const id = maps[0].id;
  const map = await (await fetch(local + "/local/maps/" + id)).json();
  const grid = await (
    await fetch(local + "/local/maps/" + id + "/terrain")
  ).json();
  assert.ok(grid.provenance?.tiles.length);
  assert.equal(grid.vertical_datum, "EGM96");
  assert.equal(grid.arc_seconds, 1);
  assert.ok(
    Math.abs((map.requested_bounds[0] + map.requested_bounds[2]) / 2 - lon) <
      1e-6,
  );
  assert.ok(
    Math.abs((map.requested_bounds[1] + map.requested_bounds[3]) / 2 - lat) <
      1e-6,
  );
  // Independent decoded raw reference: compare EVERY persisted geographic node.
  const url =
    "https://elevation-tiles-prod.s3.amazonaws.com/skadi/N30/N30E078.hgt.gz";
  const response = await fetch(url, { signal: AbortSignal.timeout(25000) });
  assert.equal(response.status, 200);
  const raw = zlib.gunzipSync(Buffer.from(await response.arrayBuffer()), {
    maxOutputLength: 3601 * 3601 * 2,
  });
  assert.equal(raw.length, 3601 * 3601 * 2);
  assert.equal(
    crypto.createHash("sha256").update(raw).digest("hex"),
    grid.provenance.tiles[0].decoded_sha256,
  );
  for (let y = 0; y < grid.rows; y++)
    for (let x = 0; x < grid.columns; x++) {
      const row = Math.round((31 - grid.bounds[3]) * 3600) + y,
        col = Math.round((grid.bounds[0] - 78) * 3600) + x,
        v = raw.readInt16BE((row * 3601 + col) * 2);
      assert.equal(grid.samples[y * grid.columns + x], v === -32768 ? null : v);
    }
  const nearestRow = Math.round((31 - lat) * 3600),
    nearestCol = Math.round((lon - 78) * 3600),
    knownHeight = raw.readInt16BE((nearestRow * 3601 + nearestCol) * 2);
  assert.ok(knownHeight > 1000 && knownHeight < 2500);
  await page.locator('[data-close="map-settings"]').click();
  await page.locator("#open-layers").click();
  for (const k of ["hillshade", "contours", "elevation"]) {
    assert.equal(await page.locator("#layer-" + k).isChecked(), false);
    await page.locator("#layer-" + k).check();
  }
  await page.locator('[data-close="layers"]').click();
  async function alignment() {
    return page.evaluate(
      ({ lon, lat }) => {
        const root = document.querySelector("#tracks"),
          image = document.querySelector('[data-layer="hillshade"]'),
          [x, y] = GeoMap.project(terrain.bounds[0], terrain.bounds[3]);
        const expectedX =
            (x - viewport.cx) * viewport.scale + root.clientWidth / 2,
          expectedY =
            root.clientHeight / 2 - (y - viewport.cy) * viewport.scale;
        const pixelAligned =
          Math.abs(
            Number(image.getAttribute("x")) +
              Number(image.getAttribute("width")) / terrain.columns / 2 -
              expectedX,
          ) < 1e-6 &&
          Math.abs(
            Number(image.getAttribute("y")) +
              Number(image.getAttribute("height")) / terrain.rows / 2 -
              expectedY,
          ) < 1e-6;
        const segment = terrain.contour.segments[0],
          points = document
            .querySelector('[data-layer="contours"] polyline')
            .getAttribute("points")
            .split(" ")
            .map((s) => s.split(",").map(Number));
        const contourAligned = segment.points.every(([lon, lat], i) => {
          const [x, y] = GeoMap.project(lon, lat);
          return (
            Math.abs(
              points[i][0] -
                ((x - viewport.cx) * viewport.scale + root.clientWidth / 2),
            ) < 1e-6 &&
            Math.abs(
              points[i][1] -
                (root.clientHeight / 2 - (y - viewport.cy) * viewport.scale),
            ) < 1e-6
          );
        });
        const p = GeoMap.project(lon, lat),
          r = root.getBoundingClientRect();
        return {
          pixelAligned,
          contourAligned,
          contourSegments: terrain.contour.segments.length,
          elevation: Terrain.elevation(terrain, lon, lat),
          cursor: {
            x:
              r.left +
              root.clientWidth / 2 +
              (p[0] - viewport.cx) * viewport.scale,
            y:
              r.top +
              root.clientHeight / 2 -
              (p[1] - viewport.cy) * viewport.scale,
          },
        };
      },
      { lon, lat },
    );
  }
  const aligned = await alignment();
  assert.ok(
    aligned.pixelAligned &&
      aligned.contourAligned &&
      aligned.contourSegments > 0,
  );
  await page.mouse.move(aligned.cursor.x, aligned.cursor.y);
  assert.match(
    await page.locator("#hover").textContent(),
    new RegExp(
      `Estimated ground elevation ~${Math.round(aligned.elevation)} m.*EGM96`,
    ),
  );
  await page.screenshot({
    path: path.join(out, "real-dem-aligned-online.png"),
    fullPage: true,
  });
  const terrainFile = Buffer.from(
    await (
      await fetch(local + "/local/maps/" + id + "/terrain-file")
    ).arrayBuffer(),
  );
  command.kill("SIGTERM");
  await once(command, "exit");
  await start(true);
  let externalBrowserRequests = 0;
  await context.route("**/*", (r) => {
    if (r.request().url().startsWith(local)) return r.continue();
    externalBrowserRequests++;
    return r.abort();
  });
  // Verify outbound acquisition really fails with blocked Command internet and
  // that this failed retry cannot overwrite the saved offline terrain.
  const retry = await fetch(local + "/local/maps/" + id + "/terrain-download", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ components: { elevation: true } }),
  });
  assert.equal(retry.status, 400);
  assert.deepEqual(
    Buffer.from(
      await (
        await fetch(local + "/local/maps/" + id + "/terrain-file")
      ).arrayBuffer(),
    ),
    terrainFile,
  );
  await page.reload();
  await page.waitForSelector('[data-layer="hillshade"]');
  await page.waitForSelector('[data-layer="contours"]');
  const offline = await alignment();
  assert.equal(offline.elevation, aligned.elevation);
  assert.ok(offline.pixelAligned && offline.contourAligned);
  assert.equal(externalBrowserRequests, 0);
  await page.mouse.move(offline.cursor.x, offline.cursor.y);
  assert.match(
    await page.locator("#hover").textContent(),
    /Estimated ground elevation ~\d+ m.*EGM96/,
  );
  await page.screenshot({
    path: path.join(out, "real-dem-aligned-offline-restart.png"),
    fullPage: true,
  });
  assert.deepEqual(errors, []);
  Object.assign(report, {
    passed: true,
    real_osm_features: map.features.length,
    requested_bounds: map.requested_bounds,
    grid: { bounds: grid.bounds, rows: grid.rows, columns: grid.columns },
    provenance: grid.provenance,
    known_coordinate: {
      lat,
      lon,
      nearest_elevation_m: knownHeight,
      interpolated_elevation_m: aligned.elevation,
    },
    every_grid_node_matches_raw: true,
    hillshade_pixel_centres_aligned: true,
    contours_geographically_aligned: true,
    offline_restart_passed: true,
    blocked_internet_retry_retains_terrain: true,
    external_browser_requests: externalBrowserRequests,
  });
})()
  .catch((e) => {
    report.error = e.stack;
    process.exitCode = 1;
  })
  .finally(async () => {
    command?.kill("SIGTERM");
    await browser?.close();
    if (process.env.GNSS_TERRAIN_PROVIDER_REPORT)
      fs.writeFileSync(
        process.env.GNSS_TERRAIN_PROVIDER_REPORT,
        JSON.stringify(report, null, 2),
      );
    console.log(JSON.stringify(report, null, 2));
  });
