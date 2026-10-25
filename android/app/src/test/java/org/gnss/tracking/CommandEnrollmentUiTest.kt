package org.gnss.tracking

import android.content.ClipboardManager
import android.content.Context
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommandEnrollmentUiTest {
    @Test
    fun enrollmentAndSixStatesPreservePublicIdentityAndNativeLayout() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        val output = System.getenv("GNSS_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }
        for (night in listOf(false, true)) {
            withContext(Dispatchers.IO) {
                app.repository.settings("Alpha", "Alpha", "http://192.168.1.10:8080", 10)
            }
            app.getSharedPreferences("appearance", 0).edit().putBoolean("night", night).commit()
            org.robolectric.RuntimeEnvironment.setQualifiers("w360dp-h800dp-port")
            withContext(Dispatchers.IO) {
                app.repository.edit {
                    it.copy(
                        controlAuthority = null,
                        controlEnrollment = null,
                        controlDisabled = false,
                    )
                }
            }
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val activity = controller.get()
            app.activityVisible =
                false // deterministic presentation fixture; no synthetic network traffic
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            suspend fun settle() {
                repeat(15) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(15)
                }
                val density = activity.resources.displayMetrics.density
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(
                        (360 * density).toInt(),
                        View.MeasureSpec.EXACTLY,
                    ),
                    View.MeasureSpec.makeMeasureSpec(
                        (800 * density).toInt(),
                        View.MeasureSpec.EXACTLY,
                    ),
                )
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            }
            fun click(label: String) {
                nativeViews(root)
                    .filterIsInstance<Button>()
                    .single { it.isShown && it.text == label }
                    .performClick()
            }
            fun capture(label: String) {
                nativeViews(root)
                    .filterIsInstance<TextView>()
                    .filter { it.isShown && it.width > 0 }
                    .forEach {
                        assertTrue(
                            "Text must fit its view: ${it.text}",
                            it.layout == null || it.layout.height <= it.height,
                        )
                    }
                if (output != null) {
                    val image =
                        Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(image))
                    File(output, "enrollment-${if(night) "night" else "day"}-$label.png")
                        .outputStream()
                        .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
            }
            try {
                settle()
                click("Settings")
                settle()
                // Discoverable direct entry opens the existing Command access destination.
                nativeViews(root)
                    .single {
                        it.isShown && it.contentDescription?.startsWith("Command access.") == true
                    }
                    .performClick()
                settle()
                capture("not-enrolled")
                click("Set up Command access")
                settle()
                capture("compare-phone")
                click("Copy phone ID")
                val clipboard =
                    activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals(
                    app.repository.state().deviceId,
                    clipboard.primaryClip!!.getItemAt(0).text.toString(),
                )
                click("I have the private credential")
                settle()
                capture("private-entry-empty")
                val input =
                    nativeViews(root).filterIsInstance<EditText>().single {
                        it.hint == "Private enrollment credential"
                    }
                input.setText("invalid")
                click("Review authorization")
                settle()
                assertTrue(
                    nativeViews(root).filterIsInstance<TextView>().any {
                        it.isShown && it.text.toString().startsWith("Credential invalid")
                    }
                )
                val authority = Protocol.newId()
                val enrollment = Protocol.newId()
                val phone = app.repository.state().deviceId
                input.setText("gnss1:$authority:$phone:$enrollment:${"01".repeat(32)}")
                click("Review authorization")
                settle()
                assertEquals("", input.text.toString())
                capture("review-authority")
                assertTrue(
                    nativeViews(root).filterIsInstance<TextView>().any {
                        it.isShown && it.text.contains(authority)
                    }
                )
                click("Continue")
                settle()
                val confirmation = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                assertTrue(confirmation.isShowing)
                if (output != null) {
                    val decor = confirmation.window!!.decorView
                    val density = activity.resources.displayMetrics.density
                    decor.measure(
                        View.MeasureSpec.makeMeasureSpec(
                            (340 * density).toInt(),
                            View.MeasureSpec.EXACTLY,
                        ),
                        View.MeasureSpec.makeMeasureSpec(
                            (700 * density).toInt(),
                            View.MeasureSpec.AT_MOST,
                        ),
                    )
                    decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                    val image =
                        Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                    decor.draw(Canvas(image))
                    File(output, "enrollment-${if(night) "night" else "day"}-confirmation.png")
                        .outputStream()
                        .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
                confirmation
                    .getButton(android.content.DialogInterface.BUTTON_NEGATIVE)
                    .performClick()
                click("Cancel")
                settle()
                // Synthetic contact telemetry, with real Room/native presentation. Crypto is tested
                // in CommandControlTest.
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
                    withContext(Dispatchers.IO) {
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
                    }
                    settle()
                    assertTrue(
                        "$label is displayed",
                        nativeViews(root).filterIsInstance<TextView>().any {
                            it.isShown && it.text == label
                        },
                    )
                    capture("state-$index")
                    if (index == 1) {
                        controller.pause()
                        org.robolectric.shadows.ShadowSystemClock.advanceBy(
                            java.time.Duration.ofSeconds(31)
                        )
                        controller.resume()
                        assertTrue(
                            nativeViews(root).filterIsInstance<TextView>().any {
                                it.isShown && it.text == "Contact unavailable"
                            }
                        )
                    }
                }
                // Identity copy remains separate from secrets and instruction IDs.
                nativeViews(root)
                    .single {
                        it.isShown &&
                            it.contentDescription?.startsWith("Identity & verification details.") ==
                                true
                    }
                    .performClick()
                settle()
                capture("identity-details")
                click("Copy Command ID")
                assertEquals(authority, clipboard.primaryClip!!.getItemAt(0).text.toString())
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }

    @Test
    fun enrollmentLayoutsScrollAndKeepPrivateDraftFocusAtLargeText() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        val output = System.getenv("GNSS_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }
        try {
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
                val activity = controller.get()
                app.activityVisible = false
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                suspend fun settle() {
                    repeat(12) {
                        shadowOf(Looper.getMainLooper()).idle()
                        delay(10)
                    }
                    val density = activity.resources.displayMetrics.density
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(
                            (width * density).toInt(),
                            View.MeasureSpec.EXACTLY,
                        ),
                        View.MeasureSpec.makeMeasureSpec(
                            (height * density).toInt(),
                            View.MeasureSpec.EXACTLY,
                        ),
                    )
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                }
                fun click(label: String) {
                    nativeViews(root)
                        .filterIsInstance<Button>()
                        .single { it.isShown && it.text == label }
                        .performClick()
                }
                fun capture(label: String) {
                    val scroll =
                        nativeViews(root).filterIsInstance<android.widget.ScrollView>().first()
                    scroll.fullScroll(View.FOCUS_DOWN)
                    assertTrue(
                        "Content remains scrollable inside the window",
                        scroll.height > 0 && scroll.height <= root.height,
                    )
                    if (output != null) {
                        val image =
                            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(image))
                        File(
                                output,
                                "enrollment-${if(night) "night" else "day"}-${width}x${height}-1.5-$label.png",
                            )
                            .outputStream()
                            .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                    }
                }
                try {
                    settle()
                    click("Settings")
                    settle()
                    nativeViews(root)
                        .single {
                            it.isShown &&
                                it.contentDescription?.startsWith("Command access.") == true
                        }
                        .performClick()
                    settle()
                    nativeViews(root)
                        .single {
                            it.isShown &&
                                it.contentDescription?.startsWith(
                                    "Identity & verification details."
                                ) == true
                        }
                        .performClick()
                    settle()
                    capture("details-bottom")
                    click("‹ Command access")
                    settle()
                    click("How to authorize this Command")
                    settle()
                    capture("compare-bottom")
                    click("I have the private credential")
                    settle()
                    val field =
                        nativeViews(root).filterIsInstance<EditText>().single {
                            it.hint == "Private enrollment credential"
                        }
                    field.setText("draft")
                    field.requestFocus()
                    field.setSelection(3)
                    app.operational.value = Operational(health = Health(wifi_connected = true))
                    settle()
                    assertTrue(field.hasFocus())
                    assertEquals(3, field.selectionStart)
                    field.text.clear()
                    capture("entry-bottom")
                    val phoneId = withContext(Dispatchers.IO) { app.repository.state().deviceId }
                    field.setText(
                        "gnss1:${Protocol.newId()}:$phoneId:${Protocol.newId()}:${"01".repeat(32)}"
                    )
                    click("Review authorization")
                    settle()
                    controller.recreate()
                    val restored =
                        nativeViews(controller.get().findViewById(android.R.id.content))
                            .filterIsInstance<EditText>()
                            .single { it.hint == "Private enrollment credential" }
                    assertEquals("", restored.text.toString())
                    assertTrue("Review recreation returns to private entry", restored.isShown)
                } finally {
                    controller.pause().stop().destroy()
                }
            }
        } finally {
            app.finishForTests()
        }
    }
}
