package com.senza.studio

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Sinkron salinan proyek ke folder pilihan pengguna lewat Storage Access Framework (SAF).
 * Folder itu bisa di penyimpanan internal publik, kartu SD, atau Google Drive. Dengan begitu
 * proyek mudah dicadangkan dan dibuka aplikasi lain. Proyek yang sedang dikerjakan tetap
 * di penyimpanan privat aplikasi (builder butuh akses file biasa); folder ini adalah cermin.
 *
 * Aman: file yang tidak ada di sisi lain TIDAK pernah dihapus. Folder out/ dan .git tidak ikut.
 */
object FolderSync {

    class Result(val copied: Int, val skipped: Int, val failed: Int, val firstError: String?)

    private val SKIP_AT_ROOT = setOf("out", ".git")

    private class Entry(val docId: String, val name: String, val isDir: Boolean, val size: Long, val mtime: Long)

    private fun list(ctx: Context, tree: Uri, parentDocId: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val out = ArrayList<Entry>()
        ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(2) ?: ""
                out.add(
                    Entry(
                        c.getString(0), c.getString(1) ?: continue,
                        mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        if (c.isNull(3)) -1L else c.getLong(3),
                        if (c.isNull(4)) 0L else c.getLong(4)
                    )
                )
            }
        }
        return out
    }

    private fun safeName(n: String) = n.isNotEmpty() && n != "." && n != ".." && !n.contains('/') && !n.contains('\\')

    /** Nama folder terpilih, untuk ditampilkan. */
    fun label(ctx: Context, tree: Uri): String = try {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: Uri.decode(id)
    } catch (_: Throwable) { tree.lastPathSegment ?: tree.toString() }

    /** Proyek -> folder. */
    fun push(ctx: Context, tree: Uri, projectDir: File, log: (String) -> Unit, cancelled: () -> Boolean): Result {
        var copied = 0; var skipped = 0; var failed = 0
        var firstError: String? = null
        val rootId = DocumentsContract.getTreeDocumentId(tree)

        fun dirDoc(parentId: String, name: String, existing: Map<String, Entry>): String {
            existing[name]?.let { if (it.isDir) return it.docId }
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            val created = DocumentsContract.createDocument(ctx.contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: throw IllegalStateException("tidak bisa membuat folder $name")
            return DocumentsContract.getDocumentId(created)
        }

        fun walk(parentId: String, local: File, atRoot: Boolean) {
            val remote = list(ctx, tree, parentId).associateBy { it.name }
            val kids = local.listFiles()?.sortedBy { it.name } ?: return
            for (f in kids) {
                if (cancelled()) throw IllegalStateException("Dibatalkan.")
                if (atRoot && f.name in SKIP_AT_ROOT) continue
                try {
                    if (f.isDirectory) {
                        walk(dirDoc(parentId, f.name, remote), f, false)
                        continue
                    }
                    val r = remote[f.name]
                    if (r != null && !r.isDir && r.size == f.length() && r.mtime > 0 && r.mtime >= f.lastModified()) { skipped++; continue }
                    val target: Uri = if (r != null && !r.isDir) {
                        DocumentsContract.buildDocumentUriUsingTree(tree, r.docId)
                    } else {
                        DocumentsContract.createDocument(
                            ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, parentId),
                            "application/octet-stream", f.name
                        ) ?: throw IllegalStateException("tidak bisa membuat ${f.name}")
                    }
                    val os = ctx.contentResolver.openOutputStream(target, "wt") ?: throw IllegalStateException("tidak bisa menulis ${f.name}")
                    os.use { o -> f.inputStream().use { it.copyTo(o) } }
                    copied++
                    if (copied % 20 == 0) log("  $copied file disalin…")
                } catch (e: Throwable) {
                    if (e.message == "Dibatalkan.") throw e
                    failed++
                    if (firstError == null) firstError = "${f.name}: ${e.message}"
                }
            }
        }
        walk(rootId, projectDir, true)
        return Result(copied, skipped, failed, firstError)
    }

    /** Folder -> proyek. File lokal dengan nama sama akan ditimpa jika berbeda. */
    fun pull(ctx: Context, tree: Uri, projectDir: File, log: (String) -> Unit, cancelled: () -> Boolean): Result {
        var copied = 0; var skipped = 0; var failed = 0
        var firstError: String? = null
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val canon = projectDir.canonicalPath

        fun walk(parentId: String, local: File, atRoot: Boolean) {
            for (r in list(ctx, tree, parentId).sortedBy { it.name }) {
                if (cancelled()) throw IllegalStateException("Dibatalkan.")
                if (!safeName(r.name)) continue
                if (atRoot && r.name in SKIP_AT_ROOT) continue
                val dest = File(local, r.name)
                try {
                    if (!dest.canonicalPath.startsWith(canon + File.separator)) continue
                    if (r.isDir) {
                        dest.mkdirs()
                        walk(r.docId, dest, false)
                        continue
                    }
                    if (dest.isFile && r.size == dest.length() && r.mtime > 0 && dest.lastModified() >= r.mtime) { skipped++; continue }
                    dest.parentFile?.mkdirs()
                    val src = DocumentsContract.buildDocumentUriUsingTree(tree, r.docId)
                    val ins = ctx.contentResolver.openInputStream(src) ?: throw IllegalStateException("tidak bisa membaca ${r.name}")
                    ins.use { i -> dest.outputStream().use { i.copyTo(it) } }
                    if (r.mtime > 0) dest.setLastModified(r.mtime)
                    copied++
                    if (copied % 20 == 0) log("  $copied file diambil…")
                } catch (e: Throwable) {
                    if (e.message == "Dibatalkan.") throw e
                    failed++
                    if (firstError == null) firstError = "${r.name}: ${e.message}"
                }
            }
        }
        walk(rootId, projectDir, true)
        return Result(copied, skipped, failed, firstError)
    }
}
