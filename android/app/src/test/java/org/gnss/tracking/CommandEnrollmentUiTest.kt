package org.gnss.tracking

import android.app.AlertDialog
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
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

/**
 * Real Room/native views; contact fixtures are presentation evidence, not authenticated traffic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommandEnrollmentUiTest {
    private suspend fun settle() {
        repeat(15) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(15)
        }
    }

    private fun capture(
        root: ViewGroup,
        activity: MainActivity,
        width: Int,
        height: Int,
        label: String,
    ) {
        val density = activity.resources.displayMetrics.density
        root.measure(
            View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        nativeViews(root)
            .filterIsInstance<TextView>()
            .filter { it.isShown && it.width > 0 }
            .forEach {
                assertTrue(
                    "Text fits: ${it.text}",
                    it.layout == null || it.layout.height <= it.height,
                )
            }
        System.getenv("GNSS_SCREENSHOT_DIR")?.let {
            val file = File(it, "enrollment-$label.png")
            file.parentFile!!.mkdirs()
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            file.outputStream().use { stream ->
                image.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            image.recycle()
        }
    }

    @Test
    fun enrollmentAndSixStatesPreservePublicIdentityAndNativeLayout() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        try {
            for (night in listOf(false, true)) {
                app.getSharedPreferences("appearance", 0).edit().putBoolean("night", night).commit()
                app.repository.settings("Alpha", "Alpha", "http://192.168.1.10:8080", 10)
                app.repository.edit {
                    it.copy(
                        controlAuthority = null,
                        controlEnrollment = null,
                        controlDisabled = false,
                    )
                }
                val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
                val activity = controller.get()
                app.activityVisible = false
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                fun click(label: String) =
                    nativeViews(root)
                        .filterIsInstance<Button>()
                        .single { it.isShown && it.text == label }
                        .performClick()
                fun visible(label: String) =
                    nativeViews(root).filterIsInstance<TextView>().any {
                        it.isShown && it.text == label
                    }
                try {
                    settle()
                    openNativeScreen(activity, "Command access")
                    settle()
                    capture(root, activity, 360, 800, "$night-not-enrolled")
                    assertTrue(visible("Not enrolled"))
                    val input =
                        nativeViews(root).filterIsInstance<EditText>().single {
                            it.hint == "Paste Command credential"
                        }
                    assertFalse(input.isSaveEnabled)
                    input.setText("invalid")
                    click("Authorize")
                    assertTrue(visible("Enter a valid gnss1 credential for this phone UUID."))
                    val authority = Protocol.newId()
                    val enrollment = Protocol.newId()
                    val phone = app.repository.state().deviceId
                    input.setText(
                        "gnss1:$authority:${Protocol.newId()}:$enrollment:${"01".repeat(32)}"
                    )
                    click("Authorize")
                    assertNull(app.repository.state().controlEnrollment)
                    input.setText("gnss1:$authority:$phone:$enrollment:${"01".repeat(32)}")
                    click("Authorize")
                    assertEquals("", input.text.toString())
                    val review = ShadowAlertDialog.getLatestAlertDialog()
                    assertEquals("Authorize Command?", shadowOf(review).title.toString())
                    assertTrue(
                        review.findViewById<TextView>(android.R.id.message).text.contains(authority)
                    )
                    review.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                    assertNull(app.repository.state().controlEnrollment)
                    click("Enrollment steps ›")
                    assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
                    ShadowAlertDialog.getLatestAlertDialog()
                        .getButton(AlertDialog.BUTTON_POSITIVE)
                        .performClick()
                    for ((index, label) in
                        listOf(
                                "Credential enrolled",
                                "Contact verified",
                                "Contact unavailable",
                                "Authentication failed",
                                "Disabled on this phone",
                            )
                            .withIndex()) {
                        val generation = 100L + index
                        val contact =
                            when (index) {
                                1 ->
                                    AuthenticatedContact(
                                        generation,
                                        authority,
                                        enrollment,
                                        SystemClock.wallMillis(),
                                        SystemClock.elapsedMillis(),
                                    )
                                2 ->
                                    AuthenticatedContact(
                                        generation,
                                        authority,
                                        enrollment,
                                        SystemClock.wallMillis() - 31000,
                                        SystemClock.elapsedMillis() - 31000,
                                    )
                                3 ->
                                    AuthenticatedContact(
                                        generation,
                                        authority,
                                        enrollment,
                                        failure = ContactFailure.AUTHENTICATION,
                                    )
                                else -> null
                            }
                        CommandControl::class
                            .java
                            .getDeclaredField("contact")
                            .apply { isAccessible = true }
                            .set(app.commandControl, contact)
                        app.repository.edit {
                            it.copy(
                                controlAuthority = authority,
                                controlEnrollment = enrollment,
                                endpointGeneration = generation,
                                controlDisabled = index == 4,
                                controlLastSuccess = null,
                                controlLastGeneration = null,
                            )
                        }
                        settle()
                        assertTrue("Contact state $label", visible(label))
                        capture(root, activity, 360, 800, "$night-state-$index")
                        if (index == 1) {
                            controller.pause()
                            org.robolectric.shadows.ShadowSystemClock.advanceBy(
                                java.time.Duration.ofSeconds(31)
                            )
                            controller.resume()
                            settle()
                            assertTrue(visible("Contact unavailable"))
                        }
                    }
                    openNativeScreen(activity, "Identity & verification details")
                    settle()
                    click("Copy Command ID")
                    assertEquals(
                        authority,
                        activity
                            .getSystemService(ClipboardManager::class.java)
                            .primaryClip!!
                            .getItemAt(0)
                            .text
                            .toString(),
                    )
                    capture(root, activity, 360, 800, "$night-identity")
                    assertFalse(app.operational.value.tracking)
                } finally {
                    controller.pause().stop().destroy()
                }
            }
        } finally {
            app.finishForTests()
        }
    }

    @Test
    fun enrollmentLayoutsScrollAndKeepPrivateDraftFocusAtLargeText() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        try {
            app.repository.state()
            for (night in listOf(false, true)) for ((width, height) in
                listOf(360 to 800, 800 to 360)) {
                app.getSharedPreferences("appearance", 0).edit().putBoolean("night", night).commit()
                org.robolectric.RuntimeEnvironment.setQualifiers(
                    "w${width}dp-h${height}dp-${if(width>height) "land" else "port"}"
                )
                @Suppress("DEPRECATION")
                app.resources.updateConfiguration(
                    android.content.res.Configuration(app.resources.configuration).apply {
                        fontScale = 1.5f
                    },
                    app.resources.displayMetrics,
                )
                val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
                try {
                    settle()
                    openNativeScreen(controller.get(), "Command access")
                    settle()
                    fun root() = controller.get().findViewById<ViewGroup>(android.R.id.content)
                    val field =
                        nativeViews(root()).filterIsInstance<EditText>().single {
                            it.hint == "Paste Command credential"
                        }
                    field.setText("draft")
                    field.requestFocus()
                    field.setSelection(3)
                    app.operational.value = Operational(health = Health(wifi_connected = true))
                    settle()
                    assertTrue(field.hasFocus())
                    assertEquals(3, field.selectionStart)
                    capture(
                        root(),
                        controller.get(),
                        width,
                        height,
                        "$night-${width}x$height-large",
                    )
                    controller.recreate()
                    settle()
                    val restored =
                        nativeViews(root()).filterIsInstance<EditText>().single {
                            it.hint == "Paste Command credential"
                        }
                    assertTrue(restored.isShown)
                    assertEquals("", restored.text.toString())
                    val scroll =
                        nativeViews(root()).filterIsInstance<android.widget.ScrollView>().first()
                    capture(
                        root(),
                        controller.get(),
                        width,
                        height,
                        "$night-${width}x$height-recreated",
                    )
                    assertTrue(scroll.height > 0 && scroll.height <= root().height)
                } finally {
                    controller.pause().stop().destroy()
                }
            }
        } finally {
            app.finishForTests()
        }
    }
}
