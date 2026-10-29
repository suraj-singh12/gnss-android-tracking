/* Real persisted #27 browser evidence, SQLite, Command and Chromium. */
"use strict";
const assert = require("node:assert/strict"),
  fs = require("node:fs"),
  path = require("node:path"),
  os = require("node:os"),
  net = require("node:net");
const { spawn } = require("node:child_process"),
  { once } = require("node:events"),
  { randomUUID } = require("node:crypto");
const { chromium } = require(
  process.env.GNSS_PLAYWRIGHT_MODULE || "playwright",
);
const work = fs.mkdtempSync(path.join(os.tmpdir(), "gnss-playback-")),
  out = process.env.GNSS_SCREENSHOT_DIR || path.join(work, "screenshots");
fs.mkdirSync(out, { recursive: true });
const report = {
  source: process.env.SOURCE_REVISION || "working-tree",
  physical_acceptance: false,
};
let command, browser, page;
async function port() {
  const s = net.createServer();
  s.listen(0, "127.0.0.1");
  await once(s, "listening");
  const p = s.address().port;
  await new Promise((r) => s.close(r));
  return p;
}
async function headerAction(id) {
  const target = page.locator(`#${id}`);
  const root = page.locator(".header-menu-root");
  if (!(await root.evaluate(e => e.open))) await root.locator(":scope > summary").click();
  const group = target.locator("xpath=ancestor::details[contains(@class, 'menu')][1]");
  if (!(await group.evaluate(e => e.open))) await group.locator(":scope > summary").click();
  await target.click();
  if (id === 'open-library') await page.waitForFunction(() => !document.querySelector('#library-status').textContent.includes('Loading'));
}
async function mapAction(id) { await page.locator(`#${id}`).click(); }
async function openDetails() {
  if (await page.locator('#close-details').isVisible()) await page.locator('#close-details').click();
  if (await page.locator('#collapse-parties').isVisible() === false) await page.locator('#toggle-parties').click();
  await page.locator('.historical-party-name:not(:disabled)').first().click();
}
const panelMeasurements = [];
async function inspectPanel(selector, mode, width, theme) {
  await page.waitForTimeout(120);
  const facts = await page.locator(selector).evaluate(panel => {
    const r=panel.getBoundingClientRect(), dock=document.querySelector(document.body.classList.contains('playback') ? '#playback-timeline' : '.recording-bar').getBoundingClientRect();
    return {width:r.width, right:r.right, bottom:r.bottom, dockTop:dock.top, scrollWidth:panel.scrollWidth, clientWidth:panel.clientWidth, font:parseFloat(getComputedStyle(panel.querySelector('small, .historical-values') || panel).fontSize), padding:parseFloat(getComputedStyle(panel).paddingTop)};
  });
  assert(facts.width > 0 && facts.scrollWidth <= facts.clientWidth+1, `${selector} overflow: ${JSON.stringify(facts)}`);
  if (width <= 1100) {
    assert(facts.bottom <= facts.dockTop-10, `${selector} covers dock`);
    assert(facts.font >= 12 && facts.font <= 13, 'panel text stays readable');
    assert.equal(facts.padding,8);
  }
  panelMeasurements.push({mode,width,theme,selector,...facts});
  fs.writeFileSync(path.join(out,'panel-measurements.json'),JSON.stringify(panelMeasurements,null,2));
  return facts;
}
async function wait(fn) {
  for (let i = 0; i < 300; i++) {
    if (await fn().catch(() => false)) return;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw Error("timed out");
}
(async () => {
  const phone = `http://127.0.0.1:${await port()}`,
    local = `http://127.0.0.1:${await port()}`;
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
      {
        stdio: [
          "ignore",
          "ignore",
          fs.openSync(path.join(work, "command.log"), "a"),
        ],
      },
    );
    await wait(async () => (await fetch(local + "/local/state")).ok);
  }
  await start();
  browser = await chromium.launch({
    ...(process.env.GNSS_CHROMIUM
      ? { executablePath: process.env.GNSS_CHROMIUM }
      : {}),
    headless: true,
    args: ["--no-sandbox"],
  });
  page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(local);
  const base = JSON.parse(
      fs.readFileSync(
        path.join(__dirname, "../../protocol/fixtures/location-normal.json"),
      ),
    ),
    ids = Array.from({ length: 21 }, () => randomUUID());
  for (let seq = 1; seq <= 5; seq++)
    for (let i = 0; i < ids.length; i++) {
      const m = structuredClone(base);
      m.device_id = ids[i];
      m.message_id = randomUUID();
      m.sequence = seq;
      m.party.id = ["Alpha", "Bravo", "Charlie"][i] || `Party ${i + 1}`;
      m.captured_at = m.fix.observed_at = new Date(
        Date.now() - 60000 + (seq - 1) * 1000,
      ).toISOString();
      m.fix.longitude += i * 0.0002 + seq * 0.0001;
      m.fix.latitude += seq * 0.00015;
      m.fix.speed_mps = 0.5;
      const r = await fetch(phone + "/api/v1/messages", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(m),
      });
      assert.equal(r.status, 200, await r.text());
    }
  await page.evaluate(() => poll());
  for (const [width, height] of [[1920,1080],[1440,900],[1280,720],[1100,800],[960,720],[760,900],[430,900],[390,844]]) {
    await page.setViewportSize({width, height});
    await page.evaluate(() => draw());
    const labels = await page.evaluate(() => {
      const rects = [...document.querySelectorAll('[data-party-label]')].map(e => { const r=e.getBoundingClientRect(); return {left:r.left,right:r.right,top:r.top,bottom:r.bottom}; });
      const overlaps = rects.some((a,i) => rects.slice(i+1).some(b => a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top));
      return {overlaps, count:rects.length, markers:document.querySelectorAll('[data-layer="live"] [data-point-key$="/live"]').length, notice:!document.querySelector('#label-density').hidden};
    });
    assert.equal(labels.markers, 21, "decluttering must retain all markers");
    assert(labels.count > 0 && !labels.overlaps, `clustered labels: ${JSON.stringify(labels)}`);
    const selectedVisible = await page.evaluate(() => {
      const map = document.querySelector('.canvas').getBoundingClientRect();
      const marker = [...document.querySelectorAll('[data-layer="live"] [data-point-key$="/live"]')].find(e => { const r=e.getBoundingClientRect(); return r.left > map.left+100 && r.right < map.right-100 && r.top > map.top+80 && r.bottom < map.bottom-80; });
      if (!marker) return true;
      const id=marker.getAttribute('data-device'); selectParty(id); draw();
      return !!document.querySelector(`[data-party-label="${CSS.escape(id)}"]`);
    });
    assert(selectedVisible, "selected visible party must receive label priority");
    for (const theme of ['day','night']) {
      if (await page.locator('#close-details').isVisible()) await page.locator('#close-details').click();
      if (!(await page.locator('#collapse-parties').isVisible())) await page.locator('#toggle-parties').click();
      await inspectPanel('.party-overview','live',width,theme);
      await page.screenshot({path:path.join(out,`live-${theme}-parties-${width}.png`)});
      const party=page.locator('.party-select').first();
      await party.scrollIntoViewIfNeeded();
      await wait(async () => (await party.boundingBox())?.height >= 40);
      await party.click();
      await inspectPanel('.party-detail','live',width,theme);
      await page.screenshot({path:path.join(out,`live-${theme}-details-${width}.png`)});
      await headerAction('appearance');
    }
    await page.screenshot({path: path.join(out, `live-day-${width}.png`)});
    await headerAction("appearance");
    await page.screenshot({path: path.join(out, `live-night-${width}.png`)});
    await headerAction("appearance");
  }
  await page.setViewportSize({width:1440,height:900});
  if (await page.locator("#toggle-parties").getAttribute("aria-expanded") === "false") await page.locator("#toggle-parties").click();
  await page.locator("#start").click();
  await page
    .locator("#session-mode")
    .check()
    .catch(() => {});
  await page.locator("#confirm-recording").click();
  await wait(async () =>
    /revision \d+/.test(await page.locator("#capture-health").textContent()),
  );
  const first = await page.evaluate(() => window.recordingAppliedState());
  // Original hide/reveal boundaries must survive future frames and inspection.
  await page.evaluate((id) => {
    hidden.add(id);
    draw();
  }, ids[2]);
  await new Promise((r) => setTimeout(r, 800));
  const hiddenAt = Date.now();
  await page.evaluate((id) => {
    hidden.delete(id);
    draw();
  }, ids[2]);
  await new Promise((r) => setTimeout(r, 800));
  // An earlier observation is delivered after Start. Its redraw belongs to
  // the later browser confirmation, never its observation timestamp.
  const late = structuredClone(base);
  late.device_id = ids[0];
  late.message_id = randomUUID();
  late.sequence = 6;
  late.captured_at = late.fix.observed_at = new Date(
    Date.now() - 90000,
  ).toISOString();
  late.fix.longitude -= 0.0001;
  late.fix.latitude -= 0.00015;
  const lateResponse = await fetch(phone + "/api/v1/messages", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(late),
  });
  assert.equal(lateResponse.status, 200, await lateResponse.text());
  await wait(
    async () =>
      !(await (await fetch(local + "/local/state")).json()).projection_pending,
  );
  await page.evaluate(() => poll());
  const lateReceipt = (await (await fetch(local + "/local/state")).json())
    .receipt_version;
  await wait(
    async () =>
      (await (await fetch(local + "/local/presentation/status")).json())
        .displayed_receipt_version >= lateReceipt,
  );
  // A map is actually applied and its immutable response is pinned by #27.
  const hgt = Buffer.alloc(1201 * 1201 * 2);
  for (let y = 0; y < 1201; y++)
    for (let x = 0; x < 1201; x++)
      hgt.writeInt16BE(500 + (x % 17) * 3 + (y % 13) * 2, (y * 1201 + x) * 2);
  const asset = await page.evaluate(async (hgt) => {
    const m = await mapPost("maps", {
      source: "Playback immutable offline map",
      data: {
        type: "FeatureCollection",
        features: [
          {
            type: "Feature",
            properties: { name: "Pinned path" },
            geometry: {
              type: "LineString",
              coordinates: [
                [77.209, 28.6139],
                [77.24, 28.62],
              ],
            },
          },
        ],
      },
    });
    await refreshMaps(m.id);
    await loadSavedMap(m.id);
    await mapPost(`maps/${encodeURIComponent(m.id)}/terrain`, {
      name: "N28E077.hgt",
      data: hgt,
      components: { hillshade: true, contours: true, elevation: true },
    });
    await loadTerrain(m.id);
    document.querySelector("#layer-hillshade").checked = true;
    draw();
    return { id: m.id, hash: window.recordingAppliedState().assets[0] };
  }, hgt.toString("base64"));
  await new Promise((r) => setTimeout(r, 800));
  await page.evaluate((id) => {
    hidden.add(id);
    draw();
  }, ids[2]);
  await new Promise((r) => setTimeout(r, 900));
  await page.evaluate((id) => {
    hidden.delete(id);
    draw();
  }, ids[2]);
  await new Promise((r) => setTimeout(r, 900));
  await page.locator("#pause").click();
  await new Promise((r) => setTimeout(r, 1100));
  await page.locator("#resume").click();
  await new Promise((r) => setTimeout(r, 800));
  const sos = JSON.parse(
    fs.readFileSync(
      path.join(__dirname, "../../protocol/fixtures/sos-fix.json"),
    ),
  );
  sos.device_id = ids[0];
  sos.message_id = sos.sos.event_id = randomUUID();
  sos.sequence = 7;
  sos.captured_at = sos.fix.observed_at = new Date().toISOString();
  const sosResponse = await fetch(phone + "/api/v1/messages", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(sos),
  });
  assert.equal(sosResponse.status, 200, await sosResponse.text());
  const sosReceipt = (await (await fetch(local + "/local/state")).json())
    .receipt_version;
  await page.evaluate(() => poll());
  await wait(
    async () =>
      (await (await fetch(local + "/local/presentation/status")).json())
        .displayed_receipt_version >= sosReceipt,
  );
  const recordingId = (await (await fetch(local + "/local/state")).json())
    .recording.recording_id;
  const closureCount = async () =>
    (
      await (
        await fetch(
          local + "/local/presentation/summary?recording_id=" + recordingId,
        )
      ).json()
    ).browser_closed || 0;
  let closedBefore = await closureCount();
  await page.goto("about:blank");
  await wait(async () => (await closureCount()) > closedBefore);
  await page.goto(local);
  await wait(async () =>
    /revision \d+/.test(await page.locator("#capture-health").textContent()),
  );
  await page.locator("#pause").click();
  await wait(
    async () =>
      (await (await fetch(local + "/local/state")).json()).recording.status ===
      "paused",
  );
  const oldEpoch = (
    await (await fetch(local + "/local/presentation/status")).json()
  ).epoch;
  closedBefore = await closureCount();
  await page.goto("about:blank");
  await wait(async () => (await closureCount()) > closedBefore);
  command.kill("SIGKILL");
  await once(command, "exit");
  await new Promise((r) => setTimeout(r, 800));
  await start();
  await page.goto(local);
  await page.locator("#resume").waitFor();
  assert.equal(
    (await (await fetch(local + "/local/state")).json()).recording.status,
    "paused",
  );
  await page.locator("#resume").click();
  await wait(async () => {
    const status = await (
      await fetch(local + "/local/presentation/status")
    ).json();
    return (
      status.epoch !== oldEpoch &&
      status.displayed_receipt_version >= sosReceipt
    );
  });
  await page.locator("#save-recording").click();
  await page.locator("#snapshot-name").fill("Matura field test · 20 of 21");
  await page.locator(`#snapshot-parties input[value="${ids[20]}"]`).uncheck();
  await page.locator("#snapshot-submit").click();
  let entries;
  await wait(async () => {
    entries = await (await fetch(local + "/local/presentation/library")).json();
    return entries.length === 1;
  });
  const recording = entries[0];
  assert.equal(recording.total_parties, 21);
  assert.equal(recording.included.length, 20);
  await page.evaluate(
    async (id) => mapPost(`maps/${encodeURIComponent(id)}/delete`, {}),
    asset.id,
  );
  assert.equal(
    (
      await fetch(
        local +
          `/local/presentation/saved?id=${recording.id}&asset=${asset.hash}`,
      )
    ).status,
    200,
  );
  await page.locator("#open-sos").click();
  await page.locator(".sos-alert button").first().click();
  await page.locator('[data-close="sos-panel"]').click();
  await page
    .locator("#sos-toast-dismiss")
    .click()
    .catch(() => {});
  await headerAction("open-library");
  await page.getByRole("button", { name: "Open", exact: true }).waitFor();
  for (const [width, height] of [
    [390,844], [430,900], [760,900], [960,720], [1280,720], [1440,900], [1920,1080],
  ]) {
    await page.setViewportSize({ width, height });
    const libraryWidth=await page.locator('#saved-library').evaluate(e=>e.getBoundingClientRect().width);
    await page.locator('[data-close="saved-library"]').click();
    if (await page.locator('#close-details').isVisible()) await page.locator('#close-details').click();
    if (!(await page.locator('#collapse-parties').isVisible())) await page.locator('#toggle-parties').click();
    await page.locator('.party-select').first().click();
    const detailWidth=(await page.locator('.party-detail').boundingBox()).width;
    assert(Math.abs(libraryWidth-detailWidth)<1, `library ${libraryWidth} != Details ${detailWidth} at ${width}`);
    await headerAction('open-library');
    for (const theme of ['day','night']) {
      const overflow=await page.locator('#saved-library').evaluate(root=>[root,...root.querySelectorAll('#library-rows, .library-row, #library-selected, .library-sortline')].filter(e=>e.scrollWidth>e.clientWidth+1).map(e=>e.id || e.className));
      assert.deepEqual(overflow,[], `library overflow ${width} ${theme}`);
      const r=await page.locator('#saved-library').boundingBox();
      assert(Math.abs(r.x+r.width-width)<1, 'library aligned right');
      assert(Math.abs(r.width-detailWidth)<1);
      panelMeasurements.push({mode:'library',width,theme,selector:'#saved-library',widthMeasured:r.width,detailWidth});
      fs.writeFileSync(path.join(out,'panel-measurements.json'),JSON.stringify(panelMeasurements,null,2));
      await page.locator('#library-search').fill('no-such-recording');
      assert.equal(await page.locator('.library-row').count(),0);
      await page.locator('#library-search').fill('');
      await page.locator('#library-sort').selectOption('name');
      assert.equal(await page.locator('.library-row').count(),1);
      await page.locator('.library-more summary').click();
      const editAction=page.getByRole('button',{name:'Edit metadata',exact:true});
      await editAction.scrollIntoViewIfNeeded();
      await editAction.click();
      assert(await page.locator('#library-edit').evaluate(e=>e.open));
      await page.keyboard.press('Escape');
      assert.equal(await page.locator('#library-edit').evaluate(e=>e.open),false);
      assert(await page.locator('#saved-library').evaluate(e=>e.open));
      await editAction.scrollIntoViewIfNeeded();
      await page.screenshot({path:path.join(out,`library-${theme}-actions-${width}.png`)});
      await page.locator('.library-more summary').click();
      await page.screenshot({path:path.join(out,`library-${theme}-${width}.png`)});
      await page.keyboard.press('Escape');
      assert.equal(await page.locator('#saved-library').evaluate(e=>e.open),false);
      await headerAction('appearance');
      await headerAction('open-library');
    }

  }
  await page.getByRole("button", { name: "Open", exact: true }).click();
  await page.waitForFunction(() => window.HistoricalPlayback?.active);
  assert.equal(await page.locator("#follow-original").isChecked(), true);
  const timeline = await (
    await fetch(local + `/local/presentation/playback?id=${recording.id}`)
  ).json();
  assert(timeline.groups.some((g) => g.Kind === "PAUSED"));
  assert(timeline.groups.some((g) => g.Kind === "BROWSER NOT ACTIVE"));
  assert(timeline.facts.some((f) => f.Kind === "command_unavailable"));
  assert(
    timeline.groups.some(
      (g) =>
        g.Facts.some((f) => f.Kind === "pause") &&
        g.Facts.some((f) => f.Kind === "browser_closed") &&
        g.Facts.some((f) => f.Kind === "command_unavailable"),
    ),
    "real overlapping Pause/browser/Command facts must share one notice",
  );
  assert.equal(timeline.groups[0].Duration >= 1, true);
  async function seek(t) {
    await page.evaluate((t) => window.HistoricalPlayback.seek(t), t);
  }
  let cursor = "0",
    hiddenStamp,
    visibility = [];
  for (let n = 0; n < 300; n++) {
    const part = await (
      await fetch(
        local +
          `/local/presentation/saved?id=${recording.id}&event_cursor=${cursor}`,
      )
    ).json();
    if (!part.event) break;
    cursor = part.next_cursor;
    if (part.checkpoint?.parties?.[ids[2]]) {
      const p = part.checkpoint.parties[ids[2]],
        stamp = Date.parse(
          part.event.Data.effective_at ||
            part.event.Data.presented_at ||
            part.event.Data.render_opportunity_at ||
            part.event.At,
        );
      if (!visibility.length || visibility.at(-1).visible !== p.visible)
        visibility.push({ visible: p.visible, at: stamp });
    }
    if (part.checkpoint?.parties?.[ids[2]]?.visible === false)
      hiddenStamp = Date.parse(
        part.event.Data.effective_at ||
          part.event.Data.presented_at ||
          part.event.Data.render_opportunity_at ||
          part.event.At,
      );
  }
  assert(hiddenStamp, "real hidden frame captured");
  await seek((hiddenStamp - Date.parse(recording.started_at)) / 1000 + 0.01);
  await page.locator("#follow-original").uncheck();
  assert.equal(
    await page.locator('[aria-label="Show Charlie"]').isEnabled(),
    false,
  );
  assert.equal(
    await page.locator('[data-historical-party="' + ids[2] + '"]').count(),
    0,
  );
  await page.screenshot({
    path: path.join(out, "playback-hidden-restriction.png"),
  });
  assert(
    visibility.length >= 5,
    "all original visibility boundaries must be confirmed",
  );
  const shift = (stamp) =>
    (stamp - Date.parse(recording.started_at)) / 1000 + 0.01;
  await page.evaluate(
    (t) => window.HistoricalPlayback.seek(t, false),
    shift(visibility[2].at),
  );
  assert.equal(
    await page.locator('[aria-label="Show Charlie"]').isChecked(),
    true,
  );
  await page.locator('[aria-label="Show Charlie"]').uncheck();
  await page.evaluate(
    (t) => window.HistoricalPlayback.seek(t, false),
    shift(visibility[2].at) + 0.05,
  );
  assert.equal(
    await page.locator('[aria-label="Show Charlie"]').isChecked(),
    false,
  );
  await page.evaluate(
    (t) => window.HistoricalPlayback.seek(t, false),
    shift(visibility[3].at),
  );
  assert.equal(
    await page.locator('[aria-label="Show Charlie"]').isEnabled(),
    false,
  );
  await page.evaluate(
    (t) => window.HistoricalPlayback.seek(t, false),
    shift(visibility[4].at),
  );
  assert.equal(
    await page.locator('[aria-label="Show Charlie"]').isChecked(),
    true,
  );
  await seek(shift(visibility[1].at));
  assert.equal(
    await page.locator('[aria-label="Show Charlie"]').isEnabled(),
    false,
  );
  await page.locator("#follow-original").check();
  await seek(shift(visibility[0].at));
  await page.locator("#follow-original").uncheck();
  await page
    .getByRole("button", { name: "Details for Alpha", exact: true })
    .click();
  await mapAction("zoom-in");
  const inspectionTransform = await page
    .locator("#historical-world")
    .getAttribute("transform");
  await page.evaluate(
    (t) => window.HistoricalPlayback.seek(t, false),
    timeline.duration,
  );
  assert.equal(
    await page.locator("#historical-world").getAttribute("transform"),
    inspectionTransform,
    "recorded viewport changes must not disrupt inspection",
  );
  await mapAction("focus-party");
  const focus = await page.evaluate((id) => {
    const p = document
        .querySelector(`[data-historical-party="${id}"]`)
        .getBoundingClientRect(),
      m = document.querySelector(".canvas").getBoundingClientRect();
    return (
      Math.abs((p.left + p.right) / 2 - (m.left + m.right) / 2) < 5 &&
      Math.abs((p.top + p.bottom) / 2 - (m.top + m.bottom) / 2) < 5
    );
  }, ids[0]);
  assert(focus, "focus uses the inspector viewport");
  await page.locator("#follow-original").check();
  await seek(timeline.duration);
  assert(
    (await page
      .locator("#tracks path,#tracks polyline,#tracks circle")
      .count()) > 0,
  );
  const shape = await page.locator("#tracks").innerHTML();
  await seek(0);
  await seek(timeline.duration);
  assert.equal(
    await page.locator("#tracks").innerHTML(),
    shape,
    "rewind must restore exact SVG",
  );
  for (const [width, height] of [
    [1280, 720],
    [1440, 900],
    [1920, 1080],
    [960, 720],
    [760, 900],
    [1100, 800],
    [430, 900],
    [390, 844],
  ]) {
    await page.setViewportSize({ width, height });
    await seek(timeline.duration);
    // Follow Original first restores recorded state; inspect through the sidebar afterwards.
    await openDetails();
    if (width > 1100) await page.evaluate(() => document.querySelector("main").classList.remove("details-closed", "parties-closed"));
    await page.waitForTimeout(100);
    const boxes = await page.evaluate(() => {
      const b = (s) => {
        const r = document.querySelector(s).getBoundingClientRect();
        return { x: r.x, right: r.right, y: r.y, bottom: r.bottom };
      };
      return {
        timeline: b("#playback-timeline"),
        map: b(".canvas"),
        left: b(".party-overview"),
        right: b(".party-detail"),
      };
    });
    assert(boxes.timeline.x >= boxes.map.x);
    assert(boxes.timeline.right <= boxes.map.right);
    const overlap = (a,b) => a.x < b.right && a.right > b.x && a.y < b.bottom && a.bottom > b.y;
    assert(!overlap(boxes.timeline, boxes.left));
    assert(!overlap(boxes.timeline, boxes.right));
    async function assertPlaybackLayout() {
      const problems = await page.evaluate(() => {
        const map = document.querySelector('.canvas').getBoundingClientRect();
        const timeline = document.querySelector('#playback-timeline').getBoundingClientRect();
        const toolbar = document.querySelector('.canvas-toolbar');
        const items = [...toolbar.querySelectorAll('button, summary')].filter(e => e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden');
        const boxes = items.map(e => e.getBoundingClientRect());
        const overlap = (a, b) => a.left < b.right - 1 && a.right > b.left + 1 && a.top < b.bottom - 1 && a.bottom > b.top + 1;
        const errors = [];
        for (const id of ['zoom-in','zoom-out','fit','focus-party','inspect-history','open-layers']) {
          const node=document.getElementById(id), r=node.getBoundingClientRect();
          if (!r.width || getComputedStyle(node).visibility==='hidden') errors.push(id+' unavailable');
        }
        const layers=document.querySelector('#open-layers').getBoundingClientRect();
        const tools=toolbar.getBoundingClientRect();
        if (Math.abs(layers.right-tools.right)>2 || Math.abs(layers.top-tools.top)>2) errors.push('Layers not top-right');
        const status=document.querySelector('#playback-status').getBoundingClientRect();
        const evidence=document.querySelector('#playback-evidence');
        if (!evidence.hidden && evidence.getBoundingClientRect().top < status.bottom) errors.push('evidence overlaps status');
        boxes.forEach((r, i) => {
          if (r.left < map.left - 1 || r.right > map.right + 1) errors.push('toolbar outside map');
          boxes.slice(i + 1).forEach(b => { if (overlap(r, b)) errors.push('toolbar overlap'); });
        });
        for (const e of document.querySelectorAll('#playback-timeline > *')) {
          const r = e.getBoundingClientRect();
          if (r.width && (r.left < timeline.left - 1 || r.right > timeline.right + 1 || r.bottom > timeline.bottom + 1)) errors.push('timeline child outside');
        }
        const sos = document.querySelector('#playback-sos');
        if (!sos.hidden && overlap(sos.getBoundingClientRect(), timeline)) errors.push('SOS overlaps timeline');
        if (timeline.left < map.left - 1 || timeline.right > map.right + 1 || timeline.bottom > map.bottom + 1) errors.push('timeline outside map');
        return errors;
      });
      assert.deepEqual(problems, []);
    }
    const inspectedDetails=await inspectPanel('.party-detail','playback',width,'day');
    await assertPlaybackLayout();
    await page.screenshot({
      path: path.join(out, `playback-day-${width}.png`),
    });
    await headerAction("appearance");
    await inspectPanel('.party-detail','playback',width,'night');
    await assertPlaybackLayout();
    await page.screenshot({
      path: path.join(out, `playback-night-${width}.png`),
    });
    await headerAction("appearance");
    if (await page.locator("#collapse-parties").isVisible()) await page.locator("#collapse-parties").click();
    if (await page.locator("#close-details").isVisible()) await page.locator("#close-details").click();
    await page.screenshot({
      path: path.join(out, `playback-collapsed-${width}.png`),
    });
    await headerAction("appearance");
    await page.screenshot({path: path.join(out, `playback-night-collapsed-${width}.png`)});
    await headerAction("appearance");
    for (const control of ["toggle-details", "toggle-parties"]) {
      await page.evaluate(() => document.querySelector("main").classList.add("details-closed", "parties-closed"));
      if (control === "toggle-details") await openDetails(); else await page.locator("#toggle-parties").click();
      const bounds = await page.evaluate(() => {
        const t = document
            .querySelector("#playback-timeline")
            .getBoundingClientRect(),
          m = document.querySelector(".canvas").getBoundingClientRect();
        return t.left >= m.left && t.right <= m.right;
      });
      assert(bounds, "timeline must resize for either individual panel");
      await inspectPanel(control === 'toggle-details' ? '.party-detail' : '.party-overview','playback',width,'day');
      await assertPlaybackLayout();
      await page.screenshot({
        path: path.join(out, `playback-${control}-only-${width}.png`),
      });
      await headerAction("appearance");
      await inspectPanel(control === 'toggle-details' ? '.party-detail' : '.party-overview','playback',width,'night');
      await assertPlaybackLayout();
      await page.screenshot({ path: path.join(out, `playback-night-${control}-only-${width}.png`) });
      await headerAction("appearance");
    }
    for (const theme of ['day','night']) {
      await headerAction('open-library');
      const measured=await page.locator('#saved-library').evaluate(e=>({width:e.getBoundingClientRect().width,overflow:e.scrollWidth>e.clientWidth+1}));
      assert(Math.abs(measured.width-inspectedDetails.width)<1, 'playback library must match Details');
      assert.equal(measured.overflow,false);
      assert(await page.evaluate(()=>window.HistoricalPlayback.active), 'library does not exit playback');
      panelMeasurements.push({mode:'playback-library',width,theme,selector:'#saved-library',widthMeasured:measured.width,detailWidth:inspectedDetails.width});
      fs.writeFileSync(path.join(out,'panel-measurements.json'),JSON.stringify(panelMeasurements,null,2));
      await page.screenshot({path:path.join(out,`playback-library-${theme}-${width}.png`)});
      await page.locator('[data-close="saved-library"]').click();
      await headerAction('appearance');
    }
    await page.evaluate(() => document.querySelector("main").classList.remove("details-closed", "parties-closed"));
  }
  await page.locator("#follow-original").uncheck();
  await mapAction("zoom-in");
  await mapAction("open-layers");
  await page
    .locator("#historical-content")
    .getByLabel("contours", { exact: true })
    .check();
  await wait(
    async () =>
      (await page.locator('#tracks [data-layer="contours"]').count()) > 0,
  );
  await page.locator('[data-close="historical-inspection"]').click();
  assert(
    (await page.locator("#playback-status").textContent()).includes(
      "INSPECTION VIEW",
    ),
  );
  await page.screenshot({ path: path.join(out, "playback-inspection.png") });
  await page.locator("#follow-original").check();
  const g = timeline.groups[0];
  await seek(g.Offset + 1);
  assert(await page.locator("#playback-notice").isVisible());
  await page.screenshot({ path: path.join(out, "playback-interruption.png") });
  await seek(g.Offset + 3.999);
  assert(await page.locator("#playback-notice").isVisible());
  await seek(g.Offset + 4);
  assert.equal(await page.locator("#playback-notice").isVisible(), false);
  await seek(g.Offset);
  await page.locator("#playback-speed").selectOption("1");
  await page.locator("#playback-play").click();
  await new Promise((r) => setTimeout(r, 3700));
  assert(await page.locator("#playback-notice").isVisible());
  await new Promise((r) => setTimeout(r, 500));
  assert.equal(await page.locator("#playback-notice").isVisible(), false);
  await page.locator("#playback-play").click();
  await seek(g.Offset);
  await page.locator("#playback-speed").selectOption("20");
  await page.locator("#playback-play").click();
  await wait(
    async () =>
      await page.evaluate(
        (offset) => window.HistoricalPlayback.position >= offset + 4,
        g.Offset,
      ),
  );
  await page.locator("#playback-play").click();
  await page.locator("#playback-speed").selectOption("5");
  await page.locator("#playback-speed").selectOption("1");
  const writes = [];
  page.on("request", (r) => {
    if (
      r.method() === "POST" &&
      !/presentation\/(heartbeat|claim|present|confirm|gap)/.test(r.url())
    )
      writes.push(r.url());
  });
  await headerAction("open-quality");
  assert(await page.locator("#historical-inspection").isVisible());
  assert.equal(await page.locator("#historical-content input").count(), 0);
  await page.locator('[data-close="historical-inspection"]').click();
  await seek(0);
  await seek(timeline.duration);
  assert.deepEqual(writes, []);
  await seek(timeline.duration);
  assert(await page.locator("#playback-sos").isVisible());
  assert.equal(await page.locator("#playback-sos button").count(), 0);
  await page.locator("#exit-playback").click();
  await headerAction("appearance");
  await headerAction("open-library");
  await page.screenshot({ path: path.join(out, "library-night-960.png") });
  await page.locator('[data-close="saved-library"]').click();
  await page.reload();
  await headerAction("open-library");
  await page.getByRole("button", { name: "Open", exact: true }).click();
  await page.waitForFunction(() => window.HistoricalPlayback?.active);
  await seek(timeline.duration);
  await page.locator("#exit-playback").click();
  await headerAction("open-library");
  await page.locator(".library-more summary").click();
  await page
    .getByRole("button", { name: "Edit metadata", exact: true })
    .click();
  await page.locator("#library-name").fill("Renamed persisted recording");
  await page.locator("#library-date").fill("27 Oct 2026 · Asia/Calcutta");
  await page.locator("#library-description").fill("Historical metadata only");
  await page.locator('#library-edit-form button[type="submit"]').click();
  await page.locator('[data-close="saved-library"]').click();
  command.kill();
  await once(command, "exit");
  await start();
  await page.reload();
  await headerAction("open-library");
  await page
    .getByText("Renamed persisted recording", { exact: true })
    .waitFor();
  await page.getByRole("button", { name: "Open", exact: true }).click();
  await page.waitForFunction(() => window.HistoricalPlayback?.active);
  await seek(timeline.duration);
  assert(await page.locator("#playback-sos").isVisible());
  await page.locator("#exit-playback").click();
  const operationalBefore = await (await fetch(local + "/local/state")).json();
  await headerAction("open-library");
  await page.locator(".library-more summary").click();
  page.once("dialog", (d) => d.accept());
  await page.getByRole("button", { name: "Delete", exact: true }).click();
  await wait(
    async () =>
      (await (await fetch(local + "/local/presentation/library")).json())
        .length === 0,
  );
  assert.equal(
    (await fetch(local + `/local/presentation/playback?id=${recording.id}`))
      .status,
    404,
  );
  const operationalAfter = await (await fetch(local + "/local/state")).json();
  assert.deepEqual(operationalAfter.sos_alerts, operationalBefore.sos_alerts);
  assert.equal(Object.keys(operationalAfter.devices).length, 21);
  await page.screenshot({ path: path.join(out, "library-night-empty.png") });
  await page.locator('[data-close="saved-library"]').click();
  await headerAction("appearance");
  await headerAction("open-library");
  await page.screenshot({ path: path.join(out, "library-day-empty.png") });
  assert.deepEqual(errors, []);
  report.passed = true;
  report.parties = 21;
  report.included = 20;
  report.timeline_duration = timeline.duration;
  report.immutable_map_after_deletion = true;
  report.pinned_terrain_after_deletion = true;
  report.deterministic_rewind = true;
  report.process_restart = true;
  report.delayed_history_redraw = true;
  report.historical_sos_read_only = true;
  report.real_command_browser_pause_overlap = true;
})()
  .catch(async (e) => {
    report.error = e.stack;
    process.exitCode = 1;
    if (page)
      await page
        .screenshot({ path: path.join(out, "failure.png") })
        .catch(() => {});
  })
  .finally(async () => {
    fs.writeFileSync(
      path.join(out, "playback-report.json"),
      JSON.stringify(report, null, 2),
    );
    console.log(JSON.stringify(report, null, 2));
    console.log("Evidence: " + out);
    if (browser) await browser.close();
    if (command) command.kill();
  });
