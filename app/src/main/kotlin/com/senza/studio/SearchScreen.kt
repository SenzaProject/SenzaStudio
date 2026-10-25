package com.senza.studio

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Satu kecocokan hasil pencarian. */
class Hit(val rel: String, val line: Int, val col: Int, val len: Int, val text: String)

class SearchOpts(
    val query: String,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false
)

/** Pencarian, ganti, dan rename simbol di seluruh file teks proyek. */
object ProjectSearch {

    private val SKIP_DIRS = setOf("out", ".git", "build", ".gradle")
    private val CODE_EXT = setOf("java", "kt", "xml")

    fun files(dir: File): List<File> =
        dir.walkTopDown()
            .onEnter { it == dir || it.name !in SKIP_DIRS }
            .filter { it.isFile && ProjectStore.isTextFile(it) && it.length() < 1_000_000 }
            .sortedBy { it.path }
            .toList()

    fun compile(o: SearchOpts): Regex? {
        if (o.query.isEmpty()) return null
        var pat = if (o.regex) o.query else Regex.escape(o.query)
        if (o.wholeWord) pat = "(?<![A-Za-z0-9_])(?:$pat)(?![A-Za-z0-9_])"
        return try {
            if (o.caseSensitive) Regex(pat) else Regex(pat, RegexOption.IGNORE_CASE)
        } catch (_: Exception) { null }
    }

    fun search(dir: File, o: SearchOpts, max: Int = 500): List<Hit> {
        val re = compile(o) ?: return emptyList()
        val out = ArrayList<Hit>()
        for (f in files(dir)) {
            val rel = f.relativeTo(dir).path
            val lines = try { f.readLines() } catch (_: Exception) { continue }
            for ((i, ln) in lines.withIndex()) {
                if (ln.length > 3000) continue
                val m = re.find(ln) ?: continue
                if (m.value.isEmpty()) continue
                out.add(Hit(rel, i + 1, m.range.first, m.value.length, ln))
                if (out.size >= max) return out
            }
        }
        return out
    }

    /** Hitung kecocokan per file tanpa mengubah apa pun. */
    fun count(dir: File, o: SearchOpts, exts: Set<String>? = null): Map<String, Int> {
        val re = compile(o) ?: return emptyMap()
        val res = LinkedHashMap<String, Int>()
        for (f in files(dir)) {
            if (exts != null && f.extension.lowercase() !in exts) continue
            val text = try { f.readText() } catch (_: Exception) { continue }
            val c = re.findAll(text).count { it.value.isNotEmpty() }
            if (c > 0) res[f.relativeTo(dir).path] = c
        }
        return res
    }

    fun replaceAll(dir: File, o: SearchOpts, repl: String, exts: Set<String>? = null): Map<String, Int> {
        val re = compile(o) ?: return emptyMap()
        val r = if (o.regex) repl else Regex.escapeReplacement(repl)
        val res = LinkedHashMap<String, Int>()
        for (f in files(dir)) {
            if (exts != null && f.extension.lowercase() !in exts) continue
            val text = try { f.readText() } catch (_: Exception) { continue }
            val c = re.findAll(text).count { it.value.isNotEmpty() }
            if (c == 0) continue
            f.writeText(re.replace(text, r))
            res[f.relativeTo(dir).path] = c
        }
        return res
    }

    fun isIdentifier(s: String) = Regex("[A-Za-z_][A-Za-z0-9_]*").matches(s)

    fun renameOpts(old: String) = SearchOpts(old, caseSensitive = true, wholeWord = true)

    fun previewRename(dir: File, old: String): Map<String, Int> = count(dir, renameOpts(old), CODE_EXT)

    /** Ganti nama simbol di semua .java/.kt/.xml; file kelas ikut diganti namanya. Mengembalikan jumlah file yang berubah. */
    fun rename(dir: File, old: String, new: String): Pair<Int, Boolean> {
        val changed = replaceAll(dir, renameOpts(old), new, CODE_EXT)
        var renamed = false
        for (root in CodeAssist.sourceRoots(dir)) {
            root.walkTopDown().filter { it.isFile && it.nameWithoutExtension == old && (it.extension == "java" || it.extension == "kt") }.toList().forEach { f ->
                val dest = File(f.parentFile, new + "." + f.extension)
                if (!dest.exists() && f.renameTo(dest)) renamed = true
            }
        }
        return changed.size to renamed
    }
}

/** Layar pencarian & ganti di seluruh proyek. */
class SearchScreen(act: MainActivity) : Screen(act) {

    override val showsNav = false

    private var caseSens = false
    private var whole = false
    private var useRegex = false

    private lateinit var query: EditText
    private lateinit var replace: EditText
    private lateinit var info: TextView
    private lateinit var results: LinearLayout
    private lateinit var optRow: LinearLayout

    private var lastHits: List<Hit> = emptyList()

    override val view: View = buildView()

    private fun buildView(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(act.topBar("Cari di proyek", R.drawable.ic_back, { act.back() }), act.lp())

        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(16), act.dp(0), act.dp(16), act.dp(8))
        }
        query = act.textField("Cari teks…").apply {
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> doSearch(); true }
        }
        replace = act.textField("Ganti dengan…")
        body.addView(query, act.lp(b = 8))
        body.addView(replace, act.lp(b = 8))

        optRow = LinearLayout(act)
        body.addView(optRow, act.lp(b = 8))
        paintOptions()

        val row = LinearLayout(act)
        row.addView(act.primaryButton("Cari", R.drawable.ic_search, 14f) { doSearch() }, act.lp(0, WRAP, r = 6, weight = 1f))
        row.addView(act.glassButton("Ganti semua", R.drawable.ic_findreplace, 13f) { doReplace() }, act.lp(0, WRAP, r = 6, weight = 1f))
        row.addView(act.glassButton("Rename", R.drawable.ic_edit, 13f) { renameDialog(query.text.toString().trim()) }, act.lp(0, WRAP, weight = 1f))
        body.addView(row, act.lp(b = 8))

        info = act.label("", 12f, Pal.sub)
        body.addView(info, act.lp(b = 4))
        col.addView(body, act.lp())

        results = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(12), act.dp(2), act.dp(12), act.dp(24))
        }
        col.addView(ScrollView(act).apply { addView(results) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return col
    }

    private fun chip(text: String, on: Boolean, onClick: () -> Unit): TextView =
        act.label(text, 13f, if (on) Color.WHITE else Pal.sub, true).apply {
            setPadding(act.dp(14), act.dp(8), act.dp(14), act.dp(8))
            background = if (on) act.glass(18, 0x552F6BFF, Pal.primary) else act.glassPress(18)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun paintOptions() {
        optRow.removeAllViews()
        optRow.addView(chip("Aa  Huruf besar/kecil", caseSens) { caseSens = !caseSens; paintOptions() }, act.lp(WRAP, WRAP, r = 6))
        optRow.addView(chip("Kata utuh", whole) { whole = !whole; paintOptions() }, act.lp(WRAP, WRAP, r = 6))
        optRow.addView(chip(".*  Regex", useRegex) { useRegex = !useRegex; paintOptions() }, act.lp(WRAP, WRAP))
    }

    /** Dipanggil dari editor (cari penggunaan simbol) atau menu. */
    fun open(q: String, wholeWord: Boolean, caseSensitive: Boolean, run: Boolean) {
        query.setText(q)
        whole = wholeWord
        caseSens = caseSensitive
        useRegex = false
        paintOptions()
        act.navigate(this)
        if (run) doSearch()
    }

    override fun onShow() {
        act.editor.saveNow()
    }

    private fun opts() = SearchOpts(query.text.toString(), caseSens, whole, useRegex)

    private fun doSearch() {
        val p = act.project ?: return act.toast("Buka proyek dulu")
        val o = opts()
        if (o.query.isEmpty()) return act.toast("Isi kata yang dicari")
        if (ProjectSearch.compile(o) == null) return act.toast("Pola regex tidak valid")
        act.editor.saveNow()
        results.removeAllViews()
        info.text = "Mencari…"
        val dir = act.store.dir(p)
        Thread {
            val hits = ProjectSearch.search(dir, o)
            act.runOnUiThread { showHits(hits, o) }
        }.start()
    }

    private fun showHits(hits: List<Hit>, o: SearchOpts) {
        lastHits = hits
        results.removeAllViews()
        if (hits.isEmpty()) {
            info.text = "Tidak ada kecocokan."
            return
        }
        val files = hits.map { it.rel }.distinct().size
        info.text = "${hits.size}${if (hits.size >= 500) "+" else ""} kecocokan di $files file"
        var cur = ""
        for (h in hits) {
            if (h.rel != cur) {
                cur = h.rel
                results.addView(
                    act.label(cur, 13f, Pal.primaryLight, true).apply { ellipsize = TextUtils.TruncateAt.MIDDLE; setSingleLine() },
                    act.lp(t = 10, b = 4, l = 4)
                )
            }
            val line = h.text.trim()
            val lead = h.text.length - h.text.trimStart().length
            val sb = SpannableStringBuilder("${h.line}  ").also {
                it.setSpan(ForegroundColorSpan(Pal.dim), 0, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val base = sb.length
            sb.append(line.take(160))
            val s = h.col - lead
            if (s >= 0 && s + h.len <= line.take(160).length) {
                sb.setSpan(BackgroundColorSpan(0x66FFC857), base + s, base + s + h.len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Color.WHITE), base + s, base + s + h.len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val tv = TextView(act).apply {
                text = sb
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextColor(Pal.text)
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                setPadding(act.dp(10), act.dp(8), act.dp(10), act.dp(8))
                background = act.glassPress(10)
                isClickable = true
                setOnClickListener { act.openFile(h.rel, h.line) }
            }
            results.addView(tv, act.lp(b = 3))
        }
    }

    private fun doReplace() {
        val p = act.project ?: return act.toast("Buka proyek dulu")
        val o = opts()
        if (o.query.isEmpty()) return act.toast("Isi kata yang dicari")
        if (ProjectSearch.compile(o) == null) return act.toast("Pola regex tidak valid")
        act.editor.saveNow()
        val dir = act.store.dir(p)
        val repl = replace.text.toString()
        val counts = ProjectSearch.count(dir, o)
        if (counts.isEmpty()) return act.toast("Tidak ada yang cocok")
        val total = counts.values.sum()
        act.confirm("Ganti $total kecocokan di ${counts.size} file dengan \"$repl\"?\n\nTindakan ini mengubah file langsung. Pertimbangkan ekspor proyek (.zip) dulu.") {
            val done = ProjectSearch.replaceAll(dir, o, repl)
            afterChange()
            act.toast("${done.values.sum()} kecocokan diganti di ${done.size} file")
            doSearch()
        }
    }

    private fun afterChange() {
        CodeAssist.invalidate()
        act.editor.clearLoaded()
        act.explorer.refresh()
    }

    /** Dialog rename simbol (dipakai layar ini dan menu editor). */
    fun renameDialog(old: String) {
        val p = act.project ?: return act.toast("Buka proyek dulu")
        if (!ProjectSearch.isIdentifier(old)) return act.toast("Isi nama simbol (huruf/angka/_) yang ingin diganti")
        act.editor.saveNow()
        val dir = act.store.dir(p)
        val counts = ProjectSearch.previewRename(dir, old)
        if (counts.isEmpty()) return act.toast("'$old' tidak ditemukan di .java / .kt / .xml")
        val et = act.textField("Nama baru", old)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(22), act.dp(10), act.dp(22), 0)
        }
        box.addView(act.label("'$old' muncul ${counts.values.sum()}x di ${counts.size} file:", 13f, Pal.sub), act.lp(b = 6))
        box.addView(
            act.label(counts.entries.take(8).joinToString("\n") { "• ${it.key} (${it.value})" } + if (counts.size > 8) "\n• …" else "", 12f, Pal.text),
            act.lp(b = 10)
        )
        box.addView(act.fieldLabel("Nama baru"))
        box.addView(et, act.lp(b = 8))
        box.addView(
            act.label("Semua kata utuh yang sama diganti, termasuk di teks dan komentar. Jika ini nama kelas, file-nya ikut diganti namanya.", 11f, Pal.dim)
        )
        val dlg = AlertDialog.Builder(act).setTitle("Ganti nama simbol").setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("Ganti", null).setNegativeButton("Batal", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = et.text.toString().trim()
                when {
                    !ProjectSearch.isIdentifier(n) -> act.toast("Nama baru: huruf, angka, _ (tidak diawali angka)")
                    n == old -> act.toast("Nama baru sama dengan nama lama")
                    n in Highlighter.JAVA_KEYWORDS -> act.toast("Nama baru tidak boleh kata kunci")
                    else -> {
                        dlg.dismiss()
                        val (files, renamed) = ProjectSearch.rename(dir, old, n)
                        afterChange()
                        val extra = if (renamed) ", file kelas ikut diganti" else ""
                        act.toast("'$old' → '$n' di $files file$extra")
                        act.tabs.toList().filter { !File(dir, it).exists() }.forEach { act.onPathDeleted(it) }
                    }
                }
            }
        }
        dlg.show()
    }
}
