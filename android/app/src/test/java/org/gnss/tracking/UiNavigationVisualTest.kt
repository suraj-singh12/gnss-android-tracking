package org.gnss.tracking

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.LocationManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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

/**
 * Real Activity/Room flows, rendered using Robolectric's native Skia renderer. Synthetic telemetry
 * does not establish physical GNSS or OEM behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiNavigationVisualTest {
    private fun descendants(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()

    @Test
    fun navigationAndOperationalStatesRenderAtCompactLargeAndScaledSizes() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        withContext(Dispatchers.IO) { app.repository.state() }
        shadowOf(app)
            .grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        shadowOf(app.getSystemService(LocationManager::class.java))
            .setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val output = System.getenv("GNSS_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }
        for ((width, height, font) in
            listOf(Triple(360, 800, 1f), Triple(480, 960, 1f), Triple(360, 800, 1.5f))) {
            app.physicalButtonTest.clearResults()
            withContext(Dispatchers.IO) { app.recorder.physicalButtonHistory() }
            val config =
                android.content.res.Configuration(app.resources.configuration).apply {
                    fontScale = font
                }
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(config, app.resources.displayMetrics)
            val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val root = activity.get().findViewById<View>(android.R.id.content)
                val density = app.resources.displayMetrics.density
                val w = (width * density).toInt()
                val h = (height * density).toInt()
                suspend fun settle() {
                    repeat(20) {
                        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10))
                        delay(10)
                    }
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
                    )
                    root.layout(0, 0, w, h)
                    for (tab in
                        descendants(root).filterIsInstance<Button>().filter {
                            it.text in listOf("Tracking", "Settings", "Diagnostics")
                        }) {
                        assertEquals(
                            "Navigation label must fit at font scale $font",
                            1,
                            tab.lineCount,
                        )
                    }
                    val sos =
                        descendants(root).filterIsInstance<Button>().single {
                            it.text == "SOS — hold to activate"
                        }
                    if (sos.isShown) {
                        // The test renders the content root directly, without a laid-out window.
                        val bounds = android.graphics.Rect(0, 0, sos.width, sos.height)
                        (root as ViewGroup).offsetDescendantRectToMyCoords(sos, bounds)
                        assertTrue(
                            "SOS must remain fully reachable",
                            bounds.top >= 0 && bounds.bottom <= h,
                        )
                    }
                }
                fun capture(name: String) {
                    if (output == null) return
                    val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(image))
                    File(output, "android-${width}-${font}-$name.png").outputStream().use {
                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    image.recycle()
                }
                suspend fun reveal(view: View) {
                    val scroller =
                        descendants(root).filterIsInstance<android.widget.ScrollView>().first()
                    val body = scroller.getChildAt(0) as ViewGroup
                    val bounds = android.graphics.Rect(0, 0, view.width, view.height)
                    body.offsetDescendantRectToMyCoords(view, bounds)
                    scroller.scrollTo(0, bounds.top)
                    settle()
                }
                awaitInitialized(activity.get())
                for ((name, op) in
                    listOf(
                        "stopped" to Operational(),
                        "tracking" to
                            Operational(
                                tracking = true,
                                gnss = "fix",
                                accuracy = 3.0,
                                ageMillis = 1000,
                                health = Health(battery_percent = 76, wifi_connected = true),
                                link = "Wi-Fi available • Command reachable",
                            ),
                        "offline-stale" to
                            Operational(
                                tracking = true,
                                gnss = "stale",
                                accuracy = 8.0,
                                ageMillis = 90000,
                                health = Health(battery_percent = 43, wifi_connected = false),
                                link = "Wi-Fi unavailable",
                            ),
                    )) {
                    app.operational.value = op
                    settle()
                    capture(name)
                    val visibleText =
                        descendants(root)
                            .filterIsInstance<TextView>()
                            .filter { it.isShown }
                            .joinToString("\n") { it.text }
                    assertTrue(visibleText.contains(if (op.tracking) "TRACKING" else "STOPPED"))
                    if (name == "tracking") {
                        assertTrue(visibleText.contains("GNSS Fresh"))
                        assertTrue(visibleText.contains("Command Connected"))
                    }
                    if (name == "offline-stale") {
                        assertTrue(visibleText.contains("GNSS Stale"))
                        assertTrue(visibleText.contains("Offline"))
                    }
                }
                app.operational.value =
                    Operational(
                        tracking = true,
                        gnss = "fix",
                        accuracy = 3.0,
                        ageMillis = 1000,
                        error = "Command unavailable; retained for retry",
                        link = "Wi-Fi available",
                        health = Health(wifi_connected = true),
                    )
                settle()
                assertTrue(
                    descendants(root).filterIsInstance<TextView>().any {
                        it.isShown && it.text == "TRACKING"
                    }
                )
                capture("tracking-command-unavailable")
                val hold =
                    descendants(root).filterIsInstance<Button>().single {
                        it.text == "SOS — hold to activate"
                    }
                val touch =
                    android.view.MotionEvent.obtain(
                        0,
                        android.os.SystemClock.uptimeMillis(),
                        android.view.MotionEvent.ACTION_DOWN,
                        80f,
                        30f,
                        0,
                    )
                hold.dispatchTouchEvent(touch)
                touch.recycle()
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
                )
                root.layout(0, 0, w, h)
                capture("sos-hold-progress")
                val cancel =
                    android.view.MotionEvent.obtain(
                        0,
                        android.os.SystemClock.uptimeMillis(),
                        android.view.MotionEvent.ACTION_CANCEL,
                        80f,
                        30f,
                        0,
                    )
                hold.dispatchTouchEvent(cancel)
                cancel.recycle()
                withContext(Dispatchers.IO) {
                    app.repository.snapshot(
                        System.currentTimeMillis(),
                        Fix(
                            utc(System.currentTimeMillis()),
                            0,
                            28.6,
                            77.2,
                            3.0,
                            null,
                            null,
                            null,
                            null,
                        ),
                        Health(gnss_status = "fix"),
                    )
                    app.repository.saveSos(
                        Protocol.newId(),
                        System.currentTimeMillis(),
                        System.currentTimeMillis(),
                        null,
                        Health(),
                    )
                }
                settle()
                capture("backlog-sos-pending")
                val all = descendants(root)
                for (destination in listOf("Settings", "Diagnostics", "Tracking")) {
                    all.filterIsInstance<Button>().single { it.text == destination }.performClick()
                    settle()
                    capture("nav-" + destination.lowercase())
                    if (destination == "Diagnostics") {
                        val tools = activity.get().findViewById<ViewGroup>(R.id.diagnostic_tools)
                        assertTrue(tools.isShown)
                        val actions =
                            descendants(tools)
                                .filterIsInstance<Button>()
                                .filter { it.isShown }
                                .map { it.text.toString() }
                        assertEquals(
                            listOf(
                                "Physical Button Test",
                                "Retry saved messages",
                                "Export diagnostics",
                            ),
                            actions,
                        )
                        descendants(tools)
                            .filterIsInstance<Button>()
                            .single { it.text == "Physical Button Test" }
                            .performClick()
                        settle()
                        capture("diagnostics-physical-button-expanded")
                        val diagnosticText =
                            descendants(tools)
                                .filterIsInstance<TextView>()
                                .filter { it.isShown }
                                .joinToString("\n") { it.text }
                        assertTrue(diagnosticText.contains("Test status: INACTIVE"))
                        assertTrue(diagnosticText.contains("Mechanism unavailable"))
                        descendants(tools)
                            .filterIsInstance<Button>()
                            .single { it.text == "Start Test" }
                            .performClick()
                        settle()
                        capture("diagnostics-listening")
                        val now = android.os.SystemClock.uptimeMillis()
                        activity
                            .get()
                            .dispatchKeyEvent(
                                android.view.KeyEvent(
                                    now,
                                    now,
                                    android.view.KeyEvent.ACTION_DOWN,
                                    android.view.KeyEvent.KEYCODE_VOLUME_UP,
                                    0,
                                )
                            )
                        activity
                            .get()
                            .dispatchKeyEvent(
                                android.view.KeyEvent(
                                    now,
                                    now + 10,
                                    android.view.KeyEvent.ACTION_UP,
                                    android.view.KeyEvent.KEYCODE_VOLUME_UP,
                                    0,
                                )
                            )
                        settle()
                        capture("diagnostics-recorded-events")
                        assertTrue(
                            descendants(tools).filterIsInstance<TextView>().any {
                                it.text.contains("Count of actual key presses: 1")
                            }
                        )
                        activity.pause().stop()
                        activity.start().resume()
                        settle()
                        capture("diagnostics-interrupted")
                        assertFalse(app.physicalButtonTest.listening)
                        assertTrue(
                            descendants(tools).filterIsInstance<TextView>().any {
                                it.text.contains("Test status: INTERRUPTED")
                            }
                        )
                        run {
                            all.filterIsInstance<Button>()
                                .single { it.text == "Settings" }
                                .performClick()
                            settle()
                            all.filterIsInstance<Button>()
                                .single { it.text.startsWith("Permissions and battery readiness") }
                                .performClick()
                            settle()
                            reveal(
                                all.filterIsInstance<Button>().single {
                                    it.text.startsWith("Permissions and battery readiness")
                                }
                            )
                            capture("settings-readiness-expanded")
                            all.filterIsInstance<Button>()
                                .single { it.text.startsWith("Emergency shortcut") }
                                .performClick()
                            settle()
                            reveal(
                                all.filterIsInstance<Button>().single {
                                    it.text.startsWith("Emergency shortcut")
                                }
                            )
                            capture("settings-emergency-expanded")
                            (root as ViewGroup).let { view ->
                                descendants(view)
                                    .filterIsInstance<android.widget.ScrollView>()
                                    .first()
                                    .scrollTo(0, 100000)
                            }
                            settle()
                            capture("settings-extended-content")
                        }
                        all.filterIsInstance<Button>()
                            .single { it.text == "Diagnostics" }
                            .performClick()
                        settle()
                        all.filterIsInstance<Button>()
                            .single { it.text.startsWith("Advanced technical evidence") }
                            .performClick()
                        settle()
                        reveal(
                            all.filterIsInstance<Button>().single {
                                it.text.startsWith("Advanced technical evidence")
                            }
                        )
                        capture("diagnostics-advanced")
                        descendants(root)
                            .filterIsInstance<android.widget.ScrollView>()
                            .first()
                            .scrollTo(0, 100000)
                        settle()
                        capture("diagnostics-extended-content")
                    }
                    assertEquals(
                        destination,
                        activity
                            .get()
                            .javaClass
                            .getDeclaredField("destination")
                            .apply { isAccessible = true }
                            .get(activity.get()),
                    )
                }
                val settings = all.filterIsInstance<TextView>().single { it.text == "Party ID" }
                assertFalse(settings.isShown)
                assertTrue(
                    all.filterIsInstance<Button>().single { it.text == "Tracking" }.isSelected
                )
                // Render actionable permission/GPS recovery, not only the granted state.
                app.operational.value = Operational()
                shadowOf(app)
                    .denyPermissions(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                shadowOf(app.getSystemService(LocationManager::class.java))
                    .setProviderEnabled(LocationManager.GPS_PROVIDER, false)
                activity.pause().resume()
                all.filterIsInstance<Button>().single { it.text == "Settings" }.performClick()
                settle()
                reveal(
                    all.filterIsInstance<Button>().single {
                        it.text.startsWith("Permissions and battery readiness")
                    }
                )
                assertTrue(
                    all.filterIsInstance<Button>()
                        .single { it.text == "Allow precise location" }
                        .isShown
                )
                assertTrue(
                    all.filterIsInstance<Button>()
                        .single { it.text == "Open Location settings" }
                        .isShown
                )
                capture("settings-permission-attention")
                shadowOf(app)
                    .grantPermissions(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                shadowOf(app.getSystemService(LocationManager::class.java))
                    .setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            } finally {
                activity.pause().stop().destroy()
            }
        }
        app.operational.value = Operational()
        app.recorder.close()
    }

    private suspend fun awaitInitialized(activity: MainActivity) {
        val field = activity.javaClass.getDeclaredField("initialized").apply { isAccessible = true }
        withTimeout(10000) {
            while (!field.getBoolean(activity)) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
    }
}
