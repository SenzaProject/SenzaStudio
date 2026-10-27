package com.senza.studio

import android.content.Context
import com.android.apksig.ApkSigner
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.L8
import com.android.tools.r8.L8Command
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.OutputMode
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Error build dengan jenis, supaya UI bisa menampilkan pesan & tombol yang pas. */
class BuildError(
    val kind: Kind,
    message: String,
    val file: String? = null,
    val line: Int? = null
) : Exception(message) {
    enum class Kind { TOOLS, CODE, RESOURCE, OTHER }
}

/**
 * Pipeline build di perangkat (Fase 1 + 2):
 * dependency Maven -> merge manifest -> aapt2 compile (app + library) -> aapt2 link
 * -> Kotlin (proses terpisah, jika ada file .kt) -> ECJ
 * -> D8 (per library, di-cache) + gabung dex (multi-dex)  ATAU  R8 (release + "Perkecil kode")
 * -> rakit APK -> sign (apksig)
 *
 * Setiap tahap punya sidik jari (hash) masukannya. Jika tidak berubah sejak build terakhir,
 * tahap itu dilewati. Dex library disimpan di cache bersama semua proyek.
 */
class SenzaBuilder(
    private val ctx: Context,
    private val log: (String) -> Unit,
    private val onStep: (Int, String) -> Unit
) {
    companion object {
        const val TOTAL_STEPS = 8
        const val USER_RULES = "proguard-rules.pro"

        /** Aturan R8 bawaan. Aturan untuk Activity/Service/dll dan View di layout dibuat aapt2 (--proguard). */
        val BUILTIN_RULES = """
            # Aturan bawaan Senza Studio (dibuat ulang tiap build, jangan diubah di sini)
            -keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
            -renamesourcefileattribute SourceFile
            -keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }
            -keepclassmembers class * implements android.os.Parcelable { public static final ** CREATOR; }
            -keepclassmembers class * implements java.io.Serializable {
                private static final java.io.ObjectStreamField[] serialPersistentFields;
                private void writeObject(java.io.ObjectOutputStream);
                private void readObject(java.io.ObjectInputStream);
                java.lang.Object writeReplace();
                java.lang.Object readResolve();
            }
            -keep class kotlin.Metadata { *; }
            -dontwarn javax.annotation.**
            -dontwarn org.jetbrains.annotations.**
            -dontwarn org.checkerframework.**
        """.trimIndent() + "\n"

        val DEFAULT_USER_RULES = """
            # Aturan R8/ProGuard milikmu. Dipakai saat Build Release dengan "Perkecil & acak kode".
            # Contoh:
            #   -keep class com.contoh.model.** { *; }      # jangan ubah kelas model (JSON/refleksi)
            #   -dontwarn okio.**                            # abaikan peringatan kelas yang tidak ada
            #   -dontobfuscate                               # kecilkan saja, nama tidak diacak
            # Berkas out/mapping.txt dipakai untuk membaca stack trace yang namanya sudah diacak.
        """.trimIndent() + "\n"
    }

    private val forgeDir = File(ctx.filesDir, "forge")
    private val androidJar = File(forgeDir, "tools/android.jar")

    private val keystores = KeystoreManager(ctx)

    @Volatile private var proc: Process? = null

    fun apkOf(proj: File) = File(proj, "out/app-signed.apk")
    fun bundleOf(proj: File) = File(proj, "out/app-release.aab")
    fun aapt2File() = File(ctx.applicationInfo.nativeLibraryDir, "libaapt2.so")
    fun hasAapt2() = aapt2File().exists()

    fun hasAndroidJar(): Boolean {
        if (androidJar.exists()) return true
        return try { ctx.assets.open("android.jar").close(); true } catch (e: IOException) { false }
    }

    /** Salin android.jar dari assets (hanya jika belum ada). */
    fun prepareTools() {
        if (!androidJar.exists()) {
            androidJar.parentFile?.mkdirs()
            ctx.assets.open("android.jar").use { i -> androidJar.outputStream().use { i.copyTo(it) } }
        }
    }

    /** Hapus cache build proyek (out/) dan, jika diminta, cache dex & unduhan library. */
    fun clean(proj: File, everything: Boolean): String {
        File(proj, "out").deleteRecursively()
        if (everything) {
            File(forgeDir, "dexcache").deleteRecursively()
            File(forgeDir, "m2").deleteRecursively()
            return "Cache proyek, dex library, dan unduhan Maven dihapus."
        }
        return "Cache proyek dihapus."
    }

    fun cacheSize(): Long = listOf("dexcache", "m2").sumOf { dirSize(File(forgeDir, it)) }

    private fun dirSize(d: File): Long = if (!d.exists()) 0 else d.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun checkCancel() {
        if (BuildControl.cancelled) throw BuildError(BuildError.Kind.OTHER, "Build dibatalkan.")
    }

    fun build(proj: File, cfg: BuildConfig): File {
        BuildControl.reset()
        BuildControl.onCancel = { try { proc?.destroy() } catch (_: Throwable) {} }
        try {
            return doBuild(proj, cfg)
        } catch (e: Throwable) {
            val bySignature = generateSequence<Throwable>(e) { it.cause }
                .any { it is SecurityException && (it.message ?: "").contains("signature", ignoreCase = true) }
            if (bySignature) {
                val stray = EcjRunner.strayApkSignatureEntries(ctx)
                log("")
                log("! Penyebab: APK Senza Studio yang terpasang memuat berkas tanda tangan jar (META-INF) yang tidak cocok,")
                log("  sehingga resource library tidak bisa dibaca. Berkas di APK: " + (if (stray.isEmpty()) "(tidak terbaca)" else stray.joinToString(", ")))
                log("  Pasang APK dari build GitHub Actions terbaru lalu coba lagi.")
            }
            throw e
        } finally {
            BuildControl.onCancel = null
            proc = null
        }
    }

    // ---------- sidik jari & cache ----------

    private class FileHasher(private val store: File) {
        private val memo = HashMap<String, String>()
        private val used = HashSet<String>()
        init {
            if (store.isFile) store.forEachLine { l ->
                val i = l.indexOf('\t')
                if (i > 0) memo[l.substring(0, i)] = l.substring(i + 1)
            }
        }
        fun hash(f: File): String {
            val k = "${f.path}|${f.length()}|${f.lastModified()}"
            used.add(k)
            return memo.getOrPut(k) { digestFile(f) }
        }
        fun save() {
            try {
                store.parentFile?.mkdirs()
                store.writeText(used.joinToString("\n") { it + "\t" + memo[it] })
            } catch (_: Exception) {}
        }
    }

    private fun sha1(vararg parts: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        parts.forEach { md.update(it.toByteArray()); md.update(0.toByte()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun filesFp(hasher: FileHasher, base: File, files: List<File>): String =
        sha1(*files.map { it.relativeTo(base).path + ":" + hasher.hash(it) }.toTypedArray())

    private fun upToDate(cache: File, name: String, fp: String): Boolean =
        File(cache, "$name.fp").let { it.isFile && it.readText() == fp }

    private fun mark(cache: File, name: String, fp: String) { File(cache, "$name.fp").writeText(fp) }

    private fun unmark(cache: File, name: String) { File(cache, "$name.fp").delete() }

    // ---------- build utama ----------

    private fun doBuild(proj: File, cfg: BuildConfig): File {
        if (!hasAndroidJar()) {
            throw BuildError(BuildError.Kind.TOOLS, "Pustaka Android (android.jar) tidak ada di dalam aplikasi ini.")
        }
        prepareTools()
        val aapt2 = aapt2File()
        if (!aapt2.exists()) {
            throw BuildError(BuildError.Kind.TOOLS, "Alat aapt2 belum terpasang di Senza Studio, jadi resource belum bisa diproses.")
        }

        val signedApk = apkOf(proj)
        val out = File(proj, "out").apply { mkdirs() }
        val cache = File(out, "cache").apply { mkdirs() }
        val gen = File(out, "gen")
        val classes = File(out, "classes")
        val dexApp = File(out, "dex-app")
        val dexOut = File(out, "dex")
        val resZip = File(out, "res.zip")
        val base = File(out, "base.apk")
        val unsigned = File(out, "app-unsigned.apk")
        val merged = File(out, "AndroidManifest.merged.xml")
        val hasher = FileHasher(File(cache, "hashes.txt"))
        val mode = if (cfg.release) "release" else "debug"

        try {
            val info = GradleLite.read(proj)
            val minSdk = (info.minSdk ?: 26).coerceAtLeast(21)
            val targetSdk = (info.targetSdk ?: 28).coerceAtLeast(minSdk)

            val srcRoots = listOf("src", "java").map { File(proj, it) }.filter { it.isDirectory }
            val allSrc = srcRoots.flatMap { r -> r.walkTopDown().filter { it.isFile }.toList() }
            val ktFiles = allSrc.filter { it.extension == "kt" }.sortedBy { it.path }
            val javaSrc = allSrc.filter { it.extension == "java" }.sortedBy { it.path }
            val shrink = cfg.release && cfg.shrink
            val kotlinOut = File(out, "kotlin-classes")
            val aaptRules = File(out, "aapt_rules.pro")
            val mapping = File(out, "mapping.txt")

            // ===== 1. dependency =====
            checkCancel()
            onStep(1, "Mengambil dependency (Maven)")
            info.warnings.forEach { log("PERINGATAN: $it") }
            if (ktFiles.isNotEmpty() && info.dependencies.none { it.key == KotlinSupport.STDLIB } && info.unversioned.none { it.key == KotlinSupport.STDLIB }) {
                info.dependencies.add(MavenCoord("org.jetbrains.kotlin", "kotlin-stdlib", KotlinSupport.VERSION))
                log("  + kotlin-stdlib:${KotlinSupport.VERSION} (otomatis karena ada file .kt)")
            }
            val libs = ArrayList<ResolvedLib>()
            val resolver = MavenResolver(File(forgeDir, "m2"), log) { BuildControl.cancelled }
            if (info.dependencies.isEmpty() && info.unversioned.isEmpty()) {
                log("  (tidak ada dependency)")
            } else {
                libs.addAll(resolver.resolve(info.dependencies + info.unversioned, info.platforms))
                log("  ${libs.size} library siap dipakai")
            }
            // core library desugaring: jar desugar_jdk_libs TIDAK masuk daftar library biasa
            var desugar: DesugarLib? = null
            val dsCoord = info.desugarLib
            if (dsCoord != null) {
                when (info.desugarFlag) {
                    false -> log("PERINGATAN: coreLibraryDesugaring ada, tetapi isCoreLibraryDesugaringEnabled = false. Desugaring tidak dipakai.")
                    else -> {
                        if (info.desugarFlag == null) log("PERINGATAN: isCoreLibraryDesugaringEnabled = true belum ditulis di compileOptions; desugaring tetap dipakai.")
                        desugar = DesugarLib.prepare(dsCoord, resolver, log)
                        log("  desugaring API Java: $dsCoord")
                    }
                }
            } else if (info.desugarFlag == true) {
                log("PERINGATAN: isCoreLibraryDesugaringEnabled = true, tetapi baris coreLibraryDesugaring(\"com.android.tools:desugar_jdk_libs:...\") tidak ada.")
            }
            val dsFp: Array<String> = listOfNotNull(desugar?.key).toTypedArray()
            File(proj, "libs").listFiles { f -> f.isFile && f.extension == "jar" }?.sortedBy { it.name }?.forEach { j ->
                libs.add(ResolvedLib(MavenCoord("local", j.nameWithoutExtension, hasher.hash(j).take(10)), File(out, "local"), listOf(j), null, null, null, null, false))
                log("  + libs/${j.name}")
            }
            val libIds = libs.joinToString(",") { it.id }
            val libJars = libs.flatMap { it.jars }

            // ===== 2. manifest =====
            checkCancel()
            onStep(2, "Menggabungkan manifest")
            val appManifest = File(proj, "AndroidManifest.xml")
            if (!appManifest.isFile) throw BuildError(BuildError.Kind.RESOURCE, "AndroidManifest.xml tidak ada di proyek.")
            val basePkg = Regex("package\\s*=\\s*\"([^\"]+)\"").find(appManifest.readText())?.groupValues?.get(1)
                ?: info.namespace ?: info.applicationId ?: ""
            val flavor = if (cfg.flavor in info.flavors) cfg.flavor else ""
            if (cfg.flavor.isNotEmpty() && flavor.isEmpty()) log("PERINGATAN: flavor '${cfg.flavor}' tidak ada di build.gradle, diabaikan.")
            val mr = try {
                ManifestMerger.merge(
                    appManifest, libs.mapNotNull { it.manifest }, merged, info.namespace ?: info.applicationId,
                    mapOf("applicationId" to Variants.applicationId(info, basePkg, cfg.release, flavor))
                )
            } catch (e: BuildError) { throw e } catch (e: Exception) {
                throw BuildError(BuildError.Kind.RESOURCE, "Gagal menggabungkan manifest: ${e.message}", appManifest.path, null)
            }
            mr.notes.forEach { log("  ! $it") }
            log("  package ${mr.pkg}" + if (mr.added > 0) ", ${mr.added} entri digabung dari library" else "")
            val eff = Variants.resolve(info, mr.pkg, cfg.versionName, cfg.release, flavor)
            if (flavor.isNotEmpty() || eff.applicationId != mr.pkg || eff.versionName != cfg.versionName) {
                log("  varian: ${eff.buildType}" + (if (flavor.isNotEmpty()) " + $flavor" else "") + ", applicationId ${eff.applicationId}, versi ${eff.versionName}")
            }
            info.applicationId?.let { if (it != mr.pkg && flavor.isEmpty()) log("  ! applicationId ($it) berbeda dari package manifest; yang dipakai: ${mr.pkg}") }
            val libPkgs = libs.mapNotNull { l ->
                l.manifest?.let { m -> Regex("package\\s*=\\s*\"([^\"]+)\"").find(m.readText())?.groupValues?.get(1) }
            }.filter { it != mr.pkg }.distinct()

            // ===== 3-4. resource =====
            val resDir = File(proj, "res")
            val assetsDir = File(proj, "assets")
            val resFiles = if (resDir.isDirectory) resDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList() else emptyList()
            val assetFiles = if (assetsDir.isDirectory) assetsDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList() else emptyList()
            fun libCompiledZips(): List<File> =
                libs.mapNotNull { l -> l.resDir?.let { File(l.dir, "res.compiled.zip") } }.filter { it.isFile }

            /** Perintah `aapt2 link`. proto=true untuk bahan AAB (tanpa R.java dan aturan proguard). */
            fun linkCommand(outFile: File, libZips: List<File>, proto: Boolean): MutableList<String> {
                val c = mutableListOf(aapt2.path, "link", "-o", outFile.path, "-I", androidJar.path, "--manifest", merged.path)
                if (!proto) { c.add("--java"); c.add(gen.path) }
                c.addAll(listOf(
                    "--min-sdk-version", minSdk.toString(),
                    "--target-sdk-version", targetSdk.toString(),
                    "--version-code", cfg.versionCode.toString(),
                    "--version-name", eff.versionName,
                    "--auto-add-overlay"
                ))
                if (eff.applicationId != mr.pkg) {
                    // applicationId berbeda dari package kode (suffix/flavor): R.java tetap di package kode
                    c.add("--rename-manifest-package"); c.add(eff.applicationId)
                    c.add("--custom-package"); c.add(mr.pkg)
                }
                if (proto) c.add("--proto-format")
                if (!proto && libPkgs.isNotEmpty()) { c.add("--extra-packages"); c.add(libPkgs.joinToString(":")) }
                if (assetsDir.isDirectory) { c.add("-A"); c.add(assetsDir.path) }
                libs.mapNotNull { it.assetsDir }.forEach { c.add("-A"); c.add(it.path) }
                if (!cfg.release) c.add("--debug-mode")
                if (!proto && shrink) { c.add("--proguard"); c.add(aaptRules.path) }
                // urutan overlay: library terdalam dulu, resource aplikasi terakhir (menang)
                libZips.forEach { c.add("-R"); c.add(it.path) }
                if (resZip.isFile) { c.add("-R"); c.add(resZip.path) }
                return c
            }

            val resFp = sha1(
                "res-v4", libIds, libPkgs.joinToString(","), "$minSdk/$targetSdk/${cfg.versionCode}/${eff.versionName}/${eff.applicationId}/${cfg.release}/$shrink",
                filesFp(hasher, resDir, resFiles), filesFp(hasher, assetsDir, assetFiles), hasher.hash(merged)
            )

            checkCancel()
            if (upToDate(cache, "res", resFp) && base.isFile && gen.isDirectory) {
                onStep(3, "Resource (tidak berubah, dilewati)")
                onStep(4, "Resource (tidak berubah, dilewati)")
            } else {
                unmark(cache, "res")
                onStep(3, "Membaca resource (aplikasi + library)")
                val libZips = ArrayList<File>()
                var compiledNow = 0
                for (l in libs) {
                    checkCancel()
                    val rd = l.resDir ?: continue
                    val z = File(l.dir, "res.compiled.zip")
                    if (!z.isFile) {
                        val tmp = File(l.dir, "res.compiled.tmp.zip")
                        tmp.delete()
                        execAapt(listOf(aapt2.path, "compile", "--dir", rd.path, "-o", tmp.path), "membaca resource ${l.coord.artifact}")
                        tmp.renameTo(z)
                        compiledNow++
                    }
                    libZips.add(z)
                }
                if (compiledNow > 0) log("  $compiledNow library baru dikompilasi (disimpan di cache)")
                resZip.delete()
                if (resFiles.isNotEmpty()) {
                    execAapt(listOf(aapt2.path, "compile", "--dir", resDir.path, "-o", resZip.path), "membaca resource")
                }

                checkCancel()
                onStep(4, "Menyusun resource dan manifest")
                gen.deleteRecursively(); gen.mkdirs()
                execAapt(linkCommand(base, libZips, false), "menyusun resource")
                mark(cache, "res", resFp)
            }

            // ===== BuildConfig (dibuat tiap build; murah) =====
            val bcWarn = ArrayList<String>()
            var bcText = ""
            if (allSrc.any { it.nameWithoutExtension == "BuildConfig" && (it.extension == "java" || it.extension == "kt") }) {
                log("  BuildConfig milikmu dipakai (yang otomatis dilewati)")
            } else {
                bcText = Variants.buildConfigJava(mr.pkg, eff, cfg.versionCode, cfg.release, bcWarn)
                val bcFile = File(gen, mr.pkg.replace('.', '/') + "/BuildConfig.java")
                bcFile.parentFile?.mkdirs()
                bcFile.writeText(bcText)
                log("  BuildConfig: DEBUG=${!cfg.release}, ${eff.fields.size} field dari build.gradle")
            }
            bcWarn.forEach { log("  ! $it") }

            // ===== 5. Kotlin + Java =====
            checkCancel()
            val srcFp = sha1("src-v3", resFp, libIds, "$minSdk", KotlinSupport.VERSION, sha1(bcText), filesFp(hasher, proj, javaSrc + ktFiles))
            val javaFp = srcFp
            val haveClasses = (classes.isDirectory && classes.walk().any { it.extension == "class" }) ||
                (kotlinOut.isDirectory && kotlinOut.walk().any { it.extension == "class" })
            if (upToDate(cache, "java", javaFp) && haveClasses) {
                onStep(5, "Kode (tidak berubah, dilewati)")
            } else {
                unmark(cache, "java"); unmark(cache, "dexapp"); unmark(cache, "dex"); unmark(cache, "r8")
                onStep(5, if (ktFiles.isNotEmpty()) "Mengompilasi Kotlin dan Java" else "Memeriksa dan mengompilasi kode Java")
                kotlinOut.deleteRecursively()
                if (ktFiles.isNotEmpty()) {
                    compileKotlin(proj, ktFiles, javaSrc, gen, kotlinOut, libJars)
                }
                compileJava(javaSrc, gen, classes, if (kotlinOut.isDirectory) libJars + kotlinOut else libJars)
                mark(cache, "java", javaFp)
            }
            val appClassRoots = listOf(classes, kotlinOut).filter { it.isDirectory }

            // ===== 6. dex (D8) atau R8 =====
            checkCancel()
            val appClassFiles = appClassRoots.flatMap { r ->
                r.walk().filter { it.isFile && it.extension == "class" }.map { it.toPath() }.toList()
            }
            if (shrink) {
                onStep(6, "Mengecilkan dan mengacak kode (R8)")
                val userRules = File(proj, USER_RULES)
                if (!userRules.exists()) {
                    userRules.writeText(DEFAULT_USER_RULES)
                    log("  dibuat $USER_RULES (tempat aturan keep milikmu)")
                }
                val builtin = File(out, "builtin-rules.pro").also { it.writeText(BUILTIN_RULES) }
                val rules = ArrayList<File>()
                rules.add(builtin)
                if (aaptRules.isFile) rules.add(aaptRules)
                libs.mapNotNull { it.proguard }.sortedBy { it.path }.forEach { rules.add(it) }
                rules.add(userRules)
                val r8Fp = sha1(
                    "r8-v1", javaFp, libIds, "$minSdk", *dsFp,
                    *rules.map { it.path + ":" + hasher.hash(it) }.toTypedArray()
                )
                if (upToDate(cache, "r8", r8Fp) && mapping.isFile && dexOut.listFiles { f -> f.extension == "dex" }?.isNotEmpty() == true) {
                    log("  R8: tidak berubah, dilewati")
                } else {
                    unmark(cache, "r8"); unmark(cache, "dex")
                    dexOut.deleteRecursively(); dexOut.mkdirs()
                    log("  R8: ${appClassFiles.size} kelas aplikasi + ${libJars.size} jar library, ${rules.size} berkas aturan")
                    runR8("kode aplikasi dan library") {
                        addProgramFiles(appClassFiles)
                        if (libJars.isNotEmpty()) addProgramFiles(libJars.map { it.toPath() })
                        addLibraryFiles(androidJar.toPath())
                        addProguardConfigurationFiles(rules.map { it.toPath() })
                        setOutput(dexOut.toPath(), OutputMode.DexIndexed)
                        setMinApiLevel(minSdk)
                        setMode(CompilationMode.RELEASE)
                        setProguardMapOutputPath(mapping.toPath())
                        desugar?.let { addDesugaredLibraryConfiguration(it.json) }
                    }
                    desugar?.let { ds ->
                        // dex j$.* hasil L8 ditaruh sebagai dex tambahan di belakang hasil R8
                        val l8 = desugarDex(ds, minSdk)
                        var n = (dexOut.listFiles { f -> f.extension == "dex" }?.size ?: 0) + 1
                        l8.listFiles { f -> f.extension == "dex" }?.sortedBy { it.name }?.forEach {
                            it.copyTo(File(dexOut, "classes$n.dex"), overwrite = true)
                            n++
                        }
                        log("  desugaring: dex j\$.* ditambahkan")
                    }
                    mark(cache, "r8", r8Fp)
                }
                log("  mapping nama: out/mapping.txt (untuk membaca stack trace yang diacak)")
            } else {
                onStep(6, "Mengubah kode ke format Android (dex)")
                val libDexDirs = dexLibraries(libs, libJars, minSdk, desugar) + listOfNotNull(desugar?.let { desugarDex(it, minSdk) })
                checkCancel()
                val appDexFp = sha1("dexapp-v2", javaFp, mode, *dsFp)
                if (!(upToDate(cache, "dexapp", appDexFp) && dexApp.isDirectory && dexApp.listFiles { f -> f.extension == "dex" }?.isNotEmpty() == true)) {
                    unmark(cache, "dex")
                    dexApp.deleteRecursively(); dexApp.mkdirs()
                    runD8("kode aplikasi") {
                        addProgramFiles(appClassFiles)
                        addLibraryFiles(androidJar.toPath())
                        if (libJars.isNotEmpty()) addClasspathFiles(libJars.map { it.toPath() })
                        setOutput(dexApp.toPath(), OutputMode.DexIndexed)
                        setMinApiLevel(minSdk)
                        setMode(if (cfg.release) CompilationMode.RELEASE else CompilationMode.DEBUG)
                        desugar?.let { addDesugaredLibraryConfiguration(it.json) }
                    }
                    mark(cache, "dexapp", appDexFp)
                }
                checkCancel()
                val dexFp = sha1("dex-v2", appDexFp, libIds, "d8", *dsFp)
                if (!(upToDate(cache, "dex", dexFp) && dexOut.listFiles { f -> f.extension == "dex" }?.isNotEmpty() == true)) {
                    unmark(cache, "r8")
                    dexOut.deleteRecursively(); dexOut.mkdirs()
                    val appDexFiles = dexApp.listFiles { f -> f.extension == "dex" }!!.sortedBy { it.name }
                    if (libDexDirs.isEmpty()) {
                        appDexFiles.forEach { it.copyTo(File(dexOut, it.name), overwrite = true) }
                    } else {
                        val inputs = ArrayList<java.nio.file.Path>()
                        appDexFiles.forEach { inputs.add(it.toPath()) }
                        libDexDirs.forEach { d -> d.listFiles { f -> f.extension == "dex" }?.sortedBy { it.name }?.forEach { inputs.add(it.toPath()) } }
                        log("  menggabungkan ${inputs.size} berkas dex")
                        runD8("penggabungan dex") {
                            addProgramFiles(inputs)
                            addLibraryFiles(androidJar.toPath())
                            setOutput(dexOut.toPath(), OutputMode.DexIndexed)
                            setMinApiLevel(minSdk)
                            setMode(CompilationMode.RELEASE)
                        }
                    }
                    mark(cache, "dex", dexFp)
                }
            }
            val dexCount = dexOut.listFiles { f -> f.extension == "dex" }?.size ?: 0
            if (dexCount > 1) log("  multi-dex: $dexCount berkas dex")

            // ===== 7. rakit =====
            checkCancel()
            onStep(7, "Merakit APK")
            val jniSources = ArrayList<File>()
            File(proj, "jniLibs").takeIf { it.isDirectory }?.let { jniSources.add(it) }
            libs.mapNotNull { it.jniDir }.forEach { jniSources.add(it) }
            mergeApk(base, dexOut, jniSources, unsigned)

            // ===== 8. tanda tangan =====
            checkCancel()
            onStep(8, "Menandatangani APK")
            val signer = if (cfg.release && cfg.signing.isNotEmpty()) {
                try { keystores.signer(cfg.signing) } catch (e: Exception) {
                    throw BuildError(BuildError.Kind.OTHER, "Keystore '${cfg.signing}' tidak bisa dipakai: ${e.message}")
                }
            } else keystores.debugSigner()
            log("  tanda tangan: ${signer.label}")
            if (cfg.release && signer.isDebug) {
                log("  ! Release memakai keystore bawaan (hanya uji coba, tidak bisa untuk Play Store). Buat keystore sendiri di menu Keystore.")
            }
            try {
                sign(unsigned, signedApk, minSdk, signer)
            } catch (e: Exception) {
                throw BuildError(BuildError.Kind.OTHER, "Gagal menandatangani APK: ${e.message}")
            }
            try {
                val rep = ApkCheck.verify(signedApk, minSdk)
                if (rep.ok) log("  tanda tangan terverifikasi (${rep.schemes}), SHA-256 ${rep.sha256?.take(23)}…")
                else log("  ! Verifikasi tanda tangan gagal: ${rep.problems.firstOrNull() ?: "tidak diketahui"}")
            } catch (e: Throwable) {
                log("  (verifikasi tanda tangan dilewati: ${e.message})")
            }

            // ===== AAB (eksperimental, hanya Release) =====
            val aab = bundleOf(proj)
            aab.delete()
            if (cfg.bundle) {
                if (!cfg.release) {
                    log("  ! Bundel .aab hanya dibuat untuk Release, dilewati.")
                } else {
                    checkCancel()
                    log("  AAB (eksperimental): menyusun resource format proto…")
                    val protoApk = File(out, "base-proto.apk")
                    protoApk.delete()
                    execAapt(linkCommand(protoApk, libCompiledZips(), true), "menyusun resource untuk AAB")
                    try {
                        AabBuilder.assemble(protoApk, dexOut, jniSources, signer, aab, log)
                    } catch (e: BuildError) { throw e } catch (e: Exception) {
                        aab.delete()
                        throw BuildError(BuildError.Kind.OTHER, "Gagal merakit AAB: ${e.message}")
                    }
                    val problems = AabBuilder.check(aab)
                    if (problems.isEmpty()) log("  AAB siap: ${aab.name}. Validasi dengan bundletool di PC sebelum diunggah ke Play Console.")
                    else log("  ! AAB tampak tidak lengkap: ${problems.joinToString()}")
                    if (signer.isDebug) log("  ! AAB ini ditandatangani keystore bawaan; Play Console akan menolaknya.")
                }
            }

            log("Selesai: ${signedApk.length() / 1024} KB")
            return signedApk
        } finally {
            hasher.save()
        }
    }

    private fun compileKotlin(proj: File, ktFiles: List<File>, javaSrc: List<File>, gen: File, outDir: File, libJars: List<File>) {
        if (!KotlinSupport.available()) {
            throw BuildError(BuildError.Kind.TOOLS, "Kotlin butuh app_process (bagian dari Android), tetapi tidak ditemukan di perangkat ini.")
        }
        outDir.deleteRecursively(); outDir.mkdirs()
        val genJava = gen.walk().filter { it.isFile && it.extension == "java" }.sortedBy { it.path }.toList()
        log("  Kotlin pertama kali biasanya lambat (beberapa menit). Build berikutnya lebih cepat.")
        val res = KotlinSupport.compile(
            ctx, proj, ktFiles + javaSrc + genJava, listOf(androidJar) + libJars, outDir, log
        ) { proc = it }
        checkCancel()
        if (res.exit != 0) throw KotlinSupport.toError(res, proj)
        val n = outDir.walk().count { it.isFile && it.extension == "class" }
        log("  Kotlin selesai: $n kelas")
    }

    private fun compileJava(javaFiles: List<File>, gen: File, classes: File, libJars: List<File>) {
        classes.deleteRecursively(); classes.mkdirs()
        val files = javaFiles.map { it.path } +
            gen.walk().filter { it.isFile && it.extension == "java" }.map { it.path }.toList()
        if (files.isEmpty()) return
        val args = ArrayList<String>()
        args.addAll(listOf("-1.8", "-proc:none", "-nowarn", "-bootclasspath", androidJar.path))
        if (libJars.isNotEmpty()) { args.add("-classpath"); args.add(libJars.joinToString(File.pathSeparator) { it.path }) }
        args.addAll(listOf("-d", classes.path))
        args.addAll(files)
        val sw = StringWriter()
        val pw = PrintWriter(sw, true)
        val argv = args.toTypedArray()
        // ECJ dimuat lewat EcjRunner: resource-nya (messages.properties, tabel parser) dibaca dari
        // berkas biasa, bukan dari dalam APK (yang di sebagian perangkat melempar SecurityException).
        val ok = try {
            EcjRunner.compile(ctx, argv, pw, log)
        } catch (e: Exception) {
            log("  ! Pemuat ECJ khusus gagal (${e.javaClass.simpleName}: ${e.message}). Mencoba jalur biasa.")
            org.eclipse.jdt.internal.compiler.batch.Main(pw, pw, false, null, null).compile(argv)
        } catch (e: LinkageError) {
            log("  ! Pemuat ECJ khusus gagal (${e.javaClass.simpleName}: ${e.message}). Mencoba jalur biasa.")
            org.eclipse.jdt.internal.compiler.batch.Main(pw, pw, false, null, null).compile(argv)
        }
        pw.flush()
        val text = sw.toString()
        if (text.isNotBlank()) log(text)
        if (!ok) {
            val m = Regex("ERROR in (.+?) \\(at line (\\d+)\\)\\R(?:.*\\R){2}(.*)").find(text)
            if (m != null) {
                val path = m.groupValues[1]
                val line = m.groupValues[2].toInt()
                throw BuildError(
                    BuildError.Kind.CODE,
                    "${File(path).name}, baris $line:\n${friendlyJava(m.groupValues[3].trim())}",
                    path, line
                )
            }
            throw BuildError(BuildError.Kind.CODE, "Kode Java tidak bisa dikompilasi. Ketuk 'Lihat detail'.")
        }
    }

    /** Dex tiap library sendiri-sendiri dan simpan di cache bersama. Mengembalikan folder dex-nya. */
    private fun dexLibraries(libs: List<ResolvedLib>, allJars: List<File>, minSdk: Int, desugar: DesugarLib?): List<File> {
        val dsFp: Array<String> = listOfNotNull(desugar?.key).toTypedArray()
        val result = ArrayList<File>()
        var fresh = 0
        val total = libs.count { it.jars.isNotEmpty() }
        for (l in libs) {
            if (l.jars.isEmpty()) continue
            checkCancel()
            val dir = File(forgeDir, "dexcache/" + sha1("lib-v1", l.id, "$minSdk", *dsFp))
            if (!File(dir, ".done").isFile) {
                dir.deleteRecursively(); dir.mkdirs()
                log("  dex ${l.coord.artifact} (${++fresh}/$total baru)")
                val own = l.jars.toSet()
                runD8(l.coord.artifact) {
                    addProgramFiles(l.jars.map { it.toPath() })
                    addLibraryFiles(androidJar.toPath())
                    val cp = allJars.filter { it !in own }.map { it.toPath() }
                    if (cp.isNotEmpty()) addClasspathFiles(cp)
                    setOutput(dir.toPath(), OutputMode.DexIndexed)
                    setMinApiLevel(minSdk)
                    setMode(CompilationMode.RELEASE)
                    desugar?.let { addDesugaredLibraryConfiguration(it.json) }
                }
                File(dir, ".done").writeText("ok")
            }
            result.add(dir)
        }
        if (total > 0) log("  dex library: ${total - fresh} dari cache, $fresh baru")
        return result
    }

    /** Dex untuk kelas j$.* (desugar_jdk_libs) lewat L8, disimpan di cache bersama. */
    private fun desugarDex(ds: DesugarLib, minSdk: Int): File {
        val dir = File(forgeDir, "dexcache/" + sha1("l8-v1", ds.key, "$minSdk"))
        if (!File(dir, ".done").isFile) {
            dir.deleteRecursively(); dir.mkdirs()
            log("  L8: membuat dex j\$.* (${ds.coord.artifact})")
            try {
                val b = L8Command.builder()
                b.addProgramFiles(listOf(ds.jar.toPath()))
                b.addLibraryFiles(androidJar.toPath())
                b.addDesugaredLibraryConfiguration(ds.json)
                b.setMinApiLevel(minSdk)
                b.setOutput(dir.toPath(), OutputMode.DexIndexed)
                L8.run(b.build())
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                throw BuildError(BuildError.Kind.OTHER, "Gagal membuat dex desugaring (L8): ${msg.lines().firstOrNull() ?: msg}")
            }
            File(dir, ".done").writeText("ok")
        }
        return dir
    }

    private fun runD8(what: String, cfg: D8Command.Builder.() -> Unit) {
        try {
            val b = D8Command.builder()
            b.cfg()
            D8.run(b.build())
        } catch (e: Exception) {
            val msg = (e.message ?: e.javaClass.simpleName)
            val hint = when {
                msg.contains("defined multiple times", ignoreCase = true) ->
                    "\n(Ada dua library yang berisi kelas yang sama. Hapus salah satunya dari build.gradle.)"
                msg.contains("Missing class", ignoreCase = true) ->
                    "\n(Library butuh kelas yang belum ada. Tambahkan dependency yang kurang.)"
                else -> ""
            }
            throw BuildError(BuildError.Kind.OTHER, "Gagal mengubah $what ke format Android: ${msg.lines().firstOrNull() ?: msg}$hint")
        }
    }

    private fun runR8(what: String, cfg: R8Command.Builder.() -> Unit) {
        val errors = ArrayList<String>()
        var warnings = 0
        val handler = object : DiagnosticsHandler {
            override fun error(d: Diagnostic) { errors.add(d.diagnosticMessage) }
            override fun warning(d: Diagnostic) { warnings++ }
        }
        try {
            val b = R8Command.builder(handler)
            b.cfg()
            R8.run(b.build())
            if (warnings > 0) log("  R8: $warnings peringatan (diabaikan)")
        } catch (e: Exception) {
            val all = (errors + (e.message ?: e.javaClass.simpleName)).joinToString("\n")
            val missing = Regex("""Missing class ([\w.$]+)""").findAll(all).map { it.groupValues[1] }.distinct().take(5).toList()
            val hint = when {
                missing.isNotEmpty() ->
                    "\n(Library butuh kelas yang tidak ada. Tambahkan ke $USER_RULES, mis.:\n" +
                        missing.joinToString("\n") { "  -dontwarn $it" } +
                        "\natau matikan 'Perkecil & acak kode'.)"
                all.contains("defined multiple times", ignoreCase = true) ->
                    "\n(Ada dua library yang berisi kelas yang sama. Hapus salah satunya dari build.gradle.)"
                else -> "\n(Jika aplikasi hasilnya error karena kelas yang dihapus, tambahkan -keep di $USER_RULES.)"
            }
            val first = (errors.firstOrNull() ?: e.message ?: e.javaClass.simpleName).lines().firstOrNull() ?: ""
            throw BuildError(BuildError.Kind.OTHER, "Gagal mengecilkan $what dengan R8: $first$hint")
        }
    }

    // ---------- helper ----------

    private fun friendlyJava(msg: String): String = when {
        msg.contains("Syntax error") -> "$msg\n(Cek tanda ; ) } yang kurang atau berlebih.)"
        msg.contains("cannot be resolved to a type") -> "$msg\n(Mungkin kurang 'import' atau salah ketik nama kelas.)"
        msg.contains("cannot be resolved") -> "$msg\n(Nama ini belum dibuat atau salah ketik.)"
        msg.contains("is undefined") -> "$msg\n(Fungsi ini tidak ada. Cek ejaan dan huruf besar/kecil.)"
        else -> msg
    }

    private fun execAapt(cmd: List<String>, what: String) {
        val p = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: IOException) {
            throw BuildError(
                BuildError.Kind.TOOLS,
                "aapt2 tidak bisa dijalankan di perangkat ini. Pastikan itu versi Android ARM64 yang berdiri sendiri.\n${e.message}"
            )
        }
        proc = p
        val outText = StringBuilder()
        p.inputStream.bufferedReader().forEachLine { outText.appendLine(it); log(it) }
        val code = p.waitFor()
        proc = null
        checkCancel()
        if (code == 0) return
        if (outText.contains("CANNOT LINK EXECUTABLE") || code == 126 || code == 127) {
            throw BuildError(
                BuildError.Kind.TOOLS,
                "File aapt2 tidak cocok dengan perangkat ini (butuh versi Android ARM64 yang berdiri sendiri)."
            )
        }
        val m = Regex("(\\S+?):(\\d+): error: (.+)").find(outText)
        if (m != null) {
            throw BuildError(
                BuildError.Kind.RESOURCE,
                "${File(m.groupValues[1]).name}, baris ${m.groupValues[2]}:\n${m.groupValues[3]}",
                m.groupValues[1], m.groupValues[2].toInt()
            )
        }
        throw BuildError(BuildError.Kind.RESOURCE, "Gagal $what (kode $code). Ketuk 'Lihat detail'.")
    }

    private fun mergeApk(base: File, dexDir: File, jniDirs: List<File>, dest: File) {
        val names = HashSet<String>()
        ZipFile(base).use { zf ->
            AlignedZipWriter(dest.outputStream()).use { zo ->
                for (e in zf.entries()) {
                    val bytes = zf.getInputStream(e).use { it.readBytes() }
                    if (names.add(e.name)) zo.add(e.name, bytes, e.method == ZipEntry.STORED)
                }
                dexDir.listFiles { f -> f.extension == "dex" }?.sortedBy { it.name }?.forEach {
                    if (names.add(it.name)) zo.add(it.name, it.readBytes(), false)
                }
                // pustaka native: <jniDir>/<abi>/*.so  ->  lib/<abi>/*.so
                for (jd in jniDirs) {
                    jd.listFiles { f -> f.isDirectory }?.forEach { abi ->
                        abi.listFiles { f -> f.isFile && f.extension == "so" }?.sortedBy { it.name }?.forEach { so ->
                            val n = "lib/${abi.name}/${so.name}"
                            if (names.add(n)) zo.add(n, so.readBytes(), false)
                        }
                    }
                }
            }
        }
    }

    private fun sign(input: File, output: File, minSdk: Int, signer: SignerMaterial) {
        val cfg = ApkSigner.SignerConfig.Builder("forge", signer.key, signer.chain).build()
        ApkSigner.Builder(listOf(cfg))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(minSdk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }
}

private fun digestFile(f: File): String {
    val md = MessageDigest.getInstance("SHA-1")
    f.inputStream().use { i ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
