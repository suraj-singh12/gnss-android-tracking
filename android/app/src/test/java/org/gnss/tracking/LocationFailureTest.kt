package org.gnss.tracking

import android.Manifest
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLocationManager

@Implements(LocationManager::class)
class FailingLocationManager : ShadowLocationManager() {
    var statusUnavailable = false
    var failUpdates = false
    lateinit var capturedStatus: GnssStatus.Callback

    @Implementation
    override fun registerGnssStatusCallback(
        callback: GnssStatus.Callback,
        handler: Handler,
    ): Boolean {
        capturedStatus = callback
        return !statusUnavailable && super.registerGnssStatusCallback(callback, handler)
    }

    @Implementation
    override fun requestLocationUpdates(
        provider: String,
        minTime: Long,
        minDistance: Float,
        listener: LocationListener,
        looper: Looper?,
    ) {
        super.requestLocationUpdates(provider, minTime, minDistance, listener, looper)
        if (failUpdates) error("injected partial registration failure")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = android.app.Application::class,
    shadows = [FailingLocationManager::class],
)
class LocationFailureTest {
    private lateinit var context: Context
    private lateinit var manager: LocationManager
    private lateinit var shadow: FailingLocationManager
    private val clock = FakeClock(100000)
    private lateinit var latest: LatestLocation
    private lateinit var source: PlatformLocationSource

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application)
            .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        manager = context.getSystemService(LocationManager::class.java)
        shadow = org.robolectric.shadow.api.Shadow.extract(manager)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        latest = LatestLocation(clock)
        source = PlatformLocationSource(context, latest, clock)
    }

    @Test
    fun partialRegistrationFailureCleansBothCallbacksAndWakeLock() {
        shadow.failUpdates = true
        val lock =
            context
                .getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gnss:test")
        val resources = TrackingResources(source, lock, clock)
        assertTrue(runCatching { resources.start() }.isFailure)
        assertFalse(lock.isHeld)
        assertFalse(source.diagnostics.registered)
        assertFalse(source.diagnostics.statusRegistered)
        assertTrue(shadow.getLocationUpdateListeners().isEmpty())
        shadow.capturedStatus.onStarted()
        assertNull(source.diagnostics.engineRunning)
        shadow.failUpdates = false
        resources.start()
        assertTrue(source.diagnostics.registered)
        resources.stop()
        assertFalse(lock.isHeld)
    }

    @Test
    fun unavailableSatelliteTelemetryDoesNotDisableRealGpsUpdates() {
        shadow.statusUnavailable = true
        source.start()
        assertTrue(source.diagnostics.registered)
        assertFalse(source.diagnostics.statusRegistered)
        assertNotNull(source.diagnostics.error)
        shadow.simulateLocation(
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 28.0
                longitude = 77.0
                accuracy = 3f
                time = clock.now
                elapsedRealtimeNanos = clock.now * 1000000
            }
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(latest.currentFix())
        assertEquals(1L, source.diagnostics.locationCallbacks)
        assertNull(source.diagnostics.lastGnssCallback)
        source.stop()
    }

    @Test
    fun providerEventsAndAcquisitionEventsCannotInventFixOrReviveStoppedSource() {
        source.start()
        val listener = shadow.getLocationUpdateListeners().single()
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("disabled", latest.status())
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("no_fix", latest.status())
        shadow.simulateGnssStatusStarted()
        shadow.simulateGnssStatusFirstFix(1000)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(latest.currentFix())
        assertEquals(true, source.diagnostics.engineRunning)
        assertNotNull(source.diagnostics.lastGnssCallback)
        assertNull(source.diagnostics.lastLocationCallback)
        clock.now += 31000
        shadow.simulateGnssStatusStopped()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, source.diagnostics.engineRunning)
        assertNull(latest.currentFix())
        clock.now += 300000
        assertTrue(source.diagnostics.callbacksQuiet(clock.now))
        assertEquals(
            1,
            source.diagnostics.registrations,
        ) // Silence is evidence, not automatic recovery.
        source.stop()
        latest.enabled = false
        listener.onProviderEnabled(LocationManager.GPS_PROVIDER)
        shadow.capturedStatus.onStarted()
        assertEquals(false, latest.enabled)
        assertNull(source.diagnostics.engineRunning)
    }

    @Test
    fun callbacksWithBadMeasurementTimeAreVisibleAndCannotFakeFreshness() {
        source.start()
        shadow.simulateLocation(
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 28.0
                longitude = 77.0
                time = clock.now
                elapsedRealtimeNanos = 0
            }
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(clock.now, source.diagnostics.lastLocationCallback)
        assertEquals(1L, source.diagnostics.rejectedObservations)
        assertEquals("unusable_elapsed_measurement_time", source.diagnostics.lastRejection)
        assertNull(latest.currentFix())
        source.stop()
    }
}
