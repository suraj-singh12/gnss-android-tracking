# Issue #96 design proposal — Enhanced recording, playback and Travel Back

**Status: design gate — STOPPED for operator approval. No production implementation.**
Audited application: real `dev` Command at merge `251081848c7d4db3f98c69547b8e7785dcdb30f8` (post-#95), running with `-enhanced-map`, Format 2 fixture package and three live parties. All screenshots are the unmodified production application.

---

## 1. Why recordings cannot keep embedding geography (the one architectural fact)

Legacy recordings never reference a map by value — they **embed** it: every checkpoint serializes the actually-rendered offline vector geometry as SVG (`common.map_svg` plus per-party overlay SVG). Playback replays frozen browser evidence; the server never re-projects anything. That is why legacy playback needs no map retention, works after deletion, and travels with the recording.

Enhanced cartography renders in WebGL and produces **no SVG**. Issue #96 forbids raster/image/video capture, so the geographical background cannot be embedded as pixels either. The only honest reconstruction is therefore:

> **Playback re-renders the real interactive map from the pinned immutable revision, driven by the recorded camera.** The operational overlays (positions, tracks, SOS, selection, labels) remain the existing frozen SVG evidence — untouched.

Everything in this proposal follows from that one fact: revisions must be immutable (true since #94 — content-addressed), referenced recordings must pin them, deletion must be protected, availability at capture time must be recorded honestly, and the renderer must be versioned for compatibility.

## 2. Recording model — extended, not paralleled

The existing model is preserved exactly: browser lease, journal, delta codec, gap kinds (PAUSED / BROWSER NOT ACTIVE / COMMAND NOT RUNNING), Save/seal, timeline, library, privacy and selected-party exclusion. One addition only: when the enhanced renderer is active, each display frame carries an `enhanced` object **inside the same frame schema** (bumped frame `version: 2`; v1 frames without the key remain valid forever):

```
enhanced: {
  renderer: "maplibre-gl", renderer_version: "<vendored version>",
  map_id, revision,            // immutable Format 2 identity (content hash)
  style_hash,                  // generated-style digest — compatibility evidence
  layers: { labels, hillshade },          // enhanced-layer toggles at capture
  availability: {
    state: "ready" | "loading" | "coverage-incomplete" | "degraded" | "failed",
    settled: true|false,       // loaded+idle+stable at the render opportunity
    detail: "<honest operator-facing cause, as displayed live>"
  }
}
```

Already captured today and reused unchanged: `common.viewport` (the production camera — cx/cy/scale, the complete enhanced camera since #95's architecture makes the production camera the single authority), `appearance` (day/night), viewport size, layer menu state, panels, selection. No duplicate camera or theme capture is added.

**Honesty rules (hard requirements):**
- `availability.state` reflects what was *actually rendered* at the render opportunity — `loading` while tiles are in flight, `coverage-incomplete` when the recorded viewport overflowed package coverage, `degraded` on partial/tile-error states, `failed` on context loss. Playback must reproduce that state honestly and may **never** substitute tiles that only became available later, nor data from the current library.
- `settled` implements the strengthened settlement requirement: a frame is `settled:true` only when the tile set was fully loaded **and** MapLibre idle **and** the camera and style have been stable for a short stability window (no source/camera mutation in flight). Settlement never blocks or delays operational capture — positions are captured regardless; only the honest readiness flag varies.
- Switching maps mid-recording produces honest mixed recordings: enhanced intervals carry the object, legacy intervals carry embedded SVG as today, blank intervals carry neither. No interval is ever misrepresented.

**What is *not* captured:** no tile images, no canvas pixels, no per-tile lists (the availability record is compact state, not a tile inventory — the immutable revision plus recorded coverage state is sufficient reconstruction evidence).

## 3. Playback reconstruction — actual interactive maps

Playback (saved recordings and View Recording of the unsaved snapshot) gains a shared MapLibre instance beneath the historical SVG overlay — the **same component** as LIVE, in playback mode:

1. On session open, the player resolves the recorded `(map_id, revision)` against the pin store. The server already serves immutable revision tiles/styles (#94/#95); playback reuses those endpoints unchanged.
2. Each checkpoint applies its recorded `viewport` through the identical Web-Mercator transform — overlay alignment is exact by the same construction as LIVE, measured the same way.
3. Interactive pan/zoom/inspection, follow/seek, timeline navigation, layer controls, theme and selected-party filtering work exactly as they do in playback today — the only difference is that the geographical background is a live interactive map instead of replayed SVG markup. Recorded enhanced-layer toggles initialize the playback layer state; the operator may diverge interactively, as with every other inspection control.
4. Recorded availability is reproduced honestly: intervals captured `loading`/`coverage-incomplete`/`failed` show the same disclosure in the same projection-status surface, not a silently perfect map. Where the recording honestly captured `ready`, playback re-renders the real tiles.

**Fallback when the revision is unavailable** (deleted before protection existed, or a transferred recording without its package — the #38 case): playback falls back to the overlay-only blank experience with an explicit, persistent disclosure: *"Recorded enhanced map revision … is not available on this Command. Positions, tracks and operational evidence are unaffected."* It never substitutes another revision, another map, or the current library.

**Legacy v1 recordings** replay exactly as today — embedded SVG path untouched, permanently. Mixed recordings switch per interval. This is verified by regression, not intention.

## 4. Pinning, retention and deletion protection

Format 2 packages are already immutable and content-addressed; identical revisions are already deduplicated by identity. Added, minimally:

- **Durable references** in the recording store: recording-start (and the first enhanced frame) records a reference `(recording/unsaved identity, map_id, revision)`; Save/seal carries references into the saved recording's metadata; discard releases the unsaved reference in the same lifecycle transaction. Reuses the existing presentation asset-claim mechanics and journal discipline — no parallel bookkeeping.
- **Deletion protection**: deleting an enhanced package that any unsaved or saved recording references is refused with an honest error naming the count of referencing recordings (e.g. *"Referenced by 2 saved recordings — export or delete those recordings first. The map itself is unchanged."*). Replacement cannot occur silently because revisions are immutable identities — a re-imported different package is a different revision.
- **Travel Back pin**: entering Travel Back pins the applicable enhanced revision in memory for the session; exiting releases it. Pins are deliberately memory-only, so a restart can never leave stale pins blocking deletion (**restart-safe cleanup by construction**); durable recording references live in the database and survive restart, as they must.
- **No garbage collection of user maps** is introduced: packages remain operator-managed. GC in the issue scope means reference cleanup, which the lifecycle transactions above cover.

## 5. Travel Back

Travel Back reconstructs from live retained history, not recordings — but the operator experience must be identical in kind: enhanced cartography beneath historical overlays while travelling. Design:

- Entry pins the active enhanced revision (in-memory) and captures the enhanced descriptor into the travel session exactly as `travelMap` captures common state today.
- The travel workspace instantiates the shared component beneath the historical SVG, driven by the same production-camera state the travel timeline already manages. Pan/zoom/inspection/follow behave as in LIVE; alignment is the same construction.
- Coverage-incomplete and unavailability disclosures carry into the travel workspace through the existing projection-status surface.
- Exit releases the pin and restores exactly today's live state. Travel Back without enhanced active is byte-identical to today.

## 6. The recording boundary from #95 is removed

With capture implemented, the temporary #95 safeguards invert:

- The recording-start refusal ("Enhanced map rendering cannot be captured yet") is removed — recording with enhanced active is fully supported.
- The hide-during-recording gate and the mid-recording selection refusal are removed — enhanced stays visible and recording throughout.
- `map_id`/asset claims now truthfully record the enhanced map with its revision, replacing the deliberate blank claim.

## 7. Exact UI changes — exhaustive list

Everything reuses existing production components; no workspace or control redesign.

| # | Surface | Change | Evidence base |
|---|---------|--------|---------------|
| 1 | Recording start dialog | Refusal removed. No new controls — recording simply works with enhanced visible. | `prod-recording-start-refusal.png` shows today's refusal this removes |
| 2 | LIVE during recording | No visible change other than enhanced remaining rendered beneath overlays (today it is hidden or refused) | `prod-live-enhanced.png`, `prod-recording-active.png` |
| 3 | Playback workspace | Enhanced interactive cartography beneath the historical SVG; the existing projection-status surface carries honest availability/missing-revision disclosures. No new chrome. | playback workspace surface |
| 4 | Records library | No change — rows, sort, search unchanged | `prod-records-library.png` |
| 5 | Map library deletion | Deletion of a referenced package refuses with the honest error in the existing error surface; rows unchanged otherwise | `prod-map-library.png` |
| 6 | Travel Back entry dialog | One added disclosure line when an enhanced map will be pinned: *"The active enhanced map is pinned while travelling and released on exit."* Otherwise unchanged | `prod-travelback-today.png` |
| 7 | Layers menu | Enhanced layer toggles (labels/hillshade) already exist from #95 and are captured/replayed; no new controls | production Layers menu |

**Unchanged, explicitly:** all recording lifecycle controls and interactions, timeline, Save/seal flow, privacy (selected-party exclusion is overlay evidence — unaffected), SOS/ACK, GNSS ingestion, legacy maps and their recordings, blank mode, keyboard contract, camera authority, themes, panels, library, Android/protocol. Cosmetic rendering differences between capture and playback (tile-level anti-aliasing, font rasterization) are acceptable per the issue; positions, tracks and geographical context are not.

## 8. Documented portable contract for #38 (documentation only)

`docs/` gains the transfer contract statement, not the implementation: an enhanced recording exported for another Command must carry, beside the existing recording evidence, the immutable `(map_id, revision)` identities plus the package bytes for each referenced revision — because playback reconstructs geography from the pinned revision and must never substitute. Import validates revision presence before claiming completeness and honestly degrades playback when a referenced revision is absent.

## 9. Verification plan (implementation phase)

Existing regressions plus focused additions: legacy v1 fixture replay byte-identical; mixed enhanced/legacy/blank recording; active and final Save; pause/gap intervals; seek/inspection/layers; delayed/stale/missing tiles with throttled tile server; deletion refusal while referenced and success after references released; re-import identity; daemon restart mid-recording and mid-travel; storage-pressure admission; settlement flag correctness against synthetic tile delays; alignment measurement (same viewport-transform construction proof as #95, plus screenshot evidence); SOS/ACK non-interference. `go test -race ./...`, `go vet`, browser regressions, notices and owning docs updated per AGENTS.md.

## 10. Decisions requested from the operator

1. **Deletion protection strength** — refuse deletion while referenced (proposed), or allow with an explicit type-to-confirm warning that playback will permanently degrade? Proposed: refuse; export-first path remains available.
2. **Playback divergence memory** — when the operator pans/zooms during playback inspection then seeks, should the camera re-anchor to the recorded camera at each seek (proposed, consistent with evidence-first playback) or retain the diverged camera?
3. **Availability granularity** — compact per-frame state as proposed, or additionally a per-interval rollup in recording metadata (slightly more evidence, slightly more complexity)? Proposed: per-frame only.
4. **Travel Back pin disclosure** — the one-line entry-dialog disclosure (proposed), or silent pinning since release-on-exit is guaranteed? Proposed: disclose; honesty costs one line.
