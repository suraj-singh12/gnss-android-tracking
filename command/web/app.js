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
        "Clear this Recording and its tracks, distances and windows? Raw observations, devices and reporting settings are retained.",
      )
    )
      return;
    post("recording", { action, confirmed: action === "clear" }).catch(
      showError,
    );
  };
$("dots").onchange = () => {
  if (!$("dots").checkValidity()) {
    $("dots").reportValidity();
    return;
  }
  poll();
};
function render() {
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
        el("div", distance(d.total_m), "distance"),
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
  const p = {};
  for (const [k, v] of new FormData(e.target)) p[k] = Number(v);
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
    points = state.points.filter((p) => !hidden.has(p.device_id));
  root.replaceChildren();
  $("empty").hidden = state.points.length > 0;
  if (!points.length) return;
  const w = root.clientWidth,
    h = root.clientHeight;
  let minX = Infinity,
    maxX = -Infinity,
    minY = Infinity,
    maxY = -Infinity;
  for (const p of points) {
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
        "stroke-width": 2.5,
        "stroke-dasharray": dash(first.device_id),
      }),
    );
    for (const p of group) {
      if (!p.dot) continue;
      const [x, y] = xy(p),
        label = state.devices[p.device_id].snapshot.party.id,
        detail = `${label} · ${time(p.fix.observed_at)} · ${distance(p.cumulative_m)} travelled · ±${p.fix.horizontal_accuracy_m} m`,
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
