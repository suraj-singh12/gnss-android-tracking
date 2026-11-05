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
        assertFalse(button.performLongClick())
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
    fun accessibilityActionWaitsFullProtectionAndRepeatedActionsCannotDuplicate() {
        assertTrue(button.performLongClick())
        assertEquals(0, calls)
        advance(600)
        assertTrue(button.performLongClick())
        advance(599)
        assertEquals(0, calls)
        advance(1)
        assertEquals(1, calls)
        assertTrue(button.performLongClick())
        advance(2400)
        assertEquals(1, calls)
        gesture.cancel()
        eligible = false
        assertFalse(button.performLongClick())
    }

    @Test
    fun accessibilityCancelActionAndTouchCancelCannotActivateOrStartAnotherHold() {
        assertTrue(button.performLongClick())
        advance(1199)
        val info = button.createAccessibilityNodeInfo()
        assertTrue(
            info.actionList.any {
                it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK &&
                    it.label == "Cancel SOS activation"
            }
        )
        assertTrue(button.contentDescription.contains("pending"))
        assertTrue(
            button.performAccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
                null,
            )
        )
        advance(1400)
        assertEquals(0, calls)
        assertEquals(0, progress.progress)
        button.performLongClick()
        advance(600)
        event(MotionEvent.ACTION_DOWN)
        assertFalse(button.performLongClick())
        advance(2000)
        event(MotionEvent.ACTION_UP)
        assertEquals(0, calls)
        event(MotionEvent.ACTION_DOWN)
        advance(1200)
        assertEquals(1, calls)
    }

    @Test
    fun actualNewAccessibilityFocusCancelsButAnnouncementsAndSameFocusDoNot() {
        val window =
            org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        window.get().setContentView(parent)
        shadowOf(
                context.getSystemService(
                    android.view.accessibility.AccessibilityManager::class.java
                )
            )
            .setEnabled(true)
        gesture.monitorFocus(parent)
        assertTrue("Protected action starts", button.performLongClick())
        assertTrue("Pending descriptor", button.contentDescription.toString().contains("pending"))
        advance(400)
        assertTrue("Timer advances", progress.progress > 0)
        for (type in
            listOf(
                android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT,
                android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            )) {
            parent.requestSendAccessibilityEvent(
                button,
                android.view.accessibility.AccessibilityEvent.obtain(type),
            )
            assertTrue(
                "Pending after event $type",
                button.contentDescription.toString().contains("pending"),
            )
        }
        advance(800)
        advance(25) // attached-window work can defer the next 25 ms timer tick
        assertEquals("Announcements preserve the delayed activation", 1, calls)
        gesture.cancel()
        button.performLongClick()
        advance(600)
        val other = Button(context)
        parent.addView(other)
        parent.requestSendAccessibilityEvent(
            other,
            android.view.accessibility.AccessibilityEvent.obtain(
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
            ),
        )
        advance(1400)
        assertEquals(1, calls)
        button.isFocusableInTouchMode = true
        other.isFocusableInTouchMode = true
        assertTrue(button.requestFocus())
        button.performLongClick()
        advance(600)
        assertTrue(other.requestFocus())
        advance(1400)
        assertEquals(1, calls)
        gesture.dispose()
        window.pause().stop().destroy()
    }

    @Test
    fun interruptedAccessibilityAttemptCannotCompleteAfterEligibilityLossOrDisposal() {
        button.performLongClick()
        advance(600)
        gesture.cancel()
        advance(1400)
        assertEquals(0, calls)
        button.performLongClick()
        advance(600)
        eligible = false
        advance(1400)
        assertEquals(0, calls)
        eligible = true
        button.performLongClick()
        advance(600)
        gesture.dispose()
        advance(1400)
        assertEquals(0, calls)
    }

    @Test
    fun keyboardRequiresContinuousFullHoldAndCanCancelAccessibilityAttempt() {
        fun key(action: Int, repeat: Int = 0) =
            button.dispatchKeyEvent(
                android.view.KeyEvent(
                    0,
                    SystemClock.uptimeMillis(),
                    action,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    repeat,
                )
            )
        key(android.view.KeyEvent.ACTION_DOWN)
        advance(600)
        key(android.view.KeyEvent.ACTION_UP)
        advance(1400)
        assertEquals(0, calls)
        key(android.view.KeyEvent.ACTION_DOWN)
        advance(1199)
        assertEquals(0, calls)
        advance(1)
        assertEquals(1, calls)
        key(android.view.KeyEvent.ACTION_DOWN, 1)
        advance(1400)
        assertEquals(1, calls)
        key(android.view.KeyEvent.ACTION_UP)
        button.performLongClick()
        advance(600)
        key(android.view.KeyEvent.ACTION_DOWN)
        advance(1400)
        key(android.view.KeyEvent.ACTION_UP)
        assertEquals(1, calls)
    }
}
