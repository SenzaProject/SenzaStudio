package com.senza.studio

import java.io.File

/** Satu library di version catalog. [version] kosong = versinya diatur BOM/platform. */
class CatalogLib(val group: String, val artifact: String, val version: String) {
    fun coord() = MavenCoord(group, artifact, version)
}

/**
 * Pembaca `gradle/libs.versions.toml` (subset TOML yang dipakai Gradle):
 * `[versions]`, `[libraries]`, `[bundles]`. `[plugins]` diabaikan.
 *
 * Nama alias dinormalisasi seperti accessor Gradle:
 * `androidx-core-ktx`, `androidx_core_ktx`, dan `androidx.core.ktx` semuanya menjadi `androidx.core.ktx`,
 * sehingga `libs.androidx.core.ktx` menemukan library-nya.
 */
class VersionCatalog private constructor(
    private val versions: Map<String, String>,
    private val libraries: Map<String, CatalogLib>,
    private val bundles: Map<String, List<String>>,
    val warnings: List<String>
) {
    fun library(ref: String): CatalogLib? = libraries[norm(ref)]
    fun bundle(ref: String): List<CatalogLib> = bundles[norm(ref)].orEmpty().mapNotNull { libraries[it] }
    fun version(ref: String): String? = versions[ref] ?: versions[norm(ref)]

    companion object {
        fun norm(alias: String) = alias.trim().lowercase().replace('-', '.').replace('_', '.')

        fun fileOf(proj: File): File? =
            listOf("gradle/libs.versions.toml", "libs.versions.toml").map { File(proj, it) }.firstOrNull { it.isFile }

        fun load(proj: File): VersionCatalog? {
            val f = fileOf(proj) ?: return null
            return try { parse(f.readText()) } catch (_: Exception) { null }
        }

        private class Entry(val section: String, val key: String, val value: Any?)

        fun parse(raw: String): VersionCatalog {
            val warn = ArrayList<String>()
            val entries = ArrayList<Entry>()
            var section = ""
            for (stmt in statements(raw)) {
                if (stmt.startsWith("[")) { section = stmt.trim('[', ']', ' ').trim(); continue }
                val eq = stmt.indexOf('=')
                if (eq <= 0) continue
                val key = stmt.substring(0, eq).trim().trim('"', '\'')
                val value = try {
                    Reader(stmt.substring(eq + 1)).value()
                } catch (e: Exception) {
                    warn.add("Baris catalog tidak terbaca: $stmt")
                    continue
                }
                entries.add(Entry(section, key, value))
            }

            // [versions] dibaca dulu supaya urutan bagian di file tidak berpengaruh
            val versions = HashMap<String, String>()
            for (e in entries) if (e.section == "versions") {
                versionText(e.value)?.let { versions[e.key] = it; versions[norm(e.key)] = it }
            }
            val libs = LinkedHashMap<String, CatalogLib>()
            for (e in entries) if (e.section == "libraries") {
                library(e.key, e.value, versions, warn)?.let { libs[norm(e.key)] = it }
            }
            val bundles = LinkedHashMap<String, List<String>>()
            for (e in entries) if (e.section == "bundles") {
                val list = (e.value as? List<*>)?.filterIsInstance<String>()?.map { norm(it) }
                if (list != null) bundles[norm(e.key)] = list else warn.add("Bundle '${e.key}' tidak valid")
            }
            return VersionCatalog(versions, libs, bundles, warn)
        }

        private fun versionText(v: Any?): String? = when (v) {
            is String -> v
            is Map<*, *> -> listOf("prefer", "require", "strictly").firstNotNullOfOrNull { v[it] as? String }
            else -> null
        }

        private fun library(key: String, v: Any?, versions: Map<String, String>, warn: MutableList<String>): CatalogLib? {
            if (v is String) {
                val p = v.split(':')
                if (p.size >= 2 && p[0].isNotBlank() && p[1].isNotBlank()) {
                    return CatalogLib(p[0].trim(), p[1].trim(), p.getOrElse(2) { "" }.trim())
                }
                warn.add("Library '$key' tidak valid: $v")
                return null
            }
            if (v !is Map<*, *>) {
                warn.add("Library '$key' tidak valid")
                return null
            }
            val module = (v["module"] as? String)?.split(':')
            val g = if (module != null && module.size == 2) module[0] else v["group"] as? String
            val a = if (module != null && module.size == 2) module[1] else v["name"] as? String
            if (g.isNullOrBlank() || a.isNullOrBlank()) {
                warn.add("Library '$key' tanpa group/name (atau module)")
                return null
            }
            // version.ref = "x"  atau  version = { ref = "x" }  atau  version = "1.0"  atau  version = { strictly = ... }
            val ref = (v["version.ref"] as? String) ?: ((v["version"] as? Map<*, *>)?.get("ref") as? String)
            val ver = if (ref != null) {
                versions[ref] ?: versions[norm(ref)] ?: run {
                    warn.add("Library '$key': versi '$ref' tidak ada di [versions]")
                    ""
                }
            } else {
                versionText(v["version"]) ?: ""
            }
            return CatalogLib(g.trim(), a.trim(), ver.trim())
        }

        // ---------- tokenizer TOML kecil ----------

        private fun stripComment(line: String): String {
            var q = 0.toChar()
            for (i in line.indices) {
                val ch = line[i]
                if (q != 0.toChar()) { if (ch == q) q = 0.toChar() }
                else if (ch == '"' || ch == '\'') q = ch
                else if (ch == '#') return line.substring(0, i)
            }
            return line
        }

        private fun bracketDelta(line: String): Int {
            var q = 0.toChar()
            var d = 0
            for (ch in line) {
                if (q != 0.toChar()) { if (ch == q) q = 0.toChar() }
                else if (ch == '"' || ch == '\'') q = ch
                else if (ch == '[' || ch == '{') d++
                else if (ch == ']' || ch == '}') d--
            }
            return d
        }

        /** Satukan pernyataan yang terpecah ke beberapa baris (array bundle) menjadi satu baris. */
        private fun statements(raw: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var depth = 0
            for (line0 in raw.lines()) {
                val line = stripComment(line0).trim()
                if (line.isEmpty() && cur.isEmpty()) continue
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(line)
                depth += bracketDelta(line)
                if (depth <= 0) {
                    out.add(cur.toString().trim())
                    cur.setLength(0)
                    depth = 0
                }
            }
            if (cur.isNotEmpty()) out.add(cur.toString().trim())
            return out
        }

        /** Pembaca nilai: string, tabel inline `{ a = "x", b.c = "y" }`, array `[ "a", "b" ]`, atau token polos. */
        private class Reader(private val s: String) {
            private var i = 0

            private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

            fun value(): Any? {
                ws()
                if (i >= s.length) throw IllegalArgumentException("nilai kosong")
                return when (s[i]) {
                    '"', '\'' -> str()
                    '{' -> table()
                    '[' -> array()
                    else -> bare()
                }
            }

            private fun str(): String {
                val q = s[i++]
                val sb = StringBuilder()
                while (i < s.length && s[i] != q) {
                    if (q == '"' && s[i] == '\\' && i + 1 < s.length) {
                        i++
                        sb.append(when (s[i]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i] })
                        i++
                    } else {
                        sb.append(s[i++])
                    }
                }
                if (i >= s.length) throw IllegalArgumentException("kutip tidak ditutup")
                i++
                return sb.toString()
            }

            private fun bare(): String {
                val st = i
                while (i < s.length && s[i] !in ",]}" && !s[i].isWhitespace()) i++
                if (i == st) throw IllegalArgumentException("token tidak dikenal di posisi $i")
                return s.substring(st, i)
            }

            private fun key(): String {
                ws()
                if (i < s.length && (s[i] == '"' || s[i] == '\'')) {
                    val k = str()
                    ws()
                    return k
                }
                val st = i
                while (i < s.length && s[i] != '=') i++
                return s.substring(st, i).trim()
            }

            private fun table(): Map<String, Any?> {
                i++   // {
                val m = LinkedHashMap<String, Any?>()
                while (true) {
                    ws()
                    if (i >= s.length) throw IllegalArgumentException("tabel tidak ditutup")
                    if (s[i] == '}') { i++; break }
                    val k = key()
                    if (i >= s.length || s[i] != '=') throw IllegalArgumentException("'=' hilang setelah $k")
                    i++
                    m[k] = value()
                    ws()
                    if (i < s.length && s[i] == ',') i++
                }
                return m
            }

            private fun array(): List<Any?> {
                i++   // [
                val l = ArrayList<Any?>()
                while (true) {
                    ws()
                    if (i >= s.length) throw IllegalArgumentException("array tidak ditutup")
                    if (s[i] == ']') { i++; break }
                    l.add(value())
                    ws()
                    if (i < s.length && s[i] == ',') i++
                }
                return l
            }
        }
    }
}
