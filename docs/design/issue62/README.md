# GNSS first-pass UI proposals — unapproved design work

Issue: [#62](https://github.com/suraj-singh12/gnss-android-tracking/issues/62). Governance + first-pass audit documentation is maintained in `docs/design-system.md`, `docs/ui-acceptance.md` and `docs/ui-preservation-matrix.md`; this folder holds **only the issue-specific interactive design candidates**, not a second design system.

## Candidates (open HTML locally)

- [Command — Travel Back / Live / range editor / diagnostics / narrow layout](command-travel-back-interactive.html). Source visual evidence: pending PR #61 at `dfe51ffeb89ac43b6d1594963050c55b63a4d6fa`, particularly [existing Travel Back screenshot](../issue34/final-review/travel-day-1440.png) and [390 px expanded screenshot](../issue34/final-review/travel-day-390.png). Candidate TB-01.
- [Android — Tracking / Settings / Party & Command / Diagnostics](android-native-interactive.html). Source visual evidence: [#39 Tracking](../../evidence/issue-39/v4-tracking-day.png), [Settings](../../evidence/issue-39/settings.png) and [Diagnostics](../../evidence/issue-39/diagnostics.png). Candidate A-01.

These are **GNSS-specific proposed interfaces**, not generic inspiration images, runtime screenshots or executable GNSS products. Open their downloaded `.html` files in a browser; they run offline with simulated values and lightweight prototype interactions. The operator has not yet approved either. Do **not** implement either UI in the real app without explicit review/approval.

The design principles reference [Apple HIG](https://developer.apple.com/design/human-interface-guidelines/design-principles/), [popover behavior](https://developer.apple.com/design/human-interface-guidelines/popovers/), and temporal map UI patterns from [ArcGIS](https://developers.arcgis.com/net/wpf/sample-code/filter-by-time-extent/). Exact app-specific behavior, native Android conventions, offline safety, SOS and recording authority take precedence over imitation.

## Review questions

1. **Command:** Is a one-line capture range + bounded editor the correct location/interaction? Should the editor be a right sheet or a smaller modal? What technical information stays secondary?
2. **Command:** Should Parties and selected-party details be refined next, preserving all actions and 20+ identities?
3. **Android:** Is the stronger Tracking/Delivery hierarchy clearer without reducing readability? Should Settings remain a landing page with explicit destinations instead of a long editable form?
4. **Both:** Confirm Day/Night, narrow/large-type layouts, complete error/loading/empty states, SOS visibility and all existing functions during implementation.

No runtime UI, tracking, playback, storage or protocol files are touched by this proposal.
