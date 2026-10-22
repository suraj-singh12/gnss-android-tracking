package org.gnss.tracking

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class)
class NativeUiAccessibilityTest {
    @Test
    fun semanticTextPairsMeetNormalTextContrastInBothThemes() {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        fun luminance(c: Int): Double {
            fun channel(n: Int): Double {
                val v = n / 255.0
                return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            }
            return .2126 * channel(android.graphics.Color.red(c)) +
                .7152 * channel(android.graphics.Color.green(c)) +
                .0722 * channel(android.graphics.Color.blue(c))
        }
        for (night in listOf(false, true)) {
            app.getSharedPreferences("appearance", 0).edit().putBoolean("night", night).commit()
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val a = controller.get()
                for ((ink, surface) in
                    listOf(
                        R.color.ink to R.color.surface,
                        R.color.muted to R.color.surface,
                        R.color.muted to R.color.background,
                        R.color.accent to R.color.positive_surface,
                        R.color.warning to R.color.warning_surface,
                        R.color.secondary to R.color.secondary_surface,
                        R.color.danger to R.color.emergency_surface,
                        android.R.color.white to R.color.emergency_action,
                        android.R.color.white to R.color.action,
                    )) {
                    val x = luminance(a.getColor(ink))
                    val y = luminance(a.getColor(surface))
                    val ratio = (maxOf(x, y) + .05) / (minOf(x, y) + .05)
                    assertTrue(
                        "Text contrast $ratio in ${if(night) "Night" else "Day"} for $ink on $surface",
                        ratio >= 4.5,
                    )
                }
            } finally {
                controller.pause().stop().destroy()
            }
        }
        app.recorder.close()
    }
}
