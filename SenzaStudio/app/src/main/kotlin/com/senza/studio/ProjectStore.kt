package com.senza.studio

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Pengaturan build per proyek (disimpan di senza.properties). */
data class BuildConfig(
    val appName: String,
    val versionCode: Int,
    val versionName: String,
    val release: Boolean,
    /** Release saja: kecilkan dan acak kode dengan R8. */
    val shrink: Boolean = false,
    /** Product flavor dari build.gradle ("" = tanpa flavor). */
    val flavor: String = "",
    /** Nama keystore untuk tanda tangan Release ("" = keystore bawaan, hanya uji coba). */
    val signing: String = "",
    /** Release saja: buat juga bundel .aab (eksperimental). */
    val bundle: Boolean = false
)

/** Mengelola banyak proyek di filesDir/forge/projects/<nama>. */
class ProjectStore(private val ctx: Context) {

    companion object {
        val TEXT_EXT = setOf(
            "java", "xml", "txt", "md", "json", "properties", "gradle", "kts", "kt", "pro", "html", "css", "js", "csv", "yml", "yaml"
        )

        fun isTextFile(f: File) = f.extension.lowercase() in TEXT_EXT || f.name.startsWith(".git")

        /** Saran package dari nama aplikasi: "Kalkulator Ku" -> com.example.kalkulatorku */
        fun suggestPackage(appName: String): String {
            var seg = appName.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
            if (seg.isEmpty()) seg = "app"
            if (seg[0].isDigit()) seg = "app$seg"
            if (seg in Highlighter.JAVA_KEYWORDS) seg += "app"
            return "com.example.$seg"
        }

        fun humanSize(n: Long): String = when {
            n < 1024 -> "$n B"
            n < 1024 * 1024 -> "${n / 1024} KB"
            else -> String.format("%.1f MB", n / 1048576.0)
        }
    }

    val base = File(ctx.filesDir, "forge").apply { mkdirs() }
    val root = File(base, "projects").apply { mkdirs() }

    fun list(): List<String> =
        root.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    fun dir(name: String) = File(root, name)

    fun packageOf(name: String): String {
        val m = File(dir(name), "AndroidManifest.xml").takeIf { it.exists() }?.readText() ?: return ""
        return Regex("package\\s*=\\s*\"([^\"]+)\"").find(m)?.groupValues?.get(1) ?: ""
    }

    fun create(name: String, pkg: String): File {
        require(Regex("[A-Za-z0-9][A-Za-z0-9 _-]{0,39}").matches(name)) {
            "Nama hanya boleh huruf, angka, spasi, _ atau - (maksimal 40 karakter)."
        }
        require(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+").matches(pkg)) {
            "Package tidak valid. Contoh: com.contoh.app (huruf kecil, dipisah titik)."
        }
        require(pkg.split('.').none { it in Highlighter.JAVA_KEYWORDS }) {
            "Package tidak boleh memakai kata kunci Java."
        }
        val d = dir(name)
        require(!d.exists()) { "Proyek '$name' sudah ada." }
        copyTemplate("template", d, "", pkg, name)
        File(d, "build.gradle").takeIf { !it.exists() }?.writeText(GradleLite.defaultFile())
        return d
    }

    // ---------- berkas ----------

    /** Semua file .java dan .xml di proyek (tanpa folder out), path relatif. */
    fun files(name: String): List<String> {
        val d = dir(name)
        return d.walkTopDown()
            .onEnter { it == d || it.name != "out" }
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt" || it.extension == "xml") }
            .map { it.relativeTo(d).path }
            .sorted()
            .toList()
    }

    fun layouts(name: String): List<String> =
        files(name).filter { it.startsWith("res/layout/") && it.endsWith(".xml") }

    fun newFile(project: String, rel: String, content: String? = null): File {
        require(rel.isNotBlank() && !rel.contains("..") && !rel.startsWith("/")) { "Path tidak valid." }
        require(rel.endsWith(".java") || rel.endsWith(".kt") || rel.endsWith(".xml")) { "Hanya file .java, .kt, atau .xml." }
        require(!(rel.endsWith(".java") || rel.endsWith(".kt")) || rel.startsWith("src/")) { "File .java dan .kt harus di dalam src/." }
        val f = File(dir(project), rel)
        require(!f.exists()) { "File itu sudah ada." }
        f.parentFile?.mkdirs()
        f.writeText(content ?: skeleton(rel))
        return f
    }

    private fun skeleton(rel: String): String {
        if (rel.endsWith(".java")) {
            val parts = rel.removePrefix("src/").removeSuffix(".java").split('/')
            val cls = parts.last()
            val pkg = parts.dropLast(1).joinToString(".")
            val header = if (pkg.isEmpty()) "" else "package $pkg;\n\n"
            return "${header}public class $cls {\n\n}\n"
        }
        if (rel.endsWith(".kt")) {
            val parts = rel.removePrefix("src/").removeSuffix(".kt").split('/')
            val cls = parts.last()
            val pkg = parts.dropLast(1).joinToString(".")
            val header = if (pkg.isEmpty()) "" else "package $pkg\n\n"
            return "${header}class $cls {\n\n}\n"
        }
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        if (rel.startsWith("res/layout/")) {
            return xml + "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"match_parent\"\n" +
                "    android:orientation=\"vertical\">\n\n</LinearLayout>\n"
        }
        return if (rel.startsWith("res/values/")) "$xml<resources>\n\n</resources>\n" else xml
    }

    // ---------- pengaturan build ----------

    private fun configFile(name: String) = File(dir(name), "senza.properties")

    fun appLabel(name: String): String {
        val f = File(dir(name), "res/values/strings.xml")
        if (!f.exists()) return name
        return Regex("<string\\s+name=\"app_name\"[^>]*>([^<]*)</string>").find(f.readText())
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: name
    }

    /** Ubah teks app_name di strings.xml. Mengembalikan false jika tidak ditemukan. */
    fun setAppLabel(name: String, label: String): Boolean {
        val f = File(dir(name), "res/values/strings.xml")
        if (!f.exists()) return false
        val text = f.readText()
        val re = Regex("(<string\\s+name=\"app_name\"[^>]*>)[^<]*(</string>)")
        if (!re.containsMatchIn(text)) return false
        val safe = label.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'")
        val m = re.find(text) ?: return false
        val out = text.substring(0, m.range.first) + m.groupValues[1] + safe + m.groupValues[2] +
            text.substring(m.range.last + 1)
        if (out != text) f.writeText(out)
        return true
    }

    fun config(name: String): BuildConfig {
        val p = Properties()
        configFile(name).takeIf { it.exists() }?.inputStream()?.use { p.load(it) }
        val g = try { GradleLite.read(dir(name)) } catch (_: Exception) { GradleInfo() }
        return BuildConfig(
            appName = appLabel(name),
            versionCode = p.getProperty("versionCode")?.toIntOrNull()?.coerceAtLeast(1) ?: g.versionCode?.coerceAtLeast(1) ?: 1,
            versionName = p.getProperty("versionName")?.takeIf { it.isNotBlank() } ?: g.versionName?.takeIf { it.isNotBlank() } ?: "1.0.0",
            release = p.getProperty("type") == "release",
            shrink = p.getProperty("shrink")?.let { it == "true" } ?: (g.minifyEnabled == true),
            flavor = p.getProperty("flavor")?.takeIf { it in g.flavors } ?: "",
            signing = p.getProperty("signing") ?: "",
            bundle = p.getProperty("bundle") == "true"
        )
    }

    fun saveConfig(name: String, c: BuildConfig) {
        val p = Properties()
        p.setProperty("versionCode", c.versionCode.toString())
        p.setProperty("versionName", c.versionName)
        p.setProperty("type", if (c.release) "release" else "debug")
        p.setProperty("shrink", c.shrink.toString())
        p.setProperty("flavor", c.flavor)
        p.setProperty("signing", c.signing)
        p.setProperty("bundle", c.bundle.toString())
        configFile(name).outputStream().use { p.store(it, "Senza Studio build config") }
    }

    // ---------- impor / ekspor ----------

    fun exportZip(name: String, out: OutputStream) {
        val d = dir(name)
        ZipOutputStream(out.buffered()).use { zo ->
            d.walkTopDown()
                .onEnter { it == d || it.name != "out" }
                .filter { it.isFile }
                .forEach { f ->
                    zo.putNextEntry(ZipEntry(name + "/" + f.relativeTo(d).path.replace('\\', '/')))
                    f.inputStream().use { it.copyTo(zo) }
                    zo.closeEntry()
                }
        }
    }

    /** Impor proyek dari zip. Mengembalikan nama proyek baru. */
    fun importZip(input: InputStream, suggestedName: String): String {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zi ->
            while (true) {
                val e = zi.nextEntry ?: break
                if (e.isDirectory) continue
                val bytes = zi.readBytes()
                total += bytes.size
                require(total < 200L * 1024 * 1024) { "Zip terlalu besar." }
                entries[e.name.replace('\\', '/')] = bytes
            }
        }
        val files = LinkedHashMap<String, ByteArray>()   // path relatif di proyek -> isi
        val gradleManifest = entries.keys
            .filter { (it == "src/main/AndroidManifest.xml" || it.endsWith("/src/main/AndroidManifest.xml")) && !it.contains("/build/") }
            .minByOrNull { it.count { c -> c == '/' } }
        if (gradleManifest != null) {
            // proyek bergaya Gradle (app/src/main/...) -> disusun ulang ke struktur Senza
            val modPrefix = gradleManifest.removeSuffix("src/main/AndroidManifest.xml")
            val main = modPrefix + "src/main/"
            for ((path, bytes) in entries) {
                if (!path.startsWith(modPrefix) || path.contains("/build/")) continue
                val rel: String? = when {
                    path == main + "AndroidManifest.xml" -> "AndroidManifest.xml"
                    path.startsWith(main + "java/") -> "src/" + path.removePrefix(main + "java/")
                    path.startsWith(main + "kotlin/") -> "src/" + path.removePrefix(main + "kotlin/")
                    path.startsWith(main + "res/") -> "res/" + path.removePrefix(main + "res/")
                    path.startsWith(main + "assets/") -> "assets/" + path.removePrefix(main + "assets/")
                    path.startsWith(main + "jniLibs/") -> "jniLibs/" + path.removePrefix(main + "jniLibs/")
                    path == modPrefix + "build.gradle" -> "build.gradle"
                    path == modPrefix + "build.gradle.kts" -> "build.gradle.kts"
                    path.startsWith(modPrefix + "libs/") && path.endsWith(".jar") -> "libs/" + path.removePrefix(modPrefix + "libs/")
                    else -> null
                }
                if (rel != null && rel.isNotEmpty()) files[rel] = bytes
            }
        } else {
            val manifest = entries.keys
                .filter { it == "AndroidManifest.xml" || it.endsWith("/AndroidManifest.xml") }
                .minByOrNull { it.count { c -> c == '/' } }
            require(manifest != null && manifest.count { it == '/' } <= 1) {
                "Zip ini bukan proyek Senza Studio atau proyek Gradle standar (AndroidManifest.xml tidak ditemukan)."
            }
            val prefix = manifest.removeSuffix("AndroidManifest.xml")
            for ((path, bytes) in entries) {
                if (!path.startsWith(prefix)) continue
                val rel = path.removePrefix(prefix)
                if (rel.isEmpty() || rel.startsWith("out/")) continue
                files[rel] = bytes
            }
        }

        var clean = suggestedName.removeSuffix(".zip").filter { it.isLetterOrDigit() || it in " _-" }.trim().take(40)
        if (clean.isEmpty()) clean = "Imported"
        var name = clean
        var n = 2
        while (dir(name).exists()) name = "$clean-${n++}"

        val dest = dir(name).apply { mkdirs() }
        val destCanon = dest.canonicalPath
        for ((rel, bytes) in files) {
            val f = File(dest, rel)
            require(f.canonicalPath.startsWith(destCanon + File.separator)) { "Zip berisi path berbahaya." }
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
        }
        return name
    }

    private fun copyTemplate(assetPath: String, destRoot: File, rel: String, pkg: String, name: String) {
        val children = ctx.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            val outRel = rel.replace("com/example/hello", pkg.replace('.', '/'))
            val text = ctx.assets.open(assetPath).bufferedReader().use { it.readText() }
                .replace("com.example.hello", pkg)
                .replace("__APP_NAME__", name)
            File(destRoot, outRel).apply { parentFile?.mkdirs() }.writeText(text)
        } else {
            children.forEach {
                copyTemplate("$assetPath/$it", destRoot, if (rel.isEmpty()) it else "$rel/$it", pkg, name)
            }
        }
    }
}
