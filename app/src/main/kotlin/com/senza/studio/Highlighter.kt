package com.senza.studio

import android.text.Editable
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/** Syntax highlight sederhana (satu kali lewat regex, tanpa span yang saling tumpang). */
object Highlighter {

    const val NONE = 0
    const val JAVA = 1
    const val XML = 2
    const val KOTLIN = 3

    val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null", "var", "record"
    )

    val KOTLIN_KEYWORDS = setOf(
        "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else", "when",
        "for", "while", "do", "return", "break", "continue", "in", "is", "as", "try", "catch", "finally",
        "throw", "null", "true", "false", "this", "super", "override", "open", "abstract", "final",
        "private", "protected", "public", "internal", "data", "sealed", "enum", "companion", "init",
        "constructor", "lateinit", "by", "suspend", "inline", "typealias", "vararg", "where", "const",
        "operator", "infix", "tailrec", "annotation", "reified", "crossinline", "noinline"
    )

    private class Hl(color: Int) : ForegroundColorSpan(color)

    // grup: 1 komentar, 2 string, 3 anotasi, 4 angka, 5 kata kunci, 6 nama tipe (huruf besar)
    private val javaRe = Regex(
        """(//[^\n]*|/\*[\s\S]*?\*/)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|(@\w+)|\b(\d+(?:\.\d+)?[fFlLdD]?)\b|\b(""" +
            JAVA_KEYWORDS.joinToString("|") + """)\b|\b([A-Z][A-Za-z0-9_]*)\b"""
    )
    private val javaColors = intArrayOf(
        0xFF5C6B99.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
        0xFFFF9E64.toInt(), 0xFFFF7EB6.toInt(), 0xFF7DCFFF.toInt()
    )

    // sama seperti Java, ditambah string mentah """..."""
    private val kotlinRe = Regex(
        """(//[^\n]*|/\*[\s\S]*?\*/)|(\"\"\"[\s\S]*?\"\"\"|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|(@\w+)|\b(\d+(?:\.\d+)?[fFlLdD]?)\b|\b(""" +
            KOTLIN_KEYWORDS.joinToString("|") + """)\b|\b([A-Z][A-Za-z0-9_]*)\b"""
    )

    // grup: 1 komentar, 2 nilai atribut, 3 nama tag, 4 nama atribut
    private val xmlRe = Regex("""(<!--[\s\S]*?-->)|("[^"]*")|(</?[\w.:-]+)|([\w.:-]+)(?==)""")
    private val xmlColors = intArrayOf(
        0xFF5C6B99.toInt(), 0xFF9ECE6A.toInt(), 0xFFFF7EB6.toInt(), 0xFFFF9E64.toInt()
    )

    fun modeFor(name: String): Int = when (name.substringAfterLast('.', "").lowercase()) {
        "java" -> JAVA
        "xml" -> XML
        "kt", "kts" -> KOTLIN
        else -> NONE
    }

    fun apply(e: Editable, mode: Int) {
        e.getSpans(0, e.length, Hl::class.java).forEach { e.removeSpan(it) }
        if (mode == NONE || e.length > 80_000) return // file besar: lewati supaya tidak lag
        val re = when (mode) { XML -> xmlRe; KOTLIN -> kotlinRe; else -> javaRe }
        val colors = if (mode == XML) xmlColors else javaColors
        for (m in re.findAll(e.toString())) {
            val i = (1 until m.groups.size).firstOrNull { m.groups[it] != null } ?: continue
            val r = m.groups[i]!!.range
            e.setSpan(Hl(colors[i - 1]), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Versi untuk teks statis (tampilan kode di UI Preview). */
    fun spannable(text: String, mode: Int): android.text.SpannableStringBuilder {
        val sb = android.text.SpannableStringBuilder(text)
        apply(sb, mode)
        return sb
    }
}
