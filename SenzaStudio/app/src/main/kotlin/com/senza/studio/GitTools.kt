package com.senza.studio

import android.content.Context
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/** Satu perubahan di working tree. [code]: ? baru, A ditambahkan, M diubah, D dihapus, U konflik. */
class GitChange(val code: Char, val path: String)

class GitState(
    val branch: String,
    val remote: String?,
    val ahead: Int,
    val behind: Int,
    val changes: List<GitChange>,
    val hasCommits: Boolean
) {
    val clean get() = changes.isEmpty()
}

class GitCommit(val id: String, val message: String, val author: String, val timeMs: Long)

/** Monitor progres JGit yang menulis ke log dan bisa dibatalkan. */
class GitMonitor(private val log: (String) -> Unit, private val cancelled: () -> Boolean) : ProgressMonitor {
    private var title = ""
    private var total = 0
    private var done = 0
    private var lastPct = -1
    override fun start(totalTasks: Int) {}
    override fun beginTask(title: String, totalWork: Int) {
        this.title = title; total = totalWork; done = 0; lastPct = -1
        if (totalWork == 0) log("  $title…")
    }
    override fun update(completed: Int) {
        done += completed
        if (total > 0) {
            val pct = done * 100 / total
            if (pct / 25 != lastPct / 25) { lastPct = pct; log("  $title: $pct%") }
        }
    }
    override fun endTask() {}
    override fun isCancelled(): Boolean = cancelled()
}

/**
 * Git lewat JGit (tanpa Termux). Hanya HTTPS dengan token (mis. GitHub Personal Access Token);
 * SSH belum didukung. Token disimpan terenkripsi di [SecretStore] (kunci Android Keystore).
 * Semua fungsi di sini BLOKIR; panggil dari thread latar.
 */
class GitTools(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("senza_studio", Context.MODE_PRIVATE)
    private val secrets = SecretStore(ctx)

    init {
        // JGit mencari folder home untuk .gitconfig; di Android tidak ada, arahkan ke folder privat aplikasi.
        try {
            val home = File(ctx.filesDir, "forge/githome").apply { mkdirs() }
            System.setProperty("user.home", home.path)
            org.eclipse.jgit.util.FS.DETECTED.setUserHome(home)
        } catch (_: Throwable) {}
    }

    // ---------- akun ----------

    var userName: String
        get() = prefs.getString("git_user", "") ?: ""
        set(v) { prefs.edit().putString("git_user", v.trim()).apply() }
    var authorName: String
        get() = prefs.getString("git_name", "") ?: ""
        set(v) { prefs.edit().putString("git_name", v.trim()).apply() }
    var authorEmail: String
        get() = prefs.getString("git_email", "") ?: ""
        set(v) { prefs.edit().putString("git_email", v.trim()).apply() }

    fun hasToken() = secrets.has("git.token")
    fun setToken(t: String?) = secrets.put("git.token", t?.trim())

    private fun token(): String? = secrets.get("git.token")

    private fun creds(): UsernamePasswordCredentialsProvider? {
        val t = token() ?: return null
        // GitHub menerima username apa saja (non-kosong) bersama token
        return UsernamePasswordCredentialsProvider(userName.ifBlank { "senza" }, t)
    }

    /** Buang token dari teks sebelum ditampilkan. */
    fun scrub(msg: String?): String {
        var m = msg ?: ""
        token()?.let { if (it.isNotEmpty()) m = m.replace(it, "***") }
        return m
    }

    /** Ubah error JGit menjadi kalimat yang dimengerti pemula. */
    fun friendly(e: Throwable): String {
        val raw = scrub(generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" | "))
        return when {
            raw.contains("not authorized", true) || raw.contains("Authentication is required", true) ||
                raw.contains("401") || raw.contains("403") ->
                "Ditolak server: token salah, kedaluwarsa, atau tidak punya izin tulis ke repo ini.\n($raw)"
            raw.contains("UnknownHost", true) || raw.contains("Unable to resolve", true) || raw.contains("connect", true) && raw.contains("failed", true) ->
                "Tidak ada koneksi internet atau alamat server salah.\n($raw)"
            raw.contains("not found", true) && raw.contains("repository", true) || raw.contains("404") ->
                "Repo tidak ditemukan. Cek URL-nya (atau token tidak boleh melihat repo privat ini).\n($raw)"
            raw.contains("rejected", true) || raw.contains("non-fast-forward", true) ->
                "Push ditolak karena repo di server lebih baru. Lakukan Pull dulu, lalu Push lagi.\n($raw)"
            raw.contains("Cancelled", true) || raw.contains("canceled", true) -> "Dibatalkan."
            else -> raw.ifBlank { e.javaClass.simpleName }
        }
    }

    // ---------- repositori ----------

    fun isRepo(dir: File) = File(dir, ".git").isDirectory

    private fun open(dir: File): Git {
        require(isRepo(dir)) { "Proyek ini belum menjadi repositori Git." }
        return Git.open(dir)
    }

    private fun ensureIgnore(dir: File) {
        val f = File(dir, ".gitignore")
        val needed = listOf("out/")
        if (!f.exists()) { f.writeText(needed.joinToString("\n") + "\n"); return }
        val lines = f.readLines().map { it.trim() }
        val missing = needed.filter { it !in lines && it.trimEnd('/') !in lines }
        if (missing.isNotEmpty()) f.appendText((if (f.readText().endsWith("\n")) "" else "\n") + missing.joinToString("\n") + "\n")
    }

    fun init(dir: File) {
        require(!isRepo(dir)) { "Proyek ini sudah menjadi repositori Git." }
        Git.init().setDirectory(dir).setInitialBranch("main").call().close()
        ensureIgnore(dir)
    }

    fun clone(url: String, dir: File, monitor: ProgressMonitor) {
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "Hanya URL HTTPS yang didukung (contoh: https://github.com/nama/repo.git). SSH belum ada."
        }
        val cmd = Git.cloneRepository().setURI(url).setDirectory(dir).setProgressMonitor(monitor)
        creds()?.let { cmd.setCredentialsProvider(it) }
        cmd.call().close()
        ensureIgnore(dir)
    }

    fun remoteUrl(dir: File): String? = open(dir).use { it.repository.config.getString("remote", "origin", "url") }

    fun setRemote(dir: File, url: String) {
        require(url.startsWith("https://") || url.startsWith("http://")) { "Hanya URL HTTPS yang didukung." }
        open(dir).use { g ->
            val c = g.repository.config
            c.setString("remote", "origin", "url", url)
            c.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
            c.save()
        }
    }

    private fun trackBranch(repo: Repository, branch: String) {
        val c = repo.config
        if (c.getString("branch", branch, "remote") == null) {
            c.setString("branch", branch, "remote", "origin")
            c.setString("branch", branch, "merge", "refs/heads/$branch")
            c.save()
        }
    }

    fun status(dir: File): GitState = open(dir).use { g ->
        val repo = g.repository
        val st = g.status().call()
        val map = LinkedHashMap<String, Char>()
        st.untracked.forEach { map[it] = '?' }
        st.added.forEach { map[it] = 'A' }
        (st.changed + st.modified).forEach { map[it] = 'M' }
        (st.removed + st.missing).forEach { map[it] = 'D' }
        st.conflicting.forEach { map[it] = 'U' }
        val branch = repo.branch ?: "?"
        val tr = try { BranchTrackingStatus.of(repo, branch) } catch (_: Throwable) { null }
        GitState(
            branch, repo.config.getString("remote", "origin", "url"),
            tr?.aheadCount ?: 0, tr?.behindCount ?: 0,
            map.entries.sortedBy { it.value.toString() + it.key }.map { GitChange(it.value, it.key) },
            repo.resolve("HEAD") != null
        )
    }

    /** Stage semua perubahan lalu commit. Mengembalikan id pendek commit. */
    fun commitAll(dir: File, message: String): String {
        require(message.isNotBlank()) { "Tulis pesan commit." }
        require(authorName.isNotBlank() && authorEmail.contains('@')) { "Isi nama dan email di Akun Git dulu." }
        ensureIgnore(dir)
        return open(dir).use { g ->
            g.add().addFilepattern(".").call()
            g.add().setUpdate(true).addFilepattern(".").call()   // ikut menandai file yang dihapus
            require(!g.status().call().isClean) { "Tidak ada perubahan untuk di-commit." }
            val who = PersonIdent(authorName, authorEmail)
            val c = g.commit().setMessage(message.trim()).setAuthor(who).setCommitter(who).call()
            c.name.take(7)
        }
    }

    fun push(dir: File, monitor: ProgressMonitor): String = open(dir).use { g ->
        val repo = g.repository
        require(repo.config.getString("remote", "origin", "url") != null) { "Atur alamat remote (origin) dulu." }
        require(creds() != null) { "Isi token di Akun Git dulu." }
        val branch = repo.branch ?: throw IllegalStateException("Tidak ada cabang aktif.")
        require(repo.resolve("HEAD") != null) { "Belum ada commit. Lakukan Commit dulu." }
        val results: Iterable<PushResult> = g.push().setRemote("origin")
            .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
            .setCredentialsProvider(creds()).setProgressMonitor(monitor).call()
        val problems = ArrayList<String>()
        var ok = 0
        for (r in results) for (u in r.remoteUpdates) {
            when (u.status) {
                RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> ok++
                RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ->
                    problems.add("${u.remoteName}: ditolak, repo di server lebih baru. Pull dulu.")
                else -> problems.add("${u.remoteName}: ${u.status}" + (u.message?.let { " ($it)" } ?: ""))
            }
        }
        if (problems.isNotEmpty()) throw IllegalStateException(problems.joinToString("\n"))
        trackBranch(repo, branch)
        if (ok == 0) "Tidak ada yang dikirim." else "Push ke origin/$branch berhasil."
    }

    fun pull(dir: File, monitor: ProgressMonitor): String = open(dir).use { g ->
        val repo = g.repository
        require(repo.config.getString("remote", "origin", "url") != null) { "Atur alamat remote (origin) dulu." }
        val branch = repo.branch ?: throw IllegalStateException("Tidak ada cabang aktif.")
        trackBranch(repo, branch)
        val cmd = g.pull().setProgressMonitor(monitor)
        creds()?.let { cmd.setCredentialsProvider(it) }
        val r = cmd.call()
        val merge = r.mergeResult
        when {
            merge != null && merge.mergeStatus == MergeResult.MergeStatus.CONFLICTING ->
                "Pull selesai, tetapi ada konflik di:\n" + (merge.conflicts?.keys?.joinToString("\n") { "  $it" } ?: "") +
                    "\nBuka file itu, rapikan tanda <<<<<<< ======= >>>>>>>, lalu Commit."
            merge != null && merge.mergeStatus == MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> "Sudah terbaru."
            r.isSuccessful -> "Pull berhasil."
            else -> "Pull belum berhasil. Cek log di atas."
        }
    }

    fun log(dir: File, max: Int = 15): List<GitCommit> = open(dir).use { g ->
        try {
            g.log().setMaxCount(max).call().map {
                GitCommit(it.name.take(7), it.shortMessage, it.authorIdent?.name ?: "", it.commitTime * 1000L)
            }
        } catch (_: Throwable) { emptyList() }
    }

    fun branches(dir: File): List<String> = open(dir).use { g ->
        g.branchList().call().map { Repository.shortenRefName(it.name) }.sorted()
    }

    fun checkout(dir: File, name: String, create: Boolean) {
        require(Regex("[A-Za-z0-9._/-]{1,60}").matches(name)) { "Nama cabang tidak valid." }
        open(dir).use { g ->
            if (!create) require(!g.status().call().let { it.modified.isNotEmpty() || it.changed.isNotEmpty() || it.added.isNotEmpty() || it.removed.isNotEmpty() || it.missing.isNotEmpty() }) {
                "Ada perubahan yang belum di-commit. Commit dulu sebelum pindah cabang."
            }
            g.checkout().setName(name).setCreateBranch(create).call()
        }
    }
}
