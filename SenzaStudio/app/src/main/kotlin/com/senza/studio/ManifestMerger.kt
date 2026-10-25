package com.senza.studio

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Menggabungkan AndroidManifest.xml aplikasi dengan manifest tiap library (izin, activity,
 * service, receiver, provider, meta-data) sebelum `aapt2 link`.
 * Aturan: yang sudah ada di aplikasi menang; atribut `tools:node="remove"` di aplikasi
 * menghapus entri sejenis dari library.
 */
object ManifestMerger {

    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    private const val TOOLS = "http://schemas.android.com/tools"

    private val APP_CHILDREN = setOf(
        "activity", "activity-alias", "service", "receiver", "provider",
        "meta-data", "uses-library", "uses-native-library"
    )
    private val TOP_CHILDREN = setOf("uses-permission", "uses-permission-sdk-23", "uses-feature", "permission", "permission-group")

    class Result(val file: File, val pkg: String, val added: Int, val notes: List<String>)

    private fun parse(f: File): Document {
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = true
        return dbf.newDocumentBuilder().parse(f)
    }

    private fun children(e: Element): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = e.firstChild
        while (n != null) { if (n is Element) out.add(n); n = n.nextSibling }
        return out
    }

    private fun nameOf(e: Element): String? =
        e.getAttributeNS(ANDROID, "name").takeIf { it.isNotEmpty() }

    private fun keyOf(e: Element): String? {
        val n = nameOf(e)
        return when (e.tagName) {
            "uses-feature" -> n?.let { "uses-feature:$it" }
                ?: e.getAttributeNS(ANDROID, "glEsVersion").takeIf { it.isNotEmpty() }?.let { "uses-feature:gl:$it" }
            else -> n?.let { "${e.tagName}:$it" }
        }
    }

    /** Ubah nama relatif (".Foo" atau "Foo") menjadi nama lengkap memakai package library. */
    private fun fullName(name: String, pkg: String): String = when {
        pkg.isEmpty() -> name
        name.startsWith(".") -> pkg + name
        !name.contains('.') -> "$pkg.$name"
        else -> name
    }

    private fun substitute(s: String, pkg: String, placeholders: Map<String, String>): String {
        var out = s
        for ((k, v) in placeholders) out = out.replace("\${$k}", v)   // nilai kustom (mis. applicationId varian) menang
        out = out.replace("\${applicationId}", pkg).replace("\${packageName}", pkg)
        return out
    }

    private fun stripTools(e: Element) {
        val attrs = e.attributes
        val remove = ArrayList<String>()
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if (a.namespaceURI == TOOLS) remove.add(a.localName)
        }
        remove.forEach { e.removeAttributeNS(TOOLS, it) }
        children(e).forEach { stripTools(it) }
    }

    /**
     * @param appManifest manifest proyek
     * @param libManifests manifest dari library (AAR), urutan bebas
     * @param fallbackPackage dipakai jika manifest aplikasi tidak punya atribut package
     * @param placeholders nilai ${...} tambahan
     */
    fun merge(
        appManifest: File,
        libManifests: List<File>,
        out: File,
        fallbackPackage: String?,
        placeholders: Map<String, String> = emptyMap()
    ): Result {
        val doc = try { parse(appManifest) } catch (e: Exception) {
            throw BuildError(BuildError.Kind.RESOURCE, "AndroidManifest.xml tidak bisa dibaca: ${e.message}", appManifest.path, null)
        }
        val root = doc.documentElement
        var pkg = root.getAttribute("package")
        if (pkg.isEmpty()) {
            pkg = fallbackPackage ?: throw BuildError(
                BuildError.Kind.RESOURCE,
                "AndroidManifest.xml tidak punya atribut package, dan build.gradle tidak punya namespace/applicationId.",
                appManifest.path, null
            )
            root.setAttribute("package", pkg)
        }
        val notes = ArrayList<String>()

        val app: Element = children(root).firstOrNull { it.tagName == "application" }
            ?: doc.createElement("application").also { root.appendChild(it) }

        // kunci yang sudah ada + kunci yang diminta dihapus oleh aplikasi
        val existing = HashSet<String>()
        val removed = HashSet<String>()
        fun scan(parent: Element, tags: Set<String>) {
            for (c in children(parent)) {
                if (c.tagName !in tags) continue
                val k = keyOf(c) ?: continue
                if (c.getAttributeNS(TOOLS, "node") == "remove") removed.add(k) else existing.add(k)
            }
        }
        scan(root, TOP_CHILDREN)
        scan(app, APP_CHILDREN)
        children(root).firstOrNull { it.tagName == "queries" }?.let { q ->
            children(q).forEach { c -> existing.add("queries:" + c.tagName + ":" + (nameOf(c) ?: c.getAttribute("android:name"))) }
        }
        removed.forEach { k -> existing.add(k) }

        var added = 0
        for (lf in libManifests) {
            val ldoc = try { parse(lf) } catch (e: Exception) {
                notes.add("Manifest library dilewati (rusak): ${lf.parentFile?.name}")
                continue
            }
            val lroot = ldoc.documentElement
            val lpkg = lroot.getAttribute("package")

            fun importInto(target: Element, src: Element) {
                // salin dulu agar boleh diubah tanpa menyentuh dokumen library
                val copy = doc.importNode(src, true) as Element
                fixNames(copy, lpkg, pkg, placeholders)
                stripTools(copy)
                target.appendChild(copy)
                added++
            }

            for (c in children(lroot)) {
                when {
                    c.tagName in TOP_CHILDREN -> {
                        val k = keyOf(c) ?: continue
                        if (k in removed || !existing.add(k)) continue
                        importInto(root, c)
                    }
                    c.tagName == "queries" -> {
                        var q = children(root).firstOrNull { it.tagName == "queries" }
                        if (q == null) { q = doc.createElement("queries"); root.appendChild(q) }
                        for (qc in children(c)) {
                            val k = "queries:" + qc.tagName + ":" + (nameOf(qc) ?: qc.textContent.trim() + children(qc).joinToString { it.tagName })
                            if (!existing.add(k)) continue
                            importInto(q, qc)
                        }
                    }
                    c.tagName == "application" -> {
                        for (ac in children(c)) {
                            if (ac.tagName !in APP_CHILDREN) continue
                            val nm = nameOf(ac) ?: continue
                            val isComponent = ac.tagName in setOf("activity", "activity-alias", "service", "receiver", "provider")
                            val full = if (isComponent) fullName(nm, lpkg) else nm
                            val k = "${ac.tagName}:$full"
                            if (k in removed || !existing.add(k)) continue
                            importInto(app, ac)
                        }
                    }
                }
            }
        }
        // elemen aplikasi yang ditandai tools:node="remove" dibuang seluruhnya
        for (parent in listOf(root, app)) {
            for (c in children(parent)) {
                if (c.getAttributeNS(TOOLS, "node") == "remove") parent.removeChild(c)
            }
        }
        stripTools(root)

        out.parentFile?.mkdirs()
        val tf = TransformerFactory.newInstance().newTransformer()
        tf.setOutputProperty(OutputKeys.INDENT, "yes")
        tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        out.outputStream().use { tf.transform(DOMSource(doc), StreamResult(it)) }
        return Result(out, pkg, added, notes)
    }

    /** Lengkapi nama komponen dan ganti placeholder pada seluruh atribut. */
    private fun fixNames(e: Element, libPkg: String, appPkg: String, placeholders: Map<String, String>) {
        val attrs = e.attributes
        val isComponent = e.tagName in setOf("activity", "activity-alias", "service", "receiver", "provider")
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            var v = substitute(a.nodeValue ?: "", appPkg, placeholders)
            if (a.namespaceURI == ANDROID && isComponent && (a.localName == "name" || a.localName == "targetActivity")) {
                v = fullName(v, libPkg)
            }
            // android:authorities=".x" tidak dikenal; hanya substitusi
            a.nodeValue = v
        }
        children(e).forEach { fixNames(it, libPkg, appPkg, placeholders) }
    }
}
