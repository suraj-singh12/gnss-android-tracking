package org.gnss.tracking

import android.content.res.Configuration
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class)
class AppearanceTest {
    @Test
    fun savedAppearanceSelectsNativePaletteWithoutStartingTracking() {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        for (night in listOf(false, true)) {
            app.getSharedPreferences("appearance", 0).edit().putBoolean("night", night).commit()
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val a = controller.get()
                assertEquals(
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                    a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
                )
                assertEquals(
                    if (night) 0xffe6eef0.toInt() else 0xff23363c.toInt(),
                    a.getColor(R.color.ink),
                )
                for (root in listOf("Tracking", "Settings", "Diagnostics")) {
                    openNativeScreen(a, root)
                    val toggle = a.findViewById<Button>(R.id.tracking_appearance)
                    assertTrue("Global theme on $root", toggle.isShown)
                    assertEquals(
                        if (night) "Switch to Day mode" else "Switch to Night mode",
                        toggle.contentDescription.toString(),
                    )
                }
                a.findViewById<Button>(R.id.tracking_appearance).performClick()
                assertEquals(
                    !night,
                    app.getSharedPreferences("appearance", 0).getBoolean("night", night),
                )
                controller.recreate()
                assertEquals(
                    if (night) 0xff23363c.toInt() else 0xffe6eef0.toInt(),
                    controller.get().getColor(R.color.ink),
                )
                assertNull(shadowOf(controller.get()).nextStartedService)
                assertFalse(app.operational.value.tracking)
            } finally {
                controller.pause().stop().destroy()
            }
        }
        app.getSharedPreferences("appearance", 0).edit().putBoolean("night", false).commit()
        app.recorder.close()
    }
}
