package com.senza.studio

import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** Tentang aplikasi Senza Studio dan pengembangnya (SenzaProject). */
class AboutScreen(act: MainActivity) : Screen(act) {

    override val showsNav = false

    override val view: View = buildView()

    private fun buildView(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(act.topBar("About", R.drawable.ic_back, { act.back() }), act.lp())

        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(16), act.dp(4), act.dp(16), act.dp(28))
        }

        // ---- aplikasi ----
        val head = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        head.addView(ImageView(act).apply { setImageResource(R.drawable.logo_tile); adjustViewBounds = true },
            LinearLayout.LayoutParams(act.dp(120), act.dp(120)))
        head.addView(act.label("Senza Studio", 26f, Pal.text, true), act.lp(WRAP, WRAP, t = 8))
        head.addView(act.label("Versi ${act.versionName}", 13f, Pal.sub), act.lp(WRAP, WRAP, t = 2))
        head.addView(act.label("IDE Android Lengkap di HP Kamu", 14f, Pal.primaryLight, true), act.lp(WRAP, WRAP, t = 8))
        body.addView(head, act.lp(b = 16))

        val desc = act.card(16)
        desc.addView(
            act.label(
                "Senza Studio adalah aplikasi pembuat aplikasi Android yang bisa kamu gunakan langsung di perangkat Android. " +
                    "Dengan tampilan modern bertema Liquid Glass, kamu bisa menulis kode, melihat preview UI, " +
                    "mengelola proyek, hingga membangun APK — semuanya dalam satu aplikasi.",
                14f, Pal.text
            )
        )
        body.addView(desc, act.lp(b = 16))

        // ---- fitur ----
        body.addView(act.label("Fitur Utama", 17f, Pal.text, true), act.lp(b = 8))
        val feats = act.card(8)
        listOf(
            Triple(R.drawable.ic_code, "Code Editor", "Java dan XML dengan syntax highlighting, nomor baris, undo/redo, cari & ganti."),
            Triple(R.drawable.ic_preview, "UI Preview Live", "Lihat hasil layout XML langsung di layar HP."),
            Triple(R.drawable.ic_folder, "Project Explorer", "Kelola file dan folder proyek: tambah, ganti nama, hapus."),
            Triple(R.drawable.ic_build, "Build APK & Build Log", "Bangun APK debug/release dan lihat log secara real-time."),
            Triple(R.drawable.ic_terminal, "Terminal", "Jalankan perintah shell dan build dari terminal."),
            Triple(R.drawable.ic_files, "File Manager", "Jelajahi file, impor dan ekspor proyek dalam bentuk .zip.")
        ).forEach { (ic, t, d) ->
            val r = LinearLayout(act).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(act.dp(8), act.dp(10), act.dp(8), act.dp(10))
            }
            r.addView(act.icon(ic, 24, Pal.primaryLight), LinearLayout.LayoutParams(act.dp(24), act.dp(24)))
            val tx = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            tx.addView(act.label(t, 14f, Pal.text, true))
            tx.addView(act.label(d, 12f, Pal.sub))
            r.addView(tx, act.lp(0, WRAP, l = 14, weight = 1f))
            feats.addView(r)
        }
        body.addView(feats, act.lp(b = 16))

        // ---- batasan ----
        val warn = act.card(16, Pal.warnBg)
        val wh = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        wh.addView(act.icon(R.drawable.ic_warning, 20, Pal.warn), LinearLayout.LayoutParams(act.dp(20), act.dp(20)))
        wh.addView(act.label("Batasan Build APK di Perangkat Mobile", 14f, Pal.warn, true), act.lp(l = 8))
        warn.addView(wh)
        listOf(
            "Proses build lebih lambat dibanding PC dan memakai banyak RAM.",
            "Yang didukung: kode Java dan Kotlin, resource XML, serta library Maven/AAR (AppCompat, Material, dll). Annotation processing (Room, Hilt, Glide compiler) dan Jetpack Compose belum didukung.",
            "Build Kotlin pertama kali lambat dan memakai banyak RAM (compiler jalan di proses terpisah).",
            "Build butuh alat aapt2 ARM64 yang ikut terpasang di aplikasi (lihat Status alat di layar Build).",
            "Tidak cocok untuk aplikasi besar/kompleks (mis. game berat, engine khusus).",
            "Untuk proyek kompleks dan build yang stabil, gunakan PC atau laptop."
        ).forEach { warn.addView(act.label("• $it", 12f, Pal.text), act.lp(t = 6)) }
        body.addView(warn, act.lp(b = 16))

        // ---- pengembang ----
        body.addView(act.label("Tentang Pengembang", 17f, Pal.text, true), act.lp(b = 8))
        val dev = act.card(16)
        val dh = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        dh.addView(ImageView(act).apply { setImageResource(R.drawable.logo_tile); adjustViewBounds = true },
            LinearLayout.LayoutParams(act.dp(52), act.dp(52)))
        val dt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        dt.addView(act.label("SenzaProject", 18f, Pal.text, true))
        dt.addView(act.label("Pengembang & pemilik Senza Studio", 12f, Pal.sub))
        dh.addView(dt, act.lp(l = 14))
        dev.addView(dh)
        dev.addView(
            act.label(
                "Senza Studio dibuat oleh SenzaProject agar siapa pun bisa belajar dan membuat aplikasi Android " +
                    "kapan saja dan di mana saja, langsung dari ponsel.",
                13f, Pal.text
            ), act.lp(t = 12)
        )
        dev.addView(act.glassButton("GitHub SenzaProject", R.drawable.ic_link) {
            act.openUrl("https://github.com/SenzaProject")
        }, act.lp(t = 12))
        body.addView(dev, act.lp(b = 20))

        body.addView(act.label("Senza Studio", 18f, Pal.primaryLight, true).apply { gravity = Gravity.CENTER })
        body.addView(act.label("Your App, Your Way", 13f, Pal.sub).apply { gravity = Gravity.CENTER }, act.lp(t = 2))
        body.addView(act.label("Powered by SenzaProject", 12f, Pal.dim).apply { gravity = Gravity.CENTER }, act.lp(t = 10))
        body.addView(act.label("© 2026 SenzaProject", 11f, Pal.dim).apply { gravity = Gravity.CENTER }, act.lp(t = 2))

        col.addView(ScrollView(act).apply { addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return col
    }
}
