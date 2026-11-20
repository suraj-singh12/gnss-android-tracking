package org.gnss.tracking

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reusable native visual QA: layout audit rules, geometry snapshots and capture output.
 * Runs on real Android View/Skia rendering under Robolectric; see android/UI-DEVELOPMENT.md.
 */
internal data class VisualFinding(val rule: String, val screen: String, val detail: String) {
    override fun toString() = "[$rule] $screen: $detail"
}

internal class VisualAudit(private val density: Float, private val fontScale: Float, private val tokens: VisualTokens) {
    private fun dp(px: Int) = px / density

    /** Rect of [v] in [root] coordinates, after scrolling. */
    fun bounds(root: View, v: View): Rect =
        Rect(0, 0, v.width, v.height).apply { (root as ViewGroup).offsetDescendantRectToMyCoords(v, this) }

    private fun visibleViews(root: View): List<View> =
        nativeViews(root).filter { it.isShown && it.width > 0 && it.height > 0 && it.alpha > 0f }

    /** Portion of a view not hidden by an enclosing scroll viewport. */
    private fun visiblePart(root: View, v: View): Rect? {
        val r = bounds(root, v)
        var p = v.parent
        while (p is View && p !== root) {
            if (p is ScrollView || p is HorizontalScrollView) {
                val viewport = Rect(p.scrollX, p.scrollY, p.scrollX + p.width, p.scrollY + p.height)
                (root as ViewGroup).offsetDescendantRectToMyCoords(p, viewport)
                if (!r.intersect(viewport)) return null
            }
            p = p.parent
        }
        return r
    }

    private fun name(v: View): String {
        val label = (v as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() } ?: v.contentDescription?.toString()
        return "${v.javaClass.simpleName}(${label?.replace('\n', ' ')?.take(40) ?: "#" + v.id})"
    }

    private fun inScroll(v: View): Boolean {
        var p = v.parent
        while (p is View) { if (p is ScrollView || p is HorizontalScrollView) return true; p = p.parent }
        return false
    }

    fun audit(root: View, screen: String, rootDestination: Boolean, allow: (VisualFinding) -> Boolean): List<VisualFinding> {
        val out = mutableListOf<VisualFinding>()
        fun report(rule: String, detail: String) {
            val f = VisualFinding(rule, screen, detail)
            if (!allow(f)) out += f
        }
        val views = visibleViews(root)
        val rootW = root.width
        for (v in views) {
            val r = visiblePart(root, v) ?: continue
            // R1 text clipping / truncation.
            if (v is TextView && v !is EditText && v.layout != null && v.text.isNotEmpty()) {
                val layout = v.layout
                val needed = layout.height + v.totalPaddingTop + v.totalPaddingBottom
                if (needed > v.height + 1 && v.maxLines >= layout.lineCount && !inScrollable(v))
                    report("text-clipped", "${name(v)} needs ${dp(needed)}dp, has ${dp(v.height)}dp")
                // An explicitly bounded single line (maxLines = 1 + ellipsize) is a design decision
                // whose full value must exist elsewhere; any other truncation is a defect.
                val bounded = v.maxLines == 1 && v.ellipsize != null
                for (i in 0 until layout.lineCount) if (layout.getEllipsisCount(i) > 0 && !bounded) {
                    report("text-ellipsized", name(v)); break
                }
                if (layout.lineCount == 1 && layout.getLineWidth(0) > v.width - v.totalPaddingLeft - v.totalPaddingRight + 2 && layout.getEllipsisCount(0) == 0 && !inScrollable(v))
                    report("text-clipped", "${name(v)} single line ${dp(layout.getLineWidth(0).toInt())}dp exceeds ${dp(v.width - v.totalPaddingLeft - v.totalPaddingRight)}dp")
            }
            // R2 horizontal overflow beyond the window.
            if (!inHorizontalScroll(v) && (r.left < -1 || r.right > rootW + 1))
                report("offscreen", "${name(v)} spans ${dp(r.left)}..${dp(r.right)}dp of ${dp(rootW)}dp")
            // R3 child escapes a non-scrolling parent.
            val parent = v.parent as? ViewGroup
            if (parent != null && parent !is ScrollView && parent !is HorizontalScrollView && parent.isShown && parent !== root &&
                (v.left < -1 || v.top < -1 || v.right > parent.width + 1 || v.bottom > parent.height + 1))
                report("escapes-parent", "${name(v)} [${v.left},${v.top},${v.right},${v.bottom}] in ${name(parent)} ${parent.width}x${parent.height}")
            // R5 touch target.
            val interactive = v.isEnabled && v.isClickable && (v.hasOnClickListeners() || v is CompoundButton || v is Spinner || v is Button) &&
                !(v is TextView && v.isTextSelectable && v !is Button)
            if (interactive && (dp(v.height) < 47.5f || dp(v.width) < 47.5f))
                report("touch-target", "${name(v)} ${dp(v.width)}x${dp(v.height)}dp")
            // R6 tokens: type scale and corner radii.
            if (v is TextView && v.text.isNotEmpty() && v.autoSizeTextType == TextView.AUTO_SIZE_TEXT_TYPE_NONE) {
                val sp = v.textSize / (density * fontScale)
                if (tokens.textSizes.none { kotlin.math.abs(it - sp) < 0.06f } && !(v is EditText))
                    report("type-token", "${name(v)} ${"%.2f".format(sp)}sp is not a type token")
            }
            radius(v.background)?.let { radius ->
                val d = radius / density
                if (d > 0.5f && tokens.radii.none { kotlin.math.abs(it - d) < 0.6f } && d < 400f)
                    report("radius-token", "${name(v)} ${"%.1f".format(d)}dp radius is not a shape token")
            }
            // R10 empty text occupying layout.
            if (v is TextView && v !is EditText && v !is Button && v.text.isEmpty() && v.hint.isNullOrEmpty() && v.height > 2)
                report("empty-gap", "empty ${name(v)} occupies ${dp(v.height)}dp")
            // R9 duplicate in-page navigation/title.
            if (v is Button && v.text.startsWith("‹")) report("duplicate-header", "in-page back link ${name(v)}")
            if (!rootDestination && v is TextView && v !is Button && inScroll(v) && v.textSize / (density * fontScale) >= 20f && v.text.toString() == screen)
                report("duplicate-header", "in-page title duplicates app bar: ${name(v)}")
        }
        // R4 overlapping leaves.
        val leaves = views.filter { it !is ViewGroup || it is Spinner }.mapNotNull { v -> visiblePart(root, v)?.let { v to it } }
        for (i in leaves.indices) for (j in i + 1 until leaves.size) {
            val (a, ra) = leaves[i]; val (b, rb) = leaves[j]
            val x = Rect(ra)
            if (x.intersect(rb) && x.width() > density && x.height() > density && !isAncestor(a, b) && !isAncestor(b, a))
                report("overlap", "${name(a)} overlaps ${name(b)} by ${dp(x.width())}x${dp(x.height())}dp")
        }
        // R7 sibling actions in one row share a height.
        for (row in views.filterIsInstance<LinearLayout>().filter { it.orientation == LinearLayout.HORIZONTAL }) {
            val buttons = (0 until row.childCount).map { row.getChildAt(it) }.filter { it.isShown && it is Button && it.text.isNotEmpty() }
            if (buttons.size > 1 && buttons.map { it.height }.distinct().size > 1)
                report("row-mismatch", buttons.joinToString { "${name(it)}=${dp(it.height)}dp" })
        }
        return out
    }

    private fun inScrollable(v: TextView) = v.movementMethod != null && v.isVerticalScrollBarEnabled && v.maxLines < Int.MAX_VALUE
    private fun inHorizontalScroll(v: View): Boolean {
        var p = v.parent
        while (p is View) { if (p is HorizontalScrollView) return true; p = p.parent }
        return false
    }
    private fun isAncestor(a: View, b: View): Boolean {
        var p = b.parent
        while (p is View) { if (p === a) return true; p = p.parent }
        return false
    }

    private fun radius(d: Drawable?): Float? = when (d) {
        null -> null
        is GradientDrawable -> d.cornerRadius
        is StateListDrawable -> radius(d.current)
        is InsetDrawable -> radius(d.drawable)
        is LayerDrawable -> if (d.numberOfLayers > 0) radius(d.getDrawable(0)) else null
        else -> null
    }

    /** Stable geometry of controls and surfaces for regression comparison (dp, rounded). */
    fun geometry(root: View): JSONObject {
        val out = JSONObject()
        val counts = mutableMapOf<String, Int>()
        for (v in visibleViews(root)) {
            val surface = v is LinearLayout && radius(v.background) != null
            val control = v is Button || v is EditText || v is Spinner || v is CompoundButton || (v is ImageView && v.drawable != null)
            if (!surface && !control) continue
            val base = when {
                v is Button || v is CompoundButton -> "${v.javaClass.simpleName}:${(v as TextView).text.toString().lineSequence().first().take(28).ifEmpty { v.contentDescription ?: "icon" }}"
                v is EditText -> "EditText:${v.contentDescription ?: v.hint ?: v.id}"
                v is Spinner -> "Spinner:${v.contentDescription}"
                v is ImageView -> "Icon"
                else -> "Surface"
            }
            val n = counts.merge(base, 1, Int::plus)!!
            val r = bounds(root, v)
            out.put("$base#$n", JSONArray(listOf(r.left, r.top, r.width(), r.height()).map { Math.round(it / density) }))
        }
        return out
    }
}

internal class VisualTokens(context: android.content.Context) {
    private val res = context.resources
    private val dm = res.displayMetrics
    private fun sp(id: Int) = res.getDimension(id) / (dm.density * res.configuration.fontScale)
    private fun dp(id: Int) = res.getDimension(id) / dm.density
    val textSizes = listOf(
        R.dimen.text_display, R.dimen.text_dialog_title, R.dimen.text_appbar_title, R.dimen.text_hero,
        R.dimen.text_heading, R.dimen.text_body, R.dimen.text_button, R.dimen.text_row_title, R.dimen.text_value,
        R.dimen.text_label, R.dimen.text_mono, R.dimen.text_caption, R.dimen.text_small, R.dimen.text_meta,
        R.dimen.text_micro, R.dimen.text_metric,
    ).map(::sp)
    val radii = listOf(
        R.dimen.radius_card, R.dimen.radius_dialog, R.dimen.radius_button, R.dimen.radius_input,
        R.dimen.radius_dock, R.dimen.radius_sos, R.dimen.radius_nav, R.dimen.radius_brand,
    ).map(::dp)
}

/** Geometry baseline store under app/src/test/resources/visual-baseline. */
internal object VisualBaseline {
    private val dir = File("src/test/resources/visual-baseline")
    val recording get() = System.getenv("GNSS_VISUAL_BASELINE") == "record"

    fun compare(profile: String, actual: JSONObject, tolerance: Int = 2): List<String> {
        val file = File(dir, "$profile.json")
        if (recording) {
            dir.mkdirs(); file.writeText(actual.toString(1) + "\n"); return emptyList()
        }
        if (!file.exists()) return listOf("$profile: no approved geometry baseline at ${file.path}; review captures, then record with GNSS_VISUAL_BASELINE=record")
        val expected = JSONObject(file.readText())
        val diffs = mutableListOf<String>()
        for (screen in (expected.keys().asSequence() + actual.keys().asSequence()).toSortedSet()) {
            val e = expected.optJSONObject(screen); val a = actual.optJSONObject(screen)
            if (e == null || a == null) { diffs += "$screen: screen ${if (e == null) "added" else "missing"}"; continue }
            for (key in (e.keys().asSequence() + a.keys().asSequence()).toSortedSet()) {
                val ev = e.optJSONArray(key); val av = a.optJSONArray(key)
                if (ev == null || av == null) { diffs += "$screen/$key: ${if (ev == null) "added" else "removed"}"; continue }
                if ((0 until 4).any { kotlin.math.abs(ev.getInt(it) - av.getInt(it)) > tolerance })
                    diffs += "$screen/$key: $ev -> $av"
            }
        }
        return diffs
    }
}

internal class VisualCapture(private val profile: String) {
    val dir: File = (System.getenv("GNSS_SCREENSHOT_DIR")?.let { File(it, "visual-qa") } ?: File("build/visual-qa")).let { File(it, profile) }.apply { mkdirs() }
    val names = mutableListOf<String>()
    fun write(name: String, view: View) {
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        names += name
    }
    fun report(findings: List<VisualFinding>, geometryDiffs: List<String>) {
        File(dir, "report.json").writeText(JSONObject().apply {
            put("profile", profile)
            put("kind", "Robolectric native View/Skia render; not a physical-device capture")
            put("captures", JSONArray(names))
            put("findings", JSONArray(findings.map { it.toString() }))
            put("geometry_diffs", JSONArray(geometryDiffs))
        }.toString(1))
        File(dir, "index.html").writeText(buildString {
            append("<!doctype html><meta charset=utf-8><title>$profile</title><style>body{font:13px sans-serif;margin:16px}figure{display:inline-block;margin:6px;vertical-align:top}img{width:270px;border:1px solid #ccc}</style>")
            append("<h1>$profile</h1><p>${findings.size} audit findings, ${geometryDiffs.size} geometry differences</p><ul>")
            (findings.map { it.toString() } + geometryDiffs).forEach { append("<li>").append(it.replace("<", "&lt;")).append("</li>") }
            append("</ul>")
            names.forEach { append("<figure><img src='$it.png'><figcaption>$it</figcaption></figure>") }
        })
    }
}
