package org.gnss.tracking

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TrackingApp::class)
class SettingsDiagnosticsTest {
    private fun views(v: View): List<View> =
        listOf(v) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) }
            else emptyList()

    private suspend fun until(check: () -> Boolean) =
        withTimeout(15000) {
            while (!check()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }

    @Test
    fun draftSurvivesRefreshRecreationAndCancelSaveAreExplicit() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        val original = withContext(Dispatchers.IO) { app.repository.state() }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        fun all() = views(controller.get().findViewById(android.R.id.content))
        fun button(text: String) = all().filterIsInstance<Button>().single { it.text == text }
        fun field(label: String): EditText {
            val heading = all().filterIsInstance<TextView>().single { it.text == label }
            return controller.get().findViewById(heading.labelFor)
        }
        try {
            until {
                all().filterIsInstance<Button>().any { it.text == "Start Tracking" && it.isEnabled }
            }
            button("Settings").performClick()
            field("Party name").apply {
                setText("Unfinished field edit")
                requestFocus()
                setSelection(6)
            }
            field("Command base URL (local Wi-Fi)").setText("http://192.168.12.3:8080")
            field("Local reporting interval (seconds, 5–86400 in steps of 5)").setText("35")
            app.operational.value = Operational(health = Health(wifi_connected = true))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Unfinished field edit", field("Party name").text.toString())
            controller.recreate()
            until { field("Party name").text.toString() == "Unfinished field edit" }
            assertTrue(field("Party name").hasFocus())
            assertEquals(6, field("Party name").selectionStart)
            assertEquals(
                "35",
                field("Local reporting interval (seconds, 5–86400 in steps of 5)").text.toString(),
            )
            assertEquals(original.partyName, app.repository.state().partyName)
            @Suppress("DEPRECATION") controller.get().onBackPressed()
            val unsaved = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("Unsaved settings", shadowOf(unsaved).title.toString())
            unsaved.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            assertFalse(controller.get().isFinishing)
            button("Cancel edits").performClick()
            assertEquals(original.partyName, field("Party name").text.toString())
            field("Local reporting interval (seconds, 5–86400 in steps of 5)").setText("3")
            button("Save settings").performClick()
            until {
                all().filterIsInstance<TextView>().any { it.text.startsWith("Settings not saved") }
            }
            assertEquals(original.localInterval, app.repository.state().localInterval)
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
            field("Local reporting interval (seconds, 5–86400 in steps of 5)").setText("35")
            button("Save settings").performClick()
            until {
                all().filterIsInstance<TextView>().any { it.text.startsWith("Settings saved") }
            }
            assertEquals(35, app.repository.state().localInterval)
            assertFalse(app.operational.value.tracking)
            assertNull(shadowOf(controller.get()).nextStartedService)
        } finally {
            controller.pause().stop().destroy()
            app.finishForTests()
        }
    }

    @Test
    fun restoredDraftIsVisibleAndResavableBeforeAsyncStorageLoads() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        val saved =
            android.os.Bundle().apply {
                putString("destination", "Settings")
                putBundle(
                    "settingsDraft",
                    android.os.Bundle().apply {
                        putString("partyId", "Draft party")
                        putString("partyName", "Unpersisted field edits")
                        putString("endpoint", "http://192.168.1.4:8080")
                        putString("interval", "45")
                    },
                )
            }
        // Deliberately do not idle the UI/Room flows or resume the Activity.
        // Restoring an editor must not depend on a successful database read.
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(saved)
        try {
            val all = views(controller.get().findViewById(android.R.id.content))
            val heading = all.filterIsInstance<TextView>().single { it.text == "Party name" }
            val editor = controller.get().findViewById<EditText>(heading.labelFor)
            assertEquals("Unpersisted field edits", editor.text.toString())
            assertTrue(editor.isEnabled)
            editor.setText("Edited while storage loads")
            val nextState = android.os.Bundle()
            controller.saveInstanceState(nextState)
            assertEquals(
                "Edited while storage loads",
                nextState.getBundle("settingsDraft")!!.getString("partyName"),
            )
        } finally {
            controller.destroy()
            app.finishForTests()
        }
    }

    @Test
    fun journalPagesFiltersAndDisclosureSurviveRecreation() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        val state = app.repository.state()
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            repeat(45) { n ->
                app.repository.dao.insertCommand(
                    CommandRecord(
                        Protocol.newId(),
                        Protocol.newId(),
                        state.deviceId,
                        Protocol.newId(),
                        if (n % 2 == 0) "reporting" else "alert",
                        n.toLong(),
                        now - n * 1000,
                        state = if (n % 2 == 0) "rejected" else "unknown",
                        reason = "Recorded reason $n",
                        events = if (n == 0) "Duplicate ignored; persisted result retained" else "",
                    )
                )
            }
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        fun all() = views(controller.get().findViewById(android.R.id.content))
        fun button(label: String) = all().filterIsInstance<Button>().single { it.text == label }
        try {
            button("Diagnostics").performClick()
            all()
                .filterIsInstance<Button>()
                .single { it.text.startsWith("Command Instructions  ") }
                .performClick()
            until { all().filterIsInstance<Button>().count { it.text == "Copy command ID" } == 20 }
            button("Older instructions").performClick()
            assertTrue(all().filterIsInstance<TextView>().any { it.text.contains("Page 2 of 3") })
            val filter =
                all().filterIsInstance<EditText>().single {
                    it.hint?.startsWith("Filter outcome") == true
                }
            filter.setText("Duplicate ignored")
            assertEquals(1, all().filterIsInstance<Button>().count { it.text == "Copy command ID" })
            filter.setText("")
            all()
                .filterIsInstance<Spinner>()
                .single { it.contentDescription == "Command type" }
                .setSelection(1)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(all().filterIsInstance<TextView>().any { it.text.contains("23 matching") })
            controller.recreate()
            until { all().filterIsInstance<Button>().count { it.text == "Copy command ID" } == 20 }
            assertEquals(
                1,
                all()
                    .filterIsInstance<Spinner>()
                    .single { it.contentDescription == "Command type" }
                    .selectedItemPosition,
            )
            assertTrue(all().filterIsInstance<TextView>().any { it.text.contains("23 matching") })
            assertFalse(app.operational.value.tracking)
        } finally {
            controller.pause().stop().destroy()
            app.finishForTests()
        }
    }

    @Test
    fun presentationKeepsTrustAndRequestOutcomeSeparateFromAppliedAndAck() {
        val state =
            Installation(controlEnrollment = "enrollment", overrideSeconds = 20, version = 7)
        val row =
            CommandRecord(
                "id",
                "authority",
                state.deviceId,
                "enrollment",
                "reporting",
                1,
                100,
                state = "rejected",
                reason = "Invalid reporting interval",
                requested = "{\"seconds\":3}",
            )
        val policy = reportingPolicyDescription(state, listOf(row))
        assertTrue(policy.contains("rejected"))
        assertTrue(policy.contains("Actually applied: 20 s"))
        assertTrue(commandInstructionDetails(row).contains("Pending or unknown delivery"))
        assertTrue(commandOutcomeExplanation("output_attempted").contains("unconfirmed"))
        assertTrue(commandTrustDescription(state, listOf(row), "Offline").contains("Unknown"))
        assertTrue(
            commandTrustDescription(Installation(), emptyList(), "Offline").contains("Unenrolled")
        )
    }
}
