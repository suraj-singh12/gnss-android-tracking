package org.gnss.tracking

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
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
@Config(sdk = [35], application = TrackingApp::class)
class AdaptiveSettingsTest {
    private fun descendants(v: View): List<View> =
        listOf(v) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) }
            else emptyList()

    @Test
    fun settingsOptInPersistsAcrossActivityRecreationWithoutStartingGnss() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        withContext(Dispatchers.IO) { app.repository.state() }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        suspend fun settle() {
            repeat(30) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        suspend fun toggle(): Switch {
            settle()
            val views = descendants(controller.get().findViewById(android.R.id.content))
            openNativeScreen(controller.get(), "Tracking & GNSS")
            return views.filterIsInstance<Switch>().single { it.text == "Adaptive storage" }
        }
        val choice = toggle()
        assertFalse(choice.isChecked)
        choice.performClick()
        controller.recreate() // No wait: an Activity teardown cannot cancel the accepted write.
        settle()
        assertTrue(app.persistencePolicy.state.enabled)
        assertNull(shadowOf(controller.get()).nextStartedService)
        val restored = toggle()
        assertTrue(restored.isChecked)
        restored.performClick()
        settle()
        assertFalse(app.persistencePolicy.state.enabled)
        assertEquals(2L, app.persistencePolicy.state.changes)
        assertNull(shadowOf(controller.get()).nextStartedService)
        controller.pause().stop().destroy()
        app.finishForTests()
    }
}
