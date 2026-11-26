# Issue #96 design proposal — Enhanced recording, playback and Travel Back

**Status: design gate — updated with operator refinements (addendum 2026-11-26), STOPPED for final approval. No production implementation.**
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
  style: { day, night },       // the exact generated style documents (see below)
  layers: { labels, hillshade },          // enhanced-layer toggles at capture
  availability: {
    state: "ready" | "loading" | "coverage-incomplete" | "degraded" | "failed",
    settled: true|false,       // loaded+idle+stable at the render opportunity
    missing: [ { product, z, x, y } ],   // bounded, deduplicated — see below
    truncated: false,          // honest overflow evidence if the bound is hit
    detail: "<honest operator-facing cause, as displayed live>"
  }
}
```

Already captured today and reused unchanged: `common.viewport` (the production camera — cx/cy/scale, the complete enhanced camera since #95's architecture makes the production camera the single authority), `appearance` (day/night), viewport size, layer menu state, panels, selection. No duplicate camera or theme capture is added.

**Honesty rules (hard requirements):**
- `availability.state` reflects what was *actually rendered* at the render opportunity — `loading` while tiles are in flight, `coverage-incomplete` when the recorded viewport overflowed package coverage, `degraded` on partial/tile-error states, `failed` on context loss. Playback must reproduce that state honestly and may **never** substitute tiles that only became available later, nor data from the current library. **A warning never justifies display: geographical information unavailable during capture is not merely disclosed — it is absent in playback reconstruction.** The disclosure explains the absence; it never accompanies silently substituted geography.
- `availability.missing` is the bounded, deduplicated tile/product availability record (operator decision 3): the deterministic `{product, z, x, y}` keys of tiles that were required by the recorded viewport but *not* actually rendered at capture — still loading, errored, or outside coverage. Tile identity is deterministic per source (z/x/y), tile counts inside one viewport are naturally small (single digits to dozens), identical keys deduplicate across frames and intervals through the existing delta codec, and a fixed bound caps the record: beyond the bound, recording stops adding keys and sets `truncated:true` as honest evidence — capture never fails and positions are never blocked. Recording the *missing* set rather than the rendered set keeps the common case (complete rendering) at zero cost.
- `style` embeds the exact generated style documents (operator refinement: compatibility is **preserved**, not merely hash-identified). The style is generated, compact (small JSON, no assets — tiles/fonts are served by identity from the immutable revision) and constant across a recording, so the delta codec stores it once. Playback renders the *recorded* style verbatim through the vendored renderer; the recorded `renderer`/`renderer_version` is validated for compatibility and any incompatibility degrades honestly with disclosure rather than mis-rendering.
- `settled` implements the strengthened settlement requirement: a frame is `settled:true` only when the tile set was fully loaded **and** MapLibre idle **and** the camera and style have been stable for a short stability window (no source/camera mutation in flight). Settlement never blocks or delays operational capture — positions are captured regardless; only the honest readiness flag varies.
- Switching maps mid-recording produces honest mixed recordings: enhanced intervals carry the object, legacy intervals carry embedded SVG as today, blank intervals carry neither. No interval is ever misrepresented.

**What is *not* captured:** no tile images, no canvas pixels, no per-tile lists (the availability record is compact state, not a tile inventory — the immutable revision plus recorded coverage state is sufficient reconstruction evidence).

## 3. Playback reconstruction — actual interactive maps

Playback (saved recordings and View Recording of the unsaved snapshot) gains a shared MapLibre instance beneath the historical SVG overlay — the **same component** as LIVE, in playback mode:

1. On session open, the player resolves the recorded `(map_id, revision)` against the pin store. The server already serves immutable revision tiles/styles (#94/#95); playback reuses those endpoints unchanged.
2. Each checkpoint applies its recorded `viewport` through the identical Web-Mercator transform — overlay alignment is exact by the same construction as LIVE, measured the same way.
3. Interactive pan/zoom/inspection, follow/seek, timeline navigation, layer controls, theme and selected-party filtering work exactly as they do in playback today — the only difference is that the geographical background is a live interactive map instead of replayed SVG markup. Recorded enhanced-layer toggles initialize the playback layer state; the operator may diverge interactively, as with every other inspection control.
4. Recorded availability is **enforced**, not merely disclosed (operator decision 3): playback reconstructs each interval's cartography from the pinned revision but suppresses exactly the recorded `missing` tile keys — implemented as a deterministic request gate in the shared component's playback mode (tile URLs are deterministic `{product}/{z}/{x}/{y}`, so a `transformRequest`-level filter needs no new infrastructure). Unavailable-at-capture tiles simply do not render; the honest disclosure explains the gap. Where the recording captured `ready`, playback re-renders the real tiles completely.
5. Explicit seek re-anchors the recorded camera (operator decision 2): timeline seeks restore the checkpoint's recorded viewport; interactive inspection between seeks diverges exactly as playback inspection behaves today and is never destroyed except by the operator's own seek.

**Fallback when the revision is unavailable** (deleted before protection existed, or a transferred recording without its package — the #38 case): playback falls back to the overlay-only blank experience with an explicit, persistent disclosure: *"Recorded enhanced map revision … is not available on this Command. Positions, tracks and operational evidence are unaffected."* It never substitutes another revision, another map, or the current library.

**Legacy v1 recordings** replay exactly as today — embedded SVG path untouched, permanently. Mixed recordings switch per interval. This is verified by regression, not intention.

## 4. Pinning, retention and deletion protection

Format 2 packages are already immutable and content-addressed; identical revisions are already deduplicated by identity. Added, minimally:

- **Durable recording references** in the recording store: recording-start (and the first enhanced frame) records a reference `(recording/unsaved identity, map_id, revision)`; Save/seal carries references into the saved recording's metadata; discard releases the unsaved reference in the same lifecycle transaction. Reuses the existing presentation asset-claim mechanics and journal discipline — no parallel bookkeeping.
- **Backend-enforced pins, atomic against deletion** (operator decision 4): one small pin table holds both durable recording references and ephemeral Travel Back pins. Deletion checks pins **inside the same transaction** as the delete, so a pin acquisition and a deletion can never interleave — the deletion either sees the pin and refuses, or commits first and the later pin acquisition fails honestly because the revision is gone. Travel Back pins are acquired through the Command backend at entry and released at exit; because travel sessions do not survive a restart, startup sweeps ephemeral pin rows — restart cleanup is a deterministic one-statement migration-time sweep, not a heuristic. Pin acquisition failure (revision already gone) surfaces honestly at Travel Back entry rather than degrading mid-session.
- **Deletion protection**: deleting an enhanced package that any recording or active Travel Back session references is refused with an honest error naming the referencing recordings (e.g. *"Referenced by 2 saved recordings and 1 active Travel Back session — the map itself is unchanged."*). Per operator direction the error does **not** suggest export as a workaround: self-contained enhanced-recording transfer is #38 scope and does not exist yet. Replacement cannot occur silently because revisions are immutable identities — a re-imported different package is a different revision.
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

## 10. Operator decisions — resolved (2026-11-26)

1. **Deletion protection: refuse** deletion of referenced map revisions. The refusal error does not suggest export as a workaround — self-contained enhanced-recording transfer is #38 scope and does not yet exist.
2. **Seek re-anchors the recorded camera**; interactive inspection between seeks is unchanged existing playback behaviour.
3. **Availability is bounded, deduplicated tile/product metadata** — the `missing` record above — and it gates playback reconstruction: a warning never justifies displaying geography that was unavailable during capture.
4. **Travel Back pin disclosure approved**; pins are enforced at the Command backend atomically against deletion, with reliable release and deterministic restart sweep.
5. **Style/renderer compatibility is preserved, not merely identified**: the exact generated style documents travel inside the recording evidence (bounded, delta-compressed) and playback renders them verbatim through the vendored renderer, with recorded-version validation and honest degradation on incompatibility.
6. Existing terrain inspection and recording functionality are preserved unchanged — terrain layers, hillshade/contours/elevation controls and every recording guarantee remain exactly as they are.

**Feasibility confirmation** (verified against the merged #95 implementation, not prototypes): tile URLs are deterministic per product `{z}/{x}/{y}` and already served by immutable identity, so both recording-side missing-key capture (reading the live source caches) and playback-side gating (a request-level filter in the shared component) are small additions inside the existing component — no new rendering infrastructure. Pin atomicity is one table plus transaction-local checks in the existing deletion and pin endpoints; the restart sweep is one statement at startup. Style embedding rides the existing delta codec and compresses to a single copy per recording. No parallelism, no new subsystems.
