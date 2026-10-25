package com.senza.studio

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class PreviewDevice(val name: String, val wPx: Int, val hPx: Int, val density: Float) {
    val wDp get() = (wPx / density).toInt()
    val hDp get() = (hPx / density).toInt()
    val label get() = "$name ($wPx x $hPx)"
}

/** Bingkai ponsel yang dipakai UI Preview dan split view di editor. */
object PreviewKit {

    val devices = listOf(
        PreviewDevice("Pixel 7", 1080, 2400, 2.625f),
        PreviewDevice("Pixel 4", 1080, 2280, 2.75f),
        PreviewDevice("Small phone", 720, 1280, 2.0f),
        PreviewDevice("Tablet", 1200, 1920, 2.0f)
    )

    /** Bingkai berukuran dp asli lalu diperkecil agar muat di [availPx]. */
    fun frame(act: MainActivity, dev: PreviewDevice, landscape: Boolean, dark: Boolean, content: View, availPx: Int): View {
        val wDp = if (landscape) dev.hDp else dev.wDp
        val hDp = if (landscape) dev.wDp else dev.hDp
        val bezel = act.dp(6)
        val frameW = act.dp(wDp) + bezel * 2
        val frameH = act.dp(hDp) + bezel * 2
        val scale = minOf(1f, availPx.toFloat() / frameW)
        val barColor = if (dark) Color.WHITE else 0xFF222222.toInt()

        val frame = FrameLayout(act).apply {
            background = GradientDrawable().apply {
                setColor(0xFF0B0F1A.toInt())
                cornerRadius = act.dp(34).toFloat()
                setStroke(act.dp(3), 0xFF3A4F8F.toInt())
            }
            setPadding(bezel, bezel, bezel, bezel)
            pivotX = 0f
            pivotY = 0f
            scaleX = scale
            scaleY = scale
        }
        val screen = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (dark) 0xFF121212.toInt() else Color.WHITE)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, act.dpf(28f))
                }
            }
        }
        val status = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(18), 0, act.dp(18), 0)
            addView(act.label("10:28", 12f, barColor, true), act.lp(0, WRAP, weight = 1f))
            addView(act.label("▾ ◢ ▮", 11f, barColor))
        }
        screen.addView(status, LinearLayout.LayoutParams(MATCH, act.dp(26)))
        val body = FrameLayout(act)
        body.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        screen.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val nav = LinearLayout(act).apply { gravity = Gravity.CENTER }
        for (s in listOf("◁", "○", "□")) {
            nav.addView(act.label(s, 16f, barColor).apply { gravity = Gravity.CENTER }, act.lp(act.dp(60), WRAP))
        }
        screen.addView(nav, LinearLayout.LayoutParams(MATCH, act.dp(44)))
        frame.addView(screen, FrameLayout.LayoutParams(act.dp(wDp), act.dp(hDp)))

        val holder = FrameLayout(act)
        holder.addView(frame, FrameLayout.LayoutParams(frameW, frameH))
        holder.layoutParams = LinearLayout.LayoutParams((frameW * scale).toInt(), (frameH * scale).toInt())
        return holder
    }
}

/** LayoutParams untuk PreviewConstraint. refs: sS sE eE eS tT tB bB bT -> nama id atau "parent". */
class CLp(w: Int, h: Int) : ViewGroup.MarginLayoutParams(w, h) {
    val refs = HashMap<String, String>()
    var biasH = 0.5f
    var biasV = 0.5f
}

/**
 * Perkiraan ConstraintLayout untuk preview: constraint ke parent / view lain, bias, dan 0dp (match constraint).
 * Chain, guideline, dan barrier belum didukung.
 */
class PreviewConstraint(ctx: Context, private val ids: Map<String, Int>) : ViewGroup(ctx) {

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is CLp
    override fun generateDefaultLayoutParams(): LayoutParams = CLp(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams?): LayoutParams =
        CLp(p?.width ?: LayoutParams.WRAP_CONTENT, p?.height ?: LayoutParams.WRAP_CONTENT)

    private fun childSpec(size: Int, avail: Int, margins: Int): Int = when {
        size >= 0 -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        size == LayoutParams.MATCH_PARENT -> MeasureSpec.makeMeasureSpec((avail - margins).coerceAtLeast(0), MeasureSpec.EXACTLY)
        else -> MeasureSpec.makeMeasureSpec((avail - margins).coerceAtLeast(0), MeasureSpec.AT_MOST)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val sw = MeasureSpec.getSize(widthSpec)
        val sh = MeasureSpec.getSize(heightSpec)
        var maxR = 0
        var maxB = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as CLp
            val mh = lp.leftMargin + lp.rightMargin
            val mv = lp.topMargin + lp.bottomMargin
            c.measure(
                childSpec(if (lp.width == 0) LayoutParams.WRAP_CONTENT else lp.width, sw, mh),
                childSpec(if (lp.height == 0) LayoutParams.WRAP_CONTENT else lp.height, if (sh == 0) 100000 else sh, mv)
            )
            maxR = maxOf(maxR, c.measuredWidth + mh)
            maxB = maxOf(maxB, c.measuredHeight + mv)
        }
        val w = if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) maxR else sw
        val h = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) maxB else sh
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pw = r - l
        val ph = b - t
        val byName = HashMap<String, View>()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            ids.forEach { (n, id) -> if (c.id == id && id != View.NO_ID) byName[n] = c }
        }
        val rects = HashMap<View, IntArray>()
        val pending = ArrayList<View>()
        for (i in 0 until childCount) pending.add(getChildAt(i))

        fun ready(lp: CLp): Boolean = lp.refs.values.all { v ->
            v == "parent" || byName[v] == null || rects.containsKey(byName[v])
        }

        fun place(c: View, lp: CLp) {
            fun edge(key: String, vertical: Boolean, isEnd: Boolean): Int? {
                val ref = lp.refs[key] ?: return null
                if (ref == "parent") return if (isEnd) (if (vertical) ph else pw) else 0
                val rect = rects[byName[ref] ?: return null] ?: return null
                // rect: left, top, right, bottom
                return when (key) {
                    "sS", "eS" -> rect[0]
                    "sE", "eE" -> rect[2]
                    "tT", "bT" -> rect[1]
                    else -> rect[3]
                }
            }
            val mL = lp.leftMargin
            val mR = lp.rightMargin
            val mT = lp.topMargin
            val mB = lp.bottomMargin
            val left = edge("sS", false, false) ?: edge("sE", false, false)
            val right = edge("eE", false, true) ?: edge("eS", false, true)
            val top = edge("tT", true, false) ?: edge("tB", true, false)
            val bottom = edge("bB", true, true) ?: edge("bT", true, true)

            val wSpec = if (lp.width == 0 && left != null && right != null)
                MeasureSpec.makeMeasureSpec((right - left - mL - mR).coerceAtLeast(0), MeasureSpec.EXACTLY)
            else childSpec(if (lp.width == 0) LayoutParams.WRAP_CONTENT else lp.width, pw, mL + mR)
            val hSpec = if (lp.height == 0 && top != null && bottom != null)
                MeasureSpec.makeMeasureSpec((bottom - top - mT - mB).coerceAtLeast(0), MeasureSpec.EXACTLY)
            else childSpec(if (lp.height == 0) LayoutParams.WRAP_CONTENT else lp.height, ph, mT + mB)
            c.measure(wSpec, hSpec)
            val cw = c.measuredWidth
            val ch = c.measuredHeight

            val x = when {
                left != null && right != null -> left + mL + ((right - left - mL - mR - cw) * lp.biasH).toInt()
                left != null -> left + mL
                right != null -> right - mR - cw
                else -> mL
            }
            val y = when {
                top != null && bottom != null -> top + mT + ((bottom - top - mT - mB - ch) * lp.biasV).toInt()
                top != null -> top + mT
                bottom != null -> bottom - mB - ch
                else -> mT
            }
            c.layout(x, y, x + cw, y + ch)
            rects[c] = intArrayOf(x, y, x + cw, y + ch)
        }

        var guard = 0
        while (pending.isNotEmpty() && guard++ < childCount + 2) {
            val it = pending.iterator()
            while (it.hasNext()) {
                val c = it.next()
                val lp = c.layoutParams as CLp
                if (ready(lp)) { place(c, lp); it.remove() }
            }
        }
        // referensi melingkar atau tidak ketemu: letakkan apa adanya
        for (c in pending) place(c, c.layoutParams as CLp)
    }
}

/** Kotak kecil berisi teks (dipakai sebagai pengganti widget library di preview). */
fun Context.previewChip(text: String, fill: Int, fg: Int, radiusDp: Int): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(fg)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(radiusDp).toFloat() }
    }
