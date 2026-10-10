package org.gnss.tracking

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.ProgressBar

/** One foreground gesture adapter; the existing SOS engine owns durability and delivery. */
internal class SosHoldGesture(
    private val button: Button,
    private val progress: ProgressBar,
    private val eligible: () -> Boolean,
    private val activate: () -> Unit,
) : View.OnTouchListener {
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(button.context).scaledTouchSlop
    private var pointer: Int? = null
    private var started = 0L
    private var completed = false
    private val tick =
        object : Runnable {
            override fun run() {
                if (pointer == null || completed) return
                if (!eligible()) {
                    cancel()
                    return
                }
                val elapsed = SystemClock.uptimeMillis() - started
                progress.progress = (elapsed * 100 / HOLD_MS).toInt().coerceIn(0, 100)
                button.text = "Hold SOS · ${progress.progress}%"
                if (elapsed >= HOLD_MS) {
                    completed = true
                    button.text = "SOS activated · saving locally"
                    button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    activate()
                } else handler.postDelayed(this, 25)
            }
        }

    init {
        progress.isIndeterminate = false
        progress.max = 100
        button.setOnTouchListener(this)
        // TalkBack exposes an intentional long-click action; native touch long-click
        // is bypassed by this consuming listener so it cannot race our hold timer.
        button.setOnLongClickListener {
            if (!eligible() || pointer != null) false
            else {
                cancel()
                button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                activate()
                true
            }
        }
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                if (!eligible() || event.pointerCount != 1) return true
                pointer = event.getPointerId(0)
                started = SystemClock.uptimeMillis()
                button.isPressed = true
                button.parent?.requestDisallowInterceptTouchEvent(true)
                button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                tick.run()
            }
            MotionEvent.ACTION_MOVE -> {
                val index = pointer?.let(event::findPointerIndex) ?: -1
                if (
                    index < 0 ||
                        event.pointerCount != 1 ||
                        event.getX(index) < -slop ||
                        event.getX(index) > button.width + slop ||
                        event.getY(index) < -slop ||
                        event.getY(index) > button.height + slop
                )
                    cancel()
            }
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_OUTSIDE -> cancel()
            MotionEvent.ACTION_UP -> {
                val wasCompleted = completed
                cancel()
                if (!wasCompleted) button.performClick()
            }
        }
        return true
    }

    fun cancel() {
        handler.removeCallbacks(tick)
        pointer = null
        completed = false
        button.isPressed = false
        button.parent?.requestDisallowInterceptTouchEvent(false)
        progress.progress = 0
        button.text = "SOS — hold to activate"
    }

    companion object {
        const val HOLD_MS = 1200L
    }
}
