package org.gnss.tracking

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

// The existing Room outbox is the only durable store. This bounded memory handoff
// keeps SQLite/diagnostic IO away from LocationManager; it is not another queue on disk.
data class CapturedObservation(
    val id: String,
    val capturedMillis: Long,
    val fix: Fix,
    val health: Health,
    val generation: String?,
    val elapsedNanos: Long = 1_000_000,
    val session: kotlinx.coroutines.Deferred<TrackingSession>? = null,
)

data class CollectionState(
    val submitted: Long,
    val saved: Long,
    val writeFailures: Long,
    val overflow: Long,
    val awaitingCommit: Long,
    val committedObservations: Long = saved,
    val sampling: SamplingState = SamplingState(),
)

class ObservationPersistence(
    private val save: suspend (CapturedObservation) -> Outbound,
    private val onSaved: (CapturedObservation, Outbound) -> Unit = { _, _ -> },
    private val onFailure: () -> Unit = {},
    private val clock: Clock = SystemClock,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    capacity: Int = 1024,
    private val policy: () -> PersistencePolicy = { PersistencePolicy() },
    private val onOverflow: (CapturedObservation) -> Unit = {},
) {
    private val work = Channel<CapturedObservation>(capacity)
    private val sampling = AdaptivePersistence()
    private var liveAwaiting: Long? = null
    val samplingState: SamplingState
        get() = synchronized(this) { sampling.state }

    private val submitted = AtomicLong()
    private val saved = AtomicLong()
    private val committed = AtomicLong()
    private val failures = AtomicLong()
    private val overflow = AtomicLong()
    val state
        get(): CollectionState {
            val committed = saved.get()
            val total = submitted.get()
            return CollectionState(
                total,
                committed,
                failures.get(),
                overflow.get(),
                total - committed - overflow.get(),
                this.committed.get(),
                samplingState,
            )
        }

    private val worker =
        scope.launch {
            for (value in work) {
                var reportedFailure = false
                // Keep the same immutable callback/UUID in hand after storage failure.
                while (isActive) {
                    val row =
                        try {
                            save(value)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            failures.incrementAndGet()
                            synchronized(this@ObservationPersistence) { sampling.failSafe() }
                            if (!reportedFailure) {
                                runCatching { onFailure() }
                                reportedFailure = true
                            }
                            delay(1000)
                            continue
                        }
                    saved.incrementAndGet()
                    if (row.messageId == value.id) committed.incrementAndGet()
                    runCatching { onSaved(value, row) }
                    break
                }
            }
        }

    @Synchronized
    fun submit(
        observation: Observation,
        health: Health,
        generation: String?,
        session: kotlinx.coroutines.Deferred<TrackingSession>? = null,
    ): Boolean {
        val age = clock.elapsedMillis() - observation.elapsedMillis
        require(age >= 0) { "Unrepresentable observation age" }
        val value =
            CapturedObservation(
                Protocol.newId(),
                clock.wallMillis(),
                observation.fix.copy(fix_age_ms = age),
                health.copy(gnss_status = "fix"),
                generation,
                observation.elapsedNanos,
                session,
            )
        val candidates = sampling.state.candidates + 1
        val selected =
            try {
                sampling.select(value, policy())
            } catch (_: Exception) {
                runCatching { onFailure() }
                sampling.recover(value, candidates)
            }
        return enqueue(selected)
    }

    // Promote once per LIVE opportunity. Continuous callbacks must not move the
    // commit goal and indefinitely defer LIVE behind a slow writer/history request.
    @Synchronized
    fun prepareLive(): Boolean {
        if (sampling.state.fallback != null) {
            liveAwaiting = null
            return true
        }
        liveAwaiting?.let { goal ->
            if (saved.get() < goal) return false
            liveAwaiting = null
            return true
        }
        if (!sampling.state.boundaryCandidate) return true
        enqueue(sampling.boundary("live_opportunity"))
        liveAwaiting = submitted.get()
        return false
    }

    @Synchronized fun retainBoundary(reason: String): Boolean = enqueue(sampling.boundary(reason))

    @Synchronized
    fun checkSilence(nowElapsedMillis: Long): Boolean = enqueue(sampling.quiet(nowElapsedMillis))

    private fun enqueue(values: List<CapturedObservation>): Boolean {
        var ok = true
        for (value in values) if (!enqueue(value)) ok = false
        return ok
    }

    private fun enqueue(value: CapturedObservation): Boolean {
        submitted.incrementAndGet()
        if (work.trySend(value).isSuccess) return true
        overflow.incrementAndGet()
        sampling.failSafe()
        runCatching { onOverflow(value) }
        runCatching { onFailure() }
        return false // Caller records known loss; acquisition continues after temporary pressure.
    }

    // Normal service stop does not cancel this application-owned writer. Drain all
    // accepted callbacks even after Activity/service closes; process death can lose
    // uncommitted memory and must never be claimed as durable success.
    suspend fun finish() {
        retainBoundary("writer_finish")
        work.close()
        worker.join()
    }
}
