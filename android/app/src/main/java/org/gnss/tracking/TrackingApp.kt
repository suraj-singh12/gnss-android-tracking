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
    val historyPaused: Boolean = false,
    val transferActive: Boolean = false,
    val drainStarting: Boolean = false,
)

class TrackingApp : Application() {
    private var drainWork: Job? = null
    private val drainGeneration = java.util.concurrent.atomic.AtomicLong()
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
    val persistencePolicy by lazy {
        LocalPersistencePolicy(getSharedPreferences("gnss-persistence", MODE_PRIVATE))
    }
    val observationPersistence by lazy {
        ObservationPersistence(
            { value -> repository.saveObservation(value) },
            scope = durableWork,
            policy = { persistencePolicy.state },
            onOverflow = { value -> value.session?.let { recordCollectionLoss(it) } },
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
                            "GNSS capture policy or persistence failed. Dense fallback is active. Check Diagnostics and storage; uncommitted history is not yet durable."
                    )
                }
            },
        )
    }
    val operational = MutableStateFlow(Operational())
    val recorder by lazy {
        DiagnosticRecorder(DiagnosticJournal(java.io.File(filesDir, "diagnostics")))
    }
    // Process-local opt-in. It defaults off on cold start and writes only to Diagnostics.
    val physicalButtonTest by lazy {
        PhysicalButtonTestController(record = recorder::physicalButton)
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
            beforeSave = {
                observationPersistence.retainBoundary("sos_boundary")
                recoveryReady.await()
            },
        )
    }
    val sosNotice = MutableStateFlow<String?>(null)
    val workScope = durableWork
    @Volatile var activityVisible = false
    @Volatile var lastCaptureElapsed = 0L
    val transport by lazy { LanTransport(health::wifiNetwork) }
    val controlKeys by lazy { ControlKeyStore(this) }
    val commandControl: CommandControl by lazy {
        CommandControl(
            repository,
            transport,
            controlKeys::load,
            adaptive = { enabled, at -> persistencePolicy.setEnabled(enabled, at) },
            adaptiveFallback = { observationPersistence.samplingState.fallback },
            sync = {
                operational.update { it.copy(historyPaused = false) }
                sender.requestDrain()
            },
            tracking = { start -> executeRemoteTracking(start) },
            alertOutput = NotificationPhoneAlertOutput(this),
            alertRuntimeAllowed = {
                activityVisible || operational.value.tracking || operational.value.transferActive
            },
        )
    }

    internal suspend fun executeRemoteTracking(start: Boolean): Pair<String, String> {
        if (start) {
            if (operational.value.tracking && repository.state().tracking)
                return "applied" to "Tracking already active"
            if (!activityVisible)
                return "blocked" to
                    "OS background restriction: open Android app to permit remote Start"
            val ready = FieldPreflight(this).snapshot()
            if (
                !ready.precise ||
                    ready.gps != true ||
                    ready.notifications != true ||
                    ready.powerSave != false
            )
                return "blocked" to
                    "Needs user interaction: precise location, GPS, notifications and Battery Saver preflight required"
            return try {
                withContext(Dispatchers.Main) {
                    if (!activityVisible) throw IllegalStateException("Activity hidden")
                    startForegroundService(
                        android.content.Intent(this@TrackingApp, TrackingService::class.java)
                    )
                }
                val active =
                    withTimeoutOrNull(3000) {
                        while (!operational.value.tracking) {
                            if (operational.value.error != null) break
                            delay(25)
                        }
                        operational.value.tracking
                    }
                if (active == true && repository.state().tracking)
                    "applied" to "Tracking active: service and durable session verified"
                else
                    "unknown" to
                        "Start attempted; actual service activation not confirmed; inspect Android"
            } catch (_: Exception) {
                "blocked" to
                    "OS denied foreground location service; open Android and use local Start"
            }
        }
        withContext(Dispatchers.Main) {
            stopService(android.content.Intent(this@TrackingApp, TrackingService::class.java))
        }
        val stopped =
            withTimeoutOrNull(3000) {
                while (operational.value.tracking || operational.value.starting) delay(25)
                finishTracking().join()
                !repository.state().tracking
            }
        return if (stopped == true)
            "applied" to "Tracking stopped: collection service and durable session verified"
        else "unknown" to "Stop attempted; service/session teardown not confirmed"
    }

    val sender: Sender by lazy {
        Sender(
            repository,
            transport,
            SystemClock,
            evidence = recorder::sos,
            controlStep = { withContext(NonCancellable) { commandControl.step() } },
            prepareLive = {
                // Promote the actual latest candidate at a LIVE opportunity without
                // waiting for SQLite or delaying SOS. Retry selection on the next
                // sender iteration once the shared writer has committed it.
                !persistencePolicy.state.enabled || observationPersistence.prepareLive()
            },
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

    fun syncAllNow() {
        if (
            !operational.value.historyPaused &&
                (sender.aggressive || operational.value.drainStarting)
        )
            return
        val token = drainGeneration.incrementAndGet()
        operational.update { it.copy(historyPaused = false, drainStarting = true) }
        drainWork =
            workScope.launch {
                try {
                    if (!operational.value.historyPaused) sender.requestDrain()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    operational.update {
                        it.copy(error = "Cannot resume saved delivery — check storage")
                    }
                } finally {
                    if (drainGeneration.get() == token)
                        operational.update { it.copy(drainStarting = false) }
                }
            }
    }

    fun pauseHistory() {
        drainGeneration.incrementAndGet()
        drainWork?.cancel()
        operational.update { it.copy(historyPaused = true, drainStarting = false) }
        sender.pauseDrain()
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
            // Policy diagnostics must never gate SOS recovery or activation.
            runCatching {
                recorder.event(
                    DiagnosticEvent.PERSISTENCE_POLICY_RESTORED,
                    persistencePolicy = persistencePolicy.state,
                )
            }
        }
        workScope.launch {
            var visibleWifi: android.net.Network? = null
            while (isActive) {
                runCatching { commandControl.tickAlerts() }
                if (
                    (activityVisible || operational.value.transferActive) &&
                        !operational.value.tracking
                ) {
                    try {
                        val wifi = health.wifiNetwork()
                        if (wifi != visibleWifi) {
                            visibleWifi = wifi
                            transport.invalidate()
                            if (wifi != null) sender.connectivityRestored()
                        }
                        val snapshot = health.snapshot()
                        operational.update { it.copy(health = snapshot) }
                        sender.step(
                            retainedOnly = true,
                            pauseHistory = operational.value.historyPaused,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        sosNotice.value = "Saved delivery unavailable — check storage and receiver"
                    }
                }
                if (
                    (!activityVisible && !operational.value.transferActive) ||
                        operational.value.tracking
                )
                    visibleWifi = null
                delay(
                    if (
                        (activityVisible || operational.value.transferActive) &&
                            !operational.value.tracking &&
                            sender.attemptedLastStep
                    )
                        1
                    else 250
                )
            }
        }
    }

    internal suspend fun finishForTests() {
        workScope.coroutineContext[Job]?.cancelAndJoin()
        recorder.finish()
    }
}
