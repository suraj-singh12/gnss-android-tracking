package org.gnss.tracking

import android.app.AlertDialog
import android.graphics.Insets
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*
import androidx.test.core.app.ApplicationProvider
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

/** Native geometry and live Room values; fixtures are test-only, never product demo state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V46PolishTest {
    private suspend fun settle() { repeat(20) { shadowOf(Looper.getMainLooper()).idle(); delay(10) } }

    @Test fun savedMetricsDisplayActualRoomCountsAndLongEvidenceRemainsSelectable() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        withContext(Dispatchers.IO) {
            app.repository.snapshot(System.currentTimeMillis(), Fix(utc(System.currentTimeMillis()), 0, 28.6, 77.2, 3.0, null, null, null, null), Health(gnss_status = "fix"))
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            settle()
            val a = controller.get()
            openNativeScreen(a, "Saved data & sync")
            settle()
            val root = a.findViewById<ViewGroup>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(842, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 390, 842)
            for ((caption, expected) in listOf("Pending observations" to "1", "Command ACKed" to "0")) {
                val label = nativeViews(root).filterIsInstance<TextView>().single { it.isShown && it.text == caption }
                val metric = (label.parent as LinearLayout).getChildAt(0) as TextView
                assertEquals(expected, metric.text.toString())
                assertTrue("Real queue value has visible width", metric.width > 0)
                assertTrue("Metric is laid out above its caption", metric.bottom <= label.top)
            }
            val source = TextView(a).apply { text = "Address: http://192.168.1.10:8080\nIdentity: 12345678-1234-4234-8234-123456789012\nUnknown condition: Unverified\n2026-11-09T10:20:30Z" }
            val host = NativeUi(a).evidence(source)
            val values = nativeViews(host).filterIsInstance<TextView>().filter { it.visibility != View.GONE }
            assertTrue(values.any { it.text.toString() == "http://192.168.1.10:8080" && it.isTextSelectable })
            assertTrue(values.any { it.text.toString() == "12345678-1234-4234-8234-123456789012" && it.isTextSelectable })
            assertTrue(values.any { it.text.toString() == "Unverified" })
            assertTrue(values.any { it.text.toString() == "2026-11-09T10:20:30Z" })
            source.text = source.text.toString().replace("Unverified", "Unavailable")
            assertTrue(nativeViews(host).filterIsInstance<TextView>().any { it.text.toString() == "Unavailable" })
        } finally { controller.pause().stop().destroy(); app.finishForTests() }
    }

    @Test fun systemSideInsetsKeyboardAndScrollEndKeepProtectedSosAndLastControlsReachable() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            settle()
            val a = controller.get()
            val content = a.findViewById<ViewGroup>(android.R.id.content)
            val root = content.getChildAt(0) as ViewGroup
            val density = a.resources.displayMetrics.density
            fun px(n: Int) = (n * density).toInt()
            fun layout(w: Int, h: Int) {
                content.measure(View.MeasureSpec.makeMeasureSpec(px(w), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(px(h), View.MeasureSpec.EXACTLY))
                content.layout(0, 0, px(w), px(h))
            }
            fun bounds(view: View): Rect = Rect(view.scrollX, view.scrollY, view.scrollX + view.width, view.scrollY + view.height).apply { content.offsetDescendantRectToMyCoords(view, this) }
            for ((w, h, side, bottom, keyboard) in listOf(listOf(360,800,0,24,0), listOf(390,842,0,0,280), listOf(800,480,24,0,0))) {
                root.dispatchApplyWindowInsets(WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(), Insets.of(px(side),px(24),px(side),px(bottom))).setInsets(WindowInsets.Type.ime(), Insets.of(0,0,0,px(keyboard))).build())
                layout(w,h)
                assertEquals(px(side), root.paddingLeft)
                assertEquals(px(side), root.paddingRight)
                assertEquals(px(maxOf(bottom,keyboard)), root.paddingBottom)
                val sos = nativeViews(root).filterIsInstance<Button>().single { it.text == "Hold to activate SOS" }
                val b = bounds(sos)
                assertTrue("SOS whole hit target above keyboard/navigation", b.top >= px(24) && b.bottom <= px(h - maxOf(bottom,keyboard)))
                assertTrue(sos.height >= px(48))
            }
            root.dispatchApplyWindowInsets(WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(), Insets.of(0,px(24),0,px(24))).build())
            for (name in listOf("Tracking", "Diagnostics", "Identity & verification details", "Policy details", "Storage health", "Advanced logs")) {
                openNativeScreen(a,name)
                settle()
                layout(360,800)
                val scroll = nativeViews(root).filterIsInstance<ScrollView>().first()
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                layout(360,800)
                val page = (scroll.getChildAt(0) as ViewGroup).let { container -> (0 until container.childCount).map { container.getChildAt(it) }.single { it.visibility == View.VISIBLE } } as ViewGroup
                val last = (0 until page.childCount).map { page.getChildAt(it) }.last { it.visibility == View.VISIBLE }
                val end = bounds(last)
                assertTrue("$name final content clears footer", end.bottom <= bounds(scroll).bottom)
            }
            assertEquals(0, app.repository.dao.sosHistory().size)
        } finally { controller.pause().stop().destroy(); app.finishForTests() }
    }

    @Test fun filterActionsAreSentenceCasePaddedAndCancelRetainsAppliedSelection() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            settle()
            val a = controller.get()
            openNativeScreen(a,"History search & filters")
            fun open(): AlertDialog {
                nativeViews(a.findViewById(android.R.id.content)).filterIsInstance<Button>().single { it.isShown && it.text == "Filter history" }.performClick()
                return ShadowAlertDialog.getLatestAlertDialog()
            }
            var dialog = open()
            val switches = nativeViews(dialog.window!!.decorView).filterIsInstance<Switch>()
            assertEquals(3,switches.size)
            assertTrue((switches.first().parent as View).paddingLeft >= NativeUi(a).dp(20))
            val original = switches.map { it.isChecked }
            switches.last().performClick()
            assertEquals("Apply filters",dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            assertEquals("Apply filters", dialog.getButton(AlertDialog.BUTTON_POSITIVE).let { it.transformationMethod?.getTransformation(it.text, it)?.toString() ?: it.text.toString() })
            assertTrue(nativeViews(dialog.window!!.decorView).filterIsInstance<Button>().any { it.text == "Canonical categories" && it.isShown })
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            dialog=open()
            assertEquals(original,nativeViews(dialog.window!!.decorView).filterIsInstance<Switch>().map { it.isChecked })
            val decor = dialog.window!!.decorView as ViewGroup
            decor.measure(View.MeasureSpec.makeMeasureSpec(NativeUi(a).dp(328), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(NativeUi(a).dp(320), View.MeasureSpec.AT_MOST))
            decor.layout(0,0,decor.measuredWidth,decor.measuredHeight)
            for (id in listOf(AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_POSITIVE)) {
                val button = dialog.getButton(id)
                val bounds = Rect(0,0,button.width,button.height).apply { decor.offsetDescendantRectToMyCoords(button,this) }
                assertTrue("Short-window filter actions remain accessible: $bounds", button.isShown && bounds.top >= 0 && bounds.bottom <= decor.height)
            }
            dialog.dismiss()
        } finally { controller.pause().stop().destroy(); app.finishForTests() }
    }
}
