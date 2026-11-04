package org.gnss.tracking

import android.app.AlertDialog
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class)
class V46WorkflowTest {
    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(15000) {
            while (!predicate()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun customPolicyRoundTripsAndFilterCancellationIsTransactional() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        app.repository.settings("A", "Alpha", "http://192.168.1.2:8080", 35)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        fun all() = nativeViews(controller.get().findViewById(android.R.id.content))
        fun click(text: String) =
            all().filterIsInstance<Button>().single { it.isShown && it.text == text }.performClick()
        try {
            await {
                all().filterIsInstance<Button>().any { it.text == "Start Tracking" && it.isEnabled }
            }
            openNativeScreen(controller.get(), "Tracking policy")
            shadowOf(Looper.getMainLooper()).idle()
            val picker =
                all().filterIsInstance<Spinner>().single {
                    it.contentDescription == "Reporting interval"
                }
            val custom =
                all().filterIsInstance<EditText>().single {
                    it.contentDescription == "Custom interval · seconds"
                }
            assertEquals(4, picker.selectedItemPosition)
            assertEquals("35", custom.text.toString())
            assertEquals(35, app.repository.state().localInterval)
            picker.setSelection(0)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("5", custom.text.toString())
            picker.setSelection(4)
            shadowOf(Looper.getMainLooper()).idle()
            custom.setText("3")
            click("Save policy")
            await {
                all().filterIsInstance<TextView>().any { it.text.startsWith("Settings not saved") }
            }
            assertEquals(35, app.repository.state().localInterval)
            ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
            custom.setText("45")
            click("Save policy")
            await {
                all().filterIsInstance<TextView>().any { it.text.startsWith("Settings saved") }
            }
            assertEquals(45, app.repository.state().localInterval)
            controller.recreate()
            await {
                all().filterIsInstance<Button>().any { it.text == "Start Tracking" && it.isEnabled }
            }
            assertTrue(
                all()
                    .filterIsInstance<EditText>()
                    .single { it.contentDescription == "Custom interval · seconds" }
                    .isShown
            )
            val state = app.repository.state()
            for ((index, outcome) in listOf("applied", "unknown", "rejected").withIndex()) app
                .repository
                .dao
                .insertCommand(
                    CommandRecord(
                        Protocol.newId(),
                        Protocol.newId(),
                        state.deviceId,
                        Protocol.newId(),
                        "reporting",
                        index.toLong(),
                        java.lang.System.currentTimeMillis() + index,
                        state = outcome,
                    )
                )
            openNativeScreen(controller.get(), "History search & filters")
            click("Filter history")
            var dialog = ShadowAlertDialog.getLatestAlertDialog()
            val initial =
                nativeViews(dialog.window!!.decorView).filterIsInstance<Switch>().associate {
                    it.text.toString() to it.isChecked
                }
            assertEquals(
                mapOf("Applied" to true, "Unknown" to true, "Rejected / blocked" to false),
                initial,
            )
            nativeViews(dialog.window!!.decorView).filterIsInstance<Switch>().last().performClick()
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            click("Filter history")
            dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertFalse(
                nativeViews(dialog.window!!.decorView).filterIsInstance<Switch>().last().isChecked
            )
            nativeViews(dialog.window!!.decorView).filterIsInstance<Switch>().last().performClick()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val saved = android.os.Bundle()
            controller.saveInstanceState(saved)
            assertTrue(
                "Applied rejected group saved: ${saved.getStringArrayList("v46Outcomes")}",
                saved.getStringArrayList("v46Outcomes")!!.contains("rejected"),
            )
            controller.recreate()
            await {
                all().filterIsInstance<Button>().any { it.text == "Filter history" && it.isShown }
            }
            click("Filter history")
            dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(
                nativeViews(dialog.window!!.decorView).filterIsInstance<Switch>().last().isChecked
            )
            dialog.dismiss()
            assertFalse(app.operational.value.tracking)
        } finally {
            controller.pause().stop().destroy()
            app.finishForTests()
        }
    }

    @Test
    fun acknowledgedAlertHistoryIsEnabledAndUsesCanonicalRetainedJournal() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        val state = app.repository.state()
        val id = Protocol.newId()
        app.repository.dao.insertCommand(
            CommandRecord(
                id,
                Protocol.newId(),
                state.deviceId,
                Protocol.newId(),
                "alert",
                1,
                java.lang.System.currentTimeMillis(),
                state = "unknown",
                requested =
                    """{"mode":"urgent","message":"Return to base","duration_s":60,"repeat_s":10}""",
            )
        )
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        fun all() = nativeViews(controller.get().findViewById(android.R.id.content))
        fun click(text: String) =
            all().filterIsInstance<Button>().single { it.isShown && it.text == text }.performClick()
        try {
            await {
                all().filterIsInstance<Button>().any { it.text == "Start Tracking" && it.isEnabled }
            }
            openNativeScreen(controller.get(), "Phone Alerts")
            await {
                all().filterIsInstance<Button>().any {
                    it.isShown && it.text == "View alert & acknowledge"
                }
            }
            click("View alert & acknowledge")
            ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_NEUTRAL)
                .performClick()
            await {
                all().filterIsInstance<TextView>().any {
                    it.isShown && it.text.contains("Local output stopped; recipient ACK pending")
                }
            }
            assertEquals("unknown", app.repository.dao.command(id)!!.state)
            click("View alert & acknowledge")
            ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick()
            await {
                all().filterIsInstance<Button>().any {
                    it.isShown && it.text == "View alert history"
                }
            }
            val history =
                all().filterIsInstance<Button>().single {
                    it.isShown && it.text == "View alert history"
                }
            assertTrue(history.isEnabled)
            history.performClick()
            await {
                all().filterIsInstance<Button>().any {
                    it.isShown &&
                        it.contentDescription?.contains("Open instruction details") == true
                }
            }
            assertEquals(
                1,
                all().count {
                    it.isShown &&
                        it.contentDescription?.contains("Open instruction details") == true
                },
            )
            assertEquals("recipient_acknowledged", app.repository.dao.command(id)!!.state)
            assertTrue(app.repository.dao.sosHistory().isEmpty())
        } finally {
            controller.pause().stop().destroy()
            app.finishForTests()
        }
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun globalSosCancelsAcrossEveryDestinationAndCommitsAfterFullHold() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        app.repository.state()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        fun buttons() =
            nativeViews(controller.get().findViewById<ViewGroup>(android.R.id.content))
                .filterIsInstance<Button>()
        fun button() = buttons().single { it.text == "Hold to activate SOS" }
        fun touch(b: Button, action: Int) {
            b.layout(0, 0, 300, 64)
            val e = MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, 140f, 30f, 0)
            b.dispatchTouchEvent(e)
            e.recycle()
        }
        fun advance(ms: Long) =
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
        try {
            await { buttons().any { it.text == "Start Tracking" && it.isEnabled } }
            for (name in
                listOf(
                    "Tracking",
                    "Settings",
                    "Party & Command",
                    "Command access",
                    "Manage Command access",
                    "Identity & verification details",
                    "Field readiness",
                    "Tracking policy",
                    "Policy details",
                    "Emergency controls",
                    "Diagnostics",
                    "Command Instructions",
                    "History search & filters",
                    "Instruction filters",
                    "Phone Alerts",
                    "Saved data & sync",
                    "Transfer recovery",
                    "Storage health",
                    "Diagnostic tools",
                    "Advanced logs",
                    "GNSS details",
                    "Command contact",
                    "SOS event history",
                )) {
                openNativeScreen(controller.get(), name)
                val b = button()
                assertTrue("Global SOS on $name", b.isShown)
                touch(b, MotionEvent.ACTION_DOWN)
                advance(600)
                openNativeScreen(controller.get(), "Tracking")
                advance(1500)
                assertTrue(
                    "Navigation cancels $name hold",
                    app.repository.dao.sosHistory().isEmpty(),
                )
            }
            openNativeScreen(controller.get(), "Settings")
            val b = button()
            touch(b, MotionEvent.ACTION_DOWN)
            advance(1199)
            assertTrue(app.repository.dao.sosHistory().isEmpty())
            advance(26)
            touch(b, MotionEvent.ACTION_UP)
            controller.pause().stop().destroy()
            withTimeout(10000) { while (app.repository.dao.sosHistory().isEmpty()) delay(10) }
            assertEquals(1, app.repository.dao.sosHistory().size)
            assertNull(app.repository.dao.latestSos()!!.deliveredAt)
        } finally {
            app.finishForTests()
        }
    }
}
