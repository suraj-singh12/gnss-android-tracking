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
)

class ObservationPersistence(
    private val save: suspend (CapturedObservation) -> Outbound,
    private val onSaved: (CapturedObservation, Outbound) -> Unit = { _, _ -> },
    private val onFailure: () -> Unit = {},
    private val clock: Clock = SystemClock,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    capacity: Int = 1024,
) {
    private val work = Channel<CapturedObservation>(capacity)
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
        submitted.incrementAndGet()
        if (work.trySend(value).isSuccess) return true
        overflow.incrementAndGet()
        runCatching { onFailure() }
        return false // Caller records known loss; acquisition continues after temporary pressure.
    }

    // Normal service stop does not cancel this application-owned writer. Drain all
    // accepted callbacks even after Activity/service closes; process death can lose
    // uncommitted memory and must never be claimed as durable success.
    suspend fun finish() {
        work.close()
        worker.join()
    }
}
