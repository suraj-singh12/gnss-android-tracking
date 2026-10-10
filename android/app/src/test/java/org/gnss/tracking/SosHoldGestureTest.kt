package org.gnss.tracking

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Button
import android.widget.ProgressBar
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SosHoldGestureTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val button = Button(context).apply { layout(0, 0, 240, 64) }
    private val parent = android.widget.LinearLayout(context).apply { addView(button) }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private var eligible = true
    private var calls = 0
    private val gesture = SosHoldGesture(button, progress, { eligible }) { calls++ }

    private fun event(action: Int, x: Float = 120f, y: Float = 32f) {
        val e = MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, x, y, 0)
        gesture.onTouch(button, e)
        e.recycle()
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun completeHoldActivatesExactlyOnceWithProgress() {
        event(MotionEvent.ACTION_DOWN)
        advance(600)
        assertEquals("Half of a complete hold must show 50% progress", 50, progress.progress)
        assertEquals(0, calls)
        advance(600)
        assertEquals(1, calls)
        assertEquals(100, progress.progress)
        advance(2400)
        assertEquals(1, calls)
        event(MotionEvent.ACTION_UP)
        assertEquals(0, progress.progress)
    }

    @Test
    fun earlyReleaseNeverActivates() {
        event(MotionEvent.ACTION_DOWN)
        advance(1175)
        event(MotionEvent.ACTION_UP)
        advance(2000)
        assertEquals(0, calls)
    }

    @Test
    fun smallMotionWithinSlopRetainsHoldButLeavingCancels() {
        event(MotionEvent.ACTION_DOWN)
        advance(500)
        event(MotionEvent.ACTION_MOVE, 125f, 34f)
        advance(700)
        assertEquals(1, calls)
        event(MotionEvent.ACTION_UP)
        event(MotionEvent.ACTION_DOWN)
        event(MotionEvent.ACTION_MOVE, 500f, 32f)
        advance(2000)
        assertEquals(1, calls)
    }

    @Test
    fun cancellationPointerChangeAndLifecycleLossNeverActivate() {
        for (action in
            listOf(
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_OUTSIDE,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP,
            )) {
            event(MotionEvent.ACTION_DOWN)
            advance(600)
            event(action)
            advance(1400)
        }
        event(MotionEvent.ACTION_DOWN)
        advance(600)
        gesture.cancel()
        advance(1400)
        event(MotionEvent.ACTION_DOWN)
        advance(600)
        eligible = false
        advance(1400)
        assertEquals(0, calls)
        assertEquals(0, progress.progress)
    }

    @Test
    fun newGestureDoesNotInheritPreviousTime() {
        event(MotionEvent.ACTION_DOWN)
        advance(1000)
        event(MotionEvent.ACTION_UP)
        event(MotionEvent.ACTION_DOWN)
        advance(300)
        assertEquals(0, calls)
        advance(900)
        assertEquals(1, calls)
        event(MotionEvent.ACTION_UP)
        event(MotionEvent.ACTION_DOWN)
        advance(1200)
        assertEquals(2, calls)
    }

    @Test
    fun accessibilityLongClickIsDeliberateAndNotRacedByTouch() {
        assertTrue(button.performLongClick())
        assertEquals(1, calls)
        event(MotionEvent.ACTION_DOWN)
        assertFalse(button.performLongClick())
        advance(1200)
        assertEquals(2, calls)
        gesture.cancel()
        eligible = false
        assertFalse(button.performLongClick())
        assertEquals(2, calls)
    }
}
