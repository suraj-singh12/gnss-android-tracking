package org.gnss.tracking

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class RepositoryTest {
    private lateinit var db: TrackingDatabase
    private lateinit var repository: Repository
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("tracking.db")
        open()
    }

    private fun open() {
        db = TrackingDatabase.open(context)
        repository = Repository(db)
    }

    @After
    fun close() {
        db.close()
        context.deleteDatabase("tracking.db")
    }

    private val authority = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    private fun receipt(row: Outbound, config: RemoteConfig = RemoteConfig(authority, 0, null)) =
        Receipt(repositoryId(row), row.messageId, row.sequence, utc(100000), config, null)

    private fun repositoryId(row: Outbound) = Protocol.decodeMessage(row.json).device_id

    @Test
    fun durableIdentityConfigOutboxAndSequenceRestore() = runBlocking {
        val original = repository.state()
        repository.settings("Alpha", "North team", "http://192.168.1.2:8080", 20)
        val first = repository.snapshot(0, null, Health(gnss_status = "no_fix"))
        repository.accept(first, receipt(first, RemoteConfig(authority, 1, 30)), 0 + 1)
        repository.settings("Renamed", "South team", "http://192.168.1.2:8080", 60)
        val pending = repository.snapshot(1000, null, Health())
        db.close()
        open()
        val state = repository.state()
        assertEquals(original.deviceId, state.deviceId)
        assertEquals(2L, state.sequence)
        assertEquals(60, state.localInterval)
        assertEquals(30, state.config().effective_reporting_interval_s)
        assertEquals(pending.json, repository.next(2000)!!.json)
        assertNotNull(db.dao().row(first.sequence)!!.deliveredAt)
        assertEquals(3L, repository.snapshot(2000, null, Health()).sequence)
        repository.accept(
            pending,
            receipt(pending, RemoteConfig(authority, 2, null)),
            state.endpointGeneration,
        )
        assertEquals(60, repository.state().config().effective_reporting_interval_s)
    }

    @Test
    fun allocationAndInsertRollbackTogether() = runBlocking {
        val original = repository.state()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_insert BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'test full disk'); END"
        )
        assertTrue(runCatching { repository.snapshot(0, null, Health()) }.isFailure)
        assertEquals(original.sequence, repository.state().sequence)
        assertEquals(0, db.dao().all().size)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_insert")
        assertEquals(1L, repository.snapshot(0, null, Health()).sequence)
    }

    @Test
    fun concurrentAllocationNeverReusesSequence() = runBlocking {
        repository.state()
        val rows =
            (1..20)
                .map { async(Dispatchers.IO) { repository.snapshot(it.toLong(), null, Health()) } }
                .awaitAll()
        assertEquals((1L..20L).toSet(), rows.map { it.sequence }.toSet())
        assertEquals(20L, repository.state().sequence)
        assertEquals(20, rows.map { it.messageId }.toSet().size)
    }

    @Test
    fun newestThenOldestAndHonestStatus() = runBlocking {
        val rows =
            (1..5).map { repository.snapshot(it.toLong(), null, Health(gnss_status = "no_fix")) }
        assertEquals(5L, repository.next(100)!!.sequence)
        repository.accept(rows.last(), receipt(rows.last()), 0)
        // Remaining newest is historical now. Sender marks current delivery boundary;
        // backlog selection uses oldest after that boundary (covered in sender test).
        assertEquals(1L, repository.next(100, 5)!!.sequence)
        val packet = Protocol.decodeMessage(rows.first().json)
        assertEquals("status", packet.type)
        assertNull(packet.fix)
    }

    @Test
    fun invalidAuthorityIsVisibleButDeliveryPersists() = runBlocking {
        val a = repository.snapshot(1, null, Health())
        repository.accept(a, receipt(a), 0)
        val b = repository.snapshot(2, null, Health())
        repository.accept(b, receipt(b, RemoteConfig(Protocol.newId(), 1, 60)), 0)
        assertEquals(authority, repository.state().authority)
        assertNotNull(repository.state().configError)
        assertNotNull(db.dao().row(b.sequence)!!.deliveredAt)
        repository.settings("A", "B", "http://192.168.1.3", 20, true)
        assertNull(repository.state().authority)
        assertEquals(0L, repository.state().version)
        assertEquals(a.json, db.dao().row(a.sequence)!!.json)
    }

    @Test
    fun remoteVersionsErrorsAndQueuedEchoAreDurable() = runBlocking {
        val initial = repository.snapshot(1, null, Health())
        repository.accept(initial, receipt(initial, RemoteConfig(authority, 2, 30)), 0)
        val historical = repository.snapshot(2, null, Health())
        repository.accept(historical, receipt(historical, RemoteConfig(authority, 1, null)), 0)
        assertEquals(2L, repository.state().version)
        val equal = repository.snapshot(3, null, Health())
        repository.accept(equal, receipt(equal, RemoteConfig(authority, 2, 60)), 0)
        assertEquals(30, repository.state().overrideSeconds)
        assertNotNull(repository.state().configError)
        db.close()
        open()
        assertNotNull(repository.state().configError)
        val resolve = repository.snapshot(4, null, Health())
        repository.accept(resolve, receipt(resolve, RemoteConfig(authority, 2, 30)), 0)
        assertNull(repository.state().configError)
        assertEquals(historical.json, db.dao().row(historical.sequence)!!.json)
        assertEquals(
            30,
            Protocol.decodeMessage(resolve.json).config_state.effective_reporting_interval_s,
        )
    }

    @Test
    fun endpointPauseSurvivesRestartAndOldReplyCannotApply() = runBlocking {
        val pending = repository.snapshot(1, null, Health())
        repository.pauseReceiver("Command returned HTTP 426")
        db.close()
        open()
        assertTrue(repository.state().deliveryPaused)
        repository.settings("A", "B", "http://192.168.1.5", 10)
        assertFalse(repository.state().deliveryPaused)
        repository.fail(pending, "Old receiver failed", 100, true, 0)
        repository.pauseReceiver("Old receiver incompatible", 0)
        assertFalse(repository.state().deliveryPaused)
        assertFalse(db.dao().row(pending.sequence)!!.quarantined)
        repository.accept(pending, receipt(pending), 0)
        assertNull(repository.state().authority)
        assertNull(db.dao().row(pending.sequence)!!.deliveredAt)
        assertTrue(
            runCatching { repository.accept(pending, receipt(pending).copy(sequence = 999), 1) }
                .isFailure
        )
    }

    @Test
    fun operatorRetryPreservesEnrollmentAndImmutablePayload() = runBlocking {
        val a = repository.snapshot(1, null, Health())
        repository.accept(a, receipt(a, RemoteConfig(authority, 1, 30)), 0)
        val pending = repository.snapshot(2, null, Health())
        repository.fail(pending, "Conflict", 1000, true, 0)
        repository.pauseReceiver("Unsupported receiver")
        repository.retryDelivery()
        val state = repository.state()
        assertEquals(authority, state.authority)
        assertEquals(30, state.overrideSeconds)
        assertFalse(state.deliveryPaused)
        assertEquals(pending.json, repository.next(3)!!.json)
        assertFalse(db.dao().row(pending.sequence)!!.quarantined)
    }
}
