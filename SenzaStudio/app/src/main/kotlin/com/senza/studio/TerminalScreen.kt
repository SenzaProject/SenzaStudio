package com.senza.studio

import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Terminal nyata: perintah shell Android dijalankan di folder proyek, plus perintah build. */
class TerminalScreen(act: MainActivity) : Screen(act), BuildObserver {

    override val isTab = true

    private val user = "u0_a" + (android.os.Process.myUid() % 100000 - 10000).coerceAtLeast(0)
    private var cwd: File = act.filesDir
    private val history = ArrayList<String>()
    private var histPos = 0
    @Volatile private var proc: java.lang.Process? = null
    @Volatile private var running = false
    private var buildFromTerm = false

    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText

    override val view: View = buildView()

    private fun buildView(): View {
        cwd = act.project?.let { act.store.dir(it) } ?: act.store.root
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            act.topBar(
                "Terminal", R.drawable.ic_back, { act.back() },
                R.drawable.ic_more to { menu() }
            ), act.lp()
        )
        out = TextView(act).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(0xFFC9D6FF.toInt())
            setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(12))
            setTextIsSelectable(true)
        }
        scroll = ScrollView(act).apply {
            background = act.glass(16, 0x99040A24.toInt(), Pal.glassStroke)
            addView(out)
        }
        col.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
            setMargins(act.dp(12), 0, act.dp(12), act.dp(8))
        })

        // tombol cepat
        val quick = LinearLayout(act).apply { setPadding(act.dp(12), 0, act.dp(12), act.dp(6)) }
        quick.addView(chip("Up") { historyStep(-1) }, act.lp(WRAP, WRAP, r = 6))
        quick.addView(chip("Down") { historyStep(1) }, act.lp(WRAP, WRAP, r = 6))
        quick.addView(chip("^C") { proc?.destroy(); if (running) println("^C", Pal.warn) }, act.lp(WRAP, WRAP, r = 6))
        quick.addView(chip("ls") { setInput("ls -la") }, act.lp(WRAP, WRAP, r = 6))
        quick.addView(chip("build") { setInput("build") }, act.lp(WRAP, WRAP, r = 6))
        quick.addView(chip("help") { setInput("help") }, act.lp(WRAP, WRAP))
        col.addView(android.widget.HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            addView(quick)
        }, act.lp())

        input = EditText(act).apply {
            hint = "Type a command..."
            setHintTextColor(Pal.dim)
            setTextColor(Pal.text)
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setSingleLine()
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEND || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER)) { submit(); true } else false
            }
        }
        val bar = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = act.glass(26, Pal.field)
            setPadding(act.dp(16), act.dp(2), act.dp(6), act.dp(2))
        }
        bar.addView(input, act.lp(0, WRAP, weight = 1f))
        bar.addView(act.iconButton(R.drawable.ic_send, 22, Color.WHITE) { submit() }.apply {
            background = act.shape(Pal.primary, 22)
        })
        col.addView(bar, act.lp(l = 12, r = 12, b = 8))

        println("Senza Studio Terminal — ketik 'help' untuk daftar perintah.", Pal.sub)
        prompt()
        return col
    }

    private fun chip(text: String, onClick: () -> Unit): TextView = act.label(text, 13f, Pal.text, true).apply {
        setPadding(act.dp(14), act.dp(7), act.dp(14), act.dp(7))
        background = act.glassPress(16, Pal.glassStrong)
        isClickable = true
        setOnClickListener { onClick() }
    }

    override fun onShow() { act.observers.add(this) }
    override fun onHide() { act.observers.remove(this) }

    fun resetCwd() {
        cwd = act.project?.let { act.store.dir(it) } ?: act.store.root
        if (::out.isInitialized) { println("[proyek: ${act.project}]", Pal.sub); prompt() }
    }

    // =====================================================================

    private fun shortPath(f: File): String = f.path.replace(act.filesDir.path, "~")

    private fun append(sb: CharSequence) {
        out.append(sb)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun println(text: String, color: Int = 0xFFC9D6FF.toInt()) {
        val sb = SpannableStringBuilder(text + "\n")
        sb.setSpan(ForegroundColorSpan(color), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(sb)
    }

    private fun prompt() {
        val sb = SpannableStringBuilder()
        fun part(t: String, c: Int) {
            val s = sb.length
            sb.append(t)
            sb.setSpan(ForegroundColorSpan(c), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        part("$user@localhost", 0xFF4ADE80.toInt())
        part(":", Color.WHITE)
        part(shortPath(cwd), 0xFF60A5FA.toInt())
        part("$ ", Color.WHITE)
        append(sb)
    }

    private fun setInput(s: String) { input.setText(s); input.setSelection(s.length) }

    private fun historyStep(d: Int) {
        if (history.isEmpty()) return
        histPos = (histPos + d).coerceIn(0, history.size)
        if (histPos == history.size) setInput("") else setInput(history[histPos])
    }

    private fun submit() {
        val line = input.text.toString().trim()
        input.setText("")
        if (running) return act.toast("Perintah masih berjalan. Ketuk ^C untuk menghentikan.")
        println(line, Color.WHITE)
        if (line.isEmpty()) { prompt(); return }
        history.add(line)
        histPos = history.size
        exec(line)
    }

    private fun exec(line: String) {
        val parts = line.split(Regex("\\s+"))
        val cmd = parts[0]
        when {
            cmd == "clear" || cmd == "cls" -> { out.text = ""; prompt() }
            cmd == "help" -> { help(); prompt() }
            cmd == "pwd" -> { println(cwd.path); prompt() }
            cmd == "cd" -> { cd(parts.getOrNull(1)); prompt() }
            cmd == "exit" -> act.back()
            cmd == "build" || cmd == "assembleDebug" || cmd == "assembleRelease" ||
                ((cmd == "./gradlew" || cmd == "gradlew") && parts.size > 1 && parts[1].startsWith("assemble")) ->
                runBuild(
                    cmd.contains("Release") || parts.getOrNull(1)?.contains("Release") == true || parts.getOrNull(1) == "release",
                    if (parts.contains("shrink")) true else if (parts.contains("noshrink")) false else null
                )
            else -> shell(line)
        }
    }

    private fun help() {
        println(
            """
            Perintah khusus Senza Studio:
              build [release]   bangun APK proyek aktif
              build release shrink    release + R8 (perkecil & acak kode); noshrink = tanpa R8
              assembleDebug     sama dengan build (juga ./gradlew assembleDebug)
              assembleRelease   bangun versi release
              cd <folder>       pindah folder (mendukung .. dan ~)
              pwd, clear, exit
            Selain itu, perintah shell Android (ls, cat, cp, mv, rm, mkdir, echo, grep, ...)
            dijalankan langsung di folder saat ini. Ketuk ^C untuk menghentikan.
            """.trimIndent(), Pal.sub
        )
    }

    private fun cd(arg: String?) {
        val target = when {
            arg == null || arg == "~" -> act.project?.let { act.store.dir(it) } ?: act.store.root
            arg.startsWith("~/") -> File(act.filesDir, arg.removePrefix("~/"))
            arg.startsWith("/") -> File(arg)
            else -> File(cwd, arg)
        }
        val canon = try { target.canonicalFile } catch (e: Exception) { target }
        if (canon.isDirectory) cwd = canon else println("cd: $arg: tidak ada folder itu", Pal.err)
    }

    private fun runBuild(release: Boolean, shrink: Boolean? = null) {
        val p = act.project
        if (p == null) { println("Tidak ada proyek aktif.", Pal.err); prompt(); return }
        val base = act.store.config(p)
        val cfg = base.copy(release = release, shrink = shrink ?: base.shrink)
        buildFromTerm = true
        if (!act.startBuild(cfg)) { buildFromTerm = false; prompt() }
    }

    private fun shell(line: String) {
        running = true
        Thread {
            try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", line)
                    .directory(cwd)
                    .redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                pb.environment()["HOME"] = act.filesDir.path
                val p = pb.start()
                proc = p
                p.inputStream.bufferedReader().forEachLine { l -> act.runOnUiThread { println(l) } }
                val code = p.waitFor()
                if (code != 0) act.runOnUiThread { println("[exit $code]", Pal.warn) }
            } catch (e: Exception) {
                act.runOnUiThread { println("sh: ${e.message}", Pal.err) }
            } finally {
                proc = null
                running = false
                act.runOnUiThread { prompt() }
            }
        }.start()
    }

    private fun menu() {
        act.pickItem("Terminal", listOf("Bersihkan layar", "Salin semua output", "Ke folder proyek")) { i ->
            when (i) {
                0 -> { out.text = ""; prompt() }
                1 -> act.copyText("terminal", out.text.toString())
                2 -> { cd("~"); println(""); prompt() }
            }
        }
    }

    // ---------- BuildObserver (hanya mencetak jika build dimulai dari terminal) ----------

    override fun onLog(text: String) {
        if (!buildFromTerm) return
        val color = when {
            text.startsWith("BUILD SUCCESSFUL") -> Pal.ok
            text.startsWith("BUILD FAILED") -> Pal.err
            text.startsWith("> Task") -> 0xFF7DCFFF.toInt()
            else -> 0xFFC9D6FF.toInt()
        }
        val sb = SpannableStringBuilder(text)
        sb.setSpan(ForegroundColorSpan(color), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(sb)
    }

    override fun onStep(n: Int, total: Int, text: String) {}

    override fun onFinish(success: Boolean) {
        if (!buildFromTerm) return
        buildFromTerm = false
        val apk = act.lastApk
        if (success && apk != null) println("APK: ${shortPath(apk)}  (ketuk Build > Install untuk memasang)", Pal.ok)
        prompt()
    }
}
