package org.gnss.tracking

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.*
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*

class TrackingService : Service() {
    private val app
        get() = application as TrackingApp

    private val repository
        get() = app.repository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
                scope.launch { sender.connectivityRestored() }
            }
        }

    override fun onCreate() {
        super.onCreate()
        resources =
            TrackingResources(
                PlatformLocationSource(this, latest),
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gnss:tracking"),
            )
        health = DeviceHealth(this, latest)
        connectivity = getSystemService(ConnectivityManager::class.java)
        sender = Sender(repository, LanTransport(health::wifiNetwork), SystemClock, ::capture)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            app.operational.value =
                Operational(error = "Precise location permission required to track")
            stopSelf()
            return START_NOT_STICKY
        }
        // Promote immediately, before storage/network work. The Activity is the only
        // start entrypoint; null intent is the platform's sticky process recovery.
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Tracking", NotificationManager.IMPORTANCE_LOW)
        )
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29)
                startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(1, notification())
            if (!running) {
                // Register synchronously after location FGS promotion, before any
                // Room suspension or Activity transition. No Activity owns this source.
                resources.start()
            }
        } catch (e: Exception) {
            resources.stop()
            app.operational.value = Operational(error = e.message ?: "Unable to start GNSS")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
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
                    }
                    launch { reportingLoop() }
                    launch { sendingLoop() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.operational.value = Operational(error = e.message ?: "Unable to start GNSS")
                    runCatching {
                        repository.edit { it.copy(tracking = false, operationalError = e.message) }
                    }
                    withContext(Dispatchers.Main) { stopSelf() }
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
        var appliedConfig = repository.state().config()
        var interval = appliedConfig.effective_reporting_interval_s
        var due = SystemClock.elapsedMillis() + interval * 1000L
        while (currentCoroutineContext().isActive) {
            try {
                resources.renew()
                val state = repository.state()
                val config = state.config()
                val newInterval = config.effective_reporting_interval_s
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
                app.operational.value =
                    Operational(
                        true,
                        latest.status(),
                        latest.currentFix()?.horizontal_accuracy_m,
                        latest.age(),
                        h,
                        if (h.wifi_connected == true) "Wi-Fi connected" else "Wi-Fi unavailable",
                        latest.clockWarning() ?: state.operationalError,
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.operational.value =
                    app.operational.value.copy(error = "Cannot save report: ${e.message}")
                // Persist an error if storage still permits it, without masking full-disk failure.
                runCatching {
                    repository.edit {
                        it.copy(operationalError = "Cannot save report: ${e.message}")
                    }
                }
            }
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
                app.operational.value =
                    app.operational.value.copy(error = "Delivery paused: ${e.message}")
            }
            delay(250)
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
        resources.stop()
        if (callbackRegistered) connectivity.unregisterNetworkCallback(callback)
        app.operational.value = app.operational.value.copy(tracking = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "tracking"
    }
}
