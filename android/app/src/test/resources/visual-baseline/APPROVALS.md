# Approved visual baselines

Each geometry/pixel baseline file is approved by its exact SHA-256 with a reason.
`VisualBaselineApprovalTest` fails when a baseline changes without a matching line here.

To change a baseline: fix any real defect first; re-record with
`GNSS_VISUAL_BASELINE=record`; inspect the new captures; then replace the affected line
with the new hash and a specific reason, and add the change to the ledger in
`docs/ui-acceptance.md`. Never re-record only to make a failing comparison pass.

| File | SHA-256 | Approval reason |
|---|---|---|
| `day-360x800-1.0.json` | `4c204154717a7b55604d2d932a41d888f0e92f5dfb427257109f1ee28f8e4793` | 2026-11-21 operator spacing fix (PR #65): 12dp gap between Tracking policy Adaptive storage card and Policy details row; only tracking-policy geometry/pixels changed. |
| `day-360x800-1.5.json` | `d5a495f1cd34cab3c6a0ed04d8c389662a56b23e39a4a0c5d63ee95cd11de30d` | 2026-11-21 operator spacing fix (PR #65): 12dp gap between Tracking policy Adaptive storage card and Policy details row; only tracking-policy geometry/pixels changed. |
| `day-480x960-1.0.json` | `fc49fce7e1871758c2ad790ca1eced66b7dc4841742705cf8e92a92dfd509912` | 2026-11-21 operator spacing fix (PR #65): 12dp gap between Tracking policy Adaptive storage card and Policy details row; only tracking-policy geometry/pixels changed. |
| `night-360x640-1.3.json` | `3006de1fe5abf1911156b61764ddff8cf8d53df5567c350cf956fbadee5587ca` | 2026-11-21 operator spacing fix (PR #65): 12dp gap between Tracking policy Adaptive storage card and Policy details row; only tracking-policy geometry/pixels changed. |
| `night-360x800-1.0.json` | `9082570c58a9fe6c5f09789582db1c36af5b6892663e5ca20a78f30e6f0c0342` | 2026-11-21 operator spacing fix (PR #65): 12dp gap between Tracking policy Adaptive storage card and Policy details row; only tracking-policy geometry/pixels changed. |
| `night-800x360-1.0.json` | `85c719bef7dbcbcca0f694f3002f93ed12ac4758e94f0265a90fd14c19d9dd3f` | 2026-11-21 operator spacing fix (PR #65): 12dp gap between Tracking policy Adaptive storage card and Policy details row; only tracking-policy geometry/pixels changed. |
