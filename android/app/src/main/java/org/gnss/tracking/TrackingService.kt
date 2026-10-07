package org.gnss.tracking

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.*
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update

class TrackingService : Service() {
    private val app
        get() = application as TrackingApp

    private val repository
        get() = app.repository

    private val failureHandler = CoroutineExceptionHandler { _, e ->
        app.recorder.event(DiagnosticEvent.LOOP_FAILURE, generation)
        loopError = "Unexpected service coroutine failure: ${e.javaClass.simpleName}"
        app.operational.update {
            it.copy(
                starting = false,
                error = "Tracking failed. Open the app and Start Tracking again.",
            )
        }
        Log.e(TAG, loopError!!)
        Handler(Looper.getMainLooper()).post { stopSelf() }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + failureHandler)
    private val generation = UUID.randomUUID().toString()
    private var startReason = "not_started"
    private lateinit var source: PlatformLocationSource
    private lateinit var power: PlatformPower
    @Volatile private var reportingIteration: Long? = null
    @Volatile private var senderIteration: Long? = null
    @Volatile private var effectiveInterval: Int? = null
    @Volatile private var wifiAvailable: Boolean? = null
    @Volatile private var lastAckElapsed: Long? = null
    @Volatile private var observedAck: String? = null
    private var ackInitialized = false
    @Volatile private var deliveryPaused: Boolean? = null
    @Volatile private var deliveryError: Boolean? = null
    @Volatile private var loopError: String? = null
    private val latest = LatestLocation(SystemClock)
    private lateinit var resources: TrackingResources
    private lateinit var health: DeviceHealth
    private lateinit var sender: Sender
    private lateinit var connectivity: ConnectivityManager
    private var callbackRegistered = false
    private var running = false
    @Volatile private var lastCaptureElapsed = 0L
    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    try {
                        sender.connectivityRestored()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        app.recorder.event(DiagnosticEvent.LOOP_FAILURE, generation)
                        loopError = "Connectivity recovery failed: ${e.javaClass.simpleName}"
                        app.operational.update {
                            it.copy(error = "Cannot prepare saved delivery: ${e.message}")
                        }
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        app.recorder.event(DiagnosticEvent.SERVICE_CREATE, generation)
        source =
            PlatformLocationSource(
                this,
                latest,
                diagnosticEvent = { kind, state -> app.recorder.event(kind, generation, state) },
            )
        power = PlatformPower(this)
        resources =
            TrackingResources(
                source,
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gnss:tracking"),
                diagnosticEvent = { app.recorder.event(it, generation) },
            )
        health = DeviceHealth(this, latest)
        connectivity = getSystemService(ConnectivityManager::class.java)
        sender = Sender(repository, LanTransport(health::wifiNetwork), SystemClock, ::capture)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            startReason = if (intent == null) "sticky_restart" else "user_start"
            app.operational.update { it.copy(starting = true, error = null) }
        }
        app.recorder.event(
            DiagnosticEvent.SERVICE_START,
            generation,
            startReason =
                if (intent == null) ServiceStartReason.STICKY_RESTART
                else ServiceStartReason.USER_START,
        )
        if (
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            latest.enabled = null
            loopError = "Precise location permission unavailable at service start"
            runCatching { resources.stop() }
            runCatching { publishDiagnostics(power.snapshot(), true) }
            app.operational.value =
                Operational(error = "Precise location permission required to track")
            stopSelf()
            return START_NOT_STICKY
        }
        // Promote immediately, before storage/network work. The Activity is the only
        // start entrypoint; null intent is the platform's sticky process recovery.
        val notifications = getSystemService(NotificationManager::class.java)
        try {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, "Tracking", NotificationManager.IMPORTANCE_LOW)
            )
            if (android.os.Build.VERSION.SDK_INT >= 29)
                startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(1, notification())
            app.recorder.event(DiagnosticEvent.FOREGROUND_PROMOTED, generation)
            if (!running) {
                // Register synchronously after location FGS promotion, before any
                // Room suspension or Activity transition. No Activity owns this source.
                resources.start()
            }
        } catch (e: Exception) {
            runCatching { resources.stop() }
            app.recorder.event(DiagnosticEvent.FOREGROUND_FAILED, generation)
            loopError = "Foreground/GNSS startup failed: ${e.javaClass.simpleName}"
            Log.e(TAG, loopError!!)
            runCatching { publishDiagnostics(power.snapshot(), true) }
            app.operational.value =
                Operational(
                    error =
                        "${e.message ?: "Unable to start GNSS"}. Open the app and Start Tracking again."
                )
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            // Root supervisor children: maintenance never waits for Room/network;
            // a child reporting failure cannot cancel the sender or native source.
            scope.launch(Dispatchers.Main) { maintenanceLoop() }
            scope.launch {
                try {
                    if (intent == null && !repository.state().tracking) {
                        withContext(Dispatchers.Main) { stopSelf() }
                        return@launch
                    }
                    repository.edit { it.copy(tracking = true) }
                    capture() // Restart always saves a new current snapshot before backlog.
                    withContext(Dispatchers.Main) {
                        connectivity.registerNetworkCallback(
                            NetworkRequest.Builder()
                                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                                .build(),
                            callback,
                        )
                        callbackRegistered = true
                        app.operational.update {
                            it.copy(tracking = true, starting = false, error = null)
                        }
                    }
                    scope.launch { reportingLoop() }
                    scope.launch { sendingLoop() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.recorder.event(DiagnosticEvent.LOOP_FAILURE, generation)
                    loopError = "Tracking initialization failed: ${e.javaClass.simpleName}"
                    app.operational.update {
                        it.copy(
                            starting = false,
                            error =
                                "Cannot initialize tracking. Open the app and Start Tracking again: ${e.message}",
                        )
                    }
                    // Cleanup must not wait on the same store that just failed.
                    withContext(Dispatchers.Main) {
                        runCatching { publishDiagnostics(power.snapshot(), true) }
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun capture(): Outbound {
        val fix = latest.currentFix()
        val snapshot =
            health.snapshot().let { if (fix != null) it.copy(gnss_status = "fix") else it }
        val row = repository.snapshot(SystemClock.wallMillis(), fix, snapshot)
        lastCaptureElapsed = SystemClock.elapsedMillis()
        return row
    }

    private suspend fun reportingLoop() {
        var appliedConfig: ConfigState? = null
        var interval = 10
        var due = 0L
        while (currentCoroutineContext().isActive) {
            try {
                val state = repository.state()
                val config = state.config()
                val newInterval = config.effective_reporting_interval_s
                effectiveInterval = newInterval
                deliveryPaused = state.deliveryPaused
                deliveryError = state.operationalError != null
                if (ackInitialized && state.lastAck != observedAck) {
                    // Receipt wall timestamp may be from Command's clock; use when we observe
                    // change.
                    lastAckElapsed = state.lastAck?.let { SystemClock.elapsedMillis() }
                }
                observedAck = state.lastAck
                ackInitialized = true
                val now = SystemClock.elapsedMillis()
                if (appliedConfig != config) {
                    appliedConfig = config
                    interval = newInterval
                    due = now + interval * 1000L
                }
                // A recovery snapshot also counts as a current report.
                if (now >= due) {
                    if (now - lastCaptureElapsed >= interval * 1000L) capture()
                    due = lastCaptureElapsed + interval * 1000L
                }
                val h = health.snapshot()
                wifiAvailable = h.wifi_connected
                app.operational.update {
                    it.copy(
                        health = h,
                        link =
                            if (h.wifi_connected == true) "Wi-Fi connected"
                            else "Wi-Fi unavailable",
                        error = state.operationalError,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.recorder.event(DiagnosticEvent.LOOP_FAILURE, generation)
                loopError = "Reporting failed: ${e.javaClass.simpleName}"
                app.operational.update { it.copy(error = "Cannot save report: ${e.message}") }
                // A failing store must not be required to save its own error.
            }
            reportingIteration = SystemClock.elapsedMillis()
            delay(1000)
        }
    }

    private suspend fun sendingLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                sender.step()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.recorder.event(DiagnosticEvent.LOOP_FAILURE, generation)
                loopError = "Sending failed: ${e.javaClass.simpleName}"
                app.operational.update { it.copy(error = "Delivery paused: ${e.message}") }
            }
            senderIteration = SystemClock.elapsedMillis()
            delay(250)
        }
    }

    private suspend fun maintenanceLoop() {
        var sampledAt: Long? = null
        while (currentCoroutineContext().isActive) {
            try {
                resources.renew()
                val p = power.snapshot()
                if (!p.precisePermission) {
                    latest.enabled = null
                    runCatching { resources.stop() }
                        .onFailure {
                            Log.w(TAG, "Permission-loss cleanup failed: ${it.javaClass.simpleName}")
                        }
                    loopError = "Precise location permission lost"
                    app.operational.update {
                        it.copy(
                            tracking = false,
                            starting = false,
                            error =
                                "Precise location permission lost. Grant Precise location and Start Tracking again.",
                        )
                    }
                    runCatching { publishDiagnostics(p, true) }
                    stopSelf()
                    return
                }
                source.refreshProvider()
                app.operational.update {
                    it.copy(
                        gnss = latest.status(),
                        accuracy = latest.observation?.fix?.horizontal_accuracy_m,
                        ageMillis = latest.age(),
                        warning = latest.clockWarning() ?: p.warning(),
                    )
                }
                val now = SystemClock.elapsedMillis()
                val record = sampledAt == null || now - sampledAt >= 60000
                publishDiagnostics(p, record)
                if (record) sampledAt = now
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.recorder.event(DiagnosticEvent.LOOP_FAILURE, generation)
                loopError = "GNSS maintenance failed: ${e.javaClass.simpleName}"
                app.operational.update {
                    it.copy(
                        gnss = latest.status(),
                        accuracy = latest.observation?.fix?.horizontal_accuracy_m,
                        ageMillis = latest.age(),
                        error = "Cannot maintain GNSS: ${e.message}",
                    )
                }
                val now = SystemClock.elapsedMillis()
                val record = sampledAt == null || now - sampledAt >= 60000
                runCatching { publishDiagnostics(power.snapshot(), record) }
                if (record) sampledAt = now
            }
            delay(1000)
        }
    }

    private fun publishDiagnostics(p: PowerState, record: Boolean) {
        val now = SystemClock.elapsedMillis()
        fun age(at: Long?) = at?.let { (now - it).coerceAtLeast(0) }
        val d = source.diagnostics
        val snapshot =
            GnssDiagnostic(
                utc(SystemClock.wallMillis()),
                now,
                generation,
                startReason,
                if (android.os.Build.VERSION.SDK_INT >= 29) foregroundServiceType else null,
                d,
                latest.enabled,
                age(d.lastLocationCallback),
                age(d.lastGnssCallback),
                latest.age(),
                latest.currentFix() != null,
                d.callbacksQuiet(now),
                resources.held,
                resources.renewals,
                p,
                age(reportingIteration),
                age(senderIteration),
                lastCaptureElapsed.takeIf { it > 0 }?.let { age(it) },
                loopError,
                effectiveInterval,
                wifiAvailable,
                age(lastAckElapsed),
                deliveryPaused = deliveryPaused,
                deliveryError = deliveryError,
                lastKnownAccuracyM = latest.observation?.fix?.horizontal_accuracy_m,
            )
        app.diagnostics.value = snapshot
        app.recorder.sample(snapshot)
        if (record) {
            Log.i(
                TAG,
                "generation=$generation start=$startReason registered=${d.registered} status_registered=${d.statusRegistered} provider=${latest.enabled} location_callback_age_ms=${snapshot.locationCallbackAgeMs} gnss_callback_age_ms=${snapshot.gnssCallbackAgeMs} fix_age_ms=${latest.age()} quiet=${snapshot.callbacksQuiet} wake=${resources.held} power_save=${p.powerSave} location_power_mode=${p.locationPowerSaveMode}",
            )
        }
    }

    private fun notification(): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("GNSS tracking active")
            .setContentText("Collecting location. Open to view status or stop tracking.")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        // Attempt every cleanup even if a platform binder throws during permission
        // revocation/provider failure. TrackingResources releases the lock in finally.
        runCatching { resources.stop() }
            .onFailure { Log.w(TAG, "GNSS cleanup failed: ${it.javaClass.simpleName}") }
        if (callbackRegistered)
            runCatching { connectivity.unregisterNetworkCallback(callback) }
                .onFailure { Log.w(TAG, "Network cleanup failed: ${it.javaClass.simpleName}") }
        runCatching { publishDiagnostics(power.snapshot(), false) }
        app.recorder.event(DiagnosticEvent.SERVICE_DESTROY, generation)
        app.operational.update { it.copy(tracking = false, starting = false) }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } finally {
            super.onDestroy()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "tracking"
        const val TAG = "GnssTracking"
    }
}
