package com.senza.studio

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Project Explorer: pohon file proyek yang nyata, dengan tambah / ganti nama / hapus. */
class ExplorerScreen(act: MainActivity) : Screen(act) {

    override val isTab = true

    private val expanded = HashSet<String>()
    private var query = ""
    private lateinit var list: LinearLayout
    private lateinit var projName: TextView
    private lateinit var projPath: TextView
    private lateinit var searchBox: EditText

    override val view: View = buildView()

    private fun buildView(): View {
        val root = FrameLayout(act)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            act.topBar(
                "Senza Studio", R.drawable.ic_menu, { act.openDrawer() },
                R.drawable.ic_search to { toggleSearch() },
                R.drawable.ic_more to { projectMenu() }
            ), act.lp()
        )

        searchBox = act.textField("Cari file…").apply { visibility = View.GONE }
        searchBox.onChanged { query = it?.toString()?.trim() ?: ""; refresh() }
        col.addView(searchBox, act.lp(l = 14, r = 14, b = 8))

        val card = act.card(0)
        val head = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(14), act.dp(12), act.dp(8), act.dp(12))
            isClickable = true
            setOnClickListener { act.projectChooser() }
        }
        head.addView(act.icon(R.drawable.ic_folder_open, 24, 0xFFFFC857.toInt()), LinearLayout.LayoutParams(act.dp(24), act.dp(24)))
        val titles = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        projName = act.label("", 16f, Pal.text, true).apply { setSingleLine() }
        projPath = act.label("", 11f, Pal.sub).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
        titles.addView(projName)
        titles.addView(projPath)
        head.addView(titles, act.lp(0, WRAP, l = 12, weight = 1f))
        head.addView(act.icon(R.drawable.ic_chevron_down, 20, Pal.sub), act.lp(act.dp(20), act.dp(20), r = 6))
        card.addView(head, act.lp())
        card.addView(act.divider(), LinearLayout.LayoutParams(MATCH, 1))

        list = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(6), act.dp(6), act.dp(6), act.dp(80))
        }
        card.addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        col.addView(card, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
            setMargins(act.dp(12), 0, act.dp(12), act.dp(8))
        })
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        val fab = FrameLayout(act).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Pal.primaryLight, Pal.primary)).apply {
                shape = GradientDrawable.OVAL
                setStroke(act.dp(1), 0x884DA3FF.toInt())
            }
            elevation = act.dpf(8f)
            isClickable = true
            addView(act.icon(R.drawable.ic_plus, 26, Color.WHITE).apply {
                layoutParams = FrameLayout.LayoutParams(act.dp(26), act.dp(26), Gravity.CENTER)
            })
            setOnClickListener { addDialog(null) }
        }
        root.addView(fab, FrameLayout.LayoutParams(act.dp(58), act.dp(58), Gravity.END or Gravity.BOTTOM).apply {
            setMargins(0, 0, act.dp(22), act.dp(22))
        })
        return root
    }

    override fun onShow() { refresh() }

    fun onProjectChanged() {
        expanded.clear()
        query = ""
        if (::searchBox.isInitialized) { searchBox.setText(""); searchBox.visibility = View.GONE }
        val p = act.project
        if (p != null) {
            val d = act.store.dir(p)
            listOf("src", "res").forEach { top ->
                val t = File(d, top)
                if (t.isDirectory) {
                    expanded.add(top)
                    t.walkTopDown().maxDepth(8).filter { it.isDirectory }.forEach { expanded.add(it.relativeTo(d).path) }
                }
            }
        }
        refresh()
    }

    private fun toggleSearch() {
        if (searchBox.visibility == View.VISIBLE) {
            searchBox.setText("")
            searchBox.visibility = View.GONE
        } else {
            searchBox.visibility = View.VISIBLE
            searchBox.requestFocus()
        }
    }

    fun refresh() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val p = act.project
        if (p == null || !act.store.dir(p).isDirectory) {
            projName.text = "Belum ada proyek"
            projPath.text = "Ketuk untuk membuat atau membuka proyek"
            list.addView(
                act.glassButton("Buat proyek baru", R.drawable.ic_plus) { act.newProjectDialog() },
                act.lp(t = 12, l = 8, r = 8)
            )
            return
        }
        val base = act.store.dir(p)
        projName.text = p
        projPath.text = "~/" + base.relativeTo(act.filesDir).path
        if (query.isNotEmpty()) {
            val hits = base.walkTopDown().onEnter { it == base || it.name != "out" }
                .filter { it.isFile && it.name.contains(query, ignoreCase = true) }
                .take(100).toList()
            if (hits.isEmpty()) list.addView(act.label("Tidak ada file yang cocok.", 13f, Pal.sub), act.lp(t = 12, l = 10))
            hits.forEach { list.addView(row(it, it.relativeTo(base).path, 0, false, false)) }
            return
        }
        addDir(base, base, 0)
    }

    private fun addDir(base: File, dir: File, depth: Int) {
        val kids = dir.listFiles()?.filter { !(it.name == ".git" && it.isDirectory) }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
        for (f in kids) {
            val rel = f.relativeTo(base).path
            val open = f.isDirectory && rel in expanded
            list.addView(row(f, rel, depth, f.isDirectory, open))
            if (open) addDir(base, f, depth + 1)
        }
    }

    private fun fileTint(f: File): Int = when {
        f.name == "AndroidManifest.xml" -> 0xFF9ECE6A.toInt()
        f.extension == "java" -> 0xFFFF9E64.toInt()
        f.extension == "kt" -> 0xFFBB9AF7.toInt()
        f.extension == "xml" -> 0xFF7DCFFF.toInt()
        f.extension == "apk" -> Pal.ok
        else -> Pal.sub
    }

    private fun row(f: File, rel: String, depth: Int, isDir: Boolean, open: Boolean): View {
        val sel = !isDir && rel == act.currentFile
        val r = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(6 + depth * 16), act.dp(9), act.dp(8), act.dp(9))
            background = if (sel) act.glass(8, 0x552F6BFF, 0, 0) else act.ripple(act.shape(Color.TRANSPARENT, 8), 8)
            isClickable = true
            setOnClickListener {
                when {
                    isDir -> { if (open) expanded.remove(rel) else expanded.add(rel); refresh() }
                    f.extension == "apk" -> itemMenu(f, rel, false)
                    else -> act.openFile(rel)
                }
            }
            setOnLongClickListener { itemMenu(f, rel, isDir); true }
        }
        if (isDir) {
            r.addView(act.icon(if (open) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, 16, Pal.sub),
                LinearLayout.LayoutParams(act.dp(16), act.dp(16)))
        } else {
            r.addView(View(act), LinearLayout.LayoutParams(act.dp(16), 1))
        }
        val iconRes = when {
            isDir -> if (open) R.drawable.ic_folder_open else R.drawable.ic_folder
            else -> R.drawable.ic_file
        }
        val tint = if (isDir) 0xFFFFC857.toInt() else fileTint(f)
        r.addView(act.icon(iconRes, 20, tint), act.lp(act.dp(20), act.dp(20), l = 6, r = 10))
        val name = if (query.isNotEmpty()) rel else f.name
        r.addView(
            act.label(name, 14f, if (sel) Color.WHITE else Pal.text, isDir || sel)
                .apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE },
            act.lp(0, WRAP, weight = 1f)
        )
        if (!isDir && f.extension == "apk") {
            r.addView(act.label(ProjectStore.humanSize(f.length()), 11f, Pal.sub))
        }
        return r
    }

    // ---------- menu ----------

    private fun projectMenu() {
        act.pickItem(
            act.project,
            listOf(
                "Tambah file / folder", "Refresh", "Ganti proyek", "Cari & ganti di proyek", "Editor manifest",
                "Impor aset (gambar/font/audio)", "Pembuat ikon aplikasi", "Logcat & crash",
                "Tambah dependency (Maven)", "Bersihkan cache build", "Ekspor proyek (.zip)", "Hapus proyek ini", "Git", "Keystore", "Folder publik (cadangan)"
            )
        ) { i ->
            when (i) {
                0 -> addDialog(null)
                1 -> refresh()
                2 -> act.projectChooser()
                3 -> act.navigate(act.search)
                4 -> act.navigate(act.manifest)
                5 -> act.importAssetsDialog()
                6 -> act.iconDialog()
                7 -> act.navigate(act.logcat)
                8 -> act.addDependencyDialog()
                9 -> act.cleanBuildCache()
                10 -> act.exportProject()
                11 -> act.project?.let { p -> act.confirm("Hapus proyek '$p' beserta isinya?") { act.deleteProject(p) } }
                12 -> act.navigate(act.gitScreen)
                13 -> act.navigate(act.keystoreScreen)
                14 -> act.folderSyncDialog()
            }
        }
    }

    private fun itemMenu(f: File, rel: String, isDir: Boolean) {
        val items = ArrayList<String>()
        if (isDir) { items.add("File baru di sini"); items.add("Folder baru di sini") }
        else if (f.extension == "apk") { items.add("Install"); items.add("Share") }
        else items.add("Buka")
        items.add("Ganti nama")
        items.add("Hapus")
        act.pickItem(f.name, items) { i ->
            when (items[i]) {
                "File baru di sini" -> act.promptText("File baru", "Nama file, mis. Util.java") { n ->
                    guard { createFileIn(f, n) }
                }
                "Folder baru di sini" -> act.promptText("Folder baru", "Nama folder") { n ->
                    guard { createFolderIn(f, n) }
                }
                "Install" -> act.installApk(f)
                "Share" -> act.shareFile(f, "application/vnd.android.package-archive")
                "Buka" -> act.openFile(rel)
                "Ganti nama" -> act.promptText("Ganti nama", "Nama baru", f.name) { n -> guard { rename(f, rel, n) } }
                "Hapus" -> act.confirm("Hapus '${f.name}'${if (isDir) " beserta isinya" else ""}?") { delete(f, rel) }
            }
        }
    }

    private fun guard(block: () -> Unit) {
        try { block() } catch (e: Exception) { act.toast(e.message ?: "Gagal") }
    }

    private fun validName(n: String) {
        require(Regex("[A-Za-z0-9_.-]+").matches(n) && n != "." && n != "..") {
            "Nama hanya boleh huruf, angka, titik, _ dan -."
        }
    }

    private fun expandParents(rel: String) {
        val parts = rel.split('/', '\\')
        var acc = ""
        for (i in 0 until parts.size - 1) {
            acc = if (acc.isEmpty()) parts[i] else acc + "/" + parts[i]
            expanded.add(acc)
        }
    }

    private fun createFileIn(dir: File, name: String) {
        val p = act.project ?: return
        validName(name)
        val projDir = act.store.dir(p)
        val f = File(dir, name)
        require(!f.exists()) { "File itu sudah ada." }
        val rel = f.relativeTo(projDir).path
        if (name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".xml")) act.store.newFile(p, rel)
        else {
            require(ProjectStore.isTextFile(f)) { "Ekstensi tidak didukung (txt, md, json, properties, dll)." }
            f.parentFile?.mkdirs()
            f.writeText("")
        }
        expandParents(rel)
        refresh()
        act.openFile(rel)
    }

    private fun createFolderIn(dir: File, name: String) {
        val p = act.project ?: return
        validName(name)
        val f = File(dir, name)
        require(!f.exists()) { "Folder itu sudah ada." }
        f.mkdirs()
        expandParents(f.relativeTo(act.store.dir(p)).path + "/x")
        refresh()
    }

    private fun rename(f: File, rel: String, newName: String) {
        validName(newName)
        val dest = File(f.parentFile, newName)
        require(!dest.exists()) { "Nama itu sudah dipakai." }
        require(f.renameTo(dest)) { "Gagal mengganti nama." }
        val p = act.project ?: return
        val newRel = dest.relativeTo(act.store.dir(p)).path
        val moved = expanded.filter { it == rel || it.startsWith("$rel/") }
        expanded.removeAll(moved.toSet())
        moved.forEach { expanded.add(newRel + it.removePrefix(rel)) }
        act.onPathRenamed(rel, newRel)
        refresh()
    }

    private fun delete(f: File, rel: String) {
        f.deleteRecursively()
        expanded.removeAll(expanded.filter { it == rel || it.startsWith("$rel/") }.toSet())
        act.onPathDeleted(rel)
        refresh()
    }

    /** Tombol + : tambah kelas Java, layout, values, folder, atau file teks. */
    fun addDialog(target: File?) {
        val p = act.project ?: return act.toast("Buka proyek dulu")
        act.pickItem(
            "Tambah",
            listOf("Kelas Java (kode program)", "Kelas Kotlin (.kt)", "Layout (tampilan, .xml)", "Values (teks/warna, .xml)", "Folder baru", "File teks")
        ) { i ->
            val base = target ?: act.store.dir(p)
            when (i) {
                0 -> act.promptText("Kelas Java", "Nama kelas, mis. Pengaturan") { n ->
                    guard {
                        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(n)) { "Nama kelas: huruf, angka, _ (tidak boleh diawali angka)." }
                        val rel = "src/" + act.store.packageOf(p).replace('.', '/') + "/$n.java"
                        act.store.newFile(p, rel)
                        expandParents(rel)
                        refresh()
                        act.openFile(rel)
                    }
                }
                1 -> act.promptText("Kelas Kotlin", "Nama kelas, mis. Pengaturan") { n ->
                    guard {
                        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(n)) { "Nama kelas: huruf, angka, _ (tidak boleh diawali angka)." }
                        val rel = "src/" + act.store.packageOf(p).replace('.', '/') + "/$n.kt"
                        act.store.newFile(p, rel)
                        expandParents(rel)
                        refresh()
                        act.openFile(rel)
                    }
                }
                2 -> act.promptText("Layout baru", "Nama file, mis. activity_main") { n ->
                    guard { newRes(p, "res/layout", n) }
                }
                3 -> act.promptText("Values baru", "Nama file, mis. colors") { n ->
                    guard { newRes(p, "res/values", n) }
                }
                4 -> act.promptText("Folder baru", "Nama folder") { n -> guard { createFolderIn(base, n) } }
                else -> act.promptText("File teks", "Nama file, mis. catatan.txt") { n -> guard { createFileIn(base, n) } }
            }
        }
    }

    private fun newRes(p: String, dir: String, n: String) {
        require(Regex("[A-Za-z][A-Za-z0-9_]*").matches(n)) { "Nama file: huruf, angka, _ (diawali huruf)." }
        val rel = "$dir/${n.lowercase()}.xml"
        act.store.newFile(p, rel)
        expandParents(rel)
        refresh()
        act.openFile(rel)
    }
}
