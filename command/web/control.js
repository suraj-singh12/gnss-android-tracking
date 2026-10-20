"use strict";
// Uses the live application's selected-party identity and existing UI primitives.
// No recording/import/playback object is a source of recipients or instructions.
const controlTargets = new Set();
let controlView = {phones: {}, commands: []};
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
  const list = $("control-phones"); list.replaceChildren();
  const devices = Object.values(state?.devices || {});
  for (const phone of devices) {
    const id = phone.device_id, enrollment = controlView.phones[id];
    const row = el("div");
    const label = el("label"), check = el("input"); check.type="checkbox"; check.checked=controlTargets.has(id);
    check.disabled=!enrollment?.enabled;
    check.onchange=()=>{if(check.checked)controlTargets.add(id);else controlTargets.delete(id);};
    const seen = enrollment?.last_seen;
    label.append(check, document.createTextNode(`${phone.snapshot.party.id} · ${id} · ${!enrollment?.enabled ? "remote control unavailable / legacy or revoked" : !seen ? "remote control unavailable: enrollment not yet authenticated" : Date.now()-seen>30000 ? "offline / last authenticated "+new Date(seen).toISOString() : "authenticated contact "+new Date(seen).toISOString()}`));
    const enroll = el("button", enrollment ? "Replace credential" : "Enroll remote control");
    enroll.onclick=async()=>{try {
      if(!confirm(`Verify phone UUID ${id} directly in Android Settings. Generate a private credential? Replacing it revokes previous authorization. Saved history is retained.`))return;
      const value=await controlPost("enroll",{device:id,confirmed:true});
      // Deliberately shown only in the local provisioning dialog; no storage/log.
      prompt("Privately enter this credential in Android Settings → Authenticated remote control. Compare the phone UUID; never send through an unverified LAN service. Close after transfer.",value.credential);
      await refreshControl();
    }catch(error){controlError(error);}};
    row.append(label,enroll);
    if(enrollment?.enabled){const revoke=el("button","Revoke");revoke.onclick=async()=>{try{if(confirm(`Revoke remote authorization for ${id}? In-flight effects remain uncertain.`)){await controlPost("revoke",{device:id,confirmed:true});controlTargets.delete(id);await refreshControl();}}catch(error){controlError(error);}};row.append(revoke);}
    list.append(row);
  }
  if(!devices.length)list.append(el("p","No phones have contacted this Command. Configure its IP:port in Android first."));
  for(const id of controlTargets)if(!controlView.phones[id]?.enabled)controlTargets.delete(id);
  const history=$("control-history");history.replaceChildren();
  for(const entry of controlView.commands){
    const detail=el("details"), title=el("summary",`${entry.device} · ${entry.operation} · ${entry.state}`);
    const result=entry.result;
    detail.append(title,el("p",`Command ${entry.id} · authority ${entry.authority} · created ${new Date(entry.created).toISOString()} · expires ${new Date(entry.expires).toISOString()}`),el("p",`Requested ${JSON.stringify(entry.payload)} · effective ${result?.effective || "unknown"}`),el("p",result ? `${new Date(result.received).toISOString()} phone durable receipt · ${result.attempted ? "Execution attempted" : "No execution attempt"} · ${result.reason} · authenticated result revision ${result.revision} stored` : "No authenticated Android execution result. Queued, expired or cancelled is not proof of receipt or effect."));
    if(["queued","sent_unknown"].includes(entry.state)){const cancel=el("button","Cancel safely");cancel.onclick=async()=>{try{if(confirm("Cancel future execution? An already executed effect cannot be undone.")){await controlPost("cancel",{id:entry.id});await refreshControl();}}catch(error){controlError(error);}};detail.append(cancel);}
    const retry=el("button","Review new request");retry.onclick=()=>{controlTargets.clear();controlTargets.add(entry.device);$("control-operation").value=entry.operation;$("control-status").textContent="New request selected. Review values and expiry, then issue deliberately. Automatic delivery retries preserve the original command ID.";};detail.append(retry);history.append(detail);
  }
}
async function chooseControlTargets(all) {
  try {
    await refreshControl();controlTargets.clear();
    if(all){for(const id of Object.keys(state?.devices||{}))if(controlView.phones[id]?.enabled)controlTargets.add(id);}
    else if(selectedParty&&controlView.phones[selectedParty]?.enabled)controlTargets.add(selectedParty);
    await refreshControl();
  }catch(error){controlError(error);}
}
$("open-control").onclick=()=>{$("phone-control").showModal();refreshControl().catch(controlError);};
$("control-one").onclick=()=>chooseControlTargets(false);
$("control-all").onclick=()=>chooseControlTargets(true);
$("control-none").onclick=()=>{controlTargets.clear();refreshControl().catch(controlError);};
$("control-issue").onclick=async()=>{try{
  const targets=[...controlTargets]; // Frozen at the explicit review action.
  const operation=$("control-operation").value;
  const ttl=Number($("control-ttl").value);
  if(!targets.length)throw new Error("Select one or more enrolled recipients");
  if(!$("control-ttl").reportValidity())return;
  let payload={};
  if(operation==="reporting"){if(!$("control-seconds").reportValidity())return;payload={seconds:$("control-seconds").value===""?null:Number($("control-seconds").value)};}
  if(operation==="adaptive")payload={enabled:$("control-adaptive").checked};
  if(!confirm(`${operation} ${JSON.stringify(payload)}\n${targets.length} frozen recipients:\n${targets.join("\n")}\nExpires in ${ttl} seconds. Tracking Start requires Android visibility and permissions. Proceed?`))return;
  const result=await controlPost("issue",{targets,operation,payload,ttl,confirmed:true});
  $("control-status").textContent=`${result.ids.length} commands durably queued. Execution is not yet confirmed.`;
  await refreshControl();
}catch(error){controlError(error);}};
setInterval(()=>{if($("phone-control").open&&!$("phone-control").contains(document.activeElement?.closest("input,select")))refreshControl().catch(controlError);},5000);
