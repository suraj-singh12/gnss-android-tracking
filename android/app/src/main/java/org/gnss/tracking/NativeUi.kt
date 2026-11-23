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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/**
 * Typography role: one scalable size token, one weight and one semantic ink.
 * Sizes live in `res/values/dimens.xml`; change a token there to restyle every use.
 */
internal enum class TextRole(val size: Int, val weight: Int, val muted: Boolean = false, val mono: Boolean = false) {
    Display(R.dimen.text_display, TextWeight.BOLD),
    DialogTitle(R.dimen.text_dialog_title, TextWeight.MEDIUM),
    AppBarTitle(R.dimen.text_appbar_title, TextWeight.BOLD),
    AppBarSubtitle(R.dimen.text_micro, TextWeight.REGULAR, muted = true),
    Hero(R.dimen.text_hero, TextWeight.BOLD),
    Heading(R.dimen.text_heading, TextWeight.BOLD),
    Body(R.dimen.text_body, TextWeight.REGULAR),
    Input(R.dimen.text_input, TextWeight.REGULAR),
    Button(R.dimen.text_button, TextWeight.MEDIUM),
    RowTitle(R.dimen.text_row_title, TextWeight.BOLD),
    Value(R.dimen.text_value, TextWeight.REGULAR),
    Label(R.dimen.text_label, TextWeight.BOLD),
    Caption(R.dimen.text_caption, TextWeight.REGULAR, muted = true),
    FieldLabel(R.dimen.text_small, TextWeight.BOLD),
    Meta(R.dimen.text_meta, TextWeight.REGULAR, muted = true),
    Pill(R.dimen.text_meta, TextWeight.BOLD),
    Section(R.dimen.text_meta, TextWeight.BOLD, muted = true),
    Micro(R.dimen.text_micro, TextWeight.REGULAR, muted = true),
    Metric(R.dimen.text_metric, TextWeight.BOLD),
    Mono(R.dimen.text_mono, TextWeight.REGULAR, mono = true),
}

/** TextView that measures to zero height while it has no text. */
internal class CollapsingTextView(context: Context) : TextView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (text.isNullOrEmpty()) setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), 0)
        else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

internal enum class StatusTone { POSITIVE, WARNING, NEUTRAL }

/**
 * The SOS hold progress owner. SosHoldGesture writes `progress` exactly as before; V4.6 draws
 * it as a fill inside the SOS button (V4.6 `.sos-progress`) instead of a separate track.
 */
internal class HoldProgressBar(context: Context) :
    ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal) {
    var onProgress: ((Int) -> Unit)? = null
    @Synchronized
    override fun setProgress(progress: Int) {
        super.setProgress(progress)
        onProgress?.invoke(getProgress())
    }
}

internal object TextWeight {
    const val REGULAR = 0
    const val MEDIUM = 1
    const val BOLD = 2
}

/**
 * The Android design system: shared tokens and native view vocabulary for every screen.
 * No operational state is owned here. See android/UI-DEVELOPMENT.md.
 */
internal class NativeUi(private val context: Context) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /** Pixel value of a dimension token. */
    fun px(token: Int) = context.resources.getDimensionPixelSize(token)

    fun color(id: Int) = context.getColor(id)

    /** Shared rounded surface. [radius] is in dp; prefer [shape] with a radius token. */
    fun surface(tone: Int = R.color.surface, radius: Int = 16) =
        GradientDrawable().apply {
            setColor(color(tone))
            cornerRadius = dp(radius).toFloat()
            setStroke(px(R.dimen.stroke), color(R.color.line))
        }

    /** Token-based surface: fill, radius token and optional semantic stroke (null = borderless). */
    fun shape(tone: Int, radius: Int = R.dimen.radius_card, stroke: Int? = R.color.line) =
        GradientDrawable().apply {
            setColor(color(tone))
            cornerRadius = px(radius).toFloat()
            stroke?.let { setStroke(px(R.dimen.stroke), color(it)) }
        }

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun text(view: TextView, role: TextRole, tone: Int? = null) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(role.size))
        view.typeface = when {
            role.mono -> Typeface.MONOSPACE
            role.weight == TextWeight.BOLD -> Typeface.DEFAULT_BOLD
            role.weight == TextWeight.MEDIUM -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
        view.setTextColor(color(tone ?: if (role.muted) R.color.muted else R.color.ink))
    }

    fun label(value: String, role: TextRole, tone: Int? = null) =
        TextView(context).apply {
            text = value
            text(this, role, tone)
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
        }

    /** Legacy size-based call sites snap to the nearest V4.6 type token. Prefer [TextRole]. */
    fun label(value: String, size: Float = 13f, muted: Boolean = false, bold: Boolean = false) =
        label(value, legacyRole(size, muted, bold))

    private fun legacyRole(size: Float, muted: Boolean, bold: Boolean): TextRole =
        when {
            size >= 21f -> TextRole.Display
            size >= 16f -> if (bold) TextRole.AppBarTitle else TextRole.Body
            size >= 15f -> if (bold) TextRole.Hero else TextRole.Body
            size >= 13.5f -> if (bold) TextRole.Heading else TextRole.Body
            size >= 12.25f -> if (bold) TextRole.RowTitle else if (muted) TextRole.Caption else TextRole.Value
            size >= 11.75f -> if (bold) TextRole.Label else if (muted) TextRole.Caption else TextRole.Value
            size >= 11.25f -> if (bold) TextRole.Label else TextRole.Caption
            size >= 10.75f -> if (bold) (if (muted) TextRole.Section else TextRole.FieldLabel) else TextRole.Caption
            size >= 10.25f -> if (bold) TextRole.Pill else TextRole.Meta
            else -> TextRole.Micro
        }.let { role ->
            // Preserve an explicit muted/ink request where the role's default differs.
            if (muted && !role.muted && role == TextRole.Label) TextRole.Section else role
        }

    fun add(parent: LinearLayout, view: View, gap: Int = 0) {
        (view.parent as? ViewGroup)?.removeView(view)
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) })
    }

    fun card(parent: LinearLayout, gap: Int = 12, tone: Int = R.color.surface) =
        column().apply {
            background = shape(tone)
            val pad = px(R.dimen.card_padding)
            setPadding(pad, pad, pad, pad)
            add(parent, this, gap)
        }

    /** Uppercase section heading above a group of peer cards or rows. */
    fun section(parent: LinearLayout, title: String) =
        label(title.uppercase(), TextRole.Section).apply {
            letterSpacing = 0.06f
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = px(R.dimen.section_top)
                bottomMargin = px(R.dimen.section_bottom) - dp(1)
                marginStart = dp(2)
            })
        }

    /** Section header with one trailing text action (for example Copy); the action keeps a 48dp target. */
    fun section(parent: LinearLayout, title: String, action: String, description: String, onAction: () -> Unit): Button {
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(R.dimen.space_sm) })
        section(row, title).layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2) }
        return Button(context).apply {
            text = action
            quiet(this)
            contentDescription = description
            setOnClickListener { onAction() }
            row.addView(this, LinearLayout.LayoutParams(-2, -2))
        }
    }

    fun button(value: String, action: () -> Unit) =
        Button(context).apply {
            text = value
            style(this)
            setOnClickListener { action() }
        }

    private fun buttonBase(button: Button) {
        button.isAllCaps = false
        text(button, TextRole.Button)
        button.minHeight = px(R.dimen.control_height)
        button.minimumHeight = px(R.dimen.control_height)
        button.minimumWidth = 0
        button.minWidth = 0
        button.includeFontPadding = false
        button.stateListAnimator = null
        button.elevation = 0f
        button.setPadding(px(R.dimen.button_padding_h), px(R.dimen.button_padding_v), px(R.dimen.button_padding_h), px(R.dimen.button_padding_v))
        button.gravity = Gravity.CENTER
        button.backgroundTintList = null
    }

    private fun inks(enabled: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(color(enabled), color(R.color.disabled_ink)),
    )

    /** Shared action variants: secondary (default), primary (filled) and danger (caution). */
    fun style(button: Button, primary: Boolean = false, danger: Boolean = false) {
        buttonBase(button)
        val fill =
            if (primary) R.color.action
            else if (danger) R.color.emergency_surface else R.color.secondary_surface
        val ink =
            if (primary) android.R.color.white
            else if (danger) R.color.danger else R.color.secondary
        button.setTextColor(inks(ink))
        button.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_enabled),
                RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)),
                    shape(fill, R.dimen.radius_button, if (primary) R.color.action else R.color.line), null))
            addState(intArrayOf(), shape(R.color.disabled_surface, R.dimen.radius_button, R.color.disabled_line))
        }
    }

    /** Approved pale-red Stop Tracking treatment, identical in Day and Night. */
    fun stopStyle(button: Button) {
        buttonBase(button)
        button.setTextColor(inks(R.color.stop_ink))
        button.background =
            RippleDrawable(
                ColorStateList.valueOf(color(R.color.ripple)),
                shape(R.color.stop_surface, R.dimen.radius_button, R.color.stop_line),
                null,
            )
    }

    /** Text action without a container: Cancel, Close and inline secondary links. */
    fun quiet(button: Button) {
        buttonBase(button)
        button.setTextColor(inks(R.color.secondary))
        button.setPadding(px(R.dimen.space_md), button.paddingTop, px(R.dimen.space_md), button.paddingBottom)
        button.background = RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)),
            null, shape(R.color.surface, R.dimen.radius_button, null))
    }

    /** Borderless 48dp icon action used by the app bar. */
    fun iconButton(button: Button, resource: Int, description: String) {
        buttonBase(button)
        button.text = ""
        button.contentDescription = description
        button.setPadding(0, 0, 0, 0)
        button.setCompoundDrawables(icon(resource, 24, R.color.ink), null, null, null)
        button.background = RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)),
            null, shape(R.color.surface, R.dimen.radius_button, null))
        val target = px(R.dimen.control_height)
        button.minWidth = target
        button.minimumWidth = target
        // Centre the single compound drawable inside the fixed square target.
        button.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val b = v as Button
            val inset = ((b.width - (b.compoundDrawables[0]?.bounds?.width() ?: 0)) / 2).coerceAtLeast(0)
            if (b.paddingLeft != inset) b.setPadding(inset, 0, 0, 0)
        }
    }

    /** Pressed-state ripple for a row inside a list surface (bounded by the row). */
    fun rowRipple() = RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)), null,
        android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))

    /**
     * Bordered list surface: one rounded group, rows separated by hairlines inset to the
     * row text ([inset] adds the width of a leading icon). Rows draw no border of their own.
     */
    fun list(parent: LinearLayout, gap: Int = 0, inset: Int = 0): LinearLayout =
        column().apply {
            tag = LIST_TAG
            background = shape(R.color.surface)
            clipToOutline = true
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = android.graphics.drawable.InsetDrawable(
                GradientDrawable().apply { setColor(color(R.color.line)); setSize(1, px(R.dimen.stroke)) },
                px(R.dimen.list_row_padding_h) + inset, 0, 0, 0,
            )
            add(parent, this, gap)
        }

    /** Width a [menu] icon tile adds before the row text, for [list] divider insets. */
    fun iconInset() = px(R.dimen.icon_tile) + px(R.dimen.space_md)

    /** Rounded tinted tile holding a row's leading icon. */
    fun iconTile(resource: Int, surface: Int, ink: Int): View =
        FrameLayout(context).apply {
            background = shape(surface, R.dimen.radius_button, null)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(ImageView(context).apply { setImageDrawable(icon(resource, 20, ink)) },
                FrameLayout.LayoutParams(px(R.dimen.icon_sm), px(R.dimen.icon_sm), Gravity.CENTER))
        }

    /** Peer actions in one horizontal row share the tallest action's height (large text wraps). */
    fun equalHeights(row: LinearLayout) {
        row.gravity = Gravity.CENTER_VERTICAL
        for (i in 0 until row.childCount) row.getChildAt(i).layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
    }

    /** One navigation row: title, optional subtitle and a trailing right chevron. */
    fun menu(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        status: Pair<String, StatusTone>? = null,
        value: String? = null,
        icon: Triple<Int, Int, Int>? = null,
        action: () -> Unit,
    ) =
        LinearLayout(context).apply {
            val grouped = parent.tag == LIST_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(R.dimen.list_row_padding_h), px(R.dimen.list_row_padding_v), px(R.dimen.list_row_padding_h), px(R.dimen.list_row_padding_v))
            minimumHeight = px(R.dimen.list_row_min_height)
            // Inside a list the group owns the surface and border; a row only draws its press state.
            background =
                if (grouped) rowRipple()
                else RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)), shape(R.color.surface), null)
            icon?.let { (resource, surface, ink) ->
                addView(iconTile(resource, surface, ink), LinearLayout.LayoutParams(px(R.dimen.icon_tile), px(R.dimen.icon_tile)).apply { marginEnd = px(R.dimen.space_md) })
            }
            isClickable = true
            isFocusable = true
            contentDescription = listOfNotNull(title, subtitle.ifEmpty { null }, value, status?.first).joinToString(". ") + ". Open"
            if (android.os.Build.VERSION.SDK_INT >= 28) isScreenReaderFocusable = true
            addView(
                column().apply {
                    addView(label(title, TextRole.RowTitle))
                    if (subtitle.isNotEmpty()) add(this, label(subtitle, TextRole.Meta).apply { setLineSpacing(dp(1).toFloat(), 1f) }, 3)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            value?.let {
                addView(label(it, TextRole.Value, R.color.muted).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    // A long value (e.g. party name) never takes the title's width.
                    maxWidth = (context.resources.displayMetrics.widthPixels * .38).toInt()
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(R.dimen.space_sm) })
            }
            status?.let { (statusText, tone) ->
                addView(label(statusText, TextRole.Pill).apply {
                    statusPill(this, tone)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(R.dimen.space_sm) })
            }
            addView(
                ImageView(context).apply {
                    setImageDrawable(icon(R.drawable.ic_chevron_right, 20))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(px(R.dimen.icon_md), px(R.dimen.icon_md)).apply { leftMargin = px(R.dimen.space_sm) }
            )
            setOnClickListener { action() }
            add(parent, this, if (grouped) 0 else 1)
        }

    /** Draws a field outline inset vertically so a 48dp target looks like a 44dp field. */
    fun fieldOutline(d: Drawable): Drawable =
        android.graphics.drawable.InsetDrawable(d, 0, px(R.dimen.field_outline_inset), 0, px(R.dimen.field_outline_inset))

    /** Gap above [view] in px (for token-based spacing). */
    fun addPx(parent: LinearLayout, view: View, gapPx: Int) {
        (view.parent as? ViewGroup)?.removeView(view)
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gapPx })
    }

    /** Editable form field: FieldLabel caption, shared input, token spacing. */
    fun formField(parent: LinearLayout, title: String, editor: View): TextView {
        val caption = label(title, TextRole.FieldLabel).apply { labelFor = editor.id }
        addPx(parent, caption, if (parent.childCount == 0) 0 else px(R.dimen.field_gap) + dp(2))
        addPx(parent, editor, px(R.dimen.field_label_gap))
        if (editor is EditText) input(editor)
        return caption
    }

    /** Binary setting: label at the start, native Switch at the end, 48dp row. */
    fun switchRow(control: Switch) {
        text(control, TextRole.RowTitle)
        control.includeFontPadding = false
        control.minHeight = px(R.dimen.control_height)
        control.minimumHeight = px(R.dimen.control_height)
        control.setPadding(0, 0, 0, 0)
        control.switchPadding = px(R.dimen.space_md)
    }

    /** Shared outlined input with focused, error-free and disabled states. */
    fun input(editor: EditText) {
        text(editor, TextRole.Input)
        // Password input types otherwise switch the hint and value to monospace.
        editor.typeface = Typeface.DEFAULT
        editor.minHeight = px(R.dimen.field_height)
        editor.minimumHeight = px(R.dimen.field_height)
        editor.includeFontPadding = false
        editor.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        editor.setPadding(px(R.dimen.input_padding_h), px(R.dimen.input_padding_v), px(R.dimen.input_padding_h), px(R.dimen.input_padding_v))
        editor.setTextColor(inks(R.color.ink))
        editor.setHintTextColor(color(R.color.muted))
        editor.backgroundTintList = null
        editor.background = fieldOutline(StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(R.color.disabled_surface, R.dimen.radius_input, R.color.disabled_line))
            addState(intArrayOf(android.R.attr.state_focused), shape(R.color.surface, R.dimen.radius_input, R.color.accent).apply {
                setStroke(px(R.dimen.focus_stroke), color(R.color.accent))
            })
            addState(intArrayOf(), shape(R.color.surface, R.dimen.radius_input, R.color.control_line))
        })
        // A background replaces view padding with the drawable's own; set padding last.
        editor.setPadding(px(R.dimen.input_padding_h), px(R.dimen.input_padding_v), px(R.dimen.input_padding_h), px(R.dimen.input_padding_v))
    }

    /** Read-only label/value row. The value is selectable evidence. */
    fun fact(parent: LinearLayout, caption: String, value: String): TextView {
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        // Short labels keep their width; the value takes the rest and wraps at the end edge.
        row.addView(label(caption, TextRole.Label), LinearLayout.LayoutParams(-2, -2))
        val result = label(value, TextRole.Value).apply { gravity = Gravity.END; setTextIsSelectable(true) }
        row.addView(result, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(R.dimen.space_md) })
        add(parent, row, if (parent.childCount == 0) 0 else 6)
        return result
    }

    /** Borderless status pill; teal for positive, amber for degraded/unknown. */
    fun pill(view: TextView, positive: Boolean = false) {
        text(view, TextRole.Pill, if (positive) R.color.accent else R.color.warning)
        view.includeFontPadding = false
        view.setPadding(px(R.dimen.pill_padding_h), px(R.dimen.pill_padding_v), px(R.dimen.pill_padding_h), px(R.dimen.pill_padding_v))
        view.background =
            shape(if (positive) R.color.positive_surface else R.color.warning_surface, R.dimen.radius_pill, null)
    }

    /** Status pill with a leading icon tinted like its label (e.g. Command online/offline). */
    fun iconPill(view: TextView, resource: Int, positive: Boolean) {
        pill(view, positive)
        val size = px(R.dimen.icon_pill)
        view.setCompoundDrawablesRelative(
            context.getDrawable(resource)!!.mutate().apply {
                setTint(color(if (positive) R.color.accent else R.color.warning))
                setBounds(0, 0, size, size)
            },
            null, null, null,
        )
        view.compoundDrawablePadding = dp(4)
        view.gravity = Gravity.CENTER_VERTICAL
    }

    /** Status pill with an explicit tone; NEUTRAL is a quiet informational state. */
    fun statusPill(view: TextView, tone: StatusTone) {
        when (tone) {
            StatusTone.POSITIVE -> pill(view, true)
            StatusTone.WARNING -> pill(view, false)
            StatusTone.NEUTRAL -> {
                pill(view, false)
                view.setTextColor(color(R.color.muted))
                view.background = shape(R.color.background, R.dimen.radius_pill, null)
            }
        }
    }

    fun icon(resource: Int, size: Int = 22, tint: Int = R.color.muted): Drawable =
        context.getDrawable(resource)!!.mutate().apply {
            setTint(color(tint))
            setBounds(0, 0, dp(size), dp(size))
        }

    /** Inline artwork is measured with the label, so a full-width action stays optically centred. */
    fun centeredActionIcon(button: Button, resource: Int) {
        button.setCompoundDrawables(icon(resource, 20).apply { setTint(button.currentTextColor) }, null, null, null)
        button.compoundDrawablePadding = px(R.dimen.icon_gap)
        fun centre() {
            val artwork = button.compoundDrawables[0] ?: return
            val group = artwork.bounds.width() + button.compoundDrawablePadding + button.paint.measureText(button.text.toString())
            val inset = maxOf(px(R.dimen.space_md), ((button.width - group) / 2).toInt())
            if (button.paddingLeft != inset || button.paddingRight != inset)
                button.setPadding(inset, px(R.dimen.button_padding_v), inset, px(R.dimen.button_padding_v))
        }
        if (button.getTag(R.id.centered_action_icon) == null) {
            button.setTag(R.id.centered_action_icon, true)
            button.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> centre() }
        }
        centre()
    }

    /** Native Spinner as an outlined selector with one down-chevron at a fixed end inset. */
    fun selector(view: Spinner) {
        view.minimumHeight = px(R.dimen.field_height)
        view.setPadding(dp(2), 0, px(R.dimen.selector_end_padding), 0)
        view.backgroundTintList = null
        val outline = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(R.color.disabled_surface, R.dimen.radius_input, R.color.disabled_line))
            addState(intArrayOf(), shape(R.color.surface, R.dimen.radius_input, R.color.control_line))
        }
        view.background = LayerDrawable(arrayOf(fieldOutline(outline), icon(R.drawable.ic_chevron_down, 20))).apply {
            setLayerSize(1, px(R.dimen.icon_sm), px(R.dimen.icon_sm))
            setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL)
            setLayerInset(1, 0, 0, px(R.dimen.selector_chevron_inset), 0)
        }
        view.setPopupBackgroundDrawable(shape(R.color.surface, R.dimen.radius_input))
    }

    /** Text adapter for selectors: Body type at the input inset in both the field and popup. */
    fun <T> selectorAdapter(items: List<T>): ArrayAdapter<T> =
        object : ArrayAdapter<T>(context, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                selectorItem(super.getView(position, convertView, parent), false)
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup) =
                selectorItem(super.getDropDownView(position, convertView, parent), true)
        }

    /** Choice list for an app-owned dialog: Body type, dialog inset, 48dp rows. */
    fun dialogListAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                super.getView(position, convertView, parent).apply {
                    (this as TextView).let {
                        text(it, TextRole.Body)
                        it.minHeight = px(R.dimen.control_height)
                        it.gravity = Gravity.CENTER_VERTICAL
                        it.setPadding(px(R.dimen.dialog_padding), 0, px(R.dimen.dialog_padding), 0)
                    }
                }
        }

    /** Shared selector row typography for the closed field and its popup choices. */
    fun selectorItem(view: View, popup: Boolean): View = view.apply {
        (this as? TextView)?.let {
            text(it, TextRole.Input)
            it.includeFontPadding = false
            // Closed field matches text inputs; popup choices keep full 48dp rows.
            it.minHeight = px(if (popup) R.dimen.control_height else R.dimen.field_height)
            it.gravity = Gravity.CENTER_VERTICAL
            it.setPadding(px(R.dimen.input_padding_h) - if (popup) 0 else dp(2), 0, px(R.dimen.input_padding_h), 0)
            it.maxLines = 1
            it.ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }

    fun metric(parent: LinearLayout, value: TextView, caption: String) {
        (value.parent as? ViewGroup)?.removeView(value)
        value.gravity = Gravity.START
        text(value, TextRole.Metric)
        value.background = null
        value.setPadding(0, 0, 0, 0)
        value.includeFontPadding = false
        value.maxLines = 1
        value.setAutoSizeTextTypeUniformWithConfiguration(14, 26, 1, TypedValue.COMPLEX_UNIT_SP)
        val column = column()
        // Reparenting a value from fact() must discard its old zero-width weighted params.
        column.addView(value, LinearLayout.LayoutParams(-1, -2))
        add(column, label(caption, TextRole.Meta), 4)
        parent.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
    }

    fun feedback(view: TextView) {
        text(view, TextRole.Caption)
        view.includeFontPadding = false
        view.setPadding(dp(2), dp(6), dp(2), dp(4))
        view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /**
     * Status/error text that occupies no height while empty, so an idle message slot never
     * opens a blank gap. Its padding provides the gap once text appears. Text is unchanged.
     */
    fun collapsingLabel(role: TextRole, tone: Int? = null, gap: Int = 6): TextView =
        CollapsingTextView(context).apply {
            text(this, role, tone)
            includeFontPadding = false
            setPadding(0, dp(gap), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

    /** Native actions retain their handlers and IDs; only typography/emphasis is shared. */
    fun dialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(
            shape(R.color.surface, R.dimen.radius_dialog), px(R.dimen.dialog_inset)))
        val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
        dialog.findViewById<TextView>(titleId)?.let { text(it, TextRole.DialogTitle) }
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            text(this, TextRole.Body)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        var panel: ViewGroup? = null
        for (id in listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE)) {
            val button = dialog.getButton(id) ?: continue
            if (id == AlertDialog.BUTTON_POSITIVE && dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility == View.VISIBLE) {
                val caution = button.text.toString() in CAUTION_ACTIONS
                // The confirmation repeats the approved Stop Tracking treatment in both themes.
                if (button.text.toString() == "Stop Tracking") stopStyle(button)
                else style(button, primary = !caution, danger = caution)
            } else quiet(button)
            button.minWidth = dp(64)
            button.minimumWidth = dp(64)
            (button.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.marginStart = px(R.dimen.space_xs)
                button.layoutParams = it
            }
            panel = button.parent as? ViewGroup
        }
        // The framework bar ends flush with the window; keep filled actions off the edge.
        panel?.let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, maxOf(it.paddingBottom, px(R.dimen.space_md))) }
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
                    // The first caption of every panel starts at the card padding edge.
                    if (key.isNotEmpty()) add(panel, label(key, TextRole.Section), if (panel.childCount == 0) 0 else 10)
                    val value = if (key.isEmpty()) line else line.substring(key.length).removePrefix(":").removePrefix(" · ").trim()
                    val view = label(value, TextRole.Value).apply {
                        setTextIsSelectable(true)
                        setLineSpacing(dp(2).toFloat(), 1f)
                        visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
                    }
                    add(panel, view, if (panel.childCount == 0) 0 else if (key.isEmpty() && n > 0) 7 else 3)
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

    /**
     * Grouped label/value rows over existing bound text (read-only adapter, not a state owner).
     * Each line becomes one row; [sectionOf] names the section it belongs to. "Key: value",
     * "Key · value" and "Key • value" split into a title and value; a long value becomes the
     * row's secondary line, and an upper-case result word (e.g. DETECTED) becomes a status pill.
     */
    fun facts(source: TextView, order: List<String> = emptyList(), sectionOf: (key: String, line: String) -> String): LinearLayout {
        val host = column()
        val cells = mutableListOf<Triple<TextView, TextView, TextView>>()
        var signature = emptyList<String>()
        fun render() {
            val lines = source.text.toString().lines().map(String::trim).filter(String::isNotEmpty)
            val parsed = lines.map(::splitFact)
            val keys = parsed.mapIndexed { i, (key, _) -> "${sectionOf(key, lines[i])}|$key" }
            if (keys == signature) {
                parsed.forEachIndexed { i, (_, value) -> bindFact(cells[i], value) }
                return
            }
            signature = keys
            cells.clear()
            val cellAt = sortedMapOf<Int, Triple<TextView, TextView, TextView>>()
            host.removeAllViews()
            host.addView(source, LinearLayout.LayoutParams(0, 0))
            val lists = linkedMapOf<String, LinearLayout>()
            // Sections listed in [order] come first, in that order; others follow as they appear.
            val rank = { i: Int -> order.indexOf(sectionOf(parsed[i].first, lines[i])).let { if (it < 0) order.size else it } }
            parsed.indices.sortedBy(rank).forEach { i ->
                val (key, value) = parsed[i]
                val name = sectionOf(key, lines[i])
                val group = lists.getOrPut(name) {
                    section(host, name, "Copy", "Copy $name section") { copySection(source, name, sectionOf) }
                    list(host)
                }
                val title = label(key, TextRole.RowTitle).apply { setTextIsSelectable(true) }
                val detail = label("", TextRole.Meta).apply { setTextIsSelectable(true); setLineSpacing(dp(1).toFloat(), 1f) }
                val trailing = label("", TextRole.Value, R.color.muted).apply { setTextIsSelectable(true); gravity = Gravity.END }
                group.addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = px(R.dimen.control_height)
                    setPadding(px(R.dimen.list_row_padding_h), px(R.dimen.space_sm) + dp(2), px(R.dimen.list_row_padding_h), px(R.dimen.space_sm) + dp(2))
                    addView(column().apply {
                        addView(title)
                        add(this, detail, 2)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(trailing, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(R.dimen.space_md) })
                }, LinearLayout.LayoutParams(-1, -2))
                cellAt[i] = Triple(title, detail, trailing)
                bindFact(cellAt.getValue(i), value)
            }
            cells.addAll(cellAt.values)
            if (host.childCount > 1) ((host.getChildAt(1).layoutParams) as LinearLayout.LayoutParams).topMargin = 0
        }
        (source.parent as? ViewGroup)?.removeView(source)
        source.visibility = View.GONE
        source.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        source.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: Editable?) {}
        })
        render()
        return host
    }

    private fun bindFact(cell: Triple<TextView, TextView, TextView>, value: String) {
        val (title, detail, trailing) = cell
        val result = Regex("^([A-Z]{3,}(?: [A-Z]{3,})*)(?: — | · |$)(.*)$").find(value)
        val pill = result?.groupValues?.get(1)
        var rest = result?.groupValues?.get(2)?.trim() ?: value
        val short = pill == null && rest.length <= 22 && " · " !in rest && " • " !in rest
        val shown = if (pill != null) (if ("UNAVAILABLE" in pill) "Unavailable" else pill.lowercase().replaceFirstChar(Char::uppercase)) else if (short) rest else ""
        if (short) rest = ""
        if (detail.text.toString() != rest) detail.text = rest
        detail.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        if (trailing.text.toString() != shown) trailing.text = shown
        trailing.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        if (pill != null)
            statusPill(trailing, when {
                "UNAVAILABLE" in pill -> StatusTone.NEUTRAL
                pill == "DETECTED" -> StatusTone.POSITIVE
                else -> StatusTone.WARNING
            })
        else {
            text(trailing, TextRole.Value, R.color.muted)
            trailing.background = null
            trailing.setPadding(0, 0, 0, 0)
        }
        (title.parent.parent as View).contentDescription = listOf(title.text, shown, rest).filter { it.isNotEmpty() }.joinToString(". ")
    }

    /** Copies one whole section as plain text from the bound source, so it matches the rows exactly. */
    private fun copySection(source: TextView, name: String, sectionOf: (key: String, line: String) -> String) {
        val lines = source.text.toString().lines().map(String::trim).filter(String::isNotEmpty)
            .filter { sectionOf(splitFact(it).first, it) == name }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("$name log", (listOf(name) + lines).joinToString("\n")))
        // Android 13+ confirms clipboard writes itself.
        if (android.os.Build.VERSION.SDK_INT < 33) {
            Toast.makeText(context, "$name section copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun splitFact(line: String): Pair<String, String> {
        val match = Regex("^([A-Za-z][^:·•]{0,43}?)(?::\\s+| · | • |:$)(.*)$").find(line)
            ?: return line to ""
        return match.groupValues[1].trim() to match.groupValues[2].trim()
    }

    companion object {
        const val LIST_TAG = "ui-list"
        /** Confirmations whose affirmative action stops, disables, retries or discards. */
        val CAUTION_ACTIONS = setOf("Disable", "Stop Tracking", "Stop", "Retry", "Discard edits")
    }
}
