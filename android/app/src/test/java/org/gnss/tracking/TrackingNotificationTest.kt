package org.gnss.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.location.LocationManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class)
class TrackingNotificationTest {
    private fun views(v: View): List<View> =
        listOf(v) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) }
            else emptyList()

    private suspend fun until(check: () -> Boolean) =
        withTimeout(10000) {
            while (!check()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }

    @Test
    fun nativeActionsOpenVisibleSyncAndProtectedStopWithoutStartingGnss() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        shadowOf(app)
            .grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        shadowOf(app.getSystemService(LocationManager::class.java))
            .setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val notification = TrackingNotification.build(app)
        assertEquals(
            listOf("Sync Now", "Stop Tracking"),
            notification.actions.map { it.title.toString() },
        )
        val intents =
            notification.actions.map { action ->
                val pending = shadowOf(action.actionIntent)
                assertTrue(pending.flags and PendingIntent.FLAG_IMMUTABLE != 0)
                assertFalse(pending.isService)
                assertEquals(
                    MainActivity::class.java.name,
                    pending.savedIntent.component!!.className,
                )
                pending.savedIntent
            }
        assertEquals(
            listOf(TrackingNotification.SYNC, TrackingNotification.STOP),
            intents.map { it.action },
        )
        // A completed/blocked drain may clear its transient flags before the UI is polled.
        // Resuming the existing pause and starting retained transfer are stable dispatch evidence.
        app.pauseHistory()
        val controller = Robolectric.buildActivity(MainActivity::class.java, intents[0]).setup()
        try {
            until { !app.operational.value.historyPaused && controller.get().intent.action == null }
            assertEquals(
                RetainedTransferService::class.java.name,
                shadowOf(app).nextStartedService.component!!.className,
            )
            assertFalse(app.operational.value.tracking)
            assertNull(
                shadowOf(app).nextStartedService?.takeIf {
                    it.component?.className == TrackingService::class.java.name
                }
            )
            assertTrue(
                shadowOf(app.getSystemService(LocationManager::class.java))
                    .getLocationUpdateListeners()
                    .isEmpty()
            )
            assertNull(controller.get().intent.action)
            app.operational.value = app.operational.value.copy(tracking = true)
            controller.newIntent(intents[1])
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("Stop tracking on this phone?", shadowOf(dialog).title.toString())
            assertTrue(app.operational.value.tracking)
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
            assertTrue(app.operational.value.tracking)
            controller.recreate()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertNull(controller.get().intent.action)
        } finally {
            controller.pause().stop().destroy()
            app.finishForTests()
        }
    }

    @Test
    fun themeRefreshRetainsAlertIdentityAndHumanAckAction() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        try {
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    "command-phone-alert-urgent-v1",
                    "Alerts",
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
            val original =
                Notification.Builder(app, "command-phone-alert-urgent-v1")
                    .setSmallIcon(R.drawable.ic_device)
                    .setContentTitle("Urgent Command alert")
                    .setOngoing(true)
                    .addAction(
                        Notification.Action.Builder(
                                null,
                                "Acknowledge / Stop Alert",
                                PendingIntent.getActivity(
                                    app,
                                    99,
                                    Intent(app, MainActivity::class.java),
                                    PendingIntent.FLAG_IMMUTABLE,
                                ),
                            )
                            .build()
                    )
                    .build()
            manager.notify("command-phone-alert:fixture", 1, original)
            app.getSharedPreferences("appearance", 0).edit().putBoolean("night", true).commit()
            NotificationAppearance.refresh(app)
            val active = manager.activeNotifications.single()
            assertEquals("command-phone-alert:fixture", active.tag)
            assertEquals("Acknowledge / Stop Alert", active.notification.actions.single().title)
            assertEquals(
                original.actions.single().actionIntent,
                active.notification.actions.single().actionIntent,
            )
            assertTrue(active.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertEquals(original.timeoutAfter, active.notification.timeoutAfter)
            assertEquals(
                NotificationAppearance.colour(app, R.color.danger),
                active.notification.color,
            )
            assertTrue(app.repository.dao.sosHistory().isEmpty())
        } finally {
            app.finishForTests()
        }
    }

    @Test
    fun iconOnlyThemeToggleAndEmergencyPaletteHaveAccessibleContrast() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        fun luminance(c: Int): Double {
            fun channel(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return .2126 * channel(Color.red(c)) +
                .7152 * channel(Color.green(c)) +
                .0722 * channel(Color.blue(c))
        }
        fun contrast(a: Int, b: Int): Double {
            val x = luminance(a)
            val y = luminance(b)
            return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
        }
        try {
            for (night in listOf(false, true)) {
                app.getSharedPreferences("appearance", 0).edit().putBoolean("night", night).commit()
                val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
                try {
                    val activity = controller.get()
                    val toggle = activity.findViewById<Button>(R.id.tracking_appearance)
                    assertEquals(if (night) "☀" else "☾", toggle.text)
                    assertEquals(
                        if (night) "Switch to Day mode" else "Switch to Night mode",
                        toggle.contentDescription,
                    )
                    assertTrue(toggle.minimumHeight >= 48)
                    assertTrue(
                        contrast(
                            activity.getColor(R.color.danger),
                            activity.getColor(R.color.emergency_surface),
                        ) >= 4.5
                    )
                    assertTrue(
                        contrast(Color.WHITE, activity.getColor(R.color.emergency_action)) >= 4.5
                    )
                    assertTrue(
                        views(activity.findViewById(android.R.id.content))
                            .filterIsInstance<Button>()
                            .none { it.text.contains("Pause", true) }
                    )
                    toggle.performClick()
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(
                        !night,
                        app.getSharedPreferences("appearance", 0).getBoolean("night", night),
                    )
                } finally {
                    controller.pause().stop().destroy()
                }
            }
        } finally {
            app.finishForTests()
        }
    }
}
