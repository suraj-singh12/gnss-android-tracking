# Approved visual baselines

Each geometry/pixel baseline file is approved by its exact SHA-256 with a reason.
`VisualBaselineApprovalTest` fails when a baseline changes without a matching line here.

To change a baseline: fix any real defect first; re-record with
`GNSS_VISUAL_BASELINE=record`; inspect the new captures; then replace the affected line
with the new hash and a specific reason, and add the change to the ledger in
`docs/ui-acceptance.md`. Never re-record only to make a failing comparison pass.

| File | SHA-256 | Approval reason |
|---|---|---|
| `day-360x800-1.0.json` | `099e0faf7528a62316885f0a00adadbcb7a15f2c77f48600c5f2740b7190b03b` | 2026-11-20 V4.6 refinement (Issue #39, PR #65): Tracking Command indicator, Settings/Diagnostics consolidation, Field readiness and Diagnostic tools redesign approved by the operator task; pixel digests added. Reviewed against captures; ledger R-series in docs/ui-acceptance.md. |
| `day-360x800-1.5.json` | `b1e28f666b5b1476b0df7369bdc06866992d0141179e6f1c1d1ce3860d9b776e` | 2026-11-20 V4.6 refinement (Issue #39, PR #65): Tracking Command indicator, Settings/Diagnostics consolidation, Field readiness and Diagnostic tools redesign approved by the operator task; pixel digests added. Reviewed against captures; ledger R-series in docs/ui-acceptance.md. |
| `day-480x960-1.0.json` | `618946befa312739bcd03a9a872e423acaf7f8693578f8edc266fe7f079fb318` | 2026-11-20 V4.6 refinement (Issue #39, PR #65): Tracking Command indicator, Settings/Diagnostics consolidation, Field readiness and Diagnostic tools redesign approved by the operator task; pixel digests added. Reviewed against captures; ledger R-series in docs/ui-acceptance.md. |
| `night-360x640-1.3.json` | `933167e0d4130d1595b1358aa01559daa20eacb702b1b51289f588a5547b0a43` | 2026-11-20 V4.6 refinement (Issue #39, PR #65): Tracking Command indicator, Settings/Diagnostics consolidation, Field readiness and Diagnostic tools redesign approved by the operator task; pixel digests added. Reviewed against captures; ledger R-series in docs/ui-acceptance.md. |
| `night-360x800-1.0.json` | `16c9f1853ecfd6c25fa5c1e61eef44ba1691ecd4e0cd8b6db2390bbecacba650` | 2026-11-20 V4.6 refinement (Issue #39, PR #65): Tracking Command indicator, Settings/Diagnostics consolidation, Field readiness and Diagnostic tools redesign approved by the operator task; pixel digests added. Reviewed against captures; ledger R-series in docs/ui-acceptance.md. |
| `night-800x360-1.0.json` | `ca7a27a82fa87042f79562e62cc0922d8d415c97ac3ed663161ab7502232ce4c` | 2026-11-20 V4.6 refinement (Issue #39, PR #65): Tracking Command indicator, Settings/Diagnostics consolidation, Field readiness and Diagnostic tools redesign approved by the operator task; pixel digests added. Reviewed against captures; ledger R-series in docs/ui-acceptance.md. |
