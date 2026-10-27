"use strict";
(() => {
  const $ = (id) => document.getElementById(id),
    ns = "http://www.w3.org/2000/svg";
  let active = false,
    session,
    frame,
    position = 0,
    playing = false,
    speed = 1,
    lastTick = 0,
    busy = false,
    revision = 0;
  let follow = true,
    angle = 0,
    selected,
    filters = new Map(),
    epochs = {},
    transform = [0, 0, 1],
    layers = {},
    savedLayout,
    savedAppearance;
  const api = async (url, signal) => {
    const r = await fetch(url, { signal });
    const data = await r.json();
    if (!r.ok) throw Error(data.message || "Recording unavailable");
    return data;
  };
  const label = (tag, value, cls) => {
    const e = document.createElement(tag);
    e.textContent = value;
    if (cls) e.className = cls;
    return e;
  };
  const clock = (s) => {
    s = Math.max(0, Math.floor(s));
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
  };
  function interruptionDuration(seconds) {
    const hours = Math.floor(seconds / 3600),
      minutes = Math.floor((seconds % 3600) / 60),
      secs = Number((seconds % 60).toFixed(1));
    return `${hours ? hours + "h " : ""}${minutes ? minutes + "m " : ""}${secs}s`;
  }
  function locationAt(t) {
    let wall = Date.parse(session.metadata.started_at),
      elapsed = 0;
    for (const g of session.groups) {
      if (t < g.Offset) return { wall: wall + (t - elapsed) * 1000 };
      if (t < g.Offset + 4)
        return { wall: Math.max(wall, Date.parse(g.From) - 1), notice: g };
      wall = Math.max(wall, Date.parse(g.SkipUntil));
      elapsed = g.Offset + 4;
    }
    return {
      wall: Math.min(
        Date.parse(session.metadata.cutoff_at),
        wall + (t - elapsed) * 1000,
      ),
    };
  }
  function inspection(title, content) {
    $("historical-title").textContent = title;
    $("historical-content").replaceChildren(
      typeof content === "string" ? label("pre", content) : content,
    );
    if (!$("historical-inspection").open)
      $("historical-inspection").showModal();
  }
  // SVG evidence is passive geometry only. Never execute stored DOM handlers or
  // resolve an external URL from a recording. Pinned data images are permitted.
  function geometry(source) {
    const doc = new DOMParser().parseFromString(
      `<svg xmlns="${ns}">${source || ""}</svg>`,
      "image/svg+xml",
    );
    const group = document.createElementNS(ns, "g");
    const allowed = new Set([
      "svg",
      "g",
      "defs",
      "path",
      "circle",
      "ellipse",
      "rect",
      "line",
      "polyline",
      "polygon",
      "text",
      "tspan",
      "image",
      "clippath",
      "lineargradient",
      "radialgradient",
      "stop",
      "pattern",
      "mask",
    ]);
    for (const e of [...doc.querySelectorAll("*")]) {
      if (!allowed.has(e.localName.toLowerCase())) {
        e.remove();
        continue;
      }
      for (const a of [...e.attributes]) {
        const v = a.value.trim();
        if (
          a.name.toLowerCase().startsWith("on") ||
          a.name === "style" ||
          (a.name === "style" && /[<>]|url\(\s*[^#]/i.test(v)) ||
          (/href$/i.test(a.name) &&
            !v.startsWith("#") &&
            !/^data:image\/(png|jpeg|webp);base64,/i.test(v)) ||
          (/url\(/i.test(v) && !/url\(\s*#[\w-]+\s*\)/.test(v))
        )
          e.removeAttribute(a.name);
      }
    }
    for (const e of [...doc.documentElement.children])
      group.append(document.importNode(e, true));
    return group;
  }
  function identity(p, id) {
    const party = p.device?.snapshot?.party || p.identity;
    return party?.id || party?.name || id;
  }
  function permitted(id) {
    return (
      frame?.checkpoint?.parties?.[id]?.visible === true &&
      filters.get(id) !== false
    );
  }
  function detail() {
    const root = $("selected-detail");
    root.replaceChildren();
    const p = frame?.checkpoint?.parties?.[selected];
    root.append(label("p", "Historical · READ ONLY", "historical-party"));
    if (!p?.visible || !permitted(selected)) {
      root.append(label("p", "Select a permitted historical party."));
      return;
    }
    root.append(
      label("h2", identity(p, selected)),
      label(
        "p",
        `Displayed at: ${new Date(frame.presentation_at).toLocaleString(undefined, { timeZoneName: "short" })}`,
      ),
    );
    const d = p.device || {},
      fix = d.location?.fix || d.snapshot?.fix || {};
    const facts = [
      [
        "Original fix",
        fix.observed_at
          ? new Date(fix.observed_at).toLocaleString(undefined, {
              timeZoneName: "short",
            })
          : "Unavailable",
      ],
      [
        "Qualified distance",
        d.total_m == null
          ? "Unavailable"
          : d.total_m >= 1000
            ? `${(d.total_m / 1000).toFixed(2)} km`
            : `${d.total_m.toFixed(1)} m`,
      ],
      [
        "Accuracy at time",
        fix.horizontal_accuracy_m == null
          ? "Unavailable"
          : `± ${fix.horizontal_accuracy_m} m`,
      ],
      [
        "GNSS quality",
        (d.gnss_condition || "Unavailable").replaceAll("_", " "),
      ],
      [
        "Command contact",
        (d.contact_condition || "Unavailable").replaceAll("_", " "),
      ],
      [
        "Battery",
        d.snapshot?.health?.battery_percent == null
          ? "Unavailable"
          : `${d.snapshot.health.battery_percent}%`,
      ],
    ];
    const dl = label("dl", "", "historical-values");
    for (const [key, value] of facts)
      dl.append(label("dt", key), label("dd", value));
    root.append(dl);
    if (p.panel) {
      const extra = document.createElement("details");
      extra.append(
        label("summary", "Original displayed panel"),
        label("pre", p.panel, "historical-detail"),
      );
      root.append(extra);
    }
    const quality = label("button", "Quality at this time ›");
    quality.onclick = () => showQuality();
    root.append(quality);
    root.append(
      label(
        "p",
        `Included parties ${session.metadata.included.length} of ${session.metadata.total_parties}`,
      ),
      label(
        "small",
        "Evidence gaps remain marked. View preferences do not modify the recording.",
      ),
    );
  }
  function showQuality() {
    const policy = frame?.checkpoint?.common.policy || {},
      dl = label("dl", "", "historical-values");
    for (const [key, value] of Object.entries(policy))
      dl.append(
        label("dt", key.replaceAll("_", " ")),
        label(
          "dd",
          typeof value === "object" ? JSON.stringify(value) : String(value),
        ),
      );
    inspection("Historical quality · READ ONLY", dl);
  }
  function responsivePanels() {
    const main = document.querySelector("main");
    if (
      innerWidth <= 700 &&
      !main.classList.contains("parties-closed") &&
      !main.classList.contains("details-closed")
    )
      main.classList.add("parties-closed");
    $("toggle-parties").setAttribute(
      "aria-expanded",
      String(!main.classList.contains("parties-closed")),
    );
    $("toggle-details").setAttribute(
      "aria-expanded",
      String(!main.classList.contains("details-closed")),
    );
  }
  function repaint() {
    if (!active) return;
    responsivePanels();
    const root = $("tracks");
    root.replaceChildren();
    const checkpoint = frame?.checkpoint;
    $("no-parties").hidden = true;
    $("devices").replaceChildren();
    $("count").textContent = checkpoint
      ? `${Object.keys(checkpoint.parties).filter((id) => permitted(id)).length} / ${Object.keys(checkpoint.parties).length}`
      : "0";
    if (!checkpoint) {
      $("playback-sos").hidden = true;
      $("playback-status").textContent =
        "Unverified presentation evidence · no recorded rendered state yet";
      detail();
      return;
    }
    const c = checkpoint.common || {},
      basis = follow ? c : anchor || c,
      w = basis.width || 1280,
      h = basis.height || 720;
    root.setAttribute("viewBox", `0 0 ${w} ${h}`);
    root.setAttribute("preserveAspectRatio", "xMidYMid meet");
    const world = document.createElementNS(ns, "g");
    world.id = "historical-world";
    world.setAttribute(
      "transform",
      `translate(${transform[0]} ${transform[1]}) scale(${transform[2]}) rotate(${angle} ${w / 2} ${h / 2})`,
    );
    const evidence = document.createElementNS(ns, "g");
    if (!follow && basis.viewport && c.viewport) {
      const a = basis.viewport,
        v = c.viewport,
        z = a.scale / v.scale;
      const dx = (v.cx - a.cx) * a.scale + w / 2 - ((c.width || w) / 2) * z,
        dy = (a.cy - v.cy) * a.scale + h / 2 - ((c.height || h) / 2) * z;
      evidence.setAttribute("transform", `translate(${dx} ${dy}) scale(${z})`);
    }
    const map = geometry(follow ? c.map_svg : inspectorMap || c.map_svg);
    for (const child of map.querySelectorAll("[data-layer]"))
      if (layers[child.dataset.layer] === false) child.remove();
    world.append(map);
    for (const [id, p] of Object.entries(checkpoint.parties)) {
      const card = label(
          "section",
          "",
          "historical-party" + (!p.visible ? " locked" : ""),
        ),
        row = label("label", identity(p, id));
      const check = document.createElement("input");
      check.type = "checkbox";
      check.checked = permitted(id);
      check.disabled = !p.visible || follow;
      check.setAttribute("aria-label", `Show ${identity(p, id)}`);
      check.onchange = () => {
        filters.set(id, check.checked);
        repaint();
      };
      row.append(check);
      if (id === selected) card.classList.add("historical-selected");
      if (p.device?.track_color)
        card.style.borderLeftColor = p.device.track_color;
      card.append(
        row,
        label(
          "small",
          p.visible
            ? "Historical · read only"
            : "Unselected · locked by original visibility",
        ),
      );
      const choose = label("button", identity(p, id), "historical-party-name");
      choose.setAttribute("aria-label", `Details for ${identity(p, id)}`);
      choose.disabled = !p.visible;
      choose.onclick = () => {
        selected = id;
        document.querySelector("main").classList.remove("details-closed");
        for (const other of $("devices").querySelectorAll(
          ".historical-selected",
        ))
          other.classList.remove("historical-selected");
        card.classList.add("historical-selected");
        responsivePanels();
        detail();
      };
      row.firstChild?.remove();
      row.prepend(choose);
      if (p.device?.snapshot?.party?.name)
        card.append(label("small", p.device.snapshot.party.name));
      if (p.visible)
        card.append(
          label(
            "small",
            `Historic position · ${new Date(frame.presentation_at).toLocaleTimeString()}`,
          ),
        );
      $("devices").append(card);
      if (permitted(id)) {
        const g = geometry(p.svg);
        g.dataset.historicalParty = id;
        evidence.append(g);
      }
    }
    world.append(evidence);
    root.append(world);
    const excluded = Object.entries(session.metadata.participation || {})
      .filter(([id]) => !session.metadata.included.includes(id))
      .map(([id, p]) => ({ ...p, device_id: id }));
    // The frozen full manifest supplies permitted excluded identities only.
    if (excluded.length) {
      $("devices").append(label("p", "Excluded · metadata only"));
      for (const p of excluded)
        $("devices").append(
          label(
            "div",
            p.party?.id || p.device_id || p.device || "Excluded party",
            "historical-party locked",
          ),
        );
    }
    const alerts = Object.entries(checkpoint.parties).flatMap(([id, p]) =>
      permitted(id) ? p.sos || [] : [],
    );
    $("playback-sos").hidden = !alerts.length;
    $("playback-sos").textContent = alerts.length
      ? `● Recording SOS · ${alerts.length} historical notification${alerts.length > 1 ? "s" : ""}`
      : "";
    $("map-mode-label").textContent = checkpoint.map_id
      ? "Offline geographic map · recorded revision"
      : "Blank canvas";
    const trailing = session.metadata.trailing_unverified_interval;
    const limitation =
      (trailing &&
        Date.parse(trailing.from) <= locationAt(position).wall &&
        Date.parse(trailing.until) >= locationAt(position).wall) ||
      (session.facts || []).some(
        (f) =>
          f.Kind === "unverified" &&
          Date.parse(f.Data?.from) <= locationAt(position).wall &&
          Date.parse(f.Data?.until) >= locationAt(position).wall,
      );
    $("playback-status").textContent =
      (follow
        ? "Frozen display · original recorded viewport"
        : "INSPECTION VIEW — MODIFIED DURING PLAYBACK") +
      " · " +
      (limitation ? "UNVERIFIED PRESENTATION INTERVAL" : "read-only evidence") +
      (checkpoint.asset_warnings?.length
        ? " · " + checkpoint.asset_warnings.join(" ")
        : "");
    detail();
  }
  let inspectorMap, anchor;
  let controller;
  async function seek(t, explicit = true) {
    if (!session) return;
    position = Math.max(0, Math.min(session.duration, t));
    const target = locationAt(position);
    $("playback-seek").value = position;
    $("playback-elapsed").textContent =
      `${clock(position)} / ${clock(session.duration)}`;
    $("presentation-time").textContent = new Date(target.wall).toLocaleString(
      undefined,
      { timeZoneName: "short" },
    );
    $("playback-notice").hidden = !target.notice;
    if (target.notice) {
      $("playback-notice").replaceChildren(
        label(
          "strong",
          `${target.notice.Kind} · ${interruptionDuration(target.notice.Duration)}`,
        ),
        label("small", "4-second playback notice · no invented frames"),
      );
    }
    if (
      !explicit &&
      frame?.checkpoint &&
      (!frame.next_at?.Valid || target.wall < frame.next_at.Float64) &&
      target.wall >= Date.parse(frame.presentation_at)
    )
      return;
    if (explicit) {
      frame = undefined;
      repaint();
      $("playback-status").textContent = "Loading saved presentation…";
    }
    const token = ++revision;
    controller?.abort();
    controller = new AbortController();
    busy = true;
    try {
      const next = await api(
        `/local/presentation/playback?id=${encodeURIComponent(session.metadata.id)}&at=${Math.floor(target.wall)}`,
        controller.signal,
      );
      if (token !== revision || !active) return;
      if (explicit) {
        filters.clear();
        epochs = {};
        transform = [0, 0, 1];
        angle = 0;
      }
      for (const [id, epoch] of Object.entries(next.visibility_epochs || {})) {
        if (epochs[id] !== epoch) filters.delete(id);
      }
      epochs = next.visibility_epochs || {};
      const previous = frame;
      frame = next;
      if (!follow && (explicit || !anchor)) {
        anchor = next.checkpoint?.common;
        inspectorMap = anchor?.map_svg;
      }
      if (follow && next.checkpoint) {
        selected = next.checkpoint.common.selected_party;
        transform = [0, 0, 1];
        angle = 0;
        layers = {};
        const panels = next.checkpoint.common.panels;
        if (
          panels &&
          (explicit ||
            JSON.stringify(panels) !==
              JSON.stringify(previous?.checkpoint?.common?.panels))
        ) {
          const main = document.querySelector("main");
          main.classList.toggle("parties-closed", !panels.parties);
          main.classList.toggle("details-closed", !panels.details);
        }
      }
      if (!next.checkpoint?.parties?.[selected]?.visible) selected = undefined;
      repaint();
    } catch (e) {
      if (e.name !== "AbortError") {
        playing = false;
        $("playback-status").textContent =
          "Playback unavailable · " + e.message;
      }
    } finally {
      if (token === revision) busy = false;
    }
  }
  async function open(metadata) {
    const data = await api(
      `/local/presentation/playback?id=${encodeURIComponent(metadata.id)}`,
    );
    session = data;
    session.metadata = { ...data.metadata, name: metadata.name };
    if (!active) {
      savedLayout = document.querySelector("main").className;
      savedAppearance = document.documentElement.dataset.appearance;
    }
    active = true;
    $("snapshot-receipt").hidden = true;
    document.body.classList.add("playback");
    $("playback-rail").hidden =
      $("playback-timeline").hidden =
      $("playback-status").hidden =
        false;
    $("playback-name").textContent = metadata.name;
    $("playback-start").textContent =
      `Actual Start ${new Date(data.metadata.started_at).toLocaleString(undefined, { timeZoneName: "short" })}`;
    $("playback-seek").max = data.duration;
    follow = true;
    $("follow-original").checked = true;
    playing = false;
    frame = undefined;
    speed = 1;
    $("playback-speed").value = "1";
    await seek(0);
  }
  function exit() {
    playing = false;
    active = false;
    revision++;
    controller?.abort();
    document.body.classList.remove("playback");
    for (const id of [
      "playback-rail",
      "playback-timeline",
      "playback-status",
      "playback-notice",
      "playback-sos",
    ])
      $(id).hidden = true;
    $("tracks").removeAttribute("viewBox");
    document.querySelector("main").className = savedLayout;
    frame = undefined;
    session = undefined;
    window.dispatchEvent(new Event("resize"));
    window.restoreLiveDashboard();
  }
  $("exit-playback").onclick = exit;
  $("playback-seek").oninput = () => {
    playing = false;
    seek(Number($("playback-seek").value));
  };
  $("playback-first").onclick = () => seek(0);
  $("playback-last").onclick = () => {
    playing = false;
    seek(session.duration);
  };
  $("playback-play").onclick = () => {
    if (position >= session.duration) seek(0);
    playing = !playing;
    lastTick = performance.now();
    $("playback-play").textContent = playing ? "Ⅱ" : "▷";
    $("playback-play").setAttribute("aria-label", playing ? "Pause" : "Play");
  };
  $("playback-speed").onchange = () =>
    (speed = Number($("playback-speed").value));
  $("follow-original").onchange = () => {
    follow = $("follow-original").checked;
    if (!follow) {
      anchor = frame?.checkpoint?.common;
      inspectorMap = anchor?.map_svg;
      transform = [0, 0, 1];
      angle = 0;
    }
    if (follow) {
      filters.clear();
      layers = {};
      transform = [0, 0, 1];
      angle = 0;
      selected = frame?.checkpoint?.common.selected_party;
      const panels = frame?.checkpoint?.common.panels;
      if (panels) {
        const main = document.querySelector("main");
        main.classList.toggle("parties-closed", !panels.parties);
        main.classList.toggle("details-closed", !panels.details);
      }
    }
    repaint();
  };
  $("playback-facts").onclick = () =>
    inspection(
      "Recording facts · READ ONLY",
      JSON.stringify(
        {
          actual_start: session.metadata.started_at,
          cutoff: session.metadata.cutoff_at,
          interruptions: session.groups,
          underlying_facts: session.facts,
          limitations: session.metadata.limitations,
        },
        null,
        2,
      ),
    );
  async function preparePinnedTerrain(kind) {
    const current = frame,
      token = revision,
      c = anchor || current.checkpoint.common,
      group = document.createElementNS(ns, "g"),
      v = c.viewport;
    if (!v) throw Error("No recorded geographic viewport is available.");
    const xy = (p) => [
      (p[0] - v.cx) * v.scale + c.width / 2,
      c.height / 2 - (p[1] - v.cy) * v.scale,
    ];
    let found = false;
    for (const hash of current.checkpoint.assets || []) {
      const response = await fetch(
        `/local/presentation/saved?id=${encodeURIComponent(session.metadata.id)}&asset=${encodeURIComponent(hash)}`,
      );
      if (!response.ok)
        throw Error(
          "Pinned map/terrain asset unavailable; no current-library substitute is used.",
        );
      const bytes = await response.arrayBuffer();
      if (bytes.byteLength > 32 * 1024 * 1024)
        throw Error(
          "Pinned terrain asset exceeds the interactive inspection memory limit.",
        );
      let data;
      try {
        data = JSON.parse(new TextDecoder().decode(bytes));
      } catch {
        continue;
      }
      if (data.samples && data.bounds && data.rows && data.columns) {
        const prepared = Terrain.prepare(
          data,
          data.contour_interval_m || 10,
          [],
        );
        window.PinnedTerrainRenderer[kind](
          group,
          prepared,
          xy,
          document.documentElement.dataset.appearance === "night",
        );
        found = true;
      }
      if (token !== revision || follow || !active) return;
      await new Promise((r) => setTimeout(r, 0));
    }
    if (!found)
      throw Error(
        "No pinned terrain samples were available to Command in this frame.",
      );
    const original = geometry(inspectorMap || c.map_svg);
    for (const old of original.querySelectorAll(`[data-layer="${kind}"]`))
      old.remove();
    original.append(group);
    inspectorMap = original.innerHTML;
  }
  const controls = new Set([
    "open-quality",
    "open-map",
    "open-layers",
    "inspect-history",
    "focus-party",
    "fit",
    "zoom-in",
    "zoom-out",
    "open-control",
    "toggle-parties",
    "collapse-parties",
    "toggle-details",
    "close-details",
  ]);
  function fit(id) {
    const root = $("tracks"),
      g = id
        ? root.querySelector(`[data-historical-party="${CSS.escape(id)}"]`)
        : null;
    let boxes = g
      ? [g.getBBox()]
      : [...root.querySelectorAll("[data-historical-party]")].map((n) =>
          n.getBBox(),
        );
    boxes = boxes.filter((b) => b.width || b.height);
    if (!boxes.length) return;
    const x = Math.min(...boxes.map((b) => b.x)),
      y = Math.min(...boxes.map((b) => b.y)),
      w = Math.max(...boxes.map((b) => b.x + b.width)) - x,
      h = Math.max(...boxes.map((b) => b.y + b.height)) - y,
      c = frame.checkpoint.common;
    const z =
      Math.min(c.width / Math.max(w, 100), c.height / Math.max(h, 100)) * 0.65;
    transform = [
      c.width / 2 - (x + w / 2) * z,
      c.height / 2 - (y + h / 2) * z,
      z,
    ];
    repaint();
  }
  document.addEventListener(
    "click",
    (event) => {
      if (!active) return;
      const button = event.target.closest("button");
      if (!button || !controls.has(button.id)) return;
      event.stopImmediatePropagation();
      event.preventDefault();
      const id = button.id;
      if (
        [
          "toggle-parties",
          "collapse-parties",
          "toggle-details",
          "close-details",
        ].includes(id)
      ) {
        const main = document.querySelector("main"),
          left = id.includes("parties"),
          key = left ? "parties-closed" : "details-closed";
        if (id === "close-details") main.classList.add(key);
        else main.classList.toggle(key);
        if (innerWidth <= 700 && !main.classList.contains(key))
          main.classList.add(left ? "details-closed" : "parties-closed");
        repaint();
      } else if (id === "open-quality") showQuality();
      else if (id === "inspect-history")
        inspection(
          "Historical party · READ ONLY",
          JSON.stringify(frame?.checkpoint?.parties?.[selected] || {}, null, 2),
        );
      else if (id === "open-control")
        inspection(
          "Historical · READ ONLY",
          "Exit playback to operate live phone controls.",
        );
      else if (id === "open-map" || id === "open-layers") {
        const content = label(
          "div",
          "Pinned original map/terrain geometry. No downloads or live map changes.",
        );
        for (const name of [
          "offline-map",
          "hillshade",
          "contours",
          "coverage",
        ]) {
          const row = label("label", name),
            check = document.createElement("input");
          check.type = "checkbox";
          check.checked =
            layers[name] ??
            !!geometry(
              inspectorMap || frame.checkpoint.common.map_svg,
            ).querySelector(`[data-layer="${name}"]`);
          check.disabled = follow;
          check.onchange = async () => {
            layers[name] = check.checked;
            if (
              check.checked &&
              ["hillshade", "contours"].includes(name) &&
              !geometry(
                inspectorMap || frame.checkpoint.common.map_svg,
              ).querySelector(`[data-layer="${name}"]`)
            ) {
              check.disabled = true;
              try {
                await preparePinnedTerrain(name);
              } catch (e) {
                inspection("Historical map limitation", e.message);
              } finally {
                check.disabled = follow;
              }
            }
            repaint();
          };
          row.append(check);
          content.append(row);
        }
        const rotation = label("label", "Rotation");
        const slider = document.createElement("input");
        slider.type = "range";
        slider.min = "0";
        slider.max = "360";
        slider.value = angle;
        slider.disabled = follow;
        slider.oninput = () => {
          angle = Number(slider.value);
          repaint();
        };
        rotation.append(slider);
        content.append(rotation);
        inspection("Historical layers · READ ONLY", content);
      } else if (!follow) {
        if (id === "fit") fit();
        else if (id === "focus-party") fit(selected);
        else {
          transform[2] *= id === "zoom-in" ? 1.25 : 0.8;
          repaint();
        }
      }
    },
    true,
  );
  let drag;
  const root = $("tracks");
  root.addEventListener(
    "pointerdown",
    (e) => {
      if (!active) return;
      e.stopImmediatePropagation();
      if (!follow) {
        drag = [e.clientX, e.clientY, ...transform];
        root.setPointerCapture(e.pointerId);
      }
    },
    true,
  );
  root.addEventListener(
    "pointermove",
    (e) => {
      if (!active) return;
      e.stopImmediatePropagation();
      if (drag && !follow) {
        const scale =
          (frame?.checkpoint?.common.width || root.clientWidth) /
          root.clientWidth;
        transform = [
          drag[2] + (e.clientX - drag[0]) * scale,
          drag[3] + (e.clientY - drag[1]) * scale,
          drag[4],
        ];
        repaint();
      }
    },
    true,
  );
  root.addEventListener(
    "pointerup",
    (e) => {
      if (active) {
        e.stopImmediatePropagation();
        drag = undefined;
      }
    },
    true,
  );
  root.addEventListener(
    "wheel",
    (e) => {
      if (!active) return;
      e.stopImmediatePropagation();
      e.preventDefault();
      if (!follow) {
        transform[2] *= e.deltaY < 0 ? 1.1 : 1 / 1.1;
        repaint();
      }
    },
    { capture: true, passive: false },
  );
  root.addEventListener(
    "keydown",
    (e) => {
      if (!active) return;
      e.stopImmediatePropagation();
      if (!follow) {
        if (e.key === "ArrowLeft") transform[0] += 30;
        if (e.key === "ArrowRight") transform[0] -= 30;
        if (e.key === "ArrowUp") transform[1] += 30;
        if (e.key === "ArrowDown") transform[1] -= 30;
        if (e.key === "+") transform[2] *= 1.1;
        if (e.key === "-") transform[2] /= 1.1;
        repaint();
      }
    },
    true,
  );
  window.addEventListener("resize", () => {
    if (active) repaint();
  });
  function tick(now) {
    if (active) {
      const icon = playing ? "Ⅱ" : "▷";
      if ($("playback-play").textContent !== icon)
        $("playback-play").textContent = icon;
      $("playback-play").setAttribute("aria-label", playing ? "Pause" : "Play");
    }

    if (playing && active && !busy) {
      const delta = Math.min((now - lastTick) / 1000, 0.25) * speed;
      seek(position + delta, false);
      if (position >= session.duration) playing = false;
    }
    lastTick = now;
    requestAnimationFrame(tick);
  }
  requestAnimationFrame(tick);
  window.HistoricalPlayback = {
    get active() {
      return active;
    },
    open,
    repaint,
    seek,
    exit,
    get id() {
      return session?.metadata.id;
    },
    get position() {
      return position;
    },
  };
})();
