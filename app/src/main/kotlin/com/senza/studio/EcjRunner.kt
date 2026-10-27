package com.senza.studio

import android.content.Context
import dalvik.system.PathClassLoader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.PrintWriter
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.util.Collections
import java.util.Enumeration
import java.util.zip.ZipFile

/**
 * Menjalankan ECJ (compiler Java) lewat class loader sendiri.
 *
 * Kenapa: ECJ membaca banyak berkas resource lewat ClassLoader (messages.properties, tabel parser
 * parser*.rsc, readableNames.props, dst). Di Android, membaca resource yang ada DI DALAM APK lewat
 * class loader bisa gagal dengan SecurityException, yang lalu muncul sebagai
 * "Can't find bundle for base name org.eclipse.jdt.internal.compiler.batch.messages".
 *
 * Caranya di sini: semua resource non-kelas di bawah org/eclipse/jdt/ diekstrak sekali ke folder biasa (filesDir/forge/ecj-res),
 * lalu ECJ dimuat oleh [EcjLoader] yang melayani resource dari folder itu sebagai berkas biasa
 * (tidak lewat jalur baca-dalam-APK). Kelas ECJ tetap diambil dari dex APK yang sama.
 */
object EcjRunner {
    private const val RES_PREFIX = "org/eclipse/jdt/"
    private const val MAIN = "org.eclipse.jdt.internal.compiler.batch.Main"
    private const val PROGRESS = "org.eclipse.jdt.core.compiler.CompilationProgress"

    @Volatile private var loader: EcjLoader? = null

    /**
     * @return true jika kompilasi sukses, false jika ada error kode (pesannya ditulis ke [out]).
     * @throws Exception jika ECJ sendiri tidak bisa dijalankan (pemanggil boleh jatuh ke jalur lama).
     */
    fun compile(ctx: Context, args: Array<String>, out: PrintWriter, log: (String) -> Unit): Boolean {
        val cl = loaderFor(ctx, log)
        try {
            val mainCls = Class.forName(MAIN, true, cl)
            val progressCls = Class.forName(PROGRESS, false, cl)
            val ctor = mainCls.getConstructor(
                PrintWriter::class.java, PrintWriter::class.java,
                java.lang.Boolean.TYPE, Map::class.java, progressCls
            )
            val main = ctor.newInstance(out, out, false, null, null)
            val argv: Any = args
            return mainCls.getMethod("compile", Array<String>::class.java).invoke(main, argv) as Boolean
        } catch (e: InvocationTargetException) {
            throw (e.targetException as? Exception) ?: e
        }
    }

    /**
     * Daftar berkas tanda tangan jar (berekstensi SF, RSA, DSA, EC, dan MANIFEST.MF di folder META-INF) di dalam APK
     * aplikasi ini. Berkas seperti ini yang membuat pembacaan resource lewat class loader melempar
     * SecurityException ("Invalid signature file digest for Manifest main attributes").
     */
    fun strayApkSignatureEntries(ctx: Context): List<String> {
        val out = ArrayList<String>()
        val info = ctx.applicationInfo
        for (apk in listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())) {
            try {
                ZipFile(apk).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val n = entries.nextElement().name
                        if (!n.startsWith("META-INF/") || n.indexOf('/', 9) >= 0) continue
                        if (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC") || n == "META-INF/MANIFEST.MF") out.add(n)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun loaderFor(ctx: Context, log: (String) -> Unit): EcjLoader {
        loader?.let { return it }
        synchronized(this) {
            loader?.let { return it }
            val info = ctx.applicationInfo
            val apks = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
            val resDir = File(ctx.filesDir, "forge/ecj-res")
            val n = prepareResources(apks, resDir, log)
            if (n == 0) throw IllegalStateException("resource ECJ (org/eclipse/jdt/**) tidak ada di dalam APK Senza Studio")
            // Induk = BootClassLoader (induk loader aplikasi). Seluruh kelas non-sistem diambil dari APK.
            val parent = ctx.classLoader.parent ?: ClassLoader.getSystemClassLoader()
            val l = EcjLoader(apks.joinToString(File.pathSeparator), resDir, parent)
            loader = l
            return l
        }
    }

    /** Ekstrak resource ECJ dari APK ke [dir]. Dilewati jika APK tidak berubah sejak ekstrak terakhir. */
    private fun prepareResources(apks: List<String>, dir: File, log: (String) -> Unit): Int {
        val stamp = apks.joinToString("|") { val f = File(it); "${f.path}:${f.length()}:${f.lastModified()}" }
        val stampFile = File(dir, ".stamp")
        if (stampFile.isFile && stampFile.readText() == stamp) {
            return dir.walkTopDown().count { it.isFile && it.name != ".stamp" }
        }
        dir.deleteRecursively()
        dir.mkdirs()
        val root = dir.canonicalPath + File.separator
        var n = 0
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val name = e.name
                    if (e.isDirectory || !name.startsWith(RES_PREFIX) || name.endsWith(".class")) continue
                    val target = File(dir, name)
                    if (!target.canonicalPath.startsWith(root)) continue
                    target.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
                    n++
                }
            }
        }
        if (n > 0) {
            stampFile.writeText(stamp)
            log("  resource ECJ disiapkan ($n berkas)")
        }
        return n
    }

    /**
     * Memuat kelas dari APK dan melayani resource dari folder hasil ekstrak.
     * Induknya BootClassLoader, jadi kelas ECJ yang dipakai adalah salinan milik loader ini
     * (bukan salinan di loader aplikasi), dan Class.getResourceAsStream / ResourceBundle ECJ
     * otomatis lewat sini.
     */
    private class EcjLoader(dexPath: String, private val resDir: File, parent: ClassLoader) :
        PathClassLoader(dexPath, parent) {

        private fun local(name: String): File? {
            if (name.contains("..")) return null
            val f = File(resDir, name)
            return if (f.isFile) f else null
        }

        override fun findResource(name: String): URL? = local(name)?.toURI()?.toURL()

        override fun getResource(name: String): URL? =
            findResource(name) ?: try { super.getResource(name) } catch (_: Throwable) { null }

        override fun getResourceAsStream(name: String): InputStream? {
            local(name)?.let { return FileInputStream(it) }
            return try { super.getResourceAsStream(name) } catch (_: Throwable) { null }
        }

        override fun findResources(name: String): Enumeration<URL> =
            Collections.enumeration(listOfNotNull(findResource(name)))

        override fun getResources(name: String): Enumeration<URL> =
            Collections.enumeration(listOfNotNull(getResource(name)))
    }
}
