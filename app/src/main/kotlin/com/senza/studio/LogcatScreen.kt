package com.senza.studio

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.ArrayDeque

/** Satu crash hasil penguraian logcat atau stack trace yang ditempel. */
class Crash(val header: String, val lines: List<String>)

/** Logcat langsung + penampil crash dengan lompat ke baris kode proyek. */
class LogcatScreen(act: MainActivity) : Screen(act) {

    override val showsNav = false

    private val buf = ArrayDeque<String>()
    @Volatile private var running = false
    @Volatile private var dirty = false
    private var proc: Process? = null

    private var minLevel = 0
    private var onlyApp = false
    private var crashMode = false
    private var pasted: List<Crash> = emptyList()
    private var cachedPid = ""
    private var pidAt = 0L

    private lateinit var filter: android.widget.EditText
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var crashBox: LinearLayout
    private lateinit var crashScroll: ScrollView
    private lateinit var permCard: LinearLayout
    private lateinit var appChip: TextView

    private val tick = object : Runnable {
        override fun run() {
            if (dirty) { dirty = false; render() }
            act.handler.postDelayed(this, 700)
        }
    }

    private val levels = listOf("V", "D", "I", "W", "E")

    override val view: View = buildView()

    private fun buildView(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            act.topBar(
                "Logcat", R.drawable.ic_back, { act.back() },
                R.drawable.ic_trash to { clearAll() },
                R.drawable.ic_file to { copyAll() }
            ), act.lp()
        )
        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(14), 0, act.dp(14), act.dp(6))
        }
        body.addView(act.segmented(listOf("Log", "Crash"), 0) { crashMode = it == 1; showMode() }, act.lp(b = 8))

        permCard = act.card(12, Pal.warnBg)
        body.addView(permCard, act.lp(b = 8))

        filter = act.textField("Filter teks / tag…").apply { onChanged { dirty = true } }
        body.addView(filter, act.lp(b = 8))

        val row = LinearLayout(act)
        row.addView(act.segmented(levels, 0) { minLevel = it; dirty = true }, act.lp(0, WRAP, r = 8, weight = 1f))
        appChip = act.label("Hanya app", 13f, Pal.sub, true).apply {
            setPadding(act.dp(12), act.dp(9), act.dp(12), act.dp(9))
            isClickable = true
            setOnClickListener { onlyApp = !onlyApp; paintChip(); dirty = true }
        }
        row.addView(appChip)
        body.addView(row, act.lp(b = 6))
        paintChip()
        col.addView(body, act.lp())

        logView = TextView(act).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
            setTextColor(Pal.text)
            setPadding(act.dp(8), act.dp(8), act.dp(8), act.dp(8))
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(act).apply {
            background = act.glass(10, 0x66050B2C, 0x22FFFFFF)
            addView(logView)
        }
        col.addView(logScroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { setMargins(act.dp(14), 0, act.dp(14), act.dp(10)) })

        crashBox = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(14), 0, act.dp(14), act.dp(24))
        }
        crashScroll = ScrollView(act).apply { addView(crashBox); visibility = View.GONE }
        col.addView(crashScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return col
    }

    private fun paintChip() {
        appChip.setTextColor(if (onlyApp) Color.WHITE else Pal.sub)
        appChip.background = if (onlyApp) act.glass(18, 0x552F6BFF, Pal.primary) else act.glassPress(18)
    }

    private fun showMode() {
        logScroll.visibility = if (crashMode) View.GONE else View.VISIBLE
        crashScroll.visibility = if (crashMode) View.VISIBLE else View.GONE
        filter.visibility = if (crashMode) View.GONE else View.VISIBLE
        dirty = true
        render()
    }

    private fun hasReadLogs() = act.checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED

    private fun fillPermCard() {
        permCard.removeAllViews()
        if (hasReadLogs()) { permCard.visibility = View.GONE; return }
        permCard.visibility = View.VISIBLE
        permCard.addView(act.label("Hanya log milik Senza Studio yang terbaca", 13f, Pal.warn, true))
        permCard.addView(
            act.label(
                "Android membatasi log aplikasi lain. Untuk membaca log aplikasi hasil build, beri izin sekali lewat adb " +
                    "(Termux + Wireless debugging, atau komputer):\n\nadb shell pm grant ${act.packageName} android.permission.READ_LOGS\n\n" +
                    "Tanpa izin itu, kamu tetap bisa menempel stack trace di tab Crash.",
                11f, Pal.text
            ).apply { setTextIsSelectable(true) },
            act.lp(t = 4)
        )
    }

    // =====================================================================

    override fun onShow() {
        fillPermCard()
        start()
        act.handler.removeCallbacks(tick)
        act.handler.postDelayed(tick, 300)
        showMode()
    }

    override fun onHide() {
        act.handler.removeCallbacks(tick)
        stop()
    }

    private fun start() {
        if (running) return
        running = true
        Thread {
            try {
                val pr = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "threadtime"))
                proc = pr
                pr.inputStream.bufferedReader().use { br ->
                    while (running) {
                        val l = br.readLine() ?: break
                        synchronized(buf) {
                            buf.addLast(l)
                            while (buf.size > 5000) buf.removeFirst()
                        }
                        dirty = true
                    }
                }
            } catch (e: Exception) {
                synchronized(buf) { buf.addLast("01-01 00:00:00.000  0  0 E Senza: logcat gagal dijalankan: ${e.message}") }
                dirty = true
            } finally {
                running = false
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stop() {
        running = false
        try { proc?.destroy() } catch (_: Throwable) {}
        proc = null
    }

    private fun clearAll() {
        synchronized(buf) { buf.clear() }
        pasted = emptyList()
        try { Runtime.getRuntime().exec(arrayOf("logcat", "-c")) } catch (_: Throwable) {}
        dirty = true
        render()
    }

    private fun copyAll() {
        val text = if (crashMode) {
            (pasted + crashes()).joinToString("\n\n") { it.header + "\n" + it.lines.joinToString("\n") }
        } else filtered().joinToString("\n")
        if (text.isBlank()) return act.toast("Belum ada isi")
        act.copyText("logcat", text.takeLast(200_000))
    }

    // =====================================================================

    private val lineRe = Regex("""^\S+\s+\S+\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.*)$""")

    private fun pidOfApp(): String {
        val now = System.currentTimeMillis()
        if (now - pidAt < 3000) return cachedPid
        pidAt = now
        val pkg = appPackage() ?: return ""
        cachedPid = try {
            val pr = Runtime.getRuntime().exec(arrayOf("pidof", "-s", pkg))
            val out = pr.inputStream.bufferedReader().readText().trim()
            pr.waitFor()
            out
        } catch (_: Throwable) { "" }
        return cachedPid
    }

    private fun appPackage(): String? {
        act.runPackage?.let { return it }
        val p = act.project ?: return null
        return act.store.packageOf(p).ifEmpty { null }
    }

    private fun filtered(): List<String> {
        val all = synchronized(buf) { ArrayList(buf) }
        val q = if (::filter.isInitialized) filter.text.toString().trim() else ""
        val pid = if (onlyApp) pidOfApp() else ""
        val pkg = if (onlyApp) appPackage() ?: "" else ""
        val out = ArrayList<String>()
        for (l in all) {
            val m = lineRe.find(l)
            val lv = m?.groupValues?.get(3) ?: "I"
            val idx = levels.indexOf(if (lv == "F" || lv == "A") "E" else lv)
            if (idx in 0 until minLevel) continue
            if (onlyApp) {
                val mp = m?.groupValues?.get(1) ?: ""
                if (!((pid.isNotEmpty() && mp == pid) || (pkg.isNotEmpty() && l.contains(pkg)))) continue
            }
            if (q.isNotEmpty() && !l.contains(q, ignoreCase = true)) continue
            out.add(l)
        }
        return out.takeLast(600)
    }

    private fun render() {
        if (crashMode) { renderCrashes(); return }
        val lines = filtered()
        val sb = SpannableStringBuilder()
        for (l in lines) {
            val lv = lineRe.find(l)?.groupValues?.get(3) ?: "I"
            val color = when (lv) {
                "E", "F", "A" -> Pal.err
                "W" -> Pal.warn
                "I" -> Pal.ok
                "D" -> 0xFF7DCFFF.toInt()
                else -> Pal.sub
            }
            val s = sb.length
            sb.append(l).append('\n')
            sb.setSpan(ForegroundColorSpan(color), s, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val atBottom = logScroll.getChildAt(0)?.let { it.bottom - (logScroll.height + logScroll.scrollY) < act.dp(80) } ?: true
        logView.text = if (sb.isEmpty()) "(belum ada log yang cocok)" else sb
        if (atBottom) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // =====================================================================
    //  CRASH
    // =====================================================================

    private val prefixRe = Regex("""^.*?AndroidRuntime:\s?""")

    private fun crashes(): List<Crash> {
        val all = synchronized(buf) { ArrayList(buf) }
        val out = ArrayList<Crash>()
        var i = 0
        while (i < all.size) {
            if (all[i].contains("FATAL EXCEPTION")) {
                val lines = ArrayList<String>()
                var j = i + 1
                while (j < all.size && all[j].contains("AndroidRuntime")) {
                    lines.add(all[j].replace(prefixRe, ""))
                    j++
                }
                val head = all[i].replace(prefixRe, "").trim()
                out.add(Crash(head, lines))
                i = j
            } else i++
        }
        return out.takeLast(10).reversed()
    }

    fun parsePasted(text: String): List<Crash> {
        val lines = text.lines().map { it.replace(prefixRe, "") }.filter { it.isNotBlank() }
        if (lines.none { it.trim().startsWith("at ") }) return emptyList()
        val start = lines.indexOfFirst { it.contains("Exception") || it.contains("Error") }.coerceAtLeast(0)
        return listOf(Crash("Stack trace (ditempel)", lines.drop(start)))
    }

    private fun renderCrashes() {
        crashBox.removeAllViews()
        crashBox.addView(
            act.glassButton("Tempel stack trace dari clipboard", R.drawable.ic_file, 13f) { pasteTrace() },
            act.lp(b = 8)
        )
        val list = pasted + crashes()
        if (list.isEmpty()) {
            crashBox.addView(
                act.label(
                    if (hasReadLogs()) "Belum ada crash terekam. Jalankan aplikasimu; kalau crash, detailnya muncul di sini."
                    else "Belum ada crash terbaca. Beri izin READ_LOGS (lihat tab Log) atau tempel stack trace.",
                    13f, Pal.sub
                ), act.lp(t = 8)
            )
            return
        }
        val p = act.project
        val dir = p?.let { act.store.dir(it) }
        val frameRe = Regex("""\(([\w$]+\.(?:java|kt)):(\d+)\)""")
        for (c in list) {
            val card = act.card(12, Pal.errBg)
            card.addView(act.label(c.header, 12f, Pal.err, true))
            val shown = c.lines.take(60)
            for (l in shown) {
                val t = l.trimEnd()
                val m = frameRe.find(t)
                val src = if (m != null && dir != null) {
                    val fname = m.groupValues[1]
                    CodeAssist.sourceFiles(dir).firstOrNull { it.name == fname }?.relativeTo(dir)?.path
                } else null
                val isCause = !t.trimStart().startsWith("at ")
                val tv = act.mono(t.replace("\t", "    "), 11f, if (src != null) Color.WHITE else if (isCause) Pal.err else Pal.sub).apply {
                    ellipsize = TextUtils.TruncateAt.END
                    setSingleLine()
                    if (src != null) {
                        setPadding(act.dp(6), act.dp(5), act.dp(6), act.dp(5))
                        background = act.glassPress(8, 0x332F6BFF)
                        isClickable = true
                        val line = m!!.groupValues[2].toInt()
                        setOnClickListener { act.openFile(src, line) }
                    }
                }
                card.addView(tv, act.lp(t = 2))
            }
            if (c.lines.size > shown.size) card.addView(act.label("… ${c.lines.size - shown.size} baris lagi (Salin untuk semuanya)", 11f, Pal.dim), act.lp(t = 4))
            crashBox.addView(card, act.lp(b = 8))
        }
        crashBox.addView(
            act.label("Baris putih bisa diketuk: langsung membuka file dan baris di proyekmu.", 11f, Pal.dim),
            act.lp(t = 4)
        )
    }

    private fun pasteTrace() {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(act)?.toString() ?: ""
        val parsed = parsePasted(text)
        if (parsed.isEmpty()) return act.toast("Clipboard tidak berisi stack trace (butuh baris 'at …')")
        pasted = parsed
        renderCrashes()
    }
}
