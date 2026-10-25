package com.senza.studio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Menyimpan rahasia (kata sandi keystore, token Git) terenkripsi AES-GCM.
 * Kuncinya hidup di Android Keystore milik perangkat, tidak pernah keluar dari sana.
 * Nilai terenkripsi disimpan di SharedPreferences terpisah ("senza_secrets").
 */
class SecretStore(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("senza_secrets", Context.MODE_PRIVATE)

    companion object {
        private const val ALIAS = "senza_secret_key_v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val enc = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + enc, Base64.NO_WRAP)
    }

    fun decrypt(packed: String): String? = try {
        val all = Base64.decode(packed, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    } catch (_: Throwable) {
        null
    }

    /** Simpan rahasia dengan nama tertentu. Nilai kosong = hapus. */
    fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) prefs.edit().remove(name).apply()
        else prefs.edit().putString(name, encrypt(value)).apply()
    }

    fun get(name: String): String? = prefs.getString(name, null)?.let { decrypt(it) }

    fun has(name: String) = prefs.contains(name)

    fun remove(name: String) = prefs.edit().remove(name).apply()
}
