// Optional browser acceptance for the real embedded Command dashboard. No product
// dependencies: Node + an externally supplied Playwright installation are test-only.
"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const net = require("node:net");
const { spawn } = require("node:child_process");
const { once } = require("node:events");
const { chromium } = require(
  process.env.GNSS_PLAYWRIGHT_MODULE || "playwright",
);
const binary = process.env.GNSS_COMMAND_BINARY;
if (!binary) throw Error("Set GNSS_COMMAND_BINARY to a built Command binary");
const work = fs.mkdtempSync(path.join(os.tmpdir(), "sos-dashboard-"));
let command, browser;
async function port() {
  const server = net.createServer();
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const p = server.address().port;
  await new Promise((resolve) => server.close(resolve));
  return p;
}
async function waitFor(check, reason) {
  for (let i = 0; i < 100; i++) {
    if (await check()) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw Error(reason);
}
async function stop() {
  if (command && command.exitCode === null) {
    command.kill("SIGINT");
    await once(command, "exit");
  }
}
(async () => {
  const phone = "http://127.0.0.1:" + (await port());
  const local = "http://127.0.0.1:" + (await port());
  async function start() {
    command = spawn(
      binary,
      [
        "-db",
        path.join(work, "command.sqlite"),
        "-ingest-listen",
        phone.slice(7),
        "-dashboard-listen",
        local.slice(7),
      ],
      { stdio: ["ignore", "ignore", "pipe"] },
    );
    command.stderr.on("data", () => {});
    await waitFor(async () => {
      try {
        return (await fetch(local + "/local/state")).ok;
      } catch {
        return false;
      }
    }, "Command startup failed");
  }
  const fixture = JSON.parse(
    fs.readFileSync(
      path.join(__dirname, "../../protocol/fixtures/sos-no-fix.json"),
      "utf8",
    ),
  );
  async function send(m) {
    const response = await fetch(phone + "/api/v1/messages", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(m),
    });
    assert.equal(response.status, 200);
    return response.json();
  }
  await start();
  const first = await send(fixture);
  browser = await chromium.launch({
    executablePath: process.env.GNSS_CHROMIUM || undefined,
    headless: true,
    args: ["--no-sandbox", "--autoplay-policy=user-gesture-required"],
  });
  const page = await browser.newPage({
    viewport: { width: 1280, height: 900 },
  });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(local);
  await page.waitForSelector(".sos-alert");
  assert.match(await page.locator("#sos-audio-status").innerText(), /blocked/i);
  assert.match(
    await page.locator(".sos-alert").innerText(),
    /Coordinates unavailable/,
  );
  const button = page.locator(".sos-alert button");
  await button.focus();
  await page.waitForTimeout(2200);
  assert.equal(
    await button.evaluate((el) => el === document.activeElement),
    true,
  );
  await page.keyboard.press("Enter");
  await page.waitForSelector(".sos-alert.acknowledged");
  const state = await (await fetch(local + "/local/state")).json();
  const ack = state.sos_alerts[0].operator_acknowledged_at;
  assert.ok(ack);
  const duplicate = await send(fixture);
  assert.equal(duplicate.result, "duplicate");
  assert.equal(duplicate.received_at, first.received_at);
  const second = structuredClone(fixture);
  second.message_id = second.sos.event_id =
    "99999999-9999-4999-8999-999999999999";
  second.sequence++;
  await send(second);
  await page.reload();
  await page.waitForSelector(".sos-alert:not(.acknowledged)");
  assert.equal(await page.locator(".sos-alert").count(), 2);
  assert.equal(
    await page.locator(".sos-alert button:not(:disabled)").count(),
    1,
  );
  assert.equal(
    await page
      .locator(".sos-alert")
      .first()
      .evaluate((el) => el.classList.contains("acknowledged")),
    false,
  );
  await page.locator("#sos-audio").click();
  assert.match(
    await page.locator("#sos-audio-status").innerText(),
    /enabled|blocked/i,
  );
  await stop();
  await start();
  await page.reload();
  await page.waitForSelector(".sos-alert:not(.acknowledged)");
  const restored = await (await fetch(local + "/local/state")).json();
  assert.equal(restored.sos_alerts[0].operator_acknowledged_at, ack);
  assert.equal(restored.sos_alerts[0].received_at, first.received_at);
  assert.equal(restored.sos_alerts[1].operator_acknowledged_at, null);
  for (const action of ["start", "stop", "resume", "clear"]) {
    const response = await fetch(local + "/local/recording", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action, confirmed: action === "clear" }),
    });
    assert.equal(response.status, 200);
  }
  await page.reload();
  await page.waitForSelector(".sos-alert:not(.acknowledged)");
  assert.equal(await page.locator(".sos-alert").count(), 2);
  assert.deepEqual(errors, []);
  console.log(
    "PASS: real dashboard, blocked audio status, keyboard ACK/focus, duplicate, multiple SOS, reload/restart and Recording Clear persistence. Physical speaker playback remains untested.",
  );
})()
  .catch((e) => {
    console.error(e);
    process.exitCode = 1;
  })
  .finally(async () => {
    if (browser) await browser.close();
    await stop();
    fs.rmSync(work, { recursive: true, force: true });
  });
