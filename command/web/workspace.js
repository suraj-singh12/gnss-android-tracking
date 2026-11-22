"use strict";
// V5.14 workspace shell: mirrors the existing operational authorities (mode,
// recording, connection) into the header mode picker and context bar, and
// filters the party list. It owns no operational state of its own.
(() => {
  const $ = (id) => document.getElementById(id);
  const modeLabels = {
    LIVE: "LIVE",
    "RECORDING LIVE": "RECORD",
    "VIEWING RECORDING": "VIEW RECORDING",
    "TRAVEL BACK": "TRAVEL BACK",
  };
  const contexts = {
    LIVE: ["Live operations", () => `${$("connection").title || $("connection").textContent} · contact and GNSS freshness stay separate`],
    "RECORDING LIVE": ["Operator recording", () => recordCopy[recordState()].context],
    "VIEWING RECORDING": ["Viewing recording", () => "Frozen presentation-time evidence · read-only"],
    "TRAVEL BACK": ["Travel Back", () => "Original GNSS capture time · operator-controlled refresh"],
  };
  const recordCopy = {
    idle: { context: "Ready to begin presentation-evidence capture", pill: "Not started", title: "Ready to record", sub: "Start captures what Command renders from that moment" },
    active: { context: "Recording the authorized browser presentation", pill: "Recording", title: "Recording live", sub: "Save creates an immutable snapshot without ending the session" },
    paused: { context: "Recording paused · GNSS and SOS continue", pill: "Paused", title: "Recording paused", sub: "Save creates an immutable snapshot without ending the session" },
    stopped: { context: "Capture stopped · unsaved recording retained", pill: "Unsaved", title: "Recording stopped · Unsaved", sub: "" },
  };
  const shown = (id) => !$(id).hidden;
  // Derived only from the existing recording controls' visibility.
  const recordState = () =>
    shown("stopped-choice") ? "stopped" : shown("resume") ? "paused" : shown("pause") ? "active" : "idle";
  const railCopy = document.createElement("div");
  railCopy.className = "rail-copy";
  railCopy.innerHTML = '<strong id="rail-title"></strong><small id="rail-sub"></small>';
  $("recording").before(railCopy);
  railCopy.append($("recording"));
  // The stopped choice belongs in the rail, as in the approved design.
  $("recording").closest(".recording-bar").querySelector(".controls").before($("stopped-choice"));
  const recordPill = document.createElement("span");
  recordPill.id = "record-state-pill";
  recordPill.className = "pill";
  const factsButton = document.createElement("button");
  factsButton.type = "button";
  factsButton.id = "record-facts";
  factsButton.className = "context-button";
  factsButton.textContent = "Recording facts";
  factsButton.onclick = () => {
    const disclosure = document.querySelector(".capture-disclosure");
    disclosure.open = !disclosure.open;
  };
  $("context-actions").prepend(recordPill, factsButton);
  const setText = (node, text) => {
    if (node.textContent !== text) node.textContent = text;
  };
  function syncMode() {
    const mode = $("operational-mode").textContent.trim() || "LIVE";
    const label = modeLabels[mode] || mode;
    setText($("mode-label"), label);
    $("mode-button").setAttribute("aria-label", `Operational mode: ${label}`);
    for (const item of $("mode-navigation").querySelectorAll("button")) {
      const check = item.querySelector(".menu-check");
      if (check) setText(check, item.getAttribute("aria-pressed") === "true" ? "✓" : "");
    }
    const [title, detail] = contexts[mode] || contexts.LIVE;
    setText($("context-title"), title);
    setText($("context-detail"), detail());
    document.body.dataset.mode = mode.toLowerCase().replaceAll(" ", "-");
    const state = recordState(), copy = recordCopy[state];
    setText($("rail-title"), copy.title);
    setText($("rail-sub"), copy.sub);
    setText(recordPill, copy.pill);
    const quiet = state === "idle" || state === "stopped";
    if ($("recording").hidden !== quiet) $("recording").hidden = quiet;
    recordPill.classList.toggle("warn", state === "paused" || state === "stopped");
    recordPill.hidden = factsButton.hidden = mode !== "RECORDING LIVE";
    // The rail belongs to Record; an existing recording stays visible in LIVE
    // so active, paused or unsaved evidence is never hidden from the operator.
    const recording = window.recordingLiveState?.()?.recording;
    document.body.classList.toggle("recording-rail-hidden", mode === "LIVE" && !recording);
  }
  const observer = new MutationObserver(syncMode);
  for (const id of ["operational-mode", "recording", "connection", "pause", "resume", "stopped-choice"])
    observer.observe($(id), { childList: true, characterData: true, subtree: true, attributes: true, attributeFilter: ["hidden"] });
  setInterval(syncMode, 500);
  syncMode();

  // Mode menu keyboard support: arrows move between enabled modes.
  $("mode-picker").addEventListener("keydown", (event) => {
    if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
    const picker = $("mode-picker");
    if (!picker.open) return;
    const items = [...$("mode-navigation").querySelectorAll("button")].filter((b) => !b.disabled);
    if (!items.length) return;
    const current = items.indexOf(document.activeElement);
    const next = event.key === "ArrowDown"
      ? (current + 1) % items.length
      : (current <= 0 ? items.length - 1 : current - 1);
    items[next].focus();
    event.preventDefault();
  });
  $("mode-picker").addEventListener("toggle", () => {
    if ($("mode-picker").open)
      ($("mode-navigation").querySelector('[aria-pressed="true"]:not(:disabled)') ||
        $("mode-navigation").querySelector("button:not(:disabled)"))?.focus();
  });

  // Party search and filters only hide list rows; map visibility and selection
  // remain owned by the existing per-party track switch and selection.
  let partyFilter = sessionStorage.getItem("gnss-party-filter") || "all";
  function applyPartyFilter() {
    const query = $("party-search").value.trim().toLowerCase();
    let total = 0, shown = 0;
    for (const card of $("devices").children) {
      if (!card.dataset.search) continue;
      total++;
      const visible =
        (!query || card.dataset.search.includes(query)) &&
        (partyFilter === "all" ||
          (partyFilter === "online" && card.dataset.contact === "healthy") ||
          (partyFilter === "attention" && card.dataset.attention === "true"));
      if (card.hidden !== !visible) card.hidden = !visible;
      if (visible) shown++;
    }
    for (const button of document.querySelectorAll("[data-party-filter]"))
      button.setAttribute("aria-pressed", String(button.dataset.partyFilter === partyFilter));
    setText(
      $("party-filter-status"),
      !total
        ? "Contact and GNSS are independent."
        : shown === total
          ? `${total} of ${total} · contact and GNSS are independent`
          : `${shown} of ${total} shown`,
    );
  }
  $("party-search").addEventListener("input", applyPartyFilter);
  for (const button of document.querySelectorAll("[data-party-filter]"))
    button.addEventListener("click", () => {
      partyFilter = button.dataset.partyFilter;
      sessionStorage.setItem("gnss-party-filter", partyFilter);
      applyPartyFilter();
    });
  new MutationObserver(applyPartyFilter).observe($("devices"), { childList: true });
  applyPartyFilter();

  // V5.14 modal chrome: heading, scrollable body and a footer of actions.
  // Existing dialogs, IDs and handlers are reused; only the layout is grouped.
  const closeButton = (dialog, label = "Close") => {
    const b = document.createElement("button");
    b.type = "button";
    b.textContent = label;
    b.dataset.dismiss = dialog.id;
    return b;
  };
  const footerActions = {
    "browser-occupied": ["#retry-owner"],
    "phone-control": ["#control-issue"],
    "library-edit": ["#library-edit-form > button[type=submit]"],
    "event-history": ["a.secondary-action", "#export-runtime"],
    layers: ["#apply-layers"],
  };
  for (const dialog of document.querySelectorAll("#command dialog")) {
    const heading = dialog.querySelector(":scope > .dialog-heading");
    if (!heading || dialog.id === "map-settings") continue;
    dialog.classList.add("v514-modal");
    const body = document.createElement("div");
    body.className = "dialog-body";
    let footer = dialog.querySelector(":scope > .dialog-footer");
    for (const node of [...dialog.childNodes])
      if (node !== heading && node !== footer) body.append(node);
    dialog.append(body);
    if (!footer) {
      footer = footerActions[dialog.id]
        ? null
        : body.querySelector(":scope > .controls, :scope > form > .controls");
      if (footer) {
        const form = footer.closest("form");
        for (const b of footer.querySelectorAll("button"))
          if (form && b.type === "submit") b.setAttribute("form", form.id);
      } else {
        footer = document.createElement("div");
        footer.className = "controls";
        if (dialog.id !== "browser-occupied") footer.append(closeButton(dialog));
        for (const selector of footerActions[dialog.id] || []) {
          const action = dialog.querySelector(selector);
          if (!action) continue;
          const form = action.closest("form");
          if (form) action.setAttribute("form", form.id);
          footer.append(action);
        }
      }
      footer.classList.add("dialog-footer");
    }
    dialog.append(footer);
  }
  document.addEventListener("click", (event) => {
    const dismiss = event.target.closest("[data-dismiss]");
    if (dismiss) $(dismiss.dataset.dismiss).close();
  });
  // Segmented filters reuse the existing select as the single filter owner.
  const segmented = (select, labels, labelText) => {
    const bar = document.createElement("div");
    bar.className = "event-filter-bar";
    const label = document.createElement("span");
    label.className = "event-filter-label";
    label.id = select.id + "-label";
    label.textContent = labelText;
    const group = document.createElement("div");
    group.className = "event-segments";
    group.setAttribute("role", "group");
    group.setAttribute("aria-labelledby", label.id);
    for (const option of select.options) {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "event-segment";
      b.dataset.eventFilter = option.value;
      b.textContent = labels[option.value] || option.textContent;
      b.onclick = () => {
        select.value = option.value;
        select.dispatchEvent(new Event("change"));
        sync();
      };
      group.append(b);
    }
    const sync = () => {
      for (const b of group.children) b.setAttribute("aria-pressed", String(b.dataset.eventFilter === select.value));
    };
    select.addEventListener("change", sync);
    bar.append(label, group);
    const wrapper = select.closest("label") || select;
    wrapper.before(bar);
    wrapper.hidden = true;
    sync();
  };
  segmented($("event-filter"), { all: "All", sos: "SOS", operations: "Operations" }, "Show");

  // SOS emergencies: Pending shows unacknowledged events; All shows the history.
  let sosView = "pending";
  const applySosView = () => {
    let shown = 0;
    for (const card of $("sos-alerts").children) {
      const visible = sosView === "all" || !card.classList.contains("acknowledged");
      if (card.hidden !== !visible) card.hidden = !visible;
      if (visible) shown++;
    }
    $("sos-empty").hidden = shown > 0;
    setText($("sos-empty"), sosView === "all" ? "No SOS events received." : "No pending emergencies.");
    for (const b of document.querySelectorAll("[data-sos-view]"))
      b.setAttribute("aria-pressed", String(b.dataset.sosView === sosView));
  };
  for (const b of document.querySelectorAll("[data-sos-view]"))
    b.addEventListener("click", () => {
      sosView = b.dataset.sosView;
      applySosView();
    });
  new MutationObserver(applySosView).observe($("sos-alerts"), { childList: true, subtree: true, attributes: true, attributeFilter: ["class"] });
  applySosView();

  // Phone controls: Send instruction, Enrollment and Results views of the one dialog.
  {
    const body = $("phone-control").querySelector(".dialog-body");
    const panels = {
      enrollment: [body.querySelector(".control-identity")],
      results: [...body.querySelectorAll("h2")].filter((h) => /history/i.test(h.textContent)).concat($("control-history")),
    };
    const tabs = document.createElement("div");
    tabs.className = "phone-tabs";
    tabs.setAttribute("role", "tablist");
    tabs.setAttribute("aria-label", "Phone control views");
    const views = [["send", "Send instruction"], ["enrollment", "Enrollment"], ["results", "Results"]];
    const select = (view) => {
      for (const node of body.children) {
        if (node === tabs) continue;
        const panel = Object.keys(panels).find((k) => panels[k].includes(node)) || "send";
        node.classList.toggle("view-hidden", panel !== view);
      }
      for (const b of tabs.children) {
        b.setAttribute("aria-selected", String(b.dataset.view === view));
        b.tabIndex = b.dataset.view === view ? 0 : -1;
      }
      $("control-issue").classList.toggle("view-hidden", view !== "send");
    };
    for (const [view, label] of views) {
      const b = document.createElement("button");
      b.type = "button";
      b.id = "phone-tab-" + view;
      b.dataset.view = view;
      b.setAttribute("role", "tab");
      b.textContent = label;
      b.onclick = () => select(view);
      tabs.append(b);
    }
    tabs.addEventListener("keydown", (event) => {
      const buttons = [...tabs.children], index = buttons.indexOf(document.activeElement);
      if (index < 0) return;
      const next = { ArrowRight: index + 1, ArrowLeft: index - 1, Home: 0, End: buttons.length - 1 }[event.key];
      if (next === undefined) return;
      event.preventDefault();
      const target = buttons[(next + buttons.length) % buttons.length];
      target.click();
      target.focus();
    });
    body.prepend(tabs);
    select("send");
  }

  // Apply quality rules lives in the footer; the form re-renders its button.
  const qualityFooter = $("quality").querySelector(".dialog-footer");
  const moveQualityApply = () => {
    const apply = $("quality-form").querySelector(":scope > button");
    if (!apply) return;
    qualityFooter.querySelector(".quality-apply")?.remove();
    apply.classList.add("primary", "quality-apply");
    apply.setAttribute("form", "quality-form");
    qualityFooter.append(apply);
  };
  new MutationObserver(moveQualityApply).observe($("quality-form"), { childList: true });
  moveQualityApply();

  // Records and Tools: one tab strip per group, moved into whichever group
  // dialog is open. Each tab keeps its existing ID and opening handler.
  for (const tabs of document.querySelectorAll(".workspace-tabs[data-tab-group]")) {
    const buttons = [...tabs.querySelectorAll("[data-tab-dialog]")];
    const dialogs = buttons.map((b) => $(b.dataset.tabDialog));
    const place = (dialog) => {
      dialog.querySelector(".dialog-body").prepend(tabs);
      for (const b of buttons) {
        const selected = b.dataset.tabDialog === dialog.id;
        b.setAttribute("aria-selected", String(selected));
        b.tabIndex = selected ? 0 : -1;
      }
    };
    // Window capture runs before playback's document-level interception, so a
    // tab repurposed during playback (Quality) still closes its group dialog.
    window.addEventListener("click", (event) => {
      const b = event.target.closest?.("[data-tab-dialog]");
      if (!buttons.includes(b)) return;
      for (const d of dialogs) if (d.open && d.id !== b.dataset.tabDialog) d.close();
    }, true);
    for (const d of dialogs)
      new MutationObserver(() => {
        if (d.open) {
          place(d);
          if (d.contains(document.activeElement) === false || tabs.contains(document.activeElement))
            tabs.querySelector('[aria-selected="true"]')?.focus();
        }
      }).observe(d, { attributes: true, attributeFilter: ["open"] });
    tabs.addEventListener("keydown", (event) => {
      const index = buttons.indexOf(document.activeElement);
      if (index < 0) return;
      const next = { ArrowRight: index + 1, ArrowLeft: index - 1, Home: 0, End: buttons.length - 1 }[event.key];
      if (next === undefined) return;
      event.preventDefault();
      buttons[(next + buttons.length) % buttons.length].click();
    });
    $(tabs.dataset.tabOpener).addEventListener("click", () => {
      // Playback repurposes open-quality as historical inspection, so the
      // opener starts on the first tab that still opens its own dialog.
      const intercepted = (b) => b.id === "open-quality" && document.body.classList.contains("playback");
      (buttons.find((b) => !b.disabled && !intercepted(b)) || buttons[0]).click();
    });
  }
  $("open-diagnostics").addEventListener("click", () => $("diagnostics").showModal());
  $("open-sos-sound").addEventListener("click", () => $("sos-sound").showModal());
  $("diagnostics-runtime").addEventListener("click", () => $("export-runtime").click());
  // Tools › SOS sound mirrors the single SOS alarm switch in SOS emergencies.
  const syncSoundMirror = () => {
    const on = $("sos-audio").getAttribute("aria-checked") === "true";
    $("tools-sos-audio").setAttribute("aria-checked", String(on));
    setText($("tools-sos-audio-status"), $("sos-audio-status").textContent.trim());
    const pending = Number($("sos-count").textContent) || 0;
    setText($("tools-sos-pending"), pending ? `${pending} unacknowledged` : "None");
  };
  $("tools-sos-audio").addEventListener("click", () => {
    $("sos-audio").click();
    syncSoundMirror();
  });
  new MutationObserver(syncSoundMirror).observe($("sos-audio"), { attributes: true, attributeFilter: ["aria-checked"] });
  new MutationObserver(syncSoundMirror).observe($("sos-audio-status"), { childList: true, characterData: true, subtree: true });
  new MutationObserver(syncSoundMirror).observe($("sos-count"), { childList: true, characterData: true, subtree: true });
  syncSoundMirror();
})();
