"use strict";
const $ = (id) => document.getElementById(id),
  hidden = new Set(),
  svgNS = "http://www.w3.org/2000/svg";
let state, policyRevision;
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
    $("sos-audio-status").textContent =
      "Audible alert enabled; repeats every 10 s while SOS is unacknowledged. Check speaker volume.";
  } catch (e) {
    $("sos-audio-status").textContent =
      "Audible alert blocked / unavailable. Enable audio and check speaker volume; visible SOS remains active.";
  } finally {
    sounding = false;
  }
}
$("sos-audio").onclick = () => sosSound(true);
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
  const recording = state.recording;
  $("recording").textContent = recording
    ? recording.active
      ? "Recording active"
      : "Recording stopped"
    : "No recording";
  $("start").disabled = !!recording;
  $("stop").disabled = !recording?.active;
  $("resume").disabled = !recording || recording.active;
  $("clear").disabled = !recording;
  const devices = Object.values(state.devices).sort((a, b) =>
    a.device_id.localeCompare(b.device_id),
  );
  $("count").textContent = devices.length;
  // Preserve focused edits while polling; all content is created as text nodes.
  if (!$("devices").contains(document.activeElement)) {
    $("devices").replaceChildren();
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
        el("div", "Qualified distance: " + distance(d.total_m), "distance"),
      );
      const labels = {
        healthy: "Contact healthy",
        delayed: "Contact delayed",
        contact_lost: "Contact lost",
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
      $("devices").append(card);
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
  post("quality", p).catch(showError);
};
function svg(tag, attrs) {
  const e = document.createElementNS(svgNS, tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  return e;
}
function draw() {
  if (!state) return;
  const root = $("tracks"),
    points = (
      $("all-observations")?.checked
        ? (state.raw_points ?? [])
        : [...state.points, ...(state.provisional_points ?? [])]
    ).filter((p) => !hidden.has(p.device_id)),
    live = Object.values(state.devices)
      .filter((d) => d.current_position && !hidden.has(d.device_id))
      .map((d) => ({
        device_id: d.device_id,
        x_m: d.live_x_m,
        y_m: d.live_y_m,
        fix: d.location.fix,
      }));
  root.replaceChildren();
  $("empty").hidden = points.length > 0 || live.length > 0;
  if (!points.length && !live.length) return;
  const w = root.clientWidth,
    h = root.clientHeight;
  let minX = Infinity,
    maxX = -Infinity,
    minY = Infinity,
    maxY = -Infinity;
  for (const p of [...points, ...live]) {
    minX = Math.min(minX, p.x_m);
    maxX = Math.max(maxX, p.x_m);
    minY = Math.min(minY, p.y_m);
    maxY = Math.max(maxY, p.y_m);
  }
  const scale = Math.max(
      0.00001,
      Math.min(
        Math.max(1, w - 112) / Math.max(20, maxX - minX),
        Math.max(1, h - 168) / Math.max(20, maxY - minY),
      ),
    ),
    cx = (minX + maxX) / 2,
    cy = (minY + maxY) / 2,
    xy = (p) => [(p.x_m - cx) * scale + w / 2, h / 2 - (p.y_m - cy) * scale];
  root.setAttribute("viewBox", `0 0 ${w} ${h}`);
  // Future offline map layers can precede this geographic overlay.
  const overlay = svg("g", { "data-layer": "tracks" });
  root.append(overlay);
  const segments = new Map();
  for (const p of points) {
    if (!segments.has(p.segment_id)) segments.set(p.segment_id, []);
    segments.get(p.segment_id).push(p);
  }
  for (const group of segments.values()) {
    const first = group[0],
      stroke = color(first.device_id);
    overlay.append(
      svg("polyline", {
        points: group.map((p) => xy(p).join(",")).join(" "),
        fill: "none",
        stroke,
        "stroke-width": 1.5,
        opacity: 0.5,
        "stroke-dasharray": dash(first.device_id),
      }),
    );
    for (const p of group) {
      if (!p.dot) continue;
      const [x, y] = xy(p),
        label = state.devices[p.device_id].snapshot.party.id,
        detail = `${label} · ${time(p.fix.observed_at)} · ${p.segment_reason === "unfiltered_diagnostic" ? "Unfiltered / Diagnostic" : p.segment_reason === "provisional" ? distance(p.cumulative_m) + " section (provisional)" : distance(p.cumulative_m) + " travelled"} · accuracy ${p.fix.horizontal_accuracy_m ?? "unavailable"} m`,
        circle = svg("circle", {
          cx: x,
          cy: y,
          r: 4.5,
          fill: "white",
          stroke,
          "stroke-width": 2,
          tabindex: 0,
          "aria-label": detail,
        }),
        title = svg("title", {});
      title.textContent = detail;
      circle.append(title);
      circle.onmouseenter = circle.onfocus = () => {
        $("hover").textContent = detail;
      };
      circle.onmouseleave = circle.onblur = () => {
        $("hover").textContent =
          "Hover or focus a point for travelled distance, time and accuracy.";
      };
      overlay.append(circle);
    }
    const last = group[group.length - 1],
      [x, y] = xy(last),
      label = svg("text", {
        x: x + 10,
        y: y - 10,
        fill: stroke,
        "font-size": 12,
      });
    label.textContent = state.devices[last.device_id].snapshot.party.id;
    overlay.append(label);
  }
  const liveLayer = svg("g", { "data-layer": "live" });
  root.append(liveLayer);
  for (const p of live) {
    const [x, y] = xy(p),
      d = state.devices[p.device_id];
    const marker = svg("circle", {
      cx: x,
      cy: y,
      r: 7,
      fill: color(p.device_id),
      stroke: "white",
      "stroke-width": 2,
      tabindex: 0,
      "data-live-device": p.device_id,
      "aria-label": `${d.snapshot.party.id} current GNSS · ${time(p.fix.observed_at)}`,
    });
    const title = svg("title", {});
    title.textContent = `${d.snapshot.party.id} current GNSS · ${time(p.fix.observed_at)} · ±${p.fix.horizontal_accuracy_m ?? "unknown"} m`;
    marker.append(title);
    liveLayer.append(marker);
  }
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
    showError(e);
  } finally {
    polling = false;
  }
}
new ResizeObserver(draw).observe($("tracks"));
poll();
setInterval(poll, 2000);
