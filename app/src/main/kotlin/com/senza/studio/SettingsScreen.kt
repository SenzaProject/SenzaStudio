package com.senza.studio

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Pengaturan editor, umum, dan alat build. */
class SettingsScreen(act: MainActivity) : Screen(act) {

    override val showsNav = false

    private lateinit var body: LinearLayout
    override val view: View = buildView()

    private fun buildView(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(act.topBar("Settings", R.drawable.ic_back, { act.back() }), act.lp())
        body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(16), act.dp(4), act.dp(16), act.dp(28))
        }
        col.addView(ScrollView(act).apply { addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return col
    }

    override fun onShow() { rebuild() }

    private fun rebuild() {
        body.removeAllViews()
        val prefs = act.prefs

        // ---- editor ----
        body.addView(section("Editor"))
        val ed = act.card(14)
        val size = prefs.getInt("font_size", 13)
        val sizeLabel = act.label("Ukuran font: ${size}sp", 14f, Pal.text)
        ed.addView(sizeLabel)
        ed.addView(SeekBar(act).apply {
            max = 12
            progress = size - 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    sizeLabel.text = "Ukuran font: ${p + 10}sp"
                    if (fromUser) prefs.edit().putInt("font_size", p + 10).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, act.lp(t = 6, b = 8))
        ed.addView(switchRow("Tampilkan nomor baris", prefs.getBoolean("line_numbers", true)) {
            prefs.edit().putBoolean("line_numbers", it).apply()
        })
        ed.addView(switchRow("Simpan otomatis", prefs.getBoolean("auto_save", true)) {
            prefs.edit().putBoolean("auto_save", it).apply()
        })
        ed.addView(switchRow("Autocomplete (saran kode)", prefs.getBoolean("autocomplete", true)) {
            prefs.edit().putBoolean("autocomplete", it).apply()
        })
        ed.addView(switchRow("Diagnostik langsung (garis merah)", prefs.getBoolean("diagnostics", true)) {
            prefs.edit().putBoolean("diagnostics", it).apply()
        })
        ed.addView(switchRow("Auto-indent & kurung berpasangan", prefs.getBoolean("typing_assist", true)) {
            prefs.edit().putBoolean("typing_assist", it).apply()
        })
        ed.addView(switchRow("Run: pasang & buka aplikasi otomatis", prefs.getBoolean("run_after_build", true)) {
            prefs.edit().putBoolean("run_after_build", it).apply()
        })
        body.addView(ed, act.lp(b = 16))

        // ---- umum ----
        body.addView(section("Umum"))
        val gen = act.card(14)
        gen.addView(switchRow("Lewati layar sambutan saat dibuka", prefs.getBoolean("skip_splash", false)) {
            prefs.edit().putBoolean("skip_splash", it).apply()
        })
        body.addView(gen, act.lp(b = 16))

        // ---- alat build ----
        body.addView(section("Alat build"))
        val tools = act.card(14)
        listOf(
            "Compiler Java (ECJ)" to true,
            "Compiler Kotlin (proses terpisah)" to KotlinSupport.available(),
            "Dexer (D8)" to true,
            "Pengecil kode release (R8)" to true,
            "Pustaka Android (android.jar)" to act.builder.hasAndroidJar(),
            "Alat resource (aapt2)" to act.builder.hasAapt2()
        ).forEach { (n, ok) ->
            val r = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(act.icon(if (ok) R.drawable.ic_check else R.drawable.ic_warning, 18, if (ok) Pal.ok else Pal.err),
                LinearLayout.LayoutParams(act.dp(18), act.dp(18)))
            r.addView(act.label(n, 14f, Pal.text), act.lp(0, WRAP, l = 10, weight = 1f))
            r.addView(act.label(if (ok) "Siap" else "Belum ada", 12f, if (ok) Pal.ok else Pal.err, true))
            tools.addView(r, act.lp(t = 4, b = 4))
        }
        fun heapText(mb: Int) = if (mb == 0) "Otomatis (${KotlinSupport.heapMb(act)} MB)" else "$mb MB"
        val heapNow = prefs.getInt("kotlin_heap_mb", 0)
        val heapLabel = act.label("Memori compiler Kotlin: ${heapText(heapNow)}", 14f, Pal.text)
        tools.addView(heapLabel, act.lp(t = 10))
        tools.addView(SeekBar(act).apply {
            max = 7
            progress = if (heapNow == 0) 0 else ((heapNow - 1024) / 512 + 1).coerceIn(1, 7)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val mb = if (p == 0) 0 else 1024 + (p - 1) * 512
                    heapLabel.text = "Memori compiler Kotlin: ${heapText(mb)}"
                    if (fromUser) prefs.edit().putInt("kotlin_heap_mb", mb).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, act.lp(t = 4, b = 4))
        tools.addView(
            act.label("Naikkan jika build Kotlin gagal karena kehabisan memori. Compiler jalan di proses terpisah, jadi aplikasi tidak ikut mati.", 11f, Pal.dim)
        )
        tools.addView(act.glassButton("Cara memperbaiki / info alat", R.drawable.ic_info) { act.showToolsHelp() }, act.lp(t = 10))
        tools.addView(act.glassButton("Bersihkan cache build", R.drawable.ic_trash) {
            act.confirm("Hapus folder hasil build (out/) di semua proyek?") {
                var n = 0
                act.store.list().forEach { p ->
                    val o = java.io.File(act.store.dir(p), "out")
                    if (o.exists() && o.deleteRecursively()) n++
                }
                act.lastApk = null
                act.explorer.refresh()
                act.toast("Cache dibersihkan ($n proyek)")
            }
        }, act.lp(t = 8))
        body.addView(tools, act.lp(b = 16))

        // ---- data ----
        body.addView(section("Penyimpanan"))
        val data = act.card(14)
        data.addView(act.label("Proyek disimpan di penyimpanan privat aplikasi:", 12f, Pal.sub))
        data.addView(act.mono(act.store.root.path, 11f, Pal.text).apply { setTextIsSelectable(true) }, act.lp(t = 4))
        data.addView(
            act.label("Pindahkan proyek lewat Impor/Ekspor .zip (menu Project atau File Manager).", 12f, Pal.sub),
            act.lp(t = 8)
        )
        body.addView(data, act.lp(b = 16))

        body.addView(act.glassButton("Tentang Senza Studio", R.drawable.ic_info) { act.navigate(act.about) })
    }

    private fun section(t: String): TextView = act.label(t, 13f, Pal.sub, true).apply {
        setPadding(act.dp(4), act.dp(4), 0, act.dp(6))
    }

    private fun switchRow(text: String, on: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
        LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(act.label(text, 14f, Pal.text), act.lp(0, WRAP, weight = 1f))
            addView(Switch(act).apply {
                isChecked = on
                setOnCheckedChangeListener { _, v -> onChange(v) }
            })
            setPadding(0, act.dp(6), 0, act.dp(6))
        }
}
