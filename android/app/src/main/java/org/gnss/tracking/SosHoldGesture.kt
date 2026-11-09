package org.gnss.tracking

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ProgressBar

/** Input protection only. The existing app SOS engine owns commitment and delivery. */
internal class SosHoldGesture(
    private val button: Button,
    private val progress: ProgressBar,
    private val eligible: () -> Boolean,
    private val activate: () -> Unit,
) : View.OnTouchListener {
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(button.context).scaledTouchSlop
    private val idleDescription = button.contentDescription
    private var pointer: Int? = null
    private var heldKey: Int? = null
    private var started = 0L
    private var completed = false
    private var accessibilityPending = false
    private var accessibilityConsumed = false
    private var cancelTouch = false
    private var cancelKey = false
    private var focusAtArm: View? = null
    private var focusRoot: ViewGroup? = null
    private var focusParent: ViewGroup? = null
    private val nativeFocus =
        ViewTreeObserver.OnGlobalFocusChangeListener { old, next ->
            if (old != null && old !== next && next != null && next !== button) {
                if (
                    heldKey != null ||
                        (accessibilityPending && next !== focusAtArm) ||
                        accessibilityConsumed
                )
                    cancel()
            }
        }
    private val tick =
        object : Runnable {
            override fun run() {
                if ((pointer == null && heldKey == null && !accessibilityPending) || completed)
                    return
                if (!eligible()) {
                    cancel()
                    return
                }
                val elapsed = SystemClock.uptimeMillis() - started
                progress.progress = (elapsed * 100 / HOLD_MS).toInt().coerceIn(0, 100)
                button.text =
                    if (accessibilityPending) "Cancel SOS activation"
                    else "Hold SOS · ${progress.progress}%"
                if (elapsed >= HOLD_MS) {
                    val accessibleAttempt = accessibilityPending
                    completed = true
                    if (accessibilityPending) {
                        accessibilityPending = false
                        accessibilityConsumed = true
                        button.contentDescription =
                            "SOS activation requested. Read Emergency SOS status for saving and delivery."
                        if (Build.VERSION.SDK_INT >= 30)
                            button.stateDescription = "Activation requested"
                        button.announceForAccessibility("SOS activation requested")
                    }
                    button.text =
                        if (accessibleAttempt) "SOS activation requested"
                        else "SOS activated · saving locally"
                    button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    activate()
                } else handler.postDelayed(this, 25)
            }
        }

    init {
        progress.isIndeterminate = false
        progress.max = 100
        button.setOnTouchListener(this)
        // ACTION_LONG_CLICK has no release/duration. It starts a distinct protected
        // accessibility attempt; it can never call the SOS engine directly.
        button.setOnLongClickListener {
            when {
                !eligible() || pointer != null || heldKey != null || cancelTouch || cancelKey ->
                    false
                accessibilityPending -> true // repeated action must not reset/duplicate the timer
                accessibilityConsumed -> true // one activation per focused accessibility attempt
                else -> {
                    started = SystemClock.uptimeMillis()
                    completed = false
                    accessibilityPending = true
                    focusAtArm = button.rootView.findFocus()
                    button.contentDescription =
                        "SOS activation pending. Activate Cancel SOS activation to cancel."
                    if (Build.VERSION.SDK_INT >= 30)
                        button.stateDescription = "Activation pending; Cancel available"
                    button.announceForAccessibility("SOS pending. Cancel available.")
                    tick.run()
                    true
                }
            }
        }
        button.setAccessibilityDelegate(
            object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(
                        AccessibilityNodeInfo.AccessibilityAction(
                            AccessibilityNodeInfo.ACTION_CLICK,
                            if (accessibilityPending) "Cancel SOS activation"
                            else if (accessibilityConsumed) "SOS activation already requested"
                            else "SOS hold instructions",
                        )
                    )
                    if (accessibilityConsumed) {
                        info.removeAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK
                        )
                        info.isLongClickable = false
                    } else
                        info.addAction(
                            AccessibilityNodeInfo.AccessibilityAction(
                                AccessibilityNodeInfo.ACTION_LONG_CLICK,
                                "Activate SOS with 1.2-second protection",
                            )
                        )
                }

                override fun performAccessibilityAction(
                    host: View,
                    action: Int,
                    args: Bundle?,
                ): Boolean {
                    if (action == AccessibilityNodeInfo.ACTION_CLICK && accessibilityPending) {
                        cancel()
                        return true
                    }
                    if (action == AccessibilityNodeInfo.ACTION_CLICK && accessibilityConsumed) {
                        button.announceForAccessibility(
                            "SOS activation already requested. Read Emergency SOS status."
                        )
                        return true
                    }
                    // Announcements, content refreshes and focus reacquisition on this same
                    // button do not cancel. Actual focus on another control is monitored below.
                    return super.performAccessibilityAction(host, action, args)
                }
            }
        )
        button.setOnKeyListener { _, code, event ->
            if (
                code !in
                    listOf(
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_SPACE,
                    )
            )
                false
            else {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (accessibilityPending) {
                        cancel()
                        cancelKey = true
                    } else if (eligible() && pointer == null && heldKey == null) {
                        cancel()
                        heldKey = code
                        started = SystemClock.uptimeMillis()
                        button.isPressed = true
                        tick.run()
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    if (cancelKey) cancelKey = false else if (heldKey == code) cancel()
                }
                true // no native keyboard click/long-click may bypass protection
            }
        }
    }

    /** Observe actual new focus targets, not speech/content-change/clear-focus notifications. */
    fun monitorFocus(root: ViewGroup) {
        focusRoot = root
        root.viewTreeObserver.addOnGlobalFocusChangeListener(nativeFocus)
        val delegate =
            object : View.AccessibilityDelegate() {
                override fun onRequestSendAccessibilityEvent(
                    host: ViewGroup,
                    child: View,
                    event: AccessibilityEvent,
                ): Boolean {
                    if (
                        event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED &&
                            !containsButton(child) &&
                            (accessibilityPending || accessibilityConsumed)
                    )
                        cancel()
                    return super.onRequestSendAccessibilityEvent(host, child, event)
                }
            }
        root.setAccessibilityDelegate(delegate)
        focusParent = (button.parent as? ViewGroup)?.takeIf { it !== root }
        focusParent?.setAccessibilityDelegate(delegate)
    }

    private fun containsButton(view: View): Boolean =
        view === button ||
            (view is ViewGroup &&
                (0 until view.childCount).any { containsButton(view.getChildAt(it)) })

    fun onWindowTouch(event: MotionEvent) {
        if (!accessibilityPending || event.actionMasked != MotionEvent.ACTION_DOWN) return
        val location = IntArray(2)
        button.getLocationOnScreen(location)
        if (
            event.rawX < location[0] ||
                event.rawX >= location[0] + button.width ||
                event.rawY < location[1] ||
                event.rawY >= location[1] + button.height
        )
            cancel()
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (cancelTouch) {
            if (event.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL))
                cancelTouch = false
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (accessibilityPending) {
                    cancel()
                    cancelTouch = true
                    return true
                }
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
        val announce = accessibilityPending
        handler.removeCallbacks(tick)
        pointer = null
        heldKey = null
        completed = false
        accessibilityPending = false
        accessibilityConsumed = false
        cancelTouch = false
        cancelKey = false
        focusAtArm = null
        button.isPressed = false
        button.parent?.requestDisallowInterceptTouchEvent(false)
        progress.progress = 0
        button.text = "Hold to activate SOS"
        button.contentDescription = idleDescription
        if (Build.VERSION.SDK_INT >= 30) button.stateDescription = null
        if (announce) button.announceForAccessibility("SOS activation cancelled")
    }

    fun dispose() {
        cancel()
        focusRoot?.let {
            if (it.viewTreeObserver.isAlive)
                it.viewTreeObserver.removeOnGlobalFocusChangeListener(nativeFocus)
            it.setAccessibilityDelegate(null)
        }
        focusParent?.setAccessibilityDelegate(null)
        focusRoot = null
        focusParent = null
    }

    companion object {
        const val HOLD_MS = 1200L
    }
}
