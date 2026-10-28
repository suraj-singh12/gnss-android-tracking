/* Real Command API + Chromium. Fixture data is synthetic; no mocked state API. */
"use strict";
const { menuAction } = require("./menu-actions.cjs");
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
    const r = route === "recording" ? await require("./recording-control.cjs").recordingPost(local,body,browser) : await fetch(local + "/local/" + route, {
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

  await page.locator("#start").click();
  // Issue6's current Start modal must allow all-history with no known phones.
  assert.equal(await page.locator("#session-mode").isEnabled(), true);
  await page.locator("#session-mode").check();
  await page.locator("#confirm-recording").click();
  await wait(async () => (await state()).recording?.mode === "from_beginning");
  assert.equal(Object.keys((await state()).devices).length, 0);
  const fixture = JSON.parse(fs.readFileSync(path.join(__dirname, "../../protocol/fixtures/location-native-session.json"), "utf8"));
  const devices = Array.from({length:5}, () => randomUUID()), original = [];
  const measuredNow = Date.now();
  let missing;
  for (let d=0; d<5; d++) {
    for (let sessionIndex=0; sessionIndex<2; sessionIndex++) {
      const session=randomUUID(), start=measuredNow - [86400000,172800000,604800000,86400000,604800000][d] + sessionIndex*3600000;
      const block=[];
      for (let n=1; n<=5; n++) {
        const m=structuredClone(fixture);
        m.device_id=devices[d];m.message_id=randomUUID();m.sequence=sessionIndex*5+n;
        m.party={id:["Alpha","Bravo","Charlie","Delta","Echo"][d], name:"Retained history"};
        m.captured_at=new Date(start+n*1000).toISOString();m.fix.observed_at=m.captured_at;
        m.fix.fix_age_ms=0;m.fix.latitude=d*100/6371008.8*180/Math.PI;m.fix.longitude=n*10/6371008.8*180/Math.PI;
        m.fix.horizontal_accuracy_m=d===2 ? 100 : 1;
        m.observation={observation_id:m.message_id,tracking_session_id:session,session_started_at:new Date(start).toISOString(),observation_sequence:n,measurement_elapsed_ms:n*1000};
        original.push(m);block.push(m);
        if(d===1 && sessionIndex===0 && n===2) missing=m;
      }
      // Newer blocks first, newest packets first, one known missing observation.
      for(const m of block.reverse()) { if(m!==missing) await send(m,"history"); }
    }
  }
  await send(original[0],"history");
  await wait(async()=>!(await state()).projection_pending);
  let st=await state();
  assert.equal(st.points.length,39);
  for(let d=0; d<5; d++) {
    const r=st.devices[devices[d]].reconstruction;
    assert.equal(r.durably_received,d===1?9:10);assert.equal(r.eligible,r.durably_received);
    assert.equal(r.projected,r.eligible);assert.equal(r.projection,"completed");
    assert.equal(r.accepted,d===2?0:r.eligible);
    assert.equal(r.route_condition,d===2?"quality_filtered":"qualified_geometry");
    assert.equal(Object.keys(st.devices[devices[d]].history_sessions).length,2);
    assert.equal(st.devices[devices[d]].history.condition,"unknown");
    assert.match(r.inventory_scope,/unknown/);
    assert.equal(r.qualified_segments,d===2?0:d===1?3:2);
  }
  assert.equal(st.devices[devices[1]].reconstruction.known_missing_sequences,1);
  assert.ok(Math.abs(st.devices[devices[1]].total_m-60)<1e-6);
  await page.reload();
  await page.waitForFunction(()=>document.querySelectorAll('polyline[data-qualified-points]').length===9);
  assert.equal(await page.locator('polyline[data-device="'+devices[2]+'"]').count(),0);
  const drawn=await page.locator('polyline[data-qualified-points]').evaluateAll(nodes=>nodes.reduce((n,e)=>n+Number(e.dataset.qualifiedPoints),0));
  assert.equal(drawn,st.points.length);
  await page.getByRole("button",{name:"Charlie",exact:true}).click();
  await page.waitForFunction(()=>document.body.textContent.includes("All eligible history rejected by quality rules"));
  await page.getByText("Diagnostics & significant events",{exact:true}).click();
  assert.match(await page.locator("body").innerText(),/poor accuracy: 10/);
  assert.doesNotMatch(await page.locator("body").innerText(),/Fully synchronized/);
  await page.getByText("poor accuracy: 10",{exact:true}).scrollIntoViewIfNeeded();
  await page.screenshot({path:path.join(out,"history-quality-rejection.png"),fullPage:true});
  await send(missing,"history");
  await wait(async()=>!(await state()).projection_pending && (await state()).points.length===40);
  st=await state();
  assert.equal(st.devices[devices[1]].reconstruction.known_missing_sequences,0);
  assert.equal(st.devices[devices[1]].reconstruction.qualified_segments,2);
  assert.ok(Math.abs(st.devices[devices[1]].total_m-80)<1e-6);
  await page.reload();
  await page.waitForFunction(()=>document.querySelectorAll('polyline[data-qualified-points]').length===8);
  await menuAction(page, "fit");
  await page.screenshot({path:path.join(out,"history-five-devices-completed.png"),fullPage:true});
  // From Now persists the original-time boundary; late old data is receipt only.
  await post("recording",{action:"clear",confirmed:true});
  await post("recording",{action:"start",mode:"from_now"});
  await send(original[0],"history");
  await wait(async()=>!(await state()).projection_pending);
  st=await state();assert.equal(st.points.length,0);
  assert.equal(st.devices[devices[0]].reconstruction.route_condition,"excluded_by_recording_boundaries");
  await page.reload();
  await page.getByRole("button",{name:"Alpha",exact:true}).click();
  await page.waitForFunction(()=>document.body.textContent.includes("History outside recording boundaries"));
  await page.screenshot({path:path.join(out,"history-from-now-boundaries.png"),fullPage:true});
  assert.deepEqual(errors,[]);
  console.log("PASS: real SQLite/HTTP and Chromium: zero-inventory start, 50 retained observations, five devices/ten sessions, scrambled 1/2/7-day history, duplicate ACK, missing-sequence sections/reconciliation, exact qualified SVG point counts, rejection reasons, unknown phone backlog and From Now boundaries.");
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(async()=>{
 if(browser)await browser.close();
 if(command){command.kill("SIGINT");await once(command,"exit");}
});
