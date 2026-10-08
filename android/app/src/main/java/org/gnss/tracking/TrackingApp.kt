package org.gnss.tracking

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

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
)

class TrackingApp : Application() {
    val repository by lazy { Repository(TrackingDatabase.open(this)) }
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
    val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var activityVisible = false
    @Volatile var lastCaptureElapsed = 0L
    val sender by lazy {
        Sender(
            repository,
            LanTransport(health::wifiNetwork),
            SystemClock,
            evidence = recorder::sos,
        ) {
            capture()
        }
    }

    suspend fun capture(): Outbound {
        val fix = latest.currentFix()
        val snapshot =
            health.snapshot().let { if (fix != null) it.copy(gnss_status = "fix") else it }
        val row = repository.snapshot(SystemClock.wallMillis(), fix, snapshot)
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
