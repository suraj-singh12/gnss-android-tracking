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
            withTimeout(15000) {
                while (app.pendingAdaptive != null) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
        suspend fun toggle(): Switch {
            settle()
            val views = descendants(controller.get().findViewById(android.R.id.content))
            openNativeScreen(controller.get(), "Tracking policy")
            return views.filterIsInstance<Switch>().single { it.text == "Adaptive storage" }
        }
        val choice = toggle()
        assertFalse(choice.isChecked)
        synchronized(app.persistencePolicy) {
            choice.isFocusableInTouchMode = true
            assertTrue(choice.requestFocus())
            choice.performClick()
            assertTrue(choice.isEnabled)
            assertTrue(choice.hasFocus())
            choice.performClick()
            choice.performClick()
            assertEquals(true, app.pendingAdaptive)
            assertTrue(choice.isEnabled)
            assertTrue(choice.hasFocus())
            // Hold the policy monitor so no write can finish before teardown.
            controller.recreate()
            assertEquals(true, app.pendingAdaptive)
        }
        settle()
        assertTrue(app.persistencePolicy.state.enabled)
        assertNull(shadowOf(controller.get()).nextStartedService)
        val restored = toggle()
        assertTrue(restored.isChecked)
        restored.performClick()
        settle()
        assertFalse(app.persistencePolicy.state.enabled)
        assertEquals(4L, app.persistencePolicy.state.changes)
        assertNull(shadowOf(controller.get()).nextStartedService)
        controller.pause().stop().destroy()
        app.finishForTests()
    }

    @Test
    fun appliedStorageShowsDenseWhileSamplerFallbackOverridesAnEnabledSwitch() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        withContext(Dispatchers.IO) {
            app.repository.state()
            app.persistencePolicy.setEnabled(true, utc(System.currentTimeMillis() / 1000 * 1000))
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val root = controller.get().findViewById<View>(android.R.id.content)
        suspend fun shown(): List<String> {
            repeat(10) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
            return descendants(root).filterIsInstance<android.widget.TextView>()
                .filter { it.isShown }.map { it.text.toString() }
        }
        openNativeScreen(controller.get(), "Tracking policy")
        val revision = app.persistencePolicy.state.revision
        assertTrue(shown().toString(), "Adaptive · rev $revision" in shown())
        // The sampler stores densely after a failure; the saved switch stays on.
        ObservationPersistence::class.java.getDeclaredField("sampling").apply { isAccessible = true }
            .get(app.observationPersistence).let { (it as AdaptivePersistence).failSafe() }
        controller.pause().resume()
        val after = shown()
        assertTrue(after.toString(), "Dense · rev $revision" in after)
        assertTrue(descendants(root).filterIsInstance<Switch>().single { it.text == "Adaptive storage" }.isChecked)
        controller.pause().stop().destroy()
        app.finishForTests()
    }
}
