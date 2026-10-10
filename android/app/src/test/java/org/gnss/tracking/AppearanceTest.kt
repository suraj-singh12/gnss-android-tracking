package org.gnss.tracking

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class)
class AppearanceTest {
    private fun descendants(v: View): List<View> =
        listOf(v) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) }
            else emptyList()

    @Test
    fun savedAppearanceSelectsNativePaletteWithoutStartingTracking() {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        for (night in listOf(false, true)) {
            app.getSharedPreferences("appearance", 0).edit().putBoolean("night", night).commit()
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val a = controller.get()
            assertEquals(
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
            )
            assertEquals(
                if (night) 0xffe5edf1.toInt() else 0xff24343b.toInt(),
                a.getColor(R.color.ink),
            )
            val root = a.findViewById<View>(android.R.id.content)
            descendants(root)
                .filterIsInstance<Button>()
                .single { it.text == "Settings" }
                .performClick()
            val choice = a.findViewById<Button>(R.id.appearance)
            assertEquals("Appearance: ${if(night) "Night" else "Day"}", choice.text.toString())
            choice.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("Appearance", shadowOf(dialog).title.toString())
            assertEquals(2, dialog.listView.adapter.count)
            assertEquals("Day", dialog.listView.adapter.getItem(0))
            assertEquals("Night", dialog.listView.adapter.getItem(1))
            dialog.dismiss()
            assertNull(shadowOf(a).nextStartedService)
            controller.pause().stop().destroy()
        }
        app.getSharedPreferences("appearance", 0).edit().putBoolean("night", false).commit()
        app.recorder.close()
    }
}
