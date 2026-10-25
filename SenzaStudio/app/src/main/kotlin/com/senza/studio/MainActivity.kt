package com.senza.studio

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

interface BuildObserver {
    fun onLog(text: String)
    fun onStep(n: Int, total: Int, text: String)
    fun onFinish(success: Boolean)
}

class MainActivity : Activity() {

    companion object {
        const val REQ_IMPORT = 1001
        val TASKS = listOf(
            "resolveDependencies", "mergeManifest", "compileResources", "linkResources",
            "compileSources", "dexClasses", "packageApk", "signApk"
        )
        const val REQ_NOTIF = 1002
        const val REQ_ASSET = 1003
        const val REQ_ICON = 1004
        const val REQ_KEYSTORE = 1005
        const val REQ_KEYSTORE_EXPORT = 1006
        const val REQ_TREE = 1007
    }

    lateinit var store: ProjectStore
    val keystores by lazy { KeystoreManager(this) }
    val git by lazy { GitTools(this) }
    /** Bundel .aab hasil build terakhir (jika diminta). */
    var lastBundle: File? = null
    lateinit var builder: SenzaBuilder
    val prefs by lazy { getSharedPreferences("senza_studio", MODE_PRIVATE) }
    val handler = Handler(Looper.getMainLooper())

    // proyek & editor aktif
    var project: String? = null
    val tabs = ArrayList<String>()
    var currentFile: String? = null

    // build
    val buildLog = StringBuilder()
    @Volatile var building = false
    var lastApk: File? = null
    var lastError: BuildError? = null
    val observers = CopyOnWriteArrayList<BuildObserver>()
    private var buildStartMs = 0L
    /** Jika true, setelah build sukses APK langsung dipasang lalu dibuka. */
    @Volatile var runAfterBuild = false
    /** Package APK terakhir yang dibangun (dipakai Logcat). */
    var runPackage: String? = null
    private var pendingLaunchPkg: String? = null
    private var pendingLaunchBefore = -1L
    private var pendingLaunchUntil = 0L
    private var pendingAssetDest = 0
    private var pendingIconBg = 0
    @Volatile private var inForeground = true

    // layar
    lateinit var splash: SplashScreen
    lateinit var explorer: ExplorerScreen
    lateinit var files: FilesScreen
    lateinit var terminal: TerminalScreen
    lateinit var build: BuildScreen
    lateinit var editor: EditorScreen
    lateinit var preview: PreviewScreen
    lateinit var about: AboutScreen
    lateinit var settings: SettingsScreen
    lateinit var search: SearchScreen
    lateinit var logcat: LogcatScreen
    lateinit var manifest: ManifestScreen
    lateinit var keystoreScreen: KeystoreScreen
    lateinit var gitScreen: GitScreen

    private lateinit var content: FrameLayout
    private lateinit var navBar: LinearLayout
    private val stack = ArrayList<Screen>()
    private var current: Screen? = null
    private class NavItem(val screen: Screen, val box: LinearLayout, val ic: ImageView, val tv: TextView)
    private val navItems = ArrayList<NavItem>()

    private lateinit var drawerLayer: FrameLayout
    private lateinit var drawerBox: ScrollView
    private lateinit var drawerScrim: View
    var drawerOpen = false

    val versionName: String
        get() = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0" } catch (e: Exception) { "1.0.0" }

    // =====================================================================
    //  SIKLUS HIDUP
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Pal.bgTop
        window.navigationBarColor = Pal.bgBottom
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        store = ProjectStore(this)
        builder = SenzaBuilder(this, { emitLog(it) }, { n, text -> emitStep(n, text) })
        try { if (builder.hasAndroidJar()) builder.prepareTools() } catch (_: Throwable) {}
        try {
            if (store.list().isEmpty()) store.create("MyApp", "com.senza.myapp")
        } catch (_: Throwable) {}
        val last = prefs.getString("last_project", null)
        project = last?.takeIf { store.dir(it).isDirectory } ?: store.list().firstOrNull()

        splash = SplashScreen(this)
        explorer = ExplorerScreen(this)
        files = FilesScreen(this)
        terminal = TerminalScreen(this)
        build = BuildScreen(this)
        editor = EditorScreen(this)
        preview = PreviewScreen(this)
        about = AboutScreen(this)
        settings = SettingsScreen(this)
        search = SearchScreen(this)
        logcat = LogcatScreen(this)
        manifest = ManifestScreen(this)
        keystoreScreen = KeystoreScreen(this)
        gitScreen = GitScreen(this)
        ApkInstaller.listener = { status, text, _ -> toast(text); if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) checkPendingLaunch() }
        explorer.onProjectChanged()

        val root = FrameLayout(this).apply { background = bgGradient() }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        col.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        navBar = buildNavBar()
        col.addView(navBar, lp())
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        drawerLayer = buildDrawer()
        root.addView(drawerLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        navigate(if (prefs.getBoolean("skip_splash", false)) explorer else splash)

        // izin notifikasi (Android 13+) supaya progres build terlihat saat aplikasi ditinggal
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("asked_notif", false)
        ) {
            prefs.edit().putBoolean("asked_notif", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        checkPendingLaunch()
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
        if (::editor.isInitialized) editor.saveIfAuto()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (drawerOpen) { closeDrawer(); return }
        back()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) data?.data?.let { importFrom(it) }
        if (requestCode == REQ_ASSET && resultCode == RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            data.data?.let { uris.add(it) }
            val clip = data.clipData
            if (clip != null) for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            if (uris.isNotEmpty()) importAssets(uris.distinct())
        }
        if (requestCode == REQ_ICON && resultCode == RESULT_OK) data?.data?.let { generateIcon(it) }
        if (requestCode == REQ_KEYSTORE && resultCode == RESULT_OK) data?.data?.let { keystoreScreen.onPicked(it) }
        if (requestCode == REQ_KEYSTORE_EXPORT && resultCode == RESULT_OK) data?.data?.let { keystoreScreen.onExportTarget(it) }
        if (requestCode == REQ_TREE && resultCode == RESULT_OK) data?.data?.let { onTreePicked(it, data) }
    }

    // =====================================================================
    //  NAVIGASI
    // =====================================================================

    fun navigate(s: Screen) {
        if (s.isTab) stack.clear()
        stack.remove(s)
        stack.add(s)
        showTop()
    }

    fun back() {
        val top = stack.lastOrNull()
        if (top == null) { finish(); return }
        if (top.onBack()) return
        when {
            stack.size > 1 -> { stack.removeAt(stack.size - 1); showTop() }
            top !== explorer && top !== splash -> navigate(explorer)
            else -> finish()
        }
    }

    private fun showTop() {
        val top = stack.last()
        current?.onHide()
        content.removeAllViews()
        content.addView(top.view, FrameLayout.LayoutParams(MATCH, MATCH))
        navBar.visibility = if (top.showsNav) View.VISIBLE else View.GONE
        navItems.forEach {
            val sel = it.screen === top
            it.ic.tint(if (sel) Pal.primaryLight else Pal.sub)
            it.tv.setTextColor(if (sel) Pal.primaryLight else Pal.sub)
            it.box.background = if (sel) glass(16, 0x332F6BFF, 0x552F6BFF) else null
        }
        current = top
        top.onShow()
    }

    private fun buildNavBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Pal.navBg)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        listOf(
            Triple(explorer, R.drawable.ic_folder, "Project"),
            Triple(files, R.drawable.ic_files, "Files"),
            Triple(terminal, R.drawable.ic_terminal, "Terminal"),
            Triple(build, R.drawable.ic_build, "Build")
        ).forEach { (screen, res, name) ->
            val ic = icon(res, 22, Pal.sub)
            val tv = label(name, 11f, Pal.sub)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                isClickable = true
                setOnClickListener { navigate(screen) }
            }
            box.addView(ic, LinearLayout.LayoutParams(dp(22), dp(22)))
            box.addView(tv, lp(WRAP, WRAP, t = 2))
            bar.addView(box, lp(0, WRAP, l = 4, r = 4, weight = 1f))
            navItems.add(NavItem(screen, box, ic, tv))
        }
        return bar
    }

    // =====================================================================
    //  DRAWER
    // =====================================================================

    private fun buildDrawer(): FrameLayout {
        val layer = FrameLayout(this).apply { visibility = View.GONE }
        drawerScrim = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { closeDrawer() }
        }
        layer.addView(drawerScrim, FrameLayout.LayoutParams(MATCH, MATCH))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(18))
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_s)
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val ht = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ht.addView(label("Senza Studio", 18f, Pal.text, true))
        ht.addView(label("v$versionName", 12f, Pal.sub))
        head.addView(ht, lp(WRAP, WRAP, l = 12))
        panel.addView(head, lp(b = 18))

        panel.addView(drawerItem(R.drawable.ic_plus, "New Project") { closeDrawer(); newProjectDialog() })
        panel.addView(drawerItem(R.drawable.ic_folder_open, "Open Project") { closeDrawer(); projectChooser() })
        panel.addView(drawerItem(R.drawable.ic_import, "Import Project") { closeDrawer(); pickZip() })
        panel.addView(drawerItem(R.drawable.ic_settings, "Settings") { closeDrawer(); navigate(settings) })
        panel.addView(label("Tools", 13f, Pal.sub, true), lp(t = 18, b = 4))
        panel.addView(drawerItem(R.drawable.ic_build, "Build APK") { closeDrawer(); navigate(build) })
        panel.addView(drawerItem(R.drawable.ic_preview, "UI Preview") { closeDrawer(); navigate(preview) })
        panel.addView(drawerItem(R.drawable.ic_search, "Cari di proyek") { closeDrawer(); navigate(search) })
        panel.addView(drawerItem(R.drawable.ic_code, "Manifest") { closeDrawer(); navigate(manifest) })
        panel.addView(drawerItem(R.drawable.ic_warning, "Logcat & Crash") { closeDrawer(); navigate(logcat) })
        panel.addView(drawerItem(R.drawable.ic_terminal, "Terminal") { closeDrawer(); navigate(terminal) })
        panel.addView(drawerItem(R.drawable.ic_files, "File Manager") { closeDrawer(); navigate(files) })
        panel.addView(label("Rilis & kolaborasi", 13f, Pal.sub, true), lp(t = 18, b = 4))
        panel.addView(drawerItem(R.drawable.ic_key, "Keystore") { closeDrawer(); navigate(keystoreScreen) })
        panel.addView(drawerItem(R.drawable.ic_git, "Git") { closeDrawer(); navigate(gitScreen) })
        panel.addView(drawerItem(R.drawable.ic_sync, "Folder publik (cadangan)") { closeDrawer(); folderSyncDialog() })
        panel.addView(label("About", 13f, Pal.sub, true), lp(t = 18, b = 4))
        val aboutRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = glassPress(16)
            isClickable = true
            setOnClickListener { closeDrawer(); navigate(about) }
        }
        aboutRow.addView(ImageView(this).apply { setImageResource(R.drawable.logo_tile) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        val at = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        at.addView(label("Senza Studio", 15f, Pal.text, true))
        at.addView(label("Build Your Android App", 12f, Pal.sub))
        aboutRow.addView(at, lp(WRAP, WRAP, l = 12))
        panel.addView(aboutRow, lp())
        panel.addView(label("Powered by SenzaProject", 11f, Pal.dim).apply { gravity = Gravity.CENTER }, lp(t = 22))

        drawerBox = ScrollView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF050D30.toInt(), 0xFF07124A.toInt()))
                .apply { setStroke(dp(1), Pal.glassStroke) }
            addView(panel)
        }
        layer.addView(drawerBox, FrameLayout.LayoutParams(dp(300), MATCH))
        return layer
    }

    private fun drawerItem(res: Int, text: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(13), dp(10), dp(13))
            background = ripple(shape(Color.TRANSPARENT, 12), 12)
            isClickable = true
            addView(icon(res, 22, Pal.primaryLight), LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(label(text, 15f, Pal.text), lp(WRAP, WRAP, l = 16))
            setOnClickListener { onClick() }
        }

    fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        drawerLayer.visibility = View.VISIBLE
        drawerBox.translationX = -dp(300).toFloat()
        drawerScrim.alpha = 0f
        drawerBox.animate().translationX(0f).setDuration(220).start()
        drawerScrim.animate().alpha(1f).setDuration(220).start()
    }

    fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        drawerScrim.animate().alpha(0f).setDuration(180).start()
        drawerBox.animate().translationX(-dp(300).toFloat()).setDuration(180)
            .withEndAction { drawerLayer.visibility = View.GONE }.start()
    }

    // =====================================================================
    //  PROYEK
    // =====================================================================

    fun openProject(name: String) {
        if (::editor.isInitialized) editor.saveNow()
        project = name
        tabs.clear()
        currentFile = null
        lastApk = null
        lastError = null
        synchronized(buildLog) { buildLog.setLength(0) }
        prefs.edit().putString("last_project", name).apply()
        explorer.onProjectChanged()
        terminal.resetCwd()
    }

    fun newProjectDialog() {
        val nameEt = textField("Nama aplikasi, mis. Kalkulatorku")
        val pkgEt = textField("", ProjectStore.suggestPackage(""))
        var auto = true
        var setting = false
        pkgEt.onChanged { if (!setting) auto = false }
        nameEt.onChanged {
            if (auto) {
                setting = true
                pkgEt.setText(ProjectStore.suggestPackage(it.toString()))
                setting = false
            }
        }
        var tpl = 0
        val tplDesc = label(ProjectTemplates.DESCRIPTIONS[0], 11f, Pal.dim)
        val tplField = label("▾  " + ProjectTemplates.NAMES[0], 14f, Pal.text).apply {
            background = glassPress(12, Pal.field)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener {
                pickItem("Template proyek", ProjectTemplates.NAMES) { i ->
                    tpl = i
                    text = "▾  " + ProjectTemplates.NAMES[i]
                    tplDesc.text = ProjectTemplates.DESCRIPTIONS[i]
                }
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), 0)
            addView(label("Beri nama aplikasimu. Package dibuat otomatis.", 13f, Pal.sub), lp(b = 10))
            addView(fieldLabel("Nama aplikasi"))
            addView(nameEt, lp(b = 10))
            addView(fieldLabel("Package (ID unik aplikasi)"))
            addView(pkgEt, lp(b = 10))
            addView(fieldLabel("Template"))
            addView(tplField)
            addView(tplDesc, lp(t = 4))
        }
        val dlg = AlertDialog.Builder(this).setTitle("New Project").setView(box)
            .setPositiveButton("Buat", null).setNegativeButton("Batal", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = nameEt.text.toString().trim()
                try {
                    ProjectTemplates.create(store, n, pkgEt.text.toString().trim(), tpl)
                    dlg.dismiss()
                    openProject(n)
                    navigate(explorer)
                } catch (e: Exception) {
                    toast(e.message ?: "Gagal membuat proyek")
                }
            }
        }
        dlg.show()
    }

    fun projectChooser() {
        val names = store.list()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        var dlg: AlertDialog? = null
        if (names.isEmpty()) box.addView(label("Belum ada proyek.", 14f, Pal.sub), lp(b = 8))
        names.forEach { n ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(4), dp(10))
                background = glassPress(14, if (n == project) 0x552F6BFF else Pal.glass)
                isClickable = true
                setOnClickListener { dlg?.dismiss(); openProject(n); navigate(explorer) }
            }
            row.addView(icon(R.drawable.ic_folder, 22, 0xFFFFC857.toInt()), LinearLayout.LayoutParams(dp(22), dp(22)))
            val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            mid.addView(label(n, 15f, Pal.text, true))
            mid.addView(label(store.packageOf(n), 12f, Pal.sub))
            row.addView(mid, lp(0, WRAP, l = 12, weight = 1f))
            row.addView(iconButton(R.drawable.ic_trash, 20, Pal.err) {
                confirm("Hapus proyek '$n' beserta isinya?") {
                    dlg?.dismiss()
                    deleteProject(n)
                }
            })
            box.addView(row, lp(b = 8))
        }
        box.addView(glassButton("Buat proyek baru", R.drawable.ic_plus) { dlg?.dismiss(); newProjectDialog() }, lp(t = 4, b = 6))
        box.addView(glassButton("Impor dari .zip", R.drawable.ic_import) { dlg?.dismiss(); pickZip() }, lp(b = 6))
        val d = AlertDialog.Builder(this).setTitle("Open Project").setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Tutup", null).create()
        dlg = d
        d.show()
    }

    fun deleteProject(name: String) {
        store.dir(name).deleteRecursively()
        if (project == name) {
            val next = store.list().firstOrNull()
            if (next != null) openProject(next) else {
                project = null; tabs.clear(); currentFile = null
                explorer.onProjectChanged()
            }
        }
        explorer.refresh()
        toast("Proyek '$name' dihapus")
    }

    fun openFile(rel: String, line: Int? = null) {
        val p = project ?: return toast("Buka proyek dulu")
        val f = File(store.dir(p), rel)
        if (!f.isFile) return toast("File tidak ditemukan")
        if (!ProjectStore.isTextFile(f)) return toast("Ini bukan file teks, tidak bisa dibuka di editor")
        if (f.length() > 1_000_000) return toast("File terlalu besar untuk editor (maks 1 MB)")
        if (rel !in tabs) tabs.add(rel)
        currentFile = rel
        editor.pendingLine = line
        navigate(editor)
    }

    fun openCodeEditor() {
        val p = project ?: return navigate(explorer)
        if (currentFile != null) return navigate(editor)
        val first = store.files(p).firstOrNull { it.endsWith(".java") } ?: store.files(p).firstOrNull()
        if (first != null) openFile(first) else navigate(explorer)
    }

    fun closeTab(rel: String) {
        tabs.remove(rel)
        if (currentFile == rel) {
            editor.saveNow()
            currentFile = tabs.lastOrNull()
            if (currentFile == null) { editor.clearLoaded(); back() } else editor.onShow()
        } else editor.refreshTabs()
    }

    fun onPathDeleted(rel: String) {
        val gone = tabs.filter { it == rel || it.startsWith("$rel/") }
        tabs.removeAll(gone.toSet())
        if (currentFile != null && currentFile in gone) {
            currentFile = tabs.lastOrNull()
            editor.clearLoaded()
        }
    }

    fun onPathRenamed(old: String, new: String) {
        for (i in tabs.indices) {
            val t = tabs[i]
            if (t == old) tabs[i] = new else if (t.startsWith("$old/")) tabs[i] = new + t.removePrefix(old)
        }
        currentFile?.let { c ->
            if (c == old) currentFile = new else if (c.startsWith("$old/")) currentFile = new + c.removePrefix(old)
        }
        editor.clearLoaded()
    }

    // =====================================================================
    //  BUILD
    // =====================================================================

    private fun emitLog(line: String) {
        synchronized(buildLog) { buildLog.append(line).append('\n') }
        runOnUiThread { observers.forEach { it.onLog(line + "\n") } }
    }

    private fun emitStep(n: Int, text: String) {
        val task = "> Task :" + TASKS.getOrElse(n - 1) { "step$n" } + "  ($text)"
        synchronized(buildLog) { buildLog.append(task).append('\n') }
        BuildService.update(this, project ?: "", n, SenzaBuilder.TOTAL_STEPS, text)
        runOnUiThread {
            observers.forEach { it.onStep(n, SenzaBuilder.TOTAL_STEPS, text); it.onLog(task + "\n") }
        }
    }

    /** Mulai build. Mengembalikan false jika tidak bisa dimulai. */
    fun startBuild(cfg: BuildConfig): Boolean {
        val p = project ?: run { toast("Buka proyek dulu"); return false }
        if (building) { toast("Sedang membangun, tunggu sebentar…"); return false }
        editor.saveNow()
        if (cfg.appName.isNotBlank()) store.setAppLabel(p, cfg.appName.trim())
        store.saveConfig(p, cfg)
        synchronized(buildLog) { buildLog.setLength(0) }
        building = true
        lastApk = null
        lastBundle = null
        lastError = null
        buildStartMs = System.currentTimeMillis()
        emitLog("> Senza Builder: maven > manifest > aapt2 > kotlinc > ECJ > ${if (cfg.release && cfg.shrink) "R8" else "D8"} > apksig  [${if (cfg.release) "release" else "debug"}${if (cfg.release && cfg.shrink) ", R8" else ""}]")
        BuildService.start(this, p)
        Thread(null, {
            var ok = false
            try {
                val apk = builder.build(store.dir(p), cfg)
                lastApk = apk
                lastBundle = builder.bundleOf(store.dir(p)).takeIf { cfg.bundle && cfg.release && it.isFile }
                val sec = (System.currentTimeMillis() - buildStartMs) / 1000
                emitLog("")
                emitLog("BUILD SUCCESSFUL in ${sec}s")
                emitLog("${SenzaBuilder.TOTAL_STEPS} actionable tasks: ${SenzaBuilder.TOTAL_STEPS} executed")
                ok = true
            } catch (e: BuildError) {
                lastError = e
                emitLog("")
                emitLog("BUILD FAILED: ${e.message}")
            } catch (e: Throwable) {
                emitLog(e.stackTraceToString().take(2000))
                lastError = BuildError(BuildError.Kind.OTHER, "Terjadi kesalahan tak terduga: ${e.message ?: e.javaClass.simpleName}")
                emitLog("")
                emitLog("BUILD FAILED")
            } finally {
                building = false
                val success = ok
                BuildService.stop(this)
                if (!inForeground) {
                    val detail = if (success) "APK siap dipasang. Buka Senza Studio > Build." else
                        (lastError?.message ?: "Buka Senza Studio untuk melihat log.").take(200)
                    BuildService.notifyDone(this, p, success, detail)
                }
                if (!success) runAfterBuild = false
                runOnUiThread {
                    observers.forEach { it.onFinish(success) }
                    if (success) afterSuccessfulBuild()
                }
            }
        }, "senza-build", 64L * 1024 * 1024).start()
        return true
    }

    /** Tambah dependency Maven ke build.gradle proyek aktif. */
    fun addDependencyDialog() {
        val p = project ?: return toast("Buka proyek dulu")
        promptText("Tambah dependency", "group:artifact:versi", "androidx.appcompat:appcompat:1.6.1") { c ->
            if (c.isEmpty()) return@promptText
            try {
                val f = GradleLite.addDependency(store.dir(p), c)
                toast("Ditambahkan ke $f. Diunduh saat Build berikutnya.")
                explorer.refresh()
            } catch (e: Exception) {
                toast(e.message ?: "Gagal menambah dependency")
            }
        }
    }

    fun cleanBuildCache() {
        val p = project ?: return toast("Buka proyek dulu")
        if (building) return toast("Tunggu build selesai")
        val size = ProjectStore.humanSize(builder.cacheSize())
        pickItem("Bersihkan cache", listOf("Cache proyek ini saja", "Semua cache ($size dex + unduhan Maven)")) { i ->
            val msg = builder.clean(store.dir(p), i == 1)
            lastApk = null
            explorer.refresh()
            toast(msg)
        }
    }

    fun installApk(apk: File): Boolean {
        if (!packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            toast("Izinkan 'pasang dari sumber ini', lalu ketuk Install lagi.")
            return false
        }
        // cara utama: sesi PackageInstaller (hasil dan alasan gagal dilaporkan balik)
        try {
            ApkInstaller.install(this, apk, packageOfApk(apk))
            return true
        } catch (e: Throwable) {
            toast("Installer sesi gagal (${e.message}), memakai cara lama.")
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    /** Hapus rujukan keystore yang sudah dihapus dari pengaturan semua proyek. */
    fun clearSigningRefs(name: String) {
        store.list().forEach { p ->
            val c = store.config(p)
            if (c.signing == name) store.saveConfig(p, c.copy(signing = ""))
        }
    }

    // ---------- folder publik (SAF) ----------

    private fun treeKey(p: String) = "saf_tree_$p"

    fun folderSyncDialog() {
        val p = project ?: return toast("Buka proyek dulu")
        val tree = prefs.getString(treeKey(p), null)?.let { Uri.parse(it) }
        val items = ArrayList<String>()
        if (tree == null) items.add("Pilih folder cadangan…") else {
            items.add("Simpan ke folder: ${FolderSync.label(this, tree)}")
            items.add("Ambil dari folder (menimpa file berbeda)")
            items.add("Ganti folder…")
            items.add("Lepas folder")
        }
        pickItem("Folder publik untuk '$p'", items) { i ->
            when {
                tree == null || items[i] == "Ganti folder…" || items[i] == "Pilih folder cadangan…" -> {
                    val it = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    )
                    try { startActivityForResult(it, REQ_TREE) } catch (e: Exception) { toast("Tidak ada aplikasi pemilih folder") }
                }
                i == 0 -> runFolderSync(tree, true)
                i == 1 -> confirm("File di proyek yang namanya sama dan isinya berbeda akan ditimpa. Lanjut?") { runFolderSync(tree, false) }
                else -> { prefs.edit().remove(treeKey(p)).apply(); toast("Folder dilepas (isinya tidak dihapus).") }
            }
        }
    }

    private fun onTreePicked(uri: Uri, data: Intent?) {
        val p = project ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) { toast("Izin folder tidak bisa disimpan permanen: ${e.message}") }
        prefs.edit().putString(treeKey(p), uri.toString()).apply()
        toast("Folder dipilih. Buka menu ini lagi untuk menyimpan.")
    }

    @Volatile private var syncing = false

    private fun runFolderSync(tree: Uri, push: Boolean) {
        val p = project ?: return
        if (syncing) return toast("Sinkron sedang berjalan")
        editor.saveNow()
        syncing = true
        toast(if (push) "Menyimpan ke folder…" else "Mengambil dari folder…")
        Thread {
            try {
                val r = if (push) FolderSync.push(this, tree, store.dir(p), { }, { false })
                else FolderSync.pull(this, tree, store.dir(p), { }, { false })
                runOnUiThread {
                    if (!push) { editor.clearLoaded(); CodeAssist.invalidate(); explorer.refresh() }
                    toast("Selesai: ${r.copied} file disalin, ${r.skipped} sama" +
                        (if (r.failed > 0) ", ${r.failed} gagal (${r.firstError})" else "") +
                        ". File yang tidak ada di sisi lain tidak dihapus.")
                }
            } catch (e: Throwable) {
                runOnUiThread { toast("Sinkron gagal: ${e.message}") }
            } finally { syncing = false }
        }.start()
    }

    // ---------- Run: pasang lalu buka ----------

    @Suppress("DEPRECATION")
    fun packageOfApk(apk: File): String? =
        try { packageManager.getPackageArchiveInfo(apk.path, 0)?.packageName } catch (_: Throwable) { null }

    private fun installedTime(pkg: String): Long =
        try { packageManager.getPackageInfo(pkg, 0).lastUpdateTime } catch (_: Exception) { -1L }

    private var pendingRunApk: File? = null

    private fun afterSuccessfulBuild() {
        val apk = lastApk ?: return
        runPackage = packageOfApk(apk) ?: runPackage
        if (!runAfterBuild) return
        runAfterBuild = false
        if (!inForeground) { pendingRunApk = apk; return }
        installAndLaunch(apk)
    }

    /** Pasang APK; setelah terpasang, aplikasinya dibuka otomatis begitu kamu kembali ke Senza Studio. */
    fun installAndLaunch(apk: File) {
        val pkg = packageOfApk(apk)
        if (pkg == null) { installApk(apk); return }
        runPackage = pkg
        val before = installedTime(pkg)
        if (installApk(apk)) {
            pendingLaunchPkg = pkg
            pendingLaunchBefore = before
            pendingLaunchUntil = System.currentTimeMillis() + 5 * 60_000L
            handler.postDelayed({ checkPendingLaunch() }, 2000)
        }
    }

    fun checkPendingLaunch() {
        pendingRunApk?.let { pendingRunApk = null; installAndLaunch(it); return }
        val pkg = pendingLaunchPkg ?: return
        if (System.currentTimeMillis() > pendingLaunchUntil) { pendingLaunchPkg = null; return }
        val now = installedTime(pkg)
        if (now > 0 && now != pendingLaunchBefore) {
            pendingLaunchPkg = null
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) {
                try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { toast("Gagal membuka aplikasi: ${e.message}") }
            } else toast("Terpasang, tetapi tidak ada Activity peluncur. Cek Manifest > Peluncur.")
        } else if (inForeground) {
            handler.postDelayed({ checkPendingLaunch() }, 1500)
        }
    }

    // ---------- aset & ikon ----------

    fun importAssetsDialog() {
        if (project == null) return toast("Buka proyek dulu")
        pickItem("Impor aset ke…", AssetTools.DEST_LABELS) { i ->
            pendingAssetDest = i
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            try { startActivityForResult(intent, REQ_ASSET) } catch (e: Exception) { toast("Tidak ada aplikasi pemilih file") }
        }
    }

    private fun importAssets(uris: List<Uri>) {
        val p = project ?: return
        toast("Mengimpor ${uris.size} file…")
        Thread {
            try {
                val done = AssetTools.importFiles(this, store.dir(p), uris, pendingAssetDest)
                runOnUiThread {
                    CodeAssist.invalidate()
                    explorer.refresh()
                    toast(done.take(6).joinToString("\n") + if (done.size > 6) "\n…" else "")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Gagal impor: ${e.message}") }
            }
        }.start()
    }

    fun iconDialog() {
        if (project == null) return toast("Buka proyek dulu")
        pickItem("Warna latar ikon", AssetTools.BG_LABELS) { i ->
            pendingIconBg = i
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            try { startActivityForResult(intent, REQ_ICON) } catch (e: Exception) { toast("Tidak ada aplikasi pemilih file") }
        }
    }

    private fun generateIcon(uri: Uri) {
        val p = project ?: return
        toast("Membuat ikon…")
        Thread {
            try {
                val bmp = AssetTools.decode(this, uri)
                val msg = AssetTools.generateIcons(store.dir(p), bmp, pendingIconBg)
                bmp.recycle()
                runOnUiThread {
                    CodeAssist.invalidate()
                    explorer.refresh()
                    editor.clearLoaded()
                    toast(msg)
                }
            } catch (e: Throwable) {
                runOnUiThread { toast("Gagal membuat ikon: ${e.message}") }
            }
        }.start()
    }

    fun shareFile(f: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Bagikan"))
    }

    fun saveToDownloads(f: File, name: String, mime: String) {
        if (Build.VERSION.SDK_INT < 29) {
            toast("Simpan ke Download butuh Android 10+. Gunakan Share.")
            return
        }
        try {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/SenzaStudio")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw IllegalStateException("tidak bisa membuat file")
            contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            toast("Tersimpan di Download/SenzaStudio/$name")
        } catch (e: Exception) {
            toast("Gagal menyimpan: ${e.message}")
        }
    }

    fun showToolsHelp() {
        val msg = buildString {
            if (!builder.hasAapt2()) {
                append("Alat 'aapt2' belum ada di dalam Senza Studio. Alat ini wajib untuk memproses resource aplikasi.\n\n")
                append("Cara memperbaiki:\n")
                append("1. Siapkan file aapt2 versi Android ARM64 (berdiri sendiri).\n")
                append("2. Ganti namanya menjadi libaapt2.so dan taruh di:\n   app/src/main/jniLibs/arm64-v8a/\n   pada proyek Senza Studio di GitHub.\n")
                append("3. Push ke GitHub, tunggu Actions selesai, lalu pasang APK Senza Studio yang terbaru.\n\n")
            }
            if (!builder.hasAndroidJar()) {
                append("Pustaka Android (android.jar) tidak ikut terpasang. Pasang Senza Studio dari hasil build GitHub Actions (workflow menambahkannya otomatis).\n")
            }
            if (isEmpty()) append("Semua alat build sudah siap ✓")
        }.trim()
        AlertDialog.Builder(this).setTitle("Status alat build").setMessage(msg)
            .setPositiveButton("Mengerti", null).show()
    }

    // =====================================================================
    //  IMPOR / EKSPOR
    // =====================================================================

    fun pickZip() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        try { startActivityForResult(i, REQ_IMPORT) } catch (e: Exception) { toast("Tidak ada aplikasi pemilih file") }
    }

    private fun importFrom(uri: Uri) {
        var dn = "Imported.zip"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) dn = c.getString(0) ?: dn
            }
        } catch (_: Exception) {}
        toast("Mengimpor proyek…")
        Thread {
            try {
                val name = contentResolver.openInputStream(uri)!!.use { store.importZip(it, dn) }
                runOnUiThread { openProject(name); navigate(explorer); toast("Proyek '$name' berhasil diimpor") }
            } catch (e: Exception) {
                runOnUiThread { toast("Gagal impor: ${e.message}") }
            }
        }.start()
    }

    /** Ekspor proyek aktif menjadi zip lalu bagikan / simpan. */
    fun exportProject() {
        val p = project ?: return toast("Buka proyek dulu")
        editor.saveNow()
        pickItem("Ekspor '$p'", listOf("Bagikan (.zip)", "Simpan ke Download")) { i ->
            Thread {
                try {
                    val dir = File(cacheDir, "export").apply { mkdirs() }
                    val zip = File(dir, "$p.zip")
                    zip.outputStream().use { store.exportZip(p, it) }
                    runOnUiThread { if (i == 0) shareFile(zip, "application/zip") else saveToDownloads(zip, "$p.zip", "application/zip") }
                } catch (e: Exception) {
                    runOnUiThread { toast("Gagal ekspor: ${e.message}") }
                }
            }.start()
        }
    }

    // =====================================================================
    //  UTIL
    // =====================================================================

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("Ya") { _, _ -> ok() }
            .setNegativeButton("Batal", null).show()
    }

    fun pickItem(title: String?, items: List<String>, onPick: (Int) -> Unit) {
        val b = AlertDialog.Builder(this)
        if (title != null) b.setTitle(title)
        b.setItems(items.toTypedArray()) { _, i -> onPick(i) }.show()
    }

    fun promptText(title: String, hint: String, initial: String = "", onOk: (String) -> Unit) {
        val et = textField(hint, initial)
        val box = LinearLayout(this).apply {
            setPadding(dp(22), dp(10), dp(22), 0)
            addView(et, lp())
        }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(et.text.toString().trim()) }
            .setNegativeButton("Batal", null).show()
    }

    fun copyText(label: String, text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        toast("Disalin")
    }

    fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { toast("Tidak bisa membuka tautan") }
    }
}
