package org.gnss.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper

// Pure observation seam: elapsed measurement time establishes age across wall clock edits.
data class Observation(val fix: Fix, val elapsedMillis: Long)

class LatestLocation(private val clock: Clock) {
    @Volatile
    var observation: Observation? = null
        private set

    @Volatile var enabled: Boolean? = null
    @Volatile var satellites: Int? = null

    fun update(value: Observation) {
        if (
            value.elapsedMillis > 0 &&
                value.elapsedMillis <= clock.elapsedMillis() &&
                (observation == null || value.elapsedMillis >= observation!!.elapsedMillis)
        )
            observation = value
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

class PlatformLocationSource(private val context: Context, val latest: LatestLocation) :
    LocationSource {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val listener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                fun available(has: Boolean, value: Double, nonnegative: Boolean = false): Double? =
                    value.takeIf { has && it.isFinite() && (!nonnegative || it >= 0) }
                if (
                    !location.latitude.isFinite() ||
                        location.latitude !in -90.0..90.0 ||
                        !location.longitude.isFinite() ||
                        location.longitude !in -180.0..180.0
                )
                    return
                val altitude = available(location.hasAltitude(), location.altitude)
                latest.update(
                    Observation(
                        Fix(
                            utc(location.time),
                            0,
                            location.latitude,
                            location.longitude,
                            available(location.hasAccuracy(), location.accuracy.toDouble(), true),
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
            }

            override fun onProviderEnabled(provider: String) {
                latest.enabled = true
            }

            override fun onProviderDisabled(provider: String) {
                latest.enabled = false
                latest.satellites = null
            }

            @Deprecated("Platform callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
    private val gnss =
        object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                latest.satellites = (0 until status.satelliteCount).count { status.usedInFix(it) }
            }

            override fun onStopped() {
                latest.satellites = null
            }
        }

    override fun start() {
        check(
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            "Precise location permission required"
        }
        latest.enabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        manager.registerGnssStatusCallback(gnss, Handler(Looper.getMainLooper()))
        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,
            0f,
            listener,
            Looper.getMainLooper(),
        )
    }

    override fun stop() {
        manager.removeUpdates(listener)
        manager.unregisterGnssStatusCallback(gnss)
    }
}
