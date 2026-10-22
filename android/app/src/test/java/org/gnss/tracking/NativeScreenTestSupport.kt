package org.gnss.tracking

import android.view.View
import android.view.ViewGroup
import android.widget.Button

internal fun nativeViews(view: View): List<View> =
    listOf(view) +
        if (view is ViewGroup)
            (0 until view.childCount).flatMap { nativeViews(view.getChildAt(it)) }
        else emptyList()

/**
 * Follow real menu affordances, including parent destinations, rather than bypassing navigation.
 */
internal fun openNativeScreen(activity: MainActivity, name: String) {
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
            "Edit connection" to "Party & Command",
            "Change trusted Command" to "Party & Command",
            "Command trust" to "Party & Command",
            "Tracking & GNSS" to "Settings",
            "Reporting interval" to "Tracking & GNSS",
            "Policy details" to "Tracking & GNSS",
            "Field readiness" to "Settings",
            "Appearance & SOS" to "Settings",
            "Advanced settings" to "Settings",
            "Command access" to "Advanced settings",
            "Command Instructions" to "Diagnostics",
            "Phone Alerts" to "Diagnostics",
            "Saved data & sync" to "Diagnostics",
            "Storage health" to "Saved data & sync",
            "Diagnostic tools" to "Diagnostics",
            "Physical Button Test" to "Diagnostic tools",
            "Advanced logs" to "Diagnostic tools",
            "SOS event history" to "Diagnostic tools",
        )
    if (name == "Instruction filters") {
        openNativeScreen(activity, "Command Instructions")
        nativeViews(activity.findViewById(android.R.id.content))
            .filterIsInstance<Button>()
            .single { it.isShown && it.text == "Filters" }
            .performClick()
        return
    }
    parents[name]?.let { openNativeScreen(activity, it) }
    val all = nativeViews(activity.findViewById(android.R.id.content))
    val actionLabel = if (name == "Reporting interval") "Change interval" else name
    val target =
        all.single {
            it.isShown &&
                it.isClickable &&
                ((it is Button && it.text == actionLabel) ||
                    it.contentDescription?.toString()?.startsWith("$name. ") == true)
        }
    check(
        target.performAccessibilityAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
            null,
        )
    )
}
