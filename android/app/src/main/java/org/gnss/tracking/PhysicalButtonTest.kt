package org.gnss.tracking

import android.view.KeyEvent

enum class PhysicalButtonAction {
    DOWN,
    UP,
}

enum class PhysicalButtonSource {
    ACTIVITY,
}

// A closed, content-free record. The experiment never reads key characters or app text.
data class PhysicalButtonEvidence(
    val keyCode: Int,
    val keyName: String,
    val action: PhysicalButtonAction,
    val source: PhysicalButtonSource,
    val repeatCount: Int,
    val appForeground: Boolean?,
    val screenInteractive: Boolean?,
    val keyguardLocked: Boolean?,
)

data class PhysicalButtonLoggedEvent(val at: String, val evidence: PhysicalButtonEvidence)

data class PhysicalButtonHistory(
    val startedAt: String?,
    val stoppedAt: String?,
    val totalPresses: Long,
    val retainedEvents: List<PhysicalButtonLoggedEvent>,
    val olderEventsOmitted: Boolean,
    val activityResult: String,
    val activityReason: String,
    val activityWasInterrupted: Boolean,
    val manufacturer: String? = null,
    val model: String? = null,
    val androidVersion: String? = null,
    val androidApi: Int? = null,
    val writerIoFailures: Long = 0,
    val diagnosticQueueDroppedTotal: Long = 0,
)

// Explicit opt-in state is process-local, so it is OFF after process restart. Its only output
// is a typed Diagnostics record; there is no SOS engine or sender dependency in this adapter.
class PhysicalButtonTestController(
    private val record: (DiagnosticEvent, PhysicalButtonEvidence?) -> Unit,
    private val changed: () -> Unit = {},
) {
    @Volatile
    var listening: Boolean = false
        private set

    fun start() {
        if (listening) return
        listening = true
        record(DiagnosticEvent.PHYSICAL_BUTTON_TEST_STARTED, null)
        changed()
    }

    fun stop() {
        if (!listening) return
        listening = false
        record(DiagnosticEvent.PHYSICAL_BUTTON_TEST_STOPPED, null)
        changed()
    }

    fun clearResults() {
        record(DiagnosticEvent.PHYSICAL_BUTTON_TEST_CLEARED, null)
        // Keep active listening truthful while beginning a clean report interval.
        if (listening) record(DiagnosticEvent.PHYSICAL_BUTTON_TEST_STARTED, null)
        changed()
    }

    fun activityPaused() {
        if (!listening) return
        record(DiagnosticEvent.PHYSICAL_BUTTON_ACTIVITY_PAUSED, null)
        // End Activity-only test mode as the Activity leaves foreground so SOS is restored.
        stop()
    }

    fun activityResumed() {
        if (listening) record(DiagnosticEvent.PHYSICAL_BUTTON_ACTIVITY_RESUMED, null)
    }

    fun observeActivityKey(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        appForeground: Boolean?,
        screenInteractive: Boolean?,
        keyguardLocked: Boolean?,
    ): Boolean {
        if (!listening || action !in setOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return false
        val name = physicalButtonKeyName(keyCode) ?: return false
        val evidence =
            PhysicalButtonEvidence(
                keyCode,
                name,
                if (action == KeyEvent.ACTION_DOWN) PhysicalButtonAction.DOWN
                else PhysicalButtonAction.UP,
                PhysicalButtonSource.ACTIVITY,
                repeatCount.coerceIn(0, MAX_KEY_REPEATS),
                appForeground,
                screenInteractive,
                keyguardLocked,
            )
        record(DiagnosticEvent.PHYSICAL_BUTTON_KEY_EVENT, evidence)
        changed()
        return true
    }

    companion object {
        const val MAX_DISPLAY_EVENTS = 200
        const val MAX_KEY_REPEATS = 1000
        const val BACKGROUND_STATUS =
            "MECHANISM UNAVAILABLE — Android does not give this app a passive background volume-key listener."
        const val LOCKED_SCREEN_STATUS =
            "MECHANISM UNAVAILABLE — this Activity-only detector cannot receive keys while locked or screen-off."
        const val MEDIA_SESSION_STATUS =
            "MECHANISM UNAVAILABLE — a MediaSession would route media buttons to this app; no session or audio focus is taken."
    }
}

internal fun physicalButtonKeyName(code: Int): String? =
    when (code) {
        KeyEvent.KEYCODE_VOLUME_UP -> "VOLUME_UP"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "VOLUME_DOWN"
        KeyEvent.KEYCODE_VOLUME_MUTE -> "VOLUME_MUTE"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "MEDIA_PLAY_PAUSE"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "MEDIA_PLAY"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "MEDIA_PAUSE"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "MEDIA_NEXT"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "MEDIA_PREVIOUS"
        KeyEvent.KEYCODE_HEADSETHOOK -> "HEADSET_HOOK"
        KeyEvent.KEYCODE_CAMERA -> "CAMERA"
        KeyEvent.KEYCODE_FOCUS -> "FOCUS"
        KeyEvent.KEYCODE_POWER -> "POWER"
        else -> null
    }
