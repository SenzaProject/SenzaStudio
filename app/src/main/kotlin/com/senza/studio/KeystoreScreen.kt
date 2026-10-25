package com.senza.studio

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keystore manager: buat atau impor keystore, lihat sidik jari, ekspor cadangan, hapus. */
class KeystoreScreen(act: MainActivity) : Screen(act) {

    override val showsNav = false

    private lateinit var body: LinearLayout
    private var pendingExport: String? = null
    private val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    override val view: View = buildView()

    private fun buildView(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            act.topBar(
                "Keystore", R.drawable.ic_back, { act.back() },
                R.drawable.ic_plus to { addMenu() }
            ), act.lp()
        )
        body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(16), act.dp(4), act.dp(16), act.dp(28))
        }
        col.addView(ScrollView(act).apply { addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return col
    }

    override fun onShow() { render() }

    private fun render() {
        body.removeAllViews()
        val warn = act.card(12, Pal.warnBg)
        warn.addView(act.label("Cadangkan keystore-mu", 14f, Pal.warn, true))
        warn.addView(
            act.label("Aplikasi yang sudah dirilis hanya bisa diperbarui dengan keystore yang SAMA. Kalau hilang, update tidak bisa dipasang di atas versi lama dan Play Store menolaknya. Ekspor dan simpan di tempat aman. Build Release memakai keystore yang kamu pilih di layar Build.", 12f, Pal.text),
            act.lp(t = 4)
        )
        body.addView(warn, act.lp(b = 14))

        val names = act.keystores.names()
        if (names.isEmpty()) {
            body.addView(act.label("Belum ada keystore sendiri. Release memakai keystore bawaan (hanya untuk uji coba, tidak bisa untuk Play Store).", 13f, Pal.sub), act.lp(b = 12))
        }
        names.forEach { n -> body.addView(itemCard(n), act.lp(b = 10)) }

        val row = LinearLayout(act)
        row.addView(act.primaryButton("Buat baru", R.drawable.ic_plus, 14f) { createDialog() }, act.lp(0, WRAP, r = 6, weight = 1f))
        row.addView(act.glassButton("Impor", R.drawable.ic_import, 14f) { pickKeystore() }, act.lp(0, WRAP, weight = 1f))
        body.addView(row, act.lp(t = 6))
    }

    private fun itemCard(name: String): LinearLayout {
        val c = act.card(14)
        val info = act.keystores.infoOrNull(name)
        val head = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(act.icon(R.drawable.ic_key, 22, 0xFFFFC857.toInt()), LinearLayout.LayoutParams(act.dp(22), act.dp(22)))
        head.addView(act.label(name, 16f, Pal.text, true), act.lp(0, WRAP, l = 10, weight = 1f))
        c.addView(head)
        if (info == null) {
            c.addView(act.label("Keystore tidak bisa dibuka (kata sandi tersimpan hilang?). Hapus lalu impor ulang.", 12f, Pal.err), act.lp(t = 6))
        } else {
            val expired = info.validUntil < System.currentTimeMillis()
            c.addView(act.label("Alias: ${info.alias}   ·   ${info.type}", 12f, Pal.sub), act.lp(t = 6))
            c.addView(act.label(cn(info.subject), 12f, Pal.sub))
            c.addView(
                act.label("Berlaku sampai ${fmt.format(Date(info.validUntil))}" + if (expired) " (KEDALUWARSA)" else "", 12f, if (expired) Pal.err else Pal.sub)
            )
            c.addView(act.mono("SHA-256 " + info.sha256.take(23) + "…", 10f, Pal.dim), act.lp(t = 4))
        }
        val r = LinearLayout(act)
        r.addView(act.glassButton("Detail", R.drawable.ic_info, 12f) { detail(name) }, act.lp(0, WRAP, r = 6, weight = 1f))
        r.addView(act.glassButton("Ekspor", R.drawable.ic_save, 12f) { exportKeystore(name) }, act.lp(0, WRAP, r = 6, weight = 1f))
        r.addView(act.glassButton("Hapus", R.drawable.ic_trash, 12f, Pal.err) {
            act.confirm("Hapus keystore '$name'? Tanpa cadangan, aplikasi yang sudah ditandatangani dengannya tidak bisa diperbarui lagi.") {
                act.keystores.delete(name)
                act.clearSigningRefs(name)
                render()
            }
        }, act.lp(0, WRAP, weight = 1f))
        c.addView(r, act.lp(t = 10))
        return c
    }

    private fun cn(subject: String): String = subject.replace("\\,", "\u0000").split(',').joinToString("  ") { it.trim().replace("\u0000", ",") }

    private fun addMenu() = act.pickItem("Keystore", listOf("Buat keystore baru", "Impor keystore (.p12 / .keystore / .bks)")) {
        if (it == 0) createDialog() else pickKeystore()
    }

    // ---------- buat ----------

    private fun createDialog() {
        val name = act.textField("mis. rilis-saya", "")
        val alias = act.textField("key0", "key0")
        val pass = act.textField("Kata sandi (min. ${KeystoreManager.MIN_PASS})").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val cn = act.textField("Nama kamu / aplikasi", "")
        val org = act.textField("Organisasi (opsional)", "")
        val country = act.textField("ID", "ID")
        val years = act.textField("25", "25", numeric = true)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(22), act.dp(10), act.dp(22), 0)
            addView(act.fieldLabel("Nama keystore")); addView(name, act.lp(b = 8))
            addView(act.fieldLabel("Alias kunci")); addView(alias, act.lp(b = 8))
            addView(act.fieldLabel("Kata sandi")); addView(pass, act.lp(b = 8))
            addView(act.fieldLabel("Nama (CN)")); addView(cn, act.lp(b = 8))
            addView(act.fieldLabel("Organisasi")); addView(org, act.lp(b = 8))
            val r = LinearLayout(act)
            val a = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            a.addView(act.fieldLabel("Negara")); a.addView(country)
            val b = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            b.addView(act.fieldLabel("Berlaku (tahun)")); b.addView(years)
            r.addView(a, act.lp(0, WRAP, r = 6, weight = 1f))
            r.addView(b, act.lp(0, WRAP, l = 6, weight = 1f))
            addView(r, act.lp(b = 4))
            addView(act.label("RSA 2048-bit. Kata sandi disimpan terenkripsi di perangkat; kamu tetap bisa melihatnya di Detail.", 11f, Pal.dim), act.lp(t = 4))
        }
        val dlg = AlertDialog.Builder(act).setTitle("Buat keystore").setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("Buat", null).setNegativeButton("Batal", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p = pass.text.toString()
                val n = name.text.toString().trim()
                val yr = years.text.toString().toIntOrNull() ?: 25
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                act.toast("Membuat kunci…")
                Thread {
                    try {
                        act.keystores.create(n, alias.text.toString().trim(), p, p, cn.text.toString(), org.text.toString(), country.text.toString().trim(), yr)
                        act.runOnUiThread { dlg.dismiss(); act.toast("Keystore '$n' dibuat. Jangan lupa ekspor cadangannya."); render() }
                    } catch (e: Throwable) {
                        act.runOnUiThread { dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true; act.toast(e.message ?: "Gagal membuat keystore") }
                    }
                }.start()
            }
        }
        dlg.show()
    }

    // ---------- impor ----------

    private fun pickKeystore() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try { act.startActivityForResult(i, MainActivity.REQ_KEYSTORE) } catch (e: Exception) { act.toast("Tidak ada aplikasi pemilih file") }
    }

    /** Dipanggil MainActivity setelah pengguna memilih berkas keystore. */
    fun onPicked(uri: Uri) {
        var display = "keystore"
        try {
            act.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) display = c.getString(0) ?: display
            }
        } catch (_: Exception) {}
        val suggested = display.substringBeforeLast('.').filter { it.isLetterOrDigit() || it in "_-" }.take(32).ifEmpty { "keystore" }
        val name = act.textField("Nama di Senza Studio", suggested)
        val pass = act.textField("Kata sandi keystore").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val alias = act.textField("Alias (kosongkan = otomatis)")
        val keyPass = act.textField("Kata sandi kunci (kosongkan = sama)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(22), act.dp(10), act.dp(22), 0)
            addView(act.label(display, 12f, Pal.sub), act.lp(b = 8))
            addView(act.fieldLabel("Nama")); addView(name, act.lp(b = 8))
            addView(act.fieldLabel("Kata sandi keystore")); addView(pass, act.lp(b = 8))
            addView(act.fieldLabel("Alias")); addView(alias, act.lp(b = 8))
            addView(act.fieldLabel("Kata sandi kunci")); addView(keyPass, act.lp(b = 4))
            addView(act.label("Didukung: PKCS12 (.p12/.pfx) dan BKS. File .jks dari Java perlu diubah ke PKCS12 di PC (keytool -importkeystore … -deststoretype PKCS12).", 11f, Pal.dim), act.lp(t = 6))
        }
        val dlg = AlertDialog.Builder(act).setTitle("Impor keystore").setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("Impor", null).setNegativeButton("Batal", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                try {
                    val stream = act.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Berkas tidak bisa dibuka")
                    stream.use { act.keystores.importFrom(n, it, pass.text.toString(), alias.text.toString(), keyPass.text.toString()) }
                    dlg.dismiss()
                    act.toast("Keystore '$n' diimpor.")
                    render()
                } catch (e: Throwable) {
                    act.toast(e.message ?: "Gagal impor")
                }
            }
        }
        dlg.show()
    }

    // ---------- detail & ekspor ----------

    private fun detail(name: String) {
        val i = act.keystores.infoOrNull(name) ?: return act.toast("Keystore tidak bisa dibuka.")
        val msg = "Alias: ${i.alias}\nJenis: ${i.type}\nPemilik: ${cn(i.subject)}\nBerlaku sampai: ${fmt.format(Date(i.validUntil))}\n\nSHA-256:\n${i.sha256}\n\nSHA-1:\n${i.sha1}"
        AlertDialog.Builder(act).setTitle(name).setMessage(msg)
            .setPositiveButton("Tutup", null)
            .setNeutralButton("Salin SHA-256") { _, _ -> act.copyText("SHA-256", i.sha256) }
            .setNegativeButton("Lihat kata sandi") { _, _ ->
                val sp = act.keystores.storePassword(name) ?: "(tidak terbaca)"
                val kp = act.keystores.keyPassword(name) ?: "(tidak terbaca)"
                AlertDialog.Builder(act).setTitle("Kata sandi '$name'")
                    .setMessage("Keystore: $sp\nKunci: $kp\n\nCatat di tempat aman. Jangan dibagikan.")
                    .setPositiveButton("Tutup", null).show()
            }.show()
    }

    private fun exportKeystore(name: String) {
        pendingExport = name
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "$name.keystore")
        }
        try { act.startActivityForResult(i, MainActivity.REQ_KEYSTORE_EXPORT) } catch (e: Exception) { act.toast("Tidak ada aplikasi pemilih file") }
    }

    /** Dipanggil MainActivity setelah pengguna memilih tempat simpan. */
    fun onExportTarget(uri: Uri) {
        val n = pendingExport ?: return
        pendingExport = null
        try {
            val out = act.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Tujuan tidak bisa ditulis")
            out.use { act.keystores.exportTo(n, it) }
            act.toast("Keystore diekspor. Simpan juga kata sandinya (Detail > Lihat kata sandi).")
        } catch (e: Throwable) {
            act.toast("Gagal ekspor: ${e.message}")
        }
    }
}
