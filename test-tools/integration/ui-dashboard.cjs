/* Real Command API + Chromium. Fixture data is synthetic; no mocked state API. */
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
const { default: AxeBuilder } = require(
  process.env.GNSS_AXE_MODULE || "@axe-core/playwright",
);
const { randomUUID } = require("node:crypto");
const work = fs.mkdtempSync(path.join(os.tmpdir(), "gnss-ui-")),
  out = process.env.GNSS_SCREENSHOT_DIR || path.join(work, "screenshots");
fs.mkdirSync(out, { recursive: true });
let command, browser;
async function port() {
  const s = net.createServer();
  s.listen(0, "127.0.0.1");
  await once(s, "listening");
  const p = s.address().port;
  await new Promise((r) => s.close(r));
  return p;
}
async function wait(check) {
  for (let i = 0; i < 150; i++) {
    if (await check()) return;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw Error("Timed out waiting for real state");
}
(async () => {
  const phone = "http://127.0.0.1:" + (await port()),
    local = "http://127.0.0.1:" + (await port());
  command = spawn(
    process.env.GNSS_COMMAND_BINARY,
    [
      "-db",
      path.join(work, "command.sqlite"),
      "-ingest-listen",
      phone.slice(7),
      "-dashboard-listen",
      local.slice(7),
    ],
    { stdio: "ignore" },
  );
  await wait(async () => {
    try {
      return (await fetch(local + "/local/state")).ok;
    } catch {
      return false;
    }
  });
  async function state() {
    return (await fetch(local + "/local/state")).json();
  }
  async function post(route, body) {
    const r = await fetch(local + "/local/" + route, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    assert.equal(r.status, 200, await r.text());
  }
  async function send(body, role) {
    const r = await fetch(phone + "/api/v1/messages", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(role ? { "X-GNSS-Delivery-Role": role } : {}),
      },
      body: JSON.stringify(body),
    });
    assert.equal(r.status, 200, await r.text());
  }
  browser = await chromium.launch({
    executablePath: process.env.GNSS_CHROMIUM || undefined,
    headless: true,
    args: ["--no-sandbox", "--autoplay-policy=user-gesture-required"],
  });
  const context = await browser.newContext({
    viewport: { width: 1280, height: 800 },
    timezoneId: "Asia/Kolkata",
  });
  const page = await context.newPage(),
    errors = [];
  page.on("pageerror", (e) => {
    errors.push(e.message);
    console.error("Browser:", e.message);
  });
  await page.goto(local);
  await page.waitForFunction(() =>
    document
      .querySelector("#connection")
      .textContent.includes("Command Connected"),
  );
  await page.screenshot({
    path: path.join(out, "command-1280-empty.png"),
    fullPage: true,
  });
  assert.equal(await page.locator("#start").isEnabled(), true);
  await page.locator("#start").click();
  assert.equal(
    (await state()).recording,
    null,
    "opening Start dialog must not mutate recording",
  );
  assert.equal(await page.locator("#current-mode").isChecked(), true);
  assert.equal(await page.locator("#session-mode").isDisabled(), true);
  await page
    .locator('#recording-start [data-close="recording-start"]')
    .last()
    .click();
  assert.equal(
    (await state()).recording,
    null,
    "cancel must not mutate recording",
  );
  await page.locator("#start").click();
  await page.route("**/local/recording", (route) =>
    route.fulfill({
      status: 409,
      contentType: "application/json",
      body: JSON.stringify({
        message: "Injected backend rejection; no mutation",
      }),
    }),
  );
  await page.locator("#confirm-recording").click();
  await page.waitForFunction(() =>
    document
      .querySelector("#recording-start-error")
      .textContent.includes("Injected backend rejection"),
  );
  assert.equal((await state()).recording, null);
  await page.unroute("**/local/recording");
  await page.locator("#confirm-recording").click();
  await wait(async () => !!(await state()).recording?.active);
  const fixture = JSON.parse(
    fs.readFileSync(
      path.join(
        __dirname,
        "../../protocol/fixtures/location-native-session.json",
      ),
      "utf8",
    ),
  );
  const partyIds = [],
    latestMessages = [];
  const now = Date.now(),
    sessionStart = new Date(now - 120000).toISOString();
  for (let i = 0; i < 5; i++) {
    const device = randomUUID(),
      session = randomUUID();
    partyIds.push(device);
    for (let n = 1; n <= 3; n++) {
      const m = structuredClone(fixture),
        age = i === 4 ? 3600000 : (4 - n) * 1000;
      m.device_id = device;
      m.message_id = randomUUID();
      m.party = {
        id: ["Alpha", "Bravo", "Charlie", "Delta", "Echo"][i],
        name: [
          "North team",
          "River team",
          "East team",
          "Ridge team",
          "West team",
        ][i],
      };
      m.sequence = n;
      if (i === 4) {
        m.config_state.local_reporting_interval_s = 5;
        m.config_state.effective_reporting_interval_s = 5;
      }
      m.captured_at = new Date(now - age).toISOString();
      m.fix.observed_at = m.captured_at;
      m.fix.latitude += i * 0.001 + n * 0.00015;
      m.fix.longitude += i * 0.001;
      m.fix.speed_mps = i === 1 ? 0 : 1.8;
      m.fix.bearing_deg = [0, 90, 180, 270, 90][i];
      m.health.battery_percent = 85 - i * 12;
      m.observation = {
        observation_id: m.message_id,
        tracking_session_id: session,
        session_started_at:
          i === 4 ? new Date(now - 3700000).toISOString() : sessionStart,
        observation_sequence: n,
        measurement_elapsed_ms: n * 1000,
      };
      if (i === 2) {
        m.history_progress = {
          tracking_session_id: session,
          session_started_at: sessionStart,
          latest_committed_sequence: 10003,
          oldest_pending_sequence: 4,
          pending_observations: 10000,
          unresolved_sequences: [],
          known_collection_loss: 0,
          measured_at: new Date(now).toISOString(),
        };
      }
      await send(m);
      latestMessages[i] = structuredClone(m);
    }
  }
  await post("recording", { action: "clear", confirmed: true });
  await page.waitForFunction(() => !document.querySelector("#start").disabled);
  await page.locator("#start").click();
  assert.equal(await page.locator("#current-mode").isChecked(), true);
  assert.equal(await page.locator("#session-mode").isEnabled(), true);
  await page.locator("#session-mode").check();
  await page.screenshot({
    path: path.join(out, "command-start-recording-session.png"),
    fullPage: true,
  });
  await page.locator("#confirm-recording").click();
  await wait(
    async () => (await state()).recording?.mode === "session_beginning",
  );
  await page.reload();
  await page.waitForFunction(
    () => document.querySelectorAll(".party-select").length === 5,
  );
  await page.getByRole("button", { name: "Charlie", exact: true }).click();
  await page.getByRole("button", { name: "Charlie", exact: true }).blur();
  await page.waitForFunction(() =>
    document
      .querySelector("#selected-detail")
      .textContent.includes("10000 pending"),
  );
  assert.ok((await page.locator("[data-live-device]").count()) >= 4);
  assert.equal(
    await page.locator("path[data-live-device]").count(),
    3,
    "fresh moving fixes have course arrows; stationary and stale fixes do not",
  );
  await page.screenshot({
    path: path.join(out, "command-1280-five-parties-recording.png"),
    fullPage: true,
  });
  for (const size of [
    [1280, 720],
    [1280, 800],
    [1440, 900],
    [760, 900],
    [430, 900],
  ]) {
    await page.setViewportSize({ width: size[0], height: size[1] });
    await page.locator("#fit").click();
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
      false,
      "horizontal overflow",
    );
    if (size[0] >= 1100)
      assert.ok(
        await page.evaluate(() => {
          const box = document
            .querySelector(".recording-bar .controls")
            .getBoundingClientRect();
          return Math.abs(box.x + box.width / 2 - innerWidth / 2) < 2;
        }),
        "Recording controls are geometrically centered",
      );
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollHeight > innerHeight,
      ),
      false,
      "document vertical overflow",
    );
    assert.ok(
      await page.evaluate(() => {
        const canvas = document
          .querySelector("#tracks")
          .getBoundingClientRect();
        return [...document.querySelectorAll('[data-layer="live"] text')].every(
          (label) => {
            const bounds = label.getBoundingClientRect();
            return (
              bounds.left >= canvas.left &&
              bounds.right <= canvas.right &&
              bounds.top >= canvas.top
            );
          },
        );
      }),
      "Fitted party labels must remain inside the geographic canvas",
    );
    await page.screenshot({
      path: path.join(out, `command-${size[0]}-five-parties.png`),
      fullPage: true,
    });
    await page.screenshot({
      path: path.join(out, `command-${size[0]}-${size[1]}-five-parties.png`),
      fullPage: true,
    });
  }
  await page.setViewportSize({ width: 1440, height: 900 });
  if (
    (await page.locator("#toggle-parties").getAttribute("aria-expanded")) ===
    "false"
  )
    await page.locator("#toggle-parties").click();
  if (
    (await page.locator("#toggle-details").getAttribute("aria-expanded")) ===
    "true"
  )
    await page.locator("#close-details").click();
  assert.ok(
    await page.evaluate(() => {
      const width = document
        .querySelector(".canvas")
        .getBoundingClientRect().width;
      return width / innerWidth >= 0.75 && width / innerWidth <= 0.85;
    }),
    "closed secondary detail leaves 75–85% map width",
  );
  await page.screenshot({
    path: path.join(out, "command-1440-map-dominant.png"),
    fullPage: true,
  });
  await page.locator("#toggle-details").click();
  const before = await page
    .locator("[data-live-device]")
    .first()
    .getAttribute("cx");
  await page.locator("#zoom-in").click();
  await page.locator("#tracks").focus();
  await page.keyboard.press("ArrowRight");
  assert.notEqual(
    await page.locator("[data-live-device]").first().getAttribute("cx"),
    before,
  );
  await page.locator("#fit").click();
  await page.locator("#focus-party").click();
  await page.locator("#open-map").click();
  await page.locator("#dots").fill("40");
  await page.locator("#dots").blur();
  const qualifiedBeforeRaw = (await state()).devices[partyIds[2]].total_m;
  await page.locator("#all-observations").check();
  assert.equal(await page.locator("#all-observations").isChecked(), true);
  assert.equal(
    (await state()).devices[partyIds[2]].total_m,
    qualifiedBeforeRaw,
  );
  const map = {
    type: "FeatureCollection",
    features: [
      {
        type: "Feature",
        properties: {},
        geometry: {
          type: "Polygon",
          coordinates: [
            [
              [77.208, 28.613],
              [77.216, 28.613],
              [77.216, 28.621],
              [77.208, 28.621],
              [77.208, 28.613],
            ],
          ],
        },
      },
    ],
  };
  await page.locator("#map-file").setInputFiles({
    name: "offline.geojson",
    mimeType: "application/geo+json",
    buffer: Buffer.from(JSON.stringify(map)),
  });
  await page.waitForSelector('[data-layer="offline-map"]');
  assert.match(await page.locator("#map-metadata").innerText(), /EPSG:4326/);
  await page.locator("#map-opacity").fill("0.25");
  await page.locator('[data-close="map-settings"]').click();
  await page.locator("#fit").click();
  await page.screenshot({
    path: path.join(out, "command-offline-map.png"),
    fullPage: true,
  });
  // Drag on the painted geographic background, rather than only testing keyboard pan.
  const dragStart = await page.evaluate(() => {
    const bounds = document.querySelector("#tracks").getBoundingClientRect();
    for (const fraction of [0.25, 0.35, 0.65, 0.75]) {
      const x = bounds.x + bounds.width * fraction;
      const y = bounds.y + bounds.height * 0.35;
      if (
        document.elementFromPoint(x, y)?.closest('[data-layer="offline-map"]')
      )
        return { x, y };
    }
    return null;
  });
  assert.ok(
    dragStart,
    "A painted offline-map background must be available for pan",
  );
  const beforeMapPan = await page
    .locator("[data-live-device]")
    .first()
    .getAttribute("cx");
  await page.mouse.move(dragStart.x, dragStart.y);
  await page.mouse.down();
  await page.mouse.move(dragStart.x + 60, dragStart.y + 30, { steps: 5 });
  await page.mouse.up();
  assert.notEqual(
    await page.locator("[data-live-device]").first().getAttribute("cx"),
    beforeMapPan,
  );
  await page.locator("#fit").click();
  await page.locator("#open-map").click();
  // Provider-boundary fixtures only: production map storage and renderer remain real.
  // A live public-provider download is separate acceptance evidence.
  const downloaded = structuredClone(map);
  downloaded.attribution =
    "© OpenStreetMap contributors · ODbL · synthetic browser acceptance fixture";
  downloaded.features[0].properties = { building: "yes" };
  downloaded.features.push({
    type: "Feature",
    properties: { highway: "path" },
    geometry: {
      type: "LineString",
      coordinates: [
        [77.208, 28.615],
        [77.216, 28.619],
      ],
    },
  });
  await page.route("**/local/map-search", (route) =>
    route.fulfill({
      json: [
        {
          display_name: "Acceptance fixture, Delhi",
          lat: "28.617",
          lon: "77.212",
        },
      ],
    }),
  );
  const areas = [];
  let delayPreview = false;
  await page.route("**/local/map-preview", async (route) => {
    areas.push(route.request().postDataJSON());
    if (delayPreview) await new Promise((r) => setTimeout(r, 300));
    return route.fulfill({ json: downloaded });
  });
  await page.locator("#map-preparation summary").click();
  await page.locator("#map-search").fill("Delhi");
  await page.locator("#search-map").click();
  await page.waitForFunction(
    () => document.querySelector("#map-search-results").options.length === 2,
  );
  await page.locator("#map-search-results").selectOption({ index: 1 });
  assert.equal(await page.locator("#map-lat").inputValue(), "28.617");
  for (const size of ["500", "1000", "custom"]) {
    await page.locator("#map-area").selectOption(size);
    if (size === "custom") {
      await page.locator("#map-width").fill("1500");
      await page.locator("#map-height").fill("300");
    }
    await page.locator("#preview-map").click();
    await page.waitForFunction(
      () => !document.querySelector("#download-map").disabled,
    );
    assert.match(
      await page.locator("#map-preparation-status").innerText(),
      /features.*KB/,
    );
  }
  assert.deepEqual(
    areas.map((a) => [a.width, a.height]),
    [
      [500, 500],
      [1000, 1000],
      [1500, 300],
    ],
  );
  await page.locator("#map-search-results").selectOption({ index: 1 });
  assert.equal(
    await page.locator("#download-map").isDisabled(),
    true,
    "changing place invalidates the previous preview",
  );
  await page.locator("#map-lat").fill("");
  await page.locator("#preview-map").click();
  assert.equal(
    areas.length,
    3,
    "blank coordinates must not silently become zero",
  );
  await page.locator("#map-lat").fill("28.617");
  delayPreview = true;
  await page.locator("#preview-map").click();
  await page.locator("#map-lon").fill("77.213");
  await page.waitForFunction(() =>
    document
      .querySelector("#map-preparation-status")
      .textContent.includes("Area changed"),
  );
  assert.equal(
    await page.locator("#download-map").isDisabled(),
    true,
    "a stale response cannot enable the wrong area's download",
  );
  delayPreview = false;
  await page.locator("#map-lon").fill("77.212");
  await page.locator("#preview-map").click();
  await page.waitForFunction(
    () => !document.querySelector("#download-map").disabled,
  );
  const mapDistance = (await state()).devices[partyIds[2]].total_m;
  await page.screenshot({
    path: path.join(out, "command-map-download-preview-fixture.png"),
    fullPage: true,
  });
  await page.locator("#download-map").click();
  await page.waitForFunction(() =>
    document
      .querySelector("#map-preparation-status")
      .textContent.includes("Saved locally"),
  );
  assert.equal((await (await fetch(local + "/local/maps")).json()).length, 2);
  assert.equal((await state()).devices[partyIds[2]].total_m, mapDistance);
  await page.locator('[data-close="map-settings"]').click();
  await page.reload();
  await page.waitForSelector('[data-layer="offline-map"]');
  assert.match(
    await page.locator("#map-attribution").innerText(),
    /OpenStreetMap/,
  );
  // Synthetic georeferenced HGT: genuine local decoder/storage/rendering, NOT provider evidence.
  await page.locator("#open-layers").click();
  for (const k of ["hillshade", "contours", "elevation"])
    assert.equal(await page.locator("#layer-" + k).isDisabled(), true);
  await page.screenshot({
    path: path.join(out, "command-vector-only-layers.png"),
    fullPage: true,
  });
  await page.locator('[data-close="layers"]').click();
  await page.locator("#open-map").click();
  await page
    .getByText("Prepare terrain from a local DEM", { exact: true })
    .click();
  const hgt = Buffer.alloc(1201 * 1201 * 2);
  for (let y = 0; y < 1201; y++)
    for (let x = 0; x < 1201; x++)
      hgt.writeInt16BE(
        Math.round(500 + 250 * Math.sin(x / 7) * Math.cos(y / 9)),
        (y * 1201 + x) * 2,
      );
  await page.locator("#terrain-file").setInputFiles({
    name: "N28E077.hgt",
    mimeType: "application/octet-stream",
    buffer: hgt,
  });
  for (const k of ["hillshade", "contours", "elevation"])
    await page.locator("#prepare-" + k).check();
  await page.locator("#prepare-terrain").click();
  await page.waitForFunction(() =>
    document
      .querySelector("#terrain-status")
      .textContent.includes("Prepared and saved"),
  );
  await page.locator('[data-close="map-settings"]').click();
  await page.locator("#open-layers").click();
  const unchanged = await page.evaluate(() =>
    JSON.stringify([viewport, selectedParty]),
  );
  for (const k of ["hillshade", "contours", "elevation"]) {
    assert.equal(await page.locator("#layer-" + k).isEnabled(), true);
    assert.equal(await page.locator("#layer-" + k).isChecked(), false);
    await page.locator("#layer-" + k).check();
  }
  assert.equal(
    await page.evaluate(() => JSON.stringify([viewport, selectedParty])),
    unchanged,
    "layer changes must preserve view and party",
  );
  assert.equal(await page.locator('[data-layer="hillshade"]').count(), 1);
  assert.equal(
    await page.evaluate(() => {
      const image = document.querySelector('[data-layer="hillshade"]'),
        root = document.querySelector("#tracks"),
        [x, y] = GeoMap.project(terrain.bounds[0], terrain.bounds[3]);
      const expectedX =
          (x - viewport.cx) * viewport.scale + root.clientWidth / 2,
        expectedY = root.clientHeight / 2 - (y - viewport.cy) * viewport.scale;
      return (
        Math.abs(
          Number(image.getAttribute("x")) +
            Number(image.getAttribute("width")) / terrain.columns / 2 -
            expectedX,
        ) < 1e-6 &&
        Math.abs(
          Number(image.getAttribute("y")) +
            Number(image.getAttribute("height")) / terrain.rows / 2 -
            expectedY,
        ) < 1e-6
      );
    }),
    true,
    "hillshade pixel centres align with projected DEM nodes",
  );
  assert.equal(await page.locator('[data-layer="contours"]').count(), 1);
  assert.deepEqual(
    await page
      .locator("#tracks > [data-layer]")
      .evaluateAll((nodes) => nodes.map((n) => n.dataset.layer).slice(0, 4)),
    ["hillshade", "offline-map", "contours", "tracks"],
  );
  await page.screenshot({
    path: path.join(out, "command-day-terrain-layers-fixture.png"),
    fullPage: true,
  });
  await page.locator('[data-close="layers"]').click();
  const terrainCursor = await page.evaluate(() => {
    const lon = (terrain.bounds[0] + terrain.bounds[2]) / 2,
      lat = (terrain.bounds[1] + terrain.bounds[3]) / 2;
    const [x, y] = GeoMap.project(lon, lat);
    const root = document.querySelector("#tracks"),
      rect = root.getBoundingClientRect();
    return {
      x: rect.left + root.clientWidth / 2 + (x - viewport.cx) * viewport.scale,
      y: rect.top + root.clientHeight / 2 - (y - viewport.cy) * viewport.scale,
    };
  });
  await page.mouse.move(terrainCursor.x, terrainCursor.y);
  assert.match(
    await page.locator("#hover").textContent(),
    /Estimated ground elevation ~\d+ m.*EGM96.*not GNSS altitude/,
  );
  await page.locator("#appearance").click();
  assert.equal(
    await page.locator("html").getAttribute("data-appearance"),
    "night",
  );
  for (const size of [
    { width: 1280, height: 720 },
    { width: 1440, height: 900 },
  ]) {
    await page.setViewportSize(size);
    await page.screenshot({
      path: path.join(out, `command-night-terrain-${size.width}-fixture.png`),
      fullPage: true,
    });
  }
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.locator("#open-about").click();
  assert.match(await page.locator("#about").innerText(), /Lt Suraj Singh/);
  assert.equal(
    await page.locator("#about a").getAttribute("href"),
    "mailto:surajsingh5092@gmail.com",
  );
  await page.screenshot({
    path: path.join(out, "command-night-about.png"),
    fullPage: true,
  });
  await page.keyboard.press("Escape");
  assert.equal(await page.locator("#about").evaluate((e) => e.open), false);
  assert.equal(await page.locator("#recording-mode").count(), 0);
  assert.equal(await page.getByText(/Local workspace/i).count(), 0);
  assert.equal(
    await page
      .locator("#connection")
      .evaluate((e) => e.closest(".brand") !== null),
    true,
  );
  // Providers are denied below; restart must restore the persisted DEM and visibility.
  let offlineProviderRequests = 0;
  const denyOfflineProvider = (route) => {
    offlineProviderRequests++;
    return route.abort();
  };
  await page.route("**/local/map-preview", denyOfflineProvider);
  await page.route("**/local/map-search", denyOfflineProvider);
  // Command restart + browser reload without any provider access uses the same saved map.
  command.kill("SIGINT");
  await once(command, "exit");
  await page.waitForFunction(() =>
    document
      .querySelector("#connection")
      .textContent.includes("Command Disconnected"),
  );
  assert.equal(
    await page
      .locator("#connection")
      .evaluate((e) => e.classList.contains("unavailable")),
    true,
  );
  await page.screenshot({
    path: path.join(out, "command-workspace-unavailable.png"),
    fullPage: true,
  });
  command = spawn(
    process.env.GNSS_COMMAND_BINARY,
    [
      "-db",
      path.join(work, "command.sqlite"),
      "-ingest-listen",
      phone.slice(7),
      "-dashboard-listen",
      local.slice(7),
    ],
    { stdio: "ignore" },
  );
  await wait(async () => {
    try {
      return (await fetch(local + "/local/state")).ok;
    } catch {
      return false;
    }
  });
  await page.reload();
  await page.waitForSelector('[data-layer="offline-map"]');
  await page.waitForSelector('[data-layer="hillshade"]');
  await page.waitForSelector('[data-layer="contours"]');
  assert.equal(
    await page.locator("html").getAttribute("data-appearance"),
    "night",
  );
  await page.locator("#open-layers").click();
  for (const k of ["hillshade", "contours", "elevation"]) {
    assert.equal(await page.locator("#layer-" + k).isChecked(), true);
    await page.locator("#layer-" + k).uncheck();
    await page.locator("#layer-" + k).check();
  }
  await page.keyboard.press("Escape");
  assert.equal(await page.locator("#layers").isVisible(), false);
  assert.equal(
    await page
      .locator("#open-layers")
      .evaluate((e) => e === document.activeElement),
    true,
  );
  assert.equal(
    offlineProviderRequests,
    0,
    "terrain restoration/toggles are offline",
  );
  await page.screenshot({
    path: path.join(out, "command-night-terrain-offline-restored-fixture.png"),
    fullPage: true,
  });
  await page.waitForFunction(() =>
    document
      .querySelector("#connection")
      .textContent.includes("Command Connected"),
  );
  assert.equal(
    await page
      .locator("#connection")
      .evaluate((e) => e.classList.contains("unavailable")),
    false,
  );
  assert.equal((await (await fetch(local + "/local/maps")).json()).length, 2);
  assert.equal((await state()).devices[partyIds[2]].total_m, mapDistance);
  await page.screenshot({
    path: path.join(out, "command-offline-map-restored.png"),
    fullPage: true,
  });
  await page.locator("#open-history").click();
  await page.locator("#event-filter").selectOption("operations");
  await page.waitForSelector(".history-event");
  assert.ok((await page.locator(".history-event").count()) > 0);
  await page.screenshot({
    path: path.join(out, "command-operations-history.png"),
    fullPage: true,
  });
  await page.locator('[data-close="event-history"]').click();
  await page.locator("#open-map").click();
  await page.locator("#map-file").setInputFiles({
    name: "bad.json",
    mimeType: "application/json",
    buffer: Buffer.from('{"type":"Point","coordinates":[999,999]}'),
  });
  await page.waitForFunction(() =>
    document.querySelector("#map-warning").textContent.includes("Invalid map"),
  );
  assert.equal(await page.locator("#map-mode").inputValue(), "blank");
  await page.screenshot({
    path: path.join(out, "command-invalid-map.png"),
    fullPage: true,
  });
  await page.locator('[data-close="map-settings"]').click();
  await page.locator("#open-quality").click();
  await page.locator('[name="maximum_accuracy_m"]').fill("50");
  await page.locator('[name="enabled_maximum_speed_mps"]').uncheck();
  await page.getByRole("button", { name: "Apply quality settings" }).click();
  await wait(async () => (await state()).policy.maximum_accuracy_m === 50);
  assert.equal((await state()).policy.enabled.maximum_speed_mps, false);
  await page.locator('[data-close="quality"]').click();
  if (
    (await page.locator("#toggle-details").getAttribute("aria-expanded")) ===
    "false"
  )
    await page.locator("#toggle-details").click();
  await page.locator("#selected-detail .override input").fill("15");
  await page.locator("#selected-detail .override button").first().click();
  await wait(
    async () =>
      (await state()).devices[partyIds[2]].desired_config
        .reporting_interval_override_s === 15,
  );
  await page.locator("#selected-detail .override button").last().click();
  await wait(
    async () =>
      (await state()).devices[partyIds[2]].desired_config
        .reporting_interval_override_s === null,
  );
  await page
    .getByRole("checkbox", { name: "Show track for Alpha", exact: true })
    .uncheck();
  assert.equal(
    await page.locator(`[data-live-device="${partyIds[0]}"]`).count(),
    0,
  );
  await page.locator("#stop").click();
  await wait(async () => !(await state()).recording.active);
  await page.locator("#resume").click();
  await wait(async () => (await state()).recording.active);
  page.once("dialog", (d) => d.dismiss());
  await page.locator("#clear").click();
  assert.ok((await state()).recording, "Canceled Clear must retain recording");
  page.on("dialog", (d) => d.accept());
  await page.locator("#clear").click();
  await wait(async () => !(await state()).recording);
  await page.locator("#start").click();
  assert.equal(await page.locator("#current-mode").isChecked(), true);
  await page.locator("#confirm-recording").click();
  await wait(async () => (await state()).recording?.mode === "from_now");
  const sos = JSON.parse(
    fs.readFileSync(
      path.join(__dirname, "../../protocol/fixtures/sos-no-fix.json"),
      "utf8",
    ),
  );
  sos.device_id = partyIds[2];
  sos.party = { id: "Charlie", name: "East team" };
  sos.message_id = randomUUID();
  sos.sequence = 100;
  sos.sos.event_id = sos.message_id;
  sos.sos.triggered_at = new Date(Date.now() - 600000).toISOString();
  sos.captured_at = sos.sos.triggered_at;
  // Delayed SOS is ingested after audio is enabled. Count oscillator starts in the real AudioContext.
  await page.evaluate(() => {
    window.__tones = 0;
    const original = AudioContext.prototype.createOscillator;
    AudioContext.prototype.createOscillator = function () {
      window.__tones++;
      return original.call(this);
    };
  });
  await page.locator("#sos-audio").click();
  await send(sos);
  await page.waitForSelector("#sos-toast:not([hidden])");
  await page.locator("#sos-toast-view").click();
  await page.waitForSelector(".sos-alert");
  assert.ok((await page.evaluate(() => window.__tones)) > 0);
  await wait(async () => (await page.evaluate(() => window.__tones)) >= 2);
  assert.ok(
    (await page.evaluate(() => window.__tones)) >= 2,
    "delayed SOS must sound again while pending",
  );
  await page.screenshot({
    path: path.join(out, "command-sos-delayed.png"),
    fullPage: true,
  });
  await page.locator(".sos-alert button").first().click();
  await wait(
    async () => !!(await state()).sos_alerts[0].operator_acknowledged_at,
  );
  await page.locator('[data-close="sos-panel"]').click();
  const afterACK = await page.evaluate(() => window.__tones);
  await page.waitForTimeout(1600);
  assert.equal(
    await page.evaluate(() => window.__tones),
    afterACK,
    "last ACK immediately stops alarm cadence",
  );
  const later = [101, 102].map((sequence) => {
    const a = structuredClone(sos);
    a.message_id = a.sos.event_id = randomUUID();
    a.sequence = sequence;
    if (sequence === 102) {
      const withFix = JSON.parse(
        fs.readFileSync(
          path.join(__dirname, "../../protocol/fixtures/sos-fix.json"),
          "utf8",
        ),
      );
      a.fix = withFix.fix;
      a.health = withFix.health;
      a.captured_at =
        a.sos.triggered_at =
        a.fix.observed_at =
          new Date().toISOString();
    }
    return a;
  });
  for (const a of later) await send(a);
  await page.waitForFunction(
    () => document.querySelector("#sos-count").textContent === "2",
  );
  await page.locator("#sos-toast-dismiss").click();
  const dismissed = await page.evaluate(() => window.__tones);
  await page.waitForTimeout(2100);
  assert.ok(
    (await page.evaluate(() => window.__tones)) >= dismissed + 2,
    "toast dismissal does not silence emergencies",
  );
  await page.locator("#open-sos").click();
  assert.equal(await page.locator(".sos-alert").count(), 2);
  await page
    .locator(".sos-alert button:not(:disabled)")
    .filter({ hasText: "Locate event observation" })
    .click();
  await page.waitForSelector('[data-layer="located-sos"]');
  assert.equal(
    await page.locator('[data-layer="located-sos"] text').textContent(),
    "SOS event · Charlie",
  );
  assert.match(
    await page.locator("#hover").innerText(),
    /not necessarily a current position/,
  );
  await page.screenshot({
    path: path.join(out, "command-sos-located-observation.png"),
    fullPage: true,
  });
  await page.locator("#open-sos").click();
  await page.screenshot({
    path: path.join(out, "command-multiple-sos.png"),
    fullPage: true,
  });
  await page.locator(".sos-alert").first().locator("button").first().click();
  await page.waitForFunction(
    () => document.querySelector("#sos-count").textContent === "1",
  );
  const partial = await page.evaluate(() => window.__tones);
  await page.waitForTimeout(1100);
  assert.ok(
    (await page.evaluate(() => window.__tones)) > partial,
    "partial ACK keeps shared alarm active",
  );
  await page.locator(".sos-alert button").first().click();
  await page.waitForFunction(
    () => document.querySelector("#sos-count").textContent === "0",
  );
  await page.locator('[data-close="sos-panel"]').click();
  await page.locator("#open-history").click();
  await page.locator("#event-filter").selectOption("sos");
  await page.waitForFunction(
    () => document.querySelectorAll(".history-event").length === 3,
  );
  await page.locator(".history-event summary").first().click();
  await page.locator(".history-event summary").first().focus();
  await page.waitForTimeout(2200);
  assert.equal(
    await page.locator(".history-event").first().getAttribute("open"),
    "",
    "polling preserves open event evidence",
  );
  assert.equal(
    await page
      .locator(".history-event summary")
      .first()
      .evaluate((e) => e === document.activeElement),
    true,
    "polling preserves history keyboard focus",
  );
  assert.match(
    await page.locator("#history-events").innerText(),
    /Acknowledged/,
  );
  await page.screenshot({
    path: path.join(out, "command-sos-event-history.png"),
    fullPage: true,
  });
  await page.locator('[data-close="event-history"]').click();
  await page.waitForTimeout(32000);
  for (let i = 0; i < 4; i++) {
    const m = structuredClone(latestMessages[i]);
    m.message_id = randomUUID();
    m.sequence = 200 + i;
    m.captured_at = new Date().toISOString();
    m.fix.observed_at = m.captured_at;
    m.observation.observation_id = m.message_id;
    m.observation.observation_sequence = 4;
    m.observation.measurement_elapsed_ms = 4000;
    m.fix.latitude += 0.00015;
    delete m.history_progress;
    await send(m);
  }
  await wait(
    async () =>
      (await state()).devices[partyIds[4]].contact_condition === "contact_lost",
  );
  await page.reload();
  await page.waitForFunction(() =>
    document.querySelector("#devices").textContent.includes("Offline"),
  );
  await page.screenshot({
    path: path.join(out, "command-mixed-online-offline-stale-queue.png"),
    fullPage: true,
  });
  const violations = [];
  for (const dialog of [
    null,
    "quality",
    "map-settings",
    "event-history",
    "sos-panel",
    "about",
    "layers",
  ]) {
    if (dialog) {
      await page
        .locator(
          {
            quality: "#open-quality",
            "map-settings": "#open-map",
            "event-history": "#open-history",
            "sos-panel": "#open-sos",
            about: "#open-about",
            layers: "#open-layers",
          }[dialog],
        )
        .click();
      await page.screenshot({
        path: path.join(out, `command-${dialog}-dialog.png`),
        fullPage: true,
      });
    }
    const result = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
      .analyze();
    violations.push(
      ...result.violations.map((v) => ({
        screen: dialog || "workspace",
        ...v,
      })),
    );
    if (dialog) await page.locator(`[data-close="${dialog}"]`).click();
  }
  fs.writeFileSync(
    path.join(out, "command-accessibility.json"),
    JSON.stringify(violations, null, 2),
  );
  assert.deepEqual(
    violations.map((v) => ({
      id: v.id,
      nodes: v.nodes.map((n) => n.target),
    })),
    [],
  );
  assert.deepEqual(errors, []);
  console.log(
    "PASS: real API recording modes/lifecycle, quality switches/reprojection, reporting overrides, raw/live/history, pan/zoom/fit/focus, five parties/backlog, offline/invalid maps, responsive rendering, delayed SOS audio/ACK, axe accessibility. Screenshots: " +
      out,
  );
})()
  .catch((e) => {
    console.error(e);
    process.exitCode = 1;
  })
  .finally(async () => {
    if (browser) await browser.close();
    if (command && command.exitCode === null) {
      command.kill("SIGINT");
      await once(command, "exit");
    }
    fs.rmSync(work, { recursive: true, force: true });
  });
