package org.gnss.tracking

import android.view.View
import android.view.ViewGroup
import android.widget.Button

internal fun nativeViews(view: View): List<View> =
    listOf(view) +
        if (view is ViewGroup)
            (0 until view.childCount).flatMap { nativeViews(view.getChildAt(it)) }
        else emptyList()

/** Traverse actual V4.6 affordances; never inject private navigation state. */
internal fun openNativeScreen(activity: MainActivity, name: String) {
    if (name == "Instruction filters") {
        openNativeScreen(activity, "History search & filters")
        nativeViews(activity.findViewById(android.R.id.content))
            .filterIsInstance<Button>()
            .single { it.isShown && it.text == "Filter history" }
            .performClick()
        nativeViews(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().window!!.decorView)
            .filterIsInstance<Button>().single { it.isShown && it.text == "Canonical categories" }.performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        return
    }
    if (name == "SOS details") {
        openNativeScreen(activity, "SOS event history")
        nativeViews(activity.findViewById(android.R.id.content))
            .first {
                it.isShown &&
                    it.isClickable &&
                    (it.contentDescription?.startsWith("Delivery pending.") == true ||
                        it.contentDescription?.startsWith("Command received.") == true)
            }
            .performClick()
        return
    }
    val parents =
        mapOf(
            "Party & Command" to "Settings",
            "Command access" to "Settings",
            "Tracking policy" to "Settings",
            "Field readiness" to "Settings",
            "Emergency controls" to "Settings",
            "Manage Command access" to "Command access",
            "Identity & verification details" to "Manage Command access",
            "Policy details" to "Tracking policy",
            "Command Instructions" to "Diagnostics",
            "History search & filters" to "Command Instructions",
            "Instruction filters" to "History search & filters",
            "Phone Alerts" to "Diagnostics",
            "Saved data & sync" to "Diagnostics",
            "Transfer recovery" to "Saved data & sync",
            "Storage health" to "Transfer recovery",
            "Diagnostic tools" to "Diagnostics",
            "Advanced logs" to "Diagnostic tools",
            "SOS event history" to "Diagnostics",
            "GNSS details" to "Diagnostics",
            "Command contact" to "Diagnostics",
        )
    parents[name]?.let { openNativeScreen(activity, it) }
    val label =
        when (name) {
            "Manage Command access" -> "Manage access & trust"
            "SOS event history" -> "SOS history"
            "Phone Alerts" -> "Phone alerts"
            else -> name
        }
    val target =
        nativeViews(activity.findViewById(android.R.id.content)).single {
            it.isShown &&
                it.isClickable &&
                ((it is Button && it.text == label) ||
                    it.contentDescription?.startsWith("$label. ") == true)
        }
    check(
        target.performAccessibilityAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
            null,
        )
    )
}
