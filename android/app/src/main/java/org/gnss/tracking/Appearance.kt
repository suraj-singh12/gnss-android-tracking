package org.gnss.tracking

import android.content.Context
import android.content.res.Configuration

/**
 * Operator appearance preference: System (follows the phone's dark theme), Day or Night.
 * Older installs stored only a `night` boolean; that explicit choice is honoured until the
 * operator picks a mode in Settings › Appearance.
 */
internal object Appearance {
    const val SYSTEM = "System"
    const val DAY = "Day"
    const val NIGHT = "Night"
    val modes = listOf(SYSTEM, DAY, NIGHT)

    private fun prefs(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun mode(context: Context): String {
        val p = prefs(context)
        p.getString("mode", null)?.takeIf { it in modes }?.let { return it }
        return if (!p.contains("night")) SYSTEM else if (p.getBoolean("night", false)) NIGHT else DAY
    }

    fun isNight(context: Context, system: Configuration = context.resources.configuration): Boolean =
        when (mode(context)) {
            NIGHT -> true
            DAY -> false
            else -> (system.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }

    fun set(context: Context, mode: String) {
        require(mode in modes)
        prefs(context).edit().putString("mode", mode).remove("night").apply()
    }
}
