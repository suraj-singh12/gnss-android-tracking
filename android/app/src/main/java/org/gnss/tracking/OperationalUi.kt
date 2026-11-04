package org.gnss.tracking

import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Independent read-only facts. Values come from the existing operational owners. */
internal class HealthRow(
    context: Context,
    private val label: String,
    @Suppress("UNUSED_PARAMETER") icon: Int,
) : LinearLayout(context) {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val ui = NativeUi(context)
    private val state = ui.label("Checking", 10.5f, bold = true)
    private val metadata = ui.label("", 10.5f, muted = true)
    private val displayLabel =
        when (label) {
            "Command" -> "Command delivery"
            "Storage" -> "Local storage"
            "Remote control" -> "Trusted control"
            else -> label
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(54)
        setPadding(dp(10), dp(7), dp(10), dp(7))
        if (Build.VERSION.SDK_INT >= 28) isScreenReaderFocusable = true
        addView(
            ui.label(
                when (label) {
                    "GNSS" -> "◎"
                    "Command" -> "⇄"
                    "Storage" -> "▤"
                    else -> "◇"
                },
                18f,
                muted = true,
            ),
            LayoutParams(dp(29), -2),
        )
        addView(
            ui.column().apply {
                addView(ui.label(displayLabel, 12f, bold = true))
                addView(metadata)
            },
            LayoutParams(0, -2, 1f),
        )
        addView(state, LayoutParams(-2, -2))
        // One semantic node avoids reading decorative icons and values twice.
        for (i in 0 until childCount) getChildAt(i).importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        update("Checking")
    }

    fun update(value: String, detail: String = "", tone: Int = R.color.muted) {
        state.text = value
        ui.pill(state, tone == R.color.accent)
        state.textSize = 10.5f
        metadata.text = detail
        metadata.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        contentDescription =
            listOf(displayLabel, value, detail).filter { it.isNotEmpty() }.joinToString(", ")
    }
}
