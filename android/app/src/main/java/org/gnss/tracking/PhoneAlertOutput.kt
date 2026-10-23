package org.gnss.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import kotlinx.coroutines.launch

// Payload stays in the existing command journal, not a second queue/database.
data class PhoneAlertSpec(
    val mode: String,
    val message: String,
    val duration_s: Int,
    val repeat_s: Int,
) {
    companion object {
        fun parse(payload: com.google.gson.JsonObject): PhoneAlertSpec {
            require(payload.keySet() == setOf("mode", "message", "duration_s", "repeat_s"))
            require(
                payload["mode"].asJsonPrimitive.isString &&
                    payload["message"].asJsonPrimitive.isString
            )
            for (field in listOf("duration_s", "repeat_s")) require(
                payload[field].asJsonPrimitive.isNumber &&
                    payload[field].asString.matches(Regex("[0-9]+"))
            )
            val spec = com.google.gson.Gson().fromJson(payload, PhoneAlertSpec::class.java)
            require(
                spec.mode in listOf("normal", "urgent") &&
                    spec.message.codePointCount(0, spec.message.length) <= 160 &&
                    !spec.message.contains('\n') &&
                    !spec.message.contains('\r')
            )
            require(spec.duration_s in 5..120 && spec.repeat_s in 5..30)
            require(spec.mode == "urgent" || (spec.duration_s == 5 && spec.repeat_s == 5))
            return spec
        }
    }
}

interface PhoneAlertOutput {
    // Returned reason is evidence about API/settings only; never proof heard/felt.
    fun attempt(row: CommandRecord, spec: PhoneAlertSpec, remaining: Long): Pair<String, String>

    fun stop(id: String)

    fun resetAfterProcessStart() {}
}

class NotificationPhoneAlertOutput(private val context: Context) : PhoneAlertOutput {
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun tag(id: String) = "command-phone-alert:$id"

    override fun attempt(
        row: CommandRecord,
        spec: PhoneAlertSpec,
        remaining: Long,
    ): Pair<String, String> {
        val channelId = "command-phone-alert-${spec.mode}-v1"
        val channel =
            NotificationChannel(
                channelId,
                if (spec.mode == "urgent") "Urgent Command phone alerts"
                else "Command phone alerts",
                if (spec.mode == "urgent") NotificationManager.IMPORTANCE_HIGH
                else NotificationManager.IMPORTANCE_DEFAULT,
            )
        channel.enableVibration(true)
        channel.vibrationPattern =
            if (spec.mode == "urgent") longArrayOf(0, 300, 200, 300) else longArrayOf(0, 200)
        channel.setSound(
            android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
        )
        manager.createNotificationChannel(channel) // Never override the user's channel settings.
        if (
            !manager.areNotificationsEnabled() ||
                manager.getNotificationChannel(channelId)?.importance ==
                    NotificationManager.IMPORTANCE_NONE
        )
            return "alert_blocked" to
                "OS blocked/notification permission denied or channel disabled; sound/vibration not attempted"
        val action =
            Intent(context, PhoneAlertActionReceiver::class.java).apply {
                this.action = "org.gnss.tracking.ACK_PHONE_ALERT"
                data = Uri.parse("gnss-phone-alert://ack/${row.id}")
            }
        val pending =
            PendingIntent.getBroadcast(
                context,
                0,
                action,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_device)
                .setColor(
                    NotificationAppearance.colour(
                        context,
                        if (spec.mode == "urgent") R.color.danger else R.color.accent,
                    )
                )
                .setContentTitle(
                    if (spec.mode == "urgent") "Urgent Command alert" else "Command attention alert"
                )
                .setContentText(spec.message.ifBlank { "Please check in with Command" })
                .setStyle(
                    Notification.BigTextStyle()
                        .bigText(spec.message.ifBlank { "Please check in with Command" })
                )
                .setContentIntent(open)
                .addAction(
                    Notification.Action.Builder(null, "Acknowledge / Stop Alert", pending).build()
                )
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setTimeoutAfter(remaining.coerceAtLeast(1))
                .setOnlyAlertOnce(false)
                .build()
        manager.notify(tag(row.id), 1, notification)
        val audio = context.getSystemService(AudioManager::class.java)
        val restrictions = mutableListOf<String>()
        when (manager.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> Unit
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_NONE,
            NotificationManager.INTERRUPTION_FILTER_ALARMS ->
                restrictions.add("DND may restrict output")
            else -> restrictions.add("DND status unknown")
        }
        if (audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0)
            restrictions.add("notification volume muted")
        val actual = manager.getNotificationChannel(channelId)
        if (actual?.sound == null) restrictions.add("channel sound disabled")
        if (actual?.shouldVibrate() != true) restrictions.add("channel vibration disabled")
        return "output_attempted" to
            "Notification attempted; channel sound/vibration requested; actual audible/haptic output unknown${if (restrictions.isEmpty()) "" else "; OS limitations: " + restrictions.joinToString(", ")}"
    }

    override fun resetAfterProcessStart() {
        for (notification in manager.activeNotifications) {
            if (notification.tag?.startsWith("command-phone-alert:") == true)
                manager.cancel(notification.tag, notification.id)
        }
    }

    override fun stop(id: String) {
        manager.cancel(tag(id), 1)
    }
}

// Explicit immutable per-command identity; no exported remote trigger or network.
class PhoneAlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != "org.gnss.tracking.ACK_PHONE_ALERT" ||
                intent.data?.scheme != "gnss-phone-alert"
        )
            return
        val id = intent.data?.lastPathSegment ?: return
        if (!ControlCrypto.uuid(id)) return
        val result = goAsync()
        val app = context.applicationContext as TrackingApp
        app.workScope.launch {
            try {
                app.acknowledgePhoneAlert(id)
            } finally {
                result.finish()
            }
        }
    }
}
