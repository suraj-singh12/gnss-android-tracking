package org.gnss.tracking

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class LocationSourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun nativeSourceContinuesWithoutActivityAndStopsExplicitly() {
        shadowOf(context as android.app.Application)
            .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = context.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val clock = FakeClock(100000)
        val latest = LatestLocation(clock)
        val source = PlatformLocationSource(context, latest)
        source.start()
        source.start() // Repeated service starts must not create multiple registrations.
        assertEquals(1, shadowOf(manager).getLocationUpdateListeners().size)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        activity.pause().stop().destroy()
        fun observe(at: Long) {
            clock.now = at
            shadowOf(manager)
                .simulateLocation(
                    Location(LocationManager.GPS_PROVIDER).apply {
                        latitude = 28.0
                        longitude = 77.0
                        time = at
                        elapsedRealtimeNanos = at * 1000000
                        accuracy = 3f
                    }
                )
            shadowOf(Looper.getMainLooper()).idle()
        }
        observe(101000)
        assertEquals("fix", latest.status())
        assertEquals(3.0, latest.currentFix()!!.horizontal_accuracy_m!!, 0.0)
        observe(141000) // More than the stale threshold since the first callback.
        assertEquals(utc(141000), latest.currentFix()!!.observed_at)
        source.stop()
        source.stop()
        assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        observe(181000)
        assertNull(latest.currentFix()) // No invented refresh when callbacks stop.
        source.start() // A recreated service can register without an Activity.
        observe(182000)
        assertNotNull(latest.currentFix())
        source.stop()
    }

    @Test
    @Config(application = TrackingApp::class)
    fun trackingServiceOwnsRegistrationAcrossActualActivityDestruction() {
        shadowOf(context as android.app.Application)
            .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = context.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val service = Robolectric.buildService(TrackingService::class.java).create()
        try {
            val intent = Intent(context, TrackingService::class.java)
            service.get().onStartCommand(intent, 0, 1)
            assertNotNull(shadowOf(service.get()).lastForegroundNotification)
            assertEquals(1, shadowOf(manager).getLocationUpdateListeners().size)
            val lock = ShadowPowerManager.getLatestWakeLock()
            assertTrue(lock.isHeld)
            val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
            activity.pause().stop().destroy()
            assertEquals(1, shadowOf(manager).getLocationUpdateListeners().size)
            assertTrue(lock.isHeld)
            service.get().onStartCommand(intent, 0, 2)
            assertEquals(1, shadowOf(manager).getLocationUpdateListeners().size)
        } finally {
            service.destroy()
        }
        assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test
    fun serviceResourcesRenewBoundedLockAndReleaseOnStopAndFailure() {
        val lock =
            context
                .getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gnss:test")
        val clock = FakeClock(1000)
        var starts = 0
        var stops = 0
        var fail = false
        val source =
            object : LocationSource {
                override fun start() {
                    starts++
                    if (fail) error("registration failed")
                }

                override fun stop() {
                    stops++
                }
            }
        val resources = TrackingResources(source, lock, clock)
        resources.start()
        resources.start()
        assertEquals(1, starts)
        assertTrue(lock.isHeld)
        clock.now += TrackingResources.TIMEOUT_MS
        lock.release() // Model expiry; the service loop restores CPU protection.
        resources.renew()
        assertTrue(lock.isHeld)
        resources.stop()
        assertFalse(lock.isHeld)
        resources.renew()
        assertFalse(lock.isHeld)
        fail = true
        assertTrue(runCatching { resources.start() }.isFailure)
        assertFalse(lock.isHeld)
        assertEquals(2, stops)
    }
}
