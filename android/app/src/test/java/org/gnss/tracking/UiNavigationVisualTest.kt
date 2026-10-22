package org.gnss.tracking

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.LocationManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
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

/** Actual native Activity views and Room fixtures. This is not physical-device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiNavigationVisualTest {
    @Test
    fun navigationAndOperationalStatesRenderAtCompactLargeAndScaledSizes() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        val state = withContext(Dispatchers.IO) { app.repository.state() }
        withContext(Dispatchers.IO) {
            app.repository.settings("A-07", "Alpha Patrol", "http://192.168.1.10:8080", 5, false)
            for ((n, outcome) in listOf("blocked", "applied", "expired", "unknown").withIndex()) {
                app.repository.dao.insertCommand(
                    CommandRecord(
                        Protocol.newId(),
                        "test-command",
                        state.deviceId,
                        "test-enrollment",
                        "reporting",
                        n.toLong(),
                        System.currentTimeMillis() - n * 1000L,
                        state = outcome,
                        reason =
                            if (outcome == "blocked") "OS restriction; open app"
                            else "Recorded $outcome",
                        attempted = outcome != "unknown",
                        requested = "{\"seconds\":20}",
                        effective = if (outcome == "applied") "20 s" else "",
                    )
                )
            }
            app.repository.snapshot(
                System.currentTimeMillis(),
                Fix(utc(System.currentTimeMillis()), 0, 28.6, 77.2, 3.0, null, null, null, null),
                Health(gnss_status = "fix"),
            )
        }
        shadowOf(app.getSystemService(android.net.ConnectivityManager::class.java))
            .clearAllNetworks()
        shadowOf(app)
            .grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        val output = System.getenv("GNSS_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }
        val cases =
            listOf(
                Triple(360, 800, 1f),
                Triple(480, 960, 1f),
                Triple(360, 800, 1.5f),
                Triple(480, 960, 1.5f),
                Triple(800, 360, 1f),
                Triple(960, 480, 1.5f),
                Triple(390, 842, 1f),
            )
        for (night in listOf(false, true)) for ((width, height, font) in cases) {
            app.getSharedPreferences("appearance", 0).edit().putBoolean("night", night).commit()
            org.robolectric.RuntimeEnvironment.setQualifiers(
                "w${width}dp-h${height}dp-${if(width>height) "land" else "port"}"
            )
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(
                android.content.res.Configuration(app.resources.configuration).apply {
                    fontScale = font
                },
                app.resources.displayMetrics,
            )
            app.operational.value = Operational()
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val a = controller.get()
            shadowOf(a.getSystemService(LocationManager::class.java))
                .setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            try {
                val root = a.findViewById<ViewGroup>(android.R.id.content)
                val density = a.resources.displayMetrics.density
                val w = (width * density).toInt()
                val h = (height * density).toInt()
                suspend fun settle() {
                    repeat(12) {
                        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10))
                        delay(10)
                    }
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
                    )
                    root.layout(0, 0, w, h)
                    for (tab in
                        nativeViews(root).filterIsInstance<Button>().filter {
                            it.text in listOf("Tracking", "Settings", "Diagnostics")
                        }) assertEquals("Tab fits", 1, tab.lineCount)
                    val sos =
                        nativeViews(root).filterIsInstance<Button>().single {
                            it.text == "HOLD FOR SOS"
                        }
                    if (sos.isShown) {
                        val bounds = android.graphics.Rect(0, 0, sos.width, sos.height)
                        root.offsetDescendantRectToMyCoords(sos, bounds)
                        assertTrue(
                            "SOS reachable at $width × $height / $font",
                            bounds.top >= 0 && bounds.bottom <= h,
                        )
                        assertTrue(sos.height >= 48 * density)
                    }
                }
                fun capture(name: String) {
                    if (output == null) return
                    val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(image))
                    File(
                            output,
                            "android-${if(night) "night" else "day"}-${width}x${height}-${font}-$name.png",
                        )
                        .outputStream()
                        .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
                withTimeout(15000) {
                    while (
                        nativeViews(root).filterIsInstance<Button>().none {
                            it.text == "Start Tracking" && it.isEnabled
                        }
                    ) {
                        shadowOf(Looper.getMainLooper()).idle()
                        delay(10)
                    }
                }
                fun actionBounds(): android.graphics.Rect {
                    val sync =
                        nativeViews(root).filterIsInstance<Button>().single {
                            it.text == "Sync Now"
                        }
                    assertTrue(
                        "Sync action is measured and reachable",
                        sync.width > 0 && sync.height >= 48 * density,
                    )
                    return android.graphics.Rect(0, 0, sync.width, sync.height).apply {
                        root.offsetDescendantRectToMyCoords(sync, this)
                    }
                }
                var stableActions: android.graphics.Rect? = null
                for ((name, op) in
                    listOf(
                        "tracking-stopped" to Operational(),
                        "tracking-active" to
                            Operational(
                                tracking = true,
                                gnss = "fix",
                                accuracy = 3.0,
                                ageMillis = 1000,
                                health = Health(wifi_connected = true),
                            ),
                        "tracking-offline" to
                            Operational(
                                tracking = true,
                                gnss = "stale",
                                accuracy = 8.0,
                                ageMillis = 90000,
                                health = Health(wifi_connected = false),
                            ),
                        "tracking-error" to
                            Operational(
                                tracking = true,
                                error = "Storage needs attention · Open Diagnostics",
                            ),
                    )) {
                    app.operational.value = op
                    settle()
                    capture(name)
                    if (stableActions == null) stableActions = actionBounds()
                    else
                        assertEquals(
                            "Core actions stay in place for $name",
                            stableActions,
                            actionBounds(),
                        )
                    val visible =
                        nativeViews(root)
                            .filterIsInstance<TextView>()
                            .filter { it.isShown }
                            .joinToString(" ") { it.text }
                    assertTrue(visible.contains(if (op.tracking) "Tracking on" else "Tracking off"))
                }
                // Real persisted SOS fixture, never a production demonstration transaction.
                withContext(Dispatchers.IO) {
                    app.repository.saveSos(
                        Protocol.newId(),
                        System.currentTimeMillis(),
                        System.currentTimeMillis(),
                        null,
                        Health(),
                    )
                }
                settle()
                capture("tracking-sos-pending")
                val alert =
                    CommandRecord(
                        Protocol.newId(),
                        "test-command",
                        state.deviceId,
                        "test-enrollment",
                        "alert",
                        20L,
                        System.currentTimeMillis(),
                        state = "output_attempted",
                        attempted = true,
                        requested =
                            """{"mode":"urgent","message":"Return to base","duration_s":60,"repeat_s":10}""",
                    )
                withContext(Dispatchers.IO) { app.repository.dao.insertCommand(alert) }
                settle()
                capture("tracking-urgent-alert")
                assertEquals("Urgent alert retains core actions", stableActions, actionBounds())
                nativeViews(root)
                    .filterIsInstance<Button>()
                    .first { it.isShown && it.text == "Acknowledge / Stop Alert" }
                    .performClick()
                withTimeout(10000) {
                    while (
                        withContext(Dispatchers.IO) {
                            app.repository.dao.command(alert.id)?.state
                        } != "recipient_acknowledged"
                    ) {
                        shadowOf(Looper.getMainLooper()).idle()
                        delay(10)
                    }
                }
                settle()
                capture("tracking-alert-acknowledged")
                for (name in
                    listOf(
                        "Settings",
                        "Party & Command",
                        "Edit connection",
                        "Command trust",
                        "Change trusted Command",
                        "Tracking & GNSS",
                        "Reporting interval",
                        "Policy details",
                        "Field readiness",
                        "Appearance & SOS",
                        "Advanced settings",
                        "Command access",
                        "Diagnostics",
                        "Command Instructions",
                        "Instruction filters",
                        "Phone Alerts",
                        "Saved data & sync",
                        "Storage health",
                        "Diagnostic tools",
                        "Advanced logs",
                        "SOS event history",
                        "SOS details",
                        "Physical Button Test",
                    )) {
                    openNativeScreen(a, name)
                    settle()
                    capture(name.lowercase().replace(Regex("[^a-z0-9]+"), "-"))
                    if (name == "Physical Button Test") {
                        nativeViews(root)
                            .filterIsInstance<Button>()
                            .single { it.text == "Start Test" }
                            .performClick()
                        settle()
                        capture("physical-button-listening")
                        a.dispatchKeyEvent(
                            android.view.KeyEvent(
                                android.view.KeyEvent.ACTION_DOWN,
                                android.view.KeyEvent.KEYCODE_VOLUME_UP,
                            )
                        )
                        settle()
                        capture("physical-button-event")
                        nativeViews(root)
                            .filterIsInstance<Button>()
                            .single { it.text == "Stop Test" }
                            .performClick()
                    }
                    if (name == "Command Instructions") {
                        nativeViews(root)
                            .filterIsInstance<Button>()
                            .first {
                                it.contentDescription?.contains("Open instruction details") == true
                            }
                            .performClick()
                        settle()
                        capture("instruction-details")
                        nativeViews(root)
                            .filterIsInstance<Button>()
                            .single { it.text == "Full record" }
                            .performClick()
                        settle()
                        capture("instruction-full-record")
                    }
                    // Capture the lower viewport of every nested route at large fonts.
                    val scroller =
                        nativeViews(root).filterIsInstance<android.widget.ScrollView>().first()
                    scroller.fullScroll(View.FOCUS_DOWN)
                    settle()
                    capture(name.lowercase().replace(Regex("[^a-z0-9]+"), "-") + "-bottom")
                }
                openNativeScreen(a, "Tracking & GNSS")
                settle()
                val adaptive =
                    nativeViews(root).filterIsInstance<Switch>().single {
                        it.text == "Adaptive storage"
                    }
                adaptive.performClick()
                settle()
                capture("adaptive-on")
                assertTrue(app.persistencePolicy.state.enabled)
                adaptive.performClick()
                settle()
                assertFalse(app.persistencePolicy.state.enabled)
                openNativeScreen(a, "Tracking")
                app.operational.value = Operational(tracking = true)
                settle()
                nativeViews(root)
                    .filterIsInstance<Button>()
                    .single { it.text == "Stop Tracking" }
                    .performClick()
                settle()
                val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                assertEquals("Stop tracking on this phone?", shadowOf(dialog).title.toString())
                dialog.dismiss()
            } finally {
                controller.pause().stop().destroy()
            }
        }
        app.finishForTests()
    }
}
