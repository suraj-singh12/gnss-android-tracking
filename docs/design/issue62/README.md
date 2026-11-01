# GNSS UI/UX second-pass design candidates — awaiting approval

Issue: [#62](https://github.com/suraj-singh12/gnss-android-tracking/issues/62). Mandatory guidance: [AGENTS.md](../../../AGENTS.md), [design-system.md](../../design-system.md), [ui-acceptance.md](../../ui-acceptance.md), [ui-preservation-matrix.md](../../ui-preservation-matrix.md).

## Current-source provenance — do not substitute older screenshots

- **Application source inspected:** draft [PR #61](https://github.com/suraj-singh12/gnss-android-tracking/pull/61), revision `dfe51ffeb89ac43b6d1594963050c55b63a4d6fa` — `command/web/index.html`, `style.css`, `app.js`; Android `MainActivity.kt`, `NativeUi.kt`, `OperationalUi.kt`.
- **Integration baseline at inspection:** `6f435942394b805d5e81129bafa3607a41ae0290`; PR #61 is **not** merged. Re-fetch both branch heads before future tasks.
- **Evidence limitation:** The latest application was inspected as source; the live GNSS application was **not** freshly run/captured here. No screenshots from closed/old issues are claimed as present-state evidence. Prototype screenshots are fresh renders of the exact proposed HTML, not GNSS runtime captures.

## Current second-pass v2 interactive proposals

1. [Command — Travel Back / Live / range editor / diagnostics / narrow layout](command-travel-back-interactive.html). Replaces the rejected first-pass file at the same path. The compact capture-time summary remains visible. Edit range and filters in a bounded side sheet (mobile full-height sheet). Raw diagnostics are secondary. Map and playback retain primary attention; no additional permanent duplicate Travel Back heading.
2. [Android — Tracking / Settings / Party & Command / Diagnostics](android-native-interactive.html). Replaces the rejected first-pass file at the same path. Tracking GNSS and Command receipt are separate, with a protected SOS dock retained. Settings use focused destinations; diagnostics distinguish technical health and evidence.
3. Proposed refinements for Parties density and selected-party details remain **ideas**, not operator-approved work.

**Operator review status: UNAPPROVED.** These files are intended screen layouts, not generic example images, running GNSS UIs, Android native screens or production code. Sample observations are simulated; buttons may navigate within the mock or explain a retained behavior rather than perform live GNSS, recording, remote commands, SOS or acknowledgement.

### What was corrected after first-pass rejection

- Removed repeated operational labels/status blocks and unnecessary permanent description text.
- Replaced the large inline Travel Back accordion, filters and raw `<pre>` data with a bounded dedicated editor and secondary diagnostics.
- Unified component radius, typography, spacing and control styling for each platform.
- Adapted compact Command modes to visible short labels while retaining full semantic roles; fixed accidental label clipping at 320 px.
- Kept Command's main canvas, history transport, Parties, Details and SOS reachable; Android keeps native-like three destinations and sticky protected SOS control.

### Prototype verification (not app acceptance)

Fresh headless Chromium renders of these exact HTML files at Command 1440×900 and 390×844, Android 390×844; additional layout/overflow checks at 320×640, 360×720, 480×900, 760×650, 1024×768 and 1440×900. Horizontal document overflow = 0 and visible button overflow = 0 at these sizes after a 320 px correction. Browser interaction checks exercised Command range open/close, Parties open, selection/close and Android Settings → configuration → Cancel and Diagnostics navigation. Android persistent SOS and bottom navigation remained within the phone viewport at compact phone sizes (320–480 px). Android 760–1024 desktop-sized responsive adaptations and native device font scaling were **not accepted**; those require platform-targeted work. No backend/field acceptance or application UI modification was performed.

## Review/implementation rule

Discuss and approve individual screen/flow proposals by version. Only after explicit approval may a separate issue implement that scope using the repository's design-system reuse rules. No design choice here authorizes a merge or any modification to `main`. Existing stored data, SOS, safety and offline behavior remain authoritative.

Design research: [Apple HIG layout](https://developer.apple.com/design/human-interface-guidelines/layout), [disclosure controls](https://developer.apple.com/design/human-interface-guidelines/disclosure-controls), [popovers](https://developer.apple.com/design/human-interface-guidelines/popovers/), and [Android adaptive navigation](https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns).
