package org.gnss.tracking

import android.Manifest
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TrackingApp::class)
class PendingOutboxDiagnosticsTest {
    private val app
        get() = ApplicationProvider.getApplicationContext<TrackingApp>()

    private suspend fun waitFor(condition: () -> Boolean) =
        withTimeout(10000) {
            while (!condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }

    private suspend fun accept(row: Outbound) {
        app.repository.accept(
            row,
            Receipt(
                Protocol.decodeMessage(row.json).device_id,
                row.messageId,
                row.sequence,
                utc(100000),
                RemoteConfig(Protocol.newId(), 0, null),
                null,
            ),
            0,
        )
    }

    @After
    fun close() = runBlocking {
        app.finishForTests()
        app.repository.db.close()
        app.deleteDatabase("tracking.db")
        Unit
    }

    @Test
    fun roomEnqueueAndAckTransitionsReachZeroWithoutActivity() = runBlocking {
        val cache = PendingOutboxDiagnostics()
        val changes = java.util.concurrent.CopyOnWriteArrayList<Int?>()
        val job =
            launch(Dispatchers.IO) {
                cache.observe(onChange = { changes.add(cache.value) }) {
                    app.repository.dao.pendingCount()
                }
            }
        try {
            waitFor { cache.value == 0 }
            val a = app.repository.snapshot(0, null, Health())
            waitFor { cache.value == 1 }
            val b = app.repository.snapshot(1000, null, Health())
            waitFor { cache.value == 2 }
            accept(a)
            waitFor { cache.value == 1 }
            accept(b)
            waitFor { cache.value == 0 }
            assertEquals(listOf(0, 1, 2, 1, 0), changes.toList())
            assertEquals(false, cache.unavailable)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun observationFailureClearsPreviousValueInsteadOfInventingZero() = runBlocking {
        val cache = PendingOutboxDiagnostics()
        val seen = java.util.concurrent.CopyOnWriteArrayList<Int?>()
        val job =
            launch(Dispatchers.IO) {
                cache.observe(onChange = { seen.add(cache.value) }) {
                    flow {
                        emit(7)
                        throw IllegalStateException("Room unavailable")
                    }
                }
            }
        try {
            waitFor { cache.unavailable == true }
            assertNull(cache.value)
            assertEquals(listOf(7, null), seen.toList())
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun suspendedRoomObservationDoesNotBlockCachedReadOrDiagnosticFailure() = runBlocking {
        val cache = PendingOutboxDiagnostics()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job =
            launch(Dispatchers.IO) {
                cache.observe(
                    onChange = { throw IllegalStateException("diagnostic disk failure") }
                ) {
                    flow {
                        entered.complete(Unit)
                        release.await()
                        emit(3)
                    }
                }
            }
        try {
            entered.await()
            assertNull(cache.value)
            assertNull(cache.unavailable)
            release.complete(Unit)
            waitFor { cache.value == 3 }
            assertEquals(false, cache.unavailable)
            withTimeout(10000) { job.join() }
            assertTrue(job.isCompleted)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun servicePopulatesDiagnosticsWithoutAnyActivity() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = app.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        app.repository.state()
        val service = Robolectric.buildService(TrackingService::class.java).create()
        try {
            service.get().onStartCommand(Intent(app, TrackingService::class.java), 0, 1)
            waitFor { app.operational.value.tracking && app.diagnostics.value?.pendingOutbox == 1 }
            val row = app.repository.snapshot(1000, null, Health())
            waitFor { app.diagnostics.value?.pendingOutbox == 2 }
            accept(row)
            waitFor { app.diagnostics.value?.pendingOutbox == 1 }
            assertEquals(1, shadowOf(manager).getLocationUpdateListeners().size)
        } finally {
            service.destroy()
        }
    }

    @Test
    fun realRoomTypeCountsIncludeBlockedAndDeliveredHistory() = runBlocking {
        app.repository.state()
        val cache = PendingOutboxDiagnostics()
        val job =
            launch(Dispatchers.IO) { cache.observeByType { app.repository.dao.queueCounts() } }
        try {
            waitFor { cache.snapshot.byType?.pending == 0 }
            val status = app.repository.snapshot(100000, null, Health())
            val fix = Fix(utc(101000), 0, 28.0, 77.0, 3.0, null, null, null, null)
            val location = app.repository.snapshot(101000, fix, Health(gnss_status = "fix"))
            waitFor { cache.snapshot.byType?.pending == 2 }
            assertEquals(1, cache.snapshot.byType!!.gnss)
            assertEquals(1, cache.snapshot.byType!!.routine)
            app.repository.fail(status, "invalid receiver", 0, true, 0)
            waitFor { cache.snapshot.byType?.blocked == 1 }
            accept(location)
            waitFor { cache.snapshot.byType?.deliveredGnss == 1 }
            assertEquals(0, cache.snapshot.byType!!.gnss)
            assertEquals(1, cache.value)
            accept(status)
            waitFor { cache.value == 0 }
            assertEquals(1, cache.snapshot.byType!!.deliveredRoutine)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun typeCountFailureClearsAllCachedCounts() = runBlocking {
        val cache = PendingOutboxDiagnostics()
        val job =
            launch(Dispatchers.IO) {
                cache.observeByType {
                    flow {
                        emit(QueueCounts(3, 2, 0, 1, 0, 0, 0, 0))
                        throw IllegalStateException("Room failed")
                    }
                }
            }
        try {
            waitFor { cache.unavailable == true }
            assertNull(cache.value)
            assertNull(cache.snapshot.byType)
        } finally {
            job.cancelAndJoin()
        }
    }
}
