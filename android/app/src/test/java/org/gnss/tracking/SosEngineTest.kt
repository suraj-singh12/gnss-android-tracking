package org.gnss.tracking

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SosEngineTest {
    private lateinit var context: Context
    private lateinit var db: TrackingDatabase
    private lateinit var repository: Repository
    private val clock = FakeClock()
    private val latest = LatestLocation(clock)
    private val events = mutableListOf<DiagnosticEntry>()
    private var wifi = false

    private fun evidence(kind: DiagnosticEvent, e: SosEvidence) {
        events.add(DiagnosticEntry(kind, utc(clock.now), clock.now, null, sos = e))
    }

    private fun engine() =
        SosEngine(repository, latest, { Health(wifi_connected = wifi) }, clock, ::evidence)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("tracking.db")
        open()
    }

    private fun open() {
        db = TrackingDatabase.open(context)
        repository = Repository(db, clock)
    }

    @After
    fun close() {
        db.close()
        context.deleteDatabase("tracking.db")
    }

    private fun ack(json: String, wrong: Boolean = false): Response {
        val m = Protocol.decodeMessage(json)
        return Response(
            200,
            """{"protocol_version":1,"device_id":"${m.device_id}","message_id":"${if(wrong) Protocol.newId() else m.message_id}","sequence":${m.sequence},"result":"stored","received_at":"${utc(clock.now)}","config":{"authority_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","version":0,"reporting_interval_override_s":null}}""",
        )
    }

    @Test
    fun offlineCreationHasOneDurableIdentityAndNoInventedFix() = runBlocking {
        val result = engine().activate(SosTrigger.SCREEN)
        val m = Protocol.decodeMessage(result.row.json)
        assertTrue(result.created)
        assertEquals(m.message_id, m.sos!!.event_id)
        assertEquals(utc(clock.now), m.sos.triggered_at)
        assertEquals(repository.state().deviceId, m.device_id)
        assertNull(m.fix)
        assertNull(db.dao().row(result.row.sequence)!!.deliveredAt)
        assertTrue(events.any { it.event == DiagnosticEvent.SOS_NETWORK_UNAVAILABLE })
        assertTrue(
            events.any {
                it.event == DiagnosticEvent.SOS_SAVED_LOCALLY && it.sos!!.durationMs == 0L
            }
        )
    }

    @Test
    fun unknownHealthDoesNotFabricateOfflineEvidence() = runBlocking {
        val saved =
            SosEngine(repository, latest, { error("health unavailable") }, clock, ::evidence)
                .activate(SosTrigger.SCREEN)
        assertNull(Protocol.decodeMessage(saved.row.json).health.wifi_connected)
        assertFalse(events.any { it.event == DiagnosticEvent.SOS_NETWORK_UNAVAILABLE })
        assertTrue(saved.row.sosDescription(null, true).contains("status unavailable"))
    }

    @Test
    fun duplicateAdaptersCoalesceAcrossReopenAndDeliberateRepeatIsDistinct() = runBlocking {
        val first = engine().activate(SosTrigger.SCREEN)
        clock.now += 100
        assertEquals(first.row, engine().activate(SosTrigger.VOLUME_UP).row)
        // An earlier detected press can reach the transaction after the later one.
        assertEquals(
            first.row,
            engine().activate(SosTrigger.SCREEN, triggered = clock.now - 200).row,
        )
        db.close()
        open()
        assertFalse(engine().activate(SosTrigger.SCREEN).created)
        clock.now += SosEngine.DEBOUNCE_MS
        val second = engine().activate(SosTrigger.VOLUME_UP)
        assertTrue(second.created)
        assertNotEquals(first.row.messageId, second.row.messageId)
        assertEquals(2, db.dao().all().size)
        assertEquals(first.row.json, db.dao().row(first.row.sequence)!!.json)
    }

    @Test
    fun concurrentEnginesShareTransactionalDebounce() = runBlocking {
        val rows =
            (1..8)
                .map {
                    async(Dispatchers.IO) {
                        SosEngine(repository, latest, { Health() }, clock)
                            .activate(SosTrigger.SCREEN)
                    }
                }
                .awaitAll()
        assertEquals(1, rows.count { it.created })
        assertEquals(1, rows.map { it.row.messageId }.toSet().size)
        assertEquals(1L, repository.state().sequence)
    }

    @Test
    fun storageFailureRollsBackSequenceAndPreservesFailureEvidence() = runBlocking {
        val original = repository.state()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER full BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT,'full'); END"
        )
        assertTrue(runCatching { engine().activate(SosTrigger.SCREEN) }.isFailure)
        assertEquals(original.sequence, repository.state().sequence)
        assertTrue(db.dao().all().isEmpty())
        assertTrue(events.any { it.event == DiagnosticEvent.SOS_SAVE_FAILED })
        assertFalse(events.any { it.event == DiagnosticEvent.SOS_SAVED_LOCALLY })
    }

    @Test
    fun staleLastKnownFixRetainsTimeAgeAndUnavailableHealth() = runBlocking {
        latest.enabled = true
        val f = Fix(utc(clock.now), 0, 12.345, 78.901, 4.0, null, null, null, null)
        latest.update(Observation(f, clock.now))
        clock.now += 45000
        assertNull(latest.currentFix())
        val m = Protocol.decodeMessage(engine().activate(SosTrigger.SCREEN).row.json)
        assertEquals(f.observed_at, m.fix!!.observed_at)
        assertEquals(45000L, m.fix.fix_age_ms)
        assertEquals("unknown", m.health.gnss_status)
        assertEquals(utc(clock.now), m.sos!!.triggered_at)
    }

    @Test
    fun freshAndDisabledLastKnownObservationsRemainTruthful() = runBlocking {
        latest.enabled = true
        latest.update(
            Observation(Fix(utc(clock.now), 0, 0.0, 0.0, null, null, null, null, null), clock.now)
        )
        val first = Protocol.decodeMessage(engine().activate(SosTrigger.SCREEN).row.json)
        assertEquals("fix", first.health.gnss_status)
        assertNotNull(first.fix)
        latest.enabled = false
        clock.now += 4000
        val second = Protocol.decodeMessage(engine().activate(SosTrigger.SCREEN).row.json)
        assertEquals("disabled", second.health.gnss_status)
        assertEquals(4000L, second.fix!!.fix_age_ms)
    }

    @Test
    fun preemptsHundredsOfReportsWithoutRecoveryCapture() = runBlocking {
        repository.settings("A", "B", "http://192.168.1.2", 86400)
        repeat(250) { repository.snapshot(clock.now - 86400000, null, Health()) }
        val sos = engine().activate(SosTrigger.SCREEN).row
        val sent = mutableListOf<String>()
        val sender =
            Sender(
                repository,
                Transport { _, json ->
                    val m = Protocol.decodeMessage(json)
                    assertEquals(json, db.dao().row(m.sequence)!!.json)
                    sent.add(json)
                    ack(json)
                },
                clock,
                ::evidence,
            ) {
                error("SOS must not wait for reporting storage")
            }
        sender.step()
        assertEquals(listOf(sos.json), sent)
        assertNotNull(db.dao().row(sos.sequence)!!.deliveredAt)
        assertTrue(events.any { it.event == DiagnosticEvent.SOS_PREEMPTED_BACKLOG })
    }

    @Test
    fun bypassesOrdinaryBackoffAndFailedSosAllowsTracking() = runBlocking {
        repository.settings("A", "B", "http://192.168.1.2", 10)
        repository.snapshot(clock.now, null, Health())
        val sent = mutableListOf<String>()
        var failing = true
        val sender =
            Sender(
                repository,
                Transport { _, json ->
                    sent.add(Protocol.decodeMessage(json).type)
                    if (failing) throw java.io.IOException("offline")
                    ack(json)
                },
                clock,
            ) {
                repository.snapshot(clock.now, null, Health())
            }
        sender.step()
        val sos = engine().activate(SosTrigger.SCREEN).row
        sender.step()
        assertEquals(listOf("status", "sos"), sent)
        assertNull(db.dao().row(sos.sequence)!!.deliveredAt)
        clock.now += 1000
        failing = false
        sender.step()
        sender.step()
        assertEquals(listOf("status", "sos", "sos", "status"), sent)
        clock.now += 4000
        val another = engine().activate(SosTrigger.SCREEN).row
        repository.snapshot(clock.now, null, Health())
        val independent =
            Sender(
                repository,
                Transport { _, json ->
                    sent.add(Protocol.decodeMessage(json).type)
                    if (Protocol.decodeMessage(json).type == "sos") Response(503, "unavailable")
                    else ack(json)
                },
                clock,
            ) {
                repository.snapshot(clock.now, null, Health())
            }
        independent.step()
        independent.step()
        assertEquals(listOf("sos", "status"), sent.takeLast(2))
        assertNull(db.dao().row(another.sequence)!!.deliveredAt)
    }

    @Test
    fun wrongAckTimeoutAndReopenKeepIdentityUntilMatchingAck() = runBlocking {
        repository.settings("A", "B", "http://192.168.1.2", 10)
        val sos = engine().activate(SosTrigger.SCREEN).row
        val sender =
            Sender(repository, Transport { _, json -> ack(json, true) }, clock, ::evidence) {
                repository.snapshot(clock.now, null, Health())
            }
        sender.step()
        assertNull(db.dao().row(sos.sequence)!!.deliveredAt)
        assertTrue(events.any { it.event == DiagnosticEvent.SOS_TRANSPORT_ACK_REJECTED })
        db.close()
        open()
        assertEquals(sos.json, db.dao().row(sos.sequence)!!.json)
        clock.now += 1000
        val lost =
            Sender(
                repository,
                Transport { _, _ -> throw java.net.SocketTimeoutException() },
                clock,
            ) {
                repository.snapshot(clock.now, null, Health())
            }
        lost.step()
        assertNull(db.dao().row(sos.sequence)!!.deliveredAt)
        clock.now += 2000
        val recovered =
            Sender(repository, Transport { _, json -> ack(json) }, clock) {
                repository.snapshot(clock.now, null, Health())
            }
        recovered.step()
        assertNotNull(db.dao().row(sos.sequence)!!.deliveredAt)
        assertEquals(sos.json, db.dao().row(sos.sequence)!!.json)
    }

    @Test
    fun trackingStopAndConfigCannotDeleteOrRewriteSos() = runBlocking {
        val sos = engine().activate(SosTrigger.SCREEN).row
        repository.settings("Changed", "Team", "http://192.168.1.3", 5)
        repository.edit { it.copy(tracking = false) }
        repository.edit { it.copy(tracking = true) }
        assertEquals(sos.json, db.dao().row(sos.sequence)!!.json)
        assertEquals(sos.sequence, repository.next(clock.now)!!.sequence)
        assertEquals("Party", Protocol.decodeMessage(sos.json).party.id)
    }

    @Test
    fun privacySafeExportIncludesAutomaticEvidenceAndConservativeVerdicts() = runBlocking {
        val sos = engine().activate(SosTrigger.SCREEN).row
        evidence(
            DiagnosticEvent.SOS_PREEMPTED_BACKLOG,
            SosEvidence(sosReference(sos.messageId), competingReports = 1),
        )
        evidence(
            DiagnosticEvent.SOS_TRANSPORT_ACK_ACCEPTED,
            SosEvidence(sosReference(sos.messageId)),
        )
        evidence(DiagnosticEvent.SOS_RESTORED, SosEvidence(sosReference(sos.messageId)))
        val directory = java.nio.file.Files.createTempDirectory("sos-evidence").toFile()
        try {
            val journal = DiagnosticJournal(directory)
            events.forEach(journal::event)
            val out = ByteArrayOutputStream()
            journal.export(out, DiagnosticBuild("test", 1, 28))
            val exported = mutableMapOf<String, String>()
            ZipInputStream(out.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    exported[e.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            val report = exported["sos-report.json"]!!
            assertTrue(report.contains("SOS saved locally"))
            assertTrue(report.contains("PASS"))
            assertTrue(report.contains("INCONCLUSIVE"))
            assertFalse(report.contains(sos.messageId))
            assertFalse(report.contains(repository.state().deviceId))
            assertFalse(report.contains("latitude"))
            val again = ByteArrayOutputStream()
            DiagnosticJournal(directory).export(again, DiagnosticBuild("test", 1, 28))
            assertTrue(again.size() > 0)
        } finally {
            directory.deleteRecursively()
        }
    }
}
