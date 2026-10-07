package org.gnss.tracking

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build

// Readiness is local UI state. Android's optimization exemption does not describe
// every OEM background restriction and is never requested automatically.
data class FieldReadiness(
    val precise: Boolean,
    val gps: Boolean?,
    val notificationPermission: Boolean,
    val notifications: Boolean?,
    val powerSave: Boolean?,
    val optimizationExemption: Boolean?,
) {
    fun description() = buildString {
        appendLine(
            "Location permission: ${if (precise) "✓ Precise" else "Action required — precise location is required for tracking"}"
        )
        appendLine(
            "System Location: ${if (gps == true) "✓ GPS on" else "Action required — enable Location/GPS"}"
        )
        appendLine(
            "Notifications: ${if (notifications == true) "✓ Allowed" else "Action required — allow tracking notifications"}"
        )
        appendLine(
            "Battery Saver: ${when(powerSave) { true -> "Action required — Battery Saver is ON — turn it OFF for field tracking"
 false -> "✓ Off"
 null -> "Warning — unable to check" }}"
        )
        append(
            "Battery background policy: ${if (optimizationExemption == true) "✓ Android optimization exemption present; OEM setup still required" else "Warning — background battery restrictions may affect tracking. Set GNSS Tracking to Unrestricted / allow background activity in system settings."}"
        )
    }
}

class FieldPreflight(private val context: Context) {
    fun snapshot(): FieldReadiness {
        val p = PlatformPower(context).snapshot()
        val manager = context.getSystemService(NotificationManager::class.java)
        val notificationPermission =
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        return FieldReadiness(
            p.precisePermission,
            runCatching {
                    context
                        .getSystemService(LocationManager::class.java)
                        .isProviderEnabled(LocationManager.GPS_PROVIDER)
                }
                .getOrNull(),
            notificationPermission,
            runCatching {
                    notificationPermission &&
                        manager.areNotificationsEnabled() &&
                        manager.getNotificationChannel(TrackingService.CHANNEL)?.importance !=
                            NotificationManager.IMPORTANCE_NONE
                }
                .getOrNull(),
            p.powerSave,
            p.ignoringBatteryOptimizations,
        )
    }
}

// Accuracy belongs to the last real observation. Only gnss/measurement age determine
// whether it is current. This formatter never supplies data to the wire serializer.
fun Operational.fixDescription(): String {
    val accuracyText = accuracy?.let { "%.1f m".format(java.util.Locale.ROOT, it) } ?: "Unavailable"
    val ageText = ageMillis?.let { "${it / 1000} s" } ?: "Unavailable"
    return when {
        gnss == "fix" && ageMillis != null && ageMillis in 0..30000 ->
            "GPS: Fix\nAccuracy: $accuracyText\nFix age: $ageText"
        ageMillis != null -> {
            val state =
                if (gnss == "disabled") "Disabled"
                else if (ageMillis > 30000) "Stale fix" else gnss.replace('_', ' ')
            "GPS: $state\nLast fix age: $ageText\nLast known accuracy: $accuracyText"
        }
        else ->
            "GPS: ${if (gnss == "disabled") "Disabled" else "No fix"}\nLast fix: None\nAccuracy: Unavailable"
    }
}
