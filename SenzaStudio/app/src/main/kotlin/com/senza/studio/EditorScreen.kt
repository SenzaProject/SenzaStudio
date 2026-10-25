package com.senza.studio

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

private class BracketSpan : BackgroundColorSpan(0x663E6BFF)
private class DiagSpan(c: Int) : BackgroundColorSpan(c)
private class FoldLook : BackgroundColorSpan(0x552F6BFF)
private class FoldFg : ForegroundColorSpan(0xFF8FC1FF.toInt())

/**
 * Code Editor: tab file, nomor baris, syntax highlight, undo/redo, cari & ganti, autocomplete,
 * diagnostik langsung, lipat kode, format otomatis, split view dengan preview, dan tombol Run.
 */
class EditorScreen(act: MainActivity) : Screen(act) {

    override val showsNav = false

    var pendingLine: Int? = null

    private var loadedPath: String? = null
    private var loadedProject: String? = null
    private var loading = false
    private var folding = false
    private var dirty = false

    private val history = ArrayList<String>()
    private var histIdx = -1

    private var diags: List<Diag> = emptyList()
    private var acSeq = 0
    private var diagSeq = 0
    private var acCompletion: Completion? = null
    private var foldWarnedAt = 0L
    private var splitOn = false

    private val bg = Executors.newSingleThreadExecutor()

    private lateinit var title: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var editor: CodeEditText
    private lateinit var langLabel: TextView
    private lateinit var statusChip: TextView
    private lateinit var typing: TypingAssist
    private lateinit var acScroll: HorizontalScrollView
    private lateinit var acRow: LinearLayout
    private lateinit var bodyRow: LinearLayout
    private lateinit var previewScroll: ScrollView
    private lateinit var previewBox: LinearLayout

    private val hlRunnable = Runnable { highlight(); snapshot() }
    private val saveRunnable = Runnable { if (autoSave()) saveNow() }
    private val acRunnable = Runnable { computeAc() }
    private val diagRunnable = Runnable { computeDiag() }
    private val previewRunnable = Runnable { renderSplitPreview() }

    override val view: View = buildView()

    private fun autoSave() = act.prefs.getBoolean("auto_save", true)
    private fun mode() = Highlighter.modeFor(act.currentFile ?: "")

    private fun buildView(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        title = act.label("", 18f, Pal.text, true).apply { setSingleLine() }
        val top = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(6), act.dp(8), act.dp(6), act.dp(8))
            addView(act.iconButton(R.drawable.ic_back, 24, Pal.text) { act.back() })
            addView(title, act.lp(0, WRAP, weight = 1f))
            addView(act.iconButton(R.drawable.ic_more, 22, Pal.text) { menu() })
        }
        col.addView(top, act.lp())

        tabRow = LinearLayout(act).apply { setPadding(act.dp(10), act.dp(2), act.dp(10), act.dp(8)) }
        col.addView(HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        }, act.lp())

        editor = CodeEditText(act).apply {
            setBackgroundColor(Pal.editorBg)
            setTextColor(Pal.editorFg)
            setHintTextColor(Pal.dim)
            hint = "Ketik kode di sini…"
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            setHorizontallyScrolling(true)
            highlightColor = 0x552F6BFF
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (Build.VERSION.SDK_INT >= 29) {
                textCursorDrawable = GradientDrawable().apply { setColor(Color.WHITE); setSize(act.dp(2), -1) }
            }
        }
        typing = TypingAssist(editor) { mode() }
        editor.addTextChangedListener(typing)
        // saat ada lipatan, teks tidak boleh diubah (isi tersembunyi akan hilang)
        editor.filters = arrayOf(InputFilter { _, _, _, dest, dstart, dend ->
            if (folding || loading || !hasFolds()) null
            else {
                val now = System.currentTimeMillis()
                if (now - foldWarnedAt > 2500) {
                    foldWarnedAt = now
                    act.toast("Ada kode yang dilipat. Ketuk bagian terlipat atau pilih 'Buka semua lipatan' untuk mengedit.")
                }
                dest.subSequence(dstart, dend)
            }
        })
        editor.onChanged {
            if (!loading && !folding) {
                if (!dirty) { dirty = true; updateTitle() }
                act.handler.removeCallbacks(hlRunnable)
                act.handler.postDelayed(hlRunnable, 400)
                act.handler.removeCallbacks(saveRunnable)
                act.handler.postDelayed(saveRunnable, 1500)
                act.handler.removeCallbacks(acRunnable)
                act.handler.postDelayed(acRunnable, 170)
                act.handler.removeCallbacks(diagRunnable)
                act.handler.postDelayed(diagRunnable, 900)
                act.handler.removeCallbacks(previewRunnable)
                act.handler.postDelayed(previewRunnable, 800)
            }
        }
        editor.onSelection = { s, e -> onSelection(s, e) }

        bodyRow = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        bodyRow.addView(editor, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
            setMargins(act.dp(8), 0, act.dp(8), 0)
        })
        previewBox = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(act.dp(8), act.dp(8), act.dp(8), act.dp(16))
        }
        previewScroll = ScrollView(act).apply {
            visibility = View.GONE
            background = act.glass(12, 0x33050B2C, 0x22FFFFFF)
            addView(previewBox)
        }
        bodyRow.addView(previewScroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
            setMargins(act.dp(8), act.dp(6), act.dp(8), 0)
        })
        col.addView(bodyRow, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // baris saran autocomplete
        acRow = LinearLayout(act).apply { setPadding(act.dp(6), act.dp(4), act.dp(6), act.dp(2)) }
        acScroll = HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setBackgroundColor(0x99081552.toInt())
            addView(acRow)
        }
        col.addView(acScroll, act.lp())

        // baris simbol
        val symRow = LinearLayout(act).apply { setPadding(act.dp(6), act.dp(6), act.dp(6), act.dp(6)) }
        listOf(
            "Tab" to "    ", "{ }" to "{}", "( )" to "()", "[ ]" to "[]", "\" \"" to "\"\"",
            ";" to ";", "." to ".", "," to ",", "=" to "=", "<" to "<", ">" to ">", "/" to "/",
            "!" to "!", "+" to "+", "-" to "-", "*" to "*", "&" to "&", "|" to "|", "_" to "_", "@" to "@"
        ).forEach { (lab, ins) ->
            val backBy = if (ins.length == 2 && lab.contains(' ')) 1 else 0
            symRow.addView(act.label(lab, 15f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                minWidth = act.dp(40)
                setPadding(act.dp(10), act.dp(8), act.dp(10), act.dp(8))
                background = act.glassPress(10, Pal.glassStrong)
                isClickable = true
                setOnClickListener { insert(ins, backBy) }
            }, act.lp(WRAP, WRAP, r = 6))
        }
        col.addView(HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0x66050B2C)
            addView(symRow)
        }, act.lp())

        // bilah bawah: bahasa, status, undo, redo, cari, run
        val bottom = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Pal.navBg)
            setPadding(act.dp(10), act.dp(6), act.dp(10), act.dp(6))
        }
        langLabel = act.label("Java", 13f, Pal.text, true).apply {
            background = act.glass(18, Pal.glassStrong)
            setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
        }
        bottom.addView(langLabel)
        statusChip = act.label("", 12f, Pal.sub, true).apply {
            setPadding(act.dp(10), act.dp(8), act.dp(10), act.dp(8))
            isClickable = true
            setOnClickListener { showDiags() }
        }
        bottom.addView(statusChip, act.lp(WRAP, WRAP, l = 4))
        bottom.addView(View(act), act.lp(0, 1, weight = 1f))
        bottom.addView(act.iconButton(R.drawable.ic_undo, 22, Pal.primaryLight) { undo() })
        bottom.addView(act.iconButton(R.drawable.ic_redo, 22, Pal.primaryLight) { redo() })
        bottom.addView(act.iconButton(R.drawable.ic_search, 22, Pal.primaryLight) { findDialog() })
        bottom.addView(
            act.primaryButton("Run", R.drawable.ic_play, 14f) { runBuild() },
            act.lp(WRAP, WRAP, l = 6)
        )
        col.addView(bottom, act.lp())
        return col
    }

    // =====================================================================

    override fun onShow() {
        val p = act.project
        val rel = act.currentFile
        applyPrefs()
        hideAc()
        if (p == null || rel == null) {
            clearLoaded()
            title.text = "Editor"
            refreshTabs()
            return
        }
        val f = File(act.store.dir(p), rel)
        if (!f.isFile) { clearLoaded(); act.currentFile = null; refreshTabs(); return }
        if (loadedPath == rel && loadedProject == p) {
            // muat ulang jika isi file di disk berubah (mis. diubah dari layar lain)
            val disk = f.readText()
            if (!dirty && disk != fullText()) setText(disk, resetHistory = false)
        } else {
            saveNow()
            loadedPath = rel
            loadedProject = p
            dirty = false
            setText(f.readText(), resetHistory = true)
            editor.scrollTo(0, 0)
        }
        langLabel.text = when (Highlighter.modeFor(f.name)) {
            Highlighter.JAVA -> "Java"
            Highlighter.KOTLIN -> "Kotlin"
            Highlighter.XML -> "XML"
            else -> "Teks"
        }
        updateTitle()
        refreshTabs()
        pendingLine?.let { goToLine(it) }
        pendingLine = null
        applySplit()
        act.handler.removeCallbacks(diagRunnable)
        act.handler.postDelayed(diagRunnable, 500)
        act.handler.removeCallbacks(previewRunnable)
        act.handler.postDelayed(previewRunnable, 300)
    }

    override fun onHide() {
        saveIfAuto()
        for (r in listOf(hlRunnable, saveRunnable, acRunnable, diagRunnable, previewRunnable)) act.handler.removeCallbacks(r)
    }

    private fun applyPrefs() {
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, act.prefs.getInt("font_size", 13).toFloat())
        editor.showLineNumbers = act.prefs.getBoolean("line_numbers", true)
        typing.enabled = act.prefs.getBoolean("typing_assist", true)
        splitOn = act.prefs.getBoolean("split_view", false)
    }

    private fun updateTitle() {
        val name = act.currentFile?.substringAfterLast('/') ?: "Editor"
        title.text = if (dirty) "$name •" else name
    }

    fun clearLoaded() {
        loadedPath = null
        loadedProject = null
        dirty = false
        if (::editor.isInitialized) {
            setText("", resetHistory = true)
            diags = emptyList()
            updateStatus()
        }
    }

    fun refreshTabs() {
        if (!::tabRow.isInitialized) return
        tabRow.removeAllViews()
        act.tabs.forEach { rel ->
            val sel = rel == act.currentFile
            val tab = LinearLayout(act).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(act.dp(14), act.dp(7), act.dp(6), act.dp(7))
                background = if (sel) act.glass(18, 0x552F6BFF, Pal.primary) else act.glassPress(18)
                isClickable = true
                setOnClickListener { if (!sel) switchTo(rel) }
            }
            tab.addView(act.label(rel.substringAfterLast('/'), 13f, if (sel) Color.WHITE else Pal.sub, sel))
            tab.addView(act.iconButton(R.drawable.ic_close, 14, if (sel) Color.WHITE else Pal.sub) { act.closeTab(rel) }.apply {
                setPadding(act.dp(6), act.dp(2), act.dp(6), act.dp(2))
                layoutParams = LinearLayout.LayoutParams(act.dp(26), act.dp(20))
            })
            tabRow.addView(tab, act.lp(WRAP, WRAP, r = 8))
        }
    }

    private fun switchTo(rel: String) {
        saveNow()
        act.currentFile = rel
        onShow()
    }

    // =====================================================================
    //  Teks, simpan, undo/redo
    // =====================================================================

    private fun hasFolds(): Boolean {
        val e = editor.text
        return e.length > 0 && e.getSpans(0, e.length, FoldSpan::class.java).isNotEmpty()
    }

    /** Teks asli (semua lipatan dibuka). */
    private fun fullText(): String = FoldTools.expand(editor.text, 0, editor.text.length)

    private fun setText(s: String, resetHistory: Boolean) {
        loading = true
        typing.blocked = true
        editor.setText(s)
        typing.blocked = false
        loading = false
        highlight()
        if (resetHistory) {
            history.clear()
            history.add(s)
            histIdx = 0
        }
    }

    private fun highlight() {
        Highlighter.apply(editor.text, mode())
    }

    fun saveNow() {
        val p = loadedProject ?: return
        val rel = loadedPath ?: return
        val f = File(act.store.dir(p), rel)
        if (f.exists() && dirty) {
            f.writeText(fullText())
            CodeAssist.invalidate()
        }
        dirty = false
        if (::title.isInitialized) updateTitle()
    }

    fun saveIfAuto() { if (autoSave()) saveNow() }

    private fun snapshot() {
        if (hasFolds()) return
        val t = editor.text.toString()
        if (histIdx >= 0 && history.getOrNull(histIdx) == t) return
        while (history.size > histIdx + 1) history.removeAt(history.size - 1)
        history.add(t)
        if (history.size > 100) history.removeAt(0)
        histIdx = history.size - 1
    }

    private fun undo() {
        unfoldAll()
        act.handler.removeCallbacks(hlRunnable)
        snapshot()
        if (histIdx <= 0) return act.toast("Tidak ada yang bisa di-undo")
        histIdx--
        restore(history[histIdx])
    }

    private fun redo() {
        unfoldAll()
        if (histIdx >= history.size - 1) return act.toast("Tidak ada yang bisa di-redo")
        histIdx++
        restore(history[histIdx])
    }

    private fun restore(s: String) {
        val cur = editor.selectionStart.coerceAtLeast(0)
        loading = true
        typing.blocked = true
        editor.setText(s)
        typing.blocked = false
        loading = false
        editor.setSelection(cur.coerceAtMost(s.length))
        highlight()
        dirty = true
        updateTitle()
        act.handler.removeCallbacks(saveRunnable)
        act.handler.postDelayed(saveRunnable, 1500)
        act.handler.removeCallbacks(diagRunnable)
        act.handler.postDelayed(diagRunnable, 900)
        act.handler.removeCallbacks(previewRunnable)
        act.handler.postDelayed(previewRunnable, 800)
    }

    private fun insert(s: String, back: Int) {
        val st = editor.selectionStart.coerceAtLeast(0)
        val en = editor.selectionEnd.coerceAtLeast(0)
        val a = minOf(st, en)
        editor.text.replace(a, maxOf(st, en), s)
        editor.setSelection(a + s.length - back)
    }

    fun goToLine(line: Int) {
        unfoldAll()
        val text = editor.text.toString()
        var idx = 0
        repeat((line - 1).coerceAtLeast(0)) {
            val n = text.indexOf('\n', idx)
            if (n >= 0) idx = n + 1
        }
        editor.requestFocus()
        editor.setSelection(idx.coerceAtMost(text.length))
        editor.bringPointIntoView(idx.coerceAtMost(text.length))
    }

    // =====================================================================
    //  Kursor: pasangan kurung, ketuk lipatan
    // =====================================================================

    private fun onSelection(s: Int, e: Int) {
        if (!::editor.isInitialized || loading || folding) return
        val ed = editor.text
        ed.getSpans(0, ed.length, BracketSpan::class.java).forEach { ed.removeSpan(it) }
        if (s != e || s < 0) return
        // ketuk bagian terlipat = buka lipatan
        for (f in ed.getSpans(s, s, FoldSpan::class.java)) {
            val st = ed.getSpanStart(f)
            val en = ed.getSpanEnd(f)
            if (s > st && s < en) { act.handler.post { unfold(f) }; return }
        }
        val m = BracketMatch.find(ed, s) ?: return
        for (pos in m) ed.setSpan(BracketSpan(), pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // =====================================================================
    //  Lipat kode
    // =====================================================================

    private fun foldRange(a: Int, b: Int) {
        val e = editor.text
        if (b <= a || a < 0 || b > e.length) return
        val hidden = FoldTools.expand(e, a, b)
        val ph = " … ${hidden.count { it == '\n' } + 1} baris "
        folding = true
        typing.blocked = true
        try {
            for (s in e.getSpans(a, b, FoldSpan::class.java)) e.removeSpan(s)
            for (s in e.getSpans(a, b, FoldLook::class.java)) e.removeSpan(s)
            for (s in e.getSpans(a, b, FoldFg::class.java)) e.removeSpan(s)
            e.replace(a, b, ph)
            val end = a + ph.length
            e.setSpan(FoldSpan(hidden), a, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            e.setSpan(FoldLook(), a, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            e.setSpan(FoldFg(), a, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } finally {
            folding = false
            typing.blocked = false
        }
        editor.setSelection(a)
        highlight()
    }

    private fun unfold(f: FoldSpan) {
        val e = editor.text
        val st = e.getSpanStart(f)
        val en = e.getSpanEnd(f)
        if (st < 0) return
        folding = true
        typing.blocked = true
        try {
            for (s in e.getSpans(st, en, FoldLook::class.java)) e.removeSpan(s)
            for (s in e.getSpans(st, en, FoldFg::class.java)) e.removeSpan(s)
            e.removeSpan(f)
            e.replace(st, en, f.hidden)
        } finally {
            folding = false
            typing.blocked = false
        }
        editor.setSelection(st)
        highlight()
    }

    private fun unfoldAll() {
        if (!::editor.isInitialized || !hasFolds()) return
        val sel = editor.selectionStart.coerceAtLeast(0)
        val full = fullText()
        loading = true
        typing.blocked = true
        editor.setText(full)
        typing.blocked = false
        loading = false
        editor.setSelection(sel.coerceAtMost(full.length))
        highlight()
    }

    private fun foldMenu() {
        val m = mode()
        if (m == Highlighter.NONE) return act.toast("Lipat kode untuk file Java, Kotlin, dan XML")
        val text = editor.text.toString()
        val blocks = FoldTools.blocks(text, m)
        val items = ArrayList<String>()
        items.add("Lipat blok di kursor")
        items.add("Pilih blok untuk dilipat…")
        items.add("Buka semua lipatan")
        act.pickItem("Lipat kode", items) { i ->
            when (i) {
                0 -> {
                    val caret = editor.selectionStart.coerceAtLeast(0)
                    val b = blocks.filter { it.from <= caret && caret <= it.to }.maxByOrNull { it.from }
                    if (b == null) act.toast("Kursor tidak berada di dalam blok yang bisa dilipat") else foldRange(b.from, b.to)
                }
                1 -> {
                    if (blocks.isEmpty()) return@pickItem act.toast("Tidak ada blok yang bisa dilipat")
                    val shown = blocks.take(60)
                    act.pickItem("Pilih blok", shown.map { "baris ${it.line}: ${it.title}" }) { k -> foldRange(shown[k].from, shown[k].to) }
                }
                else -> unfoldAll()
            }
        }
    }

    // =====================================================================
    //  Format otomatis
    // =====================================================================

    private fun formatNow() {
        val m = mode()
        if (m == Highlighter.NONE) return act.toast("Format kode untuk file Java, Kotlin, dan XML")
        unfoldAll()
        val src = editor.text.toString()
        val out = CodeFormat.format(src, m)
        if (out == src) return act.toast("Kode sudah rapi")
        val sel = editor.selectionStart.coerceAtLeast(0)
        typing.blocked = true
        editor.setText(out)
        typing.blocked = false
        editor.setSelection(sel.coerceAtMost(out.length))
        highlight()
        act.toast("Kode dirapikan")
    }

    // =====================================================================
    //  Autocomplete
    // =====================================================================

    private fun hideAc() {
        acCompletion = null
        if (::acScroll.isInitialized) acScroll.visibility = View.GONE
    }

    private fun computeAc() {
        if (!act.prefs.getBoolean("autocomplete", true) || loading || folding) return hideAc()
        val p = act.project ?: return hideAc()
        val rel = act.currentFile ?: return hideAc()
        val m = mode()
        if (m == Highlighter.NONE || editor.selectionStart != editor.selectionEnd || hasFolds()) return hideAc()
        val text = editor.text.toString()
        val caret = editor.selectionStart
        val seq = ++acSeq
        val dir = act.store.dir(p)
        bg.execute {
            val comp = try { CodeAssist.complete(dir, m, text, caret, rel) } catch (_: Throwable) { null }
            act.runOnUiThread { if (seq == acSeq && editor.selectionStart == caret) showAc(comp) }
        }
    }

    private fun kindColor(kind: String): Int = when {
        kind == "class" || kind == "kelas" -> 0xFFFF9E64.toInt()
        kind == "method" || kind == "member" -> 0xFF7DCFFF.toInt()
        kind == "field" -> 0xFF9ECE6A.toInt()
        kind == "snippet" -> Pal.accent
        kind == "kunci" -> 0xFFFF7EB6.toInt()
        kind.startsWith("R") || kind.startsWith("@") || kind == "resource" -> 0xFFE0AF68.toInt()
        else -> Pal.sub
    }

    private fun showAc(comp: Completion?) {
        acRow.removeAllViews()
        if (comp == null || comp.items.isEmpty()) {
            acCompletion = null
            acScroll.visibility = View.GONE
            return
        }
        acCompletion = comp
        for (c in comp.items) {
            val chip = LinearLayout(act).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(act.dp(10), act.dp(6), act.dp(10), act.dp(6))
                background = act.glassPress(14, Pal.glassStrong)
                isClickable = true
                setOnClickListener { applyCand(c) }
            }
            chip.addView(act.label(c.label, 13f, Color.WHITE, true))
            chip.addView(act.label(c.kind, 10f, kindColor(c.kind)), act.lp(l = 6))
            acRow.addView(chip, act.lp(WRAP, WRAP, r = 6))
        }
        acScroll.scrollTo(0, 0)
        acScroll.visibility = View.VISIBLE
    }

    private fun applyCand(c: Cand) {
        val comp = acCompletion ?: return
        val e = editor.text
        val caret = editor.selectionStart.coerceAtLeast(0)
        val start = (caret - comp.prefixLen).coerceAtLeast(0)
        typing.blocked = true
        try {
            e.replace(start, caret, c.insert)
            var pos = start + c.insert.length - c.back
            if (c.imp != null) {
                val ie = CodeAssist.importEdit(e.toString(), c.imp, mode() == Highlighter.KOTLIN)
                if (ie != null && ie.first <= start) {
                    e.insert(ie.first, ie.second)
                    pos += ie.second.length
                }
            }
            editor.setSelection(pos.coerceIn(0, e.length))
        } finally {
            typing.blocked = false
        }
        hideAc()
        if (c.kind == "atribut" || c.kind == "resource") act.handler.postDelayed({ computeAc() }, 90)
    }

    // =====================================================================
    //  Diagnostik
    // =====================================================================

    private fun computeDiag() {
        if (!::editor.isInitialized || loading || folding) return
        val p = act.project
        val rel = act.currentFile
        if (p == null || rel == null || !act.prefs.getBoolean("diagnostics", true) || mode() == Highlighter.NONE || hasFolds()) {
            diags = emptyList()
            applyDiags()
            return
        }
        val text = editor.text.toString()
        val m = mode()
        val seq = ++diagSeq
        val dir = act.store.dir(p)
        bg.execute {
            val ds = CodeAssist.diagnose(dir, rel, text, m)
            act.runOnUiThread {
                if (seq == diagSeq && rel == act.currentFile) { diags = ds; applyDiags() }
            }
        }
    }

    private fun applyDiags() {
        val e = editor.text
        e.getSpans(0, e.length, DiagSpan::class.java).forEach { e.removeSpan(it) }
        for (d in diags) {
            val s = d.start.coerceIn(0, e.length)
            val en = (d.start + d.len).coerceIn(s, e.length)
            if (en > s) e.setSpan(DiagSpan(if (d.error) 0x55FF5470 else 0x44FBBF24), s, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        editor.errorLines = diags.filter { it.error }.map { it.line }.toSet()
        editor.warnLines = diags.filter { !it.error }.map { it.line }.toSet()
        editor.invalidate()
        updateStatus()
    }

    private fun updateStatus() {
        if (!::statusChip.isInitialized) return
        val errs = diags.count { it.error }
        val warns = diags.size - errs
        when {
            act.currentFile == null || mode() == Highlighter.NONE || !act.prefs.getBoolean("diagnostics", true) -> statusChip.text = ""
            errs == 0 && warns == 0 -> { statusChip.text = "✓"; statusChip.setTextColor(Pal.ok) }
            else -> {
                statusChip.text = (if (errs > 0) "✗$errs " else "") + (if (warns > 0) "⚠$warns" else "")
                statusChip.setTextColor(if (errs > 0) Pal.err else Pal.warn)
            }
        }
    }

    private fun showDiags() {
        if (diags.isEmpty()) return act.toast("Tidak ada masalah terdeteksi ✓")
        val shown = diags.take(80)
        act.pickItem("Masalah (${diags.size})", shown.map { (if (it.error) "✗ " else "⚠ ") + "baris ${it.line}: ${it.msg}" }) { goToLine(shown[it].line) }
    }

    // =====================================================================
    //  Definisi, penggunaan, rename
    // =====================================================================

    private fun wordNow(): String =
        CodeAssist.wordAt(editor.text.toString(), editor.selectionStart.coerceAtLeast(0))

    private fun gotoDefinition() {
        val p = act.project ?: return
        val rel = act.currentFile ?: return
        unfoldAll()
        val text = editor.text.toString()
        val caret = editor.selectionStart.coerceAtLeast(0)
        val word = CodeAssist.wordAt(text, caret)
        if (word.isEmpty()) return act.toast("Taruh kursor pada nama yang ingin dicari definisinya")
        val defs = CodeAssist.definitions(act.store.dir(p), text, caret, rel)
        fun go(s: Sym) { saveNow(); act.openFile(s.rel, s.line) }
        when {
            defs.isEmpty() -> act.toast("Definisi '$word' tidak ditemukan di proyek (mungkin dari library atau Android)")
            defs.size == 1 -> go(defs[0])
            else -> act.pickItem("Definisi '$word'", defs.map { "${it.kind} ${it.name} — ${it.rel}:${it.line}" }) { go(defs[it]) }
        }
    }

    private fun findUsages() {
        unfoldAll()
        val w = wordNow()
        if (w.isEmpty()) return act.toast("Taruh kursor pada nama yang ingin dicari penggunaannya")
        saveNow()
        act.search.open(w, wholeWord = true, caseSensitive = true, run = true)
    }

    private fun renameSymbol() {
        unfoldAll()
        val w = wordNow()
        saveNow()
        act.search.renameDialog(w)
    }

    // =====================================================================
    //  Split view
    // =====================================================================

    private fun isLayoutFile(): Boolean {
        val rel = act.currentFile ?: return false
        return rel.startsWith("res/layout") && rel.endsWith(".xml")
    }

    private fun horizontalSplit(): Boolean {
        val dm = act.resources.displayMetrics
        return dm.widthPixels / dm.density >= 600f || dm.widthPixels > dm.heightPixels
    }

    private fun applySplit() {
        val horizontal = horizontalSplit()
        bodyRow.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val ep = editor.layoutParams as LinearLayout.LayoutParams
        val pp = previewScroll.layoutParams as LinearLayout.LayoutParams
        if (horizontal) {
            ep.width = 0; ep.height = MATCH; ep.weight = 1f
            pp.width = 0; pp.height = MATCH; pp.weight = 1f
            pp.setMargins(0, 0, act.dp(8), 0)
        } else {
            ep.width = MATCH; ep.height = 0; ep.weight = 1f
            pp.width = MATCH; pp.height = 0; pp.weight = 1f
            pp.setMargins(act.dp(8), act.dp(6), act.dp(8), 0)
        }
        editor.layoutParams = ep
        previewScroll.layoutParams = pp
        previewScroll.visibility = if (splitOn) View.VISIBLE else View.GONE
    }

    private fun toggleSplit() {
        splitOn = !splitOn
        act.prefs.edit().putBoolean("split_view", splitOn).apply()
        applySplit()
        if (splitOn) renderSplitPreview()
        act.toast(if (splitOn) "Split view aktif" else "Split view mati")
    }

    private fun renderSplitPreview() {
        if (!::previewBox.isInitialized || !splitOn) return
        previewBox.removeAllViews()
        val p = act.project
        if (p == null || !isLayoutFile()) {
            previewBox.addView(
                act.label("Buka file layout (res/layout/*.xml) untuk melihat preview di sini.", 13f, Pal.sub).apply { gravity = Gravity.CENTER },
                act.lp(t = 24)
            )
            return
        }
        if (hasFolds()) return
        val dark = act.prefs.getBoolean("preview_dark", true)
        val res = LayoutPreview(act, act.store.dir(p), dark).render(editor.text.toString())
        if (res.view == null) {
            previewBox.addView(act.label("⚠ " + (res.error ?: "Tidak bisa merender layout."), 13f, Pal.err), act.lp(t = 16))
            return
        }
        val dm = act.resources.displayMetrics
        val paneW = if (horizontalSplit()) dm.widthPixels / 2 else dm.widthPixels
        previewBox.addView(PreviewKit.frame(act, PreviewKit.devices[0], false, dark, res.view, paneW - act.dp(40)))
        if (res.warnings.isNotEmpty()) {
            previewBox.addView(
                act.label(res.warnings.distinct().joinToString("\n") { "• $it" }, 11f, Pal.warn),
                act.lp(t = 8)
            )
        }
    }

    // =====================================================================
    //  Menu, cari & ganti, run
    // =====================================================================

    private fun menu() {
        val items = listOf(
            "Simpan sekarang", "Cari & ganti (file ini)", "Cari di proyek…", "Ke baris…",
            "Lompat ke definisi", "Cari penggunaan simbol", "Ganti nama simbol…",
            "Format kode", "Lipat kode…", "Daftar masalah",
            if (splitOn) "Split view: matikan" else "Split view: aktifkan",
            "Split view: ganti tema preview",
            "UI Preview layout ini", "Tutup tab ini", "Kembali ke proyek"
        )
        act.pickItem(act.currentFile?.substringAfterLast('/'), items) { i ->
            when (items[i]) {
                "Simpan sekarang" -> { saveNow(); act.toast("Tersimpan ✓") }
                "Cari & ganti (file ini)" -> findDialog()
                "Cari di proyek…" -> { saveNow(); act.navigate(act.search) }
                "Ke baris…" -> act.promptText("Ke baris", "Nomor baris") { n -> n.toIntOrNull()?.let { goToLine(it) } }
                "Lompat ke definisi" -> gotoDefinition()
                "Cari penggunaan simbol" -> findUsages()
                "Ganti nama simbol…" -> renameSymbol()
                "Format kode" -> formatNow()
                "Lipat kode…" -> foldMenu()
                "Daftar masalah" -> showDiags()
                "Split view: matikan", "Split view: aktifkan" -> toggleSplit()
                "Split view: ganti tema preview" -> {
                    act.prefs.edit().putBoolean("preview_dark", !act.prefs.getBoolean("preview_dark", true)).apply()
                    renderSplitPreview()
                }
                "UI Preview layout ini" -> {
                    val rel = act.currentFile
                    if (rel != null && rel.startsWith("res/layout/")) {
                        saveNow()
                        act.preview.select(rel)
                        act.navigate(act.preview)
                    } else act.toast("Buka file layout (res/layout/*.xml) untuk preview")
                }
                "Tutup tab ini" -> act.currentFile?.let { act.closeTab(it) }
                "Kembali ke proyek" -> act.back()
            }
        }
    }

    private fun findDialog() {
        unfoldAll()
        val q = act.textField("Cari…")
        val r = act.textField("Ganti dengan…")
        val info = act.label("", 12f, Pal.sub)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(20), act.dp(10), act.dp(20), 0)
        }
        box.addView(q, act.lp(b = 8))
        box.addView(r, act.lp(b = 8))
        box.addView(info, act.lp(b = 8))
        val row = LinearLayout(act)
        box.addView(row, act.lp())

        fun findNext() {
            val needle = q.text.toString()
            if (needle.isEmpty()) return
            val text = editor.text.toString()
            val total = Regex(Regex.escape(needle), RegexOption.IGNORE_CASE).findAll(text).count()
            var idx = text.indexOf(needle, editor.selectionEnd.coerceAtLeast(0), ignoreCase = true)
            if (idx < 0) idx = text.indexOf(needle, 0, ignoreCase = true)
            if (idx < 0) { info.text = "Tidak ditemukan"; return }
            editor.setSelection(idx, idx + needle.length)
            editor.bringPointIntoView(idx)
            info.text = "$total kecocokan"
        }
        fun replaceAll() {
            val needle = q.text.toString()
            if (needle.isEmpty()) return
            val text = editor.text.toString()
            val count = Regex(Regex.escape(needle), RegexOption.IGNORE_CASE).findAll(text).count()
            if (count == 0) { info.text = "Tidak ditemukan"; return }
            val out = Regex(Regex.escape(needle), RegexOption.IGNORE_CASE)
                .replace(text, Regex.escapeReplacement(r.text.toString()))
            typing.blocked = true
            editor.setText(out)
            typing.blocked = false
            info.text = "$count diganti"
        }
        row.addView(act.glassButton("Cari", R.drawable.ic_search) { findNext() }, act.lp(0, WRAP, r = 8, weight = 1f))
        row.addView(act.glassButton("Ganti semua", R.drawable.ic_findreplace) { replaceAll() }, act.lp(0, WRAP, weight = 1f))
        AlertDialog.Builder(act).setTitle("Cari & ganti").setView(box).setPositiveButton("Tutup", null).show()
    }

    private fun runBuild() {
        saveNow()
        act.navigate(act.build)
        act.build.startFromUi(run = true)
    }
}
