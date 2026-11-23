package org.gnss.tracking

import android.content.res.Configuration
import android.view.View
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
    private val app = ApplicationProvider.getApplicationContext<TrackingApp>()
    private val dayInk = 0xff23363c.toInt()
    private val nightInk = 0xffe6eef0.toInt()

    private fun prefs() = app.getSharedPreferences("appearance", 0)

    @Test
    fun legacyChoiceIsHonouredAndSettingsAppearanceSwitchesWithoutStartingTracking() {
        for (night in listOf(false, true)) {
            prefs().edit().clear().putBoolean("night", night).commit()
            assertEquals(if (night) Appearance.NIGHT else Appearance.DAY, Appearance.mode(app))
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val a = controller.get()
                assertEquals(
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                    a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
                )
                assertEquals(if (night) nightInk else dayInk, a.getColor(R.color.ink))
                for (root in listOf("Tracking", "Settings", "Diagnostics")) {
                    openNativeScreen(a, root)
                    val toggle = a.findViewById<Button>(R.id.tracking_appearance)
                    assertTrue("v3 has no app-bar theme control on $root", toggle == null || !toggle.isShown)
                }
                openNativeScreen(a, "Settings")
                val row =
                    nativeViews(a.findViewById(android.R.id.content)).single {
                        it.isShown && it.contentDescription?.startsWith("Appearance. ") == true
                    }
                assertTrue(row.contentDescription.contains(if (night) "Night" else "Day"))
                openNativeScreen(a, "Appearance")
                val choices =
                    nativeViews(a.findViewById(android.R.id.content)).filter {
                        it.isShown && it.isClickable &&
                            Appearance.modes.any { m -> it.contentDescription?.toString()?.startsWith(m) == true }
                    }
                assertEquals(3, choices.size)
                choices.forEach { assertTrue(it.height == 0 || it.height >= NativeUi(a).dp(48)) }
                val current = if (night) "Night" else "Day"
                assertEquals("$current, selected", choices.single { it.isSelected }.contentDescription)
                val target = if (night) "Day" else "Night"
                choices.single { it.contentDescription == target }.performClick()
                assertEquals(target, Appearance.mode(app))
                assertFalse("Explicit mode replaces the legacy flag", prefs().contains("night"))
                controller.recreate()
                assertEquals(if (night) dayInk else nightInk, controller.get().getColor(R.color.ink))
                assertNull(shadowOf(controller.get()).nextStartedService)
                assertFalse(app.operational.value.tracking)
            } finally {
                controller.pause().stop().destroy()
            }
        }
        prefs().edit().clear().commit()
        app.recorder.close()
    }

    @Test
    fun systemModeFollowsThePhoneDarkTheme() {
        prefs().edit().clear().commit()
        assertEquals(Appearance.SYSTEM, Appearance.mode(app))
        for (systemNight in listOf(false, true)) {
            val config = Configuration(app.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (systemNight) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            assertEquals(systemNight, Appearance.isNight(app, config))
        }
        Appearance.set(app, Appearance.DAY)
        assertFalse(Appearance.isNight(app, Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_YES }))
        Appearance.set(app, Appearance.NIGHT)
        assertTrue(Appearance.isNight(app, Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_NO }))
        assertThrows(IllegalArgumentException::class.java) { Appearance.set(app, "Dusk") }
        prefs().edit().clear().commit()
        app.recorder.close()
    }
}
