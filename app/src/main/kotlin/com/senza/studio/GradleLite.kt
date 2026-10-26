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

/** BOM / platform(...) yang mengatur versi library lain. [enforced] = enforcedPlatform (versi dipaksa). */
class PlatformDep(val coord: MavenCoord, val enforced: Boolean)

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
    /** Dependency tanpa versi (versinya datang dari BOM). MavenCoord.version kosong. */
    val unversioned = ArrayList<MavenCoord>()
    /** Daftar platform(...) / enforcedPlatform(...). */
    val platforms = ArrayList<PlatformDep>()
    /** coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:x"), null jika tidak ada. */
    var desugarLib: MavenCoord? = null
    /** isCoreLibraryDesugaringEnabled di compileOptions (null = tidak ditulis). */
    var desugarFlag: Boolean? = null
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
 * Fase 5: platform(...)/enforcedPlatform(...) (BOM), dependency tanpa versi, version catalog
 * (gradle/libs.versions.toml: libs.xxx, libs.bundles.xxx), dan coreLibraryDesugaring.
 * Yang dilewati dengan peringatan: project(...), file tree.
 */
object GradleLite {

    private val CONFIGS = setOf("implementation", "api", "runtimeOnly", "compile", "runtime")
    private val PROCESSOR_CONFIGS = setOf("annotationProcessor", "kapt", "ksp")
    private val IGNORED_CONFIGS = setOf(
        "testImplementation", "androidTestImplementation", "debugImplementation", "releaseImplementation",
        "compileOnly", "annotationProcessor", "kapt", "ksp", "testRuntimeOnly", "testCompileOnly",
        "androidTestRuntimeOnly", "lintChecks"
    )

    fun fileOf(proj: File): File? =
        listOf("build.gradle", "build.gradle.kts").map { File(proj, it) }.firstOrNull { it.isFile }

    fun read(proj: File): GradleInfo {
        val f = fileOf(proj) ?: return GradleInfo()
        return parse(f.readText(), VersionCatalog.load(proj))
    }

    fun parse(raw: String, catalog: VersionCatalog? = null): GradleInfo {
        val info = GradleInfo()
        catalog?.warnings?.forEach { info.warnings.add("libs.versions.toml: $it") }
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
        Regex("""(?m)^\s*(?:isCoreLibraryDesugaringEnabled|coreLibraryDesugaringEnabled)\s*(?:=|\s)\s*(true|false)""")
            .find(text)?.let { info.desugarFlag = it.groupValues[1] == "true" }

        val seen = HashSet<String>()
        val seenManaged = HashSet<String>()
        for (line in text.lines()) {
            val t = line.trim()
            val m = Regex("""^([A-Za-z]+)\s*[\s(]\s*(.+)$""").find(t) ?: continue
            val conf = m.groupValues[1]
            val rest = m.groupValues[2].trim()
            if (conf in PROCESSOR_CONFIGS) {
                info.warnings.add("$conf belum didukung (annotation processing belum ada). Library yang butuh pemroses seperti Room, Hilt, atau Glide compiler tidak akan jalan: $t")
                continue
            }
            if (conf == "coreLibraryDesugaring") {
                val c = readArgs(rest, catalog, info, t).firstOrNull { !it.platform }?.coord
                if (c != null && c.version.isNotBlank()) info.desugarLib = c
                else info.warnings.add("coreLibraryDesugaring tanpa versi dilewati: $t")
                continue
            }
            if (conf in IGNORED_CONFIGS) continue
            if (conf !in CONFIGS) continue

            for (a in readArgs(rest, catalog, info, t)) {
                val c = a.coord
                when {
                    a.platform ->
                        if (c.version.isBlank()) info.warnings.add("platform(...) harus punya versi: $t")
                        else if (info.platforms.none { it.coord.toString() == c.toString() }) info.platforms.add(PlatformDep(c, a.enforced))
                    c.version.isBlank() -> if (seenManaged.add(c.key)) info.unversioned.add(c)
                    else -> if (seen.add(c.toString())) info.dependencies.add(c)
                }
            }
        }
        parseVariants(text, info)
        return info
    }

    // ---------- argumen dependency: "g:a:v", platform(...), libs.xxx ----------

    private class Arg(val coord: MavenCoord, val platform: Boolean = false, val enforced: Boolean = false)

    /** Ubah sisi kanan satu baris dependency menjadi daftar koordinat (bundle bisa menghasilkan banyak). */
    private fun readArgs(rest: String, catalog: VersionCatalog?, info: GradleInfo, line: String): List<Arg> {
        val enforced = rest.startsWith("enforcedPlatform(")
        if (rest.startsWith("platform(") || enforced) {
            val inner = rest.substringAfter('(').trim()
            return readArgs(inner, catalog, info, line).map { Arg(it.coord, true, enforced) }
        }

        val lit = Regex("""^["']([^"']+)["']""").find(rest)
        if (lit != null) {
            var spec = lit.groupValues[1].substringBefore('@')
            if (spec.contains('$') && catalog != null) {
                // "g:a:${libs.versions.xxx.get()}"
                spec = Regex("""\$\{?libs\.versions\.([A-Za-z0-9_.]+?)\.get\(\)\}?""").replace(spec) { mm ->
                    catalog.version(mm.groupValues[1]) ?: mm.value
                }
            }
            if (spec.contains('$')) {
                info.warnings.add("Versi memakai variabel yang belum didukung, tulis versinya langsung: $line")
                return emptyList()
            }
            val parts = spec.split(':').map { it.trim() }
            return when {
                parts.size >= 3 && parts[0].isNotEmpty() && parts[1].isNotEmpty() && parts[2].isNotEmpty() ->
                    listOf(Arg(MavenCoord(parts[0], parts[1], parts[2])))
                parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty() ->
                    listOf(Arg(MavenCoord(parts[0], parts[1], "")))   // versi dari BOM
                else -> {
                    info.warnings.add("Dependency tidak valid dilewati: ${lit.groupValues[1]}")
                    emptyList()
                }
            }
        }

        val ref = Regex("""^libs\.([A-Za-z0-9_.]+)""").find(rest)
        if (ref != null) {
            val key = ref.groupValues[1].removeSuffix(".get").removeSuffix(".asProvider")
            if (key.startsWith("versions.") || key.startsWith("plugins.")) return emptyList()
            if (catalog == null) {
                info.warnings.add("libs.$key dipakai tetapi gradle/libs.versions.toml tidak ditemukan: $line")
                return emptyList()
            }
            if (key.startsWith("bundles.")) {
                val list = catalog.bundle(key.removePrefix("bundles."))
                if (list.isEmpty()) info.warnings.add("Bundle tidak ditemukan atau kosong di libs.versions.toml: libs.$key")
                return list.map { Arg(it.coord()) }
            }
            val l = catalog.library(key)
            if (l == null) {
                info.warnings.add("Alias libs.$key tidak ada di libs.versions.toml")
                return emptyList()
            }
            return listOf(Arg(l.coord()))
        }

        when {
            rest.startsWith("project(") ->
                info.warnings.add("Modul lain (project(...)) belum didukung: $line")
            rest.startsWith("files(") || rest.startsWith("fileTree(") ->
                info.warnings.add("files()/fileTree() belum didukung. Taruh .jar di folder libs/ proyek.")
            Regex("""^[A-Za-z]""").containsMatchIn(rest) ->
                info.warnings.add("Baris dependency tidak dikenali: $line")
        }
        return emptyList()
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
        val cur = read(proj)
        if (cur.dependencies.any { it.key == "${parts[0]}:${parts[1]}" } || cur.unversioned.any { it.key == "${parts[0]}:${parts[1]}" })
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
