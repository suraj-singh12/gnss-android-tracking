package org.gnss.tracking

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
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

/** Each Advanced logs section copies as one plain-text block; single values stay selectable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class)
class AdvancedLogsCopyTest {
    @Test
    fun everySectionHasCopyForItsWholeTextAndRowsStaySelectable(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        try {
            withContext(Dispatchers.IO) { app.repository.state() }
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val activity = controller.get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            openNativeScreen(activity, "Advanced logs")
            repeat(15) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(15)
            }
            val copies = nativeViews(root).filterIsInstance<Button>()
                .filter { it.isShown && it.contentDescription?.toString()?.matches(Regex("Copy .+ section")) == true }
            val sections = copies.map { it.contentDescription.toString().removePrefix("Copy ").removeSuffix(" section") }
            assertTrue(sections.toString(), sections.containsAll(listOf("Device", "GNSS", "Delivery", "SOS")))
            copies.forEach { assertEquals("Copy", it.text.toString()) }
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            copies.zip(sections).forEach { (button, name) ->
                button.performClick()
                val lines = clipboard.primaryClip!!.getItemAt(0).text.toString().lines()
                assertEquals(name, lines.first())
                assertTrue("$name copies its lines", lines.size > 1 && lines.drop(1).all { it.isNotBlank() })
            }
            val shown = nativeViews(root).filterIsInstance<TextView>().filter { it.isShown && it !is Button }
            assertTrue(shown.filter { it.text.isNotEmpty() }.any { it.isTextSelectable })
            controller.pause().stop().destroy()
        } finally {
            app.finishForTests()
        }
    }
}
