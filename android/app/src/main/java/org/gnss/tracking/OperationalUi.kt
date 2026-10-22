package org.gnss.tracking

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Read-only health information. Actions live in the disclosures below the overview. */
internal class HealthRow(context: Context, private val label: String, icon: Int) :
    LinearLayout(context) {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val state =
        TextView(context).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxWidth = dp(160)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    private val metadata =
        TextView(context).apply {
            textSize = 13f
            setTextColor(context.getColor(R.color.muted))
            setPadding(dp(30), dp(3), 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    init {
        orientation = VERTICAL
        minimumHeight = dp(62)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        if (Build.VERSION.SDK_INT >= 28) isScreenReaderFocusable = true
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    ImageView(context).apply {
                        setImageResource(icon)
                        imageTintList = ColorStateList.valueOf(context.getColor(R.color.muted))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(12) },
                )
                addView(
                    TextView(context).apply {
                        text = label
                        textSize = 16f
                        setTextColor(context.getColor(R.color.ink))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LayoutParams(0, -2, 1f),
                )
                addView(state, LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            },
            LayoutParams(-1, -2),
        )
        addView(metadata, LayoutParams(-1, -2))
        update("Checking", "", R.color.muted)
    }

    fun update(value: String, detail: String = "", tone: Int = R.color.muted) {
        state.text = value
        val colour = context.getColor(tone)
        state.setTextColor(colour)
        state.background =
            GradientDrawable().apply {
                setColor((colour and 0x00ffffff) or 0x16000000)
                cornerRadius = dp(12).toFloat()
            }
        metadata.text = detail
        metadata.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        contentDescription =
            listOf(label, value, detail).filter { it.isNotEmpty() }.joinToString(", ")
    }
}
