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
            stopSelf()
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
                    while (isActive && SystemClock.elapsedMillis() - began < 5 * 60 * 60 * 1000L) {
                        if (app.operational.value.historyPaused || app.operational.value.tracking)
                            break
                        if (!app.operational.value.drainStarting && !app.sender.aggressive) break
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
                                .setContentText(app.sender.syncProgress.mode)
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
                    stopSelf()
                }
        return START_NOT_STICKY
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
