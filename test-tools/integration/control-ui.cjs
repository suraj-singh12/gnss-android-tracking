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
const { randomUUID, randomBytes, createHmac } = require("node:crypto");
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
    const raw=await r.text();assert.equal(r.status, 200, raw);return JSON.parse(raw);
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
  const publicIdentity=(await (await fetch(local+"/local/control")).json()).authority_id;
  assert.match(publicIdentity,/^[0-9a-f-]{36}$/);
  assert.equal(await page.locator("#control-authority").textContent(),`Command ID: ${publicIdentity}`);
  await page.context().grantPermissions(["clipboard-read","clipboard-write"]);
  await page.locator("#control-copy-authority").click();
  assert.equal(await page.evaluate(()=>navigator.clipboard.readText()),publicIdentity);
  assert.equal((await page.locator("#control-authority").textContent()).includes("gnss1:"),false);
  let provisioningPrompts=0;
  const credentials=new Map(), confirmations=[];
  page.on("dialog",async dialog=>{if(dialog.type()==="prompt"){provisioningPrompts++;assert.match(dialog.defaultValue(),/^gnss1:/);credentials.set(dialog.defaultValue().split(":")[2],dialog.defaultValue());}else confirmations.push(dialog.message());await dialog.accept();});
  await page.locator("#control-phones button").first().click();
  await wait(async()=>provisioningPrompts===1);
  // Enroll remaining phones through the same production local API.
  for(const id of ids){const v=await (await fetch(local+"/local/control")).json();if(!v.phones[id]){const enrolled=await post("control/enroll",{device:id,confirmed:true});credentials.set(id,enrolled.credential);}}
  await page.locator("#control-all").click();
  await wait(async()=>await page.locator("#control-phones input:checked").count()===20);
  await page.locator("#control-operation").selectOption("sync");
  await page.locator("#control-issue").click();
  await wait(async()=>((await (await fetch(local+"/local/control")).json()).commands.length===20));
  // Interrupt real LIVE/inventory requests while a new enrolled phone arrives.
  // All recipients must come from fresh enrollment, not stale GNSS inventory.
  // Abort rather than hold these requests beyond the product's 4-second timeout.
  const holdInventory=async route=>{await route.abort("connectionreset");};
  await page.route("**/local/live",holdInventory);
  await page.route("**/local/state?**",holdInventory);
  await page.waitForFunction(()=>!livePolling&&!polling); // Settle any request already in flight.
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
  await page.locator("#control-operation").selectOption("alert");
  await page.locator("#control-alert-mode").selectOption("normal");
  await page.locator("#control-alert-message").fill("Check in with Command");
  // Normal mode must ignore stale invalid urgent-only fields.
  await page.locator("#control-alert-duration").fill("121");
  await page.locator("#control-alert-repeat").fill("0");
  await page.locator("#control-issue").click();
  await wait(async()=>((await (await fetch(local+"/local/control")).json()).commands.filter(c=>c.operation==="alert").length===2));
  // Delay the real control response to prove issuance cannot race All selection.
  let selectionHeld=false, releaseSelection;
  const selectionGate=new Promise(resolve=>{releaseSelection=resolve;});
  await page.route("**/local/control",async route=>{selectionHeld=true;await selectionGate;await route.continue();});
  await page.locator("#control-all").click();
  await wait(async()=>selectionHeld);
  assert.equal(await page.locator("#control-issue").isDisabled(),true);
  assert.equal(await page.locator("#control-none").isDisabled(),true);
  releaseSelection();
  await wait(async()=>await page.locator("#control-phones input:checked").count()===21&&await page.locator("#control-issue").isEnabled());
  await page.unroute("**/local/control");
  assert.match(await page.locator("#control-status").textContent(),/21 enrolled recipients selected/);
  assert.equal(await page.evaluate(()=>Object.keys(state.devices).length),20);
  await page.unroute("**/local/live");
  await page.unroute("**/local/state?**");
  await page.locator("#control-alert-mode").selectOption("urgent");
  await page.locator("#control-alert-duration").fill("60");
  await page.locator("#control-alert-repeat").fill("10");
  await page.locator("#control-issue").click();
  await wait(async()=>((await (await fetch(local+"/local/control")).json()).commands.filter(c=>c.operation==="alert").length===23));
  controls=await (await fetch(local+"/local/control")).json();
  assert.equal(controls.commands.filter(c=>c.priority==="urgent").length,21);
  assert.ok(controls.commands.filter(c=>c.priority==="urgent").every(c=>c.payload.duration_s===60&&c.payload.repeat_s===10));
  assert.ok(confirmations.some(text=>text.includes("21 frozen recipients:")&&ids.every(id=>text.includes(id))));
  // Synthetic authenticated phone evidence goes through the actual control API;
  // physical output/human action is covered separately, never inferred from UI.
  const alert=controls.commands.find(c=>c.priority==="urgent"&&credentials.has(c.device));
  const parts=credentials.get(alert.device).split(":");
  async function phoneResult(state,revision){
    const result={id:alert.id,revision,state,reason:state==="recipient_acknowledged"?"Recipient local action (synthetic browser fixture)":"Notification attempted; audible output unknown",effective:state==="recipient_acknowledged"?"Local user action at "+new Date().toISOString():"OS output unknown",received:Date.now(),attempted:true};
    const data=Buffer.from(JSON.stringify({version:1,authority:parts[1],nonce:randomBytes(32).toString("hex"),at:Date.now(),results:[result],results_only:true})).toString("base64");
    const mac=createHmac("sha256",Buffer.from(parts[4],"hex")).update(`gnss-control-v1\nphone\n${parts[2]}\n${parts[3]}\n${data}`).digest("hex");
    const reply=await fetch(phone+"/api/v1/control",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({device:parts[2],enrollment:parts[3],data,mac})});
    assert.equal(reply.status,200,await reply.text());
  }
  await phoneResult("output_attempted",2);
  await page.evaluate(()=>refreshControl());
  const alertDetail=page.locator(`#control-history details[data-command-id="${alert.id}"]`);
  await alertDetail.locator("summary").click();
  assert.match(await alertDetail.textContent(),/heard\/felt unknown/);
  await alertDetail.getByText("Cancel safely",{exact:true}).click();
  await wait(async()=>((await (await fetch(local+"/local/control")).json()).commands.find(c=>c.id===alert.id).state==="cancel_requested_unknown"));
  await phoneResult("recipient_acknowledged",3);
  await page.evaluate(()=>refreshControl());
  assert.match(await alertDetail.textContent(),/Human recipient locally acknowledged/);
  await post("control/cancel",{id:alert.id});
  assert.equal((await (await fetch(local+"/local/control")).json()).commands.find(c=>c.id===alert.id).state,"recipient_acknowledged");
  await alertDetail.scrollIntoViewIfNeeded();
  await page.screenshot({path:path.join(out,"command-phone-alert-human-ack.png"),fullPage:true});
  await page.locator("#phone-control").evaluate(dialog=>dialog.scrollTop=0);
  await page.screenshot({path:path.join(out,"command-phone-controls.png"),fullPage:true});
  await page.evaluate(()=>{document.documentElement.dataset.appearance="night";});
  await page.screenshot({path:path.join(out,"command-phone-controls-night.png"),fullPage:true});
  await page.evaluate(()=>{document.documentElement.dataset.appearance="day";});
  const accessibility=await new AxeBuilder({page}).withTags(["wcag2a","wcag2aa","wcag21aa"]).analyze();
  fs.writeFileSync(path.join(out,"control-accessibility.json"),JSON.stringify(accessibility.violations,null,2));
  assert.deepEqual(accessibility.violations.map(v=>v.id),[]);assert.deepEqual(errors,[]);
  console.log("PASS: real Command browser enrollment, 20 frozen all recipients plus late 21st, selected recipients, guarded tracking request, normal/urgent alerts to selected/all 21 phones, cancellation, unknown execution and accessible controls.");
})().catch(error=>{console.error(error);process.exitCode=1;}).finally(async()=>{if(browser)await browser.close();if(command&&command.exitCode===null){command.kill("SIGINT");await once(command,"exit");}if(!process.exitCode)fs.rmSync(work,{recursive:true,force:true});});
