package org.gnss.tracking

import android.Manifest
import android.app.AlertDialog
import android.graphics.Insets
import android.location.LocationManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import java.util.TimeZone
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog

/**
 * Visual regression suite for every Android screen, interaction state and app-owned dialog.
 *
 * For each profile it renders the real MainActivity with deterministic Room fixtures,
 * runs the [VisualAudit] layout rules, compares control/surface geometry with the approved
 * baseline in `src/test/resources/visual-baseline/`, and writes PNG captures plus an
 * index.html/report.json to `build/visual-qa/<profile>/` (or `$GNSS_SCREENSHOT_DIR/visual-qa`).
 *
 * Native Robolectric rendering, not physical-device evidence. See android/UI-DEVELOPMENT.md.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualRegressionTest(private val profile: Profile) {
    data class Profile(
        val night: Boolean,
        val width: Int,
        val height: Int,
        val font: Float,
        val dialogs: Boolean = false,
        val keyboard: Boolean = false,
    ) {
        val name = "${if (night) "night" else "day"}-${width}x$height-$font"
        override fun toString() = name
    }

    companion object {
        /** Fixed instant for every persisted fixture so labels and wrapping are repeatable. */
        const val FIXTURE_TIME = 1_762_683_630_000L // 2025-11-09T10:20:30Z
        const val FIXTURE_DEVICE = "6b0f3c2e-9a41-4d7e-8c55-2f1e0a9b7d34"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun profiles() = listOf(
            Profile(false, 360, 800, 1f, dialogs = true, keyboard = true),
            Profile(true, 360, 800, 1f, dialogs = true),
            Profile(false, 360, 800, 1.5f, dialogs = true, keyboard = true),
            Profile(true, 360, 640, 1.3f, keyboard = true),
            Profile(false, 480, 960, 1f),
            Profile(true, 800, 360, 1f),
        ).map { arrayOf<Any>(it) }

        /**
         * Reviewed, intentional exceptions. Each entry names its reason; anything else fails.
         */
        /**
         * Reviewed, intentional exceptions as (pattern, reason). Keep this list short; every
         * entry must cite why the finding is not a defect. Anything not listed fails.
         */
        private val allowed: List<Pair<Regex, String>> = emptyList()
    }

    private val app get() = ApplicationProvider.getApplicationContext<TrackingApp>()

    private suspend fun idle(n: Int = 12) = repeat(n) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10)); delay(10)
    }

    @Test
    fun everyScreenPassesLayoutAuditAndMatchesApprovedGeometry() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        // Test-only fixed identity before any message exists: a random UUID in a
        // proportional font can wrap differently and move the identity screens.
        val state = withContext(Dispatchers.IO) {
            app.repository.dao.update(app.repository.state().copy(deviceId = FIXTURE_DEVICE))
            app.repository.state()
        }
        assertEquals(FIXTURE_DEVICE, state.deviceId)
        withContext(Dispatchers.IO) {
            app.repository.settings("A-07", "Alpha Patrol", "http://192.168.1.10:8080", 5, false)
            for ((n, outcome) in listOf("blocked", "applied", "expired", "unknown").withIndex())
                app.repository.dao.insertCommand(CommandRecord(
                    "00000000-0000-4000-8000-00000000000$n", "test-command", state.deviceId, "test-enrollment",
                    "reporting", n.toLong(), FIXTURE_TIME - n * 60_000L, state = outcome,
                    reason = if (outcome == "blocked") "OS restriction; open app" else "Recorded $outcome",
                    attempted = outcome != "unknown", requested = "{\"seconds\":20}",
                    effective = if (outcome == "applied") "20 s" else "",
                ))
            app.repository.snapshot(FIXTURE_TIME, Fix(utc(FIXTURE_TIME), 0, 28.6, 77.2, 3.0, null, null, null, null), Health(gnss_status = "fix"))
        }
        shadowOf(app.getSystemService(android.net.ConnectivityManager::class.java)).clearAllNetworks()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        app.getSharedPreferences("appearance", 0).edit().putBoolean("night", profile.night).commit()
        RuntimeEnvironment.setQualifiers("w${profile.width}dp-h${profile.height}dp-${if (profile.width > profile.height) "land" else "port"}-xhdpi")
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(
            android.content.res.Configuration(app.resources.configuration).apply { fontScale = profile.font },
            app.resources.displayMetrics,
        )
        app.operational.value = Operational()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        shadowOf(a.getSystemService(LocationManager::class.java)).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val capture = VisualCapture(profile.name)
        val findings = mutableListOf<VisualFinding>()
        val geometry = JSONObject()
        try {
            val content = a.findViewById<ViewGroup>(android.R.id.content)
            val root = content.getChildAt(0) as ViewGroup
            val density = a.resources.displayMetrics.density
            val audit = VisualAudit(density, a.resources.configuration.fontScale, VisualTokens(a))
            val w = (profile.width * density).toInt()
            val h = (profile.height * density).toInt()
            fun px(n: Int) = (n * density).toInt()
            fun insets(ime: Int = 0) = root.dispatchApplyWindowInsets(
                WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, px(24), 0, px(24)))
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, px(ime))).build())
            fun layout() {
                content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                content.layout(0, 0, w, h)
            }
            suspend fun settle() { idle(); layout() }
            fun allow(f: VisualFinding) = allowed.any { it.first.containsMatchIn(f.toString()) }
            val roots = setOf("Tracking", "Settings", "Diagnostics")
            fun check(name: String, screen: String) {
                layout()
                findings += audit.audit(content, screen, screen in roots, ::allow)
                val pixels = capture.write(name, content)
                geometry.put(name, audit.geometry(content).apply {
                    if (name !in VisualBaseline.hostDependent) put(VisualBaseline.PIXELS, pixels)
                })
                // System bars/navigation insets: app bar below status bar, SOS + nav above nav bar.
                val sos = nativeViews(root).filterIsInstance<Button>().singleOrNull { it.text == "Hold to activate SOS" }
                if (sos != null && sos.isShown)
                    audit.safeArea(content, sos, name, px(24), root.height - root.paddingBottom)?.let { findings += it }
            }
            suspend fun scrollBottom(): Boolean {
                val scroll = nativeViews(root).filterIsInstance<ScrollView>().first()
                val max = scroll.getChildAt(0).height - scroll.height
                if (max <= 0) return false
                scroll.scrollTo(0, max); settle(); return true
            }
            insets()
            settle()
            withTimeout(15000) {
                while (nativeViews(root).filterIsInstance<Button>().none { it.text == "Start Tracking" && it.isEnabled }) idle(1)
            }
            // Tracking states.
            for ((name, op) in listOf(
                "tracking-stopped" to Operational(),
                "tracking-active" to Operational(tracking = true, gnss = "fix", accuracy = 3.0, ageMillis = 1000, health = Health(wifi_connected = true)),
                "tracking-offline" to Operational(tracking = true, gnss = "stale", accuracy = 8.0, ageMillis = 90000, health = Health(wifi_connected = false)),
                "tracking-error" to Operational(tracking = true, error = "Storage needs attention · Open Diagnostics"),
            )) {
                app.operational.value = op
                settle()
                check(name, "Tracking")
            }
            app.operational.value = Operational()
            withContext(Dispatchers.IO) { app.repository.saveSos("00000000-0000-4000-8000-0000000005a5", FIXTURE_TIME, FIXTURE_TIME, null, Health()) }
            settle()
            check("tracking-sos-pending", "Tracking")
            val alert = CommandRecord("00000000-0000-4000-8000-0000000000a1", "test-command", state.deviceId, "test-enrollment",
                "alert", 20L, FIXTURE_TIME, state = "unknown", attempted = true,
                requested = """{"mode":"urgent","message":"Return to base","duration_s":60,"repeat_s":10}""")
            withContext(Dispatchers.IO) { app.repository.dao.insertCommand(alert) }
            withTimeout(15000) {
                // Fixed persisted state ("unknown": output unconfirmed) keeps the banner copy stable.
                while (nativeViews(root).filterIsInstance<android.widget.TextView>().none { it.isShown && it.text.toString().contains("Output unconfirmed") }) idle(1)
            }
            settle()
            check("tracking-urgent-alert", "Tracking")
            if (scrollBottom()) check("tracking-urgent-alert-bottom", "Tracking")
            // Every destination, top and scrolled end.
            for (screen in listOf(
                "Settings", "Party & Command", "Command access", "Manage Command access",
                "Field readiness", "Tracking policy", "Policy details", "Emergency controls",
                "Diagnostics", "Command Instructions", "History search & filters", "Instruction filters", "Phone Alerts",
                "Saved data & sync", "SOS event history", "SOS details",
                "Diagnostic tools", "Advanced logs",
            )) {
                openNativeScreen(a, screen)
                settle()
                val slug = screen.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                check(slug, screen)
                if (scrollBottom()) check("$slug-bottom", screen)
                if (screen == "Command Instructions") {
                    nativeViews(root).first { it.isShown && it.contentDescription?.contains("Open instruction details") == true }.performClick()
                    settle()
                    check("instruction-details", "Instruction details")
                }
            }
            // Interaction states.
            openNativeScreen(a, "Diagnostic tools")
            settle()
            nativeViews(root).filterIsInstance<Button>().single { it.text == "Start Test" }.performClick()
            settle()
            check("physical-button-listening", "Diagnostic tools")
            // A real Activity key event is the only source of a displayed result.
            a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_UP))
            settle()
            nativeViews(root).filterIsInstance<Button>().single { it.text == "Stop Test" }.performClick()
            settle()
            check("physical-button-event", "Diagnostic tools")
            if (profile.keyboard) {
                openNativeScreen(a, "Party & Command")
                settle()
                val field = nativeViews(root).filterIsInstance<EditText>().first { it.isShown }
                field.requestFocus()
                insets(ime = 300)
                settle()
                check("party-command-keyboard", "Party & Command")
                val sos = nativeViews(root).filterIsInstance<Button>().single { it.text == "Hold to activate SOS" }
                assertTrue("SOS dock stays above the keyboard", audit.bounds(content, sos).bottom <= h - px(300))
                field.clearFocus()
                insets()
                settle()
            }
            if (profile.dialogs) {
                suspend fun dialog(name: String, open: suspend () -> Unit) {
                    open()
                    idle()
                    val d = ShadowAlertDialog.getLatestAlertDialog()
                    assertTrue("$name dialog shown", d.isShowing)
                    val decor = d.window!!.decorView as ViewGroup
                    decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h - px(48), View.MeasureSpec.AT_MOST))
                    decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                    findings += audit.audit(decor, "dialog:$name", true, ::allow)
                    val pixels = capture.write("dialog-$name", decor)
                    geometry.put("dialog-$name", audit.geometry(decor).apply { put(VisualBaseline.PIXELS, pixels) })
                    val negative = d.getButton(AlertDialog.BUTTON_NEGATIVE)
                    if (negative != null && negative.isShown) negative.performClick() else d.dismiss()
                    idle()
                }
                fun click(text: String) = nativeViews(content).filterIsInstance<Button>().single { it.isShown && it.text == text }.performClick()
                dialog("stop-tracking") {
                    openNativeScreen(a, "Tracking"); app.operational.value = Operational(tracking = true); settle(); click("Stop Tracking")
                }
                app.operational.value = Operational(); settle()
                dialog("enrollment-steps") { openNativeScreen(a, "Command access"); settle(); click("Enrollment steps ›") }
                dialog("disable-remote-control") { openNativeScreen(a, "Manage Command access"); settle(); click("Disable remote control") }
                dialog("re-enroll") { openNativeScreen(a, "Manage Command access"); settle(); click("Re-enroll Command") }
                dialog("instruction-filter") { openNativeScreen(a, "History search & filters"); settle(); click("Filter history") }
                dialog("retry") { openNativeScreen(a, "Saved data & sync"); settle(); click("Retry saved messages") }
                dialog("phone-alert") { openNativeScreen(a, "Phone Alerts"); settle(); click("View alert & acknowledge") }
                dialog("invalid-interval") {
                    openNativeScreen(a, "Party & Command"); settle()
                    val label = nativeViews(root).filterIsInstance<android.widget.TextView>().single { it.text == "Custom interval · seconds" && it.labelFor != View.NO_ID && (it.parent as View).isShown }
                    a.findViewById<EditText>(label.labelFor).setText("3")
                    click("Save settings")
                }
                dialog("unsaved-edits") {
                    @Suppress("DEPRECATION") a.onBackPressed()
                    openNativeScreen(a, "Tracking")
                    @Suppress("DEPRECATION") a.onBackPressed()
                }
            }
            val diffs = VisualBaseline.compare(profile.name, geometry)
            capture.report(findings, diffs)
            assertTrue("Visual audit findings for ${profile.name} (see ${capture.dir}/index.html):\n" + findings.joinToString("\n"), findings.isEmpty())
            assertTrue("Geometry differs from the approved baseline for ${profile.name}. Review captures, then record a justified baseline:\n" + diffs.joinToString("\n"), diffs.isEmpty())
        } finally {
            controller.pause().stop().destroy()
            app.finishForTests()
        }
    }
}
