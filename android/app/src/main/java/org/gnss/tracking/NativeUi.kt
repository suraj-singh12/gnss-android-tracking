package org.gnss.tracking

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.TextWatcher
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
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
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
        button.textSize = 12f
        button.minHeight = dp(48)
        button.minimumWidth = 0
        button.minWidth = 0
        button.includeFontPadding = false
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
                intArrayOf(color(ink), color(R.color.muted)),
            )
        )
        button.backgroundTintList = null
        button.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_enabled),
                RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)), surface(fill, 12), null))
            addState(intArrayOf(), surface(R.color.background, 12))
        }
        button.gravity = Gravity.CENTER
    }

    fun stopStyle(button: Button) {
        style(button)
        button.setTextColor(android.graphics.Color.parseColor("#8C3449"))
        button.background =
            RippleDrawable(
                ColorStateList.valueOf(color(R.color.ripple)),
                GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#F9E9ED"))
                    setStroke(dp(1), android.graphics.Color.parseColor("#E9BEC8"))
                    cornerRadius = dp(11).toFloat()
                },
                null,
            )
    }

    fun menu(parent: LinearLayout, title: String, subtitle: String, action: () -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(14), dp(14))
            minimumHeight = dp(60)
            background =
                RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)), surface(), null)
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $subtitle. Open"
            if (android.os.Build.VERSION.SDK_INT >= 28) isScreenReaderFocusable = true
            addView(
                column().apply {
                    addView(label(title, 12.5f, bold = true))
                    if (subtitle.isNotEmpty()) addView(label(subtitle, 10.5f, muted = true))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            addView(
                ImageView(context).apply {
                    setImageDrawable(icon(R.drawable.ic_chevron_right, 20))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(8) }
            )
            setOnClickListener { action() }
            add(parent, this, 1)
        }

    fun input(editor: EditText) {
        editor.textSize = 14f
        editor.minHeight = dp(48)
        editor.setPadding(dp(11), dp(10), dp(11), dp(10))
        editor.setTextColor(color(R.color.ink))
        editor.setHintTextColor(color(R.color.muted))
        editor.background =
            GradientDrawable().apply {
                setColor(color(R.color.surface))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), color(R.color.control_line))
            }
    }

    fun fact(parent: LinearLayout, caption: String, value: String): TextView {
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(caption, 12f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        val result = label(value, 12.5f).apply { gravity = Gravity.END; setTextIsSelectable(true) }
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

    fun icon(resource: Int, size: Int = 22, tint: Int = R.color.muted): Drawable =
        context.getDrawable(resource)!!.mutate().apply {
            setTint(color(tint))
            setBounds(0, 0, dp(size), dp(size))
        }

    /** Inline artwork is measured with the label, so a full-width action stays optically centred. */
    fun centeredActionIcon(button: Button, resource: Int) {
        button.setCompoundDrawables(icon(resource, 20).apply { setTint(button.currentTextColor) }, null, null, null)
        button.compoundDrawablePadding = dp(6)
        fun centre() {
            val artwork = button.compoundDrawables[0] ?: return
            val group = artwork.bounds.width() + button.compoundDrawablePadding + button.paint.measureText(button.text.toString())
            val inset = maxOf(dp(12), ((button.width - group) / 2).toInt())
            if (button.paddingLeft != inset || button.paddingRight != inset)
                button.setPadding(inset, dp(8), inset, dp(8))
        }
        if (button.getTag(R.id.centered_action_icon) == null) {
            button.setTag(R.id.centered_action_icon, true)
            button.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> centre() }
        }
        centre()
    }

    fun selector(view: Spinner) {
        view.minimumHeight = dp(48)
        view.setPadding(dp(10), dp(4), dp(38), dp(4))
        view.backgroundTintList = null
        view.background = LayerDrawable(arrayOf(surface(radius = 10), icon(R.drawable.ic_chevron_down, 20))).apply {
            setLayerSize(1, dp(20), dp(20))
            setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL)
            setLayerInset(1, 0, 0, dp(12), 0)
        }
        view.setPopupBackgroundDrawable(surface(radius = 10))
    }

    fun metric(parent: LinearLayout, value: TextView, caption: String) {
        (value.parent as? ViewGroup)?.removeView(value)
        value.gravity = Gravity.START
        value.setTextColor(color(R.color.ink))
        value.setTypeface(null, Typeface.BOLD)
        value.includeFontPadding = false
        value.textSize = 26f
        value.maxLines = 1
        value.setAutoSizeTextTypeUniformWithConfiguration(14, 26, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        val column = column()
        // Reparenting a value from fact() must discard its old zero-width weighted params.
        column.addView(value, LinearLayout.LayoutParams(-1, -2))
        add(column, label(caption, 10.5f, muted = true), 4)
        parent.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
    }

    fun feedback(view: TextView) {
        view.textSize = 11.5f
        view.includeFontPadding = false
        view.setPadding(dp(2), dp(6), dp(2), dp(4))
        view.setTextColor(color(R.color.muted))
        view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun quiet(button: Button) {
        style(button)
        button.background = RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)),
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT), null)
    }

    /** Native actions retain their handlers and IDs; only typography/emphasis is shared. */
    fun dialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(surface(), dp(16)))
        val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
        dialog.findViewById<TextView>(titleId)?.apply {
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(color(R.color.ink))
        }
        for (id in listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE)) {
            val button = dialog.getButton(id) ?: continue
            if (id == AlertDialog.BUTTON_POSITIVE && dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility == View.VISIBLE) {
                val caution = button.text.toString() in listOf("Disable", "Stop Tracking", "Stop", "Retry")
                style(button, primary = !caution, danger = caution)
            } else quiet(button)
            button.textSize = 13f
            button.isAllCaps = false
        }
        dialog.listView?.setPadding(0, 0, 0, 0)
    }

    fun dialogContent(content: LinearLayout, maxHeightDp: Int? = null): ScrollView = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = minOf((resources.displayMetrics.heightPixels - dp(192)).coerceAtLeast(dp(64)), maxHeightDp?.let(::dp) ?: Int.MAX_VALUE)
            val bound = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) available
                else minOf(available, View.MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(bound, View.MeasureSpec.AT_MOST))
        }
    }.apply { addView(content, ViewGroup.LayoutParams(-1, -2)) }

    /** Read-only presentation adapter over the existing bound text, not another state owner. */
    fun evidence(source: TextView, separateGroups: Boolean = true): LinearLayout {
        val host = column()
        val cells = mutableListOf<Pair<TextView, String>>()
        var previous = ""
        fun render() {
            val text = source.text.toString()
            if (text == previous && host.childCount > 1) return
            previous = text
            val groups = text.trim().split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
            // Keep an unchanged selected value/focus intact while unrelated facts refresh.
            val lines = groups.flatMap { it.lines().filter(String::isNotBlank) }
            val headings = setOf("Authorized Command ID", "This phone ID", "Saved Command address")
            val signatures = lines.map { line ->
                val split = Regex("^[A-Za-z][^:]{0,43}:(?:\\s|$)").find(line)?.let { line.indexOf(':') }
                    ?: line.indexOf(" · ").takeIf { it in 1..44 }
                if (split == null) line.takeIf { it in headings }.orEmpty() else line.substring(0, split)
            }
            if (cells.size == lines.size && cells.map { it.second } == signatures) {
                lines.forEachIndexed { i, line ->
                    val key = signatures[i]
                    val value = if (key.isEmpty()) line else line.substring(key.length).removePrefix(":").removePrefix(" · ").trim()
                    if (cells[i].first.text.toString() != value) cells[i].first.text = value
                    cells[i].first.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
                }
                return
            }
            host.removeAllViews()
            host.addView(source, LinearLayout.LayoutParams(0, 0))
            cells.clear()
            var index = 0
            var sharedPanel: LinearLayout? = null
            for (group in groups) {
                val panel = if (separateGroups || sharedPanel == null)
                    card(host, if (host.childCount == 1) 0 else 12).also { sharedPanel = it }
                    else sharedPanel!!
                for ((n, line) in group.lines().filter(String::isNotBlank).withIndex()) {
                    val key = signatures[index++]
                    if (key.isNotEmpty()) add(panel, label(key, 11f, muted = true, bold = true), if (n == 0 && index == 1) 0 else 10)
                    val value = if (key.isEmpty()) line else line.substring(key.length).removePrefix(":").removePrefix(" · ").trim()
                    val view = label(value, 12.5f).apply {
                        setTextIsSelectable(true)
                        setLineSpacing(dp(2).toFloat(), 1f)
                        visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
                    }
                    add(panel, view, if (key.isEmpty() && n > 0) 7 else 3)
                    cells.add(view to key)
                }
            }
        }
        (source.parent as? ViewGroup)?.removeView(source)
        source.visibility = View.GONE
        source.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        host.addView(source, LinearLayout.LayoutParams(0, 0))
        source.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: Editable?) {}
        })
        render()
        return host
    }
}
