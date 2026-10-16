package org.gnss.tracking

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update

// User-visible execution lifetime only. TrackingApp still owns the single Sender;
// this service never opens a provider, creates a measurement or owns another queue.
class RetainedTransferService : Service() {
    private val app
        get() = application as TrackingApp

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var work: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "pause") {
            app.pauseHistory()
            // Pause history, not the lifetime needed by a saved emergency retry.
            if (work == null) stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "history-transfer",
                "Saved history transfer",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val pause =
            PendingIntent.getService(
                this,
                0,
                Intent(this, RetainedTransferService::class.java).setAction("pause"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification.Builder(this, "history-transfer")
                .setSmallIcon(R.drawable.ic_device)
                .setContentTitle("Synchronizing saved GNSS")
                .setContentText("Saved history only. Pause preserves saved data.")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Pause", pause).build())
                .build()
        if (android.os.Build.VERSION.SDK_INT >= 29)
            startForeground(22, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(22, notification)
        app.operational.update { it.copy(transferActive = true) }
        if (work == null)
            work =
                scope.launch {
                    val began = SystemClock.elapsedMillis()
                    // Below Android 15's six-hour dataSync budget. No restart/boot promise.
                    try {
                        while (
                            isActive && SystemClock.elapsedMillis() - began < 5 * 60 * 60 * 1000L
                        ) {
                            val status = retainedWorkStatus() ?: break
                            if (wake?.isHeld != true) {
                                wake =
                                    getSystemService(PowerManager::class.java)
                                        .newWakeLock(
                                            PowerManager.PARTIAL_WAKE_LOCK,
                                            "gnss:history-transfer",
                                        )
                                wake?.acquire(60000)
                            }
                            manager.notify(
                                22,
                                Notification.Builder.recoverBuilder(
                                        this@RetainedTransferService,
                                        notification,
                                    )
                                    .setContentText(status)
                                    .build(),
                            )
                            delay(15000)
                        }
                        if (SystemClock.elapsedMillis() - began >= 5 * 60 * 60 * 1000L) {
                            app.pauseHistory()
                            app.operational.update {
                                it.copy(error = "Transfer time budget reached; reopen to resume")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        app.operational.update {
                            it.copy(error = "Saved transfer state unavailable; reopen to resume")
                        }
                    } finally {
                        stopSelf()
                    }
                }
        return START_NOT_STICKY
    }

    // Same Room queue and Sender; future SOS deadlines must keep execution eligible.
    internal suspend fun retainedWorkStatus(): String? {
        val op = app.operational.value
        if (op.tracking) return null // Existing location service takes over delivery.
        if (app.repository.dao.pendingSosExists()) {
            val state = app.repository.state()
            if (!state.deliveryPaused && state.endpoint.isNotEmpty())
                return "Saved SOS awaiting receipt; ${if (op.historyPaused) "history paused" else "history transfer separate"}"
        }
        if (op.historyPaused || !op.drainStarting && !app.sender.aggressive) return null
        return app.sender.syncProgress.mode
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        app.pauseHistory()
        app.operational.update {
            it.copy(error = "Android stopped background transfer; reopen to resume")
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (wake?.isHeld == true) wake?.release()
        app.operational.update { it.copy(transferActive = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
