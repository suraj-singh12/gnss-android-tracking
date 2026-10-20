package org.gnss.tracking

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TrackingApp::class)
class CommandConsoleUiTest {
    private fun views(v: View): List<View> =
        listOf(v) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) }
            else emptyList()

    @Test
    fun consoleUsesCanonicalRowsFilterExpandableDetailsAndCopy() = runBlocking {
        val app: TrackingApp = ApplicationProvider.getApplicationContext()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val root = activity.get().findViewById<View>(android.R.id.content)
            views(root)
                .filterIsInstance<Button>()
                .single { it.text == "Diagnostics" }
                .performClick()
            views(root)
                .filterIsInstance<Button>()
                .single { it.text.toString().contains("Command Instructions") }
                .performClick()
            val id = Protocol.newId()
            val state = app.repository.state()
            app.repository.dao.insertCommand(
                CommandRecord(
                    id,
                    Protocol.newId(),
                    state.deviceId,
                    Protocol.newId(),
                    "tracking_start",
                    1,
                    System.currentTimeMillis(),
                    state = "blocked",
                    reason = "OS background restriction; open app",
                    attempted = true,
                    requested = "operation request",
                )
            )
            withTimeout(10000) {
                while (
                    views(root).filterIsInstance<TextView>().none {
                        it.text.toString().contains("OS background restriction")
                    }
                ) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            val filter =
                views(root).filterIsInstance<EditText>().single {
                    it.hint?.toString()?.startsWith("Filter outcome") == true
                }
            filter.setText("reporting")
            assertTrue(
                views(root).filterIsInstance<TextView>().any {
                    it.text == "No received instructions match this filter"
                }
            )
            filter.setText("blocked")
            val title =
                views(root).filterIsInstance<TextView>().first {
                    it.contentDescription?.toString()?.contains("Tap to expand details") == true
                }
            title.performClick()
            assertTrue(
                views(root).filterIsInstance<TextView>().any {
                    it.visibility == View.VISIBLE && it.text.toString().contains(id)
                }
            )
            app.repository.dao.updateCommand(
                app.repository.dao
                    .command(id)!!
                    .event(System.currentTimeMillis(), "Result pending delivery")
            )
            withTimeout(10000) {
                while (
                    views(root).filterIsInstance<TextView>().none {
                        it.visibility == View.VISIBLE &&
                            it.text.toString().contains("Result pending delivery")
                    }
                ) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            views(root)
                .filterIsInstance<Button>()
                .single { it.text == "Copy command ID" }
                .performClick()
            assertEquals(
                id,
                app.getSystemService(android.content.ClipboardManager::class.java)
                    .primaryClip!!
                    .getItemAt(0)
                    .text
                    .toString(),
            )
            assertFalse(app.operational.value.tracking)
        } finally {
            activity.pause().stop().destroy()
            app.finishForTests()
        }
    }
}
