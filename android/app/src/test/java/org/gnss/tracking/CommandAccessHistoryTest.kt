package org.gnss.tracking

import android.os.Looper
import android.view.ViewGroup
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

/** A verified response saved for an earlier Command binding stays readable, marked historical. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class)
class CommandAccessHistoryTest {
    private suspend fun settle() {
        repeat(15) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(15)
        }
    }

    @Test
    fun previousBindingResponseIsHistoricalUntilTheCurrentBindingVerifies(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        val verifiedAt = 1_700_000_000_000L
        try {
            app.repository.settings("Alpha", "Alpha", "http://192.168.1.10:8080", 10)
            val generation = app.repository.state().endpointGeneration + 1
            app.repository.edit {
                it.copy(
                    endpointGeneration = generation,
                    controlLastSuccess = verifiedAt,
                    controlLastGeneration = generation - 1,
                )
            }
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val activity = controller.get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            fun shown() =
                nativeViews(root).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
            openNativeScreen(activity, "Command access")
            settle()
            assertTrue(shown().toString(), "None for this binding" in shown())
            assertTrue("Previous binding: ${utc(verifiedAt)} · historical only" in shown())

            // The new binding verifies: the earlier response is no longer shown as history.
            app.repository.edit { it.copy(controlLastGeneration = generation) }
            settle()
            assertTrue(utc(verifiedAt) in shown())
            assertTrue(shown().none { it.startsWith("Previous binding:") })
            controller.pause().stop().destroy()
        } finally {
            app.finishForTests()
        }
    }
}
