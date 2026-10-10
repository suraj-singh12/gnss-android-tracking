"use strict";
const $ = (id) => document.getElementById(id),
  hidden = new Set(),
  svgNS = "http://www.w3.org/2000/svg";
let state, policyRevision;
let selectedParty = localStorage.getItem("gnss-selected-party");
function selectParty(id) {
  selectedParty = id;
  localStorage.setItem("gnss-selected-party", id);
  locatedSOS = undefined;
}
let viewport, offlineMap, inspectedPoint, locatedSOS, terrain, activeMapID;
function applyAppearance() {
  const night = localStorage.getItem("gnss-appearance") === "night";
  document.documentElement.dataset.appearance = night ? "night" : "day";
  $("appearance").textContent = night ? "Day" : "Night";
  $("appearance").setAttribute(
    "aria-label",
    `Switch to ${night ? "Day" : "Night"} appearance`,
  );
  if (terrain) terrain.image = undefined;
  draw();
}
$("appearance").onclick = () => {
  localStorage.setItem(
    "gnss-appearance",
    document.documentElement.dataset.appearance === "night" ? "day" : "night",
  );
  applyAppearance();
};
$("open-about").onclick = () => $("about").showModal();
applyAppearance();
const fields = {
  minimum_forward_m: "Forward movement floor · m",
  minimum_backward_m: "Backward movement floor · m",
  maximum_accuracy_m: "Maximum accuracy · m",
  maximum_fix_age_s: "Maximum fix age · s",
  clock_tolerance_s: "Clock tolerance · s",
  maximum_speed_mps: "Maximum speed · m/s",
  maximum_gap_s: "Maximum gap · s",
  uncertainty_multiplier: "Uncertainty multiplier",
};
function el(tag, text, cls) {
  const e = document.createElement(tag);
  if (text !== undefined) e.textContent = text;
  if (cls) e.className = cls;
  return e;
}
function distance(m) {
  return m >= 1000 ? (m / 1000).toFixed(2) + " km" : m.toFixed(1) + " m";
}
function time(s) {
  return new Date(s).toLocaleString(undefined, { timeZoneName: "short" });
}
function color(id) {
  return state.devices[id].track_color;
}
function dash(id) {
  return state.devices[id].track_dash;
}
function showError(e) {
  $("error").hidden = !e;
  $("error").textContent = e ? e.message : "";
}
async function post(path, data) {
  const response = await fetch("/local/" + path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  });
  const b = await response.json();
  if (!response.ok) throw Error(b.message || "Command operation failed");
  await poll();
}
for (const action of ["stop", "resume", "clear"])
  $(action).onclick = () => {
    if (
      action === "clear" &&
      !confirm(
        "Clear this Recording and its tracks, distances and windows? Raw observations, devices, reporting settings and all SOS alerts are retained.",
      )
    )
      return;
    post("recording", {
      action,
      confirmed: action === "clear",
    }).catch(showError);
  };
function updateSessionChoice() {
  const available = state?.recording_session_available === true;
  $("session-mode").disabled = !available;
  $("session-mode-status").textContent = available
    ? "Uses reported Android session boundaries; Command remains authoritative."
    : "Beginning of session unavailable: wait for Android session metadata.";
  $("confirm-recording").disabled =
    !!state?.recording || (!available && $("session-mode").checked);
}
$("start").onclick = () => {
  $("current-mode").checked = true;
  $("recording-start-error").textContent = "";
  updateSessionChoice();
  $("recording-start").showModal();
};
$("recording-start-form").onchange = updateSessionChoice;
$("recording-start-form").onsubmit = async (e) => {
  e.preventDefault();
  $("confirm-recording").disabled = true;
  try {
    await post("recording", {
      action: "start",
      mode: $("recording-start-form").elements.mode.value,
    });
    $("recording-start").close();
  } catch (err) {
    $("recording-start-error").textContent = err.message;
  } finally {
    updateSessionChoice();
  }
};
$("all-observations").onchange = () => draw();
$("dots").onchange = () => {
  if (!$("dots").checkValidity()) {
    $("dots").reportValidity();
    return;
  }
  poll();
};
let audio;
let activeTone;
const seenSOS = new Set();
function stopTone() {
  if (activeTone) {
    try {
      activeTone.stop();
    } catch (_) {}
    activeTone = undefined;
  }
}
function beep() {
  if (audio?.state !== "running") return;
  stopTone();
  const tone = audio.createOscillator(),
    gain = audio.createGain();
  activeTone = tone;
  tone.frequency.value = 880;
  gain.gain.value = 0.12;
  tone.connect(gain);
  gain.connect(audio.destination);
  tone.onended = () => {
    tone.disconnect();
    gain.disconnect();
    if (activeTone === tone) activeTone = undefined;
  };
  tone.start();
  tone.stop(audio.currentTime + 0.18);
}
const alarm = new SosAlarm(beep, stopTone);
async function sosSound(test = false) {
  try {
    const Audio = window.AudioContext || window.webkitAudioContext;
    if (!Audio) throw Error("Audio unavailable");
    if (!audio && !test) throw Error("Audio requires operator activation");
    audio ||= new Audio();
    audio.onstatechange = () => {
      alarm.update(
        state?.sos_alerts?.some((a) => !a.operator_acknowledged_at),
        audio.state === "running",
      );
      if (audio.state !== "running") {
        $("sos-audio").textContent = "Enable SOS sound · currently blocked";
        $("sos-audio-status").textContent =
          "Audio suspended / blocked. Enable sound; visual SOS stays active.";
      }
    };
    if (test) await audio.resume();
    if (audio.state !== "running") throw Error("Audio blocked or suspended");
    const pending = state?.sos_alerts?.some((a) => !a.operator_acknowledged_at);
    alarm.update(pending, true);
    if (test && !pending) beep();
    $("sos-audio").textContent = "SOS sound enabled · Test";
    $("sos-audio-status").textContent =
      "Sound enabled · one shared beep every second until every SOS is acknowledged. Check speaker volume.";
  } catch (e) {
    $("sos-audio").textContent = "Enable SOS sound · currently blocked";
    $("sos-audio-status").textContent =
      "Audible alert blocked / unavailable. Enable audio and check speaker volume; visible SOS remains active.";
    alarm.update(alarm.pending, false);
  }
}
$("sos-audio").onclick = () => sosSound(true);
$("open-sos").onclick = $("sos-toast-view").onclick = () =>
  $("sos-panel").showModal();
$("sos-toast-dismiss").onclick = () => {
  $("sos-toast").hidden = true;
};
function detailsOpen(open) {
  document.querySelector("main").classList.toggle("details-closed", !open);
  $("toggle-details").setAttribute("aria-expanded", String(open));
}
$("toggle-details").onclick = () =>
  detailsOpen(
    document.querySelector("main").classList.contains("details-closed"),
  );
$("close-details").onclick = () => detailsOpen(false);
window.addEventListener("resize", () => {
  if (innerWidth <= 850) detailsOpen(false);
  if (innerWidth <= 550) {
    document.querySelector("main").classList.add("parties-closed");
    $("toggle-parties").setAttribute("aria-expanded", "false");
  }
});
$("toggle-parties").onclick = $("collapse-parties").onclick = () => {
  const closed = document
    .querySelector("main")
    .classList.toggle("parties-closed");
  $("toggle-parties").setAttribute("aria-expanded", String(!closed));
};
function renderSOS() {
  const alerts = [...(state.sos_alerts || [])].sort(
    (a, b) =>
      Number(!!a.operator_acknowledged_at) -
        Number(!!b.operator_acknowledged_at) ||
      b.triggered_at.localeCompare(a.triggered_at) ||
      a.device_id.localeCompare(b.device_id),
  );
  const pending = alerts.filter((a) => !a.operator_acknowledged_at).length;
  $("sos-heading").textContent = pending
    ? "SOS emergencies"
    : "SOS event history";
  $("sos-count").textContent = pending;
  $("open-sos").classList.toggle("active", pending > 0);
  $("open-sos").setAttribute(
    "aria-label",
    `${pending} unacknowledged SOS emergencies`,
  );
  let arrived = 0;
  for (const a of alerts) {
    const key = a.device_id + "/" + a.event_id;
    if (!seenSOS.has(key) && !a.operator_acknowledged_at) arrived++;
    seenSOS.add(key);
  }
  if (arrived) {
    $("sos-toast-text").textContent =
      `${arrived} new SOS · ${pending} require acknowledgement`;
    $("sos-toast").hidden = false;
  }
  if (!pending) $("sos-toast").hidden = true;
  const summary = `${pending} unacknowledged · ${alerts.length - pending} acknowledged. Receipt and operator acknowledgement are separate. Recording controls retain SOS.`;
  if ($("sos-summary").textContent !== summary)
    $("sos-summary").textContent = summary;
  // Reuse event-specific nodes so polling never removes keyboard focus or changes
  // the identity captured by a pending acknowledgement request.
  const root = $("sos-alerts");
  const focused = root.contains(document.activeElement)
    ? document.activeElement
    : null;
  let index = 0;
  for (const a of alerts.filter((a) => !a.operator_acknowledged_at)) {
    const key = a.device_id + "/" + a.event_id;
    let card = Array.from(root.children).find((c) => c.dataset.event === key);
    if (!card) {
      card = el("article", undefined, "sos-alert");
      card.dataset.event = key;
      const details = el("div");
      details.className = "sos-details";
      const button = el("button", "Acknowledge this SOS");
      button.onclick = async () => {
        button.disabled = true;
        try {
          await post("sos/acknowledge", {
            device_id: a.device_id,
            event_id: a.event_id,
          });
        } catch (e) {
          button.disabled = false;
          showError(e);
        }
      };
      const locate = el("button", "Locate event observation");
      locate.disabled = !a.fix;
      locate.onclick = () => {
        selectParty(a.device_id);
        locatedSOS = a;
        const [cx, cy] = GeoMap.project(a.fix.longitude, a.fix.latitude);
        viewport = { cx, cy, scale: 2 };
        $("sos-panel").close();
        detailsOpen(true);
        render();
        $("hover").textContent =
          `SOS event observation · ${a.fix.latitude}, ${a.fix.longitude} · ${time(a.fix.observed_at)} · not necessarily a current position`;
      };
      card.append(details, button, locate);
      root.append(card);
    }
    card.classList.toggle("acknowledged", !!a.operator_acknowledged_at);
    const f = a.fix;
    const details = card.querySelector(".sos-details");
    if (card.dataset.snapshot !== JSON.stringify(a)) {
      card.dataset.snapshot = JSON.stringify(a);
      details.replaceChildren(
        el("h2", `SOS · ${a.party.id} · ${a.party.name}`),
        el("div", `Device ${a.device_id} · Event ${a.event_id}`),
        el(
          "div",
          `Activated ${time(a.triggered_at)} · Snapshot ${time(a.captured_at)} · Command received ${time(a.received_at)}`,
        ),
        el(
          "div",
          f
            ? `Last credible reported coordinates: ${f.latitude}, ${f.longitude} · accuracy ${f.horizontal_accuracy_m == null ? "unknown" : "±" + f.horizontal_accuracy_m + " m"}`
            : "Coordinates unavailable at activation",
        ),
        el(
          "div",
          `GNSS at capture: ${a.gnss_status.replaceAll("_", " ")} · ${a.location_freshness.replaceAll("_", " ")}`,
        ),
        el(
          "div",
          f
            ? `Measured ${time(f.observed_at)} · age at capture ${(f.fix_age_ms / 1000).toFixed(1)} s. This is the event observation, not a current position.`
            : "SOS sent without waiting for GNSS",
        ),
        el(
          "strong",
          a.operator_acknowledged_at
            ? `Acknowledged by operator ${time(a.operator_acknowledged_at)}`
            : "UNACKNOWLEDGED — operator action required",
        ),
      );
    }
    card.querySelector("button").disabled = !!a.operator_acknowledged_at;
    card
      .querySelector("button")
      .setAttribute(
        "aria-label",
        `Acknowledge SOS for ${a.party.id}, activated ${time(a.triggered_at)}, event ${a.event_id}`,
      );
    if (root.children[index] !== card)
      root.insertBefore(card, root.children[index] || null);
    index++;
  }
  for (const card of Array.from(root.children)) {
    if (
      !alerts.some(
        (a) =>
          !a.operator_acknowledged_at &&
          card.dataset.event === a.device_id + "/" + a.event_id,
      )
    )
      card.remove();
  }
  if (focused && document.activeElement !== focused) focused.focus();
  alarm.update(pending > 0, audio?.state === "running");
}
function render() {
  renderSOS();
  if ($("event-history").open) renderEvents();
  $("projection-status").textContent = state.projection_error
    ? "Projection failed: " + state.projection_error
    : state.projection_pending
      ? "Projection catching up — displayed history is not fully synchronized"
      : "";
  $("connection").textContent = "● Command Connected";
  $("connection").classList.remove("unavailable");
  const recording = state.recording;
  $("recording").textContent = recording
    ? recording.active
      ? "Recording active · " + duration(recording.duration_s)
      : "Recording stopped · " + duration(recording.duration_s)
    : "No recording";
  if ($("recording-start").open) updateSessionChoice();
  $("start").disabled = !!recording;
  $("stop").disabled = !recording?.active;
  $("resume").disabled = !recording || recording.active;
  $("clear").disabled = !recording;
  const devices = Object.values(state.devices).sort((a, b) =>
    a.device_id.localeCompare(b.device_id),
  );
  $("count").textContent = devices.length;
  $("party-summary").textContent =
    `${devices.filter((d) => d.contact_condition === "healthy").length}/${devices.length} Parties`;
  $("no-parties").hidden = devices.length > 0;
  if (!state.devices[selectedParty]) selectedParty = devices[0]?.device_id;
  // Preserve focused edits while polling; all content is created as text nodes.
  if (
    !$("devices").contains(document.activeElement) &&
    !$("selected-detail").contains(document.activeElement)
  ) {
    $("devices").replaceChildren();
    $("selected-detail").replaceChildren();
    if (!devices.length)
      $("selected-detail").append(
        el(
          "p",
          "Select a party to inspect location, history and reporting settings.",
          "muted",
        ),
      );
    for (const d of devices) {
      const card = el("section", undefined, "device"),
        title = el("div", undefined, "device-title"),
        swatch = el("span", undefined, "swatch");
      swatch.style.backgroundColor = color(d.device_id);
      title.append(swatch, el("span", d.snapshot.party.id));
      const toggle = el("input");
      toggle.type = "checkbox";
      toggle.checked = !hidden.has(d.device_id);
      toggle.setAttribute(
        "aria-label",
        "Show track for " + d.snapshot.party.id,
      );
      toggle.onchange = () => {
        toggle.checked ? hidden.delete(d.device_id) : hidden.add(d.device_id);
        draw();
      };
      title.append(toggle);
      card.append(
        title,
        el("small", d.snapshot.party.name + " · " + d.device_id.slice(0, 8)),
        el("small", "Qualified travelled distance"),
        el("div", distance(d.total_m), "distance"),
      );
      const labels = {
        healthy: "Command Connected",
        delayed: "Contact delayed",
        contact_lost: "Offline · contact lost",
      };
      card.append(el("div", labels[d.contact_condition], d.contact_condition));
      const status = el("div", undefined, "status");
      status.append(
        el("div", "Last seen " + time(d.last_contact)),
        el(
          "div",
          "GNSS " +
            d.gnss_condition.replaceAll("_", " ") +
            " · " +
            (d.location?.fix.horizontal_accuracy_m == null
              ? "accuracy unknown"
              : d.location.fix.horizontal_accuracy_m + " m"),
        ),
      );
      status.append(
        el(
          "div",
          d.current_position
            ? "Current position available"
            : "Current position unavailable — check GNSS age/quality",
        ),
      );
      if (d.location_age_s != null)
        status.append(
          el(
            "div",
            "Location age " + Math.max(0, d.location_age_s).toFixed(0) + " s",
          ),
        );
      status.append(
        el(
          "div",
          "Battery " +
            (d.snapshot.health.battery_percent == null
              ? "unavailable"
              : d.snapshot.health.battery_percent + "%"),
        ),
        el(
          "div",
          d.snapshot.config_state.effective_reporting_interval_s +
            " s reporting · " +
            (d.config_converged ? "settings applied" : "settings pending"),
        ),
      );
      if (d.field_evidence) {
        const e = d.field_evidence;
        status.append(
          el(
            "div",
            `Reports received: ${e.reports_received} · Raw fixes: ${e.raw_fixes} · Useful points: ${e.useful_points}`,
          ),
          el(
            "div",
            `Cadence expected: ${e.expected_interval_s} s · observed: ${e.median_recent_unique_receipt_gap_s == null ? "unavailable" : "~" + e.median_recent_unique_receipt_gap_s.toFixed(1) + " s"}`,
          ),
        );
      }
      if (d.history)
        status.append(
          el(
            "div",
            `History: ${d.history.condition} · received through ${d.history.received_through} · processed through ${d.history.processed_through}`,
          ),
          el(
            "div",
            d.history.last_reported_phone_queue
              ? `Phone queue measured ${time(d.history.last_reported_phone_queue.measured_at)}: ${d.history.last_reported_phone_queue.pending_observations} pending${d.history.phone_state_recent ? "" : " (last reported, current state unknown)"}`
              : "Phone queue unavailable",
          ),
        );
      if (d.field_evidence)
        status.append(
          el(
            "div",
            `Delayed history received: ${d.field_evidence.delayed_location_reports ?? 0} GNSS · ${d.field_evidence.delayed_routine_reports ?? 0} routine · pending history requires Android evidence`,
          ),
        );
      card.append(status);
      const form = el("form", undefined, "override"),
        input = el("input");
      input.type = "number";
      input.min = 5;
      input.max = 86400;
      input.step = 5;
      input.value =
        d.desired_config.reporting_interval_override_s ??
        d.snapshot.config_state.local_reporting_interval_s;
      input.setAttribute(
        "aria-label",
        "Reporting interval override for " + d.snapshot.party.id,
      );
      const set = el("button", "Set"),
        clear = el("button", "Clear");
      clear.type = "button";
      clear.onclick = () =>
        post("override", {
          device_id: d.device_id,
          reporting_interval_override_s: null,
        }).catch(showError);
      form.onsubmit = (e) => {
        e.preventDefault();
        if (input.reportValidity())
          post("override", {
            device_id: d.device_id,
            reporting_interval_override_s: Number(input.value),
          }).catch(showError);
      };
      form.append(input, set, clear);
      card.append(
        el("small", "Reporting interval override · seconds"),
        form,
        el(
          "small",
          "Remote override " +
            (d.desired_config.reporting_interval_override_s ?? "cleared") +
            " · delivered in next ACK",
        ),
      );
      const overview = el(
        "section",
        undefined,
        "device" + (selectedParty === d.device_id ? " selected" : ""),
      );
      const select = el("button", d.snapshot.party.id, "party-select");
      select.setAttribute(
        "aria-pressed",
        String(selectedParty === d.device_id),
      );
      select.onclick = () => {
        selectParty(d.device_id);
        detailsOpen(true);
        select.blur();
        render();
      };
      const overviewTitle = el("div", undefined, "device-title");
      const dot = el("span", undefined, "swatch");
      dot.style.backgroundColor = color(d.device_id);
      overviewTitle.append(dot, select, toggle);
      overview.append(
        overviewTitle,
        el("small", d.snapshot.party.name),
        el("div", labels[d.contact_condition], d.contact_condition),
        el("div", gnssLabel(d)),
        el("small", historyLabel(d)),
      );
      if (
        state.sos_alerts?.some(
          (a) => a.device_id === d.device_id && !a.operator_acknowledged_at,
        )
      )
        overview.append(el("strong", "SOS Received · needs acknowledgement"));
      $("devices").append(overview);
      if (selectedParty === d.device_id) {
        card.className = "selected-content";
        title.replaceChildren(swatch, el("h2", d.snapshot.party.id));
        const f = d.location?.fix;
        if (f)
          card.insertBefore(
            el(
              "div",
              `${f.latitude.toFixed(6)}, ${f.longitude.toFixed(6)} · observed ${time(f.observed_at)}`,
              "detail-block",
            ),
            status,
          );
        const summary = el("div", undefined, "status detail-block");
        summary.append(
          el(
            "div",
            gnssLabel(d) +
              " · accuracy " +
              (f?.horizontal_accuracy_m == null
                ? "unknown"
                : "±" + f.horizontal_accuracy_m + " m"),
          ),
          el("div", "Last Command contact: " + time(d.last_contact)),
          el(
            "div",
            "Battery: " +
              (d.snapshot.health.battery_percent == null
                ? "Unavailable"
                : d.snapshot.health.battery_percent + "%"),
          ),
          el("div", historyLabel(d)),
          el(
            "small",
            d.location_age_s == null
              ? "Observation age unavailable"
              : "Observation age: " +
                  Math.max(0, d.location_age_s).toFixed(0) +
                  " s",
          ),
        );
        card.insertBefore(summary, form);
        const diagnostics = el("details");
        diagnostics.append(
          el("summary", "Diagnostics & significant events"),
          status,
        );
        for (const event of state.sos_alerts ?? []) {
          if (event.device_id !== d.device_id) continue;
          diagnostics.append(
            el(
              "p",
              `SOS raised ${time(event.triggered_at)} · SOS Received ${time(event.received_at)} · ${event.operator_acknowledged_at ? "SOS Acknowledged " + time(event.operator_acknowledged_at) : "Operator acknowledgement required"}`,
            ),
          );
        }
        card.append(diagnostics);
        $("selected-detail").append(card);
      }
    }
  }
  if (
    policyRevision !== state.policy.revision &&
    !$("quality-form").contains(document.activeElement)
  ) {
    policyRevision = state.policy.revision;
    $("quality-form").replaceChildren();
    for (const [key, label] of Object.entries(fields)) {
      const row = el("label", label),
        input = el("input");
      const enabled = el("input");
      enabled.type = "checkbox";
      enabled.name = "enabled_" + key;
      enabled.checked = state.policy.enabled?.[key] !== false;
      enabled.setAttribute("aria-label", "Enable " + label);
      row.append(enabled);
      input.name = key;
      input.setAttribute("aria-label", label);
      input.type = "number";
      input.step = "any";
      input.min = key === "uncertainty_multiplier" ? "1" : "0.001";
      input.max = "86400";
      input.value = state.policy[key];
      row.append(input);
      row.append(el("small", descriptions[key]));
      $("quality-form").append(row);
    }
    $("quality-form").append(el("button", "Apply quality settings"));
  }
  draw();
}
$("quality-form").onsubmit = (e) => {
  e.preventDefault();
  const p = { enabled: {} };
  for (const key of Object.keys(fields)) {
    p[key] = Number(e.target.elements[key].value);
    p.enabled[key] = e.target.elements["enabled_" + key].checked;
  }
  $("quality-progress").textContent =
    "Applying quality rules and rebuilding history…";
  post("quality", p)
    .then(() => {
      $("quality-progress").textContent = state.projection_pending
        ? "History reconstruction in progress…"
        : "Quality settings applied.";
    })
    .catch((e) => {
      $("quality-progress").textContent = "Could not apply settings.";
      showError(e);
    });
};
function duration(seconds) {
  const n = Math.max(0, Math.floor(seconds || 0));
  return `${Math.floor(n / 3600)}h ${Math.floor((n % 3600) / 60)}m ${n % 60}s`;
}
function gnssLabel(d) {
  return d.current_position
    ? "GNSS Fresh"
    : d.gnss_condition === "stale"
      ? "GNSS Stale"
      : "GNSS · " + d.gnss_condition.replaceAll("_", " ");
}
function historyLabel(d) {
  const h = d.history,
    q = h?.last_reported_phone_queue;
  if (!h) return "History · unavailable";
  const condition =
    {
      catching_up: "Synchronizing",
      unresolved: "History Incomplete",
      incomplete: "History Incomplete",
      synchronized: "Fully synchronized",
      unknown: "Unknown",
    }[h.condition] || "Unknown";
  return `History: ${condition}${q?.unresolved_sequences?.length ? " · " + q.unresolved_sequences.length + " unresolved" : ""}${q ? " · " + q.pending_observations + " pending" : ""}${q && !h.phone_state_recent ? " (last reported; current count unknown)" : ""}`;
}
const descriptions = {
  maximum_accuracy_m: "Reject imprecise fixes above this accuracy radius.",
  maximum_fix_age_s: "Reject observations too old at capture.",
  clock_tolerance_s: "Allow bounded device clock difference.",
  maximum_speed_mps: "Reject implausible movement speeds.",
  minimum_forward_m: "Minimum accepted forward movement.",
  minimum_backward_m: "Minimum accepted reverse movement.",
  maximum_gap_s: "Break sections across long observation gaps.",
  uncertainty_multiplier:
    "Scale position uncertainty when qualifying movement.",
};
for (const [button, dialog] of [
  ["open-quality", "quality"],
  ["open-map", "map-settings"],
])
  $(button).onclick = () => $(dialog).showModal();
for (const button of document.querySelectorAll("[data-close]"))
  button.onclick = () => $(button.dataset.close).close();
let eventEvidence = [];
let eventSnapshot;
function renderEvents() {
  const root = $("history-events"),
    filter = $("event-filter").value;
  const snapshot = JSON.stringify([filter, state?.sos_alerts, eventEvidence]);
  if (snapshot === eventSnapshot && root.children.length) return;
  eventSnapshot = snapshot;
  const opened = new Set(
    [...root.querySelectorAll("details[open]")].map((e) => e.dataset.key),
  );
  const focused = root.contains(document.activeElement)
    ? document.activeElement.closest("details")?.dataset.key
    : undefined;
  root.replaceChildren();
  if (filter !== "operations") {
    for (const a of [...(state?.sos_alerts || [])].reverse()) {
      const row = el("details", undefined, "history-event");
      row.dataset.key = "sos/" + a.device_id + "/" + a.event_id;
      row.open = opened.has(row.dataset.key);
      row.append(
        el(
          "summary",
          `SOS · ${a.party.id} · ${time(a.triggered_at)} · ${a.operator_acknowledged_at ? "Acknowledged" : "Unacknowledged"}`,
        ),
      );
      row.append(
        el(
          "p",
          `Command receipt: ${time(a.received_at)} · Operator ACK: ${a.operator_acknowledged_at ? time(a.operator_acknowledged_at) : "not acknowledged"}`,
        ),
      );
      row.append(
        el(
          "p",
          a.fix
            ? `Event coordinates ${a.fix.latitude}, ${a.fix.longitude} · accuracy ±${a.fix.horizontal_accuracy_m ?? "unknown"} m · measured ${time(a.fix.observed_at)}`
            : "No GNSS observation at activation",
        ),
      );
      row.append(el("pre", JSON.stringify(a, null, 2)));
      root.append(row);
    }
  }
  if (filter !== "sos")
    for (const e of eventEvidence) {
      const row = el("details", undefined, "history-event");
      row.dataset.key = "operation/" + e.ordinal;
      row.open = opened.has(row.dataset.key);
      row.append(
        el(
          "summary",
          `${e.kind.replaceAll("_", " ")} · ${time(e.at)}${e.device_id ? " · " + (state?.devices[e.device_id]?.snapshot.party.id || e.device_id.slice(0, 8)) : ""}`,
        ),
      );
      row.append(el("pre", JSON.stringify(e, null, 2)));
      root.append(row);
    }
  if (!root.children.length) root.append(el("p", "No events in this view."));
  if (focused)
    [...root.children]
      .find((e) => e.dataset.key === focused)
      ?.querySelector("summary")
      ?.focus();
}
async function loadEvents() {
  const response = await fetch("/local/events");
  if (!response.ok) throw Error("Event history unavailable");
  eventEvidence = await response.json();
  renderEvents();
}
$("open-history").onclick = () => {
  $("event-history").showModal();
  loadEvents().catch(showError);
};
$("event-filter").onchange = renderEvents;
let preparedMap;
let preparationRevision = 0;
let downloadAbort, lastDownloadReport, retryTerrainRendering, terrainGeneration;
function showDownloadReport(report) {
  lastDownloadReport = report;
  $("download-report-text").textContent = JSON.stringify(report, null, 2);
}
async function readDownloadReport(id, since = 0) {
  const r = await fetch(
    `/local/maps/${encodeURIComponent(id)}/download-report`,
  );
  if (r.ok) {
    const report = await r.json();
    if (since && Date.parse(report.started_at) < since) return false;
    showDownloadReport({
      ...report,
      ...(terrainGeneration?.id === id
        ? { browser_terrain_generation_ms: terrainGeneration.ms }
        : {}),
    });
    return true;
  }
  return false;
}
$("choose-saved-map").onclick = () => {
  $("maps-library").open = true;
  $("saved-maps").focus();
};
$("cancel-download").onclick = () => downloadAbort?.abort();
$("copy-download-report").onclick = async () => {
  try {
    await navigator.clipboard.writeText($("download-report-text").textContent);
  } catch {
    $("download-diagnostics").open = true;
    $("download-report-text").focus();
  }
};
$("export-download-report").onclick = () => {
  const url = URL.createObjectURL(
    new Blob([$("download-report-text").textContent], { type: "text/plain" }),
  );
  const a = document.createElement("a");
  a.href = url;
  a.download = "terrain-download-report.txt";
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};
async function mapPost(path, data) {
  const response = await fetch("/local/" + path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  });
  const result = await response.json();
  if (!response.ok) throw Error(result.message || "Map operation failed");
  return result;
}
async function refreshMaps(selected) {
  const response = await fetch("/local/maps");
  if (!response.ok) throw Error("Saved map library unavailable");
  const maps = await response.json();
  $("saved-maps").replaceChildren(new Option("Choose saved map", ""));
  $("saved-map-list").replaceChildren();
  for (const m of maps) {
    if (!m.bounds) {
      try {
        const data = await fetch(`/local/maps/${encodeURIComponent(m.id)}`);
        if (data.ok)
          m.bounds = GeoMap.parse(await data.text(), m.source).bounds;
      } catch {}
    }
    $("saved-maps").add(
      new Option(`${m.source} · ${(m.bytes / 1024).toFixed(1)} KB`, m.id),
    );
    const row = el("div", undefined, "saved-map-row");
    const select = el("button", m.source);
    select.onclick = () => {
      $("saved-maps").value = m.id;
      loadSavedMap(m.id).catch(showError);
    };
    row.dataset.mapId = m.id;
    row.append(
      select,
      el(
        "small",
        `${m.bounds ? m.bounds.map((v) => v.toFixed(4)).join(", ") : "Imported geographic coverage"} · ${
          Object.entries(m.components || {})
            .filter(([, v]) => v)
            .map(([k]) => k)
            .join(", ") || "Vectors only"
        }`,
      ),
    );
    $("saved-map-list").append(row);
  }
  if (!maps.length)
    $("saved-map-list").append(
      el("p", "No saved maps yet. Download or import a map."),
    );
  if (selected) $("saved-maps").value = selected;
}
function useMap(text, source, id) {
  const parsed = GeoMap.parse(text, source);
  offlineMap = parsed;
  activeMapID = id;
  $("current-map-name").textContent = source;
  for (const row of $("saved-map-list").children)
    row.classList.toggle("selected", row.dataset.mapId === id);
  terrain = undefined;
  updateLayers();
  $("map-mode").value = "offline";
  $("map-warning").textContent = "";
  $("map-metadata").textContent =
    `Source: ${source} · WGS84 / EPSG:4326 · Display EPSG:3857 · Coverage ${parsed.bounds.map((n) => n.toFixed(5)).join(", ")} · ${parsed.count} coordinates · saved locally`;
  $("map-attribution").textContent = parsed.attribution;
  $("export-map").href = "/local/maps/" + encodeURIComponent(id);
  $("export-map").setAttribute("download", "offline-map.geojson");
  localStorage.setItem("gnss-map-id", id);
  viewport = undefined;
  draw();
  localStorage.setItem("gnss-map-mode", "offline");
}
async function loadSavedMap(id) {
  const response = await fetch("/local/maps/" + encodeURIComponent(id));
  if (!response.ok)
    throw Error("Saved map unavailable; blank canvas remains available");
  const source =
    $("saved-maps").selectedOptions[0]?.textContent || "Saved offline map";
  useMap(await response.text(), source, id);
  await loadTerrain(id);
  await readDownloadReport(id);
}
const terrainLayers = ["hillshade", "contours", "elevation"];
function updateLayers() {
  for (const kind of terrainLayers) {
    const control = $("layer-" + kind),
      available =
        !!terrain?.components[kind] &&
        !(kind === "contours" && terrain.contour_error);
    control.disabled = !available;
    control.checked =
      available &&
      localStorage.getItem(`gnss-layer-${activeMapID}-${kind}`) === "true";
    $("availability-" + kind).textContent = available
      ? control.checked
        ? "Downloaded · visible"
        : "Downloaded · hidden"
      : kind === "contours" && terrain?.contour_error
        ? "Unavailable: contour complexity limit; choose a smaller area."
        : "Not downloaded / prepared";
  }
  $("current-map-layers").textContent = terrain
    ? "Available: " +
      terrainLayers.filter((k) => terrain.components[k]).join(", ") +
      " · visibility via Layers"
    : "Vectors only · no terrain downloaded";
  for (const id of ["delete-map", "prepare-terrain", "download-terrain"])
    $(id).disabled = !activeMapID || !!downloadAbort;
  $("export-map").setAttribute("aria-disabled", String(!activeMapID));
  $("terrain-metadata").textContent = terrain
    ? `${terrain.source} · ${terrain.arc_seconds} arc sec (~${Math.round((terrain.arc_seconds / 3600) * 111320)} m north–south) · ${terrain.vertical_datum} · interpolated, not survey-grade${terrain.contour ? " · contours " + terrain.contour.interval + " m" : ""}${terrain.provenance ? " · " + terrain.provenance.attribution : ""}`
    : "No terrain for this map. Download / retry terrain or import a DEM in Map & layers.";
  $("export-terrain").hidden = !terrain;
  if (terrain)
    $("export-terrain").href =
      `/local/maps/${encodeURIComponent(activeMapID)}/terrain-file`;
}
async function loadTerrain(id) {
  const r = await fetch(`/local/maps/${encodeURIComponent(id)}/terrain`);
  if (activeMapID !== id) return;
  if (r.status === 404) {
    terrain = undefined;
    updateLayers();
    draw();
    return;
  }
  if (!r.ok) throw Error("Saved terrain unavailable; vector map retained.");
  const grid = await r.json();
  if (activeMapID !== id) return;
  const generationStart = performance.now();
  try {
    terrain = Terrain.prepare(grid);
  } catch (e) {
    if (lastDownloadReport)
      showDownloadReport({
        ...lastDownloadReport,
        terrain_generation_error: e.message,
      });
    throw Error("Terrain generation failed: " + e.message);
  }
  terrainGeneration = { id, ms: performance.now() - generationStart };
  if (lastDownloadReport?.map_id === id)
    showDownloadReport({
      ...lastDownloadReport,
      browser_terrain_generation_ms: terrainGeneration.ms,
      ...(terrain.contour_error
        ? { terrain_generation_warning: terrain.contour_error }
        : {}),
    });
  updateLayers();
  draw();
}
$("open-layers").onclick = () => {
  updateLayers();
  const dialog = $("layers");
  if (dialog.open) dialog.close();
  else dialog.show();
  $("open-layers").setAttribute("aria-expanded", String(dialog.open));
};
$("layers").onclose = () =>
  $("open-layers").setAttribute("aria-expanded", "false");
$("layers").onkeydown = (event) => {
  if (event.key === "Escape") {
    event.preventDefault();
    $("layers").close();
    $("open-layers").focus();
  }
};
for (const kind of terrainLayers)
  $("layer-" + kind).onchange = () => {
    localStorage.setItem(
      `gnss-layer-${activeMapID}-${kind}`,
      String($("layer-" + kind).checked),
    );
    updateLayers();
    draw();
  };
async function attachTerrain(id, file, components) {
  if (!file)
    throw Error(
      "Select a local SRTM DEM or use Download / retry selected terrain.",
    );
  if (file.size > 3601 * 3601 * 2)
    throw Error("DEM exceeds the bounded HGT import limit.");
  const data = await new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(",")[1]);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
  await mapPost(`maps/${encodeURIComponent(id)}/terrain`, {
    name: file.name,
    data,
    components,
  });
  for (const kind of terrainLayers)
    localStorage.setItem(`gnss-layer-${id}-${kind}`, "false");
  await loadTerrain(id);
}
$("prepare-terrain").onclick = async () => {
  const id = activeMapID;
  if (!id) {
    $("terrain-status").textContent = "Save/select a vector map first.";
    return;
  }
  $("prepare-terrain").disabled = true;
  try {
    await attachTerrain(
      id,
      $("terrain-file").files[0],
      Object.fromEntries(
        terrainLayers.map((k) => [k, $("prepare-" + k).checked]),
      ),
    );
    $("terrain-status").textContent = terrain?.contour_error
      ? "DEM saved locally. Contours unavailable at this area's complexity; hillshade/elevation and export remain available. Choose a smaller area for contours."
      : "Prepared and saved locally. Layers start OFF; use Layers to enable them. DEM estimate is not receiver GNSS altitude.";
  } catch (e) {
    $("terrain-status").textContent =
      e.message +
      " · Vector map retained; reopen this saved map to verify terrain.";
  } finally {
    $("prepare-terrain").disabled = false;
  }
};
async function downloadTerrain(id, components) {
  if (downloadAbort) throw Error("A terrain download is already active.");
  if (
    retryTerrainRendering?.id === id &&
    retryTerrainRendering.components === JSON.stringify(components)
  ) {
    await loadTerrain(id);
    retryTerrainRendering = undefined;
    return;
  }
  downloadAbort = new AbortController();
  const operationStarted = Date.now();
  $("cancel-download").hidden = false;
  $("download-progress").hidden = false;
  $("download-progress").textContent = "Connecting to elevation provider…";
  $("download-terrain").disabled = true;
  $("download-map").disabled = true;
  $("prepare-terrain").disabled = true;
  $("delete-map").disabled = true;
  for (const control of $("map-settings").querySelectorAll(
    "#saved-maps, #map-mode, #map-file, #choose-saved-map, .saved-map-row button",
  ))
    control.disabled = true;
  try {
    const response = await fetch(
      `/local/maps/${encodeURIComponent(id)}/terrain-download`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/x-ndjson",
        },
        body: JSON.stringify({ components }),
        signal: downloadAbort.signal,
      },
    );
    if (!response.ok) {
      const error = await response.json();
      throw Error(error.message || "Terrain acquisition failed");
    }
    if (!(response.headers.get("content-type") || "").includes("ndjson")) {
      const error = await response.json();
      throw Error(error.message || "Download progress stream unavailable");
    }
    const reader = response.body.getReader(),
      decoder = new TextDecoder();
    let pending = "",
      final;
    function accept(event) {
      if (event.report) {
        showDownloadReport(event.report);
        final = event;
      }
      const labels = {
        connecting: "Connecting to elevation provider…",
        downloading: `Downloading elevation · ${((event.received_bytes || 0) / 1048576).toFixed(2)} MiB received · ${Math.round((event.elapsed_ms || 0) / 1000)}s`,
        "retry backoff": `Retrying after transient failure · attempt ${event.attempt}`,
        "processing terrain": "Processing elevation terrain…",
        saving: "Saving offline terrain…",
        ready: "Terrain saved · preparing selected display layers…",
        failed: event.error,
        cancelled: "Download cancelled · existing maps retained",
      };
      $("download-progress").textContent = labels[event.stage] || event.stage;
    }
    while (true) {
      const { value, done } = await reader.read();
      pending += decoder.decode(value || new Uint8Array(), { stream: !done });
      let line;
      while ((line = pending.indexOf("\n")) >= 0) {
        const text = pending.slice(0, line);
        pending = pending.slice(line + 1);
        if (text.trim()) accept(JSON.parse(text));
      }
      if (done) break;
    }
    if (!final || final.stage !== "ready")
      throw Error(
        final?.error || "Terrain download interrupted before completion",
      );
    retryTerrainRendering = { id, components: JSON.stringify(components) };
  } catch (e) {
    $("download-progress").textContent =
      e.name === "AbortError"
        ? "Download cancelled · vector map and previous terrain retained"
        : e.message;
    await new Promise((resolve) => setTimeout(resolve, 150));
    const recorded = await readDownloadReport(
      id,
      operationStarted - 1000,
    ).catch(() => false);
    if (!recorded && e.name === "AbortError")
      showDownloadReport({
        map_id: id,
        started_at: new Date(operationStarted).toISOString(),
        outcome: "client_cancelled",
        server_report_pending: true,
        attempts: null,
        note: "Client request cancelled. Reopen the saved map to retrieve the persisted server report; provider timings are not yet available.",
      });
    throw e.name === "AbortError" ? Error("Download cancelled") : e;
  } finally {
    downloadAbort = undefined;
    $("cancel-download").hidden = true;
    $("download-terrain").disabled = false;
    $("prepare-terrain").disabled = false;
    $("delete-map").disabled = false;
    for (const control of $("map-settings").querySelectorAll(
      "#saved-maps, #map-mode, #map-file, #choose-saved-map, .saved-map-row button",
    ))
      control.disabled = false;
  }
  for (const kind of terrainLayers)
    localStorage.setItem(`gnss-layer-${id}-${kind}`, "false");
  await loadTerrain(id);
  retryTerrainRendering = undefined;
  $("download-progress").textContent = "Map ready for offline use";
  await refreshMaps(id);
}
$("download-terrain").onclick = async () => {
  if (!activeMapID) {
    $("terrain-status").textContent = "Save/select a vector map first.";
    return;
  }
  $("download-terrain").disabled = true;
  $("terrain-status").textContent = "Acquiring real elevation data…";
  try {
    await downloadTerrain(
      activeMapID,
      Object.fromEntries(
        terrainLayers.map((k) => [k, $("prepare-" + k).checked]),
      ),
    );
    $("terrain-status").textContent =
      "Terrain saved for offline use. Layers start OFF; enable them in Layers.";
  } catch (e) {
    $("terrain-status").textContent =
      e.message +
      " · Vector map and previous terrain retained. Retry selected terrain when connectivity returns.";
  } finally {
    $("download-terrain").disabled = false;
  }
};
$("delete-map").onclick = async () => {
  if (
    !activeMapID ||
    !confirm(
      "Delete this saved map and its associated terrain? GNSS, recordings and SOS are retained.",
    )
  )
    return;
  try {
    await mapPost(`maps/${encodeURIComponent(activeMapID)}/delete`, {});
    offlineMap = terrain = undefined;
    activeMapID = undefined;
    localStorage.removeItem("gnss-map-id");
    $("map-mode").value = "blank";
    localStorage.setItem("gnss-map-mode", "blank");
    $("map-metadata").textContent = "No offline map loaded.";
    $("export-map").removeAttribute("href");
    await refreshMaps();
    updateLayers();
    draw();
  } catch (e) {
    showError(e);
  }
};
$("saved-maps").onchange = () => {
  if ($("saved-maps").value)
    loadSavedMap($("saved-maps").value).catch(showError);
};
$("search-map").onclick = async () => {
  $("search-map").disabled = true;
  $("map-preparation-status").textContent = "Searching OpenStreetMap…";
  try {
    const results = await mapPost("map-search", {
      text: $("map-search").value,
    });
    $("map-search-results").replaceChildren(new Option("Choose a place", ""));
    $("map-search-results").hidden = results.length === 0;
    for (const place of results)
      $("map-search-results").add(
        new Option(place.display_name, JSON.stringify([place.lat, place.lon])),
      );
    $("map-preparation-status").textContent =
      `${results.length} places · choose a center, then Preview.`;
  } catch (e) {
    $("map-preparation-status").textContent = e.message;
  } finally {
    $("search-map").disabled = false;
  }
};
$("map-search-results").onchange = () => {
  if ($("map-search-results").value) {
    const [lat, lon] = JSON.parse($("map-search-results").value);
    $("map-lat").value = lat;
    $("map-lon").value = lon;
    invalidatePreparation();
  }
};
function invalidatePreparation() {
  preparationRevision++;
  preparedMap = undefined;
  $("download-map").disabled = true;
}
for (const id of ["map-lat", "map-lon", "map-width", "map-height"])
  $(id).oninput = invalidatePreparation;
for (const id of [
  "download-hillshade",
  "download-contours",
  "download-elevation",
])
  $(id).onchange = invalidatePreparation;
$("map-area").onchange = () => {
  if ($("map-area").value !== "custom")
    $("map-width").value = $("map-height").value = $("map-area").value;
  invalidatePreparation();
  $("custom-map-dimensions").hidden = $("map-area").value !== "custom";
};
$("custom-map-dimensions").hidden = $("map-area").value !== "custom";
$("preview-map").onclick = async () => {
  invalidatePreparation();
  for (const id of ["map-lat", "map-lon", "map-width", "map-height"])
    if (!$(id).reportValidity()) return;
  $("preview-map").disabled = true;
  $("map-preparation-status").textContent =
    "Preparing bounded OSM vector area…";
  const revision = preparationRevision;
  const area = {
    lat: Number($("map-lat").value),
    lon: Number($("map-lon").value),
    width: Number($("map-width").value),
    height: Number($("map-height").value),
  };
  const components = Object.fromEntries(
    terrainLayers.map((k) => [k, $("download-" + k).checked]),
  );
  try {
    const data = await mapPost("map-preview", area);
    if (revision !== preparationRevision)
      throw Error(
        "Area changed while preparing. Preview the new area before downloading.",
      );
    const text = JSON.stringify(data),
      parsed = GeoMap.parse(text, "OpenStreetMap");
    preparedMap = {
      source: `OSM ${area.lat}, ${area.lon} · ${area.width} × ${area.height} m`,
      data,
      components,
    };
    $("map-preparation-status").textContent =
      `${data.features.length} features · ${(new TextEncoder().encode(text).length / 1024).toFixed(1)} KB · centre ${area.lat}, ${area.lon} · selected bounds W/S/E/N ${data.requested_bounds?.map((v) => v.toFixed(6)).join(", ") || "provider fixture: bounds unavailable"} · roads, paths, buildings, water/land where available. Relations/tiles are not downloaded. Save for offline use.`;
    $("map-preview").replaceChildren();
    const a = GeoMap.project(parsed.bounds[0], parsed.bounds[1]),
      b = GeoMap.project(parsed.bounds[2], parsed.bounds[3]);
    const scale = Math.min(
      380 / Math.max(1, b[0] - a[0]),
      160 / Math.max(1, b[1] - a[1]),
    );
    const point = (p) => [
      200 + (p[0] - (a[0] + b[0]) / 2) * scale,
      90 - (p[1] - (a[1] + b[1]) / 2) * scale,
    ];
    for (const shape of parsed.shapes) {
      if (shape.type === "polygon")
        $("map-preview").append(
          svg("path", {
            d: shape.rings
              .map(
                (r) => "M" + r.map((p) => point(p).join(",")).join("L") + "Z",
              )
              .join(" "),
            fill: "#d4e2d1",
            stroke: "#718d7b",
            "fill-rule": "evenodd",
          }),
        );
      else if (shape.type === "line")
        $("map-preview").append(
          svg("polyline", {
            points: shape.coordinates.map((p) => point(p).join(",")).join(" "),
            fill: "none",
            stroke: "#718d7b",
          }),
        );
    }
    $("download-map").disabled = false;
  } catch (e) {
    $("map-preparation-status").textContent =
      e.message + " · Existing maps are retained.";
  } finally {
    $("preview-map").disabled = false;
  }
};
$("download-map").onclick = async () => {
  if (!preparedMap) return;
  const candidate = preparedMap;
  $("download-map").disabled = true;
  try {
    const saved = await mapPost("maps", candidate);
    await refreshMaps(saved.id);
    await loadSavedMap(saved.id);
    for (const kind of terrainLayers)
      $("prepare-" + kind).checked = !!candidate.components?.[kind];
    if (Object.values(candidate.components || {}).some(Boolean)) {
      $("map-preparation-status").textContent =
        "Vector map saved. Acquiring real elevation data…";
      try {
        await downloadTerrain(saved.id, candidate.components);
      } catch (e) {
        $("map-preparation-status").textContent =
          "Vector map saved for offline use. Terrain unavailable: " +
          e.message +
          " · Select this saved map and use Download / retry selected terrain.";
        preparedMap = undefined; // retry terrain, never create duplicate vectors
        return;
      }
    }
    preparedMap = undefined;
    $("map-preparation-status").textContent =
      "Saved locally · available after restart without internet." +
      (terrain?.contour_error
        ? " Contours unavailable: complexity limit; choose a smaller area. Other terrain retained."
        : "");
  } catch (e) {
    $("map-preparation-status").textContent =
      e.message +
      " · Any saved vector map is retained; terrain can be prepared later.";
    $("download-map").disabled = false;
  }
};
refreshMaps()
  .then(async () => {
    const id = localStorage.getItem("gnss-map-id"),
      mode = localStorage.getItem("gnss-map-mode");
    if (id && [...$("saved-maps").options].some((o) => o.value === id)) {
      $("saved-maps").value = id;
      await loadSavedMap(id);
      if (mode === "blank") {
        $("map-mode").value = "blank";
        localStorage.setItem("gnss-map-mode", "blank");
        draw();
      }
    }
  })
  .catch(showError);
$("map-mode").onchange = () => {
  $("current-map-name").textContent =
    $("map-mode").value === "blank"
      ? "Blank canvas"
      : offlineMap?.source || "No offline map selected";
  localStorage.setItem("gnss-map-mode", $("map-mode").value);
  if ($("map-mode").value === "offline" && !offlineMap) {
    $("map-warning").textContent =
      "No offline map loaded. Blank canvas remains available.";
  }
  draw();
};
$("map-opacity").oninput = draw;
$("map-file").onchange = async () => {
  const file = $("map-file").files[0];
  if (!file) return;
  try {
    if (file.size > 20 * 1024 * 1024)
      throw Error("Map exceeds the 20 MB limit.");
    const text = await file.text();
    GeoMap.parse(text, file.name);
    const saved = await mapPost("maps", {
      source: file.name,
      data: JSON.parse(text),
    });
    await refreshMaps(saved.id);
    useMap(text, file.name, saved.id);
  } catch (e) {
    offlineMap = undefined;
    $("map-mode").value = "blank";
    localStorage.setItem("gnss-map-mode", "blank");
    $("map-metadata").textContent = "No valid offline map loaded.";
    $("map-warning").textContent =
      "Invalid map: " + e.message + " Blank canvas retained.";
    draw();
  }
};
$("fit").onclick = () => {
  locatedSOS = undefined;
  viewport = undefined;
  draw();
};
$("focus-party").onclick = () => {
  locatedSOS = undefined;
  const d = state?.devices[selectedParty];
  if (!d?.location) return;
  const [cx, cy] = GeoMap.project(
    d.location.fix.longitude,
    d.location.fix.latitude,
  );
  viewport = { cx, cy, scale: 2 };
  draw();
};
function zoom(factor) {
  if (!viewport) return;
  viewport.scale = Math.max(0.00001, Math.min(100, viewport.scale * factor));
  draw();
}
$("zoom-in").onclick = () => zoom(1.5);
$("zoom-out").onclick = () => zoom(1 / 1.5);
let drag;
$("tracks").onpointerdown = (e) => {
  if (e.target.closest?.('[role="button"]') || e.button !== 0 || !viewport)
    return;
  drag = { x: e.clientX, y: e.clientY, cx: viewport.cx, cy: viewport.cy };
  $("tracks").setPointerCapture(e.pointerId);
};
$("tracks").onpointermove = (e) => {
  if (!drag) {
    if (
      terrain &&
      $("map-mode").value === "offline" &&
      $("layer-elevation").checked &&
      viewport
    ) {
      const root = $("tracks"),
        rect = root.getBoundingClientRect(),
        px =
          viewport.cx +
          (e.clientX - rect.left - root.clientWidth / 2) / viewport.scale,
        py =
          viewport.cy -
          (e.clientY - rect.top - root.clientHeight / 2) / viewport.scale;
      const [lon, lat] = GeoMap.unproject(px, py);
      $("hover").textContent =
        `${lat.toFixed(6)}, ${lon.toFixed(6)}` + terrainReadout(lon, lat);
    }
    return;
  }
  viewport.cx = drag.cx - (e.clientX - drag.x) / viewport.scale;
  viewport.cy = drag.cy + (e.clientY - drag.y) / viewport.scale;
  draw();
};
$("tracks").onpointerup = $("tracks").onpointercancel = () =>
  (drag = undefined);
$("tracks").addEventListener(
  "wheel",
  (e) => {
    e.preventDefault();
    zoom(e.deltaY < 0 ? 1.2 : 1 / 1.2);
  },
  { passive: false },
);
$("tracks").onkeydown = (e) => {
  if (!viewport) return;
  const step = 80 / viewport.scale;
  switch (e.key) {
    case "ArrowLeft":
      viewport.cx -= step;
      break;
    case "ArrowRight":
      viewport.cx += step;
      break;
    case "ArrowUp":
      viewport.cy += step;
      break;
    case "ArrowDown":
      viewport.cy -= step;
      break;
    case "+":
    case "=":
      zoom(1.5);
      break;
    case "-":
      zoom(1 / 1.5);
      break;
    case "f":
    case "F":
      viewport = undefined;
      break;
    default:
      return;
  }
  e.preventDefault();
  draw();
};
function svg(tag, attrs) {
  const e = document.createElementNS(svgNS, tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  return e;
}
function terrainReadout(lon, lat) {
  const value = Terrain.elevation(terrain, lon, lat);
  return ` · Estimated ground elevation ${value === null ? "unavailable / out of coverage" : "~" + Math.round(value) + " m"} · ${terrain.vertical_datum} · ${terrain.arc_seconds} arc sec · not GNSS altitude`;
}
function draw() {
  if (!state) return;
  const root = $("tracks"),
    raw = $("all-observations").checked;
  const points = (
    raw
      ? (state.raw_points ?? [])
      : [...(state.points ?? []), ...(state.provisional_points ?? [])]
  ).filter((p) => !hidden.has(p.device_id));
  const live = Object.values(state.devices)
    .filter((d) => d.location && !hidden.has(d.device_id))
    .map((d) => ({
      device_id: d.device_id,
      fix: d.location.fix,
      live: d.current_position,
    }));
  const all = [...points, ...live];
  $("track-layer-label").textContent = raw
    ? "┄ Unfiltered / Diagnostic · distance remains qualified"
    : "— Qualified history";
  const focusedPoint = document.activeElement?.getAttribute?.("data-point-key");
  root.replaceChildren();
  $("empty").hidden = all.length > 0;
  const w = root.clientWidth,
    h = root.clientHeight;
  if (!w || !h) return;
  const geo = (p) => GeoMap.project(p.fix.longitude, p.fix.latitude);
  if (!viewport) {
    let coords = all.map(geo);
    if (!coords.length && offlineMap && $("map-mode").value === "offline")
      coords = [
        GeoMap.project(offlineMap.bounds[0], offlineMap.bounds[1]),
        GeoMap.project(offlineMap.bounds[2], offlineMap.bounds[3]),
      ];
    if (!coords.length) coords = [[0, 0]];
    let minX = Infinity,
      maxX = -Infinity,
      minY = Infinity,
      maxY = -Infinity;
    for (const p of coords) {
      minX = Math.min(minX, p[0]);
      maxX = Math.max(maxX, p[0]);
      minY = Math.min(minY, p[1]);
      maxY = Math.max(maxY, p[1]);
    }
    viewport = {
      cx: (minX + maxX) / 2,
      cy: (minY + maxY) / 2,
      scale: Math.max(
        0.00001,
        Math.min(
          Math.max(1, w - 100) / Math.max(50, maxX - minX),
          Math.max(1, h - 200) / Math.max(50, maxY - minY),
        ),
      ),
    };
    viewport.empty = !all.length && !offlineMap;
  } else if (viewport.empty && all.length) {
    viewport = undefined;
    draw();
    return;
  }
  const xyCoord = (p) => [
    (p[0] - viewport.cx) * viewport.scale + w / 2,
    h / 2 - (p[1] - viewport.cy) * viewport.scale,
  ];
  const xy = (p) => xyCoord(geo(p));
  root.setAttribute("viewBox", `0 0 ${w} ${h}`);
  const mapVisible = offlineMap && $("map-mode").value === "offline";
  const terrainVisible = mapVisible && terrain;
  const layerOn = (kind) => terrainVisible && $("layer-" + kind).checked;
  const night = document.documentElement.dataset.appearance === "night";
  const mapColors = night
    ? {
        water: "#234858",
        building: "#48515a",
        land: "#263d32",
        border: "#647d73",
        river: "#83b8cf",
        road: "#d1b88e",
        other: "#92ab9e",
      }
    : {
        water: "#c9e3ec",
        building: "#d5cec6",
        land: "#dce7d4",
        border: "#a6b4ad",
        river: "#83b8cf",
        road: "#a29076",
        other: "#81958c",
      };
  if (layerOn("hillshade") && terrain.shades) {
    if (!terrain.image) {
      const c = document.createElement("canvas");
      c.width = terrain.columns;
      c.height = terrain.rows;
      const context = c.getContext("2d"),
        image = context.createImageData(c.width, c.height);
      const [west, south, east, north] = terrain.bounds,
        pyNorth = GeoMap.project(west, north)[1],
        pySouth = GeoMap.project(west, south)[1];
      // Resample north-up rows to the existing Mercator projection, not a new CRS.
      for (let y = 0; y < c.height; y++) {
        const py = pyNorth - (y / (c.height - 1)) * (pyNorth - pySouth);
        const lat = GeoMap.unproject(0, py)[1];
        const row = Math.max(
          0,
          Math.min(
            c.height - 1,
            Math.round(((north - lat) / (north - south)) * (c.height - 1)),
          ),
        );
        for (let x = 0; x < c.width; x++) {
          const a = (y * c.width + x) * 4,
            b = (row * c.width + x) * 4;
          const shade = terrain.shades[b];
          image.data[a] =
            image.data[a + 1] =
            image.data[a + 2] =
              night ? Math.round(25 + shade * 0.32) : shade;
          image.data[a + 3] = terrain.shades[b + 3];
        }
      }
      context.putImageData(image, 0, 0);
      terrain.image = c.toDataURL("image/png");
    }
    const [west, south, east, north] = terrain.bounds,
      tl = xyCoord(GeoMap.project(west, north)),
      br = xyCoord(GeoMap.project(east, south)),
      cellWidth = (br[0] - tl[0]) / (terrain.columns - 1),
      cellHeight = (br[1] - tl[1]) / (terrain.rows - 1);
    root.append(
      svg("image", {
        "data-layer": "hillshade",
        href: terrain.image,
        // Raster pixels cover cells; their centres must coincide with DEM nodes.
        // Horn's unsupported outer border is transparent, not invented coverage.
        x: tl[0] - cellWidth / 2,
        y: tl[1] - cellHeight / 2,
        width: cellWidth * terrain.columns,
        height: cellHeight * terrain.rows,
        preserveAspectRatio: "none",
        opacity: night ? 0.8 : 0.5,
        "pointer-events": "none",
      }),
    );
  }
  $("map-mode-label").textContent = mapVisible
    ? "Offline geographic map"
    : "Blank canvas";
  if (!mapVisible)
    $("projection-status").textContent =
      state.projection_error ||
      (state.projection_pending ? "History reconstruction in progress…" : "");
  if (mapVisible) {
    const layer = svg("g", {
      "data-layer": "offline-map",
      opacity: $("map-opacity").value,
    });
    for (const shape of offlineMap.shapes) {
      if (shape.type === "polygon")
        layer.append(
          svg("path", {
            d: shape.rings
              .map(
                (r) => "M" + r.map((c) => xyCoord(c).join(",")).join("L") + "Z",
              )
              .join(" "),
            fill:
              shape.properties?.natural === "water" || shape.properties?.water
                ? mapColors.water
                : shape.properties?.building
                  ? mapColors.building
                  : mapColors.land,
            stroke: mapColors.border,
            "fill-rule": "evenodd",
            "stroke-width": 1,
          }),
        );
      else if (shape.type === "line")
        layer.append(
          svg("polyline", {
            points: shape.coordinates
              .map((c) => xyCoord(c).join(","))
              .join(" "),
            fill: "none",
            stroke: shape.properties?.waterway
              ? mapColors.river
              : shape.properties?.highway
                ? mapColors.road
                : mapColors.other,
            "stroke-width": shape.properties?.highway ? 3 : 2,
            "stroke-dasharray": ["path", "footway", "track"].includes(
              shape.properties?.highway,
            )
              ? "4 3"
              : "",
          }),
        );
      else {
        const [x, y] = xyCoord(shape.coordinates[0]);
        layer.append(svg("circle", { cx: x, cy: y, r: 3, fill: "#81958c" }));
      }
    }
    root.append(layer);
    const outside = all.some(
      (p) =>
        p.fix.longitude < offlineMap.bounds[0] ||
        p.fix.longitude > offlineMap.bounds[2] ||
        p.fix.latitude < offlineMap.bounds[1] ||
        p.fix.latitude > offlineMap.bounds[3],
    );
    $("projection-status").textContent = outside
      ? "Some GNSS observations are outside map coverage. Tracks remain available."
      : state.projection_error ||
        (state.projection_pending ? "History reconstruction in progress…" : "");
  }
  if (layerOn("contours") && terrain.contour) {
    const lines = svg("g", {
      "data-layer": "contours",
      "pointer-events": "none",
    });
    for (const [i, segment] of terrain.contour.segments.entries()) {
      const coords = segment.points.map((p) => xyCoord(GeoMap.project(...p)));
      lines.append(
        svg("polyline", {
          points: coords.map((p) => p.join(",")).join(" "),
          fill: "none",
          stroke: night ? "#c4ab82" : "#79603d",
          "stroke-width": 1,
          opacity: 0.75,
        }),
      );
      if (
        i % 100 === 0 &&
        Math.hypot(coords[1][0] - coords[0][0], coords[1][1] - coords[0][1]) >
          35
      ) {
        const label = svg("text", {
          x: coords[0][0],
          y: coords[0][1] - 3,
          fill: night ? "#ecd1a8" : "#614723",
          "font-size": 10,
        });
        label.textContent = segment.level + " m";
        lines.append(label);
      }
    }
    root.append(lines);
  }
  const overlay = svg("g", { "data-layer": "tracks" });
  root.append(overlay);
  const segments = new Map();
  for (const p of points) {
    const k = p.device_id + "/" + p.segment_id;
    if (!segments.has(k)) segments.set(k, []);
    segments.get(k).push(p);
  }
  function inspect(p) {
    inspectedPoint = p;
    $("hover").textContent =
      `${state.devices[p.device_id].snapshot.party.id} · ${p.fix.latitude.toFixed(6)}, ${p.fix.longitude.toFixed(6)} · ${time(p.fix.observed_at)} · accuracy ±${p.fix.horizontal_accuracy_m ?? "unknown"} m · ${raw ? "Unfiltered diagnostic; distance remains qualified" : p.segment_reason === "provisional" ? "Provisional movement" : "Qualified distance " + distance(p.cumulative_m ?? state.devices[p.device_id].total_m)}`;
    if (layerOn("elevation"))
      $("hover").textContent += terrainReadout(p.fix.longitude, p.fix.latitude);
  }
  for (const group of segments.values()) {
    const first = group[0],
      stroke = color(first.device_id),
      provisional = first.segment_reason === "provisional";
    overlay.append(
      svg("polyline", {
        points: group.map((p) => xy(p).join(",")).join(" "),
        fill: "none",
        stroke,
        "stroke-width": selectedParty === first.device_id ? 2.5 : 1.5,
        opacity: raw ? 0.45 : 0.6,
        "stroke-dasharray": raw
          ? "2 4"
          : provisional
            ? "6 5"
            : dash(first.device_id),
      }),
    );
    for (const p of group) {
      if (!p.dot && !raw) continue;
      const [x, y] = xy(p),
        circle = svg("circle", {
          cx: x,
          cy: y,
          r: 5,
          fill: "white",
          stroke,
          "stroke-width": 2,
          tabindex: 0,
          role: "button",
          "data-point-key": p.device_id + "/" + p.fix.observed_at,
          "aria-label": `${state.devices[p.device_id].snapshot.party.id} observation ${p.fix.latitude}, ${p.fix.longitude}`,
        });
      circle.onmouseenter = circle.onfocus = circle.onclick = () => inspect(p);
      circle.onkeydown = (e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          circle.onclick();
        }
      };
      overlay.append(circle);
    }
  }
  const liveLayer = svg("g", { "data-layer": "live" });
  root.append(liveLayer);
  for (const p of live) {
    const [x, y] = xy(p),
      d = state.devices[p.device_id];
    const bearing = GeoMap.liveHeading(p.fix, p.live);
    const marker = svg(bearing == null ? "circle" : "path", {
      cx: x,
      cy: y,
      r: p.live ? 8 : 6,
      ...(bearing == null
        ? {}
        : {
            d: "M0,-12 L8,9 L0,5 L-8,9 Z",
            transform: `translate(${x} ${y}) rotate(${bearing})`,
          }),
      fill: p.live ? color(p.device_id) : night ? "#1b2831" : "#fff",
      stroke: color(p.device_id),
      "stroke-width": 3,
      tabindex: 0,
      role: "button",
      "data-point-key": p.device_id + "/live",
      "data-live-device": p.live ? p.device_id : "",
      "aria-label": `${d.snapshot.party.id} ${gnssLabel(d)}${bearing == null ? " · direction unavailable or stationary" : ` · course ${bearing} degrees`}`,
    });
    marker.onclick = () => {
      selectParty(p.device_id);
      detailsOpen(true);
      inspect(p);
      render();
    };
    marker.onfocus = () => inspect(p);
    marker.onkeydown = (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        marker.onclick();
      }
    };
    liveLayer.append(marker);
    const label = svg("text", {
      x: x + (x > w / 2 ? -13 : 13),
      y: y < 24 ? y + 24 : y - 12,
      "text-anchor": x > w / 2 ? "end" : "start",
      fill: color(p.device_id),
      "font-size": 12,
    });
    label.textContent = d.snapshot.party.id + (p.live ? "" : " · last known");
    liveLayer.append(label);
  }
  $("scale-label").textContent = "WGS84 / Web Mercator · north up";
  if (locatedSOS?.fix) {
    const [x, y] = xy({ fix: locatedSOS.fix });
    const layer = svg("g", { "data-layer": "located-sos" });
    const marker = svg("path", {
      d: "M0,-9L9,0L0,9L-9,0Z",
      transform: `translate(${x} ${y})`,
      fill: night ? "#ff9ba5" : "#a51621",
      stroke: "white",
      "stroke-width": 2,
      tabindex: 0,
      role: "button",
      "aria-label": `SOS event observation for ${locatedSOS.party.id}, ${time(locatedSOS.triggered_at)}; not a current position`,
    });
    marker.onfocus = marker.onclick = () => {
      $("hover").textContent =
        `SOS event observation · ${locatedSOS.fix.latitude}, ${locatedSOS.fix.longitude} · ${time(locatedSOS.fix.observed_at)} · not a current position`;
    };
    const label = svg("text", {
      x: x + 13,
      // Separate coincident event/current labels without moving either position.
      y: y + 22,
      fill: night ? "#ff9ba5" : "#a51621",
      "font-size": 12,
    });
    label.textContent = `SOS event · ${locatedSOS.party.id}`;
    layer.append(marker, label);
    root.append(layer);
  }
  if (focusedPoint)
    Array.from(root.querySelectorAll?.("[data-point-key]") ?? [])
      .find((p) => p.getAttribute("data-point-key") === focusedPoint)
      ?.focus();
}
let polling = false;
async function poll() {
  if (polling) return;
  polling = true;
  try {
    const response = await fetch(
      "/local/state?dot_interval_s=" + encodeURIComponent($("dots").value),
    );
    const b = await response.json();
    if (!response.ok) throw Error(b.message || "Command unavailable");
    state = b;
    render();
    if ($("event-history").open) loadEvents().catch(showError);
    showError(null);
  } catch (e) {
    $("connection").textContent = "● Command Disconnected";
    $("connection").classList.add("unavailable");
    showError(e);
  } finally {
    polling = false;
  }
}
new ResizeObserver(draw).observe($("tracks"));
poll();
setInterval(poll, 2000);
