package com.senza.studio

import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Satu library yang sudah diunduh dan (untuk AAR) diekstrak. */
class ResolvedLib(
    val coord: MavenCoord,
    val dir: File,
    val jars: List<File>,
    val resDir: File?,
    val manifest: File?,
    val assetsDir: File?,
    val jniDir: File?,
    val isAar: Boolean,
    /** Aturan keep dari library (proguard.txt di dalam AAR), dipakai R8. */
    val proguard: File? = null
) {
    val id get() = coord.toString()
}

/**
 * Resolver Maven kecil: mengunduh .pom/.jar/.aar dari Google Maven dan Maven Central,
 * menyelesaikan dependency turunan (transitif), memilih versi tertinggi saat bentrok,
 * dan menyimpan semuanya di cache (filesDir/forge/m2).
 */
class MavenResolver(
    private val cacheRoot: File,
    private val log: (String) -> Unit,
    private val cancelled: () -> Boolean
) {
    companion object {
        val REPOS = listOf(
            "https://dl.google.com/dl/android/maven2",
            "https://repo1.maven.org/maven2"
        )
        /** Pustaka lama yang kelasnya bentrok dengan penggantinya. */
        private val DROP = setOf("com.intellij:annotations")

        fun compareVersions(a: String, b: String): Int {
            fun tok(v: String) = v.split('.', '-', '_', '+').filter { it.isNotEmpty() }
            fun qual(s: String): Int = when (s.lowercase()) {
                "alpha", "a" -> 1
                "beta", "b" -> 2
                "m", "milestone" -> 3
                "rc", "cr" -> 4
                "snapshot" -> 0
                "", "final", "ga", "release" -> 6
                else -> 5
            }
            val x = tok(a); val y = tok(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val p = x.getOrNull(i); val q = y.getOrNull(i)
                if (p == null) return if (q!!.toIntOrNull() != null) -1 else 1
                if (q == null) return if (p.toIntOrNull() != null) 1 else -1
                val pi = p.toIntOrNull(); val qi = q.toIntOrNull()
                val c = when {
                    pi != null && qi != null -> pi.compareTo(qi)
                    pi != null -> 1
                    qi != null -> -1
                    else -> qual(p.filter { it.isLetter() }).compareTo(qual(q.filter { it.isLetter() }))
                }
                if (c != 0) return c
            }
            return 0
        }
    }

    private val repoDir = File(cacheRoot, "repo").apply { mkdirs() }
    private val extractDir = File(cacheRoot, "extracted").apply { mkdirs() }

    // ---------- model pom ----------

    private class RawDep(
        val g: String, val a: String, val v: String?, val scope: String?,
        val optional: Boolean, val type: String?, val exclusions: List<String>
    )

    private class RawPom(
        val g: String?, val a: String, val v: String?, val packaging: String,
        val parent: MavenCoord?, val props: Map<String, String>,
        val mgmt: List<RawDep>, val deps: List<RawDep>
    )

    private class Dep(val g: String, val a: String, val v: String, val scope: String, val optional: Boolean, val exclusions: List<String>) {
        val key get() = "$g:$a"
    }

    private class EffPom(val packaging: String, val props: Map<String, String>, val mgmt: Map<String, Dep>, val deps: List<Dep>)

    private val effCache = HashMap<String, EffPom>()

    // ---------- API ----------

    /**
     * [roots] boleh berversi kosong: versinya diambil dari BOM di [platforms].
     * BOM biasa bertindak sebagai batas bawah (versi tertinggi menang, seperti constraint Gradle);
     * enforcedPlatform memaksa versinya.
     */
    fun resolve(roots0: List<MavenCoord>, platforms: List<PlatformDep> = emptyList()): List<ResolvedLib> {
        if (roots0.isEmpty()) return emptyList()
        // rentang versi Maven "[0.7.0,0.8.0)" -> batas bawah (tanpa maven-metadata, jadi bukan "tertinggi yang cocok")
        val roots = roots0.map { r ->
            if (r.version.startsWith("[") || r.version.startsWith("(")) {
                val v = cleanVersion(r.version)
                log("  rentang versi ${r.key}:${r.version} dipakai sebagai $v")
                MavenCoord(r.group, r.artifact, v)
            } else r
        }

        val constraints = HashMap<String, String>()
        val forced = HashSet<String>()
        for (p in platforms) {
            checkCancel()
            val bom = effective(p.coord)
            log("  BOM ${p.coord}: ${bom.mgmt.size} versi diatur${if (p.enforced) " (dipaksa)" else ""}")
            for ((k, d) in bom.mgmt) {
                if (p.enforced) {
                    constraints[k] = d.v
                    forced.add(k)
                } else if (k !in forced) {
                    val cur = constraints[k]
                    if (cur == null || compareVersions(d.v, cur) > 0) constraints[k] = d.v
                }
            }
        }
        /** Versi yang dipakai untuk [key] bila ada yang menawarkan [v], setelah memperhitungkan BOM. */
        fun candidate(key: String, v: String): String {
            val c = constraints[key] ?: return v
            return if (key in forced) c else if (compareVersions(c, v) > 0) c else v
        }

        val chosen = LinkedHashMap<String, String>()
        for (r0 in roots) {
            val v = if (r0.version.isNotBlank()) candidate(r0.key, r0.version) else constraints[r0.key] ?: throw BuildError(
                BuildError.Kind.OTHER,
                "Dependency ${r0.key} tidak punya versi.\nTulis versinya, atau tambahkan platform(\"group:bom:versi\") yang mengaturnya."
            )
            val cur = chosen[r0.key]
            if (cur == null || compareVersions(v, cur) > 0) chosen[r0.key] = v
        }

        var order = ArrayList<String>()
        for (round in 1..10) {
            checkCancel()
            order = ArrayList()
            var changed = false
            val visited = HashSet<String>()
            // antrian: (kunci, exclusions di sepanjang jalur)
            val queue = ArrayDeque<Pair<String, Set<String>>>()
            for (r in roots.map { it.key }.distinct()) queue.add(r to emptySet())
            while (queue.isNotEmpty()) {
                val (key, excl) = queue.removeFirst()
                if (key in DROP) continue
                if (!visited.add(key)) continue
                order.add(key)
                val ver = chosen[key] ?: continue
                val (g, a) = key.split(':').let { it[0] to it[1] }
                val eff = effective(MavenCoord(g, a, ver))
                for (d in eff.deps) {
                    if (d.optional) continue
                    if (d.scope != "compile" && d.scope != "runtime") continue
                    if (d.key in excl || "${d.g}:*" in excl || "*:${d.a}" in excl) continue
                    if (d.key in DROP) continue
                    val cur = chosen[d.key]
                    val cand = candidate(d.key, d.v)
                    if (cur == null || (d.key in forced && cur != cand) || compareVersions(cand, cur) > 0) {
                        chosen[d.key] = cand
                        changed = true
                    }
                    queue.add(d.key to (excl + d.exclusions))
                }
            }
            if (!changed) break
        }

        // dependency dulu, pemakainya belakangan (urutan yang cocok untuk overlay resource)
        val result = ArrayList<ResolvedLib>()
        for (key in order.reversed()) {
            checkCancel()
            val (g, a) = key.split(':').let { it[0] to it[1] }
            val ver = chosen[key] ?: continue
            materialize(MavenCoord(g, a, ver))?.let { result.add(it) }
        }
        return result
    }

    private fun checkCancel() {
        if (cancelled()) throw BuildError(BuildError.Kind.OTHER, "Build dibatalkan.")
    }

    // ---------- pom ----------

    private fun effective(c: MavenCoord, depth: Int = 0): EffPom {
        effCache[c.toString()]?.let { return it }
        val raw = parsePom(fetch(c, "pom") ?: throw BuildError(
            BuildError.Kind.OTHER, "Dependency tidak ditemukan: $c\nCek ejaan group, artifact, dan versinya."
        ))
        val props = HashMap<String, String>()
        val mgmt = LinkedHashMap<String, Dep>()
        if (raw.parent != null && depth < 8) {
            val p = effective(raw.parent, depth + 1)
            props.putAll(p.props)
            mgmt.putAll(p.mgmt)
        }
        val gid = raw.g ?: raw.parent?.group ?: c.group
        val ver = raw.v ?: raw.parent?.version ?: c.version
        props["project.groupId"] = gid
        props["pom.groupId"] = gid
        props["groupId"] = gid
        props["project.artifactId"] = raw.a
        props["project.version"] = ver
        props["pom.version"] = ver
        props["version"] = ver
        raw.parent?.let {
            props["project.parent.version"] = it.version
            props["parent.version"] = it.version
            props["project.parent.groupId"] = it.group
        }
        props.putAll(raw.props)

        for (d in raw.mgmt) {
            val dg = interp(d.g, props) ?: d.g
            val da = interp(d.a, props) ?: d.a
            val dv = interp(d.v, props) ?: continue
            if (d.scope == "import" && d.type == "pom" && depth < 8) {
                val bom = try { effective(MavenCoord(dg, da, cleanVersion(dv)), depth + 1) } catch (e: BuildError) { null }
                bom?.mgmt?.forEach { (k, v) -> mgmt.putIfAbsent(k, v) }
            } else {
                mgmt["$dg:$da"] = Dep(dg, da, cleanVersion(dv), d.scope ?: "compile", d.optional, emptyList())
            }
        }

        val deps = ArrayList<Dep>()
        for (d in raw.deps) {
            val dg = interp(d.g, props) ?: d.g
            val da = interp(d.a, props) ?: d.a
            val managed = mgmt["$dg:$da"]
            val dvRaw = interp(d.v, props) ?: managed?.v ?: continue
            val scope = d.scope ?: managed?.scope ?: "compile"
            if (d.type != null && d.type != "jar" && d.type != "aar" && d.type != "bundle") continue
            deps.add(Dep(dg, da, cleanVersion(dvRaw), scope, d.optional, d.exclusions))
        }
        val e = EffPom(raw.packaging, props, mgmt, deps)
        effCache[c.toString()] = e
        return e
    }

    private fun cleanVersion(v: String): String {
        var s = v.trim()
        if (s.startsWith("[") || s.startsWith("(")) {
            s = s.trim('[', ']', '(', ')')
            s = s.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: s
        }
        return s
    }

    private fun interp(s: String?, props: Map<String, String>): String? {
        if (s == null) return null
        var cur = s.trim()
        for (i in 0 until 6) {
            if (!cur.contains("\${")) break
            cur = Regex("""\$\{([^}]+)\}""").replace(cur) { m -> props[m.groupValues[1]] ?: m.value }
        }
        return cur
    }

    private fun parsePom(f: File): RawPom {
        val dbf = DocumentBuilderFactory.newInstance()
        try { dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Exception) {}
        val doc = try { dbf.newDocumentBuilder().parse(f) } catch (e: Exception) {
            f.delete()
            throw BuildError(BuildError.Kind.OTHER, "File pom rusak (${f.name}), coba build lagi.")
        }
        val root = doc.documentElement

        fun Element.kids(name: String): List<Element> {
            val out = ArrayList<Element>()
            var n = firstChild
            while (n != null) { if (n is Element && n.tagName == name) out.add(n); n = n.nextSibling }
            return out
        }
        fun Element.kid(name: String) = kids(name).firstOrNull()
        fun Element.txt(name: String) = kid(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

        fun dep(e: Element): RawDep? {
            val g = e.txt("groupId") ?: return null
            val a = e.txt("artifactId") ?: return null
            val ex = ArrayList<String>()
            e.kid("exclusions")?.kids("exclusion")?.forEach { x ->
                ex.add("${x.txt("groupId") ?: "*"}:${x.txt("artifactId") ?: "*"}")
            }
            return RawDep(g, a, e.txt("version"), e.txt("scope"), e.txt("optional") == "true", e.txt("type"), ex)
        }

        val parent = root.kid("parent")?.let {
            val pg = it.txt("groupId"); val pa = it.txt("artifactId"); val pv = it.txt("version")
            if (pg != null && pa != null && pv != null) MavenCoord(pg, pa, cleanVersion(pv)) else null
        }
        val props = HashMap<String, String>()
        root.kid("properties")?.let { p ->
            var n = p.firstChild
            while (n != null) {
                val cur = n
                if (cur is Element) props[cur.tagName] = cur.textContent.trim()
                n = n.nextSibling
            }
        }
        val mgmt = root.kid("dependencyManagement")?.kid("dependencies")?.kids("dependency")?.mapNotNull { dep(it) } ?: emptyList()
        val deps = root.kid("dependencies")?.kids("dependency")?.mapNotNull { dep(it) } ?: emptyList()
        return RawPom(
            root.txt("groupId"), root.txt("artifactId") ?: "", root.txt("version"),
            root.txt("packaging") ?: "jar", parent, props, mgmt, deps
        )
    }

    // ---------- unduh & ekstrak ----------

    private enum class Dl { OK, NOT_FOUND, ERROR }

    private var netError: String? = null

    private fun fetch(c: MavenCoord, ext: String): File? {
        val rel = "${c.group.replace('.', '/')}/${c.artifact}/${c.version}/${c.artifact}-${c.version}.$ext"
        val f = File(repoDir, rel)
        if (f.isFile && f.length() > 0) return f
        val miss = File(f.path + ".missing")
        if (miss.exists()) return null
        f.parentFile?.mkdirs()
        var anyError = false
        for (repo in REPOS) {
            checkCancel()
            when (download("$repo/$rel", f)) {
                Dl.OK -> { log("  ↓ ${c.artifact}-${c.version}.$ext"); return f }
                Dl.NOT_FOUND -> {}
                Dl.ERROR -> anyError = true
            }
        }
        if (anyError) {
            throw BuildError(
                BuildError.Kind.OTHER,
                "Gagal mengunduh $c.\nPastikan HP terhubung ke internet lalu coba lagi.${netError?.let { "\n($it)" } ?: ""}"
            )
        }
        miss.writeText("404")
        return null
    }

    private fun download(url: String, dest: File): Dl {
        val tmp = File(dest.path + ".part")
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "SenzaStudio")
            val code = conn.responseCode
            if (code == 404 || code == 403 || code == 410) return Dl.NOT_FOUND
            if (code !in 200..299) { netError = "HTTP $code dari ${URL(url).host}"; return Dl.ERROR }
            conn.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (tmp.length() == 0L) { tmp.delete(); return Dl.NOT_FOUND }
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
            return Dl.OK
        } catch (e: IOException) {
            netError = e.message ?: e.javaClass.simpleName
            tmp.delete()
            return Dl.ERROR
        } finally {
            conn?.disconnect()
        }
    }

    private fun materialize(c: MavenCoord): ResolvedLib? {
        val eff = effective(c)
        val packaging = eff.packaging
        if (packaging == "pom") return null   // BOM / agregator: tidak ada kode
        val dir = File(extractDir, "${c.group}/${c.artifact}/${c.version}")

        val aar = if (packaging == "aar") fetch(c, "aar") else null
        if (aar != null) return extractAar(c, aar, dir)

        val jar = fetch(c, "jar")
        if (jar != null) return ResolvedLib(c, dir, listOf(jar), null, null, null, null, false)

        if (packaging != "aar") {
            val aar2 = fetch(c, "aar")
            if (aar2 != null) return extractAar(c, aar2, dir)
        }
        if (eff.deps.isEmpty()) log("  ! ${c.key} tidak punya berkas library, dilewati")
        return null   // pom pengalih (mis. library multiplatform): isinya ada di dependency-nya
    }

    private fun extractAar(c: MavenCoord, aar: File, dir: File): ResolvedLib {
        val done = File(dir, ".done")
        if (!done.exists()) {
            dir.deleteRecursively()
            dir.mkdirs()
            val canon = dir.canonicalPath
            ZipInputStream(aar.inputStream().buffered()).use { zi ->
                while (true) {
                    val e = zi.nextEntry ?: break
                    if (e.isDirectory) continue
                    val out = File(dir, e.name)
                    if (!out.canonicalPath.startsWith(canon + File.separator)) continue
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zi.copyTo(it) }
                }
            }
            done.writeText("ok")
        }
        val jars = ArrayList<File>()
        File(dir, "classes.jar").takeIf { it.isFile }?.let { jars.add(it) }
        File(dir, "libs").listFiles { f -> f.extension == "jar" }?.sortedBy { it.name }?.let { jars.addAll(it) }
        return ResolvedLib(
            c, dir, jars,
            File(dir, "res").takeIf { it.isDirectory && !it.listFiles().isNullOrEmpty() },
            File(dir, "AndroidManifest.xml").takeIf { it.isFile },
            File(dir, "assets").takeIf { it.isDirectory },
            File(dir, "jni").takeIf { it.isDirectory },
            true,
            File(dir, "proguard.txt").takeIf { it.isFile && it.length() > 0 }
        )
    }
}
