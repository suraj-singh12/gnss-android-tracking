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
let viewport, offlineMap, inspectedPoint, locatedSOS;
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
for (const action of ["start", "stop", "resume", "clear"])
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
      mode: $("recording-mode").value,
    }).catch(showError);
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
  $("connection").textContent = "Command Connected · local workspace";
  const recording = state.recording;
  $("recording").textContent = recording
    ? recording.active
      ? "Recording active · " + duration(recording.duration_s)
      : "Recording stopped · " + duration(recording.duration_s)
    : "No recording";
  $("recording-mode").disabled = !!recording;
  if (recording?.mode) $("recording-mode").value = recording.mode;
  $("start").disabled = !!recording;
  $("stop").disabled = !recording?.active;
  $("resume").disabled = !recording || recording.active;
  $("clear").disabled = !recording;
  const devices = Object.values(state.devices).sort((a, b) =>
    a.device_id.localeCompare(b.device_id),
  );
  $("count").textContent = devices.length;
  $("party-summary").textContent =
    `${devices.length} ${devices.length === 1 ? "party" : "parties"}`;
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
  for (const m of maps)
    $("saved-maps").add(
      new Option(`${m.source} · ${(m.bytes / 1024).toFixed(1)} KB`, m.id),
    );
  if (selected) $("saved-maps").value = selected;
}
function useMap(text, source, id) {
  const parsed = GeoMap.parse(text, source);
  offlineMap = parsed;
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
}
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
$("map-area").onchange = () => {
  if ($("map-area").value !== "custom")
    $("map-width").value = $("map-height").value = $("map-area").value;
  invalidatePreparation();
};
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
    };
    $("map-preparation-status").textContent =
      `${data.features.length} features · ${(new TextEncoder().encode(text).length / 1024).toFixed(1)} KB · roads, paths, buildings, water/land where available. Relations/tiles are not downloaded. Save for offline use.`;
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
  $("download-map").disabled = true;
  try {
    const saved = await mapPost("maps", preparedMap);
    await refreshMaps(saved.id);
    await loadSavedMap(saved.id);
    $("map-preparation-status").textContent =
      "Saved locally · available after restart without internet.";
  } catch (e) {
    $("map-preparation-status").textContent = e.message;
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
  if (!drag) return;
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
                ? "#c9e3ec"
                : shape.properties?.building
                  ? "#d5cec6"
                  : "#dce7d4",
            stroke: "#a6b4ad",
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
              ? "#83b8cf"
              : shape.properties?.highway
                ? "#a29076"
                : "#81958c",
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
      fill: p.live ? color(p.device_id) : "#fff",
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
      fill: "#a51621",
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
      y: y - 13,
      fill: "#a51621",
      "font-size": 12,
    });
    label.textContent = `SOS · ${locatedSOS.party.id}`;
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
    $("connection").textContent = "Offline · Command workspace unavailable";
    showError(e);
  } finally {
    polling = false;
  }
}
new ResizeObserver(draw).observe($("tracks"));
poll();
setInterval(poll, 2000);
