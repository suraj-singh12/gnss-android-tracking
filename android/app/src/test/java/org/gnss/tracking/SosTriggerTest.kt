package org.gnss.tracking

import org.junit.Assert.*
import org.junit.Test

class SosTriggerTest {
    @Test
    fun threeShortPairsTriggerOnceButHeldKeysNeverDo() {
        val counts = mutableListOf<Int>()
        val p = TripleVolumeUp { counts.add(it) }
        repeat(3) { n ->
            assertFalse(p.key(true, true, 0, n * 300L))
            assertEquals(n == 2, p.key(true, false, 0, n * 300L + 80))
        }
        assertEquals(listOf(1, 2, 3), counts)
        assertFalse(p.key(true, false, 0, 1000))
        p.key(true, true, 0, 1100)
        assertFalse(p.key(true, true, 1, 1150))
        assertFalse(p.key(true, false, 0, 1200))
    }

    @Test
    fun slowMixedCanceledHeldAndLifecycleResetCannotCompletePattern() {
        fun press(p: TripleVolumeUp, at: Long, up: Boolean = true): Boolean {
            p.key(up, true, 0, at)
            return p.key(up, false, 0, at + 100)
        }
        val p = TripleVolumeUp()
        assertFalse(press(p, 0))
        assertFalse(press(p, 300))
        assertFalse(press(p, 1600))
        p.reset()
        press(p, 0)
        press(p, 300, false)
        assertFalse(press(p, 600))
        p.reset()
        press(p, 0)
        p.key(true, true, 0, 300)
        assertFalse(p.key(true, false, 0, 400, true))
        assertFalse(press(p, 600))
        p.reset()
        p.key(true, true, 0, 0)
        assertFalse(p.key(true, false, 0, 501))
        p.reset()
        press(p, 0)
        press(p, 300)
        p.reset()
        assertFalse(press(p, 600))
    }

    @Test
    fun missingEvidenceNeverProducesPass() {
        assertFalse(sosReport(emptyList(), 1, 1).toString().contains("PASS"))
    }

    @Test
    fun restorationRequiresALaterDifferentProcessBoundary() {
        val ref = "0123456789abcdef"
        fun entry(kind: DiagnosticEvent, generation: String) =
            DiagnosticEntry(kind, utc(100000), 100000, generation, sos = SosEvidence(ref))
        val saved = entry(DiagnosticEvent.SOS_SAVED_LOCALLY, "first")
        val same = entry(DiagnosticEvent.SOS_RESTORED, "first")
        val restarted = entry(DiagnosticEvent.SOS_RESTORED, "second")
        fun survival(events: List<DiagnosticEntry>): String {
            @Suppress("UNCHECKED_CAST")
            val assessments = sosReport(events, 0, 0)["assessments"] as List<Map<String, Any>>
            return assessments.first { it["scenario"] == "SOS survived restart" }["result"]
                as String
        }
        assertEquals("INCONCLUSIVE", survival(listOf(saved, same)))
        assertEquals("INCONCLUSIVE", survival(listOf(restarted, saved)))
        assertEquals("PASS", survival(listOf(saved, restarted)))
    }
}
