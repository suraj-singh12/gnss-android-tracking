"use strict";
const $ = (id) => document.getElementById(id),
  hidden = new Set(),
  svgNS = "http://www.w3.org/2000/svg";
let state, policyRevision, selectedParty;
let viewport, offlineMap, inspectedPoint;
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
let sounding = false;
let lastSound = 0;
let showSOSEventHistory = false;
async function sosSound(test = false) {
  if (sounding) return;
  if (!test && !state?.sos_alerts?.some((a) => !a.operator_acknowledged_at))
    return;
  if (!test && Date.now() - lastSound < 10000) return;
  sounding = true;
  try {
    const Audio = window.AudioContext || window.webkitAudioContext;
    if (!Audio) throw Error("Audio unavailable");
    if (!audio && !test) throw Error("Audio requires operator activation");
    audio ||= new Audio();
    if (test) await audio.resume();
    if (audio.state !== "running") throw Error("Audio blocked or suspended");
    const tone = audio.createOscillator(),
      gain = audio.createGain();
    tone.frequency.value = 880;
    gain.gain.value = 0.12;
    tone.connect(gain);
    gain.connect(audio.destination);
    tone.start();
    tone.stop(audio.currentTime + 0.5);
    lastSound = Date.now();
    $("sos-audio").textContent = "SOS sound enabled · Test";
    $("sos-audio-status").textContent =
      "Audible alert enabled; repeats every 10 s while SOS is unacknowledged. Check speaker volume.";
  } catch (e) {
    $("sos-audio").textContent = "Enable SOS sound · currently blocked";
    $("sos-audio-status").textContent =
      "Audible alert blocked / unavailable. Enable audio and check speaker volume; visible SOS remains active.";
  } finally {
    sounding = false;
  }
}
$("sos-audio").onclick = () => sosSound(true);
$("sos-history-toggle").onclick = () => {
  showSOSEventHistory = !showSOSEventHistory;
  renderSOS();
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
  $("sos-panel").hidden = alerts.length === 0;
  $("sos-panel").classList.toggle("quiet-events", pending === 0);
  $("sos-heading").textContent = pending
    ? "SOS emergencies"
    : "SOS event history";
  $("sos-history-toggle").hidden = pending > 0;
  $("sos-history-toggle").textContent = showSOSEventHistory
    ? "Hide event history"
    : "Show event history";
  $("sos-alerts").hidden = pending === 0 && !showSOSEventHistory;
  document.body.classList.toggle("has-events", alerts.length > 0);
  document.body.classList.toggle("has-sos", pending > 0);
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
      card.append(details, button);
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
  if (focused && document.activeElement !== focused) focused.focus();
  sosSound();
}
function render() {
  renderSOS();
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
  $("no-parties").hidden = devices.length > 0;
  selectedParty ||= devices[0]?.device_id;
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
        selectedParty = d.device_id;
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
$("map-mode").onchange = () => {
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
    const parsed = GeoMap.parse(await file.text(), file.name);
    offlineMap = parsed;
    $("map-mode").value = "offline";
    $("map-warning").textContent = "";
    $("map-metadata").textContent =
      `Source: ${parsed.source} · CRS: WGS84 / EPSG:4326 · Display: EPSG:3857 · Coverage: ${parsed.bounds.map((n) => n.toFixed(5)).join(", ")} · ${parsed.count} coordinates`;
    viewport = undefined;
    draw();
  } catch (e) {
    offlineMap = undefined;
    $("map-mode").value = "blank";
    $("map-metadata").textContent = "No valid offline map loaded.";
    $("map-warning").textContent =
      "Invalid map: " + e.message + " Blank canvas retained.";
    draw();
  }
};
$("fit").onclick = () => {
  viewport = undefined;
  draw();
};
$("focus-party").onclick = () => {
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
  if (e.target.tagName !== "svg" || !viewport) return;
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
            fill: "#d7e2d6",
            stroke: "#91a399",
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
            stroke: "#81958c",
            "stroke-width": 2,
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
    const marker = svg("circle", {
      cx: x,
      cy: y,
      r: p.live ? 8 : 6,
      fill: p.live ? color(p.device_id) : "#fff",
      stroke: color(p.device_id),
      "stroke-width": 3,
      tabindex: 0,
      role: "button",
      "data-point-key": p.device_id + "/live",
      "data-live-device": p.live ? p.device_id : "",
      "aria-label": `${d.snapshot.party.id} ${gnssLabel(d)}`,
    });
    marker.onclick = () => {
      selectedParty = p.device_id;
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
      x: x + 13,
      y: y - 12,
      fill: color(p.device_id),
      "font-size": 12,
    });
    label.textContent = d.snapshot.party.id + (p.live ? "" : " · last known");
    liveLayer.append(label);
  }
  $("scale-label").textContent = "WGS84 / Web Mercator · north up";
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
