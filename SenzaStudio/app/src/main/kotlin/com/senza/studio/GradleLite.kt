package com.senza.studio

import java.io.File

/** Satu koordinat Maven: group:artifact:version. */
data class MavenCoord(val group: String, val artifact: String, val version: String) {
    val key get() = "$group:$artifact"
    override fun toString() = "$group:$artifact:$version"
}

/** Satu baris buildConfigField: tipe Java, nama, dan nilai (literal Java persis seperti ditulis). */
class BuildField(val type: String, val name: String, val value: String)

/** Pengaturan satu build type atau product flavor. */
class VariantCfg {
    var idSuffix: String? = null
    var versionSuffix: String? = null
    val fields = LinkedHashMap<String, BuildField>()
}

/** Hasil pembacaan build.gradle (hanya bagian yang dipahami Senza Studio). */
class GradleInfo {
    var namespace: String? = null
    var applicationId: String? = null
    var versionCode: Int? = null
    var versionName: String? = null
    var minSdk: Int? = null
    var targetSdk: Int? = null
    var compileSdk: Int? = null
    var minifyEnabled: Boolean? = null
    val dependencies = ArrayList<MavenCoord>()
    val warnings = ArrayList<String>()
    /** buildConfigField di defaultConfig. */
    val defaultFields = LinkedHashMap<String, BuildField>()
    /** Isi blok buildTypes (debug, release, dst.). */
    val buildTypes = LinkedHashMap<String, VariantCfg>()
    /** Isi blok productFlavors (tanpa dimensi; flavor dipilih satu per build). */
    val flavors = LinkedHashMap<String, VariantCfg>()
}

/**
 * "Gradle-lite": membaca subset kecil build.gradle (Groovy) dan build.gradle.kts.
 * Yang dipahami: namespace, applicationId, versionCode, versionName, minSdk, targetSdk,
 * compileSdk, dan baris dependencies (implementation / api / runtimeOnly) berbentuk teks "g:a:v".
 * Yang dilewati dengan peringatan: platform/BOM, project(...), version catalog (libs.xxx), file tree.
 */
object GradleLite {

    private val CONFIGS = setOf("implementation", "api", "runtimeOnly", "compile", "runtime")
    private val PROCESSOR_CONFIGS = setOf("annotationProcessor", "kapt", "ksp")
    private val IGNORED_CONFIGS = setOf(
        "testImplementation", "androidTestImplementation", "debugImplementation", "releaseImplementation",
        "compileOnly", "annotationProcessor", "kapt", "ksp", "testRuntimeOnly", "testCompileOnly",
        "androidTestRuntimeOnly", "lintChecks", "coreLibraryDesugaring"
    )

    fun fileOf(proj: File): File? =
        listOf("build.gradle", "build.gradle.kts").map { File(proj, it) }.firstOrNull { it.isFile }

    fun read(proj: File): GradleInfo {
        val f = fileOf(proj) ?: return GradleInfo()
        return parse(f.readText())
    }

    fun parse(raw: String): GradleInfo {
        val info = GradleInfo()
        val text = stripComments(raw)

        fun str(vararg keys: String): String? {
            for (k in keys) {
                // namespace = "x"   namespace "x"   namespace("x")   applicationId 'x'
                val m = Regex("""(?m)^\s*$k\s*(?:=|\(|\s)\s*["']([^"']+)["']""").find(text)
                if (m != null) return m.groupValues[1]
            }
            return null
        }
        fun int(vararg keys: String): Int? {
            for (k in keys) {
                val m = Regex("""(?m)^\s*$k\s*(?:=|\(|\s)\s*(\d+)""").find(text)
                if (m != null) return m.groupValues[1].toIntOrNull()
            }
            return null
        }

        info.namespace = str("namespace")
        info.applicationId = str("applicationId")
        info.versionName = str("versionName")
        info.versionCode = int("versionCode")
        info.minSdk = int("minSdk", "minSdkVersion")
        info.targetSdk = int("targetSdk", "targetSdkVersion")
        info.compileSdk = int("compileSdk", "compileSdkVersion")
        val minify = Regex("""(?m)^\s*(?:is)?[mM]inify(?:Enabled)?\s*(?:=|\s)\s*(true|false)""").findAll(text).toList()
        if (minify.isNotEmpty()) info.minifyEnabled = minify.any { it.groupValues[1] == "true" }

        val seen = HashSet<String>()
        for (line in text.lines()) {
            val t = line.trim()
            val m = Regex("""^([A-Za-z]+)\s*[\s(]\s*(.+)$""").find(t) ?: continue
            val conf = m.groupValues[1]
            val rest = m.groupValues[2].trim()
            if (conf in PROCESSOR_CONFIGS) {
                info.warnings.add("$conf belum didukung (annotation processing belum ada). Library yang butuh pemroses seperti Room, Hilt, atau Glide compiler tidak akan jalan: $t")
                continue
            }
            if (conf in IGNORED_CONFIGS) continue
            if (conf !in CONFIGS) continue

            val lit = Regex("""^["']([^"']+)["']""").find(rest)
            if (lit != null) {
                val parts = lit.groupValues[1].split(':')
                if (parts.size >= 3 && parts[2].isNotBlank()) {
                    val c = MavenCoord(parts[0].trim(), parts[1].trim(), parts[2].trim())
                    if (seen.add(c.toString())) info.dependencies.add(c)
                } else {
                    info.warnings.add("Dependency tanpa versi dilewati: ${lit.groupValues[1]}")
                }
                continue
            }
            when {
                rest.startsWith("platform(") || rest.startsWith("enforcedPlatform(") ->
                    info.warnings.add("BOM/platform belum didukung, tulis versi tiap library: $t")
                rest.startsWith("project(") ->
                    info.warnings.add("Modul lain (project(...)) belum didukung: $t")
                rest.startsWith("files(") || rest.startsWith("fileTree(") ->
                    info.warnings.add("files()/fileTree() belum didukung. Taruh .jar di folder libs/ proyek.")
                rest.startsWith("libs.") ->
                    info.warnings.add("Version catalog (libs.xxx) belum didukung, tulis \"group:artifact:versi\": $t")
                else -> if (Regex("""^[A-Za-z]""").containsMatchIn(rest))
                    info.warnings.add("Baris dependency tidak dikenali: $t")
            }
        }
        parseVariants(text, info)
        return info
    }

    // ---------- build types, product flavors, buildConfigField ----------

    private fun matchBraces(text: String, open: Int): String {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(open + 1, i) }
            }
            i++
        }
        return text.substring(open + 1)
    }

    private fun blockBody(text: String, name: String): String? {
        val m = Regex("""(?:^|[\s;}])$name\s*\{""").find(text) ?: return null
        return matchBraces(text, m.range.last)
    }

    /** Blok anak tingkat atas: `release {`, `create("staging") {`, `getByName("debug") {`. */
    private fun childBlocks(body: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        var headStart = 0
        while (i < body.length) {
            val ch = body[i]
            if (ch == '{') {
                val head = body.substring(headStart, i).trim()
                val inner = matchBraces(body, i)
                val m = Regex("""(?:create|register|named|getByName|maybeCreate)\s*\(\s*["']([^"']+)["']\s*\)|^([A-Za-z_][A-Za-z0-9_]*)$""").find(head)
                val key = m?.groupValues?.get(1)?.ifEmpty { null } ?: m?.groupValues?.get(2)?.ifEmpty { null }
                if (key != null) out.add(key to inner)
                i += inner.length + 1          // loncat ke kurung tutup pasangannya
                headStart = i + 1
            } else if (ch == '\n' || ch == ';') headStart = i + 1
            i++
        }
        return out
    }

    private fun strIn(body: String, key: String): String? =
        Regex("""(?m)^\s*$key\s*(?:=|\()?\s*["']([^"']*)["']""").find(body)?.groupValues?.get(1)

    private fun unquote(tok: String): String {
        val t = tok.trim().removeSuffix(")").trim()
        if (t.length >= 2 && (t[0] == '"' || t[0] == '\'') && t.last() == t[0]) {
            return t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return t
    }

    private fun readFields(body: String, into: MutableMap<String, BuildField>, warn: MutableList<String>) {
        val re = Regex("""(?m)buildConfigField\s*\(?\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*,\s*(.+?)\s*\)?\s*$""")
        for (m in re.findAll(body)) {
            val type = m.groupValues[1].trim()
            val name = m.groupValues[2].trim()
            val value = unquote(m.groupValues[3])
            if (!Regex("[A-Za-z0-9_.<>\\[\\]]+").matches(type) || !Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name) || value.isEmpty()) {
                warn.add("buildConfigField tidak valid dilewati: ${m.value.trim()}")
                continue
            }
            into[name] = BuildField(type, name, value)
        }
    }

    private fun readVariant(body: String, info: GradleInfo): VariantCfg = VariantCfg().also { v ->
        v.idSuffix = strIn(body, "applicationIdSuffix")
        v.versionSuffix = strIn(body, "versionNameSuffix")
        readFields(body, v.fields, info.warnings)
    }

    private fun parseVariants(text: String, info: GradleInfo) {
        blockBody(text, "defaultConfig")?.let { readFields(it, info.defaultFields, info.warnings) }
        blockBody(text, "buildTypes")?.let { b ->
            childBlocks(b).forEach { (n, body) -> info.buildTypes[n] = readVariant(body, info) }
        }
        blockBody(text, "productFlavors")?.let { b ->
            childBlocks(b).forEach { (n, body) ->
                if (Regex("""(?m)^\s*dimension\s*(?:=)?\s*["']""").containsMatchIn(body))
                    info.warnings.add("Flavor '$n' memakai dimension; Senza Studio memilih satu flavor per build (tanpa kombinasi dimensi).")
                info.flavors[n] = readVariant(body, info)
            }
        }
    }

    private fun stripComments(s: String): String {
        val noBlock = s.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        return noBlock.lines().joinToString("\n") { l ->
            // hapus // di luar tanda kutip (cukup untuk kasus umum)
            var inQ = 0.toChar()
            var cut = -1
            var i = 0
            while (i < l.length) {
                val ch = l[i]
                if (inQ != 0.toChar()) { if (ch == inQ) inQ = 0.toChar() }
                else if (ch == '"' || ch == '\'') inQ = ch
                else if (ch == '/' && i + 1 < l.length && l[i + 1] == '/') { cut = i; break }
                i++
            }
            if (cut >= 0) l.substring(0, cut) else l
        }
    }

    /** Tambah satu dependency ke build.gradle (buat filenya jika belum ada). */
    fun addDependency(proj: File, coord: String): String {
        val parts = coord.trim().split(':')
        require(parts.size == 3 && parts.none { it.isBlank() } && coord.none { it.isWhitespace() || it == '"' || it == '\'' }) {
            "Format harus group:artifact:versi, contoh androidx.appcompat:appcompat:1.6.1"
        }
        val gradle = fileOf(proj) ?: File(proj, "build.gradle").also { it.writeText(defaultFile()) }
        val kts = gradle.name.endsWith(".kts")
        val line = if (kts) "    implementation(\"$coord\")" else "    implementation '$coord'"
        val text = gradle.readText()
        if (read(proj).dependencies.any { it.key == "${parts[0]}:${parts[1]}" })
            throw IllegalArgumentException("Library ${parts[0]}:${parts[1]} sudah ada di ${gradle.name}.")
        val idx = Regex("""dependencies\s*\{""").find(text)
        val out = if (idx != null) {
            val at = idx.range.last + 1
            text.substring(0, at) + "\n" + line + text.substring(at)
        } else {
            text.trimEnd() + "\n\ndependencies {\n$line\n}\n"
        }
        gradle.writeText(out)
        return gradle.name
    }

    fun defaultFile(): String =
        "// Senza Studio membaca subset kecil dari file ini (Gradle-lite).\n" +
            "// Contoh: implementation 'androidx.appcompat:appcompat:1.6.1'\n" +
            "dependencies {\n}\n"
}
