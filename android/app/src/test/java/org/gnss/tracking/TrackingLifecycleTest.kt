package org.gnss.tracking

import android.Manifest
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.Looper
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TrackingApp::class)
class TrackingLifecycleTest {
    private lateinit var app: TrackingApp
    private lateinit var manager: LocationManager

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        manager = app.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    @Test
    fun asynchronousStartCannotCreateLocationServiceAfterActivityHidden() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        val request =
            MainActivity::class.java.getDeclaredMethod("requestStart").apply { isAccessible = true }
        try {
            activity.pause().stop()
            request.invoke(
                activity.get()
            ) // Completion of saved-settings/permission work after Home.
            assertNull(shadowOf(app).nextStartedService)
            assertTrue(app.operational.value.error!!.contains("while the app is visible"))
            activity.start().resume()
            assertNull(
                shadowOf(app).nextStartedService
            ) // Visibility never restarts GNSS automatically.
            request.invoke(activity.get())
            assertEquals(
                TrackingService::class.java.name,
                shadowOf(app).nextStartedService.component!!.className,
            )
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun lockMaintenanceAndNativeRegistrationDoNotWaitForRoomStartup() = runBlocking {
        app.repository.state()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transaction =
            launch(Dispatchers.IO) {
                app.repository.db.withTransaction {
                    entered.complete(Unit)
                    release.await()
                }
            }
        withTimeout(10000) { entered.await() }
        val service = Robolectric.buildService(TrackingService::class.java).create()
        try {
            service.get().onStartCommand(Intent(app, TrackingService::class.java), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, shadowOf(manager).getLocationUpdateListeners().size)
            val lock = ShadowPowerManager.getLatestWakeLock()
            assertTrue(lock.isHeld)
            assertNull(app.diagnostics.value!!.savedSnapshotAgeMs) // Room is still blocked.
            lock.release() // Model expiry independently of blocked startup/reporting IO.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertTrue(lock.isHeld)
            assertEquals(1, app.diagnostics.value!!.wakeRenewals)
            assertTrue(app.diagnostics.value!!.source.registered)
        } finally {
            release.complete(Unit)
            transaction.join()
            service.destroy()
        }
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
    }

    @Test
    fun stickyRecreationRestoresResourcesWithoutInventingFix() = runBlocking {
        app.repository.edit { it.copy(tracking = true) }
        val first = Robolectric.buildService(TrackingService::class.java).create()
        first.get().onStartCommand(Intent(app, TrackingService::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        val oldGeneration = app.diagnostics.value!!.serviceGeneration
        first.destroy()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        val second = Robolectric.buildService(TrackingService::class.java).create()
        try {
            assertEquals(android.app.Service.START_STICKY, second.get().onStartCommand(null, 0, 2))
            shadowOf(Looper.getMainLooper()).idle()
            val diagnostic = app.diagnostics.value!!
            assertNotEquals(oldGeneration, diagnostic.serviceGeneration)
            assertEquals("sticky_restart", diagnostic.startReason)
            assertTrue(diagnostic.source.registered)
            assertTrue(diagnostic.wakeLockHeld)
            assertFalse(diagnostic.fixAvailable)
            assertNull(diagnostic.locationCallbackAgeMs)
            assertEquals(1, shadowOf(manager).getLocationUpdateListeners().size)
        } finally {
            second.destroy()
        }
    }

    @Test
    fun precisePermissionLossStopsSourceAndReleasesLock() {
        val service = Robolectric.buildService(TrackingService::class.java).create()
        try {
            service.get().onStartCommand(Intent(app, TrackingService::class.java), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertTrue(shadowOf(service.get()).isStoppedBySelf)
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
            assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
            assertFalse(app.diagnostics.value!!.power.precisePermission)
            assertTrue(app.operational.value.error!!.contains("Precise location permission lost"))
        } finally {
            service.destroy()
        }
    }

    @Test
    fun connectivityStorageFailureIsContainedAndDiagnosed() = runBlocking {
        val service = Robolectric.buildService(TrackingService::class.java).create()
        try {
            service.get().onStartCommand(Intent(app, TrackingService::class.java), 0, 1)
            val connectivity = shadowOf(app.getSystemService(ConnectivityManager::class.java))
            withTimeout(10000) {
                while (connectivity.networkCallbacks.isEmpty()) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            app.repository.db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER injected_failure BEFORE UPDATE ON outbox BEGIN SELECT RAISE(ABORT, 'injected disk failure'); END"
            )
            connectivity.networkCallbacks
                .single()
                .onAvailable(org.robolectric.shadows.ShadowNetwork.newInstance(1))
            withTimeout(10000) {
                while (
                    app.operational.value.error?.contains("Cannot prepare saved delivery") != true
                ) delay(10)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertTrue(app.diagnostics.value!!.loopError!!.contains("Connectivity recovery failed"))
            assertTrue(app.diagnostics.value!!.source.registered)
            assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
            assertEquals(1, shadowOf(manager).getLocationUpdateListeners().size)
        } finally {
            service.destroy()
        }
    }

    @Test
    @Config(sdk = [35])
    fun targetSdkForegroundPromotionUsesLocationType() {
        val service = Robolectric.buildService(TrackingService::class.java).create()
        try {
            service.get().onStartCommand(Intent(app, TrackingService::class.java), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                app.diagnostics.value!!.foregroundType,
            )
            assertTrue(app.diagnostics.value!!.source.registered)
        } finally {
            service.destroy()
        }
    }

    @Test
    fun initializationFailureRequestsShutdownWithoutAnotherFailedStoreWrite() = runBlocking {
        app.repository.state()
        app.repository.db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER injected_failure BEFORE UPDATE ON installation BEGIN SELECT RAISE(ABORT, 'injected disk failure'); END"
        )
        val service = Robolectric.buildService(TrackingService::class.java).create()
        try {
            service.get().onStartCommand(Intent(app, TrackingService::class.java), 0, 1)
            withTimeout(10000) {
                while (!shadowOf(service.get()).isStoppedBySelf) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            assertTrue(app.operational.value.error!!.contains("Cannot initialize tracking"))
            assertTrue(
                app.diagnostics.value!!.loopError!!.contains("Tracking initialization failed")
            )
        } finally {
            service.destroy()
        }
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
    }

    @After
    fun finishApplicationRecorder() =
        kotlinx.coroutines.runBlocking {
            // Application-owned IO must finish before Robolectric replaces its Android sandbox.
            // Otherwise native fsync/runtime initialization can race the next SDK's font
            // extraction.
            val application: android.app.Application = ApplicationProvider.getApplicationContext()
            (application as? TrackingApp)?.finishForTests()
            Unit
        }
}
