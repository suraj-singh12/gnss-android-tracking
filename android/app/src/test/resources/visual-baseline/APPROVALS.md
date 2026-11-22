# Approved visual baselines

Each geometry/pixel baseline file is approved by its exact SHA-256 with a reason.
`VisualBaselineApprovalTest` fails when a baseline changes without a matching line here.

To change a baseline: fix any real defect first; re-record with
`GNSS_VISUAL_BASELINE=record`; inspect the new captures; then replace the affected line
with the new hash and a specific reason, and add the change to the ledger in
`docs/ui-acceptance.md`. Never re-record only to make a failing comparison pass.

| File | SHA-256 | Approval reason |
|---|---|---|
| `day-360x800-1.0.json` | `8a94b656f4f994348bfd4741e58ed6aae486b631a9de578105775adaa05a6c69` | 2026-11-22 Issue #68 operator-approved v3 UI (docs/design/approved/android-v3-simplified-interactive.html): root titles, Tracking home, Settings landing/Appearance, merged Command access and Tracking policy, Diagnostics Health/Activity/Tools, 48dp scrolling Activity chips, wrapping SOS caption; new review-authorization dialog; captures reviewed. |
| `day-360x800-1.5.json` | `1837be3365b12f23d91ff519bea55e3f6216b59baad5a67e8d66fa252cf21eb7` | 2026-11-22 Issue #68 operator-approved v3 UI (docs/design/approved/android-v3-simplified-interactive.html): root titles, Tracking home, Settings landing/Appearance, merged Command access and Tracking policy, Diagnostics Health/Activity/Tools, 48dp scrolling Activity chips, wrapping SOS caption; new review-authorization dialog; captures reviewed. |
| `day-480x960-1.0.json` | `d32037f893a300b6c84d813b0487d033d83acbf71850ae388b54a288530f45d3` | 2026-11-22 Issue #68 operator-approved v3 UI (docs/design/approved/android-v3-simplified-interactive.html): root titles, Tracking home, Settings landing/Appearance, merged Command access and Tracking policy, Diagnostics Health/Activity/Tools, 48dp scrolling Activity chips, wrapping SOS caption; new review-authorization dialog; captures reviewed. |
| `night-360x640-1.3.json` | `818c158198218eba4fa0d247eda44dea88a8087dba74844016e772b34919c87b` | 2026-11-22 Issue #68 operator-approved v3 UI (docs/design/approved/android-v3-simplified-interactive.html): root titles, Tracking home, Settings landing/Appearance, merged Command access and Tracking policy, Diagnostics Health/Activity/Tools, 48dp scrolling Activity chips, wrapping SOS caption; new review-authorization dialog; captures reviewed. |
| `night-360x800-1.0.json` | `183996f65415140b83783d41f774f57775a4decea44bc3f3d21ea9170b1b3a59` | 2026-11-22 Issue #68 operator-approved v3 UI (docs/design/approved/android-v3-simplified-interactive.html): root titles, Tracking home, Settings landing/Appearance, merged Command access and Tracking policy, Diagnostics Health/Activity/Tools, 48dp scrolling Activity chips, wrapping SOS caption; new review-authorization dialog; captures reviewed. |
| `night-800x360-1.0.json` | `9d80840be3880d50d784a0e19c2ea055b3326e1fa1a572cd59a45b475ac1e910` | 2026-11-22 Issue #68 operator-approved v3 UI (docs/design/approved/android-v3-simplified-interactive.html): root titles, Tracking home, Settings landing/Appearance, merged Command access and Tracking policy, Diagnostics Health/Activity/Tools, 48dp scrolling Activity chips, wrapping SOS caption; new review-authorization dialog; captures reviewed. |
