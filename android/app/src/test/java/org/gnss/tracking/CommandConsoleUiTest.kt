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
            openNativeScreen(activity.get(), "Command Instructions")
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
                        it.contentDescription?.contains("Open instruction details") == true
                    }
                ) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            val filter =
                views(root).filterIsInstance<EditText>().single {
                    it.hint?.toString()?.startsWith("Search instructions") == true
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
                    it.contentDescription?.toString()?.contains("Open instruction details") == true
                }
            assertTrue("Command row must be keyboard focusable", title.isFocusable)
            assertTrue(
                title.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
                    null,
                )
            )
            assertTrue(
                views(root).filterIsInstance<TextView>().any {
                    it.isShown && it.text.toString().contains(id)
                }
            )
            views(root)
                .filterIsInstance<Button>()
                .single { it.text == "Full record" }
                .performClick()
            app.repository.dao.updateCommand(
                app.repository.dao
                    .command(id)!!
                    .event(System.currentTimeMillis(), "Result pending delivery")
            )
            withTimeout(10000) {
                while (
                    views(root).filterIsInstance<TextView>().none {
                        it.isShown && it.text.toString().contains("Result pending delivery")
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
            openNativeScreen(activity.get(), "Command Instructions")
            val focusedRow =
                views(root).filterIsInstance<Button>().single {
                    it.contentDescription?.toString()?.contains("Open instruction details") == true
                }
            focusedRow.requestFocus()
            app.repository.dao.updateCommand(app.repository.dao.command(id)!!)
            repeat(20) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
            assertTrue(
                "Unchanged journal refresh must preserve native row identity",
                views(root).any { it === focusedRow },
            )
            assertTrue(
                "Unchanged journal refresh must preserve keyboard focus",
                focusedRow.hasFocus(),
            )
            filter.setText("")
            val alertId = Protocol.newId()
            app.repository.dao.insertCommand(
                CommandRecord(
                    alertId,
                    Protocol.newId(),
                    state.deviceId,
                    Protocol.newId(),
                    "alert",
                    2,
                    System.currentTimeMillis(),
                    state = "unknown",
                    attempted = true,
                    requested =
                        """{"mode":"urgent","message":"private-alert-content","duration_s":60,"repeat_s":10}""",
                )
            )
            withTimeout(10000) {
                while (
                    views(root).filterIsInstance<Button>().none {
                        it.text == "Acknowledge / Stop Alert"
                    }
                ) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            views(root)
                .filterIsInstance<Button>()
                .last { it.text == "Acknowledge / Stop Alert" }
                .performClick()
            withTimeout(10000) {
                while (
                    app.repository.dao.command(alertId)!!.state != "recipient_acknowledged"
                ) delay(10)
            }
            assertFalse(app.repository.dao.command(alertId)!!.confirmed)
            val output = java.io.ByteArrayOutputStream()
            val directory = java.nio.file.Files.createTempDirectory("alert-diagnostic").toFile()
            try {
                DiagnosticJournal(directory)
                    .export(
                        output,
                        DiagnosticBuild("test", 1, 28, "source"),
                        commands = app.repository.dao.commandHistory(),
                    )
                val exported =
                    java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                        buildString {
                            while (true) {
                                val entry = zip.nextEntry ?: break
                                append(entry.name)
                                append(String(zip.readBytes()))
                            }
                        }
                    }
                assertTrue(exported.contains("recipient_acknowledged"))
                assertTrue(exported.contains(alertId))
                assertFalse(exported.contains("private-alert-content"))
            } finally {
                directory.deleteRecursively()
            }
            assertFalse(app.operational.value.tracking)
        } finally {
            activity.pause().stop().destroy()
            app.finishForTests()
        }
    }
}
