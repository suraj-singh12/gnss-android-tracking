package org.gnss.tracking

import android.content.SharedPreferences
import com.google.gson.Gson
import kotlin.math.*

// Local-only, independent of reporting config and Command authority. One atomic
// preference value; absence (including upgrades) never constitutes consent.
data class PersistencePolicy(
    val schema: Int = 1,
    val revision: Int = 1,
    val enabled: Boolean = false,
    val changes: Long = 0,
    val effectiveAt: String? = null,
    val error: String? = null,
)

class LocalPersistencePolicy(private val preferences: SharedPreferences) {
    private val current = kotlinx.coroutines.flow.MutableStateFlow(load())
    val updates: kotlinx.coroutines.flow.StateFlow<PersistencePolicy>
        get() = current

    var state: PersistencePolicy
        get() = current.value
        private set(value) {
            current.value = value
        }

    private fun load(): PersistencePolicy =
        try {
            val raw = preferences.getString("effective", null)
            if (raw == null) PersistencePolicy()
            else {
                val json = Protocol.parse(raw, maximumBytes = 4096)
                require(
                    json.keySet() ==
                        setOf("schema", "revision", "enabled", "changes", "effectiveAt")
                )
                require(json["schema"].toString() == "1" && json["revision"].toString() == "1")
                require(
                    json["changes"].asJsonPrimitive.isNumber &&
                        json["changes"].asString.matches(Regex("[1-9][0-9]*"))
                )
                require(json["enabled"].asJsonPrimitive.isBoolean)
                val value = Gson().fromJson(raw, PersistencePolicy::class.java)
                require(
                    value.schema == 1 && value.revision == 1 && value.changes in 1..MAX_WIRE_INTEGER
                )
                require(
                    value.effectiveAt != null &&
                        utc(java.time.Instant.parse(value.effectiveAt).toEpochMilli()) ==
                            value.effectiveAt
                )
                require(value.error == null)
                value
            }
        } catch (_: Exception) {
            PersistencePolicy(error = "Unknown or unreadable policy; dense persistence effective")
        }

    // Call off the UI/native callback thread. Publish only after durable commit.
    @Synchronized
    fun setEnabled(enabled: Boolean, at: String): PersistencePolicy {
        require(utc(java.time.Instant.parse(at).toEpochMilli()) == at)
        val next =
            try {
                check(state.changes < MAX_WIRE_INTEGER)
                val updated =
                    PersistencePolicy(
                        enabled = enabled,
                        changes = state.changes + 1,
                        effectiveAt = at,
                    )
                check(preferences.edit().putString("effective", Gson().toJson(updated)).commit())
                updated
            } catch (e: Exception) {
                state =
                    state.copy(
                        error = "Policy save failed; dense persistence effective",
                    )
                throw e
            }
        state = next
        return next
    }
}

data class SamplingState(
    val candidates: Long = 0,
    val selected: Long = 0,
    val intentionallyNotSelected: Long = 0,
    val boundaryCandidate: Boolean = false,
    val callbackGaps: Long = 0,
    val reason: String = "dense_default",
    val fallback: String? = null,
    val policy: PersistencePolicy = PersistencePolicy(),
    val firstCallbackAppliedAt: String? = null,
) {
    val effectiveAdaptive: Boolean
        get() = policy.enabled && policy.error == null && fallback == null
}

// A single capture decision, not a quality filter. Command still assesses every
// retained original. One unsaved callback is held for boundary lookback; no path
// interpolation or coordinate dedupe. State is reset conservatively on recreation.
class AdaptivePersistence {
    var state = SamplingState()
        private set

    private var previous: CapturedObservation? = null
    private var pending: CapturedObservation? = null
    private var anchor: CapturedObservation? = null
    private var stillSince: Long? = null
    private var stillAnchor: CapturedObservation? = null

    fun failSafe() {
        state = state.copy(fallback = "Persistence/selection failure; dense until process restart")
    }

    fun recover(value: CapturedObservation, candidates: Long): List<CapturedObservation> {
        failSafe()
        val retained = listOfNotNull(pending) + value
        pending = null
        previous = value
        anchor = value
        stillSince = null
        stillAnchor = null
        state =
            state.copy(
                candidates = candidates,
                selected = state.selected + retained.size,
                intentionallyNotSelected = candidates - state.selected - retained.size,
                boundaryCandidate = false,
                reason = "selection_failure_dense",
            )
        return retained
    }

    fun boundary(reason: String): List<CapturedObservation> {
        val result = listOfNotNull(pending)
        pending = null
        anchor = previous
        if (reason !in listOf("live_opportunity", "sos_boundary", "network_recovery_boundary")) {
            stillSince = null
            stillAnchor = null
        }
        state =
            state.copy(
                selected = state.selected + result.size,
                boundaryCandidate = false,
                reason = reason,
                intentionallyNotSelected = state.candidates - state.selected - result.size,
            )
        return result
    }

    fun quiet(nowElapsedMillis: Long): List<CapturedObservation> =
        if (
            pending != null &&
                previous?.let { nowElapsedMillis - callbackElapsed(it) >= 3000 } == true
        )
            boundary("valid_candidate_silence_boundary")
        else emptyList()

    fun select(value: CapturedObservation, policy: PersistencePolicy): List<CapturedObservation> {
        val result = mutableListOf<CapturedObservation>()
        fun flush() {
            pending?.let { result.add(it) }
            pending = null
        }
        val changed = state.policy != policy
        val last = previous
        val dt = last?.let { (value.elapsedNanos - it.elapsedNanos) / 1_000_000 }
        val callbackDt = last?.let { callbackElapsed(value) - callbackElapsed(it) }
        state =
            state.copy(
                candidates = state.candidates + 1,
                policy = policy,
                firstCallbackAppliedAt =
                    if (changed) utc(value.capturedMillis) else state.firstCallbackAppliedAt,
                callbackGaps =
                    state.callbackGaps +
                        if (
                            callbackDt != null &&
                                callbackDt > 2500 &&
                                last?.generation == value.generation &&
                                last.session === value.session
                        )
                            1
                        else 0,
            )
        var reason: String? = null
        if (changed || last?.session !== value.session || last?.generation != value.generation) {
            flush()
            anchor = null
            stillSince = null
            stillAnchor = null
            reason = "policy_or_lifecycle_boundary"
        }
        if (!state.effectiveAdaptive)
            reason =
                if (state.fallback != null || policy.error != null) "dense_fallback"
                else "dense_off"
        else if (last == null || anchor == null) reason = "first_fix"
        else if (
            dt == null ||
                dt <= 0 ||
                dt > 2500 ||
                abs(wall(value) - wall(last) - dt) > 2000 ||
                value.fix.fix_age_ms > 2000 ||
                abs(value.capturedMillis - wall(value) - value.fix.fix_age_ms) > 2000
        ) {
            reason = "gap_or_timestamp_anomaly"
            stillSince = null
            stillAnchor = null
        } else {
            val f = value.fix
            val p = last.fix
            val accuracy = f.horizontal_accuracy_m
            val speed = f.speed_mps
            if (
                accuracy == null ||
                    accuracy > 10 ||
                    p.horizontal_accuracy_m == null ||
                    p.horizontal_accuracy_m > 10 ||
                    speed == null ||
                    p.speed_mps == null ||
                    speed > 80 ||
                    distance(p, f) >
                        max(speed, p.speed_mps ?: speed) * (dt / 1000.0) +
                            accuracy +
                            (p.horizontal_accuracy_m ?: accuracy) +
                            5
            ) {
                reason = "uncertain_dense"
                stillSince = null
                stillAnchor = null
            } else {
                // Stationarity needs 30 s of low speed AND bounded displacement,
                // with tighter entry than exit thresholds (hysteresis).
                val origin = stillAnchor ?: value.also { stillAnchor = it }
                val within = distance(origin.fix, f) <= max(2.0, accuracy)
                if (speed <= 0.2 && accuracy <= 5 && within) {
                    if (stillSince == null) stillSince = value.elapsedNanos / 1_000_000
                } else if (speed >= 0.7 || !within || accuracy > 5) {
                    if (stillSince != null) reason = "motion_transition"
                    stillSince = null
                    stillAnchor = null
                }
                if (speed > 0.2 && speed < 0.7) reason = "uncertain_motion_dense"
                val stationary =
                    stillSince?.let { value.elapsedNanos / 1_000_000 - it >= 30000 } == true
                val elapsed = (value.elapsedNanos - anchor!!.elapsedNanos) / 1_000_000
                if (abs(speed - p.speed_mps) >= 0.8) reason = "rapid_speed_change"
                if (!stationary && speed > 0.2) {
                    if (f.bearing_deg == null || p.bearing_deg == null)
                        reason = "missing_bearing_dense"
                    else if (
                        headingDifference(f.bearing_deg, p.bearing_deg) >= 10 ||
                            anchor!!.fix.bearing_deg?.let {
                                headingDifference(f.bearing_deg, it) >= 10
                            } == true ||
                            deviation(last.fix, anchor!!.fix, f) > 1.0
                    )
                        reason = "turn"
                }
                if (reason == null && elapsed >= if (stationary) 30000 else 5000)
                    reason = if (stationary) "stationary_periodic" else "moving_periodic"
            }
        }
        if (reason != null) {
            // Preserve the last real callback on either side of a meaningful boundary.
            if (reason !in listOf("moving_periodic", "stationary_periodic", "dense_off")) flush()
            else pending = null
            result.add(value)
            anchor = value
        } else pending = value
        previous = value
        state =
            state.copy(
                selected = state.selected + result.size,
                intentionallyNotSelected =
                    state.candidates - state.selected - result.size - if (pending == null) 0 else 1,
                boundaryCandidate = pending != null,
                reason = reason ?: "intentional_sampling",
            )
        return result
    }

    private fun callbackElapsed(v: CapturedObservation) =
        v.elapsedNanos / 1_000_000 + v.fix.fix_age_ms

    private fun wall(v: CapturedObservation) =
        java.time.Instant.parse(v.fix.observed_at).toEpochMilli()

    private fun headingDifference(a: Double, b: Double) = abs((a - b + 540) % 360 - 180)

    companion object {
        fun distance(a: Fix, b: Fix): Double {
            val lat = Math.toRadians((a.latitude + b.latitude) / 2)
            val x = Math.toRadians((b.longitude - a.longitude + 540) % 360 - 180) * cos(lat)
            val y = Math.toRadians(b.latitude - a.latitude)
            return hypot(x, y) * 6371008.8
        }

        fun deviation(p: Fix, a: Fix, b: Fix): Double {
            val lat = Math.toRadians(a.latitude)
            fun x(f: Fix) =
                Math.toRadians((f.longitude - a.longitude + 540) % 360 - 180) * cos(lat) * 6371008.8
            fun y(f: Fix) = Math.toRadians(f.latitude - a.latitude) * 6371008.8
            val bx = x(b)
            val by = y(b)
            val t =
                if (bx * bx + by * by == 0.0) 0.0
                else ((x(p) * bx + y(p) * by) / (bx * bx + by * by)).coerceIn(0.0, 1.0)
            return hypot(x(p) - t * bx, y(p) - t * by)
        }
    }
}
