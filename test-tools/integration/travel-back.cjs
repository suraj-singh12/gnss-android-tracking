/* Production Command/SQLite/Chromium regression for capture-time reconstruction. */
const { chromium } = require(
  process.env.GNSS_PLAYWRIGHT_MODULE || "playwright",
);
const { spawn } = require("node:child_process");
const fs = require("node:fs"),
  os = require("node:os"),
  path = require("node:path"),
  assert = require("node:assert/strict");
const { randomUUID } = require("node:crypto");
const work = fs.mkdtempSync(path.join(os.tmpdir(), "travel34-"));
const out = process.env.GNSS_SCREENSHOT_DIR || work;
fs.mkdirSync(out, { recursive: true });
let command, browser, page;
const report = { physical_acceptance: false };
(async () => {
  command = spawn(
    process.env.GNSS_COMMAND_BINARY,
    [
      "-db",
      path.join(work, "command.sqlite"),
      "-ingest-listen",
      "127.0.0.1:18741",
      "-dashboard-listen",
      "127.0.0.1:18742",
    ],
    { stdio: "ignore" },
  );
  const local = "http://127.0.0.1:18742",
    phone = "http://127.0.0.1:18741";
  for (let i = 0; i < 100; i++) {
    try {
      if ((await fetch(local + "/local/state")).ok) break;
    } catch {}
    await new Promise((r) => setTimeout(r, 100));
  }
  const fixture = JSON.parse(
    fs.readFileSync(
      path.join(__dirname, "../../protocol/fixtures/location-normal.json"),
    ),
  );
  const ids = Array.from({ length: 21 }, () => randomUUID());
  const capture = Date.now() - 60000;
  async function ingest(i, n) {
    const m = structuredClone(fixture);
    m.device_id = ids[i];
    m.message_id = randomUUID();
    m.sequence = n;
    m.party.id = `Party ${i + 1}`;
    m.captured_at = m.fix.observed_at = new Date(
      capture + n * 1000,
    ).toISOString();
    m.fix.longitude += i * 0.0001 + n * 0.00005;
    const r = await fetch(phone + "/api/v1/messages", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(m),
    });
    assert.equal(r.status, 200, await r.text());
  }
  for (let i = 0; i < 20; i++) for (const n of [1, 3]) await ingest(i, n);
  browser = await chromium.launch({
    executablePath: process.env.GNSS_CHROMIUM,
    headless: true,
    args: ["--no-sandbox"],
  });
  page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  let releaseState;
  const stateReady = new Promise((resolve) => (releaseState = resolve));
  const stateRoute = /\/local\/(?:live|state)(?:\?|$)/;
  await page.route(stateRoute, async (route) => {
    await stateReady;
    await route.continue();
  });
  await page.goto(local);
  assert(
    await page.locator("#mode-travel").isDisabled(),
    "unknown startup recording state locks history",
  );
  const waiting = await page.evaluate(async () => {
    try {
      await window.HistoricalPlayback.open({ id: "unavailable" });
      return "";
    } catch (e) {
      return e.message;
    }
  });
  assert.match(waiting, /Waiting for Command/);
  releaseState();
  await page.waitForFunction(() => !!window.recordingLiveState?.());
  await page.unroute(stateRoute);
  const unverified = await page.evaluate(async () => {
    const original = window.recordingLiveState;
    window.recordingLiveState = () => ({
      ...original(),
      recording: { active: false, status: "active" },
    });
    try {
      await window.HistoricalPlayback.open({ id: "unavailable" });
      return "";
    } catch (e) {
      return e.message;
    } finally {
      window.recordingLiveState = original;
    }
  });
  assert.match(unverified, /Stop or discard/);

  await page.locator("#mode-travel").click();
  await page.waitForFunction(() => window.HistoricalPlayback.active);
  assert(!(await page.locator("#follow-label").isVisible()));
  assert.match(
    await page.locator("#playback-seek").getAttribute("aria-label"),
    /GNSS capture/,
  );
  assert.equal(await page.locator(".historical-party-name").count(), 20);
  assert.equal(
    await page.locator("[data-historical-party] circle").count(),
    0,
    "metadata must not expose future positions",
  );
  await page.locator("#playback-last").click();
  assert.equal(await page.locator(".historical-party-name").count(), 20);
  const before = await page.locator("#tracks").innerHTML();
  await ingest(0, 2);
  await ingest(20, 1);
  await ingest(20, 3);
  await page.evaluate(() => poll());
  assert.equal(await page.locator("#tracks").innerHTML(), before);
  assert.match(
    await page.locator("#travel-pending").textContent(),
    /New stored observations/,
  );
  await page.locator("#travel-settings > summary").click();
  await page.locator("#travel-refresh").click();
  await page.waitForTimeout(200);
  assert.equal(await page.locator(".historical-party-name").count(), 21);
  const position = await page.evaluate(
    () => window.HistoricalPlayback.position,
  );
  const sos = JSON.parse(
    fs.readFileSync(
      path.join(__dirname, "../../protocol/fixtures/sos-fix.json"),
    ),
  );
  sos.device_id = ids[0];
  sos.message_id = randomUUID();
  sos.sequence = 901;
  sos.sos.event_id = sos.message_id;
  sos.sos.triggered_at = sos.captured_at = new Date().toISOString();
  sos.fix.observed_at = sos.captured_at;
  const response = await fetch(phone + "/api/v1/messages", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(sos),
  });
  assert.equal(response.status, 200, await response.text());
  await page.evaluate(() => poll());
  await page.locator("#open-sos").click();
  await page.getByRole("button", { name: "Locate" }).click();
  assert.equal(
    await page.evaluate(() => window.HistoricalPlayback.active),
    false,
  );
  // Starting a new recording while temporarily in LIVE also locks Go Back.
  await page.locator("#start").click();
  await page.locator("#confirm-recording").click();
  await page.waitForFunction(
    () => window.recordingLiveState().recording?.active,
  );
  assert(await page.locator("#historical-return").isDisabled());
  await page.evaluate(() =>
    document.querySelector("#historical-return").onclick(),
  );
  assert.equal(
    await page.evaluate(() => window.HistoricalPlayback.active),
    false,
  );
  await page.locator("#pause").click();
  await page.waitForFunction(
    () => window.recordingLiveState().recording?.status === "paused",
  );
  assert(await page.locator("#historical-return").isDisabled());
  await page.locator("#stop").click();
  await page.locator('[data-close="save-snapshot"]').first().click();
  page.once("dialog", (d) => d.accept());
  await page.locator("#discard-recording").click();
  await page.waitForFunction(() => !window.recordingLiveState().recording);
  await page.locator("#historical-return").click();
  assert.equal(
    await page.evaluate(() => window.HistoricalPlayback.position),
    position,
  );
  assert.equal(
    await page.locator("#playback-play").getAttribute("aria-label"),
    "Play",
  );
  await page.locator("#travel-settings > summary").click();
  await page.locator("#sos-toast-dismiss").click();
  await page.locator(".historical-party-name").first().click();
  assert(
    (await page.locator("[data-historical-party] circle").count()) > 0,
    "qualified observed positions render",
  );
  for (const [width, height] of [
    [390, 844],
    [960, 720],
    [1440, 900],
  ]) {
    await page.setViewportSize({ width, height });
    for (const theme of ["day", "night"]) {
      await page.evaluate(
        (t) => (document.documentElement.dataset.appearance = t),
        theme,
      );
      await page.screenshot({
        path: path.join(out, `travel-${theme}-${width}.png`),
      });
    }
  }
  await page.locator("#mode-live").click();
  assert.equal(
    await page.evaluate(() => window.HistoricalPlayback.active),
    false,
  );
  await page.locator("#mode-recording").click();
  await page.locator("#confirm-recording").click();
  await page.waitForFunction(
    () => window.recordingLiveState().recording?.active,
  );
  assert(await page.locator("#mode-travel").isDisabled());
  for (const [width, height] of [
    [390, 844],
    [960, 720],
    [1440, 900],
  ]) {
    await page.setViewportSize({ width, height });
    for (const theme of ["day", "night"]) {
      await page.evaluate(
        (t) => (document.documentElement.dataset.appearance = t),
        theme,
      );
      await page.screenshot({
        path: path.join(out, `recording-${theme}-${width}.png`),
      });
    }
  }
  const recordingId = await page.evaluate(
    () => window.recordingLiveState().recording.recording_id,
  );
  await page.locator("#mode-live").click();
  assert.equal(
    await page.evaluate(() => window.recordingLiveState().recording.active),
    true,
  );
  await page.locator("#mode-recording").click();
  assert.equal(
    await page.evaluate(
      () => window.recordingLiveState().recording.recording_id,
    ),
    recordingId,
  );
  assert.deepEqual(errors, []);
  report.passed = true;
  report.parties = 21;
  report.late_join = true;
  report.manual_refresh = true;
  report.pause_return = true;
})()
  .catch(async (e) => {
    report.error = e.stack;
    process.exitCode = 1;
    if (page)
      await page
        .screenshot({ path: path.join(out, "travel-failure.png") })
        .catch(() => {});
  })
  .finally(async () => {
    fs.writeFileSync(
      path.join(out, "travel-report.json"),
      JSON.stringify(report, null, 2),
    );
    console.log(report);
    if (browser) await browser.close();
    if (command) command.kill();
  });
