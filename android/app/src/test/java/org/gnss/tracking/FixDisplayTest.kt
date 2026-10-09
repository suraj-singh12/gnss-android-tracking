package org.gnss.tracking

import android.Manifest
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TrackingApp::class)
class FixDisplayTest {
    @Test
    fun currentFixShowsCurrentAccuracyAndMeasurementAge() {
        assertEquals(
            "GPS: Fix\nAccuracy: 3.2 m\nFix age: 2 s",
            Operational(gnss = "fix", accuracy = 3.2, ageMillis = 2000).fixDescription(),
        )
    }

    @Test
    fun staleFixShowsLastKnownAccuracyWithoutClaimingItIsCurrent() {
        assertEquals(
            "GPS: Stale fix\nLast fix age: 47 s\nLast known accuracy: 3.2 m",
            Operational(gnss = "unknown", accuracy = 3.2, ageMillis = 47000).fixDescription(),
        )
        assertTrue(
            Operational(gnss = "unknown", ageMillis = 47000)
                .fixDescription()
                .contains("Last known accuracy: Unavailable")
        )
    }

    @Test
    fun noObservationShowsNoneAndDisabledProviderRemainsExplicit() {
        assertEquals(
            "GPS: No fix\nLast fix: None\nAccuracy: Unavailable",
            Operational(gnss = "no_fix").fixDescription(),
        )
        assertTrue(
            Operational(gnss = "disabled", accuracy = 3.2, ageMillis = 47000)
                .fixDescription()
                .startsWith("GPS: Disabled")
        )
    }

    @Test
    fun thirtySecondBoundaryIsUnchangedAndOldFixCannotBeMadeCurrentByDisplay() {
        val clock = FakeClock(100000)
        val latest = LatestLocation(clock).apply { enabled = true }
        latest.update(
            Observation(Fix(utc(clock.now), 0, 28.0, 77.0, 3.2, null, null, null, null), clock.now)
        )
        clock.now += 30000
        assertNotNull(latest.currentFix())
        assertTrue(
            Operational(
                    gnss = latest.status(),
                    accuracy = latest.observation!!.fix.horizontal_accuracy_m,
                    ageMillis = latest.age(),
                )
                .fixDescription()
                .startsWith("GPS: Fix")
        )
        clock.now++
        assertNull(latest.currentFix())
        assertTrue(
            Operational(
                    gnss = latest.status(),
                    accuracy = latest.observation!!.fix.horizontal_accuracy_m,
                    ageMillis = latest.age(),
                )
                .fixDescription()
                .startsWith("GPS: Stale fix")
        )
        assertEquals(utc(100000), latest.observation!!.fix.observed_at)
        assertNull(latest.currentFix())
    }

    @Test
    fun productionServiceRetainsAccuracyWhenFreshnessExpiresWithoutNewCallbacks() {
        val app: TrackingApp = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = app.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val service = Robolectric.buildService(TrackingService::class.java).create()
        try {
            service.get().onStartCommand(Intent(app, TrackingService::class.java), 0, 1)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            shadowOf(manager)
                .simulateLocation(
                    Location(LocationManager.GPS_PROVIDER).apply {
                        latitude = 28.0
                        longitude = 77.0
                        time = java.lang.System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                        accuracy = 3.2f
                    }
                )
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertTrue(app.diagnostics.value!!.fixAvailable)
            val callbackCount = app.diagnostics.value!!.source.locationCallbacks
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(47))
            assertFalse(app.diagnostics.value!!.fixAvailable)
            assertEquals(callbackCount, app.diagnostics.value!!.source.locationCallbacks)
            assertEquals(1, app.diagnostics.value!!.source.registrations)
            assertEquals(3.2, app.operational.value.accuracy!!, 0.001)
            assertTrue(
                app.operational.value.fixDescription().contains("Last known accuracy: 3.2 m")
            )
            assertTrue(app.operational.value.ageMillis!! > 30000)
        } finally {
            service.destroy()
        }
    }

    @After
    fun finishApplicationRecorder() =
        kotlinx.coroutines.runBlocking {
            // Application-owned IO must finish before Robolectric replaces its Android sandbox.
            // Otherwise native fsync/runtime initialization can race the next SDK's font
            // extraction.
            val application: android.app.Application = ApplicationProvider.getApplicationContext()
            (application as? TrackingApp)?.recorder?.finish()
            Unit
        }
}
