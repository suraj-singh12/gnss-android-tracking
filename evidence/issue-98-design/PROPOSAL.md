# Issue #98 design proposal — automated offline map preparation

**Status: proposal, frozen only on explicit operator approval. Simulated UI — not production.**
Design gate under Issue #62. Evidence branch `evidence/issue-98-design`; interactive prototype `prototype.html` (open in any browser, eight scenarios + night mode).

Sources inspected before designing: canonical `AGENTS.md` on `main`; Issue #98 and #93 consolidated architecture (comment 7161701596) with operator refinements (7161937356, esp. refinement 4: managed pack acquisition, no separate updater platform); the actual current Command application running at `dev` `e24e2e6` (`c065fbf` content); the qualified preparation evidence from #93–#97 (Andorra pipeline, 20 km-class input sizes, pack sizes 45–64 MB per platform).

## 1. What exists today (verified against the running application)

The **Maps & Layers** window already has four tabs — Get a Map, Import, My Maps, Display (screenshots `shots/current-*.png`):

- **Get a Map** already solves area selection well: place/coordinate search, visual circle preview, radius presets (1/5/10 km + Custom), terrain toggle, an estimate line, and a single **Download map** action. Consent exists today as the estimate review plus the 5 MB cumulative allowance with explicit *Approve additional transfer* escalation.
- **My Maps** is the library: use, export, delete; deletion protection for recording-referenced maps already exists (#96).
- Progress today is a status line plus *Stop download*, *Retry Missing*, diagnostics folded into a details disclosure; jobs continue server-side when the window closes and resume semantics already exist in the ledger.

**Design consequence: the correct design is not a new workflow — it is the same Get a Map flow, extended to prepare enhanced Format 2 maps, with exactly one genuinely new operator-facing concept (one-time preparation-tools setup). Everything else reuses existing surfaces.** This follows quality rules 1, 3, 6 and 15: one function one interface, controls at point of use, no clutter, no lost capability.

## 2. The proposed operator experience

### 2.1 First use — preparation tools absent (scenario 1–2)

When the operator opens Get a Map without a compatible pack installed, the tab shows the normal area controls (they can already explore area and radius — zero dead ends) but instead of the estimate line, one amber setup notice:

> **One-time map preparation setup required** — Preparing a high-quality offline map on this computer needs the GNSS preparation tools (one download, about 64 MB). They are verified automatically against the official GNSS build fingerprint before use — nothing to install, no internet needed afterwards. Viewing and importing existing maps works without them.

Footer: **Download tools · 64 MB** (primary, exact size known up front — unlike geographical data, the pack size is exact) replacing the disabled *Download map*. One folded alternative: *import tools from a file instead* for fully offline operators (file picker + the existing digest-authenticated offline import from #97 — unchanged). No new screen, no wizard, no separate "pack management" UI anywhere. Rationale per rule 3: control at point of use; the operator meets the tools exactly when they first need them, never before.

While downloading (scenario 2): three compact stages — *Downloading preparation tools* (exact bytes, real progress because the size is known), *Verifying integrity against the official fingerprint*, *Preparation tools ready* — then preparation of the requested map starts automatically; the operator's original intent is never lost (rule 10). A *Technical details* disclosure carries the source, platform and the sha256 for operators who want it; the fingerprint comparison itself is silent because it cannot fail invisibly — mismatch refuses the tools with an honest error, never a warning-and-continue.

**Trust design (implementation-relevant, shown because it shapes what the UI can promise):** each Command build carries the compatible pack version and its official sha256 **compiled into the binary**. The digest therefore arrives inside the authenticated Command distribution itself — independent of the download channel, and never inside the archive — satisfying the operator's #97 directive exactly. The download URL is a fixed project release location compiled in the same way. An operator who does not trust the channel can use the file-import alternative with the digest published on the official build page. Cryptographic signing remains future hardening, unchanged from the #97 decision.

Failure of the tools download is small and honest: retryable in place, partial bytes discarded (a tool file is either whole-verified or nothing — there is no safe resume of an unverified prefix; at 45–64 MB this is acceptable), existing offline import always available.

### 2.2 Normal preparation — tools installed (scenario 3)

Exactly today's flow with a better-informed estimate line:

> Estimated download **about 85 MB** · map storage **about 240 MB** · usually **10–25 minutes** on this computer. Previously downloaded data in this area is reused, not downloaded again.

Differences from today, each deliberate:

1. **The estimate is a real plan**, because the pipeline can now cost the regional extract, DEM and land-cover products before committing (bounded, cache-aware). Where a number is genuinely unknowable the line says so honestly rather than implying precision — same truthfulness contract as today (rule 13).
2. **Cache reuse is stated in plain language** — repeat or overlapping preparation downloads little or nothing, which is the acceptance-testable "offline cache-only repeat" behaviour made visible.
3. **Consent stays the existing mechanism**: the estimate review plus the cumulative allowance with explicit escalation when transfer would exceed it. The *Download map* button carries the estimate in its label (**Download map · about 85 MB**) so consent is inseparable from the action — no extra checkbox; the existing separate "I reviewed" checkbox disappears in this flow because the button itself is the informed consent (reduces clutter, rule 6; nothing is actually lost because today's checkbox gates exactly this same button). **Operator decision requested — see Q4.**

Terrain stays the existing switch (default on for enhanced preparation because hillshade/elevation are integral to the enhanced product; the operator can still opt out — same control, same position).

### 2.3 Progress and cancellation (scenario 4)

Four operator-facing stages, plain language, one active at a time:

1. **Downloading geographical data** — real bytes where known, cache reuse credited ("reused 14 MB from cache")
2. **Processing map features** — classification, tiling, geometry repair inside
3. **Preparing terrain & elevation** — DEM/land-cover products
4. **Checking map quality** — the zero-invalid validation gate

One line beneath: *You can close this window — preparation continues. Tracking, recording and SOS are not affected.* Both halves are load-bearing truth: the job is server-side (already true of the existing ledger), and the isolation guarantee is an operator-visible promise the implementation must keep (heavy stages outside GNSS locks, bounded resources, deferrable under load — per the approved architecture). Technical detail (feature counts, tool versions, stage internals) is folded into one disclosure, never on the main surface (rules 3, 8 of the Command corrections list).

**Stop preparation** is the single destructive-ish action, always available, with its result honest: stopping keeps received data for resume but publishes nothing. No parallel cancel/stop/pause vocabulary — one verb (rule 1).

### 2.4 Interrupted or failed downloads (scenario 5)

Interrupted states are a first-class visual: amber notice naming the cause (*network unreachable or stopped by operator*), exact position (*41 of 85 MB*), and the two certainties: everything received is kept, nothing incomplete is used. Actions: **Resume download** (primary) / **Discard partial download** (danger, rule 9 semantics). This is the existing ledger resume semantics surfaced in the new staged presentation — no new model.

### 2.5 Failure — insufficient storage (scenario 6) and other failures

Failures are red, specific, and always carry the recovery path in the same breath: *needs about 240 MB, only 118 MB remains — free storage, then resume; the 71 MB already downloaded is kept and reused.* The crucial trust sentence is always present: **preparation stopped before touching any existing map** — atomic publication means existing maps are structurally untouchable by partial output, and the UI says so. The same pattern serves every failure class (source unreachable, corrupt output detected, validation failure): what happened, what it means, what to do, what is safe.

### 2.6 Incomplete geographical coverage (scenario 7)

Completion with gaps is **green success plus a separate amber coverage disclosure** — never amber-dimmed success and never hidden gaps:

> **Coverage disclosure — read before relying on this map.** Land cover is not available for part of the northern area… Nothing was substituted or guessed: missing products are absent, exactly as they will appear in the field, in recordings and in playback.

The second sentence is the #96 honesty contract restated in operator language — playback/Travel Back can never show geography that was absent at capture, so the operator must understand gaps at preparation time. Per-product truth (vectors complete, elevation complete, land cover partial) not a vague percentage (rule 13).

### 2.7 Successful completion (scenario 8)

Completion drops the operator directly into a library view with the new map first, marked *new*, one **Use this map now** action (or automatic use — see Q5). The confirmation states the offline guarantee once, completely: *works entirely offline — viewing, recording, playback and Travel Back included.* Tools silently persist for the next map; there is no "tools management" surface because there is nothing the operator ever needs to do to them (rule 6). Pack replacement, when a future Command version ships a new pinned pack, is the same one-time download flow at next use — invisible in the normal case.

## 3. What does not change

- The four-tab Maps & Layers structure, Import tab, Display tab, My Maps management, deletion protection, export — all untouched.
- Legacy chunked acquisition: stays exactly as it is where it is (the existing flow is the fallback when enhanced rendering is not active). **Open question Q2 on coexistence.**
- Blank canvas / legacy maps / Format 1 import-export — untouched.
- Every existing control, keyboard contract and the rest of the V5.14 shell. No other screen is affected.

## 4. Anti-regression audit (15 rules + applicable historical checks)

| Rule/check | Verdict |
|---|---|
| 1 one function one interface | One Download map action; one-time tools setup inline at point of use, no parallel screens |
| 2 control-intent match | switches binary (terrain), buttons actions, tabs unchanged |
| 3 point of use | tools setup appears inside the exact flow that needs it; no settings-page detour |
| 4 stable geometry | staged progress replaces the form in place; footer actions swap in the same footer, no reflow of shell |
| 5 contained transient UI | everything inside the existing dialog; no popovers added |
| 6 clutter | consent checkbox removed (button carries consent); no tools-management surface; one disclosure per state max |
| 7 separators | none added |
| 8 measured hierarchy | compact 4-stage list, no oversized cards; failure text 2–3 sentences |
| 9 semantic actions | primary affirmative (Download/Resume/Use), neutral dismissive, danger only for Discard |
| 10 drafts preserved | operator's area/radius survive tools setup and interruption; job survives dialog close (existing ledger) |
| 11 no reset of active interactions | progress updates in place; disclosures never re-render closed |
| 12 precise labels | "Download tools · 64 MB", "Stop preparation", "Resume download", "Discard partial download" — verbs with objects |
| 13 operational truth | estimate vs exact bytes distinguished; per-product coverage truth; cache reuse stated, never implied |
| 14 real workflows | scenarios cover cancel/interrupt/failure/offline-import/night; prototype exercises all eight |
| 15 no lost capability | offline tools import retained; legacy acquisition retained; existing resume/approval mechanics reused not replaced |
| Correction 8 (status before evidence) | operator stage first, technical details folded |
| Correction 12/13 (dialog size/reflow) | same 720px modal, fixed footer, no resize across states |
| Correction 14 (compact rows not grey cards) | stages are compact rows; notices are tinted semantic surfaces, not debug prose |

## 5. Questions for the operator (recommendations marked)

1. **Q1 — Trusted-digest pinning.** Compiled into the Command binary (recommended: zero operator burden, independently trusted, satisfies the #97 directive exactly) — versus operator-pasted digest at download time (more explicit, but contradicts "absolutely no GIS knowledge / minimal interaction" and a pasted digest copied from the same channel proves little).
2. **Q2 — Legacy download coexistence.** When enhanced preparation is available, the legacy chunked download moves behind *Advanced · legacy map* in the same tab (recommended — one obvious path, no capability lost); alternatives: keep both visible peer-level (clutter), or remove legacy from this flow entirely (loses the pack-free offline path — not recommended).
3. **Q3 — Tools download source.** Fixed official project release location compiled into Command (recommended). Note this is the first runtime dependency on project release hosting; fully offline operators are unaffected (file import), and failure is honest and retryable.
4. **Q4 — Consent shape.** Button-carries-estimate (recommended: *Download map · about 85 MB* is the informed consent; the cumulative-allowance escalation for overruns is unchanged) — versus retaining the explicit review checkbox as today.
5. **Q5 — Completion behaviour.** One-tap *Use this map now* (recommended — deliberate, matches existing Save & Use semantics) versus automatic activation on completion.
6. **Q6 — Estimate honesty.** Where real numbers cannot be bounded (regional extract size varies by geography), the line shows measured planning estimates with "about" and the allowance mechanism governs (recommended) — versus downloading-then-reporting without an up-front estimate.

## 6. Prototype

`prototype.html` — eight clickable scenarios with day/night: first use, tools download, normal preparation, active progress, interruption, storage failure, gapped completion, ready. All copy in the prototype is proposed production copy, not placeholder. Screenshots in `shots/`.
