# Approved visual baselines

Each geometry/pixel baseline file is approved by its exact SHA-256 with a reason.
`VisualBaselineApprovalTest` fails when a baseline changes without a matching line here.

To change a baseline: fix any real defect first; re-record with
`GNSS_VISUAL_BASELINE=record`; inspect the new captures; then replace the affected line
with the new hash and a specific reason, and add the change to the ledger in
`docs/ui-acceptance.md`. Never re-record only to make a failing comparison pass.

| File | SHA-256 | Approval reason |
|---|---|---|
| `day-360x800-1.0.json` | `2d9d6a190e284e0e7abf6c4b4459e3292a112f6a493b03679dda799d2e42eed9` | 2026-11-21 V4.6 polish (Issue #39, PR #65, operator task): SOS track removed (progress fills button), compact 44dp field outlines, sans UUIDs, Command access contact row and inert Disabled button removed, Tracking policy reduced to Adaptive storage + Policy details, Command Instructions/Phone Alerts recomposed, fact rows widened. Reviewed against captures; ledger P2-series in docs/ui-acceptance.md. |
| `day-360x800-1.5.json` | `d345239e7d0f3626b4c0cb76e284e1dc3ab93eba985250b37268b39c5084cb45` | 2026-11-21 V4.6 polish (Issue #39, PR #65, operator task): SOS track removed (progress fills button), compact 44dp field outlines, sans UUIDs, Command access contact row and inert Disabled button removed, Tracking policy reduced to Adaptive storage + Policy details, Command Instructions/Phone Alerts recomposed, fact rows widened. Reviewed against captures; ledger P2-series in docs/ui-acceptance.md. |
| `day-480x960-1.0.json` | `6f776a267df51ffef6f1114ae7d288039d990521489501f8f8056a8a738ff28d` | 2026-11-21 V4.6 polish (Issue #39, PR #65, operator task): SOS track removed (progress fills button), compact 44dp field outlines, sans UUIDs, Command access contact row and inert Disabled button removed, Tracking policy reduced to Adaptive storage + Policy details, Command Instructions/Phone Alerts recomposed, fact rows widened. Reviewed against captures; ledger P2-series in docs/ui-acceptance.md. |
| `night-360x640-1.3.json` | `f7173aec87cfd281ca8cb011e1b1ef5feae029daf9e8878a96768e130a9c3ec0` | 2026-11-21 V4.6 polish (Issue #39, PR #65, operator task): SOS track removed (progress fills button), compact 44dp field outlines, sans UUIDs, Command access contact row and inert Disabled button removed, Tracking policy reduced to Adaptive storage + Policy details, Command Instructions/Phone Alerts recomposed, fact rows widened. Reviewed against captures; ledger P2-series in docs/ui-acceptance.md. |
| `night-360x800-1.0.json` | `a9ed79f68473a18bdd3bb1eae2f5188cbb29258a58b13f68b7263c4d00905a73` | 2026-11-21 V4.6 polish (Issue #39, PR #65, operator task): SOS track removed (progress fills button), compact 44dp field outlines, sans UUIDs, Command access contact row and inert Disabled button removed, Tracking policy reduced to Adaptive storage + Policy details, Command Instructions/Phone Alerts recomposed, fact rows widened. Reviewed against captures; ledger P2-series in docs/ui-acceptance.md. |
| `night-800x360-1.0.json` | `bd457ed0c9f6e3645abbb8d206d8dd4ef12c07b4d262d326ef9fbd7479e0ff89` | 2026-11-21 V4.6 polish (Issue #39, PR #65, operator task): SOS track removed (progress fills button), compact 44dp field outlines, sans UUIDs, Command access contact row and inert Disabled button removed, Tracking policy reduced to Adaptive storage + Policy details, Command Instructions/Phone Alerts recomposed, fact rows widened. Reviewed against captures; ledger P2-series in docs/ui-acceptance.md. |
