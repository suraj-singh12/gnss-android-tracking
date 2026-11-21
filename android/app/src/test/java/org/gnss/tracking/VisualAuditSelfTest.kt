package org.gnss.tracking

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Proves every enabled VisualAudit rule reports a deliberately defective layout, and that a
 * layout built only from the shared components produces no finding (no false pass, no noise).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualAuditSelfTest {
    private val activity by lazy { Robolectric.buildActivity(android.app.Activity::class.java).setup().get() }
    private val density get() = activity.resources.displayMetrics.density
    private fun px(n: Int) = (n * density).toInt()
    private fun audit() = VisualAudit(density, activity.resources.configuration.fontScale, VisualTokens(activity))

    private fun host(build: (LinearLayout) -> Unit): FrameLayout {
        val root = FrameLayout(activity)
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        build(column)
        activity.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(px(360), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(px(640), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, px(360), px(640))
        return root
    }

    private fun rules(root: View, rootDestination: Boolean = false) =
        audit().audit(root, "Self test", rootDestination) { false }.map { it.rule }.toSet()

    private fun assertOnly(rule: String, build: (LinearLayout) -> Unit) {
        val found = rules(host(build))
        assertTrue("$rule detected in $found", rule in found)
    }

    @Test fun typeToken() = assertOnly("type-token") { it.addView(TextView(activity).apply { text = "Odd size"; textSize = 17.3f }) }

    @Test fun ellipsizedUnlessDeclaredBounded() {
        assertOnly("text-ellipsized") {
            it.addView(TextView(activity).apply {
                text = "A very long label that must be truncated somewhere in this narrow box"
                textSize = 14f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(px(60), -2))
        }
        val bounded = rules(host {
            it.addView(TextView(activity).apply {
                text = "A declared single bounded line that ellipsizes on purpose"
                textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(px(60), -2))
        })
        assertTrue("Declared bounded line is intentional: $bounded", "text-ellipsized" !in bounded)
    }

    @Test fun textClipped() = assertOnly("text-clipped") {
        it.addView(TextView(activity).apply { text = "Three\nwrapped\nlines"; textSize = 14f }, LinearLayout.LayoutParams(-1, px(20)))
    }

    @Test fun offscreen() = assertOnly("offscreen") {
        it.addView(TextView(activity).apply { text = "Wide"; textSize = 14f }, LinearLayout.LayoutParams(px(500), -2))
    }

    @Test fun escapesParent() = assertOnly("escapes-parent") {
        val box = FrameLayout(activity)
        box.addView(TextView(activity).apply { text = "Pushed out"; textSize = 14f },
            FrameLayout.LayoutParams(-2, px(20)).apply { topMargin = px(40) })
        it.addView(box, LinearLayout.LayoutParams(-1, px(30)))
    }

    @Test fun overlap() = assertOnly("overlap") {
        val box = FrameLayout(activity)
        box.addView(TextView(activity).apply { text = "First"; textSize = 14f })
        box.addView(TextView(activity).apply { text = "Second"; textSize = 14f })
        it.addView(box)
    }

    @Test fun overlapIgnoresContentScrolledOutOfItsViewport() {
        val found = rules(host { column ->
            val scroll = ScrollView(activity)
            val inner = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            repeat(30) { inner.addView(TextView(activity).apply { text = "Row $it"; textSize = 14f }) }
            scroll.addView(inner)
            column.addView(scroll, LinearLayout.LayoutParams(-1, px(200)))
            column.addView(TextView(activity).apply { text = "Footer below the viewport"; textSize = 14f })
        })
        assertTrue("Scrolled rows beyond the viewport are not overlaps: $found", "overlap" !in found)
    }

    @Test fun touchTarget() = assertOnly("touch-target") {
        it.addView(Button(activity).apply { text = "Tiny"; minHeight = 0; minimumHeight = 0; setOnClickListener {} },
            LinearLayout.LayoutParams(px(60), px(30)))
    }

    @Test fun radiusTokenInsideRippleAndStateListWrappers() {
        val odd = GradientDrawable().apply { cornerRadius = px(7).toFloat() }
        val wrapped = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_enabled), RippleDrawable(ColorStateList.valueOf(0x33000000), GradientDrawable().apply { cornerRadius = px(12).toFloat() }, null))
            // The odd radius only exists in the disabled state; a current-state check would miss it.
            addState(intArrayOf(), odd)
        }
        assertOnly("radius-token") {
            it.addView(Button(activity).apply { text = "Wrapped"; background = wrapped; setOnClickListener {} },
                LinearLayout.LayoutParams(-1, px(48)))
        }
        assertEquals(listOf(px(12).toFloat(), px(7).toFloat()), audit().radii(wrapped))
    }

    @Test fun emptyGap() = assertOnly("empty-gap") { it.addView(TextView(activity), LinearLayout.LayoutParams(-1, px(16))) }

    @Test fun duplicateHeaderBackLinkAndTitle() {
        assertOnly("duplicate-header") { it.addView(Button(activity).apply { text = "‹ Parent"; setOnClickListener {} }) }
        val found = audit().audit(host { column ->
            val scroll = ScrollView(activity)
            scroll.addView(TextView(activity).apply { text = "Self test"; textSize = 22f })
            column.addView(scroll)
        }, "Self test", false) { false }.map { it.rule }
        assertTrue("In-page title repeating the app bar: $found", "duplicate-header" in found)
    }

    @Test fun rowMismatch() = assertOnly("row-mismatch") {
        val row = LinearLayout(activity)
        row.addView(Button(activity).apply { text = "Short"; setOnClickListener {} }, LinearLayout.LayoutParams(0, px(48), 1f))
        row.addView(Button(activity).apply { text = "Tall"; setOnClickListener {} }, LinearLayout.LayoutParams(0, px(64), 1f))
        it.addView(row)
    }

    @Test fun safeArea() {
        lateinit var target: View
        val root = host { column ->
            column.addView(View(activity), LinearLayout.LayoutParams(-1, px(600)))
            target = Button(activity).apply { text = "Below the navigation bar" }
            column.addView(target, LinearLayout.LayoutParams(-1, px(48)))
        }
        assertNotNull(audit().safeArea(root, target, "Self test", px(24), px(640 - 24)))
        assertNull(audit().safeArea(root, target, "Self test", 0, px(700)))
    }

    @Test fun sharedComponentsProduceNoFindings() {
        val ui = NativeUi(activity)
        val root = host { column ->
            column.setPadding(px(17), px(11), px(17), px(17))
            val card = ui.card(column, 0)
            ui.add(card, ui.label("Heading", TextRole.Heading))
            ui.fact(card, "Label", "Value")
            val row = LinearLayout(activity)
            row.addView(ui.button("Cancel") {}, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ui.button("Save") {}.apply { ui.style(this, primary = true) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(8) })
            ui.equalHeights(row)
            ui.add(card, row, 12)
            ui.section(column, "Section")
            ui.menu(column, "Navigation row", "Subtitle") {}
        }
        val found = audit().audit(root, "Self test", true) { false }
        assertTrue("Shared components are clean: $found", found.isEmpty())
        assertTrue((root as ViewGroup).childCount > 0)
    }
}
