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
        val initial = withContext(Dispatchers.IO) { app.repository.state() }
        val authority = Protocol.newId()
        val enrollment = Protocol.newId()
        val output = System.getenv("GNSS_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }
        for (night in listOf(false, true)) for (font in listOf(1f, 1.5f)) {
            withContext(Dispatchers.IO) {
                app.repository.edit {
                    it.copy(
                        controlAuthority = authority,
                        controlEnrollment = enrollment,
                        controlDisabled = false,
                    )
                }
            }
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
                openNativeScreen(a, "Command access")
                click("Enrollment steps ›")
                capture("enrollment-steps")
                openNativeScreen(a, "Transfer recovery")
                click("Retry saved messages")
                capture("retry")
                openNativeScreen(a, "Manage Command access")
                click("Re-enroll Command")
                capture("change-command")
                click("Disable remote control")
                capture("disable-command")
                assertFalse(app.repository.state().controlDisabled)
                app.repository.edit { it.copy(controlAuthority = null, controlEnrollment = null) }
                settle()
                openNativeScreen(a, "Command access")
                all()
                    .filterIsInstance<EditText>()
                    .single { it.hint == "Paste Command credential" }
                    .setText("gnss1:$authority:${initial.deviceId}:$enrollment:${"01".repeat(32)}")
                click("Authorize")
                capture("authorize")
                assertNull(app.repository.state().controlEnrollment)
                app.repository.edit {
                    it.copy(controlAuthority = authority, controlEnrollment = enrollment)
                }
                settle()
                assertEquals(enrollment, app.repository.state().controlEnrollment)
                openNativeScreen(a, "Tracking")
                app.operational.value = Operational(tracking = true)
                settle()
                click("Stop Tracking")
                capture("stop-tracking")
                assertTrue(app.operational.value.tracking)
                app.operational.value = Operational()
                settle()
                openNativeScreen(a, "Party & Command")
                val field =
                    all().filterIsInstance<EditText>().single {
                        it.contentDescription == "Custom interval · seconds"
                    }
                val intervalLabel =
                    all().filterIsInstance<android.widget.TextView>().single {
                        it.text == "Reporting interval · seconds" && it.labelFor != View.NO_ID
                    }
                a.findViewById<EditText>(intervalLabel.labelFor).setText("3")
                click("Save settings")
                capture("invalid-interval")
                @Suppress("DEPRECATION") a.onBackPressed()
                openNativeScreen(a, "Tracking")
                @Suppress("DEPRECATION") a.onBackPressed()
                capture("unsaved-edits")
                assertFalse(a.isFinishing)
                openNativeScreen(a, "History search & filters")
                click("Filter history")
                capture("instruction-filter")
                val alertId = Protocol.newId()
                app.repository.dao.insertCommand(
                    CommandRecord(
                        alertId,
                        authority,
                        initial.deviceId,
                        enrollment,
                        "alert",
                        1,
                        System.currentTimeMillis(),
                        state = "unknown",
                        requested =
                            """{"mode":"urgent","message":"Test-only check in","duration_s":60,"repeat_s":10}""",
                    )
                )
                settle()
                openNativeScreen(a, "Phone Alerts")
                settle()
                click("View alert & acknowledge")
                capture("phone-alert")
                assertEquals("unknown", app.repository.dao.command(alertId)!!.state)
                openNativeScreen(a, "Diagnostic tools")
                click("Start Test")
                a.dispatchKeyEvent(
                    android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_VOLUME_UP,
                    )
                )
                settle()
                click("Stop Test")
                settle()
                click("Clear results")
                capture("clear-key-results")
                assertTrue(app.recorder.physicalButtonHistory().totalPresses > 0)
            } finally {
                controller.pause().stop().destroy()
            }
        }
        app.finishForTests()
    }
}
