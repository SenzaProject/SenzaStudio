package com.senza.studio

import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * EKSPERIMENTAL: merakit Android App Bundle (.aab) tanpa bundletool.
 *
 * Bahan: APK dari `aapt2 link --proto-format` (manifest + resource dalam format protobuf),
 * dex hasil D8/R8, dan pustaka native. Susunan modul `base`:
 *   base/manifest/AndroidManifest.xml, base/resources.pb, base/res/**, base/assets/**,
 *   base/dex/classes*.dex, base/lib/<abi>/*.so, base/root/**, plus BundleConfig.pb di akar.
 * Lalu ditandatangani gaya jarsigner (MANIFEST.MF + .SF + blok PKCS#7), seperti yang diminta Play.
 *
 * Belum diuji lawan bundletool/Play Console. Validasi dengan `bundletool validate --bundle=app.aab`
 * di PC sebelum mengunggah.
 */
object AabBuilder {

    private class Item(val name: String, val bytes: ByteArray)

    fun assemble(protoApk: File, dexDir: File, jniDirs: List<File>, signer: SignerMaterial, out: File, log: (String) -> Unit) {
        val items = ArrayList<Item>()
        val seen = HashSet<String>()
        fun add(name: String, bytes: ByteArray) { if (seen.add(name)) items.add(Item(name, bytes)) }

        ZipFile(protoApk).use { zf ->
            for (e in zf.entries()) {
                if (e.isDirectory) continue
                val n = e.name
                val bytes = zf.getInputStream(e).use { it.readBytes() }
                when {
                    n == "AndroidManifest.xml" -> add("base/manifest/AndroidManifest.xml", bytes)
                    n == "resources.pb" -> add("base/resources.pb", bytes)
                    n.startsWith("res/") -> add("base/$n", bytes)
                    n.startsWith("assets/") -> add("base/$n", bytes)
                    n.startsWith("lib/") -> add("base/$n", bytes)
                    n.startsWith("META-INF/") -> {}                      // tanda tangan APK tidak ikut
                    else -> add("base/root/$n", bytes)
                }
            }
        }
        dexDir.listFiles { f -> f.isFile && f.extension == "dex" }?.sortedBy { it.name }?.forEach {
            add("base/dex/${it.name}", it.readBytes())
        }
        for (jd in jniDirs) {
            jd.listFiles { f -> f.isDirectory }?.forEach { abi ->
                abi.listFiles { f -> f.isFile && f.extension == "so" }?.sortedBy { it.name }?.forEach { so ->
                    add("base/lib/${abi.name}/${so.name}", so.readBytes())
                }
            }
        }
        // BundleConfig.pb kosong = konfigurasi bawaan bundletool (semua pengaturan default)
        add("BundleConfig.pb", ByteArray(0))

        if (items.none { it.name == "base/manifest/AndroidManifest.xml" }) throw BuildError(BuildError.Kind.OTHER, "AAB: manifest tidak ditemukan di hasil aapt2 --proto-format.")
        if (items.none { it.name.startsWith("base/dex/") }) throw BuildError(BuildError.Kind.OTHER, "AAB: tidak ada berkas dex.")

        // ---- tanda tangan JAR ----
        val crlf = "\r\n"
        val b64 = Base64.getEncoder()
        fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        fun wrap(line: String): String {                       // baris manifest maks 72 byte
            val sb = StringBuilder()
            var rest = line
            var first = true
            while (rest.toByteArray(Charsets.UTF_8).size > 70 || (first && rest.length > 70)) {
                val take = if (first) 70 else 69
                sb.append(if (first) "" else " ").append(rest.substring(0, take)).append(crlf)
                rest = rest.substring(take)
                first = false
            }
            sb.append(if (first) "" else " ").append(rest).append(crlf)
            return sb.toString()
        }

        val sections = items.map { it ->
            wrap("Name: ${it.name}") + wrap("SHA-256-Digest: ${b64.encodeToString(sha256(it.bytes))}") + crlf
        }
        val manifest = ("Manifest-Version: 1.0$crlf" + "Created-By: Senza Studio$crlf" + crlf + sections.joinToString("")).toByteArray(Charsets.UTF_8)

        val sf = StringBuilder()
        sf.append("Signature-Version: 1.0$crlf").append("Created-By: Senza Studio$crlf")
        sf.append(wrap("SHA-256-Digest-Manifest: ${b64.encodeToString(sha256(manifest))}")).append(crlf)
        items.forEachIndexed { i, it ->
            sf.append(wrap("Name: ${it.name}"))
            sf.append(wrap("SHA-256-Digest: ${b64.encodeToString(sha256(sections[i].toByteArray(Charsets.UTF_8)))}"))
            sf.append(crlf)
        }
        val sfBytes = sf.toString().toByteArray(Charsets.UTF_8)

        val keyAlgo = signer.key.algorithm.uppercase()
        val (sigAlg, ext) = when {
            keyAlgo.startsWith("EC") -> "SHA256withECDSA" to "EC"
            keyAlgo.startsWith("DSA") -> "SHA256withDSA" to "DSA"
            else -> "SHA256withRSA" to "RSA"
        }
        val gen = CMSSignedDataGenerator()
        gen.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().build())
                .build(JcaContentSignerBuilder(sigAlg).build(signer.key), signer.chain.first())
        )
        gen.addCertificates(JcaCertStore(signer.chain))
        val pkcs7 = gen.generate(CMSProcessableByteArray(sfBytes), false).encoded

        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zo ->
            fun put(name: String, bytes: ByteArray) {
                zo.putNextEntry(ZipEntry(name)); zo.write(bytes); zo.closeEntry()
            }
            put("META-INF/MANIFEST.MF", manifest)
            put("META-INF/SENZA.SF", sfBytes)
            put("META-INF/SENZA.$ext", pkcs7)
            items.forEach { put(it.name, it.bytes) }
        }
        log("  AAB: ${items.size} berkas, ${out.length() / 1024} KB")
    }

    /** Pemeriksaan struktur sederhana. Mengembalikan daftar masalah (kosong = tampak wajar). */
    fun check(aab: File): List<String> {
        val problems = ArrayList<String>()
        ZipFile(aab).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toSet()
            listOf("BundleConfig.pb", "base/manifest/AndroidManifest.xml", "base/resources.pb", "META-INF/MANIFEST.MF")
                .filter { it !in names }.forEach { problems.add("hilang: $it") }
            if (names.none { it.startsWith("base/dex/") }) problems.add("hilang: base/dex/*.dex")
            if (names.none { it.startsWith("META-INF/") && (it.endsWith(".RSA") || it.endsWith(".EC") || it.endsWith(".DSA")) }) problems.add("hilang: blok tanda tangan")
        }
        return problems
    }
}
