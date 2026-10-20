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
let command, browser, diagnosticPage;
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
  diagnosticPage = page;
  page.on("pageerror", (e) => {
    errors.push(e.message);
    console.error("Browser:", e.message);
  });
  await page.goto(local);
  await page.waitForFunction(()=>document.querySelector("#connection").textContent.includes("Command Connected"));
  const fixture=JSON.parse(fs.readFileSync(path.join(__dirname,"../../protocol/fixtures/location-native-session.json"),"utf8"));
  const ids=[];
  for(let i=0;i<20;i++){const id=randomUUID();ids.push(id);const message=structuredClone(fixture);message.device_id=id;message.message_id=randomUUID();message.observation.observation_id=message.message_id;message.observation.tracking_session_id=randomUUID();message.party.id=`Remote ${i+1}`;await send(message);}
  await wait(async()=>Object.keys((await state()).devices).length===20);
  await page.waitForFunction(()=>document.querySelectorAll(".device").length===20);
  await page.locator("#open-control").click();
  await page.waitForFunction(()=>document.querySelectorAll("#control-phones label").length===20);
  let provisioningPrompts=0;
  page.on("dialog",async dialog=>{if(dialog.type()==="prompt"){provisioningPrompts++;assert.match(dialog.defaultValue(),/^gnss1:/);}await dialog.accept();});
  await page.locator("#control-phones button").first().click();
  await wait(async()=>provisioningPrompts===1);
  // Enroll remaining phones through the same production local API.
  for(const id of ids){const v=await (await fetch(local+"/local/control")).json();if(!v.phones[id])await post("control/enroll",{device:id,confirmed:true});}
  await page.locator("#control-all").click();
  await wait(async()=>await page.locator("#control-phones input:checked").count()===20);
  await page.locator("#control-operation").selectOption("sync");
  await page.locator("#control-issue").click();
  await wait(async()=>((await (await fetch(local+"/local/control")).json()).commands.length===20));
  const late=structuredClone(fixture);late.device_id=randomUUID();late.message_id=randomUUID();late.observation.observation_id=late.message_id;late.observation.tracking_session_id=randomUUID();await send(late);await post("control/enroll",{device:late.device_id,confirmed:true});
  let controls=await (await fetch(local+"/local/control")).json();assert.equal(controls.commands.some(c=>c.device===late.device_id),false);
  assert.ok(controls.commands.every(c=>c.state==="queued"&&c.result===null));
  await page.locator("#control-none").click();
  await wait(async()=>await page.locator("#control-phones input:checked").count()===0);
  await page.locator("#control-phones input").nth(0).check();await page.locator("#control-phones input").nth(1).check();
  await page.locator("#control-operation").selectOption("tracking_stop");await page.locator("#control-issue").click();
  await wait(async()=>((await (await fetch(local+"/local/control")).json()).commands.length===22));
  await page.locator("#control-history details").first().locator("summary").click();
  await page.evaluate(()=>refreshControl());
  assert.equal(await page.locator("#control-history details").first().evaluate(detail=>detail.open),true);
  await page.locator("#control-history details").first().getByText("Cancel safely",{exact:true}).click();
  await wait(async()=>((await (await fetch(local+"/local/control")).json()).commands.some(c=>c.state==="cancelled")));
  await page.locator("#phone-control").evaluate(dialog=>dialog.scrollTop=0);
  await page.screenshot({path:path.join(out,"command-phone-controls.png"),fullPage:true});
  const accessibility=await new AxeBuilder({page}).withTags(["wcag2a","wcag2aa","wcag21aa"]).analyze();
  fs.writeFileSync(path.join(out,"control-accessibility.json"),JSON.stringify(accessibility.violations,null,2));
  assert.deepEqual(accessibility.violations.map(v=>v.id),[]);assert.deepEqual(errors,[]);
  console.log("PASS: real Command browser enrollment, 20 frozen all recipients plus late 21st, selected recipients, guarded tracking request, cancellation, unknown execution and accessible controls.");
})().catch(error=>{console.error(error);process.exitCode=1;}).finally(async()=>{if(browser)await browser.close();if(command&&command.exitCode===null){command.kill("SIGINT");await once(command,"exit");}if(!process.exitCode)fs.rmSync(work,{recursive:true,force:true});});
