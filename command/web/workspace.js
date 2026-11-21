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
    "RECORDING LIVE": ["Operator recording", () => $("recording").textContent.trim() || "Recording status unavailable"],
    "VIEWING RECORDING": ["Viewing recording", () => "Frozen presentation-time evidence · read-only"],
    "TRAVEL BACK": ["Travel Back", () => "Original GNSS capture time · operator-controlled refresh"],
  };
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
    // The rail belongs to Record; an existing recording stays visible in LIVE
    // so active, paused or unsaved evidence is never hidden from the operator.
    const recording = window.recordingLiveState?.()?.recording;
    document.body.classList.toggle("recording-rail-hidden", mode === "LIVE" && !recording);
  }
  const observer = new MutationObserver(syncMode);
  for (const id of ["operational-mode", "recording", "connection"])
    observer.observe($(id), { childList: true, characterData: true, subtree: true });
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

  $("open-sos-sound").addEventListener("click", () => {
    $("sos-panel").showModal();
    $("sos-audio").focus();
  });
})();
