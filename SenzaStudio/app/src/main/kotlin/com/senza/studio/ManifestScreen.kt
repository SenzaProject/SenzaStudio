package com.senza.studio

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File

/** Editor manifest visual: nama aplikasi, izin, dan daftar activity. */
class ManifestScreen(act: MainActivity) : Screen(act) {

    override val showsNav = false

    private lateinit var body: LinearLayout
    override val view: View = buildView()

    private fun buildView(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            act.topBar(
                "Manifest", R.drawable.ic_back, { act.back() },
                R.drawable.ic_code to { openAsText() }
            ), act.lp()
        )
        body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(16), act.dp(4), act.dp(16), act.dp(28))
        }
        col.addView(ScrollView(act).apply { addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return col
    }

    private fun file(): File? = act.project?.let { File(act.store.dir(it), "AndroidManifest.xml") }

    override fun onShow() {
        act.editor.saveNow()
        rebuild()
    }

    private fun openAsText() {
        if (file()?.isFile != true) return act.toast("AndroidManifest.xml tidak ada")
        act.openFile("AndroidManifest.xml")
    }

    private fun edit(block: (String) -> String) {
        val f = file() ?: return
        try {
            val t = f.readText()
            val n = block(t)
            if (n != t) f.writeText(n)
            CodeAssist.invalidate()
            act.editor.clearLoaded()
            act.explorer.refresh()
        } catch (e: Exception) {
            act.toast(e.message ?: "Gagal mengubah manifest")
        }
        rebuild()
    }

    private fun section(t: String): TextView = act.label(t, 13f, Pal.sub, true).apply {
        setPadding(act.dp(4), act.dp(4), 0, act.dp(6))
    }

    private fun rebuild() {
        body.removeAllViews()
        val p = act.project
        val f = file()
        if (p == null || f == null || !f.isFile) {
            body.addView(act.label("Buka proyek yang punya AndroidManifest.xml.", 14f, Pal.sub), act.lp(t = 20))
            return
        }
        val text = f.readText()

        // ---- aplikasi ----
        body.addView(section("Aplikasi"))
        val app = act.card(14)
        app.addView(act.label("Package", 12f, Pal.sub))
        app.addView(act.mono(act.store.packageOf(p).ifEmpty { "(tidak terbaca)" }, 13f, Pal.text), act.lp(b = 10))
        app.addView(act.label("Nama aplikasi", 12f, Pal.sub))
        val nameRow = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        nameRow.addView(act.label(act.store.appLabel(p), 15f, Pal.text, true), act.lp(0, WRAP, weight = 1f))
        nameRow.addView(act.glassButton("Ubah", R.drawable.ic_edit, 12f) {
            act.promptText("Nama aplikasi", "Nama yang tampil di layar utama", act.store.appLabel(p)) { n ->
                if (n.isEmpty() || n.any { it in "<>&\"" }) act.toast("Nama tidak boleh kosong atau berisi < > & \"")
                else {
                    if (!act.store.setAppLabel(p, n)) act.toast("String app_name tidak ditemukan di res/values/strings.xml")
                    rebuild()
                }
            }
        })
        app.addView(nameRow)
        body.addView(app, act.lp(b = 16))

        // ---- izin ----
        body.addView(section("Izin (permission)"))
        val perms = act.card(14)
        val have = ManifestTools.permissions(text).toMutableList()
        val known = ManifestTools.COMMON_PERMISSIONS
        for ((perm, label) in known) {
            perms.addView(switchRow(label, perm.removePrefix("android.permission."), perm in have) { on ->
                edit { ManifestTools.setPermission(it, perm, on) }
            })
        }
        val extra = have.filter { h -> known.none { it.first == h } }.distinct()
        for (perm in extra) {
            perms.addView(switchRow(perm.substringAfterLast('.'), perm, true) { on ->
                edit { ManifestTools.setPermission(it, perm, on) }
            })
        }
        perms.addView(act.glassButton("Tambah izin lain…", R.drawable.ic_plus, 13f) {
            act.promptText("Tambah izin", "mis. android.permission.NFC") { n ->
                if (!Regex("[A-Za-z][A-Za-z0-9_.]*\\.[A-Za-z0-9_]+").matches(n)) act.toast("Nama izin tidak valid")
                else edit { ManifestTools.setPermission(it, n, true) }
            }
        }, act.lp(t = 8))
        body.addView(perms, act.lp(b = 16))

        // ---- activity ----
        body.addView(section("Activity"))
        val acts = ManifestTools.activities(text)
        if (acts.isEmpty()) body.addView(act.label("Belum ada activity di manifest.", 13f, Pal.sub), act.lp(b = 8))
        for (a in acts) {
            val c = act.card(12)
            val head = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(act.label(a.name, 15f, Pal.text, true), act.lp(0, WRAP, weight = 1f))
            if (a.launcher) head.addView(act.label("PELUNCUR", 11f, Pal.ok, true).apply {
                background = act.glass(10, Pal.okBg, Pal.ok)
                setPadding(act.dp(8), act.dp(3), act.dp(8), act.dp(3))
            })
            c.addView(head)
            val row = LinearLayout(act)
            row.addView(act.glassButton("Buka", R.drawable.ic_code, 12f) { openClass(a.name) }, act.lp(0, WRAP, r = 6, weight = 1f))
            if (!a.launcher) {
                row.addView(act.glassButton("Peluncur", R.drawable.ic_play, 12f) {
                    edit { ManifestTools.setLauncher(it, a.name) }
                }, act.lp(0, WRAP, r = 6, weight = 1f))
            }
            row.addView(act.glassButton("Hapus", R.drawable.ic_trash, 12f, Pal.err) {
                act.confirm("Hapus ${a.name} dari manifest? (file kelasnya tidak dihapus)") {
                    edit { ManifestTools.removeActivity(it, a.name) }
                }
            }, act.lp(0, WRAP, weight = 1f))
            c.addView(row, act.lp(t = 8))
            body.addView(c, act.lp(b = 8))
        }
        body.addView(act.primaryButton("Tambah activity", R.drawable.ic_plus, 14f) { addActivity() }, act.lp(t = 4, b = 16))

        body.addView(
            act.label("Perubahan langsung ditulis ke AndroidManifest.xml. Ikon di menu proyek > Pembuat ikon.", 11f, Pal.dim)
        )
    }

    private fun switchRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
        LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, act.dp(5), 0, act.dp(5))
            val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            t.addView(act.label(title, 14f, Pal.text))
            t.addView(act.label(sub, 10f, Pal.dim).apply { setSingleLine() })
            addView(t, act.lp(0, WRAP, weight = 1f))
            addView(Switch(act).apply {
                isChecked = on
                setOnCheckedChangeListener { _, v -> onChange(v) }
            })
        }

    private fun openClass(name: String) {
        val p = act.project ?: return
        val rel = CodeAssist.fileForClass(act.store.dir(p), name.removePrefix("."))
        if (rel == null) act.toast("File kelas $name tidak ditemukan di src/") else act.openFile(rel)
    }

    private fun addActivity() {
        val p = act.project ?: return act.toast("Buka proyek dulu")
        act.promptText("Activity baru", "Nama kelas, mis. SettingsActivity") { n ->
            if (!Regex("[A-Z][A-Za-z0-9_]*").matches(n)) return@promptText act.toast("Nama kelas diawali huruf besar: huruf, angka, _")
            val pkg = act.store.packageOf(p)
            if (pkg.isEmpty()) return@promptText act.toast("Package manifest tidak terbaca")
            try {
                val rel = ManifestTools.createActivityFiles(act.store.dir(p), pkg, n)
                edit { ManifestTools.addActivity(it, n, false) }
                act.toast("$n dibuat (kelas + layout) dan didaftarkan")
                act.explorer.refresh()
                act.openFile(rel)
            } catch (e: Exception) {
                act.toast(e.message ?: "Gagal membuat activity")
            }
        }
    }
}
