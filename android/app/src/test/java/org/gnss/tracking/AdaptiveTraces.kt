package org.gnss.tracking

import kotlin.math.*

// Instrumented deterministic 1 Hz references, not field-recorded/survey traces.
internal object AdaptiveTraces {
    data class Sample(
        val second: Int,
        val x: Double,
        val y: Double = 0.0,
        val speed: Double? = 2.0,
        val bearing: Double? = 90.0,
        val accuracy: Double? = 1.0,
        val boundary: Boolean = false,
        val wallOffset: Long = 0,
    )

    fun cases(): Map<String, List<Sample>> =
        linkedMapOf(
            "walking" to (0..300).map { Sample(it, it * 1.5, speed = 1.5) },
            "vehicle" to (0..300).map { Sample(it, it * 8.0, speed = 8.0) },
            "stationary_jitter" to (0..3600).map { Sample(it, (it % 3 - 1) * 0.2, speed = 0.0) },
            "sharp_turns_hairpin" to
                (0..180).map {
                    when {
                        it <= 43 -> Sample(it, it * 3.0, speed = 3.0)
                        it <= 87 -> Sample(it, 129.0, (it - 43) * 3.0, 3.0, 0.0)
                        it <= 91 -> Sample(it, 129.0 - (it - 87) * 3, 132.0, 3.0, 270.0)
                        else -> Sample(it, 117.0, 132.0 - (it - 91) * 3, 3.0, 180.0)
                    }
                },
            "slow_curve" to
                (0..180).map {
                    val angle = it * PI / 180
                    Sample(
                        it,
                        60 * sin(angle),
                        60 * (1 - cos(angle)),
                        PI / 3,
                        (90.0 - it + 360) % 360,
                    )
                },
            "reversal" to
                (0..180).map {
                    Sample(
                        it,
                        if (it <= 83) it * 3.0 else (166 - it) * 3.0,
                        speed = 3.0,
                        bearing = if (it <= 83) 90.0 else 270.0,
                    )
                },
            "rapid_acceleration_deceleration" to
                (0..180).map {
                    val speed =
                        when {
                            it < 40 -> 0.0
                            it < 48 -> (it - 39).toDouble()
                            it < 100 -> 8.0
                            it < 108 -> (107 - it).toDouble()
                            else -> 0.0
                        }
                    Sample(
                        it,
                        when {
                            it < 40 -> 0.0
                            it < 48 -> (it - 39) * (it - 38) / 2.0
                            it < 100 -> 36.0 + (it - 47) * 8
                            it < 108 -> 452.0 + (it - 99) * (15 - (it - 99)) / 2
                            else -> 480.0
                        },
                        speed = speed,
                    )
                },
            "start_stop_restart_sos" to
                (0..180).map {
                    Sample(it, it * 3.0, speed = 3.0, boundary = it in listOf(43, 87, 129))
                },
            "gnss_loss_recovery" to
                (0..180).map { Sample(if (it > 83) it + 300 else it, it * 3.0, speed = 3.0) },
            "weak_accuracy" to
                (0..180).map {
                    Sample(it, it * 3.0, speed = 3.0, accuracy = if (it in 40..100) 50.0 else 1.0)
                },
            "missing_speed_bearing" to
                (0..180).map { Sample(it, it * 3.0, speed = null, bearing = null) },
            "timestamp_anomalies" to
                (0..180).map {
                    Sample(it, it * 3.0, speed = 3.0, wallOffset = if (it in 40..80) 90000 else 0)
                },
        )

    fun value(
        s: Sample,
        base: Long,
        session: kotlinx.coroutines.Deferred<TrackingSession>? = null,
    ): CapturedObservation =
        CapturedObservation(
            Protocol.newId(),
            base + s.second * 1000,
            Fix(
                utc(base + s.second * 1000 + s.wallOffset),
                0,
                Math.toDegrees(s.y / 6371008.8),
                Math.toDegrees(s.x / 6371008.8),
                s.accuracy,
                null,
                null,
                s.speed,
                s.bearing,
            ),
            Health(gnss_status = "fix"),
            "replay",
            (100000 + s.second * 1000L) * 1000000,
            session,
        )
}
