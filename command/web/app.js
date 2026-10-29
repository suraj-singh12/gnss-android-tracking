"use strict";
const $ = (id) => document.getElementById(id),
  hidden = new Set(),
  svgNS = "http://www.w3.org/2000/svg";
let state, policyRevision;
const mapAssetFetch = (url, options) => fetch(url, options);
let liveConnection = "Connecting to Command…";
const uiTimings = {};
function uiTiming(stage, started, failed = false) {
  const t = (uiTimings[stage] ??= { count: 0, errors: 0, recent_ms: [] });
  t.count++;
  t.errors += Number(failed);
  t.recent_ms.push(performance.now() - started);
  if (t.recent_ms.length > 128) t.recent_ms.shift();
}
let historyDrawKey, historyDrawMap, historyDrawTerrain;

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
  if (terrain) {
    terrain.image = undefined;
    for (const g of terrain.grids || []) g.image = undefined;
  }
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
  const pressed = new Date().toISOString();
  const boundary =
    path === "recording" &&
    (data.action === "start" || data.action === "resume")
      ? window.RecordingCapture?.boundary(pressed)
      : undefined;
  const response = await fetch("/local/" + path, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...window.RecordingCapture?.headers(),
    },
    body: JSON.stringify({
      ...data,
      ...(path === "recording" ? { operator_at: pressed, boundary } : {}),
    }),
  });
  const b = await response.json();
  if (!response.ok) throw Error(b.message || "Command operation failed");
  await poll();
}
function updateSessionChoice() {
  // From Beginning selects all retained sessions; no current session report needed.
  $("session-mode").disabled = false;
  $("session-mode-status").textContent =
    "Includes retained observations from all received tracking sessions, even if they arrive late.";
  $("confirm-recording").disabled = !!state?.recording;
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
$("all-observations").onchange = () => poll();
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
        $("sos-audio").textContent = "SOS alarm: blocked";
        $("sos-audio-status").textContent =
          "Audio suspended / blocked. Enable sound; visual SOS stays active.";
      }
    };
    if (test) await audio.resume();
    if (audio.state !== "running") throw Error("Audio blocked or suspended");
    const pending = state?.sos_alerts?.some((a) => !a.operator_acknowledged_at);
    alarm.update(pending, true);
    if (test && !pending) beep();
    $("sos-audio").textContent = "SOS alarm: ready";
    $("sos-audio-status").textContent =
      "Sound enabled · one shared beep every second until every SOS is acknowledged. Check speaker volume.";
  } catch (e) {
    $("sos-audio").textContent = (window.AudioContext || window.webkitAudioContext) ? "SOS alarm: blocked" : "SOS alarm: error";
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
  if (open && innerWidth <= 1100) {
    document.querySelector("main").classList.add("parties-closed");
    $("toggle-parties").setAttribute("aria-expanded", "false");
  }
  document.querySelector("main").classList.toggle("details-closed", !open);
  $("close-details").setAttribute("aria-expanded", String(open));
}
$("close-details").onclick = () => { detailsOpen(false); $("tracks").focus(); };
$("toggle-parties").onclick = $("collapse-parties").onclick = () => {
  const closed = document
    .querySelector("main")
    .classList.toggle("parties-closed");
  $("toggle-parties").setAttribute("aria-expanded", String(!closed));
  if (!closed && innerWidth <= 1100) detailsOpen(false);
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
    `SOS ${pending} unacknowledged emergencies`,
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
  const summary = `${pending} unacknowledged · ${alerts.length - pending} acknowledged.`;
  if ($("sos-summary").textContent !== summary)
    $("sos-summary").textContent = summary;
  // Reuse event-specific nodes so polling never removes keyboard focus or changes
  // the identity captured by a pending acknowledgement request.
  const root = $("sos-alerts");
  const focused = root.contains(document.activeElement)
    ? document.activeElement
    : null;
  let index = 0;
  for (const a of alerts) {
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
      locate.hidden = !a.fix;
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
      const more = el("details", undefined, "sos-more");
      more.append(el("summary", "Show more"), el("div", undefined, "sos-evidence"));
      card.append(details, button, locate, more);
      root.append(card);
    }
    card.classList.toggle("acknowledged", !!a.operator_acknowledged_at);
    const f = a.fix;
    const details = card.querySelector(".sos-details");
    if (card.dataset.snapshot !== JSON.stringify(a)) {
      card.dataset.snapshot = JSON.stringify(a);
      details.replaceChildren(
        el("h2", `${a.party.id} · ${a.party.name}`),
        el("strong", a.operator_acknowledged_at ? "Acknowledged" : "Unacknowledged — action required"),
        el("p", `Activated ${time(a.triggered_at)}`),
        el("p", f ? `Event location: ${f.latitude}, ${f.longitude} · accuracy ${f.horizontal_accuracy_m == null ? "unknown" : "±" + f.horizontal_accuracy_m + " m"}` : "Location unavailable at activation"),
        el("p", `${a.location_freshness.replaceAll("_", " ")} · event observation, not current position`),
      );
      const evidence = card.querySelector(".sos-evidence");
      const group = (title, rows) => {
        const section = el("section"); section.append(el("h3", title));
        const list = el("dl");
        for (const [name, value] of rows) list.append(el("dt", name), el("dd", value));
        section.append(list); return section;
      };
      evidence.replaceChildren(
        group("Event timeline", [["Activation", time(a.triggered_at)], ["Capture", time(a.captured_at)], ["Command receipt", time(a.received_at)], ["Operator acknowledgement", a.operator_acknowledged_at ? time(a.operator_acknowledged_at) : "Pending"]]),
        group("GNSS evidence", [["Original observation", f ? time(f.observed_at) : "Unavailable"], ["GNSS state", a.gnss_status.replaceAll("_", " ")], ["Freshness", a.location_freshness.replaceAll("_", " ")], ["Fix age at capture", f ? (f.fix_age_ms / 1000).toFixed(1) + " s" : "Unavailable"], ["Accuracy", f?.horizontal_accuracy_m == null ? "Unknown" : "±" + f.horizontal_accuracy_m + " m"]]),
        group("Technical identification", [["Device ID", a.device_id], ["Event ID", a.event_id]]),
      );
    }
    card.querySelector("button").disabled = !!a.operator_acknowledged_at;
    card.querySelector("button").hidden = !!a.operator_acknowledged_at;
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
          card.dataset.event === a.device_id + "/" + a.event_id,
      )
    )
      card.remove();
  }
  if (focused && document.activeElement !== focused) focused.focus();
  alarm.update(pending > 0, audio?.state === "running");
}
function render() {
  const started = performance.now();
  try {
    renderDashboard();
  } finally {
    uiTiming("render", started);
    globalThis.RecordingCapture?.changed();
  }
}
function renderDashboard() {
  renderSOS();
  if (window.HistoricalPlayback?.active) return;
  if ($("event-history").open) renderEvents();
  $("projection-status").textContent = state.projection_error
    ? "Projection failed: " + state.projection_error
    : state.projection_pending
      ? "Projection catching up — displayed history is not fully synchronized"
      : "";
  updateConnectionLabel();
  $("connection").classList.toggle(
    "unavailable",
    liveConnection.includes("unavailable"),
  );
  if (state.history_sampled)
    $("projection-status").textContent +=
      " · Map preview only (bounded points); use Inspect selected history for every full-resolution point and original observation. Distance remains full resolution.";
  const recording = state.recording;
  $("recording").textContent = recording
    ? `${recording.status === "stopped" ? "STOPPED" : recording.active ? "● REC" : "PAUSED"} · ${duration(recording.duration_s)}`
    : "Ready to record";
  if ($("recording-start").open) updateSessionChoice();
  $("start").hidden = !!recording;
  $("pause").hidden = !recording?.active;
  $("resume").hidden =
    !recording || recording.active || recording.status === "stopped";
  $("stop").disabled = !recording || recording.status === "stopped";
  $("save-recording").disabled = !recording;
  $("stopped-choice").hidden = recording?.status !== "stopped";
  const devices = Object.values(state.devices).sort((a, b) =>
    a.device_id.localeCompare(b.device_id),
  );
  $("count").textContent = devices.length;
  $("party-summary").textContent =
    `${devices.filter((d) => d.contact_condition === "healthy").length}/${devices.length} Parties`;
  $("no-parties").hidden = devices.length > 0;
  if (!state.devices[selectedParty]) selectedParty = devices[0]?.device_id;
  // Preserve focused edits while polling; all content is created as text nodes.
  // Editing one panel must not freeze the other panel's device inventory.
  const preserveDevices = $("devices").contains(document.activeElement);
  const preserveDetail = $("selected-detail").contains(document.activeElement);
  if (!preserveDevices || !preserveDetail) {
    if (!preserveDevices) $("devices").replaceChildren();
    if (!preserveDetail) $("selected-detail").replaceChildren();
    if (!devices.length && !preserveDetail)
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
            (d.config_converged
              ? "reported settings match"
              : "reported settings differ") +
            " · execution evidence in Phone controls",
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
      if (d.reconstruction) {
        const r = d.reconstruction;
        status.append(
          el("div", reconstructionLabel(d)),
          el(
            "div",
            `GNSS durably received: ${r.durably_received} · eligible: ${r.eligible} · outside boundaries: ${r.excluded_by_boundaries}`,
          ),
          el(
            "div",
            `Projection: ${r.projection} · processed: ${r.projected} · accepted: ${r.accepted} · rejected: ${r.rejected}`,
          ),
          el(
            "div",
            `Earliest received measurement: ${r.earliest_received_observed_at ? time(r.earliest_received_observed_at) : "unknown until history arrives"}`,
          ),
          el(
            "div",
            `Known missing sequences: ${r.known_missing_sequences} · reported unresolved: ${r.reported_unresolved} · reported collection loss: ${r.reported_collection_loss}`,
          ),
          el("div", r.inventory_scope),
        );
        const drawing = el(
          "div",
          "Qualified geometry available; drawing status updating…",
        );
        drawing.setAttribute("data-drawing-device", d.device_id);
        status.append(drawing);
        if (r.projection_error) status.append(el("div", r.projection_error));
        if (r.clock_uncertain)
          status.append(
            el(
              "div",
              `${r.clock_uncertain} observations have clock uncertainty; original times are unchanged.`,
            ),
          );
        for (const [reason, count] of Object.entries(r.rejection_reasons ?? {}))
          status.append(el("div", `${reason.replaceAll("_", " ")}: ${count}`));
      }
      if (d.history)
        status.append(
          el(
            "div",
            `History: ${d.history.condition} · received through ${d.history.received_through} · known delivery outcomes through ${d.history.processed_through}`,
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
      const remote = el("button", "Phone controls");
      remote.type = "button";
      remote.onclick = () => {
        selectParty(d.device_id);
        $("open-control").click();
        $("control-one").click();
      };
      card.append(remote);
      card.append(
        el("small", "Reporting interval override · seconds"),
        form,
        el(
          "small",
          "Remote override " +
            (d.desired_config.reporting_interval_override_s ?? "cleared") +
            " · legacy v1 setting; authenticated controls are in Phone controls",
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
      if (!preserveDevices) $("devices").append(overview);
      if (selectedParty === d.device_id && !preserveDetail) {
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
function reconstructionLabel(d) {
  const r = d.reconstruction;
  if (!r) return "Route status unavailable";
  const condition =
    {
      no_recording: "No recording selected",
      no_observations_received: "No GNSS observations received",
      excluded_by_recording_boundaries: "History outside recording boundaries",
      quality_filtered: "All eligible history rejected by quality rules",
      projection_pending: "Route reconstruction pending",
      projection_failed: "Route reconstruction failed — retrying",
      projection_inconsistent:
        "Route projection inconsistent — export diagnostics",
      qualified_geometry: `${r.qualified_points} qualified points / ${r.qualified_segments} sections`,
    }[r.route_condition] || "Route status unknown";
  return condition;
}
function historyLabel(d) {
  const h = d.history,
    q = h?.last_reported_phone_queue;
  if (!h) return "History · unavailable";
  const condition =
    {
      catching_up: "Receiving history",
      unresolved: "History Incomplete",
      incomplete: "History Incomplete",
      synchronized: "Reported session received",
      unknown: "Unknown",
    }[h.condition] || "Unknown";
  return `${reconstructionLabel(d)}${d.reconstruction?.known_missing_sequences ? " · " + d.reconstruction.known_missing_sequences + " missing" : ""} · Phone: ${condition}${q?.unresolved_sequences?.length ? " · " + q.unresolved_sequences.length + " unresolved" : ""}${q ? " · " + q.pending_observations + " pending" : ""}${q && !h.phone_state_recent ? " (last reported; current count unknown)" : ""}`;
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
let mapImportPending = false,
  transferApprovalPending = false;
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
$("choose-saved-map").onclick = (event) => {
  event.preventDefault();
  $("maps-library").open = true;
  $("map-preparation").open = false;
  $("map-import").open = false;
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
async function mapPost(path, data, signal) {
  const response = await fetch("/local/" + path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
    signal,
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
    const option = new Option(
      `${m.source} · ${(m.bytes / 1024).toFixed(1)} KB`,
      m.id,
    );
    option.dataset.source = m.source;
    $("saved-maps").add(option);
    const row = el("div", undefined, "saved-map-row");
    const select = el("strong", m.source);
    row.dataset.mapId = m.id;
    row.classList.toggle("selected", m.id === (selected || activeMapID));
    row.append(
      select,
      el(
        "small",
        `${m.bounds ? m.bounds.map((v) => v.toFixed(4)).join(", ") : "Imported geographic coverage"} · ${
          m.chunks
            ? `Vector ${m.chunks.vector}/${m.chunks.requested} · DEM ${m.chunks.terrain}/${m.chunks.requested} chunks; render availability in Layers`
            : Object.entries(m.components || {})
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
function useMap(text, source, id, recordingAsset = "") {
  const job = JSON.parse(text).plan;
  const previousWrap = GeoMap.setWrap(job?.request.lon || 0);
  let parsed;
  try {
    parsed = GeoMap.parse(text, source);
    parsed._recordingAsset = recordingAsset;
  } catch (e) {
    GeoMap.setWrap(previousWrap);
    throw e;
  }
  radiusManifest = undefined;
  radiusViewKey = undefined;
  radiusViewVersion++;
  globalThis.radiusGrids = undefined;
  $("resume-map").hidden = true;
  $("export-manifest").hidden = true;
  $("map-coverage-status").textContent = "";
  $("map-detail-status").textContent = "";
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
  $("export-map").href = "/local/maps/" + encodeURIComponent(id) + "/export";
  $("export-map").setAttribute("download", "offline-map.gmap.zip");
  localStorage.setItem("gnss-map-id", id);
  viewport = undefined;
  draw();
  localStorage.setItem("gnss-map-mode", "offline");
}
async function loadSavedMap(id) {
  const response = await mapAssetFetch("/local/maps/" + encodeURIComponent(id));
  if (!response.ok)
    throw Error("Saved map unavailable; blank canvas remains available");
  const source =
    [...$("saved-maps").options].find((option) => option.value === id)?.dataset
      .source || "Saved offline map";
  useMap(
    await response.text(),
    source,
    id,
    response.headers.get("X-Presentation-Asset") || "",
  );
  if (offlineMap?.plan) {
    await loadRadiusManifest(id);
    return;
  }
  await loadTerrain(id);
  await readDownloadReport(id);
}
const terrainLayers = ["hillshade", "contours", "elevation"];
function updateLayers() {
  for (const kind of terrainLayers) {
    const control = $("layer-" + kind),
      available =
        !!terrain?.components[kind] &&
        !(kind === "contours" && terrain.contour_error) &&
        !(kind === "hillshade" && terrain.hillshade_error);
    control.disabled = !available;
    control.checked =
      available &&
      localStorage.getItem(`gnss-layer-${activeMapID}-${kind}`) === "true";
    $("availability-" + kind).textContent = available
      ? control.checked
        ? "Downloaded · visible"
        : "Downloaded · hidden"
      : kind === "hillshade" && terrain?.hillshade_error
        ? terrain.hillshade_error
        : kind === "contours" && terrain?.contour_error
          ? terrain.contour_error
          : "Not downloaded / prepared";
    if (offlineMap?.plan)
      $("availability-" + kind).textContent = available
        ? `Available in visible DEM chunks · ${control.checked ? "visible" : "hidden"}; inspect coverage gaps`
        : "Unavailable in current view; inspect saved source / render status";
  }
  $("current-map-layers").textContent =
    offlineMap?.plan && radiusManifest
      ? radiusSummary(radiusManifest)
      : terrain
        ? "Available: " +
          terrainLayers.filter((k) => terrain.components[k]).join(", ") +
          " · visibility via Layers"
        : offlineMap
          ? "Vectors only · no terrain downloaded"
          : "No offline map selected.";
  for (const id of ["delete-map", "prepare-terrain", "download-terrain"])
    $(id).disabled = !activeMapID || !!downloadAbort;
  if (offlineMap?.plan) {
    $("prepare-terrain").disabled = true;
    $("prepare-terrain").title =
      "Local single-grid import is for legacy maps; use Resume for radius maps";
  } else $("prepare-terrain").removeAttribute("title");
  $("export-map").hidden = !activeMapID;
  $("export-map").setAttribute("aria-disabled", String(!activeMapID));
  $("terrain-metadata").textContent = terrain
    ? `${terrain.source} · ${terrain.arc_seconds} arc sec (~${Math.round((terrain.arc_seconds / 3600) * 111320)} m north–south) · ${terrain.vertical_datum} · interpolated, not survey-grade${terrain.contour ? " · contours " + terrain.contour.interval + " m" : ""}${terrain.provenance ? " · " + terrain.provenance.attribution : ""}`
    : "No terrain for this map. Download / retry terrain or import a DEM in Map & layers.";
  $("export-terrain").hidden = !terrain || !!offlineMap?.plan;
  if (terrain)
    $("export-terrain").href =
      `/local/maps/${encodeURIComponent(activeMapID)}/terrain-file`;
}
async function loadTerrain(id) {
  const r = await mapAssetFetch(
    `/local/maps/${encodeURIComponent(id)}/terrain`,
  );
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
    terrain = {
      ...Terrain.prepare(grid),
      _recordingAsset: r.headers.get("X-Presentation-Asset") || "",
    };
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
  if (offlineMap?.plan) {
    try {
      await mapPost(`maps/${encodeURIComponent(activeMapID)}/products`, {
        components: Object.fromEntries(
          terrainLayers.map((k) => [k, $("prepare-" + k).checked]),
        ),
      });
      await resumeRadius();
    } catch (e) {
      $("terrain-status").textContent = e.message;
    }
    return;
  }
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
    radiusManifest = undefined;
    radiusViewKey = undefined;
    radiusViewVersion++;
    clearTimeout(radiusViewTimer);
    globalThis.radiusGrids = [];
    for (const id of [
      "resume-map",
      "save-use-map",
      "approve-transfer",
      "export-manifest",
      "download-progress",
    ])
      $(id).hidden = true;
    $("current-map-name").textContent = "Blank canvas · no saved map selected";
    $("map-detail-status").textContent = "";
    $("map-coverage-status").textContent = "";
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
function invalidatePreparation(resetConsent = true) {
  if (resetConsent !== false) $("confirm-map-cost").checked = false;
  preparationRevision++;
  preparedMap = undefined;
  $("map-preview").replaceChildren();
  $("map-plan-details").textContent = "";
  $("map-preparation-status").textContent =
    resetConsent === false
      ? "Checking requested area…"
      : "Area changed · Preview & Download checks the new selection before acquisition.";
  $("download-map").disabled = false;
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
  if ($("map-shape").value === "circle") {
    await previewRadius();
    return;
  }
  invalidatePreparation(false);
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
  if (preparedMap.plan) {
    await startRadiusDownload();
    return;
  }
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
    await loadSavedMap(saved.id);
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
  if (pickingMapCenter && viewport) {
    pickRadiusCenter(e);
    return;
  }
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
  if (window.HistoricalPlayback?.active) { window.HistoricalPlayback.repaint(); return; }
  if (!state) return;
  const started = performance.now();
  try {
    drawMap();
    window.recordingRenderedModel = structuredClone(state);
  } finally {
    uiTiming("draw", started);
    globalThis.RecordingCapture?.changed();
  }
}
function drawMap() {
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
  globalThis.legacyTerrainForDraw = terrain;
  if (offlineMap?.plan) scheduleRadiusView(w, h);
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
  // Retain immutable history/map nodes across LIVE and unchanged history polls.
  // Explicit pan/zoom, selection, style, layer and geometry changes invalidate it.
  const key = JSON.stringify([
    points,
    raw,
    selectedParty,
    viewport,
    w,
    h,
    night,
    $("map-mode").value,
    $("map-opacity").value,
    ["hillshade", "contours", "elevation"].map((k) => $("layer-" + k).checked),
    Object.values(state.devices).map((d) => [
      d.device_id,
      d.snapshot.party.id,
      d.track_color,
      d.track_dash,
    ]),
  ]);
  const reuseHistory =
    key === historyDrawKey &&
    offlineMap === historyDrawMap &&
    terrain === historyDrawTerrain;
  if (reuseHistory) {
    root
      .querySelectorAll('[data-layer="live"], [data-layer="located-sos"]')
      .forEach((n) => n.remove());
  } else root.replaceChildren();
  function inspect(p) {
    inspectedPoint = p;
    $("hover").textContent =
      `${state.devices[p.device_id].snapshot.party.id} · ${p.fix.latitude.toFixed(6)}, ${p.fix.longitude.toFixed(6)} · ${time(p.fix.observed_at)} · accuracy ±${p.fix.horizontal_accuracy_m ?? "unknown"} m · ${raw ? "Unfiltered diagnostic; distance remains qualified" : p.segment_reason === "provisional" ? "Provisional movement" : "Qualified distance " + distance(p.cumulative_m ?? state.devices[p.device_id].total_m)}`;
    if (layerOn("elevation"))
      $("hover").textContent += terrainReadout(p.fix.longitude, p.fix.latitude);
  }
  if (!reuseHistory) {
    for (const terrain of (
      globalThis.radiusGrids || [globalThis.legacyTerrainForDraw]
    ).filter(Boolean)) {
      if (layerOn("hillshade") && terrain.shades) appendPinnedHillshade(root, terrain, xyCoord, night);
    }
    $("map-mode-label").textContent = mapVisible
      ? "Offline geographic map"
      : "Blank canvas";
    const outsideView = points.some((p) => {
      const [x, y] = xy(p);
      return x < 0 || x > w || y < 0 || y > h;
    });
    const projectionStatus = state.projection_error
      ? "Projection failed: " + state.projection_error
      : state.projection_pending
        ? "History reconstruction in progress…"
        : outsideView
          ? "Historical geometry extends beyond this view — use Fit parties."
          : "";
    $("projection-status").textContent = projectionStatus;
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
                  (r) =>
                    "M" + r.map((c) => xyCoord(c).join(",")).join("L") + "Z",
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
        : projectionStatus;
    }
    for (const terrain of (
      globalThis.radiusGrids || [globalThis.legacyTerrainForDraw]
    ).filter(Boolean)) {
      if (layerOn("contours") && terrain.contour) appendPinnedContours(root, terrain, xyCoord, night);
    }
    if (mapVisible && offlineMap.plan) drawRadiusCoverage(root, xyCoord);
    const overlay = svg("g", { "data-layer": "tracks" });
    root.append(overlay);
    const segments = new Map();
    for (const p of points) {
      const k = p.device_id + "/" + p.segment_id;
      if (!segments.has(k)) segments.set(k, []);
      segments.get(k).push(p);
    }
    for (const group of segments.values()) {
      const first = group[0],
        stroke = color(first.device_id),
        provisional = first.segment_reason === "provisional";
      overlay.append(
        svg("polyline", {
          "data-device": first.device_id,
          "data-segment": first.segment_id,
          "data-qualified-points": raw ? "" : group.length,
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
        circle.onmouseenter =
          circle.onfocus =
          circle.onclick =
            () => inspect(p);
        circle.onkeydown = (e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            circle.onclick();
          }
        };
        overlay.append(circle);
      }
    }
    historyDrawKey = key;
    historyDrawMap = offlineMap;
    historyDrawTerrain = terrain;
  }
  for (const label of document.querySelectorAll("[data-drawing-device]")) {
    const device = label.getAttribute("data-drawing-device");
    if (hidden.has(device))
      label.textContent =
        "Drawing hidden by operator · qualified geometry retained";
    else if (raw)
      label.textContent =
        "Unfiltered diagnostic drawing selected · qualified geometry retained";
    else {
      const sections = [
        ...root.querySelectorAll("polyline[data-qualified-points]"),
      ].filter((e) => e.getAttribute("data-device") === device);
      const count = sections.reduce(
        (n, e) => n + Number(e.getAttribute("data-qualified-points")),
        0,
      );
      label.textContent =
        `Qualified drawing: ${count} points / ${sections.length} sections` +
        (points.some(
          (p) =>
            p.device_id === device &&
            (() => {
              const [x, y] = xy(p);
              return x < 0 || x > w || y < 0 || y > h;
            })(),
        )
          ? " · outside this view; use Fit parties"
          : "");
    }
  }
  const liveLayer = svg("g", { "data-layer": "live" });
  root.append(liveLayer);
  // Place high-priority labels first; markers and accessible identities are never
  // removed. A stable device-id tie break keeps redraws deterministic.
  const pendingSOS = new Set((state.sos_alerts || []).filter(a => !a.operator_acknowledged_at).map(a => a.device_id));
  const ranked = [...live].sort((a, b) => {
    const rank = p => (p.device_id === selectedParty ? 2 : 0) + (pendingSOS.has(p.device_id) ? 4 : 0);
    return rank(b) - rank(a) || a.device_id.localeCompare(b.device_id);
  });
  const labelBoxes = [];
  let hiddenLabels = 0;
  for (const p of ranked) {
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
      "data-device": p.device_id,
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
    marker.onfocus = marker.onmouseenter = () => inspect(p);
    marker.onkeydown = (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        marker.onclick();
      }
    };
    liveLayer.append(marker);
    // Labels remain close to their own markers, so they cannot be mistaken
    // for a route or another party. If no candidate fits, the marker retains
    // its keyboard/hover identity and the density notice explains the omission.
    if (x < 0 || x > w || y < 0 || y > h) continue;
    const label = svg("text", {
      fill: color(p.device_id), "font-size": 13,
      "data-device": p.device_id, "data-party-label": p.device_id,
    });
    label.textContent = d.snapshot.party.id + (p.live ? "" : " · last known");
    liveLayer.append(label);
    const width = label.getComputedTextLength(), height = 16;
    const intersects = (a, b) => a.x < b.x + b.width + 3 && a.x + a.width + 3 > b.x && a.y < b.y + b.height + 2 && a.y + a.height + 2 > b.y;
    let placed;
    for (const dy of [-16, 18, -32, 34, -48, 50, -64, 66]) {
      for (const dx of [13, -13 - width]) {
        const box = { x: x + dx, y: y + dy - 12, width, height };
        if (box.x < 2 || box.x + width > w - 2 || box.y < 2 || box.y + height > h - 2 || labelBoxes.some(other => intersects(box, other))) continue;
        label.setAttribute("x", box.x);
        label.setAttribute("y", box.y + 12);
        labelBoxes.push(box);
        placed = true;
        break;
      }
      if (placed) break;
    }
    if (!placed) { label.remove(); hiddenLabels++; }

  }
  $("label-density").hidden = !hiddenLabels;
  $("label-density").textContent = hiddenLabels ? `${hiddenLabels} crowded party label${hiddenLabels === 1 ? "" : "s"} hidden · select a marker or zoom in` : "";
  $("scale-label").textContent = "WGS84 / Web Mercator · north up";
  if (locatedSOS?.fix) {
    const [x, y] = xy({ fix: locatedSOS.fix });
    const layer = svg("g", {
      "data-layer": "located-sos",
      "data-device": locatedSOS.device_id,
    });
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
let inspectionCursor = "";
let inspectionDevice;
async function inspectHistory(cursor = "") {
  const device = selectedParty;
  if (!device) return;
  $("inspection-status").textContent = "Loading a bounded page…";
  try {
    const layer = $("inspection-layer").value;
    const response = await fetch(
      `/local/history?device_id=${encodeURIComponent(device)}&layer=${layer}&cursor=${encodeURIComponent(cursor)}`,
      { signal: AbortSignal.timeout(4000) },
    );
    const page = await response.json();
    if (!response.ok) throw Error(page.message || "History unavailable");
    inspectionCursor = page.next_cursor || "";
    $("inspection-next").disabled = !inspectionCursor;
    $("inspection-data").textContent = JSON.stringify(page, null, 2);
    inspectionDevice = device;
    $("inspection-status").textContent =
      layer === "raw"
        ? "Original capture/envelope and Command receipt times; transport sequence order. No distance derived here."
        : "Full-resolution qualified points and decisions; cumulative distances remain authoritative. Generation changes require First page.";
  } catch (e) {
    $("inspection-status").textContent = e.message;
  }
}
$("inspect-history").onclick = () => {
  $("history-inspection").showModal();
  inspectHistory();
};
$("inspection-restart").onclick = () => inspectHistory();
$("inspection-next").onclick = () => inspectHistory(inspectionCursor);
$("inspection-layer").onchange = () => inspectHistory();
let livePolling = false;
let latestLive;
let latestLiveStarted = -Infinity;
async function pollLive() {
  if (livePolling) return;
  livePolling = true;
  const started = performance.now();
  let failed = false;
  try {
    const response = await fetch("/local/live", {
      signal: AbortSignal.timeout(4000),
    });
    if (!response.ok) throw Error("LIVE/SOS status unavailable");
    const b = await response.json();
    liveConnection = "● Command Connected";
    if (state && b.receipt_version < state.receipt_version) {
      updateConnectionLabel();
      $("connection").classList.remove("unavailable");
      return;
    }
    latestLive = b;
    latestLiveStarted = started;
    const changed =
      state &&
      (state.policy.revision !== b.policy.revision ||
        state.projection_version !== b.projection_version ||
        state.recording?.recording_id !== b.recording?.recording_id ||
        state.recording?.active !== b.recording?.active);
    state = {
      ...state,
      ...b,
      points: changed ? [] : (state?.points ?? []),
      raw_points: changed ? [] : (state?.raw_points ?? []),
      history_sampled: state?.history_sampled,
    };
    renderSOS(); // emergency notification precedes map work
    render();
  } catch (e) {
    failed = true;
    liveConnection = "● Command LIVE/SOS unavailable";
    updateConnectionLabel();
    $("connection").classList.add("unavailable");
    if (!state) showError(e);
  } finally {
    livePolling = false;
    uiTiming("live_fetch", started, failed);
  }
}
async function poll() {
  if (polling) return;
  polling = true;
  const started = performance.now();
  let failed = false;
  try {
    const response = await fetch(
      "/local/state?dot_interval_s=" +
        encodeURIComponent($("dots").value) +
        ($("all-observations").checked ? "&include_raw=true" : ""),
      { signal: AbortSignal.timeout(4000) },
    );
    const b = await response.json();
    if (!response.ok) throw Error(b.message || "Command unavailable");
    if (b.history_sampled && $("all-observations").checked) {
      const device = selectedParty || Object.keys(b.devices)[0];
      if (device) {
        const raw = await fetch(
          `/local/history?device_id=${encodeURIComponent(device)}&layer=raw`,
          { signal: AbortSignal.timeout(4000) },
        );
        if (!raw.ok) throw Error("Raw history page unavailable");
        b.raw_points = (await raw.json()).points;
      }
    }
    state = b;
    if (
      latestLive &&
      (latestLive.receipt_version > b.receipt_version ||
        (latestLive.receipt_version === b.receipt_version &&
          latestLiveStarted >= started))
    ) {
      const compatible =
        b.policy.revision === latestLive.policy.revision &&
        b.projection_version === latestLive.projection_version &&
        b.recording?.recording_id === latestLive.recording?.recording_id &&
        b.recording?.active === latestLive.recording?.active;
      state = {
        ...b,
        ...latestLive,
        points: compatible ? b.points : [],
        raw_points: compatible ? b.raw_points : [],
        provisional_points: compatible ? b.provisional_points : [],
        history_sampled: b.history_sampled,
      };
      if (compatible && latestLive.receipt_version === b.receipt_version) {
        // A history reader may finish later while holding an older read snapshot.
        // Its projection can advance without replacing fresher LIVE/contact fields.
        state.devices = Object.fromEntries(
          Object.entries(latestLive.devices).map(([id, live]) => {
            const history = b.devices[id];
            return [
              id,
              history
                ? {
                    ...live,
                    total_m: history.total_m,
                    reconstruction: history.reconstruction,
                    history: history.history,
                    history_sessions: history.history_sessions,
                  }
                : live,
            ];
          }),
        );
        state.projection_pending = b.projection_pending;
        state.projection_error = b.projection_error;
      }
    }
    render();
    if ($("event-history").open) loadEvents().catch(showError);
    showError(null);
  } catch (e) {
    failed = true;
    showError(new Error("History refresh unavailable: " + e.message));
  } finally {
    polling = false;
    uiTiming("history_fetch", started, failed);
  }
}
new ResizeObserver(draw).observe($("tracks"));
poll();
pollLive();
setInterval(pollLive, 1000);
setInterval(poll, 2000);

$("export-runtime").onclick = async () => {
  try {
    const response = await fetch("/local/runtime-diagnostics", {
      signal: AbortSignal.timeout(4000),
    });
    if (!response.ok) throw Error("Runtime diagnostics unavailable");
    const report = {
      command: await response.json(),
      browser: {
        timings: uiTimings,
        live_connection: liveConnection,
        map_status: $("map-detail-status").textContent,
        map_warning: $("map-warning").textContent,
        history_pending: state?.projection_pending,
        displayed_points: state?.points?.length ?? 0,
      },
    };
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(report, null, 2)], { type: "application/json" }),
    );
    const a = document.createElement("a");
    a.href = url;
    a.download = "gnss-runtime-diagnostics.json";
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) {
    showError(e);
  }
};

// Radius acquisitions remain in the existing library and geographic renderer.
let radiusManifest,
  radiusViewKey,
  radiusViewTimer,
  radiusViewVersion = 0,
  radiusFetchAbort;
let pickingMapCenter = false;
$("map-shape").onchange = () => {
  $("radius-fields").hidden = $("map-shape").value !== "circle";
  const circle = $("map-shape").value === "circle";
  $("legacy-area").hidden = circle;
  const presets = document.querySelector(".map-radius-presets");
  if (presets) {
    presets.hidden = !circle;
    $("map-radius-caption").hidden = !circle;
    $("radius-fields").hidden =
      !circle ||
      presets.querySelector('[aria-pressed="true"]')?.textContent !== "Custom";
  }
  invalidatePreparation();
};
for (const id of ["map-radius", "radius-unit"])
  $(id).oninput = invalidatePreparation;
$("pick-map-center").onclick = () => {
  pickingMapCenter = true;
  $("map-settings").close();
  $("hover").textContent =
    "Click the geographic map to choose the requested centre.";
};
function pickRadiusCenter(e) {
  const root = $("tracks"),
    rect = root.getBoundingClientRect();
  const [lon, lat] = GeoMap.unproject(
    viewport.cx +
      (e.clientX - rect.left - root.clientWidth / 2) / viewport.scale,
    viewport.cy -
      (e.clientY - rect.top - root.clientHeight / 2) / viewport.scale,
  );
  $("map-lat").value = lat;
  $("map-lon").value = ((lon + 540) % 360) - 180;
  pickingMapCenter = false;
  invalidatePreparation();
  $("open-map").click();
}
function radiusSummary(manifest) {
  const n = manifest.chunks.length,
    count = (k) =>
      manifest.chunks.filter((c) => c[k].status === "complete").length,
    requested = Object.values(manifest.components || {}).some(Boolean),
    voids = manifest.chunks.some((c) => c.terrain.void_dem_cells > 0),
    partial =
      count("vector") < n || (requested && (count("terrain") < n || voids));
  return `${partial ? "Partial map · known geographic/layer gaps" : "Verified selected source coverage"} · vectors ${count("vector")}/${n} · DEM ${count("terrain")}/${n}${requested ? (voids ? " (void gaps)" : "") : " (not requested)"}. Product visibility and processing failures in Layers.`;
}

function radiusDescription(manifest) {
  const chunks = manifest.chunks || [],
    complete = (k) => chunks.filter((c) => c[k].status === "complete").length;
  return (
    (manifest.plan.full_extent
      ? "Imported declared full source extent.\n"
      : `Requested centre ${manifest.plan.request.lat}, ${manifest.plan.request.lon}; radius ${manifest.plan.request.radius_m} m.\n`) +
    `Actual cumulative received bytes: ${manifest.transfer?.received_bytes || 0}; reserved ${manifest.transfer?.reserved_bytes || 0}; allowance ${manifest.transfer?.approved_bytes || 5000000} bytes; additional approval requires cumulative ${manifest.transfer?.approval_required_bytes || 0} bytes.\nCurrent/last source: ${manifest.transfer?.current_source || "local verified data / not contacted"}.\n` +
    `Vector acquired ${complete("vector")}/${chunks.length} chunk envelopes (empty features are valid). DEM grids saved ${complete("terrain")}/${chunks.length}.\n` +
    `DEM cells with four valid corners: ${chunks.reduce((n, c) => n + (c.terrain.valid_dem_cells || 0), 0)}; cells with void corners: ${chunks.reduce((n, c) => n + (c.terrain.void_dem_cells || 0), 0)}.\n` +
    `${manifest.plan.dem_unsupported || ""}\n` +
    `Stored asset bytes: ${chunks.reduce((n, c) => n + (c.vector.stored_bytes || 0) + (c.terrain.stored_bytes || 0), 0)}. No geographic percentage inferred from cells. Contours / hillshade require valid DEM neighborhoods and successful browser processing.\n` +
    chunks
      .filter((c) => c.vector.error || c.terrain.error)
      .map((c) => `${c.key}: ${c.vector.error || ""} ${c.terrain.error || ""}`)
      .join("\n")
  );
}
async function loadRadiusManifest(id) {
  const r = await mapAssetFetch(
    `/local/maps/${encodeURIComponent(id)}/manifest`,
  );
  if (!r.ok) throw Error("Saved coverage manifest unavailable");
  const manifest = await r.json();
  if (activeMapID !== id) return;
  radiusManifest = manifest;
  radiusManifest._recordingAsset = r.headers.get("X-Presentation-Asset") || "";
  radiusViewKey = undefined;
  radiusViewVersion++;
  globalThis.radiusGrids = [];
  terrain = undefined;
  $("resume-map").hidden = false;
  $("export-manifest").hidden = false;
  $("export-manifest").href = `/local/maps/${encodeURIComponent(id)}/manifest`;
  $("export-manifest").download = "map-source-coverage.json";
  $("save-use-map").hidden = false;
  $("approve-transfer").hidden = !manifest.transfer?.approval_required_bytes;
  $("approve-transfer").textContent = "Approve once";
  $("approve-transfer").setAttribute(
    "aria-label",
    `Approve cumulative ${manifest.transfer?.approval_required_bytes || 0} bytes for ${manifest.transfer?.current_source || "this source"}`,
  );
  $("map-coverage-status").textContent = radiusDescription(manifest);
  $("map-metadata").textContent = radiusDescription(manifest);
  updateLayers();
  draw();
}
async function previewRadius() {
  invalidatePreparation(false);
  for (const id of ["map-lat", "map-lon", "map-radius"])
    if (!$(id).reportValidity()) return;
  const request = {
    lat: Number($("map-lat").value),
    lon: Number($("map-lon").value),
    radius_m: Number($("map-radius").value) * Number($("radius-unit").value),
  };
  const revision = preparationRevision;
  try {
    const plan = await mapPost("map-plan", request);
    if (revision !== preparationRevision) return;
    preparedMap = {
      plan,
      components: Object.fromEntries(
        terrainLayers.map((k) => [k, $("download-" + k).checked]),
      ),
    };
    $("map-plan-details").textContent =
      `Additional network transfer: unknown. Initial cumulative allowance: 5 MB, shared by vectors, DEM, source discovery and retries. Large fallback requires additional approval before transfer. Disk: ≤256 MiB saved chunks, ≤1 GB source cache plus bounded on-disk index and 3 GB free-space admission for regional processing. WGS84 centre ${request.lat}, ${request.lon}; radius ${request.radius_m} m; approximate ellipsoidal area ${(plan.approximate_ellipsoidal_area_m2 / 1000000).toFixed(6)} km² (sampled boundary). Ellipsoidal geodesic circle. Envelope W/S/E/N ${plan.bounds.map((v) => v.toFixed(7)).join(", ")}. ${plan.chunks.length} chunks; ${plan.source_tiles.length} possible native source tiles including sampling halo; ${plan.maximum_source_requests_before_retries} maximum source requests before cache/retries. DEM transfer ≤ ${(plan.maximum_compressed_dem_bytes / 1048576).toFixed(1)} MiB before bounded retries (compressed sizes unknown). Vector sizes unknown, ≤20 MiB/chunk. DEM ≤${(plan.maximum_terrain_bytes / 1048576).toFixed(1)} MiB; chunk library budget 256 MiB; source cache 99 MiB; disk reserve 128 MiB; one network/decode at a time. Provider cooldown 10 s/vector request. ${plan.dem_unsupported || "DEM adapter: 56°S–60°N, WGS84/EGM96, native 1 arc sec; source accuracy varies."} ${plan.unsupported || "Confirm estimate, then acquire available layers. Coverage gaps remain explicit."}`;
    $("map-preparation-status").textContent =
      `${request.radius_m} m radius · ${plan.chunks.length} geographic chunks. Additional transfer unknown; initially limited to 5 MB. Regional sources require up to 3 GB free disk space. ${plan.unsupported || "Review coverage and resource limits, then Download Map."}`;
    $("map-preview").replaceChildren();
    if (plan.circle?.length) {
      const pts = plan.circle.map((p) => {
        const x = ((p[0] * Math.PI) / 180) * 6378137,
          y = GeoMap.project(0, p[1])[1];
        return [x, y];
      });
      const xs = pts.map((p) => p[0]),
        ys = pts.map((p) => p[1]),
        cx = (Math.min(...xs) + Math.max(...xs)) / 2,
        cy = (Math.min(...ys) + Math.max(...ys)) / 2;
      const scale = Math.min(
        380 / Math.max(1, Math.max(...xs) - Math.min(...xs)),
        160 / Math.max(1, Math.max(...ys) - Math.min(...ys)),
      );
      $("map-preview").append(
        svg("polygon", {
          points: pts
            .map(
              (p) => `${200 + (p[0] - cx) * scale},${90 - (p[1] - cy) * scale}`,
            )
            .join(" "),
          fill: "#4084aa22",
          stroke: "#2677a8",
          "stroke-width": 2,
          "data-requested-circle": "true",
        }),
      );
    }
    $("download-map").disabled = !!plan.unsupported;
  } catch (e) {
    $("map-preparation-status").textContent = e.message;
  }
}
async function startRadiusDownload() {
  const candidate = preparedMap;
  if (!$("confirm-map-cost").checked) {
    $("map-preparation-status").textContent +=
      " Review and acknowledge the initial transfer allowance.";
    $("confirm-map-cost").scrollIntoView({ block: "nearest" });
    $("confirm-map-cost").focus();
    return;
  }
  $("download-map").disabled = true;
  try {
    const saved = await mapPost("map-jobs", {
      ...candidate.plan.request,
      components: candidate.components,
      confirmed: $("confirm-map-cost").checked,
    });
    preparedMap = undefined;
    await refreshMaps(saved.id);
    await loadSavedMap(saved.id);
    await resumeRadius();
  } catch (e) {
    $("map-preparation-status").textContent = e.message;
    $("download-map").disabled = false;
  }
}
$("resume-map").onclick = () => resumeRadius().catch(showError);
async function resumeRadius() {
  const started = performance.now();
  if (downloadAbort) throw Error("Map acquisition already active");
  const id = activeMapID,
    controller = new AbortController();
  downloadAbort = controller;
  $("cancel-download").hidden = false;
  $("resume-map").disabled = true;
  $("approve-transfer").disabled = true;
  let failure;
  try {
    await loadRadiusManifest(id);
    const manifest = radiusManifest;
    const layers = [
      "vector",
      ...(Object.values(manifest.components).some(Boolean) ? ["terrain"] : []),
    ];
    for (const layer of layers) {
      try {
        for (const [chunkIndex, c] of manifest.chunks.entries()) {
          const needsProducts =
            layer === "terrain" &&
            Object.entries(manifest.components).some(
              ([k, v]) => v && !c.terrain.dem_metadata?.components?.[k],
            );
          if (c[layer].status === "complete" && !needsProducts) continue;
          if (layer === "terrain" && (c.bounds[1] < -56 || c.bounds[3] > 60))
            continue;
          if (controller.signal.aborted)
            throw Error(
              "Cancelled; validated chunks retained. Resume missing chunks safely.",
            );
          $("map-preparation-status").textContent =
            `Acquiring ${layer} chunk ${c.key}; ${manifest.chunks.filter((x) => x[layer].status === "complete").length}/${manifest.chunks.length} already committed. Cancel retains partial results.`;
          const response = await fetch(
            `/local/maps/${encodeURIComponent(id)}/chunks/${c.key}`,
            {
              method: "POST",
              headers: {
                "Content-Type": "application/json",
                Accept: "application/x-ndjson",
              },
              body: JSON.stringify({ layer }),
              signal: controller.signal,
            },
          );
          if (!response.ok) throw Error(await response.text());
          const reader = response.body.getReader(),
            decoder = new TextDecoder();
          let pending = "",
            attemptedOverpass = false,
            doneEvent = false;
          for (;;) {
            const { value, done } = await reader.read();
            pending += decoder.decode(value, { stream: !done });
            const lines = pending.split("\n");
            pending = lines.pop();
            for (const line of lines) {
              if (!line) continue;
              const event = JSON.parse(line);
              if (event.stage === "bounded Overpass request")
                attemptedOverpass = true;
              $("download-progress").hidden = false;
              $("download-progress").textContent =
                `${layer === "vector" ? "Map data" : "Terrain"} · ${manifest.chunks.filter((x) => x[layer].status === "complete").length}/${manifest.chunks.length} areas safely saved · ${event.stage}; ${((performance.now() - started) / 1000).toFixed(1)} s elapsed; ${event.received_bytes ?? "not measured"} observed bytes ${event.error || ""}`;
              if (event.stage === "failed") throw Error(event.error);
              if (event.stage === "complete") doneEvent = true;
            }
            if (done) break;
          }
          if (!doneEvent)
            throw Error(
              "Response lost; reload manifest and resume safely (committed chunks are reused).",
            );
          c[layer].status = "complete";
          if (
            layer === "vector" &&
            attemptedOverpass &&
            manifest.chunks
              .slice(chunkIndex + 1)
              .some((c) => c.vector.status !== "complete")
          )
            await new Promise((resolve, reject) => {
              const aborted = () => {
                clearTimeout(timer);
                reject(Error("Cancelled; partial results retained"));
              };
              const timer = setTimeout(() => {
                controller.signal.removeEventListener("abort", aborted);
                resolve();
              }, 10050);
              controller.signal.addEventListener("abort", aborted, {
                once: true,
              });
            });
        }
      } catch (e) {
        failure = e;
        if (controller.signal.aborted) throw e;
        await loadRadiusManifest(id);
        // Stop on consent so the displayed request cannot change under the operator.
        if (radiusManifest?.transfer?.approval_required_bytes) break;
        // Stop this provider's queue after one failure; try the independent layer.
      }
    }
  } catch (e) {
    failure = e;
  } finally {
    downloadAbort = undefined;
    $("cancel-download").hidden = true;
    $("resume-map").disabled = false;
    $("approve-transfer").disabled = false;
    if (activeMapID === id) {
      await loadRadiusManifest(id);
      await refreshMaps(id);
    }
  }
  if (radiusManifest?.plan) {
    $("download-progress").textContent =
      `${radiusSummary(radiusManifest)} Received ${radiusManifest.transfer?.received_bytes || 0} body bytes; saved ${radiusManifest.chunks.reduce((n, c) => n + (c.vector.stored_bytes || 0) + (c.terrain.stored_bytes || 0), 0)} verified asset bytes.`;
  }
  if (failure)
    $("download-report-text").textContent =
      (radiusManifest
        ? radiusDescription(radiusManifest)
        : "Saved manifest unavailable; reload Saved Maps.") +
      "\nLast operation: " +
      failure.message;
  const incomplete = radiusManifest?.chunks.some(
    (c) =>
      c.vector.status !== "complete" ||
      (Object.values(radiusManifest.components).some(Boolean) &&
        (c.terrain.status !== "complete" || c.terrain.void_dem_cells > 0)),
  );
  $("map-preparation-status").textContent =
    failure || incomplete
      ? `Partial map retained. ${radiusManifest?.transfer?.approval_required_bytes ? "Additional transfer approval is required." : "Some requested coverage or layers remain unavailable."} Retry Missing or Save & Use. Details explains the cause.`
      : "Saved locally · acquired chunks available after restart without internet; inspect source & coverage manifest for known voids and layer availability.";
}
function radiusClip(root, g, xy) {
  if (!g.core_bounds) return "none";
  const id = "clip-" + g.chunk_key;
  if (root.querySelector(`[id="${id}"]`)) return `url(#${id})`;
  const clip = svg("clipPath", { id }),
    a = xy(GeoMap.project(g.core_bounds[0], g.core_bounds[3])),
    b = xy(GeoMap.project(g.core_bounds[2], g.core_bounds[1]));
  clip.append(
    svg("rect", { x: a[0], y: a[1], width: b[0] - a[0], height: b[1] - a[1] }),
  );
  root.append(clip);
  return `url(#${id})`;
}
function drawRadiusCoverage(root, xy) {
  if (!radiusManifest) return;
  const group = svg("g", {
    "data-layer": "coverage",
    "pointer-events": "none",
  });
  for (const c of radiusManifest.chunks) {
    const a = xy(GeoMap.project(c.bounds[0], c.bounds[3])),
      b = xy(GeoMap.project(c.bounds[2], c.bounds[1]));
    group.append(
      svg("rect", {
        x: a[0],
        y: a[1],
        width: b[0] - a[0],
        height: b[1] - a[1],
        fill: "none",
        stroke: c.vector.status === "complete" ? "#3c9373" : "#c78431",
        "stroke-width": 0.7,
        "stroke-dasharray": "3 4",
      }),
    );
  }
  group.append(
    svg("polygon", {
      points: (
        offlineMap.plan.circle || [
          [offlineMap.plan.bounds[0], offlineMap.plan.bounds[1]],
          [offlineMap.plan.bounds[2], offlineMap.plan.bounds[1]],
          [offlineMap.plan.bounds[2], offlineMap.plan.bounds[3]],
          [offlineMap.plan.bounds[0], offlineMap.plan.bounds[3]],
        ]
      )
        .map((p) => xy(GeoMap.project(...p)).join(","))
        .join(" "),
      fill: "none",
      stroke: "#277bb8",
      "stroke-width": 2,
    }),
  );
  root.append(group);
}
function scheduleRadiusView(w, h) {
  if (!radiusManifest || !viewport) return;
  const id = activeMapID,
    key = JSON.stringify([id, viewport, w, h]);
  if (key === radiusViewKey) return;
  radiusViewKey = key;
  clearTimeout(radiusViewTimer);
  radiusFetchAbort?.abort();
  const controller = new AbortController();
  radiusFetchAbort = controller;
  const version = ++radiusViewVersion;
  const a = GeoMap.unproject(
      viewport.cx - w / 2 / viewport.scale,
      viewport.cy - h / 2 / viewport.scale,
    ),
    b = GeoMap.unproject(
      viewport.cx + w / 2 / viewport.scale,
      viewport.cy + h / 2 / viewport.scale,
    );
  const center = offlineMap.plan.request.lon,
    unwrap = (x) => center + ((x - center + 540) % 360) - 180;
  const chunks = radiusManifest.chunks.filter(
    (c) =>
      unwrap(c.bounds[2]) >= a[0] &&
      unwrap(c.bounds[0]) <= b[0] &&
      c.bounds[3] >= a[1] &&
      c.bounds[1] <= b[1],
  );
  radiusViewTimer = setTimeout(async () => {
    const shapes = [],
      appliedVectorAssets = [],
      grids = [],
      detailWarnings = [];
    let count = 0,
      loadedVectors = 0,
      omittedFeatures = 0;
    const vectorChunks = chunks.filter(
      (c) => c.vector.status === "complete",
    ).length;
    const chunkBudget = Math.floor(100000 / Math.max(1, vectorChunks));
    const detailScale = viewport.scale;
    const generationStarted = performance.now();
    try {
      if (chunks.filter((c) => c.terrain.status === "complete").length > 8)
        detailWarnings.push(
          "Overview shows vectors across all saved chunks. DEM detail is limited to eight grids; zoom in for terrain throughout the viewport.",
        );
      for (const c of chunks) {
        if (version !== radiusViewVersion || activeMapID !== id) return;
        if (c.vector.status === "complete") {
          try {
            const r = await mapAssetFetch(
              `/local/maps/${encodeURIComponent(id)}/chunks/${c.key}?layer=vector`,
              { signal: controller.signal },
            );
            if (!r.ok) throw Error("Saved vector chunk read failed");
            const text = await r.text();
            if (version !== radiusViewVersion || activeMapID !== id) return;
            const parsed = GeoMap.parse(text, "OSM");
            const detail = GeoMap.overview(
              parsed.shapes,
              1 / detailScale,
              chunkBudget,
            );
            count += detail.count;
            omittedFeatures += detail.omitted;
            shapes.push(...detail.shapes);
            appliedVectorAssets.push(
              r.headers.get("X-Presentation-Asset") || "",
            );
            loadedVectors++;
            offlineMap = {
              ...offlineMap,
              shapes: shapes.slice(),
              _recordingVectors: appliedVectorAssets.slice(),
            };
            $("map-detail-status").textContent =
              `Loading local overview: ${loadedVectors}/${vectorChunks} saved vector chunks; ${omittedFeatures} features deferred by the display budget. Zoom in for more detail.`;
            draw();
            await new Promise(requestAnimationFrame);
          } catch (error) {
            detailWarnings.push(
              `${c.key}: vector not rendered: ${error.message}`,
            );
          }
        }
        if (c.terrain.status === "complete" && grids.length < 8) {
          try {
            const r = await mapAssetFetch(
              `/local/maps/${encodeURIComponent(id)}/chunks/${c.key}?layer=terrain`,
              { signal: controller.signal },
            );
            if (!r.ok) throw Error("Saved DEM chunk read failed");
            const grid = await r.json();
            grid._recordingAsset = r.headers.get("X-Presentation-Asset") || "";
            if (
              !Array.isArray(grid.samples) ||
              !Number.isInteger(grid.columns) ||
              !Number.isInteger(grid.rows) ||
              grid.columns < 2 ||
              grid.columns > 257 ||
              grid.rows < 2 ||
              grid.rows > 257 ||
              grid.samples.length !== grid.columns * grid.rows
            )
              throw Error("Invalid saved DEM grid");
            grid.core_bounds = c.bounds;
            grid.chunk_key = c.key;
            grids.push(grid);
          } catch (error) {
            detailWarnings.push(`${c.key}: DEM not rendered: ${error.message}`);
          }
        }
      }
      if (version !== radiusViewVersion || activeMapID !== id) return;
      let low = Infinity,
        high = -Infinity;
      for (const grid of grids)
        for (const v of grid.samples)
          if (v !== null) {
            low = Math.min(low, v);
            high = Math.max(high, v);
          }
      const wanted = Math.max(5, (high - low) / 24),
        power = 10 ** Math.floor(Math.log10(wanted));
      const interval = [1, 2, 5, 10]
        .map((v) => v * power)
        .find((v) => v >= wanted);
      const prepared = [];
      for (const grid of grids) {
        try {
          prepared.push({
            ...Terrain.prepare(grid, interval, grids),
            _recordingAsset: grid._recordingAsset,
          });
        } catch (error) {
          detailWarnings.push(
            `${grid.chunk_key}: DEM processing failed: ${error.message}`,
          );
        }
        await new Promise(requestAnimationFrame);
        if (version !== radiusViewVersion || activeMapID !== id) return;
      }
      grids.splice(0, grids.length, ...prepared);
      if (omittedFeatures)
        detailWarnings.push(
          `${omittedFeatures} stored features deferred by the 100,000-coordinate overview budget; zoom in for detail. Saved data is unchanged.`,
        );
      $("map-detail-status").textContent =
        `Local detail: ${loadedVectors}/${vectorChunks} saved vector chunks loaded; ${chunks.length} visible chunks, ${grids.length} DEM grids; ${offlineMap.plan.full_extent ? "declared source extent" : "requested circle"} in blue; dashed envelopes show acquired/missing coverage.`;
      detailWarnings.push(
        ...grids
          .filter((g) => g.contour_error || g.hillshade_error)
          .map(
            (g) => `${g.chunk_key}: ${g.contour_error || g.hillshade_error}`,
          ),
      );
      $("map-warning").textContent = detailWarnings.join("; ");
      if (detailWarnings.length)
        $("map-detail-status").textContent += " " + detailWarnings.join("; ");
    } catch (e) {
      if (version !== radiusViewVersion) return;
      // Retain independently decoded geography if a later component fails.
      $("map-warning").textContent = e.message;
      $("map-detail-status").textContent = e.message;
    }
    if (version !== radiusViewVersion || activeMapID !== id) return;
    terrainGeneration = {
      id,
      ms: performance.now() - generationStarted,
      visible_chunks: chunks.length,
      coordinates: count,
    };
    offlineMap = {
      ...offlineMap,
      shapes,
      _recordingVectors: appliedVectorAssets,
    };
    globalThis.radiusGrids = grids;
    terrain = grids.length
      ? {
          ...grids[0],
          grids,
          contour_error: undefined,
          hillshade_error: undefined,
          components: Object.fromEntries(
            terrainLayers.map((k) => [
              k,
              grids.some(
                (g) =>
                  g.components[k] &&
                  (k !== "contours" || g.contour) &&
                  (k !== "hillshade" || g.shades),
              ),
            ]),
          ),
        }
      : undefined;
    updateLayers();
    draw();
  }, 120);
}

$("new-map").onclick = (event) => {
  event.preventDefault();
  $("map-preparation").open = true;
  $("maps-library").open = false;
  $("map-import").open = false;
  $("map-lat").focus();
};
$("open-map-import").onclick = (event) => {
  event.preventDefault();
  $("map-import").open = true;
  $("maps-library").open = false;
  $("map-preparation").open = false;
  $("source-map-file").focus();
};
$("save-use-map").onclick = async () => {
  if (downloadAbort) downloadAbort.abort();
  if (activeMapID) {
    await loadSavedMap(activeMapID);
    $("map-settings").close();
  }
};
$("approve-transfer").onclick = async () => {
  if (
    downloadAbort ||
    mapImportPending ||
    transferApprovalPending ||
    !activeMapID
  )
    return;
  const approvalMapID = activeMapID;
  transferApprovalPending = true;
  $("approve-transfer").disabled = true;
  try {
    // Re-read durable consent before prompting: a lost response or restart must
    // not ask again for an allowance the server already committed.
    await loadRadiusManifest(approvalMapID);
    if (!$("map-settings").open || activeMapID !== approvalMapID) return;
    const n = radiusManifest?.transfer?.approval_required_bytes;
    if (!n) {
      await resumeRadius();
      return;
    }
    if (
      !confirm(
        `Source: ${radiusManifest.transfer.current_source || "unknown"}. Already received ${(radiusManifest.transfer.received_bytes / 1000000).toFixed(2)} MB. Approve cumulative network transfer up to ${(n / 1000000).toFixed(2)} MB (${n} bytes) for this map job, including bytes already received? Regional sources may require up to 3 GB free disk space. Further escalation will require approval.`,
      )
    )
      return;
    await mapPost(`maps/${encodeURIComponent(activeMapID)}/approve-transfer`, {
      approved_bytes: n,
    });
    await resumeRadius();
  } catch (e) {
    $("map-preparation-status").textContent = e.message;
  } finally {
    transferApprovalPending = false;
    $("approve-transfer").disabled = !!downloadAbort;
    $("map-import-status").dispatchEvent(new Event("map-operation-change"));
  }
};
$("import-map-source").onclick = async () => {
  if (downloadAbort || mapImportPending || transferApprovalPending) return;
  const file = $("source-map-file").files[0];
  if (!file) return;
  const controller = new AbortController();
  downloadAbort = controller;
  mapImportPending = true;
  $("import-map-source").disabled = true;
  $("map-import-status").textContent =
    `Validating local ${file.name} (${file.size.toLocaleString()} bytes). No network transfer. Existing maps remain available.`;
  try {
    let saved;
    if (/\.(geojson|json)$/i.test(file.name)) {
      if (file.size > 20 * 1024 * 1024) throw Error("GeoJSON exceeds 20 MiB");
      const data = JSON.parse(await file.text());
      controller.signal.throwIfAborted();
      saved = await mapPost(
        "maps",
        { source: file.name, data },
        controller.signal,
      );
    } else {
      const zip = /\.zip$/i.test(file.name);
      if (file.size > (zip ? 256 * 1024 * 1024 : 1000000000))
        throw Error("Input exceeds supported resource limit");
      const query = new URLSearchParams({
        mode: $("source-map-mode").value,
        lat: $("import-map-lat").value,
        lon: $("import-map-lon").value,
        radius_m: $("import-map-radius").value,
      });
      const r = await fetch(
        zip ? "/local/map-package" : "/local/map-import?" + query,
        {
          method: "POST",
          headers: { "Content-Type": "application/octet-stream" },
          body: file,
          signal: controller.signal,
        },
      );
      saved = await r.json();
      if (!r.ok) throw Error(saved.message || "Import rejected");
    }
    if (downloadAbort === controller) downloadAbort = undefined;
    await refreshMaps(saved.id);
    await loadSavedMap(saved.id);
    if (!/\.zip$/i.test(file.name) && offlineMap?.plan) await resumeRadius();
    $("map-import-status").textContent = radiusManifest
      ? `Stored in Saved Maps. ${radiusSummary(radiusManifest)} ${radiusManifest.chunks.some((c) => c.vector.status === "complete" || c.terrain.status === "complete") ? "Verified saved areas are available offline." : "No verified map areas yet; retry missing work."}`
      : "Stored in Saved Maps and active. Source attribution is retained.";
  } catch (e) {
    $("map-import-status").textContent = controller.signal.aborted
      ? "Import stopped. Previously saved geography is retained."
      : e.message;
  } finally {
    if (downloadAbort === controller) downloadAbort = undefined;
    mapImportPending = false;
    $("import-map-source").disabled = false;
    $("map-import-status").dispatchEvent(new Event("map-operation-change"));
  }
};

$("source-map-mode").onchange = () => {
  $("import-crop-fields").hidden = $("source-map-mode").value === "full";
};

$("source-map-file").onchange = () => {
  $("regional-import-options").hidden = !/\.pbf$/i.test(
    $("source-map-file").files[0]?.name || "",
  );
};

// V3 adapts the existing popup and controls in place. No duplicate action/state
// owners: moved nodes retain their original backend handlers and identifiers.
function initializeMapModal() {
  const dialog = $("map-settings"),
    get = $("map-preparation"),
    library = $("maps-library"),
    imports = $("map-import");
  const current = $("current-map-name").closest("section");
  const display = el("details");
  display.append(el("summary", "Display & geographic details"));
  display.append(
    current.querySelector(".current-map-controls"),
    $("current-map-layers"),
  );
  const oldDisplay = current.querySelector("details");
  for (const node of [...oldDisplay.children].slice(1)) display.append(node);
  oldDisplay.remove();
  library.append(display, $("map-warning"));
  current.classList.add("map-current-strip");
  $("current-map-title").textContent = "Current Map";
  const change = el("button", "Change in My Maps →");
  change.className = "map-text-action";
  change.onclick = () => selectTab("maps-library");
  current.append(change);
  const tabs = el("div");
  tabs.className = "map-task-tabs";
  tabs.setAttribute("role", "tablist");
  tabs.setAttribute("aria-label", "Map tasks");
  const panels = [
    [get, "Get a Map", "new-map"],
    [imports, "Import", "open-map-import"],
    [library, "My Maps", "choose-saved-map"],
  ];
  let importAreaEdited = false;
  function selectTab(id) {
    if (
      id === "map-import" &&
      !importAreaEdited &&
      $("map-shape").value === "circle"
    ) {
      $("import-map-lat").value = $("map-lat").value;
      $("import-map-lon").value = $("map-lon").value;
      $("import-map-radius").value =
        Number($("map-radius").value) * Number($("radius-unit").value);
      updateImportPreview().catch(showError);
    }
    dialog.dataset.mapTab = id;
    body.scrollTop = 0;
    for (const [panel] of panels) {
      const selected = panel.id === id;
      panel.open = selected;
      const wrapper = $(panel.id + "-panel");
      if (wrapper) wrapper.hidden = !selected;
      const button = $("tab-" + panel.id);
      button.setAttribute("aria-selected", String(selected));
      button.tabIndex = selected ? 0 : -1;
    }
    updateTask();
  }
  panels.forEach(([panel, label, legacy], index) => {
    const tab = el("button", label);
    tab.id = "tab-" + panel.id;
    tab.setAttribute("role", "tab");
    tab.setAttribute("aria-controls", panel.id);
    panel.classList.add("map-task-panel");
    panel.setAttribute("aria-labelledby", tab.id);
    tab.onclick = () => selectTab(panel.id);
    tab.onkeydown = (e) => {
      let next = index;
      if (e.key === "ArrowRight") next = (index + 1) % 3;
      else if (e.key === "ArrowLeft") next = (index + 2) % 3;
      else if (e.key === "Home") next = 0;
      else if (e.key === "End") next = 2;
      else return;
      e.preventDefault();
      selectTab(panels[next][0].id);
      $("tab-" + panels[next][0].id).focus();
    };
    tabs.append(tab);
    $(legacy).onclick = (e) => {
      e.preventDefault();
      selectTab(panel.id);
    };
  });
  current.after(tabs);
  const body = el("div");
  body.className = "map-modal-body";
  const feedback = dialog.querySelector(".map-download-feedback");
  tabs.after(body);
  body.append(get, imports, library);
  for (const [panel] of panels) {
    const wrapper = el("div");
    wrapper.id = panel.id + "-panel";
    wrapper.setAttribute("role", "tabpanel");
    wrapper.setAttribute("aria-labelledby", "tab-" + panel.id);
    panel.removeAttribute("role");
    panel.before(wrapper);
    wrapper.append(panel);
    $("tab-" + panel.id).setAttribute("aria-controls", wrapper.id);
  }
  const footer = el("div");
  footer.className = "map-modal-footer";
  const footerNote = el("small", "Saved maps work without internet");
  footerNote.className = "muted";
  const actions = el("div");
  actions.className = "map-footer-actions";
  footer.append(footerNote, actions);
  dialog.append(footer);
  const getActions = el("div");
  getActions.dataset.mapAction = "map-preparation";
  getActions.append($("download-map"));
  actions.append(getActions);
  const importActions = el("div");
  importActions.dataset.mapAction = "map-import";
  importActions.append($("import-map-source"));
  actions.append(importActions);
  $("import-map-source").classList.add("primary");
  const libraryActions = el("div");
  libraryActions.dataset.mapAction = "maps-library";
  const use = el("button", "Use selected map");
  use.className = "primary";
  use.id = "use-selected-map";
  use.onclick = () => $("save-use-map").onclick();
  libraryActions.append(use);
  actions.append(libraryActions);
  const taskActions = el("div");
  taskActions.className = "map-active-actions";
  const notNow = el("button", "Not now");
  notNow.className = "map-approval-defer";
  notNow.onclick = () => dialog.close();
  taskActions.append(notNow);
  taskActions.append(
    $("cancel-download"),
    $("resume-map"),
    $("save-use-map"),
    $("approve-transfer"),
  );
  actions.append(taskActions);
  $("save-use-map").textContent = "Use saved areas";
  $("cancel-download").textContent = "Stop download";
  $("save-use-map").classList.add("primary");
  $("approve-transfer").classList.add("primary");
  for (const p of [...get.querySelectorAll(":scope > p")]) p.remove();
  const form = el("div");
  form.id = "get-map-form";
  for (const node of [...get.children].slice(1)) form.append(node);
  get.append(form);
  form.prepend(el("h2", "Where do you need the map?"));
  const search = form.querySelector(".map-search-row");
  const centre = el("button", "Use map centre");
  centre.id = "use-map-centre";
  centre.onclick = () => {
    if (!viewport) return;
    const [lon, lat] = GeoMap.unproject(viewport.cx, viewport.cy);
    $("map-lat").value = lat;
    $("map-lon").value = ((lon + 540) % 360) - 180;
    invalidatePreparation();
  };
  search.append(centre);
  $("search-map").textContent = "Find";
  const precise = el("details");
  precise.className = "map-precise";
  precise.append(el("summary", "Coordinates & advanced area"));
  precise.append(
    $("map-lat").closest(".coordinate-fields"),
    $("pick-map-center"),
  );
  const shapeDetails = $("map-shape").closest("details");
  precise.append($("map-shape").closest("label"));
  shapeDetails.remove();
  precise.append($("legacy-area"));
  search.after(precise);
  const presets = el("div");
  presets.className = "map-radius-presets";
  presets.setAttribute("aria-label", "Coverage radius");
  const radiusLabel = el("p", "Coverage radius");
  radiusLabel.className = "map-field-caption";
  radiusLabel.id = "map-radius-caption";
  precise.after(radiusLabel, presets);
  const custom = $("radius-fields");
  for (const [label, value] of [
    ["1 km", 1000],
    ["5 km", 5000],
    ["10 km", 10000],
    ["Custom", null],
  ]) {
    const b = el("button", label);
    b.setAttribute("aria-pressed", "false");
    b.onclick = () => {
      for (const x of presets.children)
        x.setAttribute("aria-pressed", String(x === b));
      custom.hidden = value !== null;
      if (value !== null) {
        $("map-radius").value = value;
        $("radius-unit").value = "1";
        invalidatePreparation();
      } else $("map-radius").focus();
    };
    presets.append(b);
  }
  presets.after(custom);
  custom.hidden = true;
  presets.children[1].click();
  const preview = el("section");
  preview.className = "map-selection-preview";
  preview.append(
    el("p", "Selection preview"),
    $("map-preview"),
    el(
      "small",
      "Requested area only · geographic features appear after acquisition.",
    ),
  );
  get.append(preview);
  const optional = el("div");
  optional.className = "map-optional-terrain";
  const terrainLabel = el("label");
  const include = el("input");
  include.type = "checkbox";
  include.id = "include-map-terrain";
  terrainLabel.append(
    include,
    document.createTextNode(" Include terrain & elevation"),
  );
  const terrainOptions = $("download-hillshade").closest("fieldset");
  const products = el("details");
  products.append(
    el("summary", "Terrain products & provider details"),
    terrainOptions,
  );
  products.hidden = true;
  include.onchange = () => {
    products.hidden = !include.checked;
    for (const k of terrainLayers) $("download-" + k).checked = include.checked;
    invalidatePreparation();
  };
  optional.append(
    terrainLabel,
    el("small", "Hillshade and contours use available elevation data."),
    products,
  );
  get.append(optional);
  optional.after(precise);
  for (const k of terrainLayers)
    $("download-" + k).addEventListener("change", () => {
      include.checked = terrainLayers.some((k) => $("download-" + k).checked);
    });

  const consent = $("confirm-map-cost").closest("label");
  consent.className = "map-initial-consent";
  consent.lastChild.textContent =
    " Allow up to 5 MB for this map. Ask before any additional transfer.";
  get.append(consent);
  const technical = el("details");
  technical.append(
    el("summary", "Coverage, sources & resource limits"),
    $("map-plan-details"),
    $("preview-map"),
  );
  form.querySelector(".map-actions").remove();
  get.append(technical);
  // The old details container now has no plan content; remove that empty wrapper.
  for (const d of [...form.querySelectorAll(":scope > details")])
    if (!d.querySelector("input,select,button,p")) d.remove();
  get.append($("map-preparation-status"));
  body.prepend(feedback);
  const taskTitle = el("h2");
  taskTitle.id = "map-task-title";
  feedback.prepend(taskTitle);
  const taskSource = el("p");
  taskSource.id = "map-task-source";
  taskSource.className = "muted";
  taskTitle.after(taskSource);
  const taskReason = el("p");
  taskReason.id = "map-task-reason";
  taskReason.setAttribute("role", "status");
  taskSource.after(taskReason);
  feedback.setAttribute("aria-label", "Map acquisition and recovery");
  const originalDownload = $("download-map").onclick;
  $("download-map").textContent = "Preview & Download";
  $("download-map").disabled = false;
  let primaryPending = false;
  $("download-map").onclick = async () => {
    if (
      primaryPending ||
      mapImportPending ||
      transferApprovalPending ||
      downloadAbort
    )
      return;
    primaryPending = true;
    $("download-map").disabled = true;
    $("download-map").setAttribute("aria-busy", "true");
    try {
      if (!preparedMap) {
        const expected = preparationRevision + 1;
        await $("preview-map").onclick();
        if (preparationRevision !== expected) return;
      }
      if (preparedMap && !preparedMap.plan?.unsupported)
        await originalDownload();
    } finally {
      primaryPending = false;
      $("download-map").removeAttribute("aria-busy");
      $("download-map").disabled = !!preparedMap?.plan?.unsupported;
    }
  };
  imports.insertBefore(
    el("h2", "Import an existing offline map"),
    imports.children[1],
  );
  $("source-map-file").closest("label").classList.add("map-file-drop");
  $("import-map-source").disabled = !$("source-map-file").files.length;
  $("source-map-file").addEventListener("change", () => {
    $("import-map-source").disabled =
      !$("source-map-file").files.length || !!downloadAbort || mapImportPending;
  });
  $("source-map-mode").options[0].textContent = "Selected area";
  $("source-map-mode").options[1].textContent = "Entire file · if feasible";
  library.insertBefore(el("h2", "Your offline maps"), library.children[1]);
  function updateTask() {
    const previousState = dialog.dataset.mapState;
    const transfer = radiusManifest?.transfer;
    const acquiring = !!downloadAbort;
    const busy = acquiring || mapImportPending || transferApprovalPending;
    dialog.dataset.mapImportBusy = String(mapImportPending);
    for (const control of dialog.querySelectorAll(
      "#saved-maps,#map-mode,#map-file,#source-map-file,#source-map-mode,#import-map-lat,#import-map-lon,#import-map-radius",
    ))
      control.disabled = busy;
    $("import-map-source").disabled =
      busy || !$("source-map-file").files.length;
    $("cancel-download").hidden = !downloadAbort;
    $("cancel-download").textContent = mapImportPending
      ? "Stop import"
      : "Stop download";
    $("resume-map").disabled = busy;
    const partial = radiusManifest?.chunks?.some(
      (c) =>
        c.vector.status !== "complete" ||
        (Object.values(radiusManifest.components || {}).some(Boolean) &&
          (c.terrain.status !== "complete" || c.terrain.void_dem_cells > 0)),
    );
    const available = radiusManifest?.chunks?.some(
      (c) => c.vector.status === "complete" || c.terrain.status === "complete",
    );
    const approval = !mapImportPending && !!transfer?.approval_required_bytes;
    $("approve-transfer").disabled = busy || !approval;
    const task = acquiring || approval || !!radiusManifest;
    dialog.dataset.mapState = approval
      ? "approval"
      : acquiring
        ? "progress"
        : partial
          ? "recovery"
          : "idle";
    if (
      previousState !== dialog.dataset.mapState &&
      ["progress", "approval", "recovery"].includes(dialog.dataset.mapState)
    )
      body.scrollTop = 0;
    form.hidden = acquiring || approval;
    optional.hidden = acquiring || approval;
    precise.hidden = acquiring || approval;
    consent.hidden =
      acquiring || approval || dialog.dataset.mapTab !== "map-preparation";
    $("map-preparation-status").hidden = acquiring || approval;
    feedback.hidden =
      !task ||
      dialog.dataset.mapTab === "map-import" ||
      (!acquiring && !approval && !partial);
    taskTitle.textContent = mapImportPending
      ? "Importing local map"
      : approval
        ? "Additional download approval required"
        : acquiring
          ? "Downloading selected area"
          : partial
            ? available
              ? "Partial map available"
              : "Map not yet available"
            : "Saved map available";
    const importFile = $("source-map-file").files[0];
    taskSource.textContent = mapImportPending
      ? `Local import: ${importFile?.name || "selected file"}. No provider transfer.`
      : transfer
        ? `Source: ${transfer.current_source || "not contacted yet / local checks"} · ${transfer.received_bytes.toLocaleString()} bytes received · approved ceiling ${transfer.approved_bytes.toLocaleString()} bytes${approval ? ` · additional ${Math.max(0, transfer.approval_required_bytes - transfer.approved_bytes).toLocaleString()} bytes requested · new cumulative ceiling ${transfer.approval_required_bytes.toLocaleString()} bytes` : ""}`
        : "";
    const cause = radiusManifest?.chunks
      ?.flatMap((c) => [c.vector.error, c.terrain.error])
      .find(Boolean);
    taskReason.textContent = mapImportPending
      ? ""
      : cause ||
        (partial
          ? "Requested coverage or terrain remains unavailable. Verified saved areas are retained."
          : "");
    getActions.hidden = acquiring || approval;
    use.disabled = busy || !activeMapID || (!!radiusManifest && !available);
    $("save-use-map").disabled = !!radiusManifest && !available;
    $("delete-map").disabled = busy || !activeMapID;
    $("export-map").hidden = !activeMapID;
    centre.disabled = busy || !viewport;
    $("use-import-centre").disabled = busy || !viewport;
    $("use-import-centre").hidden = $("source-map-mode").value === "full";
    footerNote.textContent = acquiring
      ? "Verified saved areas are retained"
      : approval
        ? "Approval applies to this cumulative allowance"
        : "Saved maps work without internet";
  }
  // Backend handlers already publish authoritative progress/manifest state into
  // these nodes. Observe those changes, without introducing another job timer.
  new MutationObserver(updateTask).observe($("download-progress"), {
    childList: true,
    subtree: true,
    attributes: true,
  });
  new MutationObserver(updateTask).observe($("approve-transfer"), {
    attributes: true,
    attributeFilter: ["hidden"],
  });
  $("source-map-mode").addEventListener("change", updateTask);
  $("map-import-status").addEventListener("map-operation-change", updateTask);
  new MutationObserver(updateTask).observe($("map-import-status"), {
    childList: true,
    subtree: true,
  });
  new MutationObserver(updateTask).observe($("current-map-name"), {
    childList: true,
  });
  const consentArea = el("div");
  consentArea.className = "map-footer-consent";
  consentArea.append(footerNote, consent);
  footer.prepend(consentArea);
  library.insertBefore(display.querySelector(".current-map-controls"), display);
  library.insertBefore($("current-map-layers"), display);
  const visibility = el("button", "Layer visibility");
  visibility.onclick = () => {
    dialog.close();
    $("open-layers").click();
  };
  library.insertBefore(visibility, display);
  $("regional-import-options").hidden = false;
  for (const id of ["import-map-lat", "import-map-lon", "import-map-radius"])
    $(id).addEventListener("input", () => {
      importAreaEdited = true;
    });
  const useImportCentre = el("button", "Use map centre");
  useImportCentre.id = "use-import-centre";
  useImportCentre.onclick = () => {
    if (!viewport) return;
    const [lon, lat] = GeoMap.unproject(viewport.cx, viewport.cy);
    $("import-map-lat").value = lat;
    $("import-map-lon").value = ((lon + 540) % 360) - 180;
    importAreaEdited = true;
    updateImportPreview().catch(showError);
  };
  $("import-crop-fields").before(useImportCentre);
  const importLimits = $("regional-import-options").querySelector("p");
  const importDetails = el("details");
  importDetails.append(
    el("summary", "Supported coverage & source details"),
    importLimits,
  );
  imports.append(importDetails);
  const importFormatNote = el(
    "small",
    "Supported: OSM PBF, GeoJSON and portable .gmap.zip.",
  );
  $("source-map-file").closest("label").append(importFormatNote);
  const importPreview = el("section");
  importPreview.className = "map-selection-preview";
  const importSVG = svg("svg", {
    viewBox: "0 0 400 180",
    role: "img",
    "aria-label": "Selected import area preview",
  });
  importSVG.id = "import-area-preview";
  importPreview.append(
    el("p", "Selected import area"),
    importSVG,
    el(
      "small",
      "Requested crop only · source coverage is validated during import.",
    ),
  );
  imports.insertBefore(importPreview, $("map-import-status"));
  let importPreviewVersion = 0;
  async function updateImportPreview() {
    const revision = ++importPreviewVersion;
    const file = $("source-map-file").files[0];
    importPreview.hidden =
      $("source-map-mode").value === "full" ||
      (file && !/\.pbf$/i.test(file.name));
    if (importPreview.hidden) return;
    try {
      const plan = await mapPost("map-plan", {
        lat: Number($("import-map-lat").value),
        lon: Number($("import-map-lon").value),
        radius_m: Number($("import-map-radius").value),
      });
      if (revision !== importPreviewVersion) return;
      importSVG.replaceChildren();
      if (plan.circle?.length) {
        const points = plan.circle.map((p) => GeoMap.project(...p));
        const xs = points.map((p) => p[0]),
          ys = points.map((p) => p[1]);
        const cx = (Math.min(...xs) + Math.max(...xs)) / 2,
          cy = (Math.min(...ys) + Math.max(...ys)) / 2;
        const scale = Math.min(
          380 / Math.max(1, Math.max(...xs) - Math.min(...xs)),
          160 / Math.max(1, Math.max(...ys) - Math.min(...ys)),
        );
        importSVG.append(
          svg("polygon", {
            points: points
              .map(
                (p) =>
                  `${200 + (p[0] - cx) * scale},${90 - (p[1] - cy) * scale}`,
              )
              .join(" "),
            fill: "#4084aa22",
            stroke: "#2677a8",
            "stroke-width": 2,
          }),
        );
      }
    } catch (e) {
      if (revision === importPreviewVersion) {
        importSVG.replaceChildren();
        $("map-import-status").textContent = e.message;
      }
    }
  }
  for (const id of [
    "import-map-lat",
    "import-map-lon",
    "import-map-radius",
    "source-map-mode",
    "source-map-file",
  ])
    $(id).addEventListener("change", updateImportPreview);
  updateImportPreview().catch(showError);
  selectTab("map-preparation");
  updateTask();
  previewRadius().catch(showError);
}
initializeMapModal();

function recordingAppliedAssets() {
  if ($("map-mode").value !== "offline" || !offlineMap)
    return { assets: [], warnings: [] };
  const references = [
    offlineMap._recordingAsset,
    ...(offlineMap._recordingVectors || []),
  ];
  if (radiusManifest) references.push(radiusManifest._recordingAsset);
  if (terrain)
    for (const grid of terrain.grids || [terrain])
      references.push(grid._recordingAsset);
  return {
    assets: [...new Set(references.filter(Boolean))],
    warnings: references.some((h) => !h)
      ? [
          "Some applied map/terrain response revisions could not be pinned; map fidelity is incomplete.",
        ]
      : [],
  };
}
window.recordingAppliedState = () => {
  const applied = window.recordingRenderedModel || {};
  const assetEvidence = recordingAppliedAssets();
  return {
    version: 1,
    map_id: $("map-mode").value === "offline" ? activeMapID || "" : "",
    assets: assetEvidence.assets,
    asset_warnings: assetEvidence.warnings,
    common: {
      receipt_version: applied.receipt_version,
      projection_version: applied.projection_version,
      viewport,
      appearance: document.documentElement.dataset.appearance,
      width: $("tracks").clientWidth,
      height: $("tracks").clientHeight,
      policy: applied.policy,
      selected_party: selectedParty,
      hidden: [...hidden],
      panels: {
        open_dialogs: [...document.querySelectorAll("dialog[open]")].map(
          (e) => e.id,
        ),
        parties: !document
          .querySelector("main")
          .classList.contains("parties-closed"),
        details: !document
          .querySelector("main")
          .classList.contains("details-closed"),
      },
      layers: Object.fromEntries(
        [...document.querySelectorAll("#layers input, #layers select")].map(
          (e) => [e.id, e.type === "checkbox" ? e.checked : e.value],
        ),
      ),
      connection: liveConnection,
      map_status: $("map-detail-status").textContent,
      map_warning: $("map-warning").textContent,
      projection_pending: applied.projection_pending,
      projection_error: applied.projection_error,
      // Common map geometry contains no party GNSS data.
      map_svg: [...$("tracks").children]
        .filter(
          (e) =>
            ["defs","clippath","mask","lineargradient","radialgradient","pattern"].includes(e.tagName.toLowerCase()) ||
            ["offline-map", "hillshade", "contours", "coverage"].includes(
              e.dataset.layer,
            ),
        )
        .map((e) => e.outerHTML)
        .join(""),
    },
    parties: Object.fromEntries(
      Object.entries(applied.devices || {}).map(([id, d]) => [
        id,
        {
          device: structuredClone(d),
          visible: !hidden.has(id),
          selected: id === selectedParty,
          points: (applied.points || []).filter((p) => p.device_id === id),
          provisional: (applied.provisional_points || []).filter(
            (p) => p.device_id === id,
          ),
          raw: (applied.raw_points || []).filter((p) => p.device_id === id),
          sos: (applied.sos_alerts || []).filter((a) => a.device_id === id),
          svg: [...$("tracks").querySelectorAll("[data-device]")]
            .filter((e) => e.dataset.device === id)
            .map((e) => e.outerHTML)
            .join(""),
          panel:
            id === selectedParty ? $("selected-detail")?.innerText : undefined,
          inspection:
            id === inspectionDevice && $("history-inspection").open
              ? $("inspection-data").textContent
              : undefined,
        },
      ]),
    ),
  };
};
window.recordingLiveState = () => state;

window.restoreLiveDashboard = () => { const main=document.querySelector("main");$("toggle-parties").setAttribute("aria-expanded",String(!main.classList.contains("parties-closed")));$("close-details").setAttribute("aria-expanded",String(!main.classList.contains("details-closed"))); render(); draw(); };

function appendPinnedHillshade(root,terrain,xyCoord,night) {
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
            "clip-path": radiusClip(root, terrain, xyCoord),
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

function appendPinnedContours(root,terrain,xyCoord,night) {
        const lines = svg("g", {
          "data-layer": "contours",
          "clip-path": radiusClip(root, terrain, xyCoord),
          "pointer-events": "none",
        });
        for (const [i, segment] of terrain.contour.segments.entries()) {
          const coords = segment.points.map((p) =>
            xyCoord(GeoMap.project(...p)),
          );
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
            Math.hypot(
              coords[1][0] - coords[0][0],
              coords[1][1] - coords[0][1],
            ) > 35
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

window.PinnedTerrainRenderer = {hillshade:appendPinnedHillshade,contours:appendPinnedContours};

// Menus retain the existing action nodes and close after activation or Escape.
document.addEventListener("click", (event) => {
  const menu = event.target.closest(".menu");
  if (menu && event.target.closest("button, a")) {
    for (let current = menu; current; current = current.parentElement?.closest(".menu")) if (!current.classList.contains("header-menu-root") || innerWidth <= 1100) current.open = false;
  } else if (!menu) document.querySelectorAll(".menu[open]").forEach((item) => { if (!item.classList.contains("header-menu-root") || innerWidth <= 1100) item.open = false; });
});
document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape") return;
  const menu = event.target.closest(".menu[open]");
  if (menu) { event.preventDefault(); menu.open = false; menu.querySelector("summary")?.focus(); }
});
const captureHealth = $("capture-health");
function updateCaptureSummary() {
  const value = captureHealth.textContent || "";
  const unverified = value.includes("Unverified");
  $("capture-summary").textContent = unverified ? "Unverified" : "Evidence";
  $("capture-heading").hidden = !unverified;
}
new MutationObserver(updateCaptureSummary).observe(captureHealth, {childList: true, characterData: true, subtree: true});
updateCaptureSummary();

function updateConnectionLabel() {
  $("connection").title = liveConnection;
  $("connection").textContent = innerWidth <= 600
    ? (liveConnection.includes("unavailable") ? "● Unavailable" : liveConnection.includes("Connected") ? "● Connected" : "Connecting…")
    : liveConnection;
}
window.addEventListener("resize", updateConnectionLabel);
const headerMenuRoot = document.querySelector(".header-menu-root");
let compactLayout;
function updateCompactLayout() {
  const compact = innerWidth <= 1100;
  if (compact === compactLayout) return;
  compactLayout = compact;
  headerMenuRoot.open = !compact;
  if (compact) {
    const main = document.querySelector("main");
    main.classList.add("details-closed");
    $("toggle-parties").setAttribute("aria-expanded", String(!main.classList.contains("parties-closed")));
    $("close-details").setAttribute("aria-expanded", "false");
  }
}
window.addEventListener("resize", updateCompactLayout);
updateCompactLayout();
// Keep less frequent playback actions available without expanding the timeline.
const playbackMore = document.createElement("details");
playbackMore.className = "menu playback-more";
const playbackMoreSummary = document.createElement("summary");
playbackMoreSummary.textContent = "More";
const playbackMoreContent = document.createElement("div");
playbackMoreContent.className = "menu-content";
for (const id of ["playback-first", "playback-last", "playback-facts", "playback-start"]) playbackMoreContent.append($(id));
playbackMore.append(playbackMoreSummary, playbackMoreContent);
$("playback-timeline").append(playbackMore);
// Restore focus for non-modal narrow drawers, including historical inspection.
document.addEventListener("click", event => {
  if (innerWidth > 1100) return;
  const id = event.target.closest("button")?.id;
  if (!["toggle-parties", "collapse-parties", "toggle-details", "close-details"].includes(id)) return;
  queueMicrotask(() => {
    const left = id.includes("parties"), main = document.querySelector("main");
    const closed = main.classList.contains(left ? "parties-closed" : "details-closed");
    if (closed) $(left ? "toggle-parties" : "tracks").focus();
    else $(left ? "collapse-parties" : "close-details").focus();
  });
}, true);
document.addEventListener("keydown", event => {
  if (event.key !== "Escape" || innerWidth > 1100 || event.target.closest("dialog, .menu")) return;
  const aside = event.target.closest(".party-overview, .party-detail");
  if (!aside) return;
  const left = aside.classList.contains("party-overview");
  document.querySelector("main").classList.add(left ? "parties-closed" : "details-closed");
  const toggle = $(left ? "toggle-parties" : "tracks");
  toggle.setAttribute("aria-expanded", "false");
  toggle.focus();
  event.preventDefault();
});
document.addEventListener("keydown", event => {
  if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
  const menu = event.target.closest(".menu");
  if (!menu || !menu.open) return;
  const container = menu.querySelector(":scope > .menu-content, :scope > .menu-groups");
  if (!container) return;
  const items = [...container.querySelectorAll(":scope > button, :scope > a, :scope > details > summary")].filter(item => !item.disabled);
  if (!items.length) return;
  const current = items.indexOf(document.activeElement);
  const next = event.key === "ArrowDown" ? (current + 1) % items.length : (current < 0 ? items.length - 1 : (current + items.length - 1) % items.length);
  items[next].focus();
  event.preventDefault();
});
// The recording controls belong to the live map cell so side panels never
// consume the dock's width. Existing nodes and handlers are retained.
document.querySelector(".canvas").append(document.querySelector(".recording-bar"));
for (const group of document.querySelectorAll(".menu-groups > .menu")) {
  group.addEventListener("toggle", () => {
    if (group.open) for (const sibling of group.parentElement.children) if (sibling !== group) sibling.open = false;
  });
}
// A menu item may open a dialog after its menu closes. Return focus to the
// visible menu trigger when that dialog is dismissed.
document.addEventListener("click", event => {
  const button = event.target.closest(".menu button");
  if (!button) return;
  const menu = button.closest(".menu");
  const root = button.closest(".header-menu-root");
  const trigger = root && innerWidth <= 1100 ? root.querySelector(":scope > summary") : menu.querySelector(":scope > summary");
  const previouslyOpen = new Set(document.querySelectorAll("dialog[open]"));
  queueMicrotask(() => {
    for (const dialog of document.querySelectorAll("dialog[open]")) {
      if (!previouslyOpen.has(dialog)) dialog.addEventListener("close", () => trigger?.focus(), {once:true});
    }
  });
}, true);
const mapToolbar = document.querySelector('.canvas-toolbar');
new ResizeObserver(() => {
  const rect = mapToolbar.getBoundingClientRect();
  document.documentElement.style.setProperty('--command-tools-bottom', `${rect.bottom + 10}px`);
  document.querySelector('.canvas').style.setProperty('--command-tools-height', `${rect.height + 24}px`);
}).observe(mapToolbar);
