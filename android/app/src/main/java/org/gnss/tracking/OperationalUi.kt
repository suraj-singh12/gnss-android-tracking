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
    private val state = ui.label("Checking", TextRole.Pill)
    private val metadata = ui.label("", TextRole.Meta)
    private val displayLabel = label

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(54)
        setPadding(dp(10), dp(7), dp(10), dp(7))
        if (Build.VERSION.SDK_INT >= 28) isScreenReaderFocusable = true
        addView(
            android.widget.ImageView(context).apply {
                setImageDrawable(ui.icon(when (label) {
                    "GNSS" -> R.drawable.ic_health_gnss
                    "Command", "Sync" -> R.drawable.ic_health_transfer
                    "Storage", "Device space" -> R.drawable.ic_health_storage
                    else -> R.drawable.ic_health_trust
                }, 22))
                scaleType = android.widget.ImageView.ScaleType.CENTER
            },
            LayoutParams(dp(29), dp(24)),
        )
        addView(
            ui.column().apply {
                addView(ui.label(displayLabel, TextRole.Label))
                ui.add(this, metadata, 2)
            },
            LayoutParams(0, -2, 1f),
        )
        addView(state, LayoutParams(-2, -2).apply { marginStart = dp(8) })
        // One semantic node avoids reading decorative icons and values twice.
        for (i in 0 until childCount) getChildAt(i).importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        update("Checking")
    }

    fun update(value: String, detail: String = "", tone: Int = R.color.muted) {
        state.text = value
        ui.statusPill(
            state,
            when (tone) {
                R.color.accent -> StatusTone.POSITIVE
                R.color.warning -> StatusTone.WARNING
                else -> StatusTone.NEUTRAL
            },
        )
        metadata.text = detail
        metadata.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        contentDescription =
            listOf(displayLabel, value, detail).filter { it.isNotEmpty() }.joinToString(", ")
    }
}
