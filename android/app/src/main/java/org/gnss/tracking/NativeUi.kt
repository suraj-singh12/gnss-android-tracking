package org.gnss.tracking

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Native view vocabulary shared by the reference screens. No operational state is owned here. */
internal class NativeUi(private val context: Context) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun color(id: Int) = context.getColor(id)

    fun surface(tone: Int = R.color.surface, radius: Int = 16) =
        GradientDrawable().apply {
            setColor(color(tone))
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), color(R.color.line))
        }

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun label(value: String, size: Float = 13f, muted: Boolean = false, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color(if (muted) R.color.muted else R.color.ink))
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
        }

    fun add(parent: LinearLayout, view: View, gap: Int = 0) {
        (view.parent as? ViewGroup)?.removeView(view)
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) })
    }

    fun card(parent: LinearLayout, gap: Int = 12, tone: Int = R.color.surface) =
        column().apply {
            background = surface(tone)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            add(parent, this, gap)
        }

    fun button(value: String, action: () -> Unit) =
        Button(context).apply {
            text = value
            style(this)
            setOnClickListener { action() }
        }

    fun style(button: Button, primary: Boolean = false, danger: Boolean = false) {
        button.isAllCaps = false
        button.textSize = 14f
        button.minHeight = dp(48)
        button.minimumWidth = 0
        button.setPadding(dp(12), dp(8), dp(12), dp(8))
        val fill =
            if (primary) R.color.action
            else if (danger) R.color.emergency_surface else R.color.secondary_surface
        val ink =
            if (primary) android.R.color.white
            else if (danger) R.color.danger else R.color.secondary
        button.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(color(ink), color(R.color.disabled_ink)),
            )
        )
        button.backgroundTintList = null
        button.background =
            RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)), surface(fill, 12), null)
        button.gravity = Gravity.CENTER
    }

    fun menu(parent: LinearLayout, title: String, subtitle: String, action: () -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(14), dp(14))
            minimumHeight = dp(80)
            background =
                RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)), surface(), null)
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $subtitle. Open"
            if (android.os.Build.VERSION.SDK_INT >= 28) isScreenReaderFocusable = true
            addView(
                column().apply {
                    addView(label(title, 16f, bold = true))
                    if (subtitle.isNotEmpty()) addView(label(subtitle, 12f, muted = true))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            addView(
                label("›", 24f, muted = true).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            )
            setOnClickListener { action() }
            add(parent, this, 12)
        }

    fun fact(parent: LinearLayout, caption: String, value: String): TextView {
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(caption, 13f, muted = true), LinearLayout.LayoutParams(0, -2, 1f))
        val result = label(value, 14f, bold = true).apply { gravity = Gravity.END }
        row.addView(result, LinearLayout.LayoutParams(0, -2, 1f))
        add(parent, row, 4)
        return result
    }

    fun pill(view: TextView, positive: Boolean = false) {
        view.textSize = 12f
        view.setPadding(dp(10), dp(6), dp(10), dp(6))
        view.setTextColor(color(if (positive) R.color.accent else R.color.warning))
        view.background =
            surface(if (positive) R.color.positive_surface else R.color.warning_surface, 14)
    }
}
