/* Real SQLite/server + instrumented Chromium; no pixel-diff baselines. */
"use strict";
const assert = require("node:assert/strict"),
  fs = require("node:fs"),
  os = require("node:os"),
  path = require("node:path"),
  net = require("node:net");
const { spawn } = require("node:child_process"),
  { once } = require("node:events"),
  { randomUUID } = require("node:crypto");
const { chromium } = require(
  process.env.GNSS_PLAYWRIGHT_MODULE || "playwright",
);
const work = fs.mkdtempSync(path.join(os.tmpdir(), "gnss-recording-")),
  out = process.env.GNSS_SCREENSHOT_DIR || path.join(work, "screenshots");
fs.mkdirSync(out, { recursive: true });
let command, browser, local, phone, page;
const report = {
  source: process.env.SOURCE_REVISION || "working-tree",
  os_scanout_verified: false,
  human_attention_verified: false,
};
async function port() {
  const s = net.createServer();
  s.listen(0, "127.0.0.1");
  await once(s, "listening");
  const p = s.address().port;
  await new Promise((r) => s.close(r));
  return p;
}
async function wait(fn) {
  for (let i = 0; i < 200; i++) {
    try {
      if (await fn()) return;
    } catch {}
    await new Promise((r) => setTimeout(r, 100));
  }
  throw Error("timed out");
}
async function state() {
  return (await fetch(local + "/local/state")).json();
}
async function start() {
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
  await wait(async () => (await fetch(local + "/local/state")).ok);
}
async function saved() {
  return (await fetch(local + "/local/presentation/saved")).json();
}
async function captureCount() {
  return page.evaluate(() =>
    Number(
      document
        .querySelector("#capture-health")
        .textContent.match(/revision (\d+)/)?.[1] || 0,
    ),
  );
}
(async () => {
  local = `http://127.0.0.1:${await port()}`;
  phone = `http://127.0.0.1:${await port()}`;
  await start();
  browser = await chromium.launch({
    headless: true,
    ...(process.env.GNSS_CHROMIUM_PATH
      ? { executablePath: process.env.GNSS_CHROMIUM_PATH }
      : {}),
  });
  page = await browser.newPage({ viewport: { width: 1520, height: 950 } });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(local);
  await wait(
    async () =>
      await page.evaluate(
        () =>
          !document.querySelector("#browser-occupied").open &&
          !!window.recordingLiveState?.() &&
          !!window.RecordingCapture.headers()["X-Presentation-Token"],
      ),
  );
  const base = JSON.parse(
      fs.readFileSync(
        path.join(__dirname, "../../protocol/fixtures/location-normal.json"),
      ),
    ),
    ids = [];
  for (let i = 0; i < 5; i++) {
    const m = structuredClone(base);
    m.device_id = randomUUID();
    ids.push(m.device_id);
    m.message_id = randomUUID();
    m.party.id = ["Alpha", "Bravo", "Charlie", "Delta", "Echo"][i];
    m.captured_at = m.fix.observed_at = new Date().toISOString();
    m.fix.longitude += i * 0.001;
    const r = await fetch(phone + "/api/v1/messages", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(m),
    });
    assert.equal(r.status, 200, await r.text());
  }
  await page.evaluate(() => poll());
  // Delay the actual browser animation-frame callbacks, not the server response.
  await page.evaluate(() => {
    const original = requestAnimationFrame;
    window.requestAnimationFrame = (fn) =>
      original((t) => setTimeout(() => fn(t), 250));
  });
  const startAt = Date.now();
  await page.locator("#start").click();
  await page.locator("#confirm-recording").click();
  await wait(async () => (await state()).recording?.active);
  assert.equal(
    await captureCount(),
    0,
    "receipt alone must not establish confirmed display",
  );
  await wait(async () => (await captureCount()) > 0);
  report.first_confirmation_delay_ms = Date.now() - startAt;
  assert(report.first_confirmation_delay_ms >= 450);
  // Delay actual synchronous map application after a newly durable receipt.
  // While the renderer is blocked, the journal must still identify the old view.
  let paintStarted;
  const painting = new Promise((resolve) => (paintStarted = resolve));
  await page.exposeFunction("recordingPaintStarted", () => paintStarted());
  const changed = structuredClone(base);
  changed.device_id = ids[0];
  changed.message_id = randomUUID();
  changed.sequence = 2;
  changed.captured_at = changed.fix.observed_at = new Date().toISOString();
  changed.fix.longitude += 0.02;
  const received = await fetch(phone + "/api/v1/messages", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(changed),
  });
  assert.equal(received.status, 200);
  const target = (await state()).receipt_version;
  const receiptAt = Date.now();
  await page.evaluate(() => {
    const original = drawMap;
    window.delayNextPaint = true;
    drawMap = () => {
      if (window.delayNextPaint) {
        window.delayNextPaint = false;
        window.recordingPaintStarted();
        const until = performance.now() + 750;
        while (performance.now() < until) {}
        original();
        window.actualMapAppliedAt = new Date().toISOString();
      } else original();
    };
    pollLive();
  });
  await painting;
  const during = await (
    await fetch(local + "/local/presentation/status")
  ).json();
  assert(
    during.displayed_receipt_version < target,
    "durable receipt must not substitute for delayed map application",
  );
  await wait(async () => {
    const s = await (await fetch(local + "/local/presentation/status")).json();
    return s.displayed_receipt_version >= target;
  });
  const after = await (
    await fetch(local + "/local/presentation/status")
  ).json();
  const appliedAt = await page.evaluate(() => window.actualMapAppliedAt);
  assert(Date.parse(after.presented_at) >= Date.parse(appliedAt));
  assert(Date.parse(appliedAt) >= receiptAt + 650);
  report.delayed_actual_map_application_ms = Date.parse(appliedAt) - receiptAt;
  report.receipt_did_not_establish_delayed_display = true;
  await page.screenshot({
    path: path.join(out, "issue27-day-recording.png"),
    fullPage: true,
  });
  const second = await browser.newPage();
  await second.goto(local);
  await second.locator("#browser-occupied").waitFor({ state: "visible" });
  report.secondary_denied = true;
  await second.close();
  await page.locator("#save-recording").click();
  await page.locator("#snapshot-name").fill("3 of 5 frozen snapshot");
  const checks = page.locator("#snapshot-parties input");
  await checks.nth(3).uncheck();
  await checks.nth(4).uncheck();
  await page.screenshot({
    path: path.join(out, "issue27-day-save.png"),
    fullPage: true,
  });
  await page.locator("#snapshot-submit").click();
  await wait(async () => (await saved()).length === 1);
  assert((await state()).recording.active);
  const first = (await saved())[0];
  assert.equal(first.total_parties, 5);
  assert.equal(first.included.length, 3);
  assert.equal(first.operator_selected_subset, true);
  const verify = await (
    await fetch(local + "/local/presentation/saved?id=" + first.id)
  ).json();
  assert.equal(verify.stream_integrity_valid, true);
  for (let i = 0; i < 2; i++) {
    await page.locator("#pause").click();
    await wait(async () => (await state()).recording.status === "paused");
    await page.locator("#resume").click();
    await wait(async () => (await state()).recording.active);
  }
  await page.locator("#pause").click();
  await wait(async () => (await state()).recording.status === "paused");
  await page.locator("#appearance").click();
  await page.screenshot({
    path: path.join(out, "issue27-night-paused.png"),
    fullPage: true,
  });
  await page.locator("#save-recording").click();
  await page.locator("#snapshot-name").fill("Paused snapshot");
  await page.screenshot({
    path: path.join(out, "issue27-night-save.png"),
    fullPage: true,
  });
  await page.locator("#snapshot-submit").click();
  await wait(async () => (await saved()).length === 2);
  // A refresh must not turn deliberate Pause into active recording.
  await page.reload();
  await wait(async () => await page.locator("#resume").isVisible());
  assert.equal((await state()).recording.status, "paused");
  await page.locator("#resume").click();
  await wait(async () => (await state()).recording.active);
  await page.close();
  page = await browser.newPage();
  await page.goto(local);
  await wait(
    async () => !(await page.locator("#browser-occupied").isVisible()),
  );
  await wait(async () => (await captureCount()) > 0);
  report.browser_recovery = true;
  command.kill("SIGKILL");
  await once(command, "exit");
  await start();
  await page.reload();
  await wait(async () => (await captureCount()) > 0);
  assert((await state()).recording.active);
  report.command_crash_recovery = true;
  await page.locator("#stop").click();
  await page.getByLabel("Close Save snapshot", { exact: true }).click();
  assert.equal((await state()).recording.status, "stopped");
  await page.reload();
  await page.locator("#stopped-choice").waitFor({ state: "visible" });
  assert.equal(await page.locator("#resume").isVisible(), false);
  page.once("dialog", (d) => d.accept());
  await page.locator("#discard-recording").click();
  await wait(async () => !(await state()).recording);
  assert.deepEqual(
    (await saved()).find((s) => s.id === first.id),
    first,
  );
  report.snapshot_immutable = true;
  assert.deepEqual(errors, []);
  report.passed = true;
})()
  .catch(async (e) => {
    report.error = e.stack;
    if (page) {
      report.health = await page
        .locator("#capture-health")
        .textContent()
        .catch(() => "");
      await page
        .screenshot({ path: path.join(out, "failure.png") })
        .catch(() => {});
    }
    process.exitCode = 1;
  })
  .finally(async () => {
    fs.writeFileSync(
      path.join(out, "issue27-recording-report.json"),
      JSON.stringify(report, null, 2),
    );
    console.log(JSON.stringify(report, null, 2));
    if (browser) await browser.close();
    if (command) {
      command.kill();
    }
    console.log("Evidence: " + out);
  });
