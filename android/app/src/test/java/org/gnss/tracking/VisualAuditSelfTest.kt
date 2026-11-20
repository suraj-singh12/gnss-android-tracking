package org.gnss.tracking

import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Proves each VisualAudit rule detects a deliberately defective layout (no false pass). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualAuditSelfTest {
    @Test
    fun everyRuleReportsItsDefect() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        fun px(n: Int) = (n * density).toInt()
        val root = FrameLayout(activity)
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        column.addView(TextView(activity).apply { text = "Odd size"; textSize = 17.3f })
        // Two-line limit with ellipsis: an unintended truncation, unlike a declared bounded line.
        column.addView(TextView(activity).apply {
            text = "A very long label that must be truncated somewhere in this narrow box"
            textSize = 14f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(px(60), -2))
        column.addView(Button(activity).apply { text = "Tiny"; minHeight = 0; minimumHeight = 0; setOnClickListener {} }, LinearLayout.LayoutParams(px(60), px(30)))
        column.addView(TextView(activity), LinearLayout.LayoutParams(-1, px(16)))
        column.addView(Button(activity).apply { text = "‹ Parent"; setOnClickListener {} })
        val overlap = FrameLayout(activity)
        overlap.addView(TextView(activity).apply { text = "First"; textSize = 14f })
        overlap.addView(TextView(activity).apply { text = "Second"; textSize = 14f })
        column.addView(overlap)
        root.measure(View.MeasureSpec.makeMeasureSpec(px(360), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(px(640), View.MeasureSpec.EXACTLY))
        activity.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(px(360), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(px(640), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, px(360), px(640))
        val findings = VisualAudit(density, activity.resources.configuration.fontScale, VisualTokens(activity))
            .audit(root, "Self test", false) { false }
            .map { it.rule }.toSet()
        for (rule in listOf("type-token", "text-ellipsized", "touch-target", "empty-gap", "duplicate-header", "overlap"))
            assertTrue("$rule detected in $findings", rule in findings)
    }
}
