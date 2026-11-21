package org.gnss.tracking

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The approved geometry/pixel baselines can only change together with a reviewed approval
 * line (file name + SHA-256 + reason) in `visual-baseline/APPROVALS.md`.
 */
class VisualBaselineApprovalTest {
    @Test
    fun everyBaselineFileHasARecordedApproval() {
        val problems = VisualBaseline.approvalProblems()
        assertTrue(
            "Unapproved visual baseline change. Review the captures, then record each new hash with " +
                "its reason in APPROVALS.md and the ledger in docs/ui-acceptance.md:\n" +
                problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }
}
