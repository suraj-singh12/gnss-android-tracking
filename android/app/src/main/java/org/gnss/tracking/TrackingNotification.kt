package org.gnss.tracking

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration

/** Presentation only: both actions enter the existing visible Activity workflows. */
internal object TrackingNotification {
    const val SYNC = "org.gnss.tracking.NOTIFICATION_SYNC"
    const val STOP = "org.gnss.tracking.NOTIFICATION_CONFIRM_STOP"

    fun build(context: Context): Notification {
        fun open(action: String?, request: Int) =
            PendingIntent.getActivity(
                context,
                request,
                Intent(context, MainActivity::class.java).apply {
                    this.action = action
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(context, TrackingService.CHANNEL)
            .setSmallIcon(R.drawable.ic_device)
            .setContentTitle("GNSS tracking active")
            .setContentText("Collecting location · open for status")
            .setContentIntent(open(null, 70))
            .setColor(NotificationAppearance.colour(context, R.color.accent))
            .addAction(Notification.Action.Builder(null, "Sync Now", open(SYNC, 71)).build())
            .addAction(Notification.Action.Builder(null, "Stop Tracking", open(STOP, 72)).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}

/** Reads the existing preference; Android still owns system notification surfaces. */
internal object NotificationAppearance {
    fun colour(context: Context, resource: Int): Int {
        val night =
            context
                .getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .getBoolean("night", false)
        val config =
            Configuration(context.resources.configuration).apply {
                uiMode =
                    (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) Configuration.UI_MODE_NIGHT_YES
                        else Configuration.UI_MODE_NIGHT_NO
            }
        return context.createConfigurationContext(config).getColor(resource)
    }

    fun refresh(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Recolour retained notifications without creating new output/ACK records,
        // extending alert expiry, or replaying sound/vibration.
        for (active in manager.activeNotifications) {
            val resource =
                when {
                    active.notification.channelId == TrackingService.CHANNEL -> R.color.accent
                    active.tag?.startsWith("command-phone-alert:") == true ->
                        if (active.notification.channelId.contains("urgent")) R.color.danger
                        else R.color.accent
                    else -> continue
                }
            val originalTimeout = active.notification.timeoutAfter
            val remaining =
                if (originalTimeout > 0)
                    active.postTime + originalTimeout - System.currentTimeMillis()
                else 0L
            if (originalTimeout > 0 && remaining <= 0) continue
            val notification =
                Notification.Builder.recoverBuilder(context, active.notification)
                    .setColor(colour(context, resource))
                    .setOnlyAlertOnce(true)
                    .setTimeoutAfter(remaining)
                    .build()
            manager.notify(active.tag, active.id, notification)
        }
    }
}
