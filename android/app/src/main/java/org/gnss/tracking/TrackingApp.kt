package org.gnss.tracking

import android.app.Application
import kotlinx.coroutines.*
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
            { value -> repository.saveObservation(value) },
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
                operational.update {
                    it.copy(
                        error =
                            "GNSS history storage failed. Keep the app running, free storage and export diagnostics; history is not fully durable."
                    )
                }
            },
        )
    }
    val operational = MutableStateFlow(Operational())
    val recorder by lazy {
        DiagnosticRecorder(DiagnosticJournal(java.io.File(filesDir, "diagnostics")))
    }
    val diagnostics = MutableStateFlow<GnssDiagnostic?>(null)
    val latest = LatestLocation(SystemClock)
    val health by lazy { DeviceHealth(this, latest) }
    private val recoveryReady = CompletableDeferred<Unit>()
    val sos by lazy {
        SosEngine(
            repository,
            latest,
            health::snapshot,
            evidence = recorder::sos,
            beforeSave = { recoveryReady.await() },
        )
    }
    val sosNotice = MutableStateFlow<String?>(null)
    val workScope = durableWork
    @Volatile var activityVisible = false
    @Volatile var lastCaptureElapsed = 0L
    val transport by lazy { LanTransport(health::wifiNetwork) }
    val sender by lazy {
        Sender(
            repository,
            transport,
            SystemClock,
            evidence = recorder::sos,
            deliveryEvidence = { kind, report ->
                recorder.event(kind, diagnostics.value?.serviceGeneration, report = report)
            },
        ) {
            capture()
        }
    }

    suspend fun capture(): Outbound {
        val row = repository.snapshot(SystemClock.wallMillis(), null, health.snapshot())
        runCatching {
            val message = Protocol.decodeMessage(row.json)
            recorder.event(
                DiagnosticEvent.SNAPSHOT_SAVED,
                diagnostics.value?.serviceGeneration,
                report =
                    ReportEvidence(
                        reportReference(row.messageId),
                        row.type,
                        row.sequence,
                        message.captured_at,
                        null,
                    ),
            )
        }
        lastCaptureElapsed = SystemClock.elapsedMillis()
        return row
    }

    // App-owned activation survives Activity destruction. The tracking FGS keeps the process
    // available in the field; without it, sending is best effort only while this process lives.
    fun activateSos(trigger: SosTrigger) {
        sosNotice.value = "SOS trigger detected — saving on phone…"
        val detectedAt = SystemClock.wallMillis()
        val detectedElapsed = SystemClock.elapsedMillis()
        workScope.launch {
            try {
                val result = sos.activate(trigger, detectedAt, detectedElapsed)
                sosNotice.value =
                    if (result.created) "SOS saved on phone"
                    else "Repeated trigger ignored — existing SOS retained"
            } catch (e: Exception) {
                sosNotice.value =
                    "SOS NOT SAVED — storage unavailable. Retry activation; use another emergency path."
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        workScope.launch {
            try {
                repository.dao.sosHistory().forEach {
                    recorder.sos(
                        DiagnosticEvent.SOS_RESTORED,
                        SosEvidence(sosReference(it.messageId)),
                    )
                }
            } catch (e: Exception) {
                sosNotice.value = "Cannot restore saved SOS — open app and check storage"
            } finally {
                recoveryReady.complete(Unit)
            }
        }
        workScope.launch {
            while (isActive) {
                if (activityVisible && !operational.value.tracking) {
                    try {
                        val snapshot = health.snapshot()
                        operational.update { it.copy(health = snapshot) }
                        sender.step(sosOnly = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        sosNotice.value = "Saved delivery unavailable — check storage and receiver"
                    }
                }
                delay(250)
            }
        }
    }

    internal suspend fun finishForTests() {
        workScope.coroutineContext[Job]?.cancelAndJoin()
        recorder.finish()
    }
}
