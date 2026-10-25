package com.senza.studio

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** File Manager: menjelajah folder data Senza Studio (proyek, alat), buka / ganti nama / hapus / bagikan. */
class FilesScreen(act: MainActivity) : Screen(act) {

    override val isTab = true

    private var dir: File = act.store.base
    private lateinit var pathLabel: TextView
    private lateinit var list: LinearLayout

    override val view: View = buildView()

    private fun buildView(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            act.topBar(
                "File Manager", R.drawable.ic_back, { act.back() },
                R.drawable.ic_plus to { newMenu() },
                R.drawable.ic_import to { act.pickZip() }
            ), act.lp()
        )
        pathLabel = act.mono("", 12f, Pal.primaryLight).apply {
            setSingleLine(); ellipsize = TextUtils.TruncateAt.START
            setPadding(act.dp(18), act.dp(2), act.dp(18), act.dp(8))
        }
        col.addView(pathLabel, act.lp())
        list = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(12), 0, act.dp(12), act.dp(16))
        }
        col.addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return col
    }

    override fun onShow() { refresh() }

    override fun onBack(): Boolean {
        if (dir.canonicalPath != act.store.base.canonicalPath) { up(); return true }
        return false
    }

    private fun up() {
        dir.parentFile?.let { dir = it }
        refresh()
    }

    private fun refresh() {
        if (!dir.isDirectory) dir = act.store.base
        pathLabel.text = dir.path.replace(act.filesDir.path, "~")
        list.removeAllViews()
        val atRoot = dir.canonicalPath == act.store.base.canonicalPath
        if (!atRoot) list.addView(row(R.drawable.ic_folder_open, 0xFFFFC857.toInt(), "..", "Naik satu folder") { up() })
        val kids = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        if (kids.isEmpty()) list.addView(act.label("Folder kosong.", 13f, Pal.sub), act.lp(t = 14, l = 8))
        kids.forEach { f ->
            val sub = if (f.isDirectory) "${f.listFiles()?.size ?: 0} item"
            else ProjectStore.humanSize(f.length()) + "  ·  " + SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
            val v = row(
                if (f.isDirectory) R.drawable.ic_folder else R.drawable.ic_file,
                if (f.isDirectory) 0xFFFFC857.toInt() else if (f.extension == "apk") Pal.ok else Pal.sub,
                f.name, sub
            ) { open(f) }
            v.setOnLongClickListener { itemMenu(f); true }
            list.addView(v)
        }
    }

    private fun row(iconRes: Int, tint: Int, name: String, sub: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(12), act.dp(11), act.dp(12), act.dp(11))
            background = act.glassPress(14)
            isClickable = true
            setOnClickListener { onClick() }
            addView(act.icon(iconRes, 22, tint), LinearLayout.LayoutParams(act.dp(22), act.dp(22)))
            val mid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            mid.addView(act.label(name, 14f, Pal.text, true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE })
            mid.addView(act.label(sub, 11f, Pal.sub))
            addView(mid, act.lp(0, WRAP, l = 12, weight = 1f))
            layoutParams = act.lp(b = 6)
        }

    private fun projectOf(f: File): Pair<String, String>? {
        val root = act.store.root.canonicalPath + File.separator
        val c = f.canonicalPath
        if (!c.startsWith(root)) return null
        val rest = c.removePrefix(root)
        val name = rest.substringBefore(File.separator)
        val rel = rest.substringAfter(File.separator, "")
        return if (rel.isEmpty()) null else name to rel
    }

    private fun open(f: File) {
        if (f.isDirectory) { dir = f; refresh(); return }
        val pr = projectOf(f)
        when {
            f.extension == "apk" -> itemMenu(f)
            pr != null && ProjectStore.isTextFile(f) -> {
                if (act.project != pr.first) act.openProject(pr.first)
                act.openFile(pr.second.replace(File.separatorChar, '/'))
            }
            ProjectStore.isTextFile(f) && f.length() < 200_000 -> viewText(f)
            else -> act.toast("Tipe file ini tidak bisa dibuka. Tekan lama untuk opsi lain.")
        }
    }

    private fun viewText(f: File) {
        val tv = act.mono(f.readText(), 12f, Pal.editorFg).apply {
            setTextIsSelectable(true)
            setPadding(act.dp(16), act.dp(10), act.dp(16), act.dp(10))
        }
        android.app.AlertDialog.Builder(act).setTitle(f.name).setView(ScrollView(act).apply { addView(tv) })
            .setPositiveButton("Tutup", null).show()
    }

    private fun itemMenu(f: File) {
        val items = ArrayList<String>()
        if (!f.isDirectory) {
            if (f.extension == "apk") items.add("Install")
            items.add("Share")
        }
        items.add("Ganti nama")
        items.add("Hapus")
        act.pickItem(f.name, items) { i ->
            when (items[i]) {
                "Install" -> act.installApk(f)
                "Share" -> act.shareFile(f, if (f.extension == "apk") "application/vnd.android.package-archive" else "*/*")
                "Ganti nama" -> act.promptText("Ganti nama", "Nama baru", f.name) { n -> renameTo(f, n) }
                "Hapus" -> act.confirm("Hapus '${f.name}'${if (f.isDirectory) " beserta isinya" else ""}?") { deleteIt(f) }
            }
        }
    }

    private fun afterChange() {
        act.explorer.refresh()
        refresh()
    }

    private fun renameTo(f: File, n: String) {
        if (!Regex("[A-Za-z0-9_. -]+").matches(n)) return act.toast("Nama tidak valid.")
        val dest = File(f.parentFile, n)
        if (dest.exists()) return act.toast("Nama itu sudah dipakai.")
        val pr = projectOf(f)
        val isProjectDir = f.parentFile?.canonicalPath == act.store.root.canonicalPath
        if (!f.renameTo(dest)) return act.toast("Gagal mengganti nama.")
        if (isProjectDir && act.project == f.name) act.openProject(n)
        else if (pr != null && act.project == pr.first) act.onPathRenamed(pr.second.replace(File.separatorChar, '/'), dest.relativeTo(act.store.dir(pr.first)).path)
        afterChange()
    }

    private fun deleteIt(f: File) {
        val pr = projectOf(f)
        val isProjectDir = f.parentFile?.canonicalPath == act.store.root.canonicalPath
        if (isProjectDir && f.isDirectory) { act.deleteProject(f.name); afterChange(); return }
        f.deleteRecursively()
        if (pr != null && act.project == pr.first) act.onPathDeleted(pr.second.replace(File.separatorChar, '/'))
        afterChange()
    }

    private fun newMenu() {
        act.pickItem("Baru", listOf("Folder baru", "File teks baru", "Impor proyek (.zip)")) { i ->
            when (i) {
                0 -> act.promptText("Folder baru", "Nama folder") { n ->
                    if (!Regex("[A-Za-z0-9_. -]+").matches(n)) act.toast("Nama tidak valid.")
                    else if (!File(dir, n).mkdirs()) act.toast("Gagal membuat folder.") else afterChange()
                }
                1 -> act.promptText("File teks baru", "Nama file, mis. catatan.txt") { n ->
                    if (!Regex("[A-Za-z0-9_. -]+").matches(n)) act.toast("Nama tidak valid.")
                    else try {
                        val f = File(dir, n)
                        if (f.exists()) act.toast("File itu sudah ada.") else { f.writeText(""); afterChange() }
                    } catch (e: Exception) { act.toast("Gagal: ${e.message}") }
                }
                else -> act.pickZip()
            }
        }
    }
}
