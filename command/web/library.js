"use strict";
(() => {
  const $ = (id) => document.getElementById(id);
  let entries = [],
    editing,
    selectedId;
  async function request(body) {
    const response = await fetch(
      "/local/presentation/library",
      body
        ? {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
          }
        : {},
    );
    const data = await response.json();
    if (!response.ok)
      throw Error(data.message || "Saved recordings unavailable");
    return data;
  }
  function text(tag, value) {
    const el = document.createElement(tag);
    el.textContent = value;
    return el;
  }
  function duration(seconds) {
    seconds = Math.max(0, Math.floor(seconds));
    return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
  }
  function selectRecording(m) {
    const root = $("library-selected");
    root.replaceChildren(
      text("h2", `Selected · ${m.name}`),
      text(
        "p",
        `Actual Start · ${new Date(m.started_at).toLocaleString(undefined, { timeZoneName: "short" })}`,
      ),
      text(
        "p",
        `Saved at · ${new Date(m.saved_at).toLocaleString(undefined, { timeZoneName: "short" })}`,
      ),
      text(
        "p",
        `Snapshot: ${m.snapshot_status || (m.stopped_at ? "stopped" : "legacy — see facts")} · Source: ${m.source_mode}`,
      ),
      text("p", `Total parties during recording: ${m.total_parties}`),
    );
    const details = document.createElement("details");
    details.append(
      text(
        "summary",
        `Parties included in saved recording by operator: ${m.included.length} of ${m.total_parties}`,
      ),
      text(
        "p",
        m.included
          .map((id) => m.participation?.[id]?.party?.id || id)
          .join(", "),
      ),
    );
    root.append(details);
    if (m.display_date)
      root.append(text("p", `Descriptive date · ${m.display_date}`));
    if (m.description) root.append(text("p", m.description));
    const notice = text("div", "");
    notice.className = "library-evidence-note";
    notice.append(
      text("strong", "Snapshot evidence is immutable."),
      text(
        "small",
        "Rename or delete applies to saved library resources only. Evidence gaps remain marked.",
      ),
    );
    root.append(notice);
  }
  function render() {
    const query = $("library-search").value.toLocaleLowerCase(),
      sort = $("library-sort").value;
    const filtered = entries.filter((m) =>
      [m.name, m.description, m.source_mode, m.display_date].some((v) =>
        String(v || "")
          .toLocaleLowerCase()
          .includes(query),
      ),
    );
    filtered.sort(
      (a, b) =>
        String(a[sort] || "").localeCompare(String(b[sort] || "")) *
        (["saved_at", "started_at"].includes(sort) ? -1 : 1),
    );
    if (!filtered.some((m) => m.id === selectedId))
      selectedId = filtered[0]?.id;
    $("library-selected").replaceChildren();
    if (selectedId) selectRecording(filtered.find((m) => m.id === selectedId));
    $("library-rows").replaceChildren();
    for (const m of filtered) {
      const row = text("section", "");
      row.className = "library-row";
      const thumbnail = document.createElement("div");
      thumbnail.className = "library-thumbnail";
      thumbnail.setAttribute("aria-hidden", "true");
      thumbnail.innerHTML =
        '<svg viewBox="0 0 56 56" width="56" height="56"><path d="M10 40L23 22L35 30L46 13" fill="none" stroke="#17654c" stroke-width="3"/><circle cx="10" cy="40" r="4" fill="#17654c"/><circle cx="23" cy="22" r="4" fill="#287cb0"/><circle cx="46" cy="13" r="4" fill="#985a8d"/></svg>';
      const info = text("div", "");
      info.className = "library-row-info";
      const select = text("button", m.name);
      select.className = "library-select";
      select.onclick = () => {
        selectedId = m.id;
        selectRecording(m);
        for (const card of $("library-rows").children)
          card.classList.toggle("selected", card.dataset.recordingId === m.id);
      };
      info.append(
        select,
        text(
          "p",
          `${new Date(m.started_at).toLocaleDateString()} · ${duration((Date.parse(m.cutoff_at) - Date.parse(m.started_at)) / 1000)}`,
        ),
        text(
          "small",
          `${m.included.length} of ${m.total_parties} included · ${m.captured_interruptions || 0} interruption facts`,
        ),
      );
      row.dataset.recordingId = m.id;
      if (selectedId === m.id) row.classList.add("selected");
      row.append(thumbnail, info);
      const edit = text("button", "Edit metadata");
      edit.onclick = () => {
        editing = m.id;
        $("library-name").value = m.name;
        $("library-date").value = m.display_date || "";
        $("library-description").value = m.description || "";
        $("library-edit-status").textContent = "";
        $("library-edit").showModal();
      };
      const del = text("button", "Delete");
      del.onclick = async () => {
        if (
          !confirm(
            `Delete “${m.name}” from the library? Operational GNSS and SOS remain retained. Unshared snapshot-owned resources will be released.`,
          )
        )
          return;
        del.disabled = true;
        try {
          const result = await request({
            id: m.id,
            action: "delete",
            confirmed: true,
          });
          if (window.HistoricalPlayback?.id === m.id)
            window.HistoricalPlayback.exit();
          if (await load())
            $("library-status").textContent = result.cleanup_pending
              ? "Recording removed; resource cleanup pending: " +
                result.cleanup_error
              : "Recording removed from library.";
        } catch (e) {
          $("library-status").textContent = e.message;
        } finally {
          del.disabled = false;
        }
      };
      const open = text("button", "Open");
      open.onclick = async () => {
        open.disabled = true;
        $("library-status").textContent =
          "Opening recording · verifying frozen evidence…";
        try {
          await window.HistoricalPlayback.open(m);
          $("saved-library").close();
        } catch (e) {
          $("library-status").textContent = e.message;
        } finally {
          open.disabled = false;
        }
      };
      const actions = text("div", "");
      actions.className = "library-row-actions";
      open.className = selectedId === m.id ? "primary" : "";
      const more = document.createElement("details");
      more.className = "library-more";
      const summary = text("summary", "⋯");
      summary.setAttribute("aria-label", `Recording actions for ${m.name}`);
      const menu = text("div", "");
      menu.append(edit, del);
      more.append(summary, menu);
      actions.append(open, more);
      row.append(actions);
      $("library-rows").append(row);
    }
    $("library-status").textContent = filtered.length
      ? ""
      : entries.length
        ? "No matching recordings."
        : "No saved recordings yet. Use Save in the Recording strip.";
  }
  async function load() {
    $("library-status").textContent = "Loading saved recordings…";
    try {
      entries = await request();
      render();
      return true;
    } catch (e) {
      $("library-rows").replaceChildren();
      $("library-status").textContent = e.message;
    }
  }
  $("open-library").onclick = () => {
    $("saved-library").showModal();
    load();
  };
  $("library-search").oninput = render;
  $("library-sort").onchange = render;
  $("library-edit-form").onsubmit = async (event) => {
    event.preventDefault();
    const button = event.submitter;
    button.disabled = true;
    try {
      await request({
        id: editing,
        action: "edit",
        name: $("library-name").value,
        display_date: $("library-date").value,
        description: $("library-description").value,
      });
      $("library-edit").close();
      if (await load()) $("library-status").textContent = "Metadata saved.";
    } catch (e) {
      $("library-edit-status").textContent = e.message;
    } finally {
      button.disabled = false;
    }
  };
})();
