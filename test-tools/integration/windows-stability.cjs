/* Linux/Windows-capable software regression, not native/physical acceptance.
 * Real receipt/projection/HTTP; fault injection affects only browser history. */
"use strict";
const assert = require("node:assert/strict"), fs = require("node:fs"), os = require("node:os"), path = require("node:path"), net = require("node:net");
const {spawn} = require("node:child_process"), {once} = require("node:events");
const {chromium} = require(process.env.GNSS_PLAYWRIGHT_MODULE || "playwright");
const fixture = JSON.parse(fs.readFileSync(path.join(__dirname,"../../protocol/fixtures/history-batch-v1/request.json"))).messages[0];
const work = fs.mkdtempSync(path.join(os.tmpdir(),"gnss-stability-"));
let command, browser;
const report = {source: process.env.SOURCE_REVISION || "working-tree", physical_network:false, native_windows:process.platform === "win32"};
async function port(){const s=net.createServer();s.listen(0,"127.0.0.1");await once(s,"listening");const p=s.address().port;await new Promise(r=>s.close(r));return p;}
async function wait(check){for(let i=0;i<300;i++){if(await check())return;await new Promise(r=>setTimeout(r,100));}throw Error("Timed out");}
(async()=>{
 const phone=`http://127.0.0.1:${await port()}`,local=`http://127.0.0.1:${await port()}`;
 command=spawn(process.env.GNSS_COMMAND_BINARY,["-db",path.join(work,"command.sqlite"),"-ingest-listen",phone.slice(7),"-dashboard-listen",local.slice(7)],{stdio:"ignore"});
 await wait(async()=>{try{return(await fetch(local+"/local/live")).ok}catch{return false}});
 async function post(url,body){const r=await fetch(url,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body)});assert.equal(r.status,200,await r.text());}
 await post(local+"/local/recording",{action:"start",mode:"from_beginning"});
 const start=Date.now()-3600000;
 for(let d=1;d<=5;d++)for(let n=1;n<=420;){
  const messages=[];
  for(let i=0;i<40&&n<=420;i++,n++){
   const m=structuredClone(fixture),id=`00000000-0000-4000-8000-${String(n).padStart(12,"0")}`;
   m.device_id=`11111111-1111-4111-8111-${String(d).padStart(12,"0")}`;m.party.id=`Phone ${d}`;m.message_id=id;m.sequence=n;
   m.captured_at=m.fix.observed_at=new Date(start+n*1000).toISOString();m.fix.latitude=d/100;m.fix.longitude=n*5/6371008.8*180/Math.PI;
   m.observation={observation_id:id,tracking_session_id:`aaaaaaaa-aaaa-4aaa-8aaa-${String(d).padStart(12,"0")}`,session_started_at:new Date(start).toISOString(),observation_sequence:n,measurement_elapsed_ms:n*1000};messages.push(m);
  }
  await post(phone+"/api/v1/history",{batch_version:1,messages});
 }
 await wait(async()=>!(await(await fetch(local+"/local/live")).json()).projection_pending);
 browser=await chromium.launch({executablePath:process.env.GNSS_CHROMIUM||undefined,headless:true,args:["--no-sandbox"]});
 const page=await browser.newPage({viewport:{width:1280,height:900}}),errors=[];page.on("pageerror",e=>errors.push(e.message));
 await page.addInitScript(()=>{window.testIntervals=[];const interval=window.setInterval;window.setInterval=(...args)=>{const id=interval(...args);window.testIntervals.push(id);return id;};});
 await page.goto(local);await page.waitForFunction(()=>document.querySelector("#tracks polyline[data-device]"));
 await page.evaluate(()=>window.testIntervals.forEach(clearInterval));await page.waitForFunction(()=>!polling&&!livePolling);
 report.points=await page.evaluate(()=>state.points.length);
 // Fixed workload, explicit refreshes. Count actual history node replacement,
 // and time the complete production draw, including unchanged history handling.
 report.redraw=await page.evaluate(async()=>{
  const times=[];let replacements=0;const node=document.querySelector("#tracks polyline[data-device]");
  for(let i=0;i<10;i++){const t=performance.now();draw();times.push(performance.now()-t);if(!node.isConnected)replacements++;}
  times.sort((a,b)=>a-b);return {calls:times.length,history_replacements:replacements,p50_ms:times[5],p95_ms:times[9]};
 });
 // A history service failure must not override successful independent LIVE.
 await page.route("**/local/state?**",r=>r.fulfill({status:503,contentType:"application/json",body:JSON.stringify({message:"injected history read failure"})}));
 report.history_failure_connection=await page.evaluate(async()=>{await pollLive();await poll();return document.querySelector("#connection").textContent;});
 await page.unroute("**/local/state?**");
 // LIVE failure must be visible even when history keeps succeeding.
 await page.route("**/local/live",r=>r.abort());
 report.live_failure_connection=await page.evaluate(async()=>{await poll();await pollLive();return document.querySelector("#connection").textContent;});
 await page.unroute("**/local/live");
 await page.evaluate(()=>pollLive());
 // A stale, equal-receipt history reply must not restore old contact/GNSS ages.
 let releaseHistory, historyEntered;
 const entered = new Promise(r=>historyEntered=r);
 await page.route("**/local/state?**",async route=>{
  const response=await route.fetch();historyEntered();
  await new Promise(r=>releaseHistory=r);await route.fulfill({response});
 });
 await page.evaluate(()=>{window.heldHistory=poll();});await entered;
 const liveAge=await page.evaluate(async()=>{await pollLive();return Object.values(state.devices)[0].location_age_s;});
 releaseHistory();await page.evaluate(()=>window.heldHistory);
 report.stale_history_reverted=await page.evaluate(age=>Object.values(state.devices)[0].location_age_s!==age,liveAge);
 await page.unroute("**/local/state?**");
 // Later history must still age contact after the newest LIVE read.
 report.later_history_updates_age=await page.evaluate(async()=>{
  const age=Object.values(state.devices)[0].location_age_s;await new Promise(r=>setTimeout(r,30));await poll();return Object.values(state.devices)[0].location_age_s>age;
 });
 // Viewport and keyboard inspection survive an ordinary LIVE refresh.
 report.history_preserved=await page.evaluate(async()=>{
  draw();const node=document.querySelector("#tracks polyline[data-device]");const view=JSON.stringify(viewport);await pollLive();return node.isConnected&&JSON.stringify(viewport)===view;
 });
 if(process.env.GNSS_STABILITY_BASELINE!=="1") {
  let blocked;
  await page.route("**/local/state?**",route=>{blocked=route;});
  report.history_timeout=await page.evaluate(async()=>{const began=performance.now();await poll();return {elapsed_ms:performance.now()-began,polling};});
  assert.equal(report.history_timeout.polling,false);assert(report.history_timeout.elapsed_ms<6000);
  await blocked.abort().catch(()=>{});await page.unroute("**/local/state?**");
  await page.evaluate(()=>pollLive());
  report.invalidation=await page.evaluate(()=>{
   const node=document.querySelector("#tracks polyline[data-device]");viewport.cx+=10;draw();const moved=!node.isConnected;
   const next=document.querySelector("#tracks polyline[data-device]");state.points[0].dot=!state.points[0].dot;draw();return moved&&!next.isConnected;
  });
  assert.equal(report.invalidation,true);
  const download=page.waitForEvent("download");await page.evaluate(()=>document.querySelector("#export-runtime").click());
  const file=await(await download).path();const exported=JSON.parse(fs.readFileSync(file));
  assert(exported.command.timings.http_ingest_history.count>0);assert(exported.browser.timings.draw.count>0);
  assert(!JSON.stringify(exported).includes("latitude"));report.runtime_export=true;
  const oldView=await(await fetch(local+"/local/state")).json();oldView.raw_points=oldView.points.slice(0,1);
  await post(local+"/local/recording",{action:"stop"});await page.evaluate(()=>pollLive());
  await page.route("**/local/state?**",route=>route.fulfill({status:200,contentType:"application/json",body:JSON.stringify(oldView)}));
  report.old_recording_cleared=await page.evaluate(async()=>{await poll();return state.points.length===0&&state.raw_points.length===0&&!state.recording.active;});
  assert.equal(report.old_recording_cleared,true);await page.unroute("**/local/state?**");
 }
 if(process.env.GNSS_STABILITY_BASELINE!=="1"){
  assert.equal(report.redraw.history_replacements,0);assert.match(report.history_failure_connection,/Connected/);
  assert.match(report.live_failure_connection,/unavailable/i);assert.equal(report.stale_history_reverted,false);assert.equal(report.history_preserved,true);assert.equal(report.later_history_updates_age,true);assert.deepEqual(errors,[]);
 }
 report.browser_errors=errors;report.passed=process.env.GNSS_STABILITY_BASELINE!=="1";
})().catch(e=>{report.error=e.stack;process.exitCode=1;}).finally(async()=>{
 if(browser)await browser.close();if(command&&command.exitCode===null){command.kill("SIGINT");await once(command,"exit");}
 if(process.env.GNSS_STABILITY_REPORT)fs.writeFileSync(process.env.GNSS_STABILITY_REPORT,JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
 if(!process.exitCode)fs.rmSync(work,{recursive:true,force:true});else console.error("Failed fixture retained",work);
});
