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
  let capturedRecording, capturedActive;
  let generation = 0,
    stopped = false;
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
  function health(text) {
    $("capture-health").textContent = text;
  }
  async function claim() {
    try {
      lease = await request("claim");
      revision = 0;
      pending = undefined;
      lastState = undefined;
      $("browser-occupied").close();
      dirty = true;
      stopped = false;
      await pump();
    } catch (e) {
      lease = undefined;
      health("Unverified presentation evidence · browser occupied");
      if (!$("browser-occupied").open) $("browser-occupied").showModal();
    }
  }
  // Two animation frames surround a rendering opportunity. A task following the
  // second frame samples the still-applied state. DOM changes invalidate this
  // candidate. This establishes a browser rendering opportunity, not OS scanout.
  const frame = () => new Promise((resolve) => requestAnimationFrame(resolve));
  async function pump() {
    if (pumping || !lease || stopped) return;
    const rec = window.recordingLiveState?.()?.recording;
    if (!rec?.active || rec.status === "stopped") return;
    pumping = true;
    try {
      if (!pending && dirty && document.visibilityState === "visible") {
        const before = generation;
        await frame();
        const applied = window.recordingAppliedState();
        const presented = new Date().toISOString();
        await frame();
        await new Promise((resolve) => setTimeout(resolve, 0));
        if (before !== generation) {
          dirty = true;
          await request("gap", {
            from: presented,
            until: new Date().toISOString(),
            reason:
              "Display changed before rendering-opportunity confirmation; transient state unverified",
          });
          return;
        }
        const encoded = JSON.stringify(applied);
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
        const ack = await request("confirm", pending);
        lastConfirmed = Date.now();
        if (window.recordingLiveState?.()?.recording?.active)
          health(`Display state confirmed · revision ${ack.revision}`);
        pending = undefined;
      }
    } catch (e) {
      health("Unverified presentation evidence · " + e.message);
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
    },
    pump,
  };
  async function action(action) {
    const r = await fetch("/local/recording", {
      method: "POST",
      headers: { "Content-Type": "application/json", ...headers() },
      body: JSON.stringify({
        action,
        operator_at: new Date().toISOString(),
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
    $("snapshot-subset").textContent =
      `Saved subset: ${n} of ${$("snapshot-parties").children.length} parties. All remain listed in participation metadata.`;
  }
  function openSave() {
    const state = window.recordingLiveState();
    $("save-continuation").textContent =
      state.recording?.status === "stopped"
        ? "Recording is finally stopped. Dismissal preserves unsaved evidence."
        : "The recording continues after saving.";
    $("snapshot-parties").replaceChildren();
    for (const [id, d] of Object.entries(state.devices)) {
      const label = document.createElement("label"),
        input = document.createElement("input");
      input.type = "checkbox";
      input.value = id;
      input.checked = true;
      input.onchange = subset;
      label.append(
        input,
        document.createTextNode(
          d.snapshot.party.id + " · " + d.snapshot.party.name,
        ),
      );
      $("snapshot-parties").append(label);
    }
    $("snapshot-total").textContent =
      `${Object.keys(state.devices).length} participated · Select at least one`;
    subset();
    $("snapshot-error").textContent = "";
    if (!$("save-snapshot").open) $("save-snapshot").showModal();
  }
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
        `Saved ${receipt.name} · ${receipt.id} · ${receipt.confirmed_events} confirmed states · evidence limitations retained`;
    } catch (e) {
      $("snapshot-error").textContent = e.message;
    } finally {
      $("snapshot-submit").disabled = false;
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
