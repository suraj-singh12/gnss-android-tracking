package org.gnss.tracking

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class Operational(
    val tracking: Boolean = false,
    val gnss: String = "unknown",
    // UI metadata from the last real observation, even after it becomes stale.
    val accuracy: Double? = null,
    val ageMillis: Long? = null,
    val health: Health = Health(),
    val link: String = "Awaiting Command",
    val error: String? = null,
    val warning: String? = null,
    val starting: Boolean = false,
    val stopping: Boolean = false,
)

class TrackingApp : Application() {
    private val durableWork =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )

    private var stopWork: kotlinx.coroutines.Job? = null

    private val collectionLoss = mutableMapOf<kotlinx.coroutines.Deferred<TrackingSession>, Long>()
    private var lossWork: kotlinx.coroutines.Job? = null

    // Aggregate loss metadata, not observations. A service Stop cannot cancel its
    // persistence and disk failure cannot create an unbounded job per callback.
    @Synchronized
    fun recordCollectionLoss(session: kotlinx.coroutines.Deferred<TrackingSession>) {
        collectionLoss[session] = (collectionLoss[session] ?: 0) + 1
        if (lossWork?.isActive == true) return
        lossWork =
            durableWork.launch {
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val entry =
                        synchronized(this@TrackingApp) {
                            collectionLoss.entries
                                .firstOrNull()
                                ?.let { it.key to it.value }
                                .also { if (it == null) lossWork = null }
                        } ?: break
                    try {
                        repository.noteCollectionLoss(entry.first, entry.second)
                        synchronized(this@TrackingApp) {
                            val remaining = (collectionLoss[entry.first] ?: 0) - entry.second
                            if (remaining == 0L) collectionLoss.remove(entry.first)
                            else collectionLoss[entry.first] = remaining
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        kotlinx.coroutines.delay(1000)
                    }
                }
            }
    }

    @Synchronized
    fun finishTracking(): kotlinx.coroutines.Job {
        stopWork
            ?.takeIf { it.isActive }
            ?.let {
                return it
            }
        val startup = sessionStartup
        return durableWork
            .launch {
                startup?.join()
                var failed = false
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    try {
                        repository.endTracking()
                        operational.update {
                            it.copy(
                                tracking = false,
                                stopping = false,
                                error = if (failed) null else it.error,
                            )
                        }
                        break
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!failed) recorder.event(DiagnosticEvent.LOOP_FAILURE)
                        failed = true
                        operational.update {
                            it.copy(error = "Cannot save Stop; waiting for session storage")
                        }
                        kotlinx.coroutines.delay(1000)
                    }
                }
            }
            .also { stopWork = it }
    }

    var sessionReady = kotlinx.coroutines.CompletableDeferred<TrackingSession>()
    @Volatile var sessionStartup: kotlinx.coroutines.Job? = null
    val repository by lazy { Repository(TrackingDatabase.open(this)) }
    val observationPersistence by lazy {
        ObservationPersistence(
            repository::saveObservation,
            scope = durableWork,
            onSaved = { value, row ->
                recorder.event(
                    DiagnosticEvent.SNAPSHOT_SAVED,
                    value.generation,
                    report =
                        ReportEvidence(
                            reportReference(row.messageId),
                            row.type,
                            row.sequence,
                            utc(value.capturedMillis),
                            value.fix.observed_at,
                        ),
                )
            },
            onFailure = {
                recorder.event(DiagnosticEvent.LOOP_FAILURE)
                operational.value =
                    operational.value.copy(
                        error =
                            "GNSS history storage failed. Keep the app running, free storage and export diagnostics; history is not fully durable."
                    )
            },
        )
    }
    val operational = MutableStateFlow(Operational())
    val recorder by lazy {
        DiagnosticRecorder(DiagnosticJournal(java.io.File(filesDir, "diagnostics")))
    }
    val diagnostics = MutableStateFlow<GnssDiagnostic?>(null)
}
