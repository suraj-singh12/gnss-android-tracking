"use strict";
(() => {
  const $ = (id) => document.getElementById(id);
  let lease,
    revision = 0,
    dirty = true,
    pumping = false,
    pending,
    lastState,
    lastConfirmed = 0;
  let capturedRecording,
    capturedActive,
    lastPresented,
    presenceReported = false;
  let generation = 0,
    stopped = false,
    claiming = false;
  const headers = () =>
    lease
      ? {
          "X-Presentation-Token": lease.token,
          "X-Presentation-Epoch": lease.epoch,
        }
      : {};
  async function request(op, data = {}) {
    const response = await fetch(`/local/presentation/${op}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        ...data,
        token: lease?.token,
        epoch: lease?.epoch,
      }),
      signal: AbortSignal.timeout(op === "save" ? 120000 : 5000),
    });
    const body = await response.json();
    if (!response.ok)
      throw Error(body.message || "Presentation evidence unconfirmed");
    return body;
  }
  let failureReportedFor;
  function health(text) {
    $("capture-health").textContent = text;
  }
  async function claim() {
    if (claiming) return;
    claiming = true;
    try {
      lease = await request("claim");
      revision = 0;
      presenceReported = false;
      failureReportedFor = undefined;
      pending = undefined;
      lastState = undefined;
      lastPresented = undefined;
      $("browser-occupied").close();
      dirty = true;
      stopped = false;
      await pump();
    } catch (e) {
      lease = undefined;
      health("Unverified presentation evidence · " + e.message);
      $("browser-ownership-reason").textContent = e.message;
      if (!$("browser-occupied").open) $("browser-occupied").showModal();
      // pagehide is best effort. A returning browser must also recover after
      // lease expiry when the old page disappeared without sending Close.
      setTimeout(() => {
        if (!lease && !stopped) claim();
      }, 1000);
    } finally {
      claiming = false;
    }
  }
  // Two animation frames surround a rendering opportunity. A task following the
  // second frame samples the still-applied state. DOM changes invalidate this
  // candidate. This establishes a browser rendering opportunity, not OS scanout.
  const frame = () => new Promise((resolve) => requestAnimationFrame(resolve));
  async function pump() {
    if (pumping || !lease || stopped || window.HistoricalPlayback?.active) return;
    if ($("tracks").clientWidth <= 0 || $("tracks").clientHeight <= 0) {
      health("Unverified presentation evidence · map has no renderable area");
      return;
    }
    const ownerEpoch = lease.epoch;
    const rec = window.recordingLiveState?.()?.recording;
    const capturing = rec?.active && rec.status !== "stopped";
    pumping = true;
    try {
      if (!pending && dirty && document.visibilityState === "visible") {
        const before = generation;
        await frame();
        const applied = structuredClone(window.recordingAppliedState());
        const presented = new Date().toISOString();
        await frame();
        await new Promise((resolve) => setTimeout(resolve, 0));
        if (lease?.epoch !== ownerEpoch || window.HistoricalPlayback?.active) return;
        if (before !== generation) {
          dirty = true;
          if (capturing)
            await request("gap", {
              from: presented,
              until: new Date().toISOString(),
              reason:
                "Display changed before rendering-opportunity confirmation; transient state unverified",
            });
          return;
        }
        const encoded = JSON.stringify(applied);
        lastPresented = {
          state: applied,
          presented_at: presented,
          opportunity_at: new Date().toISOString(),
          encoded,
        };
        if (!capturing) {
          if (!presenceReported) {
            await request("present", {
              presented_at: presented,
              opportunity_at: lastPresented.opportunity_at,
            });
            presenceReported = true;
            if (!rec) health("Ready · GNSS independent");
          }
          dirty = false;
          return;
        }
        if (encoded === lastState) {
          dirty = false;
          return;
        }
        pending = {
          revision: ++revision,
          presented_at: presented,
          opportunity_at: new Date().toISOString(),
          state: applied,
        };
        lastState = encoded;
        dirty = false;
      }
      if (pending) {
        if (lease?.epoch !== ownerEpoch || window.HistoricalPlayback?.active) return;
        const ack = await request("confirm", pending);
        if (lease?.epoch !== ownerEpoch || window.HistoricalPlayback?.active) return;
        lastConfirmed = Date.now();
        presenceReported = true;
        if (window.recordingLiveState?.()?.recording?.active)
          health(`Display state confirmed · revision ${ack.revision}`);
        pending = undefined;
      }
    } catch (e) {
      health("Unverified presentation evidence · " + e.message);
      if (pending && failureReportedFor !== pending.revision) {
        try {
          await request("gap", {
            from: pending.presented_at,
            until: new Date().toISOString(),
            reason:
              "Browser confirmation outcome unavailable; durable outcome may be unknown until retry",
          });
          failureReportedFor = pending.revision;
        } catch {}
      }
    } finally {
      pumping = false;
    }
  }
  document.addEventListener("click", () => {
    dirty = true;
    generation++;
  });
  document.addEventListener("input", () => {
    dirty = true;
    generation++;
  });
  window.RecordingCapture = {
    headers,
    boundary(pressed) {
      if (
        !lease ||
        !lastPresented ||
        document.visibilityState !== "visible" ||
        lastPresented.opportunity_at > pressed
      )
        return undefined;
      if (
        JSON.stringify(window.recordingAppliedState()) !== lastPresented.encoded
      )
        return undefined;
      return {
        state: lastPresented.state,
        presented_at: lastPresented.presented_at,
        opportunity_at: lastPresented.opportunity_at,
        revision: ++revision,
      };
    },
    changed() {
      const rec = window.recordingLiveState?.()?.recording;
      if (
        rec?.recording_id !== capturedRecording ||
        rec?.active !== capturedActive
      ) {
        capturedRecording = rec?.recording_id;
        capturedActive = rec?.active;
        pending = undefined;
        lastConfirmed = 0;
        lastState = undefined;
        health(
          rec?.active
            ? "Unverified presentation evidence · awaiting initial state"
            : rec?.status === "paused"
              ? "Operator paused · GNSS continues"
              : rec?.status === "stopped"
                ? "Finally stopped · unsaved evidence retained"
                : "Ready · GNSS independent",
        );
      }
      dirty = true;
      generation++;
      updateOpenParticipants();
    },
    pump,
  };
  async function action(action) {
    const pressed = new Date().toISOString();
    const boundary =
      action === "resume"
        ? window.RecordingCapture.boundary(pressed)
        : undefined;
    const r = await fetch("/local/recording", {
      method: "POST",
      headers: { "Content-Type": "application/json", ...headers() },
      body: JSON.stringify({
        action,
        operator_at: pressed,
        boundary,
        confirmed: action === "discard",
      }),
    });
    const b = await r.json();
    if (!r.ok) throw Error(b.message);
    await poll();
    dirty = true;
    await pump();
  }
  $("pause").onclick = () => action("pause").catch(showError);
  $("resume").onclick = () => action("resume").catch(showError);
  $("stop").onclick = () => action("stop").then(openSave).catch(showError);
  $("discard-recording").onclick = () => {
    if (
      confirm(
        "Discard this unsaved recording? Saved snapshots, original GNSS and SOS remain retained.",
      )
    )
      action("discard").catch(showError);
  };
  function subset() {
    const n = $("snapshot-parties").querySelectorAll("input:checked").length;
    $("snapshot-submit").disabled = n === 0;
    $("snapshot-subset").textContent =
      `Saved subset: ${n} of ${$("snapshot-parties").children.length} parties. All remain listed in participation metadata.`;
  }
  async function openSave() {
    const state = window.recordingLiveState();
    const rec = state.recording;
    $("snapshot-discard").hidden = rec.status !== "stopped";
    $("snapshot-evidence-facts").textContent =
      `Source: ${rec.mode === "from_now" ? "From Now" : "From Beginning"} · Actual Start UTC: ${rec.started_at}${rec.stopped_at ? " · Final Stop UTC: " + rec.stopped_at : " · Cutoff: actual Save press"}. These facts are immutable; display date is descriptive.`;
    const warnings = window.recordingAppliedState().asset_warnings || [];
    $("snapshot-fidelity").hidden = warnings.length === 0;
    $("snapshot-fidelity").textContent = warnings.join(" ");
    $("snapshot-evidence-summary").textContent =
      "Loading recorded interruption facts…";
    fetch(
      `/local/presentation/summary?recording_id=${encodeURIComponent(rec.recording_id)}`,
    )
      .then(async (response) => {
        if (!response.ok) throw Error("summary unavailable");
        const counts = await response.json();
        if (
          $("save-snapshot").open &&
          window.recordingLiveState().recording?.recording_id ===
            rec.recording_id
        )
          $("snapshot-evidence-summary").textContent =
            `Recorded facts: ${counts.display || 0} display confirmations; ${counts.pause || 0} pauses; ${counts.browser_unavailable || 0} browser gaps; ${counts.command_unavailable || 0} Command gaps; ${counts.unverified || 0} unverified intervals. Overlapping facts are retained separately.`;
      })
      .catch(() => {
        $("snapshot-evidence-summary").textContent =
          "Interruption summary unavailable. Saved diagnostics retain known facts and uncertainty.";
      });
    $("save-continuation").textContent =
      state.recording?.status === "stopped"
        ? "Recording is finally stopped. Dismissal preserves unsaved evidence."
        : state.recording?.status === "paused"
          ? "Recording remains paused after saving."
          : "The recording continues after saving.";
    $("snapshot-parties").replaceChildren();
    choicesRecording = undefined;
    $("snapshot-total").textContent = "Loading participating parties…";
    $("snapshot-submit").disabled = true;
    $("snapshot-error").textContent = "";
    if (!$("save-snapshot").open) $("save-snapshot").showModal();
    try {
      const response = await fetch(
        `/local/presentation/participants?recording_id=${encodeURIComponent(rec.recording_id)}`,
      );
      if (!response.ok)
        throw Error(
          "Participating-party manifest is unavailable; unsaved evidence is retained.",
        );
      const participants = await response.json();
      if (
        !$("save-snapshot").open ||
        window.recordingLiveState().recording?.recording_id !== rec.recording_id
      )
        return;
      for (const [id, party] of Object.entries(participants))
        appendParticipant(id, party, true);
      choicesRecording = rec.recording_id;
      updateParticipantCount();
    } catch (e) {
      $("snapshot-error").textContent = e.message;
    }
  }
  let choicesRecording;
  function appendParticipant(id, party, checked) {
    const label = document.createElement("label"),
      input = document.createElement("input");
    input.type = "checkbox";
    input.value = id;
    input.checked = checked;
    input.onchange = subset;
    label.title = party.name + " · " + id;
    label.append(input, document.createTextNode(party.id));
    $("snapshot-parties").append(label);
  }
  function updateParticipantCount() {
    $("snapshot-total").textContent =
      `${$("snapshot-parties").children.length} participated · Select at least one`;
    subset();
  }
  function updateOpenParticipants() {
    const state = window.recordingLiveState();
    if (
      !$("save-snapshot").open ||
      !state?.recording?.active ||
      choicesRecording !== state.recording.recording_id
    )
      return;
    const known = new Set(
      [...$("snapshot-parties").querySelectorAll("input")].map((e) => e.value),
    );
    let added = false;
    for (const [id, d] of Object.entries(state.devices)) {
      if (!known.has(id)) {
        appendParticipant(id, d.snapshot.party, false);
        added = true;
      }
    }
    if (added) updateParticipantCount();
  }
  $("snapshot-discard").onclick = async () => {
    if (
      !confirm(
        "Discard this unsaved recording? Existing saved snapshots, original GNSS and SOS remain retained.",
      )
    )
      return;
    try {
      await action("discard");
      $("save-snapshot").close();
    } catch (e) {
      $("snapshot-error").textContent = e.message;
    }
  };
  $("save-recording").onclick = openSave;
  $("stopped-save").onclick = openSave;
  let saveRequest;
  $("snapshot-form").addEventListener("input", () => {
    saveRequest = undefined;
  });
  $("snapshot-form").onsubmit = async (event) => {
    event.preventDefault();
    const operatorAt = new Date().toISOString();
    $("snapshot-submit").disabled = true;
    try {
      await pump();
      const receipt = await request("save", {
        operator_at: operatorAt,
        request_id: (saveRequest ||= crypto.randomUUID()),
        name: $("snapshot-name").value,
        description: $("snapshot-description").value,
        display_date: $("snapshot-date").value,
        included: [
          ...$("snapshot-parties").querySelectorAll("input:checked"),
        ].map((e) => e.value),
      });
      saveRequest = undefined;
      $("save-snapshot").close();
      $("snapshot-receipt").hidden = false;
      $("snapshot-receipt").textContent =
        `Saved ${receipt.name} · ${receipt.id} · ${receipt.confirmed_events} confirmed states · ${receipt.included.length} of ${receipt.total_parties} parties included · evidence limitations retained`;
    } catch (e) {
      $("snapshot-error").textContent = e.message;
    } finally {
      $("snapshot-submit").disabled =
        !$("snapshot-parties").querySelector("input:checked");
    }
  };
  $("retry-owner").onclick = claim;
  window.addEventListener("pagehide", () => {
    stopped = true;
    if (lease)
      fetch("/local/presentation/close", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        keepalive: true,
        body: JSON.stringify({ token: lease.token, epoch: lease.epoch }),
      }).catch(() => {});
  });
  document.addEventListener("visibilitychange", () => {
    dirty = true;
    generation++;
    if (document.visibilityState === "visible") pump();
    else
      health(
        "Unverified presentation evidence · browser backgrounded; ownership retained",
      );
  });
  // Keep ownership separate from paint: background/minimization is never a close.
  setInterval(async () => {
    if (!lease) return;
    try {
      await request("heartbeat");
      await pump();
      if (
        window.recordingLiveState?.()?.recording?.active &&
        Date.now() - lastConfirmed > 5000
      )
        health(
          "Unverified presentation evidence · awaiting rendering confirmation",
        );
    } catch (e) {
      lease = undefined;
      health("Unverified presentation evidence · Command unavailable");
      setTimeout(claim, 1000);
    }
  }, 2000);
  setInterval(pump, 250);
  claim();
})();
