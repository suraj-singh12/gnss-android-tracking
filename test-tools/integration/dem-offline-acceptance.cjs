/* Production acquisition/UI/storage acceptance. Public providers by default;
 * controlled runs explicitly identify the verified real SRTM replay sample. */
"use strict";
const {
  waitForAcquisition,
  transferBudget,
  classify,
  json,
} = require("./map-acquisition-support.cjs");
const assert = require("node:assert/strict"),
  fs = require("node:fs"),
  os = require("node:os"),
  path = require("node:path"),
  net = require("node:net"),
  crypto = require("node:crypto");
const { spawn } = require("node:child_process"),
  { once } = require("node:events");
const { chromium } = require(
  process.env.GNSS_PLAYWRIGHT_MODULE || "playwright",
);
const work = fs.mkdtempSync(path.join(os.tmpdir(), "gnss-real-dem-"));
const report = {
  source_revision:
    process.env.SOURCE_REVISION || process.env.GITHUB_SHA || "local-unfrozen",
  checked_at: new Date().toISOString(),
  passed: false,
  checks: {
    geographic_data_acquired: "NOT TESTED",
    dem_data_acquired: "NOT TESTED",
    alignment_verified: "NOT TESTED",
    offline_restart_verified: "NOT TESTED",
  },
};
let command, browser, acceptancePage;
async function port() {
  const s = net.createServer();
  s.listen(0, "127.0.0.1");
  await once(s, "listening");
  const p = s.address().port;
  await new Promise((r) => s.close(r));
  return p;
}
async function wait(f) {
  for (let i = 0; i < 300; i++) {
    if (await f()) return;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw Error(
    "Startup/render deadline exceeded; see captured UI and manifest diagnostics",
  );
}
(async () => {
  const ingest = await port(),
    dashboard = await port(),
    local =
      process.env.GNSS_ACCEPTANCE_LOCAL || `http://127.0.0.1:${dashboard}`;
  const db =
    process.env.GNSS_ACCEPTANCE_DB || path.join(work, "command.sqlite");
  report.category = process.env.GNSS_ACCEPTANCE_LOCAL
    ? "deterministic production pipeline / real SRTM sample; controlled vector geometry"
    : "real public providers";
  report.ci_transfer_budget_bytes = transferBudget();
  async function start(offline = false) {
    command = spawn(
      process.env.GNSS_COMMAND_BINARY,
      [
        "-db",
        db,
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
  if (!process.env.GNSS_ACCEPTANCE_LOCAL) await start();
  browser = await chromium.launch({
    headless: true,
    executablePath: process.env.GNSS_CHROMIUM || undefined,
    args: ["--no-sandbox"],
  });
  const context = await browser.newContext({
      viewport: { width: 1440, height: 900 },
    }),
    page = await context.newPage();
  acceptancePage = page;
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(local);
  await page.locator("#open-map").click();
  await page.locator("#tab-map-preparation").click();
  await page.locator(".map-precise").evaluate((e) => (e.open = true));
  await page.getByRole("button", { name: "Custom", exact: true }).click();
  const lat = Number(process.env.GNSS_DEM_LAT || 30.4598),
    lon = Number(process.env.GNSS_DEM_LON || 78.061),
    radius = Number(process.env.GNSS_DEM_RADIUS || 373);
  await page.locator("#map-lat").fill(String(lat));
  await page.locator("#map-lon").fill(String(lon));
  await page.locator("#map-radius").fill(String(radius));
  await page.locator("#radius-unit").selectOption("1");
  await page.locator("#include-map-terrain").check();
  await page
    .locator("#download-hillshade")
    .evaluate((e) => (e.closest("details").open = true));
  for (const k of ["hillshade", "contours", "elevation"])
    await page.locator("#download-" + k).check();
  await page
    .locator("#preview-map")
    .evaluate((e) => (e.closest("details").open = true));
  await page.locator("#preview-map").click();
  await page.waitForFunction(() => !!preparedMap);
  const out = process.env.GNSS_SCREENSHOT_DIR || work;
  fs.mkdirSync(out, { recursive: true });
  await page.screenshot({
    path: path.join(out, "real-dem-area-preview.png"),
    fullPage: true,
  });
  await page.locator("#confirm-map-cost").check();
  await page.locator("#download-map").click();
  if (process.env.GNSS_ACCEPTANCE_LOCAL) {
    await page
      .locator("#approve-transfer")
      .waitFor({ state: "visible", timeout: 30000 });
    await page.waitForFunction(() => !downloadAbort);
    const id = await page.evaluate(() => activeMapID);
    const before = await json(local, `maps/${id}/manifest`);
    assert.equal(before.transfer.approved_bytes, 5000000);
    assert.ok(before.transfer.approval_required_bytes > 5000000);
    // Refuse the actual native confirmation; it must neither resume nor approve.
    page.once("dialog", (d) => d.dismiss());
    await page.locator("#approve-transfer").click();
    await new Promise((resolve) => setTimeout(resolve, 300));
    assert.deepEqual(
      (await json(local, `maps/${id}/manifest`)).transfer,
      before.transfer,
    );
    report.ui_approval_refused_without_transfer = true;
    await page.screenshot({
      path: path.join(out, "transfer-approval-refused.png"),
      fullPage: true,
    });
    // Leaving a pending approval preserves the map; re-opening resumes same job.
    await page.locator(".map-approval-defer").click();
    assert.deepEqual(
      (await json(local, `maps/${id}/manifest`)).transfer,
      before.transfer,
    );
    await page.locator("#open-map").click();
    await page.locator("#tab-map-preparation").click();
    report.pending_approval_save_use_preserves_map = true;
    const approvalRoute = `**/local/maps/${id}/approve-transfer`;
    await page.route(
      approvalRoute,
      async (route) => {
        await route.fetch();
        await route.abort("failed");
      },
      { times: 1 },
    );
    page.once("dialog", (d) => d.accept());
    await page.locator("#approve-transfer").click();
    await page.waitForFunction(() => !transferApprovalPending);
    const committed = await json(local, `maps/${id}/manifest`);
    assert.equal(
      committed.transfer.approved_bytes,
      before.transfer.approval_required_bytes,
    );
    assert.equal(committed.transfer.approval_required_bytes || 0, 0);
    let duplicatePrompts = 0;
    const duplicate = (d) => {
      duplicatePrompts++;
      d.dismiss();
    };
    page.on("dialog", duplicate);
    await page.locator("#approve-transfer").click();
    await page.waitForFunction(
      () =>
        !transferApprovalPending &&
        !downloadAbort &&
        radiusManifest?.chunks.every((c) => c.terrain.status === "complete"),
      null,
      { timeout: 120000 },
    );
    page.off("dialog", duplicate);
    assert.equal(
      duplicatePrompts,
      0,
      "lost approval response must not prompt again for the committed allowance",
    );
    report.ui_lost_approval_response_no_duplicate_prompt = true;
  }
  const acquired = await waitForAcquisition(page, local, report);
  const id = acquired.id,
    manifest = acquired.manifest;
  assert.equal(manifest.plan.request.radius_m, radius);
  assert.equal(manifest.chunks.length, 1, "single-chunk alignment case");
  const key = manifest.chunks[0].key;
  const map = await json(local, `maps/${id}/chunks/${key}?layer=vector`);
  const grid = await json(local, `maps/${id}/chunks/${key}?layer=terrain`);
  assert.ok(grid.provenance?.tiles.length);
  assert.equal(grid.vertical_datum, "EGM96");
  assert.equal(grid.arc_seconds, 1);
  report.checks.geographic_data_acquired = "PASS";
  report.checks.dem_data_acquired = "PASS";
  const onlineDownloadReport = manifest;
  assert.deepEqual(map.requested_bounds, manifest.chunks[0].bounds);
  assert.ok(map.requested_bounds[0] < lon && map.requested_bounds[2] > lon);
  assert.ok(map.requested_bounds[1] < lat && map.requested_bounds[3] > lat);
  // Independent decoded raw reference: compare EVERY persisted geographic node.
  assert.equal(
    grid.provenance.tiles.length,
    1,
    "this acceptance case uses one source tile",
  );
  const url = grid.provenance.tiles[0].url;
  // Controlled runs compare against pinned original HGT, never invented values.
  const raw = process.env.GNSS_ACCEPTANCE_RAW_HGT
    ? fs.readFileSync(process.env.GNSS_ACCEPTANCE_RAW_HGT)
    : fs.readFileSync(
        path.join(db + ".map-cache", url.split("/").pop().replace(".gz", "")),
      );
  assert.equal(raw.length, 3601 * 3601 * 2);
  assert.equal(
    crypto.createHash("sha256").update(raw).digest("hex"),
    grid.provenance.tiles[0].decoded_sha256,
  );
  for (let y = 0; y < grid.rows; y++)
    for (let x = 0; x < grid.columns; x++) {
      const row = Math.round((Math.floor(lat) + 1 - grid.bounds[3]) * 3600) + y,
        col = Math.round((grid.bounds[0] - Math.floor(lon)) * 3600) + x,
        v = raw.readInt16BE((row * 3601 + col) * 2);
      assert.equal(grid.samples[y * grid.columns + x], v === -32768 ? null : v);
    }
  const nearestRow = Math.round((Math.floor(lat) + 1 - lat) * 3600),
    nearestCol = Math.round((lon - Math.floor(lon)) * 3600),
    knownHeight = raw.readInt16BE((nearestRow * 3601 + nearestCol) * 2);
  if (!process.env.GNSS_DEM_LAT)
    assert.ok(knownHeight > 1000 && knownHeight < 2500);
  else
    assert.ok(
      knownHeight >= -12000 && knownHeight <= 9000 && knownHeight !== -32768,
      "real provider sample at exact requested coordinate; not a surveyed ground truth",
    );
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
        const terrain = globalThis.radiusGrids[0];
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
  report.checks.alignment_verified = "PASS";
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
      await fetch(
        local + `/local/maps/${id}/chunks/${key}?layer=terrain&format=gterrain`,
      )
    ).arrayBuffer(),
  );
  if (command) {
    command.kill("SIGTERM");
    await once(command, "exit");
  } else {
    // Terminate the test-only provider harness; production Command reopens its DB.
    process.kill(Number(process.env.GNSS_ACCEPTANCE_HARNESS_PID), "SIGTERM");
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  // Controlled harness used this port; restart production on the same URL.
  const restartPort = new URL(local).port;
  command = spawn(
    process.env.GNSS_COMMAND_BINARY,
    [
      "-db",
      db,
      "-ingest-listen",
      `127.0.0.1:${ingest}`,
      "-dashboard-listen",
      `127.0.0.1:${restartPort}`,
    ],
    {
      stdio: "ignore",
      env: {
        ...process.env,
        HTTP_PROXY: "http://127.0.0.1:1",
        HTTPS_PROXY: "http://127.0.0.1:1",
        NO_PROXY: "127.0.0.1,localhost",
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
  let externalBrowserRequests = 0;
  await context.route("**/*", (r) => {
    if (r.request().url().startsWith(local)) return r.continue();
    externalBrowserRequests++;
    return r.abort();
  });
  // Completed work is a no-network no-op, including after process interruption.
  await json(local, `maps/${id}/chunks/${key}`, { layer: "terrain" });
  const missing = await json(local, "map-jobs", {
    lat: lat + 0.02,
    lon,
    radius_m: 373,
    confirmed: true,
    components: { elevation: true },
  });
  const missingManifest = await json(local, `maps/${missing.id}/manifest`);
  const retry = await fetch(
    local + `/local/maps/${missing.id}/chunks/${missingManifest.chunks[0].key}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ layer: "vector" }),
      signal: AbortSignal.timeout(40000),
    },
  );
  assert.equal(
    retry.status,
    502,
    "offline new acquisition must truthfully fail",
  );
  assert.deepEqual(
    Buffer.from(
      await (
        await fetch(
          local +
            `/local/maps/${id}/chunks/${key}?layer=terrain&format=gterrain`,
        )
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
  report.checks.offline_restart_verified = "PASS";
  await page.mouse.move(offline.cursor.x, offline.cursor.y);
  assert.match(
    await page.locator("#hover").textContent(),
    /Estimated ground elevation ~-?\d+ m.*EGM96/,
  );
  await page.screenshot({
    path: path.join(out, "real-dem-aligned-offline-restart.png"),
    fullPage: true,
  });
  assert.deepEqual(errors, []);
  Object.assign(report, {
    passed: true,
    real_osm_features: map.features.length,
    osm_available_features: Object.fromEntries(
      ["roads", "paths", "rivers", "buildings"].map((kind) => [
        kind,
        map.features.filter((f) =>
          kind === "buildings"
            ? !!f.properties.building
            : kind === "rivers"
              ? !!f.properties.waterway
              : kind === "paths"
                ? [
                    "path",
                    "footway",
                    "track",
                    "steps",
                    "cycleway",
                    "bridleway",
                  ].includes(f.properties.highway)
                : !!f.properties.highway &&
                  ![
                    "path",
                    "footway",
                    "track",
                    "steps",
                    "cycleway",
                    "bridleway",
                  ].includes(f.properties.highway),
        ).length,
      ]),
    ),
    download_diagnostics: onlineDownloadReport,
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
  .catch(async (e) => {
    report.error = e.stack;
    report.error_cause = e.cause ? String(e.cause) : undefined;
    report.outcome = classify(e);
    if (acceptancePage) {
      try {
        report.visible_failure_state = (
          await acceptancePage.locator("body").innerText()
        ).slice(0, 16000);
        const output = process.env.GNSS_SCREENSHOT_DIR || work;
        fs.mkdirSync(output, { recursive: true });
        await acceptancePage.screenshot({
          path: path.join(output, "real-dem-failure.png"),
          fullPage: true,
        });
      } catch (captureError) {
        report.failure_capture_error = String(captureError);
      }
    }
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
