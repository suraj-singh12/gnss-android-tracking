package org.gnss.tracking

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class HistoryDeliveryTest {
    private val clock = FakeClock(100000)
    private val context
        get() = ApplicationProvider.getApplicationContext<Application>()

    private fun value(n: Int, session: TrackingSession): CapturedObservation {
        val at = 100000 + n * 1000L
        return CapturedObservation(
            Protocol.newId(),
            at,
            Fix(utc(at), 0, 28.0, 77.0, 1.0, null, null, null, null),
            Health(gnss_status = "fix"),
            null,
            at * 1000000,
            kotlinx.coroutines.CompletableDeferred(session),
        )
    }

    @Test
    fun identitiesDeduplicateMeasurementsNotCoordinatesAndSessionSequenceSurvives() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TrackingDatabase::class.java).build()
        try {
            val r = Repository(db, clock)
            val session = r.beginTracking()
            val first = value(1, session)
            val a = r.saveObservation(first)
            val duplicate = r.saveObservation(first.copy(id = Protocol.newId()))
            assertEquals(a, duplicate)
            val b = r.saveObservation(value(2, session))
            assertNotEquals(a.messageId, b.messageId)
            assertEquals(listOf(1L, 2L), db.dao().all().map { it.observationSequence })
            assertEquals(session, r.beginTracking())
            r.snapshot(clock.now, null, Health())
            assertEquals(2L, r.state().observationSequence)
            r.endTracking()
            val next = r.beginTracking()
            assertNotEquals(session.id, next.id)
            assertEquals(1L, r.saveObservation(value(3, next)).observationSequence)
            // A delayed writer from the ended session preserves that session and its own sequence.
            assertEquals(3L, r.saveObservation(value(4, session)).observationSequence)
            assertEquals(1L, r.state().observationSequence)
        } finally {
            db.close()
        }
    }

    @Test
    fun committedHistoryNeedsNoCurrentACKAndRecoveryBlocksDoNotMix() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TrackingDatabase::class.java).build()
        try {
            val r = Repository(db, clock)
            val session = r.beginTracking()
            clock.now = 200000
            val rows = (1..9).map { r.saveObservation(value(it, session)) }
            assertEquals(rows.take(2), r.historical(clock.now, 2400))
            suspend fun ack(row: Outbound) =
                Receipt(
                    r.state().deviceId,
                    row.messageId,
                    row.sequence,
                    utc(clock.now),
                    RemoteConfig(Protocol.newId(), 0, null),
                    null,
                )
            r.accept(rows[3], ack(rows[3]), 0)
            r.accept(rows[4], ack(rows[4]), 0)
            assertEquals(rows.take(3), r.historical(clock.now, 20000))
            rows.take(3).forEach { r.accept(it, ack(it), 0) }
            assertEquals(rows.drop(5), r.historical(clock.now, 20000))
        } finally {
            db.close()
        }
    }

    @Test
    fun adaptationUsesCompleteObservationsAndBoundedGrowth() {
        val a = BatchAdaptation()
        assertEquals(0, a.target)
        listOf(500, 1000, 2000, 4000).forEach {
            a.acknowledged()
            assertEquals(it, a.target)
        }
        a.acknowledged()
        assertEquals(4000, a.target)
        a.acknowledged()
        assertEquals(8000, a.target)
        a.reachableFailure()
        assertEquals(4000, a.target)
        repeat(10) { a.reachableFailure() }
        assertEquals(0, a.target)
        a.acknowledged()
        a.reset()
        assertEquals(0, a.target)
    }

    @Test
    fun batchACKMustMatchEveryOriginalMessageBeforeMarkingAnyDelivered() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TrackingDatabase::class.java).build()
        try {
            val r = Repository(db, clock)
            val session = r.beginTracking()
            val rows = (1..2).map { r.saveObservation(value(it, session)) }
            val bad =
                Receipt(
                    r.state().deviceId,
                    Protocol.newId(),
                    rows[1].sequence,
                    utc(clock.now),
                    null,
                    "unavailable",
                )
            val good =
                Receipt(
                    r.state().deviceId,
                    rows[0].messageId,
                    rows[0].sequence,
                    utc(clock.now),
                    null,
                    "unavailable",
                )
            assertTrue(runCatching { r.acceptBatch(rows, listOf(good, bad), 0) }.isFailure)
            assertTrue(db.dao().all().all { it.deliveredAt == null })
            assertEquals(
                rows.map { it.messageId },
                HistoryProtocol.batch(rows).let {
                    Protocol.parse(it).getAsJsonArray("messages").map { m ->
                        m.asJsonObject["message_id"].asString
                    }
                },
            )
        } finally {
            db.close()
        }
    }

    private fun ack(json: String): String {
        val m = Protocol.decodeMessage(json)
        return """{"protocol_version":1,"device_id":"${m.device_id}","message_id":"${m.message_id}","sequence":${m.sequence},"result":"stored","received_at":"${utc(clock.now)}","config":{"authority_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","version":0,"reporting_interval_override_s":null}}"""
    }

    private fun batchACK(json: String): Response {
        val a =
            Protocol.parse(json).getAsJsonArray("messages").joinToString(",") { ack(it.toString()) }
        return Response(200, """{"batch_version":1,"acks":[$a]}""")
    }

    @Test
    fun senderGrowsWithoutWaitingReducesReachableFailureAndResetsAfterWifiReturn() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TrackingDatabase::class.java).build()
        try {
            val r = Repository(db, clock)
            r.settings("Alpha", "Field", "http://192.168.1.2", 30)
            val session = r.beginTracking()
            val rows = (1..80).map { r.saveObservation(value(it, session)) }
            clock.now = 1000000 // Stale history is eligible even with no live fix or status ACK.
            var wifi = false
            var failure = false
            val sizes = mutableListOf<Int>()
            val counts = mutableListOf<Int>()
            val transport =
                object : Transport {
                    override fun wifiAvailable() = wifi

                    override suspend fun capabilities(endpoint: String) =
                        Response(200, """{"historical_batch_versions":[1]}""")

                    override suspend fun post(endpoint: String, immutableJson: String) =
                        error("Expected negotiated batch")

                    override suspend fun history(endpoint: String, json: String): Response {
                        sizes.add(json.toByteArray().size)
                        counts.add(Protocol.parse(json).getAsJsonArray("messages").size())
                        if (failure) {
                            failure = false
                            return Response(503, "{}")
                        }
                        return batchACK(json)
                    }
                }
            val sender =
                Sender(r, transport, clock) {
                    error("Native history must not manufacture a current snapshot")
                }
            sender.step()
            assertTrue(sizes.isEmpty())
            wifi = true
            sender.connectivityRestored()
            repeat(6) { sender.step() }
            assertEquals(1, counts.first())
            assertTrue(counts.any { it > 1 })
            failure = true
            sender.step()
            val failedSize = sizes.last()
            assertTrue(db.dao().all().any { it.deliveredAt == null })
            clock.now += 30000
            sender.step()
            assertTrue(sizes.last() <= failedSize)
            assertTrue(db.dao().all().none { it.quarantined })
            wifi = false
            sender.step()
            wifi = true
            sender.connectivityRestored()
            sender.step()
            assertEquals(1, counts.last())
            repeat(100) { sender.step() }
            assertTrue(db.dao().all().all { it.deliveredAt != null })
            assertEquals(rows.map { it.messageId }, db.dao().all().map { it.messageId })
        } finally {
            db.close()
        }
    }

    @Test
    fun indexedPermanentFailurePreservesBadRowAndSynchronizesOtherObservations() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TrackingDatabase::class.java).build()
        try {
            val r = Repository(db, clock)
            r.settings("Alpha", "Field", "http://192.168.1.2", 30)
            val session = r.beginTracking()
            val rows = (1..6).map { r.saveObservation(value(it, session)) }
            clock.now = 1000000
            var reject = true
            val transport =
                object : Transport {
                    override suspend fun capabilities(endpoint: String) =
                        Response(200, """{"historical_batch_versions":[1]}""")

                    override suspend fun post(endpoint: String, immutableJson: String) =
                        error("Expected batch")

                    override suspend fun history(endpoint: String, json: String): Response {
                        if (reject) {
                            reject = false
                            return Response(
                                422,
                                """{"batch_version":1,"entry_index":0,"observation_id":"${rows.first().messageId}","error":"invalid_observation","message":"invalid identity"}""",
                            )
                        }
                        return batchACK(json)
                    }
                }
            val sender = Sender(r, transport, clock) { error("No new snapshot") }
            repeat(20) { sender.step() }
            val bad = db.dao().row(rows.first().sequence)!!
            assertTrue(bad.quarantined)
            assertEquals(rows.first().json, bad.json)
            assertEquals("invalid_observation: invalid identity", bad.error)
            assertNull(bad.deliveredAt)
            rows.drop(1).forEach { assertNotNull(db.dao().row(it.sequence)!!.deliveredAt) }
            val progress =
                Protocol.decodeMessage(r.snapshot(clock.now, null, Health()).json)
                    .history_progress!!
            assertEquals(listOf(1L), progress.unresolved_sequences)
            assertEquals(0L, progress.pending_observations)
        } finally {
            db.close()
        }
    }

    @Test
    fun ackedNewestObservationCannotRelabelOlderPendingAsLive() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TrackingDatabase::class.java).build()
        try {
            val r = Repository(db, clock)
            val session = r.beginTracking()
            val old = r.saveObservation(value(1, session))
            val newest = r.saveObservation(value(2, session))
            clock.now = 103000
            assertEquals(newest.messageId, r.live(clock.now)!!.messageId)
            r.accept(
                newest,
                Protocol.receipt(ack(newest.json), Protocol.decodeMessage(newest.json)),
                0,
            )
            assertNull(r.live(clock.now))
            assertEquals(old.messageId, r.historical(clock.now, 0).single().messageId)
        } finally {
            db.close()
        }
    }

    @Test
    fun explicitNewOperationClosesPreviousSessionWhileStickyResumePreservesIt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TrackingDatabase::class.java).build()
        try {
            val r = Repository(db, clock)
            val first = r.beginTracking(90000, resumeExisting = false)
            r.noteCollectionLoss(kotlinx.coroutines.CompletableDeferred(first))
            val resumed = r.beginTracking(110000, resumeExisting = true)
            assertEquals(first, resumed)
            assertEquals(1L, r.state().knownCollectionLoss)
            val second = r.beginTracking(120000, resumeExisting = false)
            assertNotEquals(first.id, second.id)
            assertEquals(120000L, db.dao().session(first.id)!!.endedMillis)
            assertEquals(1L, db.dao().session(first.id)!!.knownCollectionLoss)
            assertEquals(0L, r.state().knownCollectionLoss)
            r.noteCollectionLoss(kotlinx.coroutines.CompletableDeferred(first))
            assertEquals(2L, db.dao().session(first.id)!!.knownCollectionLoss)
            assertEquals(0L, r.state().knownCollectionLoss)
        } finally {
            db.close()
        }
    }

    @Test
    fun sessionsRemainOldestFirstAcrossClockRollback() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TrackingDatabase::class.java).build()
        try {
            val repo = Repository(db, clock)
            val first = repo.beginTracking(resumeExisting = false)
            val a = repo.saveObservation(value(1, first))
            repo.endTracking()
            clock.now -= 50000
            val next = repo.beginTracking(resumeExisting = false)
            repo.saveObservation(value(2, next))
            val delayed = repo.saveObservation(value(3, first))
            assertEquals(listOf(a, delayed), repo.historical(200000, 20000))
        } finally {
            db.close()
        }
    }

    @Test
    fun lateOlderCallbackIsHistoricalAndCannotDisplacePendingCurrentObservation() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TrackingDatabase::class.java).build()
        try {
            val repo = Repository(db, clock)
            val session = repo.beginTracking()
            val current = repo.saveObservation(value(5, session))
            val older = repo.saveObservation(value(1, session))
            clock.now = 106000
            assertEquals(current.messageId, repo.live(clock.now)!!.messageId)
            assertEquals(listOf(current, older), repo.historical(clock.now, 20000))
        } finally {
            db.close()
        }
    }
}
