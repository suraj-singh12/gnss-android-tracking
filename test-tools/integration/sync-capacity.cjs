/* Real Command process, HTTP, SQLite and Chromium. Streaming original identities;
 * no Android/Room or physical-radio claims. Opt-in density has an explicit budget. */
"use strict";
const assert = require("node:assert/strict"), fs = require("node:fs"), os = require("node:os"), path = require("node:path"), net = require("node:net");
const {spawn, spawnSync} = require("node:child_process"), {once} = require("node:events");
const {chromium} = require(process.env.GNSS_PLAYWRIGHT_MODULE || "playwright");
const fixture = JSON.parse(fs.readFileSync(path.join(__dirname,"../../protocol/fixtures/history-batch-v1/request.json"))).messages[0];
const phones = Number(process.env.GNSS_SYNC_DEVICES || 20), samples = Number(process.env.GNSS_SYNC_SAMPLES || 600), days = Number(process.env.GNSS_SYNC_DAYS || 7);
const budget = Number(process.env.GNSS_SYNC_MAX_RECORDS || 2000000);
assert(phones > 0 && samples > 0 && Number.isSafeInteger(phones*samples) && (phones+(phones>=20?1:0))*samples <= budget, "Set a deliberate GNSS_SYNC_MAX_RECORDS resource budget");
const work = process.env.GNSS_SYNC_WORK || fs.mkdtempSync(path.join(os.tmpdir(),"gnss-sync-capacity-"));
fs.mkdirSync(work,{recursive:true});
const database=path.join(work,"command.sqlite"), report={source_revision:process.env.SOURCE_REVISION||"working-tree",phones,samples_per_phone:samples,days,android_room:false,physical_network:false,harness_sha256:require("node:crypto").createHash("sha256").update(fs.readFileSync(__filename)).digest("hex"),source_bytes:0,request_body_bytes:0,ack_body_bytes:0,history_requests:0,peak_command_rss_bytes:0,peak_db_bytes:0,peak_wal_bytes:0};
let command,browser,timer,active=0,late,lateStarted=false,completed=0;
const configuredDiskBudget=Number(process.env.GNSS_SYNC_MAX_DB_BYTES || 16*1024**3);
const space=fs.statfsSync(work);
const availableDiskBytes=Number(space.bavail)*Number(space.bsize);
// A configured budget cannot grant storage the selected filesystem lacks.
const diskBudget=Math.min(configuredDiskBudget,Math.floor(availableDiskBytes*0.8));
const rssBudget=Number(process.env.GNSS_SYNC_MAX_RSS_BYTES || 2*1024**3);
const expected = new Map(), extras = new Map(), historyRTT=[], liveRTT=[], sosRTT=[], visible=[], receiptVisible=[], receipts=new Map();
const startedAt=Date.now(), sourceStart=startedAt-days*86400000;
const device=d=>`11111111-1111-4111-8111-${String(d).padStart(12,"0")}`;
const identity=n=>`00000000-0000-4000-8000-${String(n).padStart(12,"0")}`;
const stamp=t=>new Date(t).toISOString();
function observation(d,n,fresh=false){
 const m=structuredClone(fixture), at=fresh?Date.now():sourceStart+Math.floor(n*days*86400000/samples);
 m.device_id=device(d);m.party.id=`Phone ${d}`;m.message_id=identity(n);m.sequence=n;m.captured_at=stamp(at);
 m.fix.observed_at=m.captured_at;m.fix.latitude=0;m.fix.longitude=n*5/6371008.8*180/Math.PI;
 m.observation={observation_id:m.message_id,tracking_session_id:`aaaaaaaa-aaaa-4aaa-8aaa-${String(d).padStart(12,"0")}`,session_started_at:stamp(sourceStart),observation_sequence:n,measurement_elapsed_ms:Math.max(1,at-sourceStart)};
 return m;
}
async function port(){const s=net.createServer();s.listen(0,"127.0.0.1");await once(s,"listening");const p=s.address().port;await new Promise(r=>s.close(r));return p;}
async function wait(check,timeout=60000,interval=50){const end=Date.now()+timeout;while(Date.now()<end){if(await check())return;await new Promise(r=>setTimeout(r,interval));}throw Error("Timed out waiting for acceptance state");}
function sample(){
 if (!command) return;
 try{const text=fs.readFileSync(`/proc/${command.pid}/status`,"utf8"),m=text.match(/^VmRSS:\s+(\d+)/m);if(m)report.peak_command_rss_bytes=Math.max(report.peak_command_rss_bytes,Number(m[1])*1024);}catch{}
 for(const [file,key] of [[database,"peak_db_bytes"],[database+"-wal","peak_wal_bytes"]])try{report[key]=Math.max(report[key],fs.statSync(file).size);}catch{}
 if ((report.peak_db_bytes+report.peak_wal_bytes>diskBudget || report.peak_command_rss_bytes>rssBudget) && !report.blocked) {
   report.blocked=true;report.resource_budget="Stopped at explicit DB/WAL or Command RSS budget; acceptance not verified";command.kill("SIGINT");
 }
}
function percentiles(a){if(!a.length)return null;const v=[...a].sort((a,b)=>a-b);return {count:v.length,p50_ms:v[Math.floor(v.length*.5)],p95_ms:v[Math.min(v.length-1,Math.floor(v.length*.95))],p99_ms:v[Math.min(v.length-1,Math.floor(v.length*.99))],worst_ms:v.at(-1)};}
function inspectDerivedCache(){
 const inspected=spawnSync("python3",["-c",`import sqlite3,json,sys
c=sqlite3.connect('file:'+sys.argv[1]+'?mode=ro',uri=True);c.execute('BEGIN')
s=json.loads(c.execute('SELECT data FROM state WHERE id=1').fetchone()[0]);h=json.loads(c.execute('SELECT data FROM projection_state WHERE id=1').fetchone()[0]);parts=[]
for d,g in h.get('generations',{}).items():
 n,lo,hi=c.execute('SELECT COUNT(*),MIN(ordinal),MAX(ordinal) FROM projection_device_chunks WHERE device=? AND generation=? AND ordinal>=0',(d,g)).fetchone()
 preview=c.execute('SELECT COUNT(*) FROM projection_device_chunks WHERE device=? AND generation=? AND ordinal=-1 AND json_valid(data)',(d,g)).fetchone()[0]
 expected=h.get('chunks',{}).get(d,0);parts.append({'device':d,'expected_chunks':expected,'stored_chunks':n,'first':lo,'last':hi,'valid':preview==1 and n==expected and (n==0 or (lo==0 and hi==n-1))})
print(json.dumps({'pending':s['projection_pending'],'error':s.get('projection_error',''),'work':c.execute('SELECT device,generation FROM projection_work').fetchall(),'partitions':parts,'windows':len(s['recording']['windows']) if s.get('recording') else None}))
c.rollback();c.close()`,database],{encoding:"utf8"});assert.equal(inspected.status,0,inspected.stderr);return JSON.parse(inspected.stdout);
}
(async()=>{
 assert(!fs.existsSync(database),"Use a fresh work directory; never overwrite a retained database");
 const estimatedRecords=(phones+(phones>=20?1:0))*samples;
 report.configured_db_wal_budget_bytes=configuredDiskBudget;report.available_disk_bytes=availableDiskBytes;report.minimum_free_space_fraction=0.2;report.db_wal_budget_bytes=diskBudget;report.command_rss_budget_bytes=rssBudget;
 if(estimatedRecords*7400>diskBudget){report.blocked=true;throw Error("Preflight resource budget: measured smoke cost ~7400 SQLite bytes/observation exceeds selected DB budget. Use suitable storage and raise explicit budgets; this case is NOT verified.");}
 const phone=`http://127.0.0.1:${await port()}`,local=`http://127.0.0.1:${await port()}`;
 command=spawn(process.env.GNSS_COMMAND_BINARY,["-db",database,"-ingest-listen",phone.slice(7),"-dashboard-listen",local.slice(7)],{stdio:["ignore","ignore",fs.openSync(path.join(work,"command.log"),"w")],env:{...process.env,GOMAXPROCS:"2",GOMEMLIMIT:process.env.GNSS_COMMAND_MEMLIMIT||"768MiB"}});
 timer=setInterval(sample,100);
 await wait(async()=>{try{return(await fetch(phone+"/api/v1/capabilities")).ok}catch{return false}});
 const control=await fetch(local+"/local/recording",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({action:"start",mode:"from_beginning"})});assert.equal(control.status,200,await control.text());
 browser=await chromium.launch({executablePath:process.env.GNSS_CHROMIUM||undefined,headless:true,args:["--no-sandbox"]});
 const page=await browser.newPage({viewport:{width:1280,height:900}}),errors=[];
 page.on("pageerror",e=>errors.push(e.message));
 await page.addInitScript(()=>{window.syncLongTasks=[];new PerformanceObserver(list=>{for(const e of list.getEntries())window.syncLongTasks.push(e.duration)}).observe({type:"longtask",buffered:true});});
 await page.goto(local);await page.waitForFunction(()=>document.querySelector("#connection").textContent.includes("Connected"));
 async function send(m){const body=JSON.stringify(m),began=performance.now();const response=await fetch(phone+"/api/v1/messages",{method:"POST",headers:{"Content-Type":"application/json"},body});const text=await response.text();assert.equal(response.status,200,text);const ack=JSON.parse(text);assert.equal(ack.message_id,m.message_id);assert.equal(ack.sequence,m.sequence);assert.equal(ack.device_id,m.device_id);report.request_body_bytes+=Buffer.byteLength(body);report.ack_body_bytes+=Buffer.byteLength(text);extras.set(`${m.device_id}/${m.sequence}`,m.message_id);const received=Date.parse(ack.received_at);assert(Number.isFinite(received));receipts.set(`${m.device_id}/${m.sequence}`,received);return performance.now()-began;}
 async function drain(d){
  expected.set(device(d),samples);
  for(let n=1;n<=samples;){
   const messages=[];let bytes=32;
   while(n<=samples&&messages.length<128){const m=observation(d,n),size=Buffer.byteLength(JSON.stringify(m));if(messages.length&&bytes+size+1>64000)break;messages.push(m);bytes+=size+1;report.source_bytes+=size;n++;}
   const body=JSON.stringify({batch_version:1,messages});
   const retryDeadline=performance.now()+120000;
   for(;;){
    const began=performance.now();active++;report.history_requests++;report.request_body_bytes+=Buffer.byteLength(body);
    let response,text;
    try{response=await fetch(phone+"/api/v1/history",{method:"POST",headers:{"Content-Type":"application/json"},body,signal:AbortSignal.timeout(15000)});text=await response.text();}
    finally{active--;}
    if(response.status===429){
     report.history_retry_responses=(report.history_retry_responses||0)+1;
     report.error_response_body_bytes=(report.error_response_body_bytes||0)+Buffer.byteLength(text);
     const value=response.headers.get("Retry-After");assert(value && /^[0-9]+$/.test(value),"Receiver must advertise bounded retry seconds");
     const delay=Math.max(1000,Number(value)*1000)+Math.floor(Math.random()*250);
     assert(Number.isSafeInteger(delay)&&performance.now()+delay<retryDeadline,"History backpressure did not resolve within the explicit per-batch retry budget");
     report.history_retry_wait_ms=(report.history_retry_wait_ms||0)+delay;
     await new Promise(r=>setTimeout(r,delay));continue;
    }
    assert.equal(response.status,200,text);const a=JSON.parse(text).acks;assert.equal(a.length,messages.length);for(let i=0;i<a.length;i++){assert.equal(a[i].device_id,messages[i].device_id);assert.equal(a[i].message_id,messages[i].message_id);assert.equal(a[i].sequence,messages[i].sequence);}
    historyRTT.push(performance.now()-began);report.history_acked_requests=(report.history_acked_requests||0)+1;report.ack_body_bytes+=Buffer.byteLength(text);completed+=messages.length;break;
   }
   if(phones>=20&&!lateStarted&&active>=10){lateStarted=true;report.late_join_active_http=active;report.late_join_acknowledged_history=completed;late=drain(phones+1);late.catch(()=>{});}
  }
 }
 const began=performance.now(),drains=Array.from({length:phones},(_,i)=>drain(i+1));
 const drainCompletion=Promise.all(drains).then(async()=>{if(late)await late;return performance.now()});drainCompletion.catch(()=>{});
 await wait(()=>active>=Math.min(phones,5));
 // New distinct events arrive while history HTTP requests are outstanding.
 for(let event=0;event<8;event++){
  // Probe throughout the retained drain, including late streamed projections.
  // Small transfers may finish before eight UI polling cycles: disclose that.
  const fraction=Math.floor((phones+(phones>=20?1:0))*samples*event/8);
  await wait(()=>completed>=fraction,1800000);
  (report.priority_probe_progress||=[]).push({event,history_durably_acked:completed,outstanding_history_http:active});
  const live=observation(1,samples+1+event*2,true);live.observation.observation_sequence=samples+1+event;live.fix.longitude=(samples+1+event)*5/6371008.8*180/Math.PI;const sos=structuredClone(live);sos.type="sos";sos.sequence++;sos.message_id=identity(sos.sequence);delete sos.observation;sos.fix=null;sos.health.gnss_status="no_fix";sos.sos={event_id:sos.message_id,triggered_at:sos.captured_at};
  report.priority_inflight_history_max=Math.max(report.priority_inflight_history_max||0,active);
  liveRTT.push(await send(live));const ack=await send(sos);sosRTT.push(ack);const receipt=performance.now();
  await page.waitForFunction(key=>[...document.querySelectorAll(".sos-alert")].some(e=>e.dataset.event===key)&&!document.querySelector("#sos-toast").hidden,`${sos.device_id}/${sos.message_id}`);
  visible.push(performance.now()-receipt);
  const fromReceipt=Date.now()-receipts.get(`${sos.device_id}/${sos.sequence}`);assert(fromReceipt>=0,"Same-host receipt clock moved backwards");receiptVisible.push(fromReceipt);
  await new Promise(r=>setTimeout(r,100));
 }
 const historyFinishedAt=await drainCompletion;
 report.transfer_including_durable_receipt_ms=historyFinishedAt-began;report.devices_including_late=expected.size;
 if(phones>=20)assert(lateStarted&&report.late_join_active_http>=10,"21st must join with actual HTTP requests outstanding");
 report.reconstruction_measurement="Residual wait after history and fresh-priority/browser probes; reconstruction also overlaps transfer";
 const projection=performance.now();await wait(async()=>{let r=await fetch(local+"/local/live");if(r.status===404)r=await fetch(local+"/local/state");if(!r.ok){(report.projection_poll_errors||=[]).push(r.status);return false}const s=await r.json();assert.equal(typeof s.projection_pending,"boolean","Missing reconstruction state is not completion");if(s.projection_error)throw Error(s.projection_error);return s.projection_pending===false},1200000,250);report.projection_poll_interval_ms=250;report.residual_reconstruction_ms=performance.now()-projection;
 const viewStart=performance.now(),response=await fetch(local+"/local/state"),body=await response.text();assert.equal(response.status,200,body);const view=JSON.parse(body);report.backend_http_and_body_ms=performance.now()-viewStart;report.dashboard_bytes=Buffer.byteLength(body);report.dashboard_points=view.points.length;if(process.env.GNSS_SYNC_EXPECT_BOUNDED!=="0")assert(view.points.length<=2048);
 const renderStart=performance.now();
 let historyFinished=0;
 const onFinished=r=>{if(r.url().includes("/local/state"))historyFinished=performance.now()};page.on("requestfinished",onFinished);
 const requestStarted=page.waitForRequest(r=>r.url().includes("/local/state"));
 const navigation=page.reload();await requestStarted;
 const coldSOS=observation(1,samples+18,true);coldSOS.type="sos";delete coldSOS.observation;coldSOS.fix=null;coldSOS.health.gnss_status="no_fix";coldSOS.sos={event_id:coldSOS.message_id,triggered_at:coldSOS.captured_at};
 const coldActivation=performance.now();report.cold_history_sos_ack_ms=await send(coldSOS);const coldReceipt=performance.now();
 await navigation;await page.waitForFunction(key=>[...document.querySelectorAll(".sos-alert")].some(e=>e.dataset.event===key)&&!document.querySelector("#sos-toast").hidden,`${coldSOS.device_id}/${coldSOS.message_id}`);
 report.cold_history_ack_to_visible_ms=performance.now()-coldReceipt;
 report.cold_history_first_received_to_visible_ms=Date.now()-receipts.get(`${coldSOS.device_id}/${coldSOS.sequence}`);assert(report.cold_history_first_received_to_visible_ms>=0);
 report.cold_history_sos_started_before_history_response_finished=historyFinished===0||coldActivation<historyFinished;
 await page.waitForFunction(()=>document.querySelectorAll("#tracks [data-device]").length>0);report.browser_cold_load_to_geometry_ms=performance.now()-renderStart;page.off("requestfinished",onFinished);
 report.browser_long_tasks=await page.evaluate(()=>window.syncLongTasks);assert.equal(errors.length,0,errors.join("\n"));
 report.history_ack=percentiles(historyRTT);report.fresh_live_ack=percentiles(liveRTT);report.fresh_sos_ack=percentiles(sosRTT);report.command_ack_to_visible_notification=percentiles(visible);report.command_first_received_to_visible_notification=percentiles(receiptVisible);report.receipt_clock_definition="Same-host wall clock from protocol first received_at (captured before durable commit) to visible notification; separately report ACK-received to visible, not an invented commit timestamp";report.android_activation_to_room_save="not measured by this process/browser test";
 report.derived_cache_before_close=inspectDerivedCache();assert.equal(report.derived_cache_before_close.pending,false);assert.equal(report.derived_cache_before_close.work.length,0);assert(report.derived_cache_before_close.partitions.every(p=>p.valid),"Published cache manifest invalid before restart");
 sample();await browser.close();browser=null;if(process.platform==="linux") {const stat=fs.readFileSync(`/proc/${command.pid}/stat`,"utf8").split(") ").at(-1).split(" ");const tick=Number(spawnSync("getconf",["CLK_TCK"],{encoding:"utf8"}).stdout)||100;report.command_cpu_user_s=Number(stat[11])/tick;report.command_cpu_system_s=Number(stat[12])/tick;}command.kill("SIGINT");await once(command,"exit");command=null;
 report.derived_cache_after_close=inspectDerivedCache();assert.equal(report.derived_cache_after_close.pending,false);assert.equal(report.derived_cache_after_close.work.length,0);assert(report.derived_cache_after_close.partitions.every(p=>p.valid),"Published cache manifest invalid after shutdown");
 // Independent streaming SQL checks every original retained history tuple. No
 // huge JS/Python expected-record arrays or geometry oracle in Command's RSS.
 const check=spawnSync("python3",["-c",`import sqlite3,json,sys\ndb=sqlite3.connect(sys.argv[1]);expected=json.loads(sys.argv[2]);extras=json.loads(sys.argv[3]);seen=set();counts={k:0 for k in expected}\nfor device,message,sequence in db.execute('SELECT device,message,sequence FROM raw ORDER BY device,sequence'):\n if device in expected and sequence<=expected[device]:\n  counts[device]+=1;assert sequence==counts[device] and message=='00000000-0000-4000-8000-%012d'%sequence,(device,message,sequence)\n else:\n  key=device+'/'+str(sequence);assert extras.get(key)==message,(key,message);seen.add(key)\nassert counts==expected and seen==set(extras),(counts,expected,seen)\nprint(json.dumps({'original_ids_accounted':sum(counts.values()),'fresh_ids_accounted':len(seen),'integrity_check':db.execute('PRAGMA integrity_check').fetchone()[0]}))`,database,JSON.stringify(Object.fromEntries(expected)),JSON.stringify(Object.fromEntries(extras))],{encoding:"utf8"});assert.equal(check.status,0,check.stderr);report.accounting=JSON.parse(check.stdout);assert.equal(report.accounting.integrity_check,"ok");
 if(process.env.GNSS_SYNC_REFERENCE_BINARY && process.env.GNSS_SYNC_COLD_REBUILD!=="0") {
  const args=[process.env.GNSS_SYNC_REFERENCE_BINARY,"-test.run=^TestSyncStoredColdReconstruction$","-test.timeout=30m","-test.v"];
  const timed=process.platform==="linux" && fs.existsSync("/usr/bin/time");
  const cold=spawnSync(timed?"/usr/bin/time":args.shift(),timed?["-f","COLD_PROCESS max_RSS_KiB=%M user_s=%U system_s=%S",...args]:args,{encoding:"utf8",env:{...process.env,GNSS_SYNC_REFERENCE_DB:database,GOMAXPROCS:"2",GOMEMLIMIT:"768MiB"},timeout:1800000});
  assert.equal(cold.status,0,cold.stdout+cold.stderr);assert(cold.stdout.includes("COLD RECONSTRUCTION VERIFIED"),"Required cold rebuild was absent/skipped");
  report.isolated_cold_reconstruction=cold.stdout+cold.stderr;
 }
 if(process.env.GNSS_SYNC_REFERENCE_BINARY){const oracle=spawnSync(process.env.GNSS_SYNC_REFERENCE_BINARY,["-test.run=^TestSyncStoredChronologicalReference$","-test.timeout=30m","-test.v"],{encoding:"utf8",env:{...process.env,GNSS_SYNC_REFERENCE_DB:database,GOMAXPROCS:"2",GOMEMLIMIT:"768MiB"},timeout:1800000});assert.equal(oracle.status,0,oracle.stdout+oracle.stderr);assert(oracle.stdout.includes("REFERENCE VERIFIED"),"Required oracle was absent/skipped");report.chronological_reference=oracle.stdout;}
 else if(process.env.GNSS_SYNC_LEGACY_BASELINE==="1") {
   report.chronological_reference="NOT RUN: explicit legacy comparison; cached format predates this oracle";
   report.measurement_completed=true;report.acceptance_status="NOT ASSESSED: legacy comparison only";
 }
 else throw Error("Required chronological reference is absent; set GNSS_SYNC_REFERENCE_BINARY. Not an acceptance PASS.");
 if(process.env.GNSS_SYNC_REFERENCE_BINARY)report.passed=true;
})().catch(e=>{report.passed=false;report.error=e.stack;process.exitCode=1;}).finally(async()=>{clearInterval(timer);if(browser)await browser.close();if(command && command.exitCode===null){command.kill("SIGINT");await once(command,"exit");}fs.writeFileSync(process.env.GNSS_SYNC_REPORT||path.join(work,"report.json"),JSON.stringify(report,null,2));
 if(process.env.GNSS_SYNC_KEEP_DB!=="1" && (report.passed || report.measurement_completed)) for(const suffix of ["","-wal","-shm"])try{fs.unlinkSync(database+suffix);}catch{}
 console.log(JSON.stringify(report,null,2));});
