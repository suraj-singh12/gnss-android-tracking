package org.gnss.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedGnssSyncProgressTest {
    @Test
    fun expandingQueueRecalculatesTotalAndCanLowerPercentage() {
        val initial = savedGnssSyncProgress(50, 50, 1000)
        assertEquals(0, initial.percentage)
        assertEquals(1000L, initial.total)

        val partlySent = savedGnssSyncProgress(50, 250, 800)
        assertEquals(200L, partlySent.acknowledged)
        assertEquals(1000L, partlySent.total)
        assertEquals(20, partlySent.percentage)

        // 400 more GNSS observations are saved while the sender is working.
        val expanded = savedGnssSyncProgress(50, 250, 1200)
        assertEquals(1400L, expanded.total)
        assertEquals(1200L, expanded.pending)
        assertEquals(14, expanded.percentage)
    }

    @Test
    fun pendingUnresolvedHistoryPreventsFalseCompletion() {
        assertEquals(99, savedGnssSyncProgress(0, 999, 1).percentage)
        assertEquals(100, savedGnssSyncProgress(0, 1000, 0).percentage)
        assertEquals(100, savedGnssSyncProgress(0, 0, 0).percentage)
    }

    @Test
    fun countersResetOrDecreaseWithoutNegativeProgress() {
        val progress = savedGnssSyncProgress(500, 100, 10)
        assertEquals(0L, progress.acknowledged)
        assertEquals(10L, progress.total)
        assertEquals(0, progress.percentage)
    }
}
