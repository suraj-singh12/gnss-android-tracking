package org.gnss.tracking

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Independent, read-only health facts; never presented as actionable controls. */
internal class HealthRow(
    context: Context,
    private val label: String,
    @Suppress("UNUSED_PARAMETER") icon: Int,
) : LinearLayout(context) {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val state =
        TextView(context).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    private val metadata =
        TextView(context).apply {
            textSize = 11f
            gravity = Gravity.END
            setTextColor(context.getColor(R.color.muted))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(42)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        if (Build.VERSION.SDK_INT >= 28) isScreenReaderFocusable = true
        addView(
            TextView(context).apply {
                text = label
                textSize = 13f
                setTextColor(context.getColor(R.color.muted))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LayoutParams(0, -2, 1f),
        )
        addView(
            LinearLayout(context).apply {
                orientation = VERTICAL
                addView(state, LayoutParams(-1, -2))
                addView(metadata, LayoutParams(-1, -2))
            },
            LayoutParams(0, -2, 1.8f),
        )
        update("Checking")
    }

    fun update(value: String, detail: String = "", tone: Int = R.color.muted) {
        state.text = value
        state.setTextColor(context.getColor(if (tone == R.color.muted) R.color.ink else tone))
        metadata.text = detail
        metadata.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        contentDescription =
            listOf(label, value, detail).filter { it.isNotEmpty() }.joinToString(", ")
    }
}
