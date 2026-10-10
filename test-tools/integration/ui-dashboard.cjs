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
  await page.locator("#recording-mode").selectOption("from_now");
  await page.locator("#start").click();
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
  await post("recording", { action: "start", mode: "session_beginning" });
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
  await page.screenshot({
    path: path.join(out, "command-1280-five-parties-recording.png"),
    fullPage: true,
  });
  for (const size of [
    [1440, 900],
    [760, 900],
    [430, 900],
  ]) {
    await page.setViewportSize({ width: size[0], height: size[1] });
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
      false,
      "horizontal overflow",
    );
    await page.screenshot({
      path: path.join(out, `command-${size[0]}-five-parties.png`),
      fullPage: true,
    });
  }
  await page.setViewportSize({ width: 1440, height: 900 });
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
  page.on("dialog", (d) => d.accept());
  await page.locator("#clear").click();
  await wait(async () => !(await state()).recording);
  await page.locator("#recording-mode").selectOption("from_now");
  await page.locator("#start").click();
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
  await send(sos);
  await page.waitForSelector(".sos-alert");
  await page.locator("#sos-audio").click();
  assert.ok((await page.evaluate(() => window.__tones)) > 0);
  await page.evaluate(() => (lastSound = 0));
  await page.waitForTimeout(2200);
  assert.ok(
    (await page.evaluate(() => window.__tones)) >= 2,
    "delayed SOS must sound again while pending",
  );
  await page.screenshot({
    path: path.join(out, "command-sos-delayed.png"),
    fullPage: true,
  });
  await page.locator(".sos-alert button").click();
  await wait(
    async () => !!(await state()).sos_alerts[0].operator_acknowledged_at,
  );
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
  const axe = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  fs.writeFileSync(
    path.join(out, "command-accessibility.json"),
    JSON.stringify(axe.violations, null, 2),
  );
  assert.deepEqual(
    axe.violations.map((v) => ({
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
