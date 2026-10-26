"use strict";
// Uses the live application's selected-party identity and existing UI primitives.
// No recording/import/playback object is a source of recipients or instructions.
const controlTargets = new Set();
let controlView = {phones: {}, commands: []};
let controlSelecting = false;
async function controlPost(action, payload) {
  const response = await fetch(`/local/control/${action}`, {method: "POST", headers: {"Content-Type":"application/json"}, body:JSON.stringify(payload)});
  const value = await response.json();
  if (!response.ok) throw new Error(value.message || "Control unavailable");
  return value;
}
function controlError(error) { $("control-status").textContent = error.message; }
async function refreshControl() {
  const response = await fetch("/local/control");
  if(!response.ok) throw new Error("Phone control state unavailable");
  controlView = await response.json();
  $("control-authority").textContent = controlView.authority_id ? `Command ID: ${controlView.authority_id}` : "Command ID unavailable";
  $("control-copy-authority").disabled = !controlView.authority_id;
  $("control-copy-authority").onclick = async () => {
    try { await navigator.clipboard.writeText(controlView.authority_id); $("control-copy-status").textContent = "Command ID copied"; }
    catch (_) { $("control-copy-status").textContent = "Copy unavailable; select the public Command ID above."; }
  };
  const list = $("control-phones"); list.replaceChildren();
  const devices = Object.values(state?.devices || {});
  const known=new Set(devices.map(phone=>phone.device_id));
  // Enrollment is authoritative even before the independent LIVE inventory poll catches up.
  for(const id of Object.keys(controlView.phones))if(!known.has(id))devices.push({device_id:id,snapshot:{party:{id:"Enrolled phone"}}});
  for (const phone of devices) {
    const id = phone.device_id, enrollment = controlView.phones[id];
    const row = el("div");
    const label = el("label"), check = el("input"); check.type="checkbox"; check.checked=controlTargets.has(id);
    check.disabled=!enrollment?.enabled;
    check.onchange=()=>{if(check.checked)controlTargets.add(id);else controlTargets.delete(id);};
    const seen = enrollment?.last_seen;
    const requestAge = seen ? Date.now() - seen : null;
    const identity = el("div");
    identity.append(el("strong",phone.snapshot.party.id),el("p",`Phone ID: ${id}`));
    label.append(check, identity);
    const enrollmentInfo = el("div"); enrollmentInfo.className="control-enrollment";
    enrollmentInfo.append(el("span","Enrollment"),el("p",!enrollment ? "Not enrolled" : !enrollment.enabled ? "Revoked on this Command" : "Credential issued · authorization enabled"));
    const evidence = el("div"); evidence.className="control-evidence";
    evidence.append(el("span","Authenticated contact evidence"),el("p", !seen ? "No authenticated phone request received" : `Phone request received · ${new Date(seen).toISOString()}${requestAge < 0 ? " · age unconfirmed (check clock)" : requestAge >= 30000 ? " · stale" : ` · ${Math.floor(requestAge/1000)} s ago`}`),el("small","Android response verification is separate."));
    const enroll = el("button", enrollment ? "Replace credential" : "Enroll remote control");
    enroll.onclick=async()=>{try {
      if(!confirm(`Verify phone UUID ${id} directly in Android Settings. Generate a private credential? Replacing it revokes previous authorization. Saved history is retained.`))return;
      const value=await controlPost("enroll",{device:id,confirmed:true});
      // Deliberately shown only in the local provisioning dialog; no storage/log.
      prompt("Privately enter this credential in Android Settings → Command access → Enter credential. Compare the phone ID and Command ID; never send through an unverified LAN service. Close after transfer.",value.credential);
      await refreshControl();
    }catch(error){controlError(error);}};
    row.append(label,enrollmentInfo);enrollmentInfo.append(enroll);
    if(enrollment?.enabled){const revoke=el("button","Revoke");revoke.onclick=async()=>{try{if(confirm(`Revoke remote authorization for ${id}? In-flight effects remain uncertain.`)){await controlPost("revoke",{device:id,confirmed:true});controlTargets.delete(id);await refreshControl();}}catch(error){controlError(error);}};enrollmentInfo.append(revoke);}
    row.append(evidence);
    list.append(row);
  }
  if(!devices.length)list.append(el("p","No phones have contacted this Command. Configure its IP:port in Android first."));
  for(const id of controlTargets)if(!controlView.phones[id]?.enabled)controlTargets.delete(id);
  const history=$("control-history");
  const expanded=new Set([...history.querySelectorAll("details[open]")].map(detail=>detail.dataset.commandId));
  history.replaceChildren();
  for(const entry of controlView.commands){
    const detail=el("details"), title=el("summary",`${entry.device} · ${entry.operation} · ${entry.state}`);
    detail.dataset.commandId=entry.id;detail.open=expanded.has(entry.id);
    const result=entry.result;
    const meanings={queued:"Queued / phone may be offline",sent_unknown:"Sent; phone receipt unknown",received:"Delivered and durably received",output_attempted:"Notification/output attempted; heard/felt unknown",alert_blocked:"OS blocked output; local acknowledgement pending",recipient_acknowledged:"Human recipient locally acknowledged (authenticated result stored by Command)",cancel_requested_unknown:"Cancel requested by operator; phone Stop pending/unknown",cancelled:result?"Cancellation confirmed by Android":"Cancelled before delivery; no phone receipt claimed",expired:"Expired / duration ended",unknown:"Unknown / unresolved"};
    detail.append(el("p",meanings[entry.state]||entry.state));
    detail.append(title,el("p",`Instruction ID ${entry.id} · Command ID ${entry.authority} · created ${new Date(entry.created).toISOString()} · expires ${new Date(entry.expires).toISOString()}`),el("p",`Requested ${JSON.stringify(entry.payload)} · effective ${result?.effective || "unknown"}`),el("p",result ? `${new Date(result.received).toISOString()} phone durable receipt · ${result.attempted ? "Execution attempted" : "No execution attempt"} · ${result.reason} · authenticated result revision ${result.revision} stored` : "No authenticated Android execution result. Queued, expired or cancelled is not proof of receipt or effect."));
    if(["queued","sent_unknown","received","output_attempted","alert_blocked"].includes(entry.state)){const cancel=el("button","Cancel safely");cancel.onclick=async()=>{try{if(confirm("Request cancellation? An offline phone may keep alerting until its duration/expiry. Only authenticated phone evidence confirms Stop. Earlier recipient acknowledgement remains valid.")){await controlPost("cancel",{id:entry.id});await refreshControl();}}catch(error){controlError(error);}};detail.append(cancel);}
    const retry=el("button","Review new request");retry.onclick=()=>{controlTargets.clear();controlTargets.add(entry.device);$("control-operation").value=entry.operation;showAlertOptions();$("control-status").textContent="New request selected. Review values and expiry, then issue deliberately. Automatic delivery retries preserve the original command ID.";};detail.append(retry);history.append(detail);
  }
}
async function chooseControlTargets(all) {
  if(controlSelecting)return;
  controlSelecting=true;
  for(const id of ["control-one","control-all","control-none","control-issue"])$(id).disabled=true;
  $("control-status").textContent="Loading eligible recipients; wait for the selection before reviewing.";
  try {
    await refreshControl();controlTargets.clear();
    if(all){for(const [id,phone] of Object.entries(controlView.phones))if(phone.enabled)controlTargets.add(id);}
    else if(selectedParty&&controlView.phones[selectedParty]?.enabled)controlTargets.add(selectedParty);
    await refreshControl();
    $("control-status").textContent=`${controlTargets.size} enrolled recipients selected. Review identities and expiry.`;
  }catch(error){controlError(error);}
  finally {
    controlSelecting=false;
    for(const id of ["control-one","control-all","control-none","control-issue"])$(id).disabled=false;
  }
}
$("open-control").onclick=()=>{$("phone-control").showModal();refreshControl().catch(controlError);};
$("control-one").onclick=()=>chooseControlTargets(false);
$("control-all").onclick=()=>chooseControlTargets(true);
$("control-none").onclick=()=>{
  controlTargets.clear();
  $("control-status").textContent="No recipients selected.";
  // Reflect the selection immediately; a network refresh must not leave checked
  // boxes whose next click would silently disagree with the frozen target set.
  for(const input of $("control-phones").querySelectorAll("input"))input.checked=false;
  refreshControl().catch(controlError);
};
$("control-issue").onclick=async()=>{try{
  if(controlSelecting)throw new Error("Wait for the recipient selection to finish");
  const targets=[...controlTargets]; // Frozen at the explicit review action.
  const operation=$("control-operation").value;
  const ttl=Number($("control-ttl").value);
  if(!targets.length)throw new Error("Select one or more enrolled recipients");
  if(!$("control-ttl").reportValidity())return;
  let payload={};
  if(operation==="reporting"){if(!$("control-seconds").reportValidity())return;payload={seconds:$("control-seconds").value===""?null:Number($("control-seconds").value)};}
  if(operation==="adaptive")payload={enabled:$("control-adaptive").checked};
  if(operation==="alert"){
    if(ttl>3600)throw new Error("Phone alert expiry must be 5–3600 seconds");
    const mode=$("control-alert-mode").value;
    if(mode==="urgent" && (!$("control-alert-duration").reportValidity() || !$("control-alert-repeat").reportValidity()))return;
    payload={mode,message:$("control-alert-message").value,duration_s:mode==="normal"?5:Number($("control-alert-duration").value),repeat_s:mode==="normal"?5:Number($("control-alert-repeat").value)};
  }
  if(!confirm(`${operation} ${JSON.stringify(payload)}\n${targets.length} frozen recipients:\n${targets.join("\n")}\nExpires in ${ttl} seconds. Tracking Start requires Android visibility and permissions. Proceed?`))return;
  const result=await controlPost("issue",{targets,operation,payload,ttl,confirmed:true});
  $("control-status").textContent=`${result.ids.length} commands durably queued. Execution is not yet confirmed.`;
  await refreshControl();
}catch(error){controlError(error);}};
setInterval(()=>{if($("phone-control").open&&!$("phone-control").contains(document.activeElement?.closest("input,select")))refreshControl().catch(controlError);},5000);

function showAlertOptions(){ $("control-alert-options").hidden=$("control-operation").value!=="alert"; }
$("control-operation").onchange=showAlertOptions;
