/* Acceptance driver only: all acquisition/authorization remains in Command. */
"use strict";
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
function transferBudget() {
  const n = Number(process.env.GNSS_CI_TRANSFER_BUDGET_BYTES || 5000000);
  if (!Number.isSafeInteger(n) || n < 5000000 || n > 1000000000)
    throw Error("GNSS_CI_TRANSFER_BUDGET_BYTES must be an integer from 5000000 to 1000000000");
  return n;
}
function classify(error) {
  if (error?.acquisitionOutcome) return error.acquisitionOutcome;
  const message = error?.providerMessage || String(error);
  if (/CI transfer ceiling exceeded/.test(message)) return "CI transfer ceiling exceeded";
  if (/approval required|invalid_approval|transfer_approval_required/i.test(message)) return "transfer approval required";
  if (/timed out|timeout|deadline exceeded/i.test(message)) return "request timeout";
  if (/HTTP (502|503|504|429)/i.test(message)) return "provider unavailable/HTTP " + message.match(/HTTP (\d+)/i)[1];
  if (/Forbidden|403|CONNECT|proxy|ENOTFOUND|fetch failed|connection refused/i.test(message)) return "provider unreachable or environment access failure";
  return "acquisition failed";
}
async function json(local, route, body, timeout = 390000) {
  const response = await fetch(local + "/local/" + route, {
    ...(body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(timeout),
  });
  const data = await response.json();
  if (!response.ok) {
    const error = Error(`HTTP ${response.status}: ${JSON.stringify(data)}`);
    error.providerMessage = data.message || data.error;
    throw error;
  }
  return data;
}
function diagnostic(id, manifest) {
  return { map_id: id, requested_area: manifest.plan?.request,
    transfer: manifest.transfer, chunks: manifest.chunks,
    recovery: "Validated assets retained; retry missing work on this map ID. Offline usability depends on completed layers." };
}
function approvalRequired(manifest, budget) {
  const t = manifest.transfer, n = t?.approval_required_bytes;
  if (!Number.isSafeInteger(n) || n <= (t.approved_bytes || 5000000))
    throw Error(`Invalid cumulative approval requirement: ${JSON.stringify(t)}`);
  if (n > budget) throw Error(`CI transfer ceiling exceeded: requested cumulative ${n} bytes; configured budget ${budget} bytes; ${JSON.stringify(t)}`);
  return n;
}
async function acquireChunk(local, id, key, layer, evidence, budget = transferBudget()) {
  evidence.approvals ||= [];
  for (let attempt = 0; attempt < 8; attempt++) {
    try { return await json(local, `maps/${id}/chunks/${key}`, { layer }); }
    catch (error) {
      const manifest = await json(local, `maps/${id}/manifest`, undefined, 10000);
      evidence.diagnostics = diagnostic(id, manifest);
      if (!manifest.transfer?.approval_required_bytes) {
        evidence.outcome = classify(error);
        throw error;
      }
      const n = approvalRequired(manifest, budget);
      evidence.approvals.push({ chunk_id: key, layer, before: manifest.transfer, approved_bytes: n });
      await json(local, `maps/${id}/approve-transfer`, { approved_bytes: n }, 10000);
      const after = await json(local, `maps/${id}/manifest`, undefined, 10000);
      if (after.transfer.approved_bytes !== n || after.transfer.approval_required_bytes)
        throw Error("Approval endpoint did not persist the exact requested allowance");
    }
  }
  throw Error(`Bounded approval attempts exhausted for ${id}/${key}/${layer}: ${JSON.stringify(evidence)}`);
}
async function waitForAcquisition(page, local, evidence, budget = transferBudget()) {
  const end = Date.now() + 12 * 60000;
  let approvals = 0, last = "", progressAt = Date.now();
  while (Date.now() < end) {
    const state = await page.evaluate(() => ({
      status: document.querySelector("#map-preparation-status").textContent,
      progress: document.querySelector("#download-progress").textContent,
      active: !!downloadAbort, id: activeMapID,
    }));
    if (state.status + state.progress !== last) { last = state.status + state.progress; progressAt = Date.now(); }
    if (state.id) {
      const manifest = await json(local, `maps/${state.id}/manifest`, undefined, 10000);
      evidence.diagnostics = diagnostic(state.id, manifest);
      if (!state.active && manifest.transfer?.approval_required_bytes) {
        if (++approvals > 8) throw Error("Bounded UI approval attempts exhausted");
        const n = approvalRequired(manifest, budget);
        evidence.approvals ||= [];
        evidence.approvals.push({ before: manifest.transfer, approved_bytes: n });
        const dialog = page.waitForEvent("dialog", { timeout: 5000 }).then(async confirmation => {
          if (!confirmation.message().includes(`${n} bytes`)) {
            await confirmation.dismiss();
            throw Error("UI confirmation does not display exact cumulative bytes");
          }
          await confirmation.accept();
        });
        await Promise.all([dialog, page.locator("#approve-transfer").click()]);
        progressAt = Date.now();
        await delay(200);
        continue;
      }
      if (!state.active && state.status.includes("Saved locally")) {
        const missing = manifest.chunks.filter(c => c.vector.status !== "complete" ||
          (Object.values(manifest.components).some(Boolean) && c.terrain.status !== "complete"));
        if (missing.length) throw Error(`False completion: ${JSON.stringify(evidence.diagnostics)}`);
        return { id: state.id, manifest };
      }
      if (!state.active && /Partial map|retained|unavailable|failed/i.test(state.status)) {
        const error = Error(`Acquisition stopped: ${state.status}; ${JSON.stringify(evidence.diagnostics)}`);
        error.providerMessage = manifest.chunks.flatMap(c => [c.vector.error, c.terrain.error]).filter(Boolean).join("; ");
        throw error;
      }
    } else if (/failed|timed out|deadline|retained/i.test(state.status)) throw Error(state.status);
    if (Date.now() - progressAt > 150000)
      throw Error(`Acquisition stalled in network/processing state: ${JSON.stringify(state)}; ${JSON.stringify(evidence.diagnostics)}`);
    await delay(250);
  }
  throw Error(`Acquisition total deadline exceeded: ${last}; ${JSON.stringify(evidence.diagnostics)}`);
}
module.exports = { json, acquireChunk, waitForAcquisition, transferBudget, classify, approvalRequired, diagnostic };
