package com.senza.studio

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Kotlin di perangkat (Fase 2).
 *
 * Compiler Kotlin (kotlin-compiler-embeddable) ikut terpasang di dalam APK Senza Studio, tetapi
 * TIDAK dijalankan di proses aplikasi. Builder memanggil `app_process` untuk memulai proses baru
 * yang memuat APK ini dan menjalankan [KotlinCompilerMain]. Keuntungannya:
 *  - heap-nya terpisah dan bisa diatur (Settings > Alat build),
 *  - bila memori habis, hanya proses compiler yang mati; Senza Studio tetap hidup,
 *  - tombol Batal tinggal mematikan proses itu.
 */
object KotlinSupport {

    const val VERSION = "1.9.24"
    const val STDLIB = "org.jetbrains.kotlin:kotlin-stdlib"

    class Result(val exit: Int, val output: String)

    private val APP_PROCESS = listOf(
        "/system/bin/app_process64", "/system/bin/app_process", "/system/bin/app_process32"
    )

    fun appProcess(): String? = APP_PROCESS.firstOrNull { File(it).exists() }

    fun available(): Boolean = appProcess() != null

    /** Heap proses Kotlin (MB). Pengaturan 0 = otomatis: seperempat RAM, antara 1 dan 3 GB. */
    fun heapMb(ctx: Context): Int {
        val pref = ctx.getSharedPreferences("senza_studio", Context.MODE_PRIVATE).getInt("kotlin_heap_mb", 0)
        if (pref > 0) return pref
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val totalMb = (mi.totalMem / 1048576L).toInt()
        return (totalMb / 4).coerceIn(1024, 3072)
    }

    private fun moduleName(proj: File): String {
        val n = proj.name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
        return if (n.isEmpty()) "app" else n
    }

    /**
     * Jalankan kompiler Kotlin. [sources] boleh berisi .kt dan .java (Kotlin perlu melihat kelas Java
     * di modul yang sama; file .java-nya sendiri tidak dikompilasi di sini, itu tugas ECJ).
     */
    fun compile(
        ctx: Context,
        proj: File,
        sources: List<File>,
        classpath: List<File>,
        outDir: File,
        log: (String) -> Unit,
        onProcess: (Process?) -> Unit
    ): Result {
        val ap = appProcess()
            ?: throw BuildError(BuildError.Kind.TOOLS, "app_process tidak ditemukan di perangkat ini, jadi compiler Kotlin tidak bisa dijalankan.")
        val info = ctx.applicationInfo
        val apkPath = (listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())).joinToString(":")
        val tmp = File(ctx.cacheDir, "kotlinc-tmp").apply { mkdirs() }
        val heap = heapMb(ctx)

        val kotlinArgs = listOf(
            "-no-stdlib", "-no-reflect", "-no-jdk",
            "-jvm-target", "1.8",
            "-module-name", moduleName(proj),
            "-nowarn",
            "-Xno-call-assertions", "-Xno-param-assertions", "-Xno-receiver-assertions",
            "-classpath", classpath.joinToString(File.pathSeparator) { it.path },
            "-d", outDir.path
        ) + sources.map { it.path }

        val cmd = listOf(
            ap,
            "-Djava.class.path=$apkPath",
            "-Djava.io.tmpdir=${tmp.path}",
            "-Xmx${heap}m",
            "/system/bin",
            "com.senza.studio.KotlinCompilerMain"
        ) + kotlinArgs

        log("  Kotlin $VERSION: ${sources.count { it.extension == "kt" }} file .kt, proses terpisah (heap $heap MB)")

        val p = try {
            ProcessBuilder(cmd).directory(proj).redirectErrorStream(true).start()
        } catch (e: IOException) {
            throw BuildError(BuildError.Kind.TOOLS, "Compiler Kotlin tidak bisa dimulai: ${e.message}")
        }
        onProcess(p)
        val text = StringBuilder()
        var code = -1
        try {
            p.inputStream.bufferedReader().forEachLine { l ->
                text.appendLine(l)
                if (l.isNotBlank()) log("  [kotlinc] " + l.take(300))
            }
            code = p.waitFor()
        } finally {
            onProcess(null)
        }
        return Result(code, text.toString())
    }

    /** Ubah keluaran kompiler yang gagal menjadi BuildError yang enak dibaca. */
    fun toError(res: Result, proj: File): BuildError {
        val text = res.output
        if (text.contains("OutOfMemoryError") || res.exit == 137 || res.exit == 9 || res.exit == 4) {
            return BuildError(
                BuildError.Kind.OTHER,
                "Compiler Kotlin kehabisan memori (proses compiler berhenti, Senza Studio tetap jalan).\n" +
                    "Naikkan memori di Settings > Alat build > Memori compiler Kotlin, atau pecah file Kotlin yang sangat besar."
            )
        }
        val m = Regex("""(?m)^(.+?\.kt):(\d+):(\d+): error: (.+)$""").find(text)
        if (m != null) {
            var path = m.groupValues[1]
            if (!File(path).isAbsolute) path = File(proj, path).path
            val line = m.groupValues[2].toInt()
            val msg = m.groupValues[4].trim()
            return BuildError(
                BuildError.Kind.CODE,
                "${File(path).name}, baris $line:\n${friendly(msg)}",
                path, line
            )
        }
        val general = Regex("""(?m)^error: (.+)$""").find(text)?.groupValues?.get(1)
        val crash = Regex("""(?m)^(?:Caused by: |Exception in thread .*?: )?([\w.$]+(?:Exception|Error)(?::.*)?)$""")
            .find(text)?.groupValues?.get(1)
        if (general != null || crash != null) {
            val first = (general ?: crash)!!.take(200)
            return BuildError(
                BuildError.Kind.TOOLS,
                "Compiler Kotlin berhenti: $first\n" +
                    "Jika ini muncul di semua proyek Kotlin, compiler belum cocok dengan perangkat ini. Ketuk 'Lihat detail' dan kirim lognya."
            )
        }
        return BuildError(BuildError.Kind.CODE, "Kode Kotlin tidak bisa dikompilasi (kode ${res.exit}). Ketuk 'Lihat detail'.")
    }

    private fun friendly(msg: String): String = when {
        msg.contains("unresolved reference", ignoreCase = true) ->
            "$msg\n(Nama ini belum dibuat, kurang 'import', atau library-nya belum ada di build.gradle.)"
        msg.contains("binary version of its metadata", ignoreCase = true) ->
            "$msg\n(Library ini dibuat dengan Kotlin yang lebih baru dari compiler Senza Studio ($VERSION). Pakai versi library yang lebih lama.)"
        msg.contains("Cannot access built-in declaration", ignoreCase = true) || msg.contains("kotlin.Unit", ignoreCase = true) ->
            "$msg\n(kotlin-stdlib tidak terbaca. Hapus folder out/ lewat 'Bersihkan cache build' lalu coba lagi.)"
        msg.contains("Expecting") || msg.contains("Unexpected tokens") ->
            "$msg\n(Cek kurung { } ( ) dan tanda kutip yang kurang atau berlebih.)"
        else -> msg
    }
}
