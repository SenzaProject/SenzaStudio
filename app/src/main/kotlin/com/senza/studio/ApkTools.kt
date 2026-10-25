package com.senza.studio

import com.android.apksig.ApkVerifier
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Penulis zip yang menyelaraskan (zipalign) data entri tanpa kompresi ke kelipatan [align] byte.
 * Penyelarasan dilakukan SAAT menulis APK, sebelum ditandatangani, jadi tanda tangan tetap sah.
 * Padding ditaruh di "extra field" header lokal dengan ID 0xD935, sama seperti zipalign Android.
 */
class AlignedZipWriter(out: OutputStream, private val align: Int = 4) : Closeable {

    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    private val counting = Counting(BufferedOutputStream(out, 64 * 1024))
    private val zo = ZipOutputStream(counting)

    fun add(name: String, bytes: ByteArray, stored: Boolean) {
        val ze = ZipEntry(name)
        if (stored) {
            ze.method = ZipEntry.STORED
            ze.size = bytes.size.toLong()
            ze.compressedSize = bytes.size.toLong()
            ze.crc = CRC32().also { it.update(bytes) }.value
            val nameLen = name.toByteArray(Charsets.UTF_8).size
            val dataStart = counting.count + 30 + nameLen
            var pad = ((align - (dataStart % align)) % align).toInt()
            if (pad in 1..3) pad += align          // header extra butuh minimal 4 byte
            if (pad > 0) {
                val extra = ByteArray(pad)
                extra[0] = 0x35; extra[1] = 0xD9.toByte()            // ID 0xD935 (little endian)
                val dataLen = pad - 4
                extra[2] = (dataLen and 0xFF).toByte(); extra[3] = ((dataLen shr 8) and 0xFF).toByte()
                if (dataLen >= 2) { extra[4] = (align and 0xFF).toByte(); extra[5] = ((align shr 8) and 0xFF).toByte() }
                ze.extra = extra
            }
        }
        zo.putNextEntry(ze)
        zo.write(bytes)
        zo.closeEntry()
    }

    override fun close() { zo.close() }
}

/** Pemeriksaan APK hasil tanda tangan. */
object ApkCheck {
    class Report(val ok: Boolean, val schemes: String, val sha256: String?, val problems: List<String>)

    fun verify(apk: File, minSdk: Int): Report {
        val r = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(minSdk).build().verify()
        val schemes = buildList {
            if (r.isVerifiedUsingV1Scheme) add("v1")
            if (r.isVerifiedUsingV2Scheme) add("v2")
            if (r.isVerifiedUsingV3Scheme) add("v3")
        }.joinToString("+")
        val cert = r.signerCertificates.firstOrNull()
        return Report(r.isVerified, schemes, cert?.let { fingerprint(it) }, r.errors.map { it.toString() })
    }

    fun fingerprint(c: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString(":") { "%02X".format(it) }
}
