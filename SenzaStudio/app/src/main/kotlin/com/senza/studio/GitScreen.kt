package com.senza.studio

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Git di dalam aplikasi: akun + token, clone, commit, push, pull, cabang, dan riwayat. */
class GitScreen(act: MainActivity) : Screen(act) {

    override val showsNav = false

    private lateinit var body: LinearLayout
    private lateinit var outView: TextView
    private lateinit var outScroll: ScrollView
    private val outBuffer = StringBuilder()
    @Volatile private var busy = false
    @Volatile private var cancelled = false
    private var state: GitState? = null
    private var commits: List<GitCommit> = emptyList()
    private var loadError: String? = null
    private val fmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

    override val view: View = buildView()

    private fun buildView(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(act.topBar("Git", R.drawable.ic_back, { act.back() }), act.lp())
        body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(16), act.dp(4), act.dp(16), act.dp(28))
        }
        col.addView(ScrollView(act).apply { addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        outView = act.mono("", 11f, Pal.text).apply { setPadding(act.dp(8), act.dp(8), act.dp(8), act.dp(8)); setTextIsSelectable(true) }
        outScroll = ScrollView(act).apply {
            background = act.glass(10, 0x66050B2C, 0x22FFFFFF)
            addView(outView)
        }
        return col
    }

    override fun onShow() { reload() }

    private fun dir(): File? = act.project?.let { act.store.dir(it) }

    // ---------- muat status ----------

    private fun reload() {
        val d = dir()
        if (d == null || busy) { render(); return }
        Thread {
            var st: GitState? = null
            var cm: List<GitCommit> = emptyList()
            var err: String? = null
            try {
                if (act.git.isRepo(d)) { st = act.git.status(d); cm = act.git.log(d) }
            } catch (e: Throwable) { err = act.git.friendly(e) }
            act.runOnUiThread { state = st; commits = cm; loadError = err; render() }
        }.start()
    }

    // ---------- tampilan ----------

    private fun render() {
        body.removeAllViews()
        val d = dir()

        // akun
        body.addView(section("Akun Git"))
        val acc = act.card(14)
        val g = act.git
        val who = if (g.authorName.isBlank()) "Belum diatur" else "${g.authorName} <${g.authorEmail}>"
        acc.addView(act.label(who, 14f, Pal.text, true))
        acc.addView(
            act.label(
                "Username: ${g.userName.ifBlank { "-" }}   Token: " + if (g.hasToken()) "tersimpan terenkripsi ✓" else "belum ada",
                12f, if (g.hasToken()) Pal.sub else Pal.warn
            ), act.lp(t = 4)
        )
        acc.addView(act.glassButton("Atur akun & token", R.drawable.ic_user, 13f) { accountDialog() }, act.lp(t = 10))
        acc.addView(
            act.label("Token dibuat di GitHub: Settings > Developer settings > Personal access tokens (izin repo). Hanya HTTPS; SSH belum didukung.", 11f, Pal.dim),
            act.lp(t = 8)
        )
        body.addView(acc, act.lp(b = 16))

        // repositori proyek
        body.addView(section("Repositori proyek" + (act.project?.let { ": $it" } ?: "")))
        val repo = act.card(14)
        when {
            d == null -> repo.addView(act.label("Buka proyek dulu.", 13f, Pal.sub))
            loadError != null -> repo.addView(act.label(loadError!!, 13f, Pal.err))
            state == null -> {
                repo.addView(act.label("Proyek ini belum menjadi repositori Git.", 13f, Pal.sub))
                repo.addView(act.primaryButton("Inisialisasi Git", R.drawable.ic_git, 14f) {
                    guard { act.git.init(d); act.toast("Repositori dibuat (cabang main). File out/ otomatis diabaikan."); reload() }
                }, act.lp(t = 10))
            }
            else -> renderRepo(repo, d, state!!)
        }
        body.addView(repo, act.lp(b = 16))

        // riwayat
        if (state != null && commits.isNotEmpty()) {
            body.addView(section("Riwayat commit"))
            val hist = act.card(12)
            commits.forEachIndexed { i, c ->
                val row = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                row.addView(act.label(c.message.take(80), 13f, Pal.text, true).apply { setSingleLine() })
                row.addView(act.label("${c.id} · ${c.author} · ${fmt.format(Date(c.timeMs))}", 11f, Pal.sub))
                hist.addView(row, act.lp(t = if (i == 0) 0 else 8))
            }
            body.addView(hist, act.lp(b = 16))
        }

        // kloning
        body.addView(section("Kloning repo"))
        val cl = act.card(14)
        cl.addView(act.label("Unduh repo dari GitHub atau layanan Git lain sebagai proyek baru.", 12f, Pal.sub))
        cl.addView(act.glassButton("Kloning dari URL…", R.drawable.ic_download, 13f) { cloneDialog() }, act.lp(t = 10))
        cl.addView(
            act.label("Repo yang dibuat dari Senza Studio langsung bisa dibuka. Repo bergaya Gradle (app/src/main/…) belum disusun ulang otomatis: kloning di PC lalu Impor .zip.", 11f, Pal.dim),
            act.lp(t = 8)
        )
        body.addView(cl, act.lp(b = 16))

        // keluaran
        body.addView(section("Keluaran"))
        (outScroll.parent as? android.view.ViewGroup)?.removeView(outScroll)
        val outCard = act.card(10)
        outCard.addView(outScroll, LinearLayout.LayoutParams(MATCH, act.dp(160)))
        if (busy) outCard.addView(act.glassButton("Batalkan", R.drawable.ic_close, 13f, Pal.err) {
            cancelled = true; act.toast("Membatalkan…")
        }, act.lp(t = 8))
        body.addView(outCard)
        outView.text = synchronized(outBuffer) { outBuffer.toString() }
    }

    private fun renderRepo(card: LinearLayout, d: File, s: GitState) {
        val head = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(act.icon(R.drawable.ic_git, 20, Pal.primaryLight), LinearLayout.LayoutParams(act.dp(20), act.dp(20)))
        head.addView(act.label(s.branch, 15f, Pal.text, true), act.lp(l = 8))
        if (s.ahead > 0) head.addView(act.label("  ↑${s.ahead}", 13f, Pal.ok, true))
        if (s.behind > 0) head.addView(act.label("  ↓${s.behind}", 13f, Pal.warn, true))
        card.addView(head)
        card.addView(
            act.mono(s.remote ?: "(remote belum diatur)", 11f, if (s.remote == null) Pal.warn else Pal.sub),
            act.lp(t = 4)
        )
        if (!s.hasCommits) card.addView(act.label("Belum ada commit.", 12f, Pal.sub), act.lp(t = 6))
        card.addView(
            act.label(if (s.clean) "Tidak ada perubahan." else "${s.changes.size} perubahan:", 13f, if (s.clean) Pal.ok else Pal.text, true),
            act.lp(t = 10, b = 4)
        )
        s.changes.take(25).forEach { c ->
            val color = when (c.code) { '?' -> Pal.sub; 'A' -> Pal.ok; 'D' -> Pal.err; 'U' -> Pal.warn; else -> Pal.primaryLight }
            card.addView(act.mono("${c.code}  ${c.path}", 11f, color))
        }
        if (s.changes.size > 25) card.addView(act.label("… dan ${s.changes.size - 25} lainnya", 11f, Pal.dim))

        val r1 = LinearLayout(act)
        r1.addView(act.primaryButton("Commit", R.drawable.ic_check, 13f) { commitDialog(d) }, act.lp(0, WRAP, r = 6, weight = 1f))
        r1.addView(act.glassButton("Push", R.drawable.ic_share, 13f) { pushOp(d) }, act.lp(0, WRAP, r = 6, weight = 1f))
        r1.addView(act.glassButton("Pull", R.drawable.ic_download, 13f) { pullOp(d) }, act.lp(0, WRAP, weight = 1f))
        card.addView(r1, act.lp(t = 12))
        val r2 = LinearLayout(act)
        r2.addView(act.glassButton("Cabang", R.drawable.ic_git, 13f) { branchDialog(d) }, act.lp(0, WRAP, r = 6, weight = 1f))
        r2.addView(act.glassButton("Remote", R.drawable.ic_link, 13f) { remoteDialog(d) }, act.lp(0, WRAP, r = 6, weight = 1f))
        r2.addView(act.glassButton("Muat ulang", R.drawable.ic_sync, 13f) { reload() }, act.lp(0, WRAP, weight = 1f))
        card.addView(r2, act.lp(t = 8))
    }

    private fun section(t: String): TextView = act.label(t, 13f, Pal.sub, true).apply {
        setPadding(act.dp(4), act.dp(4), 0, act.dp(6))
    }

    // ---------- dialog ----------

    private fun accountDialog() {
        val g = act.git
        val user = act.textField("Username GitHub", g.userName)
        val name = act.textField("Nama untuk commit", g.authorName)
        val email = act.textField("Email untuk commit", g.authorEmail).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val token = act.textField(if (g.hasToken()) "Token baru (kosongkan = tetap)" else "Personal access token").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(22), act.dp(10), act.dp(22), 0)
            addView(act.fieldLabel("Username")); addView(user, act.lp(b = 8))
            addView(act.fieldLabel("Nama")); addView(name, act.lp(b = 8))
            addView(act.fieldLabel("Email")); addView(email, act.lp(b = 8))
            addView(act.fieldLabel("Token")); addView(token, act.lp(b = 4))
        }
        val b = AlertDialog.Builder(act).setTitle("Akun Git").setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("Simpan") { _, _ ->
                g.userName = user.text.toString()
                g.authorName = name.text.toString()
                g.authorEmail = email.text.toString()
                val t = token.text.toString().trim()
                if (t.isNotEmpty()) g.setToken(t)
                render()
            }
            .setNegativeButton("Batal", null)
        if (g.hasToken()) b.setNeutralButton("Hapus token") { _, _ -> g.setToken(null); render() }
        b.show()
    }

    private fun commitDialog(d: File) {
        act.promptText("Commit semua perubahan", "Pesan commit, mis. Tambah layar login") { msg ->
            if (msg.isEmpty()) return@promptText
            act.editor.saveNow()
            runOp("commit") { _ ->
                val id = act.git.commitAll(d, msg)
                "Commit $id dibuat."
            }
        }
    }

    private fun pushOp(d: File) = runOp("push") { m -> act.git.push(d, m) }

    private fun pullOp(d: File) = act.confirm("Pull akan menggabungkan perubahan dari server ke proyek ini. Commit dulu perubahanmu agar aman. Lanjut?") {
        act.editor.saveNow()
        runOp("pull", reloadFiles = true) { m -> act.git.pull(d, m) }
    }

    private fun remoteDialog(d: File) {
        val cur = try { act.git.remoteUrl(d) ?: "" } catch (_: Throwable) { "" }
        act.promptText("Alamat remote (origin)", "https://github.com/nama/repo.git", cur) { url ->
            if (url.isEmpty()) return@promptText
            guard { act.git.setRemote(d, url); act.toast("Remote diatur."); reload() }
        }
    }

    private fun branchDialog(d: File) {
        val names = try { act.git.branches(d) } catch (e: Throwable) { return act.toast(act.git.friendly(e)) }
        val items = names + "+ Cabang baru…"
        act.pickItem("Cabang", items) { i ->
            if (i == names.size) {
                act.promptText("Cabang baru", "nama-cabang") { n ->
                    if (n.isEmpty()) return@promptText
                    act.editor.saveNow()
                    runOp("buat cabang $n", reloadFiles = true) { _ -> act.git.checkout(d, n, true); "Sekarang di cabang $n." }
                }
            } else {
                act.editor.saveNow()
                runOp("pindah ke ${names[i]}", reloadFiles = true) { _ -> act.git.checkout(d, names[i], false); "Sekarang di cabang ${names[i]}." }
            }
        }
    }

    private fun cloneDialog() {
        val url = act.textField("https://github.com/nama/repo.git")
        val name = act.textField("Nama proyek")
        var auto = true
        var setting = false
        name.onChanged { if (!setting) auto = false }
        url.onChanged {
            if (auto) {
                setting = true
                name.setText(it.toString().trim().trimEnd('/').substringAfterLast('/').removeSuffix(".git").filter { c -> c.isLetterOrDigit() || c in " _-" })
                setting = false
            }
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(22), act.dp(10), act.dp(22), 0)
            addView(act.fieldLabel("URL repo (HTTPS)")); addView(url, act.lp(b = 8))
            addView(act.fieldLabel("Nama proyek di Senza Studio")); addView(name, act.lp(b = 4))
            addView(act.label("Repo privat butuh token di Akun Git.", 11f, Pal.dim), act.lp(t = 4))
        }
        AlertDialog.Builder(act).setTitle("Kloning repo").setView(box)
            .setPositiveButton("Kloning") { _, _ -> doClone(url.text.toString().trim(), name.text.toString().trim()) }
            .setNegativeButton("Batal", null).show()
    }

    private fun doClone(url: String, name: String) {
        if (busy) return act.toast("Tunggu proses sebelumnya selesai")
        if (!Regex("[A-Za-z0-9][A-Za-z0-9 _-]{0,39}").matches(name)) return act.toast("Nama proyek: huruf, angka, spasi, _ atau - (maks 40).")
        val target = act.store.dir(name)
        if (target.exists()) return act.toast("Proyek '$name' sudah ada.")
        runOp("kloning $name", after = { ok ->
            if (!ok) target.deleteRecursively()
            else {
                if (!File(target, "AndroidManifest.xml").isFile)
                    act.toast("Repo terunduh, tetapi tidak ada AndroidManifest.xml di akar. Kalau ini proyek Gradle, kloning di PC lalu Impor .zip.")
                act.openProject(name)
                act.navigate(act.explorer)
            }
        }) { m ->
            act.git.clone(url, target, m)
            "Selesai: proyek '$name' dibuat dari repo."
        }
    }

    // ---------- menjalankan operasi ----------

    private fun appendOut(t: String) {
        synchronized(outBuffer) { outBuffer.append(t) }
        act.runOnUiThread {
            outView.append(t)
            outScroll.post { outScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun runOp(title: String, reloadFiles: Boolean = false, after: ((Boolean) -> Unit)? = null, op: (GitMonitor) -> String) {
        if (busy) return act.toast("Tunggu proses sebelumnya selesai")
        busy = true
        cancelled = false
        synchronized(outBuffer) { outBuffer.setLength(0) }
        act.runOnUiThread { outView.text = ""; render() }
        appendOut("> $title\n")
        Thread {
            var ok = false
            try {
                val msg = op(GitMonitor({ appendOut(act.git.scrub(it) + "\n") }, { cancelled }))
                appendOut(act.git.scrub(msg) + "\n")
                ok = true
            } catch (e: Throwable) {
                appendOut("GAGAL: " + act.git.friendly(e) + "\n")
            } finally {
                busy = false
                val success = ok
                act.runOnUiThread {
                    if (reloadFiles && success) {
                        act.editor.clearLoaded()
                        CodeAssist.invalidate()
                        act.explorer.refresh()
                    }
                    after?.invoke(success)
                    reload()
                }
            }
        }.start()
    }

    private fun guard(block: () -> Unit) {
        try { block() } catch (e: Throwable) { act.toast(act.git.friendly(e)) }
    }
}
