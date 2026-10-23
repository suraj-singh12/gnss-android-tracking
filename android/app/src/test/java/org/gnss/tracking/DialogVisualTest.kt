package org.gnss.tracking

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DialogVisualTest {
    @Test
    fun appOwnedWindowsRenderAndCancelWithoutMutatingOperations() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        withContext(Dispatchers.IO) { app.repository.state() }
        val output = System.getenv("GNSS_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }
        for (night in listOf(false, true)) for (font in listOf(1f, 1.5f)) {
            app.operational.value = Operational()
            app.getSharedPreferences("appearance", 0).edit().putBoolean("night", night).commit()
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(
                android.content.res.Configuration(app.resources.configuration).apply {
                    fontScale = font
                },
                app.resources.displayMetrics,
            )
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val a = controller.get()
            try {
                fun all() = nativeViews(a.findViewById(android.R.id.content))
                fun click(text: String) {
                    all()
                        .filterIsInstance<Button>()
                        .single { it.text == text && it.isShown }
                        .performClick()
                }
                suspend fun settle() {
                    repeat(20) {
                        shadowOf(Looper.getMainLooper()).idle()
                        delay(10)
                    }
                }
                suspend fun capture(name: String) {
                    settle()
                    val dialog = ShadowAlertDialog.getLatestAlertDialog()
                    assertNotNull(dialog)
                    assertTrue(dialog.isShowing)
                    val view = dialog.window!!.decorView
                    val density = a.resources.displayMetrics.density
                    val w = (328 * density).toInt()
                    val h = (740 * density).toInt()
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST),
                    )
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                    if (output != null) {
                        val bitmap =
                            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(bitmap))
                        File(
                                output,
                                "android-${if(night) "night" else "day"}-dialog-${font}-$name.png",
                            )
                            .outputStream()
                            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    val negative = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                    if (negative != null && negative.isShown) negative.performClick()
                    else dialog.dismiss()
                }
                settle()
                openNativeScreen(a, "Appearance & SOS")
                a.findViewById<Button>(R.id.appearance).performClick()
                capture("appearance")
                openNativeScreen(a, "Diagnostic tools")
                click("Retry saved messages")
                capture("retry")
                openNativeScreen(a, "Change trusted Command")
                click("Confirm change")
                capture("change-command")
                openNativeScreen(a, "Command access")
                click("Authorize Command")
                capture("authorize")
                click("Disable remote control locally")
                capture("disable-command")
                openNativeScreen(a, "Tracking")
                app.operational.value = Operational(tracking = true)
                settle()
                click("Stop Tracking")
                capture("stop-tracking")
                assertTrue(app.operational.value.tracking)
                app.operational.value = Operational()
                settle()
                openNativeScreen(a, "Reporting interval")
                val field =
                    all().filterIsInstance<EditText>().single {
                        it.hint?.startsWith("5–86400") == true
                    }
                field.setText("3")
                click("Save")
                capture("invalid-interval")
                @Suppress("DEPRECATION") a.onBackPressed()
                openNativeScreen(a, "Tracking")
                @Suppress("DEPRECATION") a.onBackPressed()
                capture("unsaved-edits")
                assertFalse(a.isFinishing)
            } finally {
                controller.pause().stop().destroy()
            }
        }
        app.finishForTests()
    }
}
