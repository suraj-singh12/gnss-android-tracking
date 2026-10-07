package org.gnss.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

// Pure observation seam: elapsed measurement time establishes age across wall clock edits.
data class Observation(val fix: Fix, val elapsedMillis: Long)

class LatestLocation(private val clock: Clock) {
    @Volatile
    var observation: Observation? = null
        private set

    @Volatile var enabled: Boolean? = null
    @Volatile var satellites: Int? = null

    fun update(value: Observation): Boolean {
        if (
            value.elapsedMillis > 0 &&
                value.elapsedMillis <= clock.elapsedMillis() &&
                (observation == null || value.elapsedMillis >= observation!!.elapsedMillis)
        ) {
            observation = value
            return true
        }
        return false
    }

    fun age(): Long? =
        observation?.let { (clock.elapsedMillis() - it.elapsedMillis).takeIf { age -> age >= 0 } }

    fun currentFix(): Fix? {
        val observed = observation ?: return null
        val age = clock.elapsedMillis() - observed.elapsedMillis
        return observed.fix.copy(fix_age_ms = age).takeIf { enabled == true && age in 0..30000 }
    }

    fun clockWarning(): String? =
        observation?.let { observed ->
            val age = clock.elapsedMillis() - observed.elapsedMillis
            val delta =
                clock.wallMillis() -
                    java.time.Instant.parse(observed.fix.observed_at).toEpochMilli() -
                    age
            if (kotlin.math.abs(delta) > 120000) "GNSS observation and device clocks disagree"
            else null
        }

    fun status(): String =
        when {
            enabled == false -> "disabled"
            enabled == null -> "unknown"
            currentFix() != null -> "fix"
            observation != null -> "unknown" // Known observation has become stale.
            else -> "no_fix"
        }
}

interface LocationSource {
    fun start()

    fun stop()
}

class PlatformLocationSource(
    private val context: Context,
    val latest: LatestLocation,
    private val clock: Clock = SystemClock,
    private val diagnosticEvent: (DiagnosticEvent, SourceState) -> Unit = { _, _ -> },
) : LocationSource {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var started = false
    @Volatile
    var diagnostics = SourceState()
        private set

    private fun event(kind: DiagnosticEvent) {
        diagnostics =
            diagnostics.copy(
                providerEnabled = latest.enabled,
                currentFixAvailable =
                    latest.enabled == true && latest.age()?.let { it in 0L..30000L } == true,
            )
        runCatching { diagnosticEvent(kind, diagnostics) } // Diagnostics cannot fail acquisition.
    }

    fun refreshProvider() {
        if (!started) return
        try {
            val enabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val changed = latest.enabled != enabled
            latest.enabled = enabled
            if (changed)
                event(
                    if (enabled) DiagnosticEvent.PROVIDER_ENABLED
                    else DiagnosticEvent.PROVIDER_DISABLED
                )
        } catch (e: Exception) {
            latest.enabled = null
            diagnostics =
                diagnostics.copy(error = "Provider state unavailable: ${e.javaClass.simpleName}")
            throw e
        }
    }

    private fun gnssEvent(running: Boolean? = diagnostics.engineRunning) {
        diagnostics =
            diagnostics.copy(
                lastGnssCallback = clock.elapsedMillis(),
                gnssCallbacks = diagnostics.gnssCallbacks + 1,
                engineRunning = running,
            )
    }

    private val listener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!started) return
                diagnostics =
                    diagnostics.copy(
                        lastLocationCallback = clock.elapsedMillis(),
                        locationCallbacks = diagnostics.locationCallbacks + 1,
                        lastObservationAccepted = false,
                        lastMeasurementAgeMs =
                            (location.elapsedRealtimeNanos / 1000000)
                                .takeIf { it > 0 }
                                ?.let { clock.elapsedMillis() - it },
                        lastAccuracyM =
                            location.accuracy.toDouble().takeIf {
                                location.hasAccuracy() && it.isFinite() && it >= 0
                            },
                    )
                fun reject(reason: String) {
                    diagnostics =
                        diagnostics.copy(
                            rejectedObservations = diagnostics.rejectedObservations + 1,
                            lastRejection = reason,
                            lastObservationAccepted = false,
                        )
                    event(DiagnosticEvent.LOCATION_CALLBACK)
                }
                fun available(has: Boolean, value: Double, nonnegative: Boolean = false): Double? =
                    value.takeIf { has && it.isFinite() && (!nonnegative || it >= 0) }
                if (
                    !location.latitude.isFinite() ||
                        location.latitude !in -90.0..90.0 ||
                        !location.longitude.isFinite() ||
                        location.longitude !in -180.0..180.0
                ) {
                    reject("invalid_coordinate")
                    return
                }
                val altitude = available(location.hasAltitude(), location.altitude)
                val accepted =
                    latest.update(
                        Observation(
                            Fix(
                                utc(location.time),
                                0,
                                location.latitude,
                                location.longitude,
                                available(
                                    location.hasAccuracy(),
                                    location.accuracy.toDouble(),
                                    true,
                                ),
                                altitude,
                                available(
                                    altitude != null && location.hasVerticalAccuracy(),
                                    location.verticalAccuracyMeters.toDouble(),
                                    true,
                                ),
                                available(location.hasSpeed(), location.speed.toDouble(), true),
                                available(location.hasBearing(), location.bearing.toDouble(), true)
                                    ?.takeIf { it < 360 },
                            ),
                            location.elapsedRealtimeNanos / 1000000,
                        )
                    )
                if (!accepted) reject("unusable_elapsed_measurement_time")
                else {
                    diagnostics =
                        diagnostics.copy(lastObservationAccepted = true, lastRejection = null)
                    event(DiagnosticEvent.LOCATION_CALLBACK)
                }
            }

            override fun onProviderEnabled(provider: String) {
                if (!started) return
                latest.enabled = true
                event(DiagnosticEvent.PROVIDER_ENABLED)
            }

            override fun onProviderDisabled(provider: String) {
                if (!started) return
                latest.enabled = false
                latest.satellites = null
                event(DiagnosticEvent.PROVIDER_DISABLED)
            }

            @Deprecated("Platform callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
    private val gnss =
        object : GnssStatus.Callback() {
            override fun onStarted() {
                if (started) {
                    gnssEvent(true)
                    event(DiagnosticEvent.GNSS_STARTED)
                }
            }

            override fun onFirstFix(ttffMillis: Int) {
                if (started) {
                    gnssEvent(true)
                    event(DiagnosticEvent.GNSS_FIRST_FIX)
                } // Never a geographic observation.
            }

            override fun onSatelliteStatusChanged(status: GnssStatus) {
                if (!started) return
                gnssEvent(true)
                latest.satellites = (0 until status.satelliteCount).count { status.usedInFix(it) }
                diagnostics =
                    diagnostics.copy(
                        satellitesTotal = status.satelliteCount,
                        satellitesUsed = latest.satellites,
                    )
                event(DiagnosticEvent.GNSS_STATUS)
            }

            override fun onStopped() {
                if (!started) return
                gnssEvent(false)
                latest.satellites = null
                diagnostics = diagnostics.copy(satellitesTotal = null, satellitesUsed = null)
                event(DiagnosticEvent.GNSS_STOPPED)
            }
        }

    override fun start() {
        if (started) return
        event(DiagnosticEvent.REGISTRATION_ATTEMPT)
        check(
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            "Precise location permission required"
        }
        latest.enabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        started = true
        try {
            val statusRegistered =
                manager.registerGnssStatusCallback(gnss, Handler(Looper.getMainLooper()))
            diagnostics =
                diagnostics.copy(
                    statusRegistered = statusRegistered,
                    error = if (statusRegistered) null else "GNSS status registration unavailable",
                )
            event(
                if (statusRegistered) DiagnosticEvent.STATUS_REGISTRATION_SUCCESS
                else DiagnosticEvent.STATUS_REGISTRATION_FAILED
            )
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                listener,
                Looper.getMainLooper(),
            )
            diagnostics =
                diagnostics.copy(
                    registered = true,
                    registrations = diagnostics.registrations + 1,
                    registeredAt = clock.elapsedMillis(),
                )
            event(DiagnosticEvent.REGISTRATION_SUCCESS)
        } catch (e: Exception) {
            diagnostics =
                diagnostics.copy(error = "GNSS registration failed: ${e.javaClass.simpleName}")
            event(DiagnosticEvent.REGISTRATION_FAILED)
            stop() // Roll back a partially registered source.
            throw e
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        diagnostics =
            diagnostics.copy(
                registered = false,
                statusRegistered = false,
                engineRunning = null,
                satellitesTotal = null,
                satellitesUsed = null,
            )
        event(DiagnosticEvent.UNREGISTER)
        latest.satellites = null
        try {
            manager.removeUpdates(listener)
        } finally {
            manager.unregisterGnssStatusCallback(gnss)
        }
    }
}

// Owned only by TrackingService. A location FGS grants location access, but does
// not itself keep the CPU running. Timeout bounds a lock if the service loop fails;
// normal tracking renews it and every stop/start failure releases it immediately.
internal class TrackingResources(
    private val source: LocationSource,
    private val wakeLock: PowerManager.WakeLock,
    private val clock: Clock = SystemClock,
    private val diagnosticEvent: (DiagnosticEvent) -> Unit = {},
) {
    private fun event(kind: DiagnosticEvent) {
        runCatching { diagnosticEvent(kind) }
    }

    private var active = false
    private var renewedAt = 0L
    @Volatile
    var renewals = 0
        private set

    val held: Boolean
        get() = wakeLock.isHeld

    init {
        wakeLock.setReferenceCounted(false)
    }

    @Synchronized
    fun start() {
        if (active) return
        try {
            wakeLock.acquire(TIMEOUT_MS)
            event(DiagnosticEvent.WAKE_ACQUIRE)
            renewedAt = clock.elapsedMillis()
            source.start()
            active = true
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    @Synchronized
    fun renew() {
        if (active && (!wakeLock.isHeld || clock.elapsedMillis() - renewedAt >= TIMEOUT_MS / 2)) {
            wakeLock.acquire(TIMEOUT_MS)
            renewedAt = clock.elapsedMillis()
            renewals++
            event(DiagnosticEvent.WAKE_RENEW)
        }
    }

    @Synchronized
    fun stop() {
        active = false
        try {
            source.stop()
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
                event(DiagnosticEvent.WAKE_RELEASE)
            }
        }
    }

    companion object {
        const val TIMEOUT_MS = 10 * 60 * 1000L
    }
}
