package com.senza.studio

import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.widget.EditText

/** Penanda teks yang dilipat. Isi aslinya disimpan di sini sampai lipatan dibuka. */
class FoldSpan(val hidden: String) {
    val lines: Int get() = hidden.count { it == '\n' }
}

/** Satu blok yang bisa dilipat: isi di antara [from, to). */
class FoldBlock(val from: Int, val to: Int, val line: Int, val title: String)

object FoldTools {

    /** Teks asli (lipatan dibuka) untuk rentang [a, b). */
    fun expand(s: Spanned, a: Int, b: Int): String {
        val spans = s.getSpans(a, b, FoldSpan::class.java)
        if (spans.isEmpty()) return s.subSequence(a, b).toString()
        val sorted = spans.sortedBy { s.getSpanStart(it) }
        val sb = StringBuilder()
        var pos = a
        for (sp in sorted) {
            val st = s.getSpanStart(sp).coerceAtLeast(pos)
            val en = s.getSpanEnd(sp)
            if (st > pos) sb.append(s, pos, st)
            sb.append(sp.hidden)
            pos = maxOf(pos, en)
        }
        if (pos < b) sb.append(s, pos, b)
        return sb.toString()
    }

    private fun lineOf(starts: IntArray, off: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= off) lo = mid else hi = mid - 1
        }
        return lo + 1
    }

    private fun lineStarts(text: String): IntArray {
        val l = ArrayList<Int>()
        l.add(0)
        for (i in text.indices) if (text[i] == '\n') l.add(i + 1)
        return l.toIntArray()
    }

    /** Cari semua blok berkurung kurawal (Java/Kotlin) atau tag bersarang (XML) yang melewati lebih dari satu baris. */
    fun blocks(text: String, mode: Int): List<FoldBlock> {
        if (text.length > 400_000) return emptyList()
        val starts = lineStarts(text)
        val out = ArrayList<FoldBlock>()
        if (mode == Highlighter.XML) xmlBlocks(text, starts, out) else codeBlocks(text, starts, out)
        return out.sortedBy { it.from }
    }

    private fun titleOf(text: String, starts: IntArray, line: Int): String {
        val s = starts[line - 1]
        val e = text.indexOf('\n', s).let { if (it < 0) text.length else it }
        return text.substring(s, e).trim().take(40)
    }

    private fun codeBlocks(text: String, starts: IntArray, out: MutableList<FoldBlock>) {
        val stack = ArrayList<Int>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    val e = text.indexOf('\n', i)
                    i = if (e < 0) n else e
                    continue
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val e = text.indexOf("*/", i + 2)
                    i = if (e < 0) n else e + 2
                    continue
                }
                text.startsWith("\"\"\"", i) -> {
                    val e = text.indexOf("\"\"\"", i + 3)
                    i = if (e < 0) n else e + 3
                    continue
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n && text[j] != '\n' && text[j] != c) {
                        if (text[j] == '\\') j++
                        j++
                    }
                    i = j + 1
                    continue
                }
                c == '{' -> stack.add(i)
                c == '}' -> if (stack.isNotEmpty()) {
                    val open = stack.removeAt(stack.size - 1)
                    val l1 = lineOf(starts, open)
                    val l2 = lineOf(starts, i)
                    if (l2 > l1) out.add(FoldBlock(open + 1, i, l1, titleOf(text, starts, l1)))
                }
            }
            i++
        }
    }

    private fun tagEnd(s: String, from: Int): Int {
        var q = '\u0000'
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (q != '\u0000') {
                if (c == q) q = '\u0000'
            } else if (c == '"' || c == '\'') q = c
            else if (c == '>') return i
            i++
        }
        return -1
    }

    private fun xmlBlocks(text: String, starts: IntArray, out: MutableList<FoldBlock>) {
        val names = ArrayList<String>()
        val contentStart = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            val lt = text.indexOf('<', i)
            if (lt < 0) break
            when {
                text.startsWith("<!--", lt) -> {
                    val e = text.indexOf("-->", lt + 4)
                    i = if (e < 0) text.length else e + 3
                }
                text.startsWith("<?", lt) || text.startsWith("<!", lt) -> {
                    val e = text.indexOf('>', lt)
                    i = if (e < 0) text.length else e + 1
                }
                text.startsWith("</", lt) -> {
                    val e = text.indexOf('>', lt)
                    val name = text.substring(lt + 2, if (e < 0) text.length else e).trim()
                    val k = names.lastIndexOf(name)
                    if (k >= 0) {
                        val from = contentStart[k]
                        while (names.size > k) { names.removeAt(names.size - 1); contentStart.removeAt(contentStart.size - 1) }
                        val l1 = lineOf(starts, from)
                        val l2 = lineOf(starts, lt)
                        if (l2 > l1) out.add(FoldBlock(from, lt, l1, titleOf(text, starts, l1)))
                    }
                    i = if (e < 0) text.length else e + 1
                }
                else -> {
                    val e = tagEnd(text, lt + 1)
                    if (e < 0) break
                    if (text[e - 1] != '/') {
                        var k = lt + 1
                        while (k < e && !text[k].isWhitespace()) k++
                        names.add(text.substring(lt + 1, k))
                        contentStart.add(e + 1)
                    } else {
                        // tag mandiri yang atributnya banyak baris juga bisa dilipat
                        val l1 = lineOf(starts, lt)
                        val l2 = lineOf(starts, e)
                        var k = lt + 1
                        while (k < e && !text[k].isWhitespace()) k++
                        if (l2 > l1 && k < e) out.add(FoldBlock(k, e - 1, l1, titleOf(text, starts, l1)))
                    }
                    i = e + 1
                }
            }
        }
    }
}

/** Letak pasangan kurung yang cocok dengan kurung di dekat kursor. */
object BracketMatch {
    private const val OPEN = "([{"
    private const val CLOSE = ")]}"

    fun find(s: CharSequence, caret: Int): IntArray? {
        if (s.length > 300_000 || caret < 0) return null
        var pos = -1
        if (caret > 0 && caret <= s.length && (OPEN.indexOf(s[caret - 1]) >= 0 || CLOSE.indexOf(s[caret - 1]) >= 0)) pos = caret - 1
        else if (caret < s.length && (OPEN.indexOf(s[caret]) >= 0 || CLOSE.indexOf(s[caret]) >= 0)) pos = caret
        if (pos < 0) return null
        val c = s[pos]
        val io = OPEN.indexOf(c)
        if (io >= 0) {
            val target = CLOSE[io]
            var depth = 0
            var i = pos
            val limit = minOf(s.length, pos + 40_000)
            while (i < limit) {
                val x = s[i]
                if (x == c) depth++ else if (x == target) {
                    depth--
                    if (depth == 0) return intArrayOf(pos, i)
                }
                i++
            }
        } else {
            val ic = CLOSE.indexOf(c)
            val target = OPEN[ic]
            var depth = 0
            var i = pos
            val limit = maxOf(0, pos - 40_000)
            while (i >= limit) {
                val x = s[i]
                if (x == c) depth++ else if (x == target) {
                    depth--
                    if (depth == 0) return intArrayOf(i, pos)
                }
                i--
            }
        }
        return null
    }
}

/**
 * Kenyamanan mengetik: auto-indent saat Enter, kurung/kutip berpasangan, lompati penutup,
 * dan geser mundur kurung tutup di awal baris.
 */
class TypingAssist(private val et: EditText, private val mode: () -> Int) : TextWatcher {

    var enabled = true
    /** true saat editor diubah lewat kode (bukan ketikan pengguna). */
    var blocked = false

    private var busy = false
    private var pStart = 0
    private var pBefore = 0
    private var pCount = 0

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        pStart = start
        pBefore = before
        pCount = count
    }

    override fun afterTextChanged(e: Editable?) {
        if (e == null || !enabled || blocked || busy) return
        if (pCount != 1 || pBefore != 0) return
        if (pStart < 0 || pStart >= e.length) return
        val m = mode()
        if (m == Highlighter.NONE) return
        val ch = e[pStart]
        busy = true
        try {
            when (ch) {
                '\n' -> newline(e, pStart, m)
                '{', '(', '[' -> open(e, pStart, ch, m)
                '"', '\'' -> quote(e, pStart, ch, m)
                '}', ')', ']' -> close(e, pStart, ch, m)
            }
        } catch (_: Throwable) {
            // jangan sampai kenyamanan mengetik merusak teks
        } finally {
            busy = false
        }
    }

    private fun lineStart(e: CharSequence, pos: Int): Int {
        var i = pos
        while (i > 0 && e[i - 1] != '\n') i--
        return i
    }

    private fun newline(e: Editable, pos: Int, m: Int) {
        val ls = lineStart(e, pos)
        val prev = e.subSequence(ls, pos).toString()
        val indent = prev.takeWhile { it == ' ' || it == '\t' }
        val trimmed = prev.trimEnd()
        val next = if (pos + 1 < e.length) e[pos + 1] else '\u0000'

        if (m == Highlighter.XML) {
            var extra = ""
            if (trimmed.startsWith("<") && !trimmed.startsWith("</") && !trimmed.startsWith("<?") && !trimmed.startsWith("<!")) {
                if (!trimmed.endsWith(">")) extra = "    "
                else if (!trimmed.endsWith("/>") && !trimmed.contains("</")) extra = "    "
            }
            val add = indent + extra
            if (add.isNotEmpty()) e.insert(pos + 1, add)
            et.setSelection(pos + 1 + add.length)
            return
        }

        val opener = trimmed.lastOrNull()
        if (opener == '{' || opener == '(' || opener == '[') {
            val closer = when (opener) { '{' -> '}'; '(' -> ')'; else -> ']' }
            val inner = indent + "    "
            if (next == closer) {
                e.insert(pos + 1, inner + "\n" + indent)
            } else {
                e.insert(pos + 1, inner)
            }
            et.setSelection(pos + 1 + inner.length)
        } else if (indent.isNotEmpty()) {
            e.insert(pos + 1, indent)
            et.setSelection(pos + 1 + indent.length)
        }
    }

    private fun open(e: Editable, pos: Int, ch: Char, m: Int) {
        if (m == Highlighter.XML) return
        val closer = when (ch) { '{' -> '}'; '(' -> ')'; else -> ']' }
        val next = if (pos + 1 < e.length) e[pos + 1] else '\n'
        if (next.isWhitespace() || next == ')' || next == ']' || next == '}' || next == ';' || next == ',') {
            e.insert(pos + 1, closer.toString())
            et.setSelection(pos + 1)
        }
    }

    private fun close(e: Editable, pos: Int, ch: Char, m: Int) {
        if (m == Highlighter.XML) return
        val next = if (pos + 1 < e.length) e[pos + 1] else '\u0000'
        if (next == ch) {
            e.delete(pos, pos + 1)
            et.setSelection(pos + 1)
            return
        }
        if (ch == '}') {
            val ls = lineStart(e, pos)
            val before = e.subSequence(ls, pos)
            if (before.length >= 4 && before.all { it == ' ' }) {
                e.delete(pos - 4, pos)
                et.setSelection(pos - 3)
            }
        }
    }

    private fun quote(e: Editable, pos: Int, ch: Char, m: Int) {
        if (m == Highlighter.XML && ch == '\'') return
        val prev = if (pos > 0) e[pos - 1] else '\n'
        val next = if (pos + 1 < e.length) e[pos + 1] else '\n'
        if (next == ch && prev != '\\') {
            e.delete(pos, pos + 1)
            et.setSelection(pos + 1)
            return
        }
        if (prev.isLetterOrDigit() || prev == '\\' || prev == '"' || prev == '\'') return
        if (next.isLetterOrDigit()) return
        e.insert(pos + 1, ch.toString())
        et.setSelection(pos + 1)
    }
}

/** Perapi kode otomatis: indentasi ulang berdasarkan kurung (Java/Kotlin) atau tag (XML). */
object CodeFormat {

    fun format(src: String, mode: Int): String {
        val text = src.replace("\r\n", "\n")
        return when (mode) {
            Highlighter.XML -> formatXml(text)
            Highlighter.JAVA, Highlighter.KOTLIN -> formatCode(text)
            else -> text.split("\n").joinToString("\n") { it.trimEnd() }.trimEnd('\n') + "\n"
        }
    }

    private fun formatCode(text: String): String {
        val lines = text.split("\n")
        val out = StringBuilder()
        var depth = 0
        var inBlock = false
        var inRaw = false
        var blank = 0
        for (raw in lines) {
            if (inRaw) {
                out.append(raw).append('\n')
                val c = Regex("\"\"\"").findAll(raw).count()
                if (c % 2 == 1) inRaw = false
                continue
            }
            val t = raw.trim()
            if (t.isEmpty()) {
                blank++
                if (blank <= 1) out.append('\n')
                continue
            }
            blank = 0
            var lead = 0
            if (!inBlock) {
                var k = 0
                while (k < t.length && (t[k] == '}' || t[k] == ')' || t[k] == ']')) { lead++; k++ }
            }
            val ind = (depth - lead).coerceAtLeast(0)
            val extra = if (inBlock && t.startsWith("*")) 1 else 0
            out.append(" ".repeat(ind * 4 + extra)).append(t).append('\n')

            var i = 0
            var str = '\u0000'
            while (i < t.length) {
                val c = t[i]
                if (inBlock) {
                    if (c == '*' && i + 1 < t.length && t[i + 1] == '/') { inBlock = false; i += 2 } else i++
                    continue
                }
                if (str != '\u0000') {
                    if (c == '\\') i += 2 else { if (c == str) str = '\u0000'; i++ }
                    continue
                }
                if (c == '/' && i + 1 < t.length && t[i + 1] == '/') break
                if (c == '/' && i + 1 < t.length && t[i + 1] == '*') { inBlock = true; i += 2; continue }
                if (t.startsWith("\"\"\"", i)) {
                    val e = t.indexOf("\"\"\"", i + 3)
                    if (e < 0) { inRaw = true; break }
                    i = e + 3
                    continue
                }
                when (c) {
                    '"', '\'' -> str = c
                    '{', '(', '[' -> depth++
                    '}', ')', ']' -> depth--
                }
                i++
            }
            if (depth < 0) depth = 0
        }
        return out.toString().trimEnd('\n') + "\n"
    }

    private fun formatXml(text: String): String {
        val lines = text.split("\n")
        val sb = StringBuilder()
        var depth = 0
        var inTag = false
        var tagDepth = 0
        var quote = '\u0000'
        var inComment = false
        var blank = 0
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty()) {
                blank++
                if (blank <= 1) sb.append('\n')
                continue
            }
            blank = 0
            val level = when {
                inComment -> depth
                inTag -> if (t.startsWith("/>") || t.startsWith(">")) tagDepth else tagDepth + 1
                t.startsWith("</") -> (depth - 1).coerceAtLeast(0)
                else -> depth
            }
            sb.append(" ".repeat(level * 4)).append(t).append('\n')

            var k = 0
            while (k < t.length) {
                val c = t[k]
                if (inComment) {
                    if (t.startsWith("-->", k)) { inComment = false; k += 3 } else k++
                    continue
                }
                if (quote != '\u0000') {
                    if (c == quote) quote = '\u0000'
                    k++
                    continue
                }
                if (inTag) {
                    when {
                        c == '"' || c == '\'' -> quote = c
                        t.startsWith("/>", k) -> { inTag = false; depth = tagDepth; k += 2; continue }
                        c == '>' -> inTag = false
                    }
                    k++
                    continue
                }
                when {
                    t.startsWith("<!--", k) -> { inComment = true; k += 4 }
                    t.startsWith("<?", k) -> { val e = t.indexOf("?>", k); k = if (e < 0) t.length else e + 2 }
                    t.startsWith("<![CDATA[", k) -> { val e = t.indexOf("]]>", k); k = if (e < 0) t.length else e + 3 }
                    t.startsWith("<!", k) -> { val e = t.indexOf('>', k); k = if (e < 0) t.length else e + 1 }
                    t.startsWith("</", k) -> {
                        val e = t.indexOf('>', k)
                        depth = (depth - 1).coerceAtLeast(0)
                        k = if (e < 0) t.length else e + 1
                    }
                    c == '<' -> { inTag = true; tagDepth = depth; depth++; k++ }
                    else -> k++
                }
            }
        }
        return sb.toString().trimEnd('\n') + "\n"
    }
}
