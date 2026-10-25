package com.senza.studio

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.content.res.ColorStateList
import java.io.File

/** Build APK: pengaturan versi, log build langsung, lalu Install / Share / Simpan. */
class BuildScreen(act: MainActivity) : Screen(act), BuildObserver {

    override val isTab = true

    private var release = false
    private var shrink = false
    private var flavor = ""
    private var signing = ""
    private var bundle = false

    private lateinit var projectField: TextView
    private lateinit var appName: android.widget.EditText
    private lateinit var versionCode: android.widget.EditText
    private lateinit var versionName: android.widget.EditText
    private lateinit var toolsRow: LinearLayout
    private lateinit var startBtn: LinearLayout
    private lateinit var cancelBtn: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var stepText: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var resultBox: LinearLayout
    private lateinit var typeBar: LinearLayout
    private lateinit var typeHolder: LinearLayout

    override val view: View = buildView()

    private fun buildView(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            act.topBar(
                "Build APK", R.drawable.ic_back, { act.back() },
                R.drawable.ic_settings to { act.navigate(act.settings) }
            ), act.lp()
        )
        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(16), act.dp(4), act.dp(16), act.dp(24))
        }

        projectField = act.label("", 14f, Pal.text, true).apply {
            background = act.glassPress(14, Pal.field)
            setPadding(act.dp(14), act.dp(13), act.dp(14), act.dp(13))
            setSingleLine()
            isClickable = true
            setOnClickListener { act.projectChooser() }
        }
        body.addView(act.fieldLabel("Proyek"))
        body.addView(projectField, act.lp(b = 12))

        typeHolder = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        body.addView(typeHolder, act.lp(b = 12))

        appName = act.textField("Nama aplikasi")
        versionCode = act.textField("1", "1", numeric = true)
        versionName = act.textField("1.0.0", "1.0.0")
        body.addView(act.fieldLabel("App Name"))
        body.addView(appName, act.lp(b = 12))
        val vrow = LinearLayout(act)
        val c1 = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        c1.addView(act.fieldLabel("Version Code")); c1.addView(versionCode)
        val c2 = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        c2.addView(act.fieldLabel("Version Name")); c2.addView(versionName)
        vrow.addView(c1, act.lp(0, WRAP, r = 6, weight = 1f))
        vrow.addView(c2, act.lp(0, WRAP, l = 6, weight = 1f))
        body.addView(vrow, act.lp(b = 12))

        toolsRow = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = act.glassPress(14)
            setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))
            isClickable = true
            setOnClickListener { act.showToolsHelp() }
        }
        body.addView(toolsRow, act.lp(b = 14))

        startBtn = act.primaryButton("Start Build", R.drawable.ic_play) { startFromUi() }
        body.addView(startBtn, act.lp(b = 8))
        cancelBtn = act.glassButton("Batalkan build", R.drawable.ic_close, 13f, Pal.err) {
            BuildControl.request()
            act.toast("Membatalkan build…")
        }.apply { visibility = View.GONE }
        body.addView(cancelBtn, act.lp(b = 16))

        // kartu output
        val out = act.card(14)
        val head = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(act.label("Build Output", 15f, Pal.text, true), act.lp(0, WRAP, weight = 1f))
        head.addView(act.label("Clear", 13f, Pal.primaryLight, true).apply {
            setPadding(act.dp(10), act.dp(4), act.dp(4), act.dp(4))
            isClickable = true
            setOnClickListener { clearLog() }
        })
        out.addView(head, act.lp(b = 8))
        stepText = act.label("Belum ada build.", 12f, Pal.sub)
        out.addView(stepText, act.lp(b = 6))
        progress = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = SenzaBuilder.TOTAL_STEPS
            progressTintList = ColorStateList.valueOf(Pal.primaryLight)
            visibility = View.GONE
        }
        out.addView(progress, act.lp(MATCH, act.dp(8), b = 8))
        logView = TextView(act).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Pal.text)
            setPadding(act.dp(8), act.dp(8), act.dp(8), act.dp(8))
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(act).apply {
            background = act.glass(10, 0x66050B2C, 0x22FFFFFF)
            addView(logView)
        }
        out.addView(logScroll, LinearLayout.LayoutParams(MATCH, act.dp(240)))
        resultBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        out.addView(resultBox, act.lp(t = 10))
        body.addView(out, act.lp())

        col.addView(ScrollView(act).apply { addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return col
    }

    // =====================================================================

    override fun onShow() {
        act.observers.add(this)
        refreshForm()
        refreshTools()
        // tampilkan log yang sudah ada
        val text = synchronized(act.buildLog) { act.buildLog.toString() }
        logView.text = ""
        text.lines().forEach { if (it.isNotEmpty() || text.isNotEmpty()) appendColored(it + "\n") }
        if (text.isEmpty()) logView.text = ""
        showState()
    }

    override fun onHide() { act.observers.remove(this) }

    fun onProjectChanged() { if (::appName.isInitialized) refreshForm() }

    private fun refreshForm() {
        val p = act.project
        projectField.text = "▾  " + (p ?: "(belum ada proyek)")
        if (p == null) return
        if (act.building) return
        val cfg = act.store.config(p)
        release = cfg.release
        shrink = cfg.shrink
        flavor = cfg.flavor
        signing = cfg.signing
        bundle = cfg.bundle
        appName.setText(cfg.appName)
        versionCode.setText(cfg.versionCode.toString())
        versionName.setText(cfg.versionName)
        typeHolder.removeAllViews()
        typeHolder.addView(act.fieldLabel("Build Type"))
        val shrinkRow = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(act.label("Perkecil & acak kode (R8)", 14f, Pal.text), act.lp(0, WRAP, weight = 1f))
            addView(Switch(act).apply {
                isChecked = shrink
                setOnCheckedChangeListener { _, v -> shrink = v }
            })
            visibility = if (release) View.VISIBLE else View.GONE
        }
        val g = try { GradleLite.read(act.store.dir(p)) } catch (_: Exception) { GradleInfo() }
        val flavors = g.flavors.keys.toList()
        if (flavor !in flavors) flavor = ""
        if (flavors.isNotEmpty()) {
            val flavorField = act.label("", 14f, Pal.text).apply {
                background = act.glassPress(12, Pal.field)
                setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(12))
                isClickable = true
            }
            fun paintFlavor() { flavorField.text = "▾  " + (if (flavor.isEmpty()) "(tanpa flavor)" else flavor) }
            flavorField.setOnClickListener {
                act.pickItem("Product flavor", listOf("(tanpa flavor)") + flavors) { i ->
                    flavor = if (i == 0) "" else flavors[i - 1]
                    paintFlavor()
                }
            }
            paintFlavor()
            typeHolder.addView(act.fieldLabel("Flavor (dari build.gradle)"), 0)
            typeHolder.addView(flavorField, 1, act.lp(b = 10))
        }

        val relBox = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (release) View.VISIBLE else View.GONE
        }
        val signField = act.label("", 14f, Pal.text).apply {
            background = act.glassPress(12, Pal.field)
            setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(12))
            isClickable = true
        }
        fun paintSign() {
            if (signing.isNotEmpty() && !act.keystores.exists(signing)) signing = ""
            signField.text = "▾  " + (if (signing.isEmpty()) "Bawaan Senza (hanya uji coba)" else signing)
            signField.setTextColor(if (signing.isEmpty()) Pal.warn else Pal.text)
        }
        signField.setOnClickListener {
            val names = act.keystores.names()
            act.pickItem("Tanda tangan Release", listOf("Bawaan Senza (hanya uji coba)") + names + "Kelola keystore…") { i ->
                when {
                    i == 0 -> { signing = ""; paintSign() }
                    i <= names.size -> { signing = names[i - 1]; paintSign() }
                    else -> act.navigate(act.keystoreScreen)
                }
            }
        }
        paintSign()
        relBox.addView(act.fieldLabel("Tanda tangan Release"))
        relBox.addView(signField)
        val bundleRow = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(act.label("Buat juga bundel .aab (eksperimental)", 14f, Pal.text), act.lp(0, WRAP, weight = 1f))
            addView(Switch(act).apply {
                isChecked = bundle
                setOnCheckedChangeListener { _, v -> bundle = v }
            })
        }
        relBox.addView(bundleRow, act.lp(t = 10))

        typeBar = act.segmented(listOf("Debug", "Release"), if (release) 1 else 0) {
            release = it == 1
            shrinkRow.visibility = if (release) View.VISIBLE else View.GONE
            relBox.visibility = if (release) View.VISIBLE else View.GONE
        }
        typeHolder.addView(typeBar)
        typeHolder.addView(shrinkRow, act.lp(t = 8))
        typeHolder.addView(relBox, act.lp(t = 10))
        typeHolder.addView(
            act.label("Debug selalu memakai keystore bawaan. Release memakai keystore pilihanmu (menu Keystore); tanpa pilihan, Release hanya cocok untuk uji coba. \"Perkecil & acak kode\" memakai R8 dengan aturan di proguard-rules.pro. Bundel .aab belum diuji lawan Play Console: validasi dengan bundletool di PC. BuildConfig dibuat otomatis (DEBUG, APPLICATION_ID, VERSION_NAME, dst.) bersama buildConfigField dari build.gradle; applicationIdSuffix dan versionNameSuffix dari build type/flavor ikut dipakai.", 11f, Pal.dim),
            act.lp(t = 8)
        )
    }

    private fun refreshTools() {
        toolsRow.removeAllViews()
        val checks = listOf(
            "ECJ" to true,
            "D8" to true,
            "R8" to true,
            "Kotlin" to KotlinSupport.available(),
            "android.jar" to act.builder.hasAndroidJar(),
            "aapt2" to act.builder.hasAapt2()
        )
        val ready = checks.filter { it.first != "Kotlin" }.all { it.second }
        toolsRow.addView(
            act.icon(if (ready) R.drawable.ic_check else R.drawable.ic_warning, 20, if (ready) Pal.ok else Pal.warn),
            LinearLayout.LayoutParams(act.dp(20), act.dp(20))
        )
        val tv = TextView(act).apply {
            textSize = 12f
            val sb = SpannableStringBuilder()
            checks.forEachIndexed { i, (n, ok) ->
                if (i > 0) sb.append("   ")
                val s = sb.length
                sb.append(if (ok) "✓ $n" else "✗ $n")
                sb.setSpan(ForegroundColorSpan(if (ok) Pal.ok else Pal.err), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text = sb
        }
        toolsRow.addView(tv, act.lp(0, WRAP, l = 10, weight = 1f))
        if (!ready) toolsRow.addView(act.label("Perbaiki", 12f, Pal.warn, true))
    }

    /** Dipanggil tombol Start Build dan tombol Run di editor. */
    fun startFromUi(run: Boolean = false) {
        if (act.project == null) return act.toast("Buka proyek dulu")
        if (act.building) return act.toast("Sedang membangun, tunggu sebentar…")
        val code = versionCode.text.toString().trim().toIntOrNull()
        if (code == null || code < 1) return act.toast("Version Code harus angka 1 atau lebih")
        val vn = versionName.text.toString().trim()
        if (vn.isEmpty() || !Regex("[A-Za-z0-9._-]+").matches(vn)) return act.toast("Version Name: huruf, angka, titik, - atau _")
        val name = appName.text.toString().trim()
        if (name.isEmpty() || name.any { it in "<>&\"" }) return act.toast("Nama aplikasi tidak boleh kosong atau berisi < > & \"")
        logView.text = ""
        resultBox.removeAllViews()
        act.runAfterBuild = run && act.prefs.getBoolean("run_after_build", true)
        if (act.startBuild(BuildConfig(name, code, vn, release, shrink, flavor, signing, bundle))) {
            progress.progress = 0
            showState()
        } else act.runAfterBuild = false
    }

    private fun clearLog() {
        if (act.building) return act.toast("Tunggu build selesai")
        synchronized(act.buildLog) { act.buildLog.setLength(0) }
        logView.text = ""
        act.lastApk = null
        act.lastError = null
        showState()
    }

    // ---------- BuildObserver ----------

    override fun onLog(text: String) {
        appendColored(text)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onStep(n: Int, total: Int, text: String) {
        progress.max = total
        progress.progress = n
        stepText.text = "Langkah $n dari $total — $text"
    }

    override fun onFinish(success: Boolean) { showState() }

    private fun appendColored(line: String) {
        val color = when {
            line.startsWith("BUILD SUCCESSFUL") -> Pal.ok
            line.startsWith("BUILD FAILED") || line.contains("error", ignoreCase = true) -> Pal.err
            line.startsWith("> Task") -> 0xFF7DCFFF.toInt()
            line.startsWith("> Senza") -> Pal.primaryLight
            else -> Pal.text
        }
        val sb = SpannableStringBuilder(line)
        sb.setSpan(ForegroundColorSpan(color), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        logView.append(sb)
    }

    private fun showState() {
        startBtn.alpha = if (act.building) 0.5f else 1f
        cancelBtn.visibility = if (act.building) View.VISIBLE else View.GONE
        progress.visibility = if (act.building) View.VISIBLE else View.GONE
        resultBox.removeAllViews()
        val apk = act.lastApk
        val err = act.lastError
        when {
            act.building -> stepText.text = "Membangun… jangan tutup aplikasi."
            apk != null && apk.exists() -> {
                stepText.text = "Selesai"
                showSuccess(apk)
            }
            err != null -> {
                stepText.text = "Build gagal"
                showFailure(err)
            }
            else -> stepText.text = "Belum ada build."
        }
    }

    private fun showSuccess(apk: File) {
        val box = act.card(12, Pal.okBg)
        val head = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(act.icon(R.drawable.ic_check, 20, Pal.ok), LinearLayout.LayoutParams(act.dp(20), act.dp(20)))
        head.addView(act.label("APK generated successfully!", 14f, Pal.ok, true), act.lp(l = 8))
        box.addView(head)
        box.addView(
            act.mono(apk.path.replace(act.filesDir.path, "~") + "  (${ProjectStore.humanSize(apk.length())})", 11f, Pal.text)
                .apply { setTextIsSelectable(true) },
            act.lp(t = 6)
        )
        val row = LinearLayout(act)
        row.addView(act.primaryButton("Install", R.drawable.ic_download, 13f, intArrayOf(Pal.ok, 0xFF15803D.toInt())) {
            act.installApk(apk)
        }, act.lp(0, WRAP, r = 6, weight = 1f))
        row.addView(act.glassButton("Share", R.drawable.ic_share, 13f) {
            act.shareFile(apk, "application/vnd.android.package-archive")
        }, act.lp(0, WRAP, r = 6, weight = 1f))
        row.addView(act.glassButton("Download", R.drawable.ic_save, 13f) {
            val name = (act.project ?: "app") + "-" + versionName.text.toString().trim() + (if (release) "-release" else "-debug") + ".apk"
            act.saveToDownloads(apk, name, "application/vnd.android.package-archive")
        }, act.lp(0, WRAP, weight = 1f))
        box.addView(row, act.lp(t = 10))
        val row2 = LinearLayout(act)
        row2.addView(act.glassButton("Pasang & buka", R.drawable.ic_play, 13f, Pal.ok) {
            act.installAndLaunch(apk)
        }, act.lp(0, WRAP, r = 6, weight = 1f))
        row2.addView(act.glassButton("Logcat", R.drawable.ic_warning, 13f) {
            act.runPackage = act.packageOfApk(apk) ?: act.runPackage
            act.navigate(act.logcat)
        }, act.lp(0, WRAP, weight = 1f))
        box.addView(row2, act.lp(t = 6))
        val aab = act.lastBundle
        if (aab != null && aab.exists()) {
            box.addView(act.label("Bundel .aab (eksperimental) · ${ProjectStore.humanSize(aab.length())}", 12f, Pal.sub), act.lp(t = 12, b = 4))
            val row3 = LinearLayout(act)
            row3.addView(act.glassButton("Bagikan .aab", R.drawable.ic_share, 13f) {
                act.shareFile(aab, "application/octet-stream")
            }, act.lp(0, WRAP, r = 6, weight = 1f))
            row3.addView(act.glassButton("Simpan .aab", R.drawable.ic_save, 13f) {
                act.saveToDownloads(aab, (act.project ?: "app") + "-" + versionName.text.toString().trim() + "-release.aab", "application/octet-stream")
            }, act.lp(0, WRAP, weight = 1f))
            box.addView(row3)
        }
        resultBox.addView(box)
    }

    private fun showFailure(e: BuildError) {
        val box = act.card(12, Pal.errBg)
        val title = when (e.kind) {
            BuildError.Kind.CODE -> "Ada kesalahan di kodemu"
            BuildError.Kind.RESOURCE -> "Ada kesalahan di file .xml"
            BuildError.Kind.TOOLS -> "Alat build belum lengkap"
            BuildError.Kind.OTHER -> "Build gagal"
        }
        box.addView(act.label(title, 14f, Pal.err, true))
        box.addView(act.label(e.message ?: "", 13f, Pal.text), act.lp(t = 4))
        val row = LinearLayout(act)
        val f = e.file
        val l = e.line
        if (f != null && l != null) {
            row.addView(act.glassButton("Ke baris $l", R.drawable.ic_code, 13f, Pal.err) { goToLine(f, l) }, act.lp(0, WRAP, r = 6, weight = 1f))
        }
        if (e.kind == BuildError.Kind.TOOLS) {
            row.addView(act.glassButton("Cara memperbaiki", R.drawable.ic_info, 13f, Pal.warn) { act.showToolsHelp() }, act.lp(0, WRAP, r = 6, weight = 1f))
        }
        row.addView(act.glassButton("Salin log", R.drawable.ic_file, 13f) {
            act.copyText("log", synchronized(act.buildLog) { act.buildLog.toString() })
        }, act.lp(0, WRAP, weight = 1f))
        box.addView(row, act.lp(t = 10))
        resultBox.addView(box)
    }

    private fun goToLine(path: String, line: Int) {
        val p = act.project ?: return
        val rel = runCatching { File(path).relativeTo(act.store.dir(p)).path }.getOrNull()
        if (rel == null || !act.store.files(p).contains(rel)) return act.toast("File itu tidak bisa dibuka dari sini")
        act.openFile(rel, line)
    }
}
