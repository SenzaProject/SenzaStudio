package com.senza.studio

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** UI Preview: merender XML layout proyek langsung (Design) atau menampilkan sumbernya (Code). */
class PreviewScreen(act: MainActivity) : Screen(act) {

    override val showsNav = false

    private var deviceIdx = 0
    private var designMode = true
    private var dark = true
    private var landscape = false
    private var layoutRel: String? = null

    private lateinit var layoutField: TextView
    private lateinit var deviceField: TextView
    private lateinit var area: LinearLayout
    private lateinit var modeBar: LinearLayout
    private lateinit var optRow: LinearLayout

    override val view: View = buildView()

    private fun buildView(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            act.topBar(
                "UI Preview", R.drawable.ic_back, { act.back() },
                R.drawable.ic_edit to { editLayout() }
            ), act.lp()
        )
        modeBar = act.segmented(listOf("Design", "Code"), 0) { designMode = it == 0; render() }
        col.addView(modeBar, act.lp(l = 16, r = 16, b = 10))

        layoutField = dropdown { pickLayout() }
        deviceField = dropdown { pickDevice() }
        col.addView(layoutField, act.lp(l = 16, r = 16, b = 8))
        col.addView(deviceField, act.lp(l = 16, r = 16, b = 8))

        optRow = LinearLayout(act)
        col.addView(optRow, act.lp(l = 16, r = 16, b = 10))
        paintOptions()

        area = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(act.dp(16), act.dp(4), act.dp(16), act.dp(24))
        }
        col.addView(ScrollView(act).apply { addView(area) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return col
    }

    private fun toggle(text: String, on: Boolean, onClick: () -> Unit): TextView =
        act.label(text, 13f, if (on) android.graphics.Color.WHITE else Pal.sub, true).apply {
            setPadding(act.dp(14), act.dp(8), act.dp(14), act.dp(8))
            background = if (on) act.glass(18, 0x552F6BFF, Pal.primary) else act.glassPress(18)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun paintOptions() {
        optRow.removeAllViews()
        optRow.addView(toggle(if (dark) "☾ Gelap" else "☀ Terang", dark) { dark = !dark; paintOptions(); render() }, act.lp(WRAP, WRAP, r = 8))
        optRow.addView(toggle(if (landscape) "▭ Lanskap" else "▯ Potret", landscape) { landscape = !landscape; paintOptions(); render() }, act.lp(WRAP, WRAP))
    }

    private fun dropdown(onClick: () -> Unit): TextView = act.label("", 14f, Pal.text).apply {
        background = act.glassPress(14, Pal.field)
        setPadding(act.dp(14), act.dp(13), act.dp(14), act.dp(13))
        setSingleLine()
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun select(rel: String) { layoutRel = rel }

    override fun onShow() {
        val p = act.project
        val all = if (p != null) act.store.layouts(p) else emptyList()
        if (layoutRel == null || layoutRel !in all) layoutRel = all.firstOrNull()
        render()
    }

    private fun pickLayout() {
        val p = act.project ?: return
        val all = act.store.layouts(p)
        if (all.isEmpty()) return act.toast("Belum ada file layout di res/layout/")
        act.pickItem("Pilih layout", all.map { it.substringAfterLast('/') }) { layoutRel = all[it]; render() }
    }

    private fun pickDevice() {
        act.pickItem("Pilih perangkat", PreviewKit.devices.map { it.label }) { deviceIdx = it; render() }
    }

    private fun editLayout() {
        val rel = layoutRel ?: return act.toast("Belum ada layout")
        act.openFile(rel)
    }

    private fun render() {
        area.removeAllViews()
        val dev = PreviewKit.devices[deviceIdx]
        deviceField.text = "▾  " + dev.label
        val p = act.project
        val rel = layoutRel
        layoutField.text = "▾  " + (rel?.substringAfterLast('/') ?: "(tidak ada layout)")
        if (p == null || rel == null) {
            area.addView(message("Belum ada file layout.\nBuat file di res/layout/ lewat Project Explorer (tombol +).", Pal.sub))
            return
        }
        val file = File(act.store.dir(p), rel)
        if (act.currentFile == rel) act.editor.saveNow()
        val xml = try { file.readText() } catch (e: Exception) { "" }

        if (!designMode) {
            val tv = act.mono("", 12f, Pal.editorFg).apply {
                text = Highlighter.spannable(xml, Highlighter.XML)
                setTextIsSelectable(true)
                setPadding(act.dp(14), act.dp(14), act.dp(14), act.dp(14))
                background = act.glass(14, Pal.editorBg)
            }
            area.addView(tv, act.lp())
            return
        }

        val res = LayoutPreview(act, act.store.dir(p), dark).render(xml)
        if (res.view == null) {
            area.addView(message("⚠ " + (res.error ?: "Tidak bisa merender layout."), Pal.err))
            return
        }
        val avail = act.resources.displayMetrics.widthPixels - act.dp(64)
        area.addView(PreviewKit.frame(act, dev, landscape, dark, res.view, avail))
        if (res.warnings.isNotEmpty()) {
            val card = act.card(12, Pal.warnBg)
            card.addView(act.label("Catatan preview", 13f, Pal.warn, true))
            res.warnings.distinct().forEach { card.addView(act.label("• $it", 12f, Pal.text), act.lp(t = 3)) }
            card.addView(act.label("Preview hanya perkiraan. Hasil akhir dilihat setelah APK dipasang.", 11f, Pal.sub), act.lp(t = 6))
            area.addView(card, act.lp(t = 14))
        } else {
            area.addView(
                act.label("Preview hanya perkiraan. Hasil akhir dilihat setelah APK dipasang.", 11f, Pal.dim)
                    .apply { gravity = Gravity.CENTER },
                act.lp(t = 12)
            )
        }
    }

    private fun message(text: String, color: Int): TextView = act.label(text, 14f, color).apply {
        gravity = Gravity.CENTER
        setPadding(act.dp(16), act.dp(30), act.dp(16), act.dp(30))
    }
}
