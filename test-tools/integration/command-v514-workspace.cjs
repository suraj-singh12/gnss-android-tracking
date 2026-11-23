/* Approved Command V5.14 workspace regression (Issue #66).
   Real Command process, SQLite and Protocol v1 ingestion; Chromium drives the
   production UI. Fixture data is synthetic; no state API is mocked except the
   explicit provider-failure step, which aborts the browser's state requests. */
"use strict";
const { menuAction } = require("./menu-actions.cjs");
const assert = require("node:assert/strict"),
  fs = require("node:fs"),
  os = require("node:os"),
  path = require("node:path"),
  net = require("node:net");
const { spawn } = require("node:child_process"),
  { once } = require("node:events"),
  { randomUUID } = require("node:crypto");
const { chromium } = require(process.env.GNSS_PLAYWRIGHT_MODULE || "playwright");
const { default: AxeBuilder } = require(process.env.GNSS_AXE_MODULE || "@axe-core/playwright");
const work = fs.mkdtempSync(path.join(os.tmpdir(), "gnss-v514-")),
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
async function wait(check, message = "Timed out waiting for real state") {
  for (let i = 0; i < 150; i++) {
    if (await check()) return;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw Error(message);
}
(async () => {
  const phone = "http://127.0.0.1:" + (await port()),
    local = "http://127.0.0.1:" + (await port());
  command = spawn(process.env.GNSS_COMMAND_BINARY, ["-db", path.join(work, "command.sqlite"), "-ingest-listen", phone.slice(7), "-dashboard-listen", local.slice(7)], { stdio: "ignore" });
  await wait(async () => { try { return (await fetch(local + "/local/state")).ok; } catch { return false; } });
  const state = async () => (await fetch(local + "/local/state")).json();
  const fixture = JSON.parse(fs.readFileSync(path.join(__dirname, "../../protocol/fixtures/location-native-session.json"), "utf8"));
  const parties = ["Alpha", "Bravo", "Charlie"].map((id, i) => ({ id, name: ["North team", "River team", "East team"][i], device: randomUUID(), session: randomUUID(), sequence: 0, started: new Date(Date.now() - 3700000).toISOString() }));
  async function report(party, ageMs = 0) {
    const m = structuredClone(fixture), captured = new Date(Date.now() - ageMs).toISOString();
    party.sequence++;
    m.device_id = party.device;
    m.message_id = randomUUID();
    m.party = { id: party.id, name: party.name };
    m.sequence = party.sequence;
    m.captured_at = captured;
    m.fix.observed_at = captured;
    m.fix.latitude += parties.indexOf(party) * 0.002 + party.sequence * 0.0001;
    m.fix.longitude += parties.indexOf(party) * 0.002;
    m.observation = { observation_id: m.message_id, tracking_session_id: party.session, session_started_at: party.started, observation_sequence: party.sequence, measurement_elapsed_ms: party.sequence * 1000 };
    const r = await fetch(phone + "/api/v1/messages", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(m) });
    assert.equal(r.status, 200, await r.text());
  }
  // Alpha and Bravo are fresh; Charlie is in contact but its GNSS fix is stale.
  await report(parties[0]); await report(parties[1]); await report(parties[2], 3600000);
  browser = await chromium.launch({ executablePath: process.env.GNSS_CHROMIUM || undefined, headless: true, args: ["--no-sandbox", "--autoplay-policy=user-gesture-required"] });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage(), errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(local);
  await page.waitForFunction(() => document.querySelectorAll("#devices .party-select").length === 3);
  const box = (selector) => page.locator(selector).boundingBox();
  const noOverflow = async (label) => assert.deepEqual(await page.evaluate(() => [document.documentElement.scrollWidth > innerWidth, document.documentElement.scrollHeight > innerHeight]), [false, false], label + " document overflow");

  // Workspace geometry follows the approved reference.
  assert.equal((await box("header")).height, 58);
  assert.equal((await box(".party-overview")).width, 242);
  assert.equal((await box(".party-detail")).width, 270);
  assert.equal(await page.locator("#mode-label").textContent(), "LIVE");
  assert.equal(await page.locator(".recording-bar").isVisible(), false, "LIVE without a recording has no rail");
  assert.match(await page.locator("#context-title").textContent(), /Live operations/);
  await noOverflow("1440x900");

  // Mode menu: anchored dropdown, keyboard navigation, Escape, and live refresh.
  await page.locator("#mode-button").click();
  assert(await page.locator("#mode-navigation").isVisible());
  assert.equal(await page.locator("#mode-live").getAttribute("aria-pressed"), "true");
  // The details toggle event that moves focus fires asynchronously.
  await page.waitForFunction(() => document.activeElement.id === "mode-live", null, { timeout: 2000 });
  await page.keyboard.press("ArrowDown");
  assert.equal(await page.evaluate(() => document.activeElement.id), "mode-recording");
  const menu = await box("#mode-navigation");
  assert(menu.y >= 58 && menu.y + menu.height <= 900, "mode menu stays within the workspace");
  await report(parties[0]);
  await page.waitForTimeout(2500);
  assert.equal(await page.locator("#mode-picker").evaluate((e) => e.open), true, "live refresh keeps the mode menu open");
  await page.keyboard.press("Escape");
  assert.equal(await page.locator("#mode-picker").evaluate((e) => e.open), false);
  assert.equal(await page.evaluate(() => document.activeElement.id), "mode-button");

  // Party search and filters survive live refresh and do not change selection authority.
  await page.locator("#party-search").fill("riv");
  await wait(async () => (await page.locator("#devices .device:visible").count()) === 1);
  await report(parties[1]);
  await page.waitForTimeout(2500);
  assert.equal(await page.locator("#party-search").inputValue(), "riv");
  assert.equal(await page.locator("#devices .device:visible .party-select").textContent(), "Bravo");
  await page.locator("#party-search").fill("");
  await page.locator('[data-party-filter="attention"]').click();
  await wait(async () => (await page.locator("#devices .device:visible").count()) === 1);
  assert.equal(await page.locator("#devices .device:visible .party-select").textContent(), "Charlie");
  assert.match(await page.locator("#party-filter-status").textContent(), /1 of 3 shown/);
  await page.locator('[data-party-filter="online"]').click();
  await wait(async () => (await page.locator("#devices .device:visible").count()) === 3);
  await page.locator('[data-party-filter="all"]').click();

  // Selecting a party updates the Details inspector from real state.
  await page.getByRole("button", { name: "Bravo", exact: true }).click();
  await page.waitForFunction(() => document.querySelector("#selected-detail .detail-name")?.textContent.includes("Bravo"));
  const detail = await page.locator("#selected-detail").innerText();
  assert.match(detail, /Command contact\s+Connected/);
  assert.match(await page.locator("#selected-detail .inspector-evidence").textContent(), /River team/, "team is in the evidence disclosure");
  assert.match(detail, /Observation sync/);
  assert.match(detail, /GNSS Fresh/);
  await page.getByRole("button", { name: "Charlie", exact: true }).click();
  await page.waitForFunction(() => document.querySelector("#selected-detail .detail-name")?.textContent.includes("Charlie"));
  assert.match(await page.locator("#selected-detail .inspector-fix").textContent(), /Stale/);
  assert.equal(await page.locator("#selected-detail .inspector-fix.positive").count(), 0, "stale GNSS is never shown as fresh");
  await page.locator("#selected-detail .inspector-evidence summary").click();
  assert(await page.locator("#selected-detail .inspector-evidence .status").isVisible());

  // Panel collapse and restore.
  await page.locator("#close-details").click();
  assert.equal(await page.locator(".party-detail").isVisible(), false);
  await page.locator("#toggle-details").click();
  assert(await page.locator(".party-detail").isVisible());
  await page.locator("#collapse-parties").click();
  assert.equal(await page.locator(".party-overview").isVisible(), false);
  await page.locator("#toggle-parties").click();
  assert(await page.locator(".party-overview").isVisible());

  // Day/Night persists through reload; drafts and selection persist.
  await page.locator("#appearance").click();
  assert.equal(await page.evaluate(() => document.documentElement.dataset.appearance), "night");
  assert.equal(await page.locator("#appearance").getAttribute("aria-label"), "Switch to Day appearance");
  await page.reload();
  await page.waitForFunction(() => document.querySelectorAll("#devices .party-select").length === 3);
  assert.equal(await page.evaluate(() => document.documentElement.dataset.appearance), "night");
  await page.locator("#appearance").click();

  // Responsive review: approved breakpoints, bounded menus and no overflow.
  for (const theme of ["day", "night"]) {
    await page.evaluate((t) => { localStorage.setItem("gnss-appearance", t); applyAppearance(); }, theme);
    for (const [width, height] of [[1280, 720], [1280, 800], [1440, 900], [1100, 800], [760, 800], [390, 844], [1280, 600]]) {
      await page.setViewportSize({ width, height });
      await page.waitForTimeout(250);
      await noOverflow(`${theme} ${width}x${height}`);
      assert.equal(await page.locator(".header-menu-root > summary").isVisible(), width <= 1250, `${width} utility menu`);
      if (width <= 800) assert.equal(await page.locator(".party-overview").isVisible(), false, `${width} parties drawer starts closed`);
      if (width > 800 && width <= 1190) assert.equal(await page.locator(".party-detail").isVisible(), false, `${width} details drawer starts closed`);
      for (const id of ["zoom-in", "zoom-out", "fit", "focus-party", "inspect-history", "open-layers", "open-sos", "appearance", "mode-button"]) {
        const b = await box("#" + id);
        assert(b && b.x >= 0 && b.x + b.width <= width && b.y >= 0 && b.y + b.height <= height, `${id} reachable at ${width}x${height}`);
      }
      await page.screenshot({ path: path.join(out, `v514-${theme}-${width}x${height}.png`) });
      // Dialogs fit the viewport, including their close buttons.
      for (const id of ["about", "recording-start", "save-snapshot"]) {
        const d = await page.evaluate((id) => {
          const dialog = document.getElementById(id);
          dialog.showModal();
          const r = dialog.getBoundingClientRect(), c = dialog.querySelector(".dialog-heading [data-close]").getBoundingClientRect();
          dialog.close();
          return [r.left, r.right, c.left, c.right];
        }, id);
        assert(d[0] >= 0 && d[1] <= width && d[2] >= 0 && d[3] <= width, `${id} fits ${width}x${height}: ${d}`);
      }
      await page.locator("#mode-button").click();
      const m = await box("#mode-navigation .mode-menu-item:last-child");
      assert(m.x >= 0 && m.x + m.width <= width && m.y + m.height <= height, `mode menu bounded at ${width}x${height}`);
      await page.keyboard.press("Escape");
      if (width <= 1250) {
        await page.locator(".header-menu-root > summary").click();
        const t = await box("#open-tools");
        assert(t.x >= 0 && t.x + t.width <= width && t.y + t.height <= height, `utility menu bounded at ${width}x${height}`);
        await page.screenshot({ path: path.join(out, `v514-${theme}-${width}x${height}-menu.png`) });
        await page.locator("#open-tools").click();
        const q = await box("#open-quality");
        assert(q.x >= 0 && q.x + q.width <= width && q.y + q.height <= height, `Tools tabs bounded at ${width}x${height}`);
        await page.keyboard.press("Escape");
        if (await page.locator(".header-menu-root").evaluate((e) => e.open)) await page.keyboard.press("Escape");
      }
    }
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.waitForTimeout(250);
    // Returning from drawer layout restores both desktop columns.
    assert(await page.locator(".party-overview").isVisible(), "Parties column restored at 1440");
    assert(await page.locator(".party-detail").isVisible(), "Details column restored at 1440");
  }
  await page.evaluate(() => { localStorage.setItem("gnss-appearance", "day"); applyAppearance(); });
  await page.evaluate(() => document.querySelector("main").classList.remove("details-closed", "parties-closed"));

  // Record mode: opening Start must not mutate; Cancel cancels; the rail appears.
  await menuAction(page, "mode-recording");
  await page.waitForSelector("#recording-start[open]");
  assert.equal((await state()).recording, null, "opening Start must not mutate recording");
  const actionsClear = (dialog) => page.evaluate((dialog) => {
    const [cancel, ...rest] = [...document.querySelectorAll(`#${dialog} .controls > button`)].filter((b) => b.getClientRects().length);
    const c = cancel.getBoundingClientRect();
    return cancel.scrollWidth <= cancel.clientWidth && rest.every((b) => { const r = b.getBoundingClientRect(); return r.left >= c.right || r.top >= c.bottom; });
  }, dialog);
  assert(await actionsClear("recording-start"), "Start Recording Cancel is unclipped and not overlapped");
  await page.locator('#recording-start [data-close="recording-start"]').last().click();
  assert.equal((await state()).recording, null, "Cancel must not start a recording");
  await wait(async () => (await page.locator("#mode-label").textContent()) === "RECORD");
  assert(await page.locator(".recording-bar #start").isVisible());
  assert.match(await page.locator("#context-title").textContent(), /Operator recording/);
  await page.locator("#start").click();
  await page.locator("#confirm-recording").click();
  await wait(async () => !!(await state()).recording?.active);
  await wait(async () => await page.locator("#pause").isVisible());
  await page.locator("#pause").click();
  await wait(async () => (await state()).recording?.status === "paused");
  // Recording facts is a read-only sheet built from Command recording state.
  await page.locator("#record-facts").click();
  assert(await page.locator("#recording-facts").evaluate((d) => d.open), "Recording facts opens as a dialog");
  await wait(async () => (await page.locator("#recording-facts .facts-stat").first().textContent()).includes("Paused"));
  assert.equal(await page.locator("#recording-facts-sub").textContent(), "Paused · Read only");
  await page.locator("#recording-facts [data-dismiss-facts]").click();
  assert.equal(await page.locator("#recording-facts").evaluate((d) => d.open), false);
  await page.locator("#resume").click();
  await wait(async () => (await state()).recording?.status === "active");
  // LIVE keeps an existing recording visible; history stays locked.
  await menuAction(page, "mode-live");
  await wait(async () => (await page.locator("#mode-label").textContent()) === "LIVE");
  assert(await page.locator(".recording-bar").isVisible(), "active recording remains visible in LIVE");
  await page.locator("#mode-button").click();
  assert(await page.locator("#mode-travel").isDisabled(), "Travel Back locked while recording");
  await page.keyboard.press("Escape");
  await page.locator("#stop").click();
  await page.waitForSelector("#save-snapshot[open]");
  await wait(async () => (await state()).recording?.status === "stopped");
  assert(await actionsClear("save-snapshot"), "Save snapshot Cancel is unclipped and not overlapped");
  await page.locator('#save-snapshot [data-close="save-snapshot"]').last().click();
  assert(await page.locator("#stopped-choice").isVisible(), "unsaved stopped recording is retained after Cancel");
  // Saving the stopped recording stacks the receipt; Discard stays usable and the notice stays truthful.
  await page.locator("#stopped-save").click();
  await page.locator("#snapshot-name").fill("V5.14 workspace snapshot");
  await page.locator("#snapshot-submit").click();
  await page.waitForSelector("#snapshot-receipt:not([hidden])");
  const receipt = await box("#snapshot-receipt"), discard = await box("#discard-recording");
  assert(receipt.y >= discard.y + discard.height || receipt.y + receipt.height <= discard.y, "save receipt does not cover Discard");
  assert.match(await page.locator("#stopped-choice").innerText(), /Snapshot saved/);

  // Provider failure: losing the state API is reported, not hidden.
  const liveRequests = (url) => /\/local\/(live|state)$/.test(url.pathname);
  await page.route(liveRequests, (route) => route.abort());
  await page.waitForFunction(() => document.querySelector("#connection").title.includes("unavailable"), null, { timeout: 15000 });
  await page.locator("#mode-button").click();
  assert(await page.locator("#mode-navigation").isVisible(), "workspace stays operable while Command is unreachable");
  await page.keyboard.press("Escape");
  await page.unroute(liveRequests);
  await page.waitForFunction(() => document.querySelector("#connection").title.includes("Connected"), null, { timeout: 15000 });

  // SOS sound is a switch inside SOS emergencies; visible SOS is independent.
  await page.locator("#open-sos").click();
  assert.equal(await page.locator("#sos-audio").getAttribute("role"), "switch");
  await page.locator("#sos-audio").click();
  assert.match(await page.locator("#sos-audio-status").innerText(), /enabled|blocked/i);
  assert.equal(await page.locator("#sos-audio").getAttribute("aria-checked"), (await page.locator("#sos-audio").textContent()).includes("ready") ? "true" : "false");
  if ((await page.locator("#sos-audio").getAttribute("aria-checked")) === "true") {
    await page.locator("#sos-audio").click();
    assert.equal(await page.locator("#sos-audio").getAttribute("aria-checked"), "false", "SOS sound switch turns off");
    assert.match(await page.locator("#sos-audio-status").innerText(), /turned off/i);
    await page.locator("#sos-audio").click();
    assert.equal(await page.locator("#sos-audio").getAttribute("aria-checked"), "true", "SOS sound switch turns back on");
  }
  await page.locator('[data-close="sos-panel"]').click();
  // Phone control tabs are a roving-tabindex tablist reachable by arrow keys.
  await menuAction(page, "open-control");
  await page.locator("#phone-tab-send").focus();
  await page.keyboard.press("ArrowRight");
  assert.equal(await page.locator("#phone-tab-enrollment").getAttribute("aria-selected"), "true");
  await page.keyboard.press("End");
  assert(await page.locator("#phone-tab-results").evaluate((e) => e === document.activeElement && e.getAttribute("aria-selected") === "true"), "End selects Results");
  await page.evaluate(() => document.querySelector("#phone-control").close());
  // History: layer tabs, structured entries (never a bare JSON dump) and close.
  await page.locator("#devices .device .party-select").first().click();
  await page.locator("#inspect-history").click();
  await wait(async () => (await page.locator("#history-inspection").getAttribute("aria-busy")) === null);
  assert.equal(await page.locator("#inspection-qualified").getAttribute("aria-selected"), "true");
  assert(await page.locator("#inspection-list :is(.history-row, .history-empty)").first().isVisible(), "qualified layer shows entries or an empty state");
  await page.locator("#inspection-raw").click();
  await wait(async () => (await page.locator("#inspection-raw").getAttribute("aria-selected")) === "true" && (await page.locator("#history-inspection").getAttribute("aria-busy")) === null);
  assert((await page.locator("#inspection-list .history-row").count()) > 0, "original observations are listed");
  assert.equal(await page.locator(".history-raw").evaluate((d) => d.open), false, "page data stays collapsed");
  const historyAxe = await new AxeBuilder({ page }).include("#history-inspection").withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
  await page.locator('[data-close="history-inspection"]').click();
  assert.equal(await page.locator("#history-inspection").evaluate((d) => d.open), false);
  // Get a Map never shows a blank preview: an empty state or labelled geometry.
  await menuAction(page, "open-map");
  assert.equal(await page.locator("#map-settings .map-selection-preview :is(.map-preview-empty, .map-preview-label):visible").count(), 1, "map preview is never blank");
  await page.evaluate(() => document.querySelector("#map-settings").close());
  await menuAction(page, "open-sos");
  const sosAxe = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
  await page.locator('[data-close="sos-panel"]').click();

  const axe = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
  const violations = [...axe.violations, ...sosAxe.violations, ...historyAxe.violations].map((v) => ({ id: v.id, nodes: v.nodes.map((n) => n.target) }));
  fs.writeFileSync(path.join(out, "v514-accessibility.json"), JSON.stringify(violations, null, 2));
  assert.deepEqual(violations, []);
  assert.deepEqual(errors, []);
  console.log("PASS: V5.14 workspace geometry, mode menu keyboard/refresh, party search/filters, inspector, panels, Day/Night persistence, 7 viewports × 2 themes, Record cancel/start/pause/resume/stop-retains, provider failure, SOS sound switch, Recording facts, History layers, Get a Map preview state, axe. Screenshots: " + out);
})()
  .catch(async (e) => {
    console.error(e);
    process.exitCode = 1;
  })
  .finally(async () => {
    await browser?.close();
    command?.kill();
  });
