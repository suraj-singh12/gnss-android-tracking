/* Independent, read-only operator UX audit against an exact Command revision.
   Runs on an isolated database; fixtures contain no real phone identities.
   This is screenshot and geometry evidence, not physical-device certification. */
"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const net = require("node:net");
const { spawn } = require("node:child_process");
const { once } = require("node:events");
const { randomUUID } = require("node:crypto");
const { chromium } = require(process.env.GNSS_PLAYWRIGHT_MODULE || "playwright");
const work = fs.mkdtempSync(path.join(os.tmpdir(), "gnss-independent-qa-"));
const out = process.env.GNSS_QA_REPORT || path.join(work, "report");
const target = process.env.GNSS_QA_TARGET || process.cwd();
const browserBinary = process.env.GNSS_COMMAND_BINARY;
assert(browserBinary, "GNSS_COMMAND_BINARY must point to the exact-source built Command");
fs.mkdirSync(out, { recursive: true });
const report = {
  source_revision: process.env.SOURCE_REVISION || "unverified",
  kind: "independent-operator-browser-qa",
  fixtures: "synthetic",
  physical_or_native_acceptance: false,
  genuine_browser_zoom: false,
  zoom_matrix: "CSS zoom approximation; actual browser chrome zoom requires manual verification",
  states: [],
  browser_errors: [],
  accessibility: [],
  observations: [],
};
let command, browser;
async function freePort() {
  const s = net.createServer();
  s.listen(0, "127.0.0.1");
  await once(s, "listening");
  const n = s.address().port;
  await new Promise((r) => s.close(r));
  return n;
}
async function until(check, label) {
  for (let n = 0; n < 150; n++) {
    if (await check().catch(() => false)) return;
    await new Promise((r) => setTimeout(r, 200));
  }
  throw Error("Timed out: " + label);
}
async function examine(page, label) {
  const sizes = [[960,720],[1280,720],[1440,900],[1920,1080],[700,720],[390,844]];
  const modes = ["both-open","parties-closed","details-closed","both-closed"];
  for (const [width,height] of sizes) {
    await page.setViewportSize({ width, height });
    for (const mode of modes) {
      await page.evaluate((mode) => {
        const el = document.querySelector("main");
        el.classList.toggle("parties-closed", mode.includes("parties-closed") || mode === "both-closed");
        el.classList.toggle("details-closed", mode.includes("details-closed") || mode === "both-closed");
        window.dispatchEvent(new Event("resize"));
      }, mode);
      await page.waitForTimeout(80);
      const measurement = await page.evaluate(() => {
        const rect = (el) => {
          if (!el) return null;
          const v = el.getBoundingClientRect();
          return {x:v.x,y:v.y,width:v.width,height:v.height,right:v.right,bottom:v.bottom};
        };
        const h = document.querySelector("header");
        const bar = document.querySelector(".recording-bar");
        const map = document.querySelector("#tracks");
        const toolbar = [...document.querySelectorAll("header .header-tools > *")]
          .filter((el) => getComputedStyle(el).display !== "none" && rect(el).width > 0)
          .map(el => ({id:el.id||el.textContent.trim().slice(0,32), rect:rect(el)}));
        const rows = new Set(toolbar.map(item=>Math.round(item.rect.y/3)*3));
        const collisions = [];
        for(let i=0;i<toolbar.length;i++) for(let j=i+1;j<toolbar.length;j++) {
          const a=toolbar[i].rect,b=toolbar[j].rect;
          if(Math.min(a.right,b.right)-Math.max(a.x,b.x)>2 &&
             Math.min(a.bottom,b.bottom)-Math.max(a.y,b.y)>2)
             collisions.push([toolbar[i].id,toolbar[j].id]);
        }
        return {
          header:rect(h), recording_bar:rect(bar), map:rect(map),
          header_rows:rows.size, header_collisions:collisions,
          toolbar:toolbar.map(el=>el.id),
          map_area_ratio:map ? Number((rect(map).width*rect(map).height/(innerWidth*innerHeight)).toFixed(3)) : 0,
          document_horizontal_overflow:document.documentElement.scrollWidth>innerWidth+2,
          sos_header_visible: !!(document.querySelector("#open-sos") && rect(document.querySelector("#open-sos")).width),
          audio_header_visible: !!(document.querySelector("#sos-audio") && rect(document.querySelector("#sos-audio")).width),
        };
      });
      const file=label+"-"+width+"x"+height+"-"+mode+".png";
      await page.screenshot({path:path.join(out,file), animations:"disabled"});
      const row={label,width,height,mode,screenshot:file,...measurement};
      report.states.push(row);
      if(measurement.header_rows>1) report.observations.push({type:"toolbar_wrapping",label,width,mode,rows:measurement.header_rows});
      if(measurement.header_collisions.length) report.observations.push({type:"toolbar_collisions",label,width,mode,pairs:measurement.header_collisions});
      if(measurement.document_horizontal_overflow) report.observations.push({type:"horizontal_overflow",label,width,mode});
      if(measurement.recording_bar?.height>60) report.observations.push({type:"tall_recording_bar",label,width,mode,height:measurement.recording_bar.height});
      if(!measurement.sos_header_visible || !measurement.audio_header_visible) report.observations.push({type:"safety_header_visibility",label,width,mode});
    }
  }
  await page.evaluate(() => document.querySelector("main").classList.remove("parties-closed","details-closed"));
}
(async () => {
  const ingest="http://127.0.0.1:" + await freePort();
  const dashboard="http://127.0.0.1:" + await freePort();
  const logfile=fs.openSync(path.join(out,"command.log"),"a");
  command=spawn(browserBinary,["-db",path.join(work,"command.sqlite"),"-ingest-listen",ingest.slice(7),"-dashboard-listen",dashboard.slice(7)],{stdio:["ignore","ignore",logfile]});
  await until(async()=> (await fetch(dashboard+"/local/state")).ok, "Command start");
  browser=await chromium.launch({headless:true,args:["--no-sandbox"]});
  const page=await browser.newPage({viewport:{width:1280,height:720},timezoneId:"Asia/Kolkata"});
  page.setDefaultTimeout(8000);
  page.on("pageerror",e=>report.browser_errors.push(e.message));
  await page.goto(dashboard);
  await until(async()=> /connected/i.test(await page.locator("#connection").innerText()),"browser connection");
  // A real first-run/empty dashboard and actual user-opened library drawer.
  await page.locator("#open-library").click();
  await page.screenshot({path:path.join(out,"library-empty-day-1280.png")});
  await page.locator('[data-close="saved-library"]').click();
  // Present five synthetic phones through the real ingest endpoint.
  const fixture=JSON.parse(fs.readFileSync(path.join(target,"protocol/fixtures/location-normal.json"),"utf8"));
  for (let i=0;i<5;i++) {
    const m=structuredClone(fixture);
    m.device_id=randomUUID();
    m.message_id=randomUUID();
    m.sequence=1;
    m.party.id=["Alpha","Bravo","Charlie","Delta","Echo"][i];
    m.party.name="QA fixture "+(i+1);
    m.captured_at=m.fix.observed_at=new Date(Date.now()-1500).toISOString();
    m.fix.latitude+=i*.00045;
    m.fix.longitude+=i*.00045;
    const response=await fetch(ingest+"/api/v1/messages",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(m)});
    assert.equal(response.status,200,"5-phone fixture ingest must succeed");
  }
  await page.evaluate(()=>poll());
  await until(async()=> (await page.locator("#party-summary").textContent()).includes("/5"),"five party summary");
  for (const theme of ["day","night"]) {
    if(theme==="night")await page.locator("#appearance").click();
    await examine(page,"live-"+theme);
    await page.setViewportSize({width:1280,height:720});
    await page.locator("#open-library").click();
    await page.screenshot({path:path.join(out,"library-empty-"+theme+"-1280.png")});
    await page.locator('[data-close="saved-library"]').click();
    await page.locator("#open-sos").click();
    await page.screenshot({path:path.join(out,"sos-drawer-"+theme+"-1280.png")});
    await page.locator('[data-close="sos-panel"]').click();
    await page.locator("#open-map").click();
    await page.screenshot({path:path.join(out,"map-dialog-"+theme+"-1280.png")});
    await page.locator('[data-close="map-settings"]').click();
  }
  // Zoom approximation is isolated and explicitly NOT labeled real browser zoom.
  await page.locator("#appearance").click();
  for (const scale of [1.25,1.5]) {
    await page.setViewportSize({width:1280,height:720});
    await page.evaluate(v=>document.documentElement.style.zoom=String(v),scale);
    await page.waitForTimeout(80);
    await page.screenshot({path:path.join(out,"css-zoom-approx-"+scale+"-1280.png")});
    report.observations.push({
      type:"zoom_approximation",scale,
      horizontal_overflow:await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+2)
    });
  }
  await page.evaluate(()=>document.documentElement.style.zoom="");
  await page.setViewportSize({width:1280,height:720});
  await page.keyboard.press("Tab");
  report.keyboard_first_tab=await page.evaluate(()=>({id:document.activeElement?.id,tag:document.activeElement?.tagName}));
  try {
    const AxeBuilder=require(process.env.GNSS_AXE_MODULE||"@axe-core/playwright").default;
    const findings=await new AxeBuilder({page}).analyze();
    report.accessibility=findings.violations.map(v=>({id:v.id,impact:v.impact,description:v.description,nodes:v.nodes.length}));
  } catch(e) {
    report.observations.push({type:"accessibility_audit_unavailable",error:e.message});
  }
  report.passed=true;
})().catch(async e=>{
  report.passed=false; report.error=e.stack;
  process.exitCode=1;
}).finally(async()=>{
  fs.writeFileSync(path.join(out,"operator-qa-report.json"),JSON.stringify(report,null,2));
  console.log(JSON.stringify({source:report.source_revision,passed:report.passed,states:report.states.length,observations:report.observations.length,accessibility:report.accessibility,error:report.error},null,2));
  if(browser)await browser.close();
  if(command)command.kill();
});
