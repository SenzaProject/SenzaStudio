package com.senza.studio

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader

/** Hasil render: view atau pesan error yang ramah. */
class PreviewResult(val view: View?, val error: String?, val warnings: List<String>)

/**
 * Merender XML layout Android langsung dari teks sumber (tanpa aapt2) menjadi View asli.
 * Mendukung widget umum dan atribut yang paling sering dipakai. Widget yang belum dikenal
 * ditampilkan sebagai kotak peringatan.
 */
class LayoutPreview(private val ctx: Context, private val projectDir: File, private val dark: Boolean = true) {

    private val strings = HashMap<String, String>()
    private val colors = HashMap<String, Int>()
    private val dimens = HashMap<String, String>()
    private val ids = HashMap<String, Int>()
    private val warnings = ArrayList<String>()
    private var includeDepth = 0
    private val textFg: Int get() = if (dark) 0xFFEEEEEE.toInt() else 0xFF202020.toInt()
    private val surface: Int get() = if (dark) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt()

    init { loadValues(File(projectDir, "res/values")) }

    // ---------- resource nilai ----------

    private fun loadValues(dir: File) {
        dir.listFiles { f -> f.extension == "xml" }?.forEach { f ->
            runCatching {
                val p = parser(f.readText())
                var tag = ""
                var name = ""
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> {
                            tag = p.name
                            name = p.getAttributeValue(null, "name") ?: ""
                            if ((tag == "string" || tag == "color" || tag == "dimen") && name.isNotEmpty()) {
                                val v = readText(p)
                                when (tag) {
                                    "string" -> strings[name] = v.replace("\\'", "'").replace("\\n", "\n")
                                    "color" -> parseColor(v)?.let { colors[name] = it }
                                    "dimen" -> dimens[name] = v
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun readText(p: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.TEXT -> sb.append(p.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
            }
        }
        return sb.toString().trim()
    }

    private fun parser(xml: String): XmlPullParser =
        XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(xml)) }

    // ---------- render ----------

    fun render(xml: String): PreviewResult {
        warnings.clear()
        ids.clear()
        includeDepth = 0
        return try {
            val p = parser(xml)
            while (p.next() != XmlPullParser.START_TAG) {
                if (p.eventType == XmlPullParser.END_DOCUMENT) return PreviewResult(null, "File layout kosong.", warnings)
            }
            val root = build(p, null)
            PreviewResult(root, null, warnings.toList())
        } catch (e: Exception) {
            PreviewResult(null, "XML belum valid: ${e.message?.take(160)}", warnings)
        }
    }

    private class Attrs(val map: Map<String, String>) {
        operator fun get(k: String): String? = map["android:$k"]
        fun app(k: String): String? = map["app:$k"]
        fun tools(k: String): String? = map["tools:$k"]
    }

    private fun attrsOf(p: XmlPullParser): Attrs {
        val m = HashMap<String, String>()
        for (i in 0 until p.attributeCount) m[p.getAttributeName(i)] = p.getAttributeValue(i)
        return Attrs(m)
    }

    private fun build(p: XmlPullParser, parent: ViewGroup?): View {
        val tag = p.name
        val a = attrsOf(p)
        if (tag == "include") {
            val ref = a.map["layout"]
            skip(p)
            return renderInclude(ref, parent)
        }
        val view = create(tag, a)
        applyCommon(view, a)
        if (parent != null) parent.addView(view, layoutParams(parent, a))
        if (view is ViewGroup) {
            val depth = p.depth
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_TAG && p.depth == depth) break
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev == XmlPullParser.START_TAG) {
                    if (p.name == "requestFocus" || p.name == "merge") {
                        skip(p)
                    } else build(p, view)
                }
            }
        } else {
            skip(p)
        }
        if (view is RelativeLayout) fixRelative(view)
        return view
    }

    private fun renderInclude(ref: String?, parent: ViewGroup?): View {
        val name = ref?.substringAfter("@layout/")
        val f = if (name == null) null else File(projectDir, "res/layout/$name.xml")
        if (f == null || !f.isFile || includeDepth >= 4) {
            warnings.add("<include ${ref ?: ""}> tidak bisa dimuat")
            return placeholder("include ${ref ?: ""}", parent)
        }
        includeDepth++
        try {
            val p2 = parser(f.readText())
            while (p2.next() != XmlPullParser.START_TAG) {
                if (p2.eventType == XmlPullParser.END_DOCUMENT) return placeholder("include kosong", parent)
            }
            return build(p2, parent)
        } catch (e: Exception) {
            warnings.add("<include $ref> error: ${e.message?.take(60)}")
            return placeholder("include $ref", parent)
        } finally {
            includeDepth--
        }
    }

    private fun placeholder(text: String, parent: ViewGroup?): View {
        val tv = TextView(ctx).apply {
            tag = "keep"
            this.text = "⚠ $text"
            setTextColor(Pal.warn)
            textSize = 12f
            background = ctx.glass(6, Pal.warnBg, Pal.warn)
            setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(6))
        }
        parent?.addView(tv, layoutParams(parent, Attrs(emptyMap())))
        return tv
    }

    private fun sampleList(a: Attrs): View {
        val sv = ScrollView(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val itemRef = a.tools("listitem")
        for (i in 1..6) {
            var row: View? = null
            val name = itemRef?.substringAfter("@layout/")
            val f = if (name != null && includeDepth < 4) File(projectDir, "res/layout/$name.xml") else null
            if (f != null && f.isFile) {
                includeDepth++
                try {
                    val p2 = parser(f.readText())
                    while (p2.next() != XmlPullParser.START_TAG) {
                        if (p2.eventType == XmlPullParser.END_DOCUMENT) break
                    }
                    if (p2.eventType == XmlPullParser.START_TAG) row = build(p2, null)
                } catch (_: Exception) {} finally { includeDepth-- }
            }
            if (row == null) {
                row = TextView(ctx).apply {
                    text = "Item contoh $i"
                    setTextColor(textFg)
                    textSize = 16f
                    setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
                }
            }
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        sv.addView(col)
        return sv
    }

    private fun toolbar(a: Attrs): View {
        val primary = colors["colorPrimary"] ?: colors["purple_500"] ?: 0xFF2F6BFF.toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(primary)
            setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
            minimumHeight = ctx.dp(56)
            val title = a["title"]?.let { str(it) } ?: a.app("title")?.let { str(it) } ?: ""
            addView(TextView(ctx).apply { text = title; setTextColor(Color.WHITE); textSize = 20f; setTypeface(typeface, Typeface.BOLD) })
        }
    }

    private fun skip(p: XmlPullParser) {
        if (p.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun create(tag: String, a: Attrs): View = when (tag.substringAfterLast('.')) {
        "LinearLayout" -> LinearLayout(ctx).apply {
            orientation = if (a["orientation"] == "vertical") LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            a["weightSum"]?.toFloatOrNull()?.let { weightSum = it }
        }
        "FrameLayout" -> FrameLayout(ctx)
        "RelativeLayout" -> RelativeLayout(ctx)
        "ScrollView" -> ScrollView(ctx)
        "HorizontalScrollView" -> HorizontalScrollView(ctx)
        "TextView", "AppCompatTextView", "MaterialTextView" -> TextView(ctx)
        "EditText", "AppCompatEditText", "TextInputEditText" -> EditText(ctx)
        "Button", "MaterialButton", "AppCompatButton" -> Button(ctx)
        "ConstraintLayout" -> PreviewConstraint(ctx, ids)
        "LinearLayoutCompat" -> LinearLayout(ctx).apply {
            orientation = if (a["orientation"] == "vertical") LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        "TextInputLayout" -> LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        "NestedScrollView" -> ScrollView(ctx)
        "merge" -> FrameLayout(ctx)
        "CardView", "MaterialCardView" -> FrameLayout(ctx).apply {
            val r = a.app("cardCornerRadius")?.let { dim(it) } ?: ctx.dp(8)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(surface); cornerRadius = r.toFloat() }
            elevation = ctx.dpf(4f)
        }
        "RecyclerView", "ListView" -> sampleList(a)
        "Toolbar", "MaterialToolbar" -> toolbar(a)
        "Spinner", "AppCompatSpinner" -> TextView(ctx).apply {
            text = "Pilihan  ▾"
            setTextColor(textFg)
            setPadding(ctx.dp(8), ctx.dp(12), ctx.dp(8), ctx.dp(12))
        }
        "RatingBar" -> TextView(ctx).apply { this.tag = "keep"; text = "★★★★☆"; setTextColor(0xFFFFC857.toInt()); textSize = 22f }
        "FloatingActionButton" -> ctx.previewChip("+", colors["colorSecondary"] ?: Pal.accent, Color.WHITE, 28).apply {
            this.tag = "keep"
            textSize = 24f
            minimumWidth = ctx.dp(56)
            minimumHeight = ctx.dp(56)
        }
        "Guideline", "Barrier", "Group", "Flow" -> {
            warnings.add("<$tag> belum didukung preview")
            View(ctx)
        }
        "CheckBox" -> CheckBox(ctx)
        "RadioButton" -> RadioButton(ctx)
        "Switch" -> Switch(ctx)
        "ImageView", "ImageButton", "AppCompatImageView", "ShapeableImageView" -> ImageView(ctx).apply {
            setImageDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(0x55808FB8); cornerRadius = ctx.dp(8).toFloat()
            })
        }
        "ProgressBar" -> ProgressBar(ctx)
        "SeekBar" -> SeekBar(ctx).apply { progress = 40 }
        "View", "Space" -> View(ctx)
        else -> {
            warnings.add("<$tag> belum didukung preview")
            TextView(ctx).apply {
                this.tag = "keep"
                text = "⚠ $tag"
                setTextColor(Pal.warn)
                textSize = 12f
                background = ctx.glass(6, Pal.warnBg, Pal.warn)
                setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(6))
            }
        }
    }

    // ---------- atribut ----------

    private fun applyCommon(v: View, a: Attrs) {
        // padding
        val all = dim(a["padding"])
        val l = dim(a["paddingLeft"] ?: a["paddingStart"]) ?: all ?: 0
        val t = dim(a["paddingTop"]) ?: all ?: 0
        val r = dim(a["paddingRight"] ?: a["paddingEnd"]) ?: all ?: 0
        val b = dim(a["paddingBottom"]) ?: all ?: 0
        if (all != null || a["paddingLeft"] != null || a["paddingTop"] != null || a["paddingRight"] != null ||
            a["paddingBottom"] != null || a["paddingStart"] != null || a["paddingEnd"] != null
        ) v.setPadding(l, t, r, b)

        a["background"]?.let { bg -> color(bg)?.let { v.setBackgroundColor(it) } }
        a["alpha"]?.toFloatOrNull()?.let { v.alpha = it }
        a["minHeight"]?.let { dim(it)?.let { px -> v.minimumHeight = px } }
        a["minWidth"]?.let { dim(it)?.let { px -> v.minimumWidth = px } }
        a["id"]?.let { raw ->
            val n = raw.substringAfter('/')
            val id = ids.getOrPut(n) { View.generateViewId() }
            v.id = id
        }
        when (a["visibility"]) {
            "gone" -> v.visibility = View.GONE
            "invisible" -> v.visibility = View.INVISIBLE
        }

        if (v is LinearLayout) a["gravity"]?.let { v.gravity = parseGravity(it) }
        if (v is TextView) applyText(v, a)
    }

    private fun applyText(v: TextView, a: Attrs) {
        if (v !is Button && v.tag != "keep") {
            v.setTextColor(textFg)
            v.setHintTextColor(if (dark) 0x88FFFFFF.toInt() else 0x88000000.toInt())
        }
        (a["text"] ?: a.tools("text"))?.let { v.text = str(it) }
        (a["hint"] ?: a.tools("hint"))?.let { v.hint = str(it) }
        a["textSize"]?.let { s -> parseSp(s)?.let { v.setTextSize(TypedValue.COMPLEX_UNIT_PX, it) } }
        a["textColor"]?.let { c -> color(c)?.let { v.setTextColor(it) } }
        a["textColorHint"]?.let { c -> color(c)?.let { v.setHintTextColor(it) } }
        a["gravity"]?.let { v.gravity = parseGravity(it) }
        val style = a["textStyle"] ?: ""
        val tf = when {
            style.contains("bold") && style.contains("italic") -> Typeface.BOLD_ITALIC
            style.contains("bold") -> Typeface.BOLD
            style.contains("italic") -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        if (tf != Typeface.NORMAL) v.setTypeface(v.typeface, tf)
        a["maxLines"]?.toIntOrNull()?.let { v.maxLines = it }
        a["lines"]?.toIntOrNull()?.let { v.setLines(it) }
        if (a["singleLine"] == "true") v.setSingleLine()
        if (v is EditText) v.isFocusable = false
    }

    private fun layoutParams(parent: ViewGroup, a: Attrs): ViewGroup.LayoutParams {
        val w = size(a["layout_width"])
        val h = size(a["layout_height"])
        val lp: ViewGroup.MarginLayoutParams = when (parent) {
            is LinearLayout -> LinearLayout.LayoutParams(w, h).apply {
                weight = a["layout_weight"]?.toFloatOrNull() ?: 0f
                a["layout_gravity"]?.let { gravity = parseGravity(it) }
            }
            is PreviewConstraint -> CLp(w, h).apply { constraintRules(this, a) }
            is RelativeLayout -> RelativeLayout.LayoutParams(w, h)
            is FrameLayout -> FrameLayout.LayoutParams(w, h).apply {
                a["layout_gravity"]?.let { gravity = parseGravity(it) }
            }
            else -> ViewGroup.MarginLayoutParams(w, h)
        }
        val all = dim(a["layout_margin"])
        lp.setMargins(
            dim(a["layout_marginLeft"] ?: a["layout_marginStart"]) ?: all ?: 0,
            dim(a["layout_marginTop"]) ?: all ?: 0,
            dim(a["layout_marginRight"] ?: a["layout_marginEnd"]) ?: all ?: 0,
            dim(a["layout_marginBottom"]) ?: all ?: 0
        )
        if (lp is RelativeLayout.LayoutParams) relativeRules(lp, a)
        return lp
    }

    private fun constraintRules(lp: CLp, a: Attrs) {
        val map = mapOf(
            "layout_constraintStart_toStartOf" to "sS", "layout_constraintLeft_toLeftOf" to "sS",
            "layout_constraintStart_toEndOf" to "sE", "layout_constraintLeft_toRightOf" to "sE",
            "layout_constraintEnd_toEndOf" to "eE", "layout_constraintRight_toRightOf" to "eE",
            "layout_constraintEnd_toStartOf" to "eS", "layout_constraintRight_toLeftOf" to "eS",
            "layout_constraintTop_toTopOf" to "tT", "layout_constraintTop_toBottomOf" to "tB",
            "layout_constraintBottom_toBottomOf" to "bB", "layout_constraintBottom_toTopOf" to "bT"
        )
        map.forEach { (attr, key) ->
            a.app(attr)?.let { v -> lp.refs[key] = if (v == "parent") "parent" else v.substringAfter('/') }
        }
        a.app("layout_constraintHorizontal_bias")?.toFloatOrNull()?.let { lp.biasH = it }
        a.app("layout_constraintVertical_bias")?.toFloatOrNull()?.let { lp.biasV = it }
        if (a.app("layout_constraintHorizontal_chainStyle") != null || a.app("layout_constraintVertical_chainStyle") != null) {
            warnings.add("chain ConstraintLayout belum didukung preview")
        }
    }

    // aturan RelativeLayout bisa merujuk id yang belum dibuat, jadi dipetakan lewat ids
    private val pendingRel = HashMap<RelativeLayout.LayoutParams, List<Pair<Int, String>>>()

    private fun relativeRules(lp: RelativeLayout.LayoutParams, a: Attrs) {
        val flags = mapOf(
            "layout_alignParentTop" to RelativeLayout.ALIGN_PARENT_TOP,
            "layout_alignParentBottom" to RelativeLayout.ALIGN_PARENT_BOTTOM,
            "layout_alignParentLeft" to RelativeLayout.ALIGN_PARENT_LEFT,
            "layout_alignParentStart" to RelativeLayout.ALIGN_PARENT_LEFT,
            "layout_alignParentRight" to RelativeLayout.ALIGN_PARENT_RIGHT,
            "layout_alignParentEnd" to RelativeLayout.ALIGN_PARENT_RIGHT,
            "layout_centerInParent" to RelativeLayout.CENTER_IN_PARENT,
            "layout_centerHorizontal" to RelativeLayout.CENTER_HORIZONTAL,
            "layout_centerVertical" to RelativeLayout.CENTER_VERTICAL
        )
        flags.forEach { (k, rule) -> if (a[k] == "true") lp.addRule(rule) }
        val refs = mapOf(
            "layout_below" to RelativeLayout.BELOW,
            "layout_above" to RelativeLayout.ABOVE,
            "layout_toRightOf" to RelativeLayout.RIGHT_OF,
            "layout_toEndOf" to RelativeLayout.RIGHT_OF,
            "layout_toLeftOf" to RelativeLayout.LEFT_OF,
            "layout_toStartOf" to RelativeLayout.LEFT_OF,
            "layout_alignTop" to RelativeLayout.ALIGN_TOP,
            "layout_alignBottom" to RelativeLayout.ALIGN_BOTTOM
        )
        val list = ArrayList<Pair<Int, String>>()
        refs.forEach { (k, rule) -> a[k]?.let { list.add(rule to it.substringAfter('/')) } }
        if (list.isNotEmpty()) pendingRel[lp] = list
    }

    private fun fixRelative(rl: RelativeLayout) {
        for (i in 0 until rl.childCount) {
            val lp = rl.getChildAt(i).layoutParams as? RelativeLayout.LayoutParams ?: continue
            pendingRel.remove(lp)?.forEach { (rule, name) ->
                ids[name]?.let { lp.addRule(rule, it) }
            }
        }
    }

    // ---------- nilai ----------

    private fun str(v: String): String =
        if (v.startsWith("@string/")) strings[v.removePrefix("@string/")] ?: v.removePrefix("@string/")
        else v.replace("\\n", "\n").replace("\\'", "'")

    private fun parseColor(v: String): Int? = try { Color.parseColor(v.trim()) } catch (_: Exception) { null }

    private fun color(v: String): Int? = when {
        v.startsWith("#") -> parseColor(v)
        v.startsWith("@color/") -> colors[v.removePrefix("@color/")]
        v.startsWith("@android:color/") -> when (v.removePrefix("@android:color/")) {
            "white" -> Color.WHITE
            "black" -> Color.BLACK
            "transparent" -> Color.TRANSPARENT
            "darker_gray" -> 0xFFAAAAAA.toInt()
            "holo_blue_light" -> 0xFF33B5E5.toInt()
            "holo_blue_dark" -> 0xFF0099CC.toInt()
            "holo_green_light" -> 0xFF99CC00.toInt()
            "holo_red_light" -> 0xFFFF4444.toInt()
            "holo_orange_light" -> 0xFFFFBB33.toInt()
            else -> null
        }
        else -> null
    }

    /** Dimensi ke px. Mendukung dp, dip, sp, px, dan @dimen/. */
    private fun dim(raw: String?): Int? {
        val v = raw?.trim() ?: return null
        if (v.startsWith("@dimen/")) return dim(dimens[v.removePrefix("@dimen/")])
        return Regex("(-?[0-9]*\\.?[0-9]+)(dp|dip|sp|px)?").matchEntire(v)?.let { m ->
            val n = m.groupValues[1].toFloatOrNull() ?: return null
            when (m.groupValues[2]) {
                "px" -> n.toInt()
                "sp" -> (n * ctx.resources.displayMetrics.scaledDensity).toInt()
                else -> ctx.dpf(n).toInt()
            }
        }
    }

    private fun parseSp(raw: String): Float? = dim(raw)?.toFloat()

    private fun size(v: String?): Int = when (v) {
        "match_parent", "fill_parent" -> ViewGroup.LayoutParams.MATCH_PARENT
        "wrap_content", null -> ViewGroup.LayoutParams.WRAP_CONTENT
        else -> dim(v) ?: ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private fun parseGravity(v: String): Int {
        var g = 0
        v.split('|').forEach {
            g = g or when (it.trim()) {
                "center" -> Gravity.CENTER
                "center_horizontal" -> Gravity.CENTER_HORIZONTAL
                "center_vertical" -> Gravity.CENTER_VERTICAL
                "left", "start" -> Gravity.START
                "right", "end" -> Gravity.END
                "top" -> Gravity.TOP
                "bottom" -> Gravity.BOTTOM
                "fill" -> Gravity.FILL
                else -> 0
            }
        }
        return g
    }
}
