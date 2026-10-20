package org.gnss.tracking

import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TrackingApp::class)
class PhoneAlertNotificationTest {
    @Test
    @Config(sdk = [35])
    fun modernNotificationPermissionDeniedHasNoNotificationAndReportsBlocked() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        try {
            val manager = app.getSystemService(NotificationManager::class.java)
            Shadows.shadowOf(manager).setNotificationsEnabled(false)
            val now = System.currentTimeMillis()
            val row =
                CommandRecord(
                    Protocol.newId(),
                    Protocol.newId(),
                    app.repository.state().deviceId,
                    Protocol.newId(),
                    "alert",
                    1,
                    now,
                    attempted = true,
                )
            val result =
                NotificationPhoneAlertOutput(app)
                    .attempt(row, PhoneAlertSpec("urgent", "Check in", 60, 10), 60000)
            assertEquals("alert_blocked", result.first)
            assertTrue(result.second.contains("permission denied"))
            assertEquals(0, manager.activeNotifications.size)
        } finally {
            app.finishForTests()
        }
    }

    @Test
    fun actualNotificationButtonPersistsHumanAckAndDoesNotAcknowledgeSos() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        try {
            val now = System.currentTimeMillis()
            val row =
                CommandRecord(
                    Protocol.newId(),
                    Protocol.newId(),
                    app.repository.state().deviceId,
                    Protocol.newId(),
                    "alert",
                    1,
                    now,
                    state = "unknown",
                    attempted = true,
                    expires = now + 60000,
                    requested = "{}",
                )
            app.repository.dao.insertCommand(row)
            val other = row.copy(id = Protocol.newId(), ordering = 2)
            app.repository.dao.insertCommand(other)
            val output = NotificationPhoneAlertOutput(app)
            assertEquals(
                "output_attempted",
                output.attempt(row, PhoneAlertSpec("urgent", "Check in", 60, 10), 60000).first,
            )
            output.attempt(other, PhoneAlertSpec("normal", "Other phone alert", 5, 5), 60000)
            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            assertEquals(2, manager.activeNotifications.size)
            val notification =
                manager.activeNotifications.first { it.tag.endsWith(row.id) }.notification
            assertEquals("Acknowledge / Stop Alert", notification.actions[0].title.toString())
            assertTrue(
                Shadows.shadowOf(notification.actions[0].actionIntent).flags and
                    android.app.PendingIntent.FLAG_IMMUTABLE != 0
            )
            notification.actions[0].actionIntent.send()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            withTimeout(5000) {
                while (
                    app.repository.dao.command(row.id)!!.state != "recipient_acknowledged"
                ) delay(10)
            }
            assertEquals("unknown", app.repository.dao.command(other.id)!!.state)
            assertTrue(app.repository.dao.sosHistory().isEmpty())
            notification.actions[0].actionIntent.send()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            delay(50)
            assertEquals(2L, app.repository.dao.command(row.id)!!.revision)
            assertFalse(app.repository.dao.command(row.id)!!.confirmed)
            assertEquals(1, manager.activeNotifications.size)
        } finally {
            app.finishForTests()
        }
    }
}
