package com.senza.studio

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Properties

/** Bahan tanda tangan: kunci privat + rantai sertifikat. */
class SignerMaterial(val label: String, val key: PrivateKey, val chain: List<X509Certificate>, val isDebug: Boolean)

/** Ringkasan satu keystore untuk ditampilkan di layar. */
class KeyInfo(
    val name: String,
    val type: String,
    val alias: String,
    val subject: String,
    val validUntil: Long,
    val sha256: String,
    val sha1: String
)

/**
 * Keystore milik pengguna disimpan di filesDir/forge/keystores/<nama>.keystore.
 * Kata sandi disimpan terenkripsi lewat [SecretStore], bukan teks biasa.
 * Jenis yang didukung Android: PKCS12 (dibuat di sini) dan BKS. JKS bawaan Java tidak didukung Android.
 */
class KeystoreManager(private val ctx: Context) {

    private val dir = File(ctx.filesDir, "forge/keystores").apply { mkdirs() }
    private val secrets = SecretStore(ctx)

    companion object {
        val NAME_RE = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,31}")
        const val MIN_PASS = 6
    }

    private fun ksFile(name: String) = File(dir, "$name.keystore")
    private fun metaFile(name: String) = File(dir, "$name.meta")
    private fun storeKey(name: String) = "ks.$name.store"
    private fun keyKey(name: String) = "ks.$name.key"

    fun names(): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".keystore") }
            ?.map { it.name.removeSuffix(".keystore") }?.sorted() ?: emptyList()

    fun exists(name: String) = ksFile(name).isFile

    private fun meta(name: String): Properties = Properties().apply {
        metaFile(name).takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }

    fun storePassword(name: String): String? = secrets.get(storeKey(name))
    fun keyPassword(name: String): String? = secrets.get(keyKey(name))
    fun aliasOf(name: String): String? = meta(name).getProperty("alias")

    // ---------- baca ----------

    private fun load(name: String): Pair<KeyStore, CharArray> {
        val type = meta(name).getProperty("type") ?: "PKCS12"
        val pass = (storePassword(name) ?: throw IllegalStateException("Kata sandi keystore '$name' tidak bisa dibaca.")).toCharArray()
        val ks = KeyStore.getInstance(type)
        ksFile(name).inputStream().use { ks.load(it, pass) }
        return ks to pass
    }

    fun signer(name: String): SignerMaterial {
        if (!exists(name)) throw IllegalStateException("Keystore '$name' tidak ditemukan. Pilih keystore lain di layar Build.")
        val (ks, storePass) = load(name)
        val alias = aliasOf(name) ?: ks.aliases().toList().first { ks.isKeyEntry(it) }
        val keyPass = (keyPassword(name)?.toCharArray()) ?: storePass
        val key = ks.getKey(alias, keyPass) as? PrivateKey
            ?: throw IllegalStateException("Kunci '$alias' tidak ditemukan atau kata sandi kunci salah.")
        val chain = ks.getCertificateChain(alias)?.map { it as X509Certificate }
            ?: throw IllegalStateException("Keystore tidak punya sertifikat untuk '$alias'.")
        return SignerMaterial(name, key, chain, false)
    }

    /** Keystore bawaan yang ikut di dalam aplikasi (hanya untuk uji coba). */
    fun debugSigner(): SignerMaterial {
        val pass = "android".toCharArray()
        val ks = KeyStore.getInstance("PKCS12")
        ctx.assets.open("debug.p12").use { ks.load(it, pass) }
        val alias = ks.aliases().nextElement()
        return SignerMaterial(
            "bawaan Senza (debug)",
            ks.getKey(alias, pass) as PrivateKey,
            listOf(ks.getCertificate(alias) as X509Certificate),
            true
        )
    }

    fun info(name: String): KeyInfo {
        val (ks, _) = load(name)
        val alias = aliasOf(name) ?: ks.aliases().toList().first { ks.isKeyEntry(it) }
        val cert = ks.getCertificate(alias) as X509Certificate
        return KeyInfo(
            name, meta(name).getProperty("type") ?: "PKCS12", alias,
            cert.subjectX500Principal.name, cert.notAfter.time,
            fingerprint(cert, "SHA-256"), fingerprint(cert, "SHA-1")
        )
    }

    fun infoOrNull(name: String): KeyInfo? = try { info(name) } catch (_: Throwable) { null }

    private fun fingerprint(c: X509Certificate, algo: String): String =
        MessageDigest.getInstance(algo).digest(c.encoded).joinToString(":") { "%02X".format(it) }

    // ---------- buat ----------

    fun create(
        name: String, alias: String, storePass: String, keyPass: String,
        commonName: String, org: String, country: String, validYears: Int
    ): KeyInfo {
        require(NAME_RE.matches(name)) { "Nama keystore: huruf, angka, _ atau - (maksimal 32 karakter)." }
        require(!exists(name)) { "Keystore '$name' sudah ada." }
        require(alias.isNotBlank() && alias.none { it.isWhitespace() }) { "Alias tidak boleh kosong atau berspasi." }
        require(storePass.length >= MIN_PASS) { "Kata sandi keystore minimal $MIN_PASS karakter." }
        require(keyPass.length >= MIN_PASS) { "Kata sandi kunci minimal $MIN_PASS karakter." }
        require(commonName.isNotBlank()) { "Nama (CN) tidak boleh kosong." }
        require(validYears in 1..100) { "Masa berlaku 1 sampai 100 tahun." }
        require(country.isEmpty() || Regex("[A-Za-z]{2}").matches(country)) { "Kode negara 2 huruf, mis. ID." }

        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048, SecureRandom())
        val pair = gen.generateKeyPair()

        val dn = buildString {
            append("CN=").append(escapeDn(commonName))
            if (org.isNotBlank()) append(", O=").append(escapeDn(org))
            if (country.isNotBlank()) append(", C=").append(country.uppercase())
        }
        val now = System.currentTimeMillis()
        val notAfter = Date(now + validYears * 365L * 24 * 3600 * 1000)
        val subject = X500Name(dn)
        val holder = JcaX509v3CertificateBuilder(
            subject, BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE),
            Date(now - 60_000), notAfter, subject, pair.public
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)

        // PKCS12 di Android memakai satu kata sandi untuk toko dan kunci
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(alias, pair.private, storePass.toCharArray(), arrayOf(cert))
        ksFile(name).outputStream().use { ks.store(it, storePass.toCharArray()) }

        val p = Properties()
        p.setProperty("type", "PKCS12")
        p.setProperty("alias", alias)
        metaFile(name).outputStream().use { p.store(it, "Senza Studio keystore") }
        secrets.put(storeKey(name), storePass)
        secrets.put(keyKey(name), storePass)   // sama dengan kata sandi toko (syarat PKCS12)
        return info(name)
    }

    private fun escapeDn(s: String) = s.trim().replace("\\", "\\\\").replace(",", "\\,").replace("=", "\\=").replace("+", "\\+")
        .replace("\"", "\\\"").replace("<", "\\<").replace(">", "\\>").replace(";", "\\;")

    // ---------- impor / ekspor / hapus ----------

    /**
     * Impor keystore dari berkas. Mencoba PKCS12 lalu BKS.
     * File .jks dari Java biasanya tidak terbaca di Android; ubah dulu ke PKCS12 di PC:
     * keytool -importkeystore -srckeystore a.jks -destkeystore a.p12 -deststoretype PKCS12
     */
    fun importFrom(name: String, input: InputStream, storePass: String, aliasIn: String, keyPassIn: String): KeyInfo {
        require(NAME_RE.matches(name)) { "Nama keystore: huruf, angka, _ atau - (maksimal 32 karakter)." }
        require(!exists(name)) { "Keystore '$name' sudah ada." }
        require(storePass.isNotEmpty()) { "Isi kata sandi keystore." }
        val bytes = input.readBytes()
        require(bytes.size in 1..5_000_000) { "Berkas keystore kosong atau terlalu besar." }

        var loaded: KeyStore? = null
        var type = ""
        val errors = ArrayList<String>()
        for (t in listOf("PKCS12", "BKS")) {
            try {
                val ks = KeyStore.getInstance(t)
                ks.load(bytes.inputStream(), storePass.toCharArray())
                loaded = ks; type = t
                break
            } catch (e: Throwable) {
                errors.add("$t: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        val ks = loaded ?: throw IllegalArgumentException(
            "Keystore tidak bisa dibuka. Kata sandi salah, atau formatnya JKS (tidak didukung Android). " +
                "Ubah ke PKCS12 di PC: keytool -importkeystore -srckeystore a.jks -destkeystore a.p12 -deststoretype PKCS12\n" +
                errors.joinToString("\n")
        )
        val keyAliases = ks.aliases().toList().filter { ks.isKeyEntry(it) }
        require(keyAliases.isNotEmpty()) { "Keystore ini tidak berisi kunci privat." }
        val alias = if (aliasIn.isNotBlank()) {
            keyAliases.firstOrNull { it.equals(aliasIn.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Alias '$aliasIn' tidak ada. Alias yang tersedia: ${keyAliases.joinToString()}")
        } else keyAliases.first()
        val keyPass = keyPassIn.ifEmpty { storePass }
        ks.getKey(alias, keyPass.toCharArray()) as? PrivateKey
            ?: throw IllegalArgumentException("Kata sandi kunci '$alias' salah.")

        ksFile(name).writeBytes(bytes)
        val p = Properties()
        p.setProperty("type", type)
        p.setProperty("alias", alias)
        metaFile(name).outputStream().use { p.store(it, "Senza Studio keystore") }
        secrets.put(storeKey(name), storePass)
        secrets.put(keyKey(name), keyPass)
        return info(name)
    }

    fun exportTo(name: String, out: OutputStream) {
        require(exists(name)) { "Keystore tidak ditemukan." }
        ksFile(name).inputStream().use { it.copyTo(out) }
    }

    fun delete(name: String) {
        ksFile(name).delete()
        metaFile(name).delete()
        secrets.remove(storeKey(name))
        secrets.remove(keyKey(name))
    }
}
