package com.senza.studio

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader

/** Satu simbol di proyek: kelas, method, field, atau resource. kind: class, method, field, atau R.<tipe>. */
class Sym(val name: String, val kind: String, val rel: String, val line: Int, val params: Boolean = false)

/** Indeks proyek: simbol kode dan nama resource. */
class ProjectIndex(val syms: List<Sym>, val res: Map<String, Set<String>>, val builtAt: Long)

/** Satu saran autocomplete. Teks [insert] mengganti awalan yang sudah diketik; kursor mundur sejauh [back]. */
class Cand(val label: String, val insert: String, val kind: String, val back: Int = 0, val imp: String? = null)

class Completion(val prefixLen: Int, val items: List<Cand>)

/** Satu temuan diagnostik. start/len = posisi di teks, line = nomor baris (mulai 1). */
class Diag(val start: Int, val len: Int, val line: Int, val msg: String, val error: Boolean)

object CodeAssist {

    // =====================================================================
    //  INDEKS
    // =====================================================================

    @Volatile private var cache: ProjectIndex? = null
    @Volatile private var cacheDir: String? = null

    fun invalidate() { cache = null }

    @Synchronized
    fun index(dir: File, force: Boolean = false): ProjectIndex {
        val now = System.currentTimeMillis()
        val c = cache
        if (!force && c != null && cacheDir == dir.path && now - c.builtAt < 4000) return c
        val idx = build(dir)
        cache = idx
        cacheDir = dir.path
        return idx
    }

    private val classRe = Regex("""\b(class|interface|enum|object|record)\s+([A-Za-z_]\w*)""")
    private val javaMethodRe = Regex("""^\s*(?:[\w@<>\[\],.?]+\s+)+([a-z_]\w*)\s*\(([^;{}]*)\)\s*(?:throws\s+[\w.,\s]+)?\{\s*$""")
    private val javaAbstractRe = Regex("""^\s*(?:public|private|protected|static|abstract|default)\s+(?:[\w@<>\[\],.?]+\s+)+([a-z_]\w*)\s*\(([^;{}]*)\)\s*;\s*$""")
    private val javaFieldRe = Regex("""^\s*(?:(?:public|private|protected|static|final|volatile|transient)\s+)+[\w<>\[\],.?]+\s+([A-Za-z_]\w*)\s*(?:=|;)""")
    private val ktFunRe = Regex("""\bfun\s+(?:<[^>]+>\s*)?(?:[\w.<>?]+\.)?([A-Za-z_]\w*)\s*\(([^)]*)""")
    private val ktValRe = Regex("""\b(?:val|var)\s+([A-Za-z_]\w*)""")
    private val notMethod = setOf("if", "for", "while", "switch", "catch", "else", "synchronized", "return", "new", "try", "throw", "super", "this")

    private val valuesRe = Regex("""<(string|color|dimen|bool|integer|style|string-array|integer-array|array|plurals|attr|declare-styleable|item)\s+[^>]*?name\s*=\s*"([^"]+)"([^>]*)>""")
    private val idRe = Regex("""@\+?id/([A-Za-z_][\w.]*)""")

    fun sourceRoots(dir: File): List<File> = listOf("src", "java").map { File(dir, it) }.filter { it.isDirectory }

    fun sourceFiles(dir: File): List<File> =
        sourceRoots(dir).flatMap { r -> r.walkTopDown().filter { it.isFile && (it.extension == "java" || it.extension == "kt") }.toList() }

    private fun build(dir: File): ProjectIndex {
        val syms = ArrayList<Sym>()
        val res = HashMap<String, MutableSet<String>>()
        fun addRes(type: String, name: String, rel: String, line: Int) {
            val set = res.getOrPut(type) { HashSet() }
            set.add(name)
            set.add(name.replace('.', '_'))
            if (line > 0) syms.add(Sym(name.replace('.', '_'), "R.$type", rel, line))
        }

        for (f in sourceFiles(dir)) {
            if (f.length() > 400_000) continue
            val rel = f.relativeTo(dir).path
            val kt = f.extension == "kt"
            val lines = try { f.readLines() } catch (_: Exception) { continue }
            lines.forEachIndexed { i, ln ->
                val t = ln.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import ") || t.startsWith("package ")) return@forEachIndexed
                classRe.find(ln)?.let { m -> syms.add(Sym(m.groupValues[2], "class", rel, i + 1)) }
                if (kt) {
                    ktFunRe.find(ln)?.let { m -> syms.add(Sym(m.groupValues[1], "method", rel, i + 1, m.groupValues[2].isNotBlank())) }
                    ktValRe.find(ln)?.let { m -> syms.add(Sym(m.groupValues[1], "field", rel, i + 1)) }
                } else {
                    val m = javaMethodRe.find(ln) ?: javaAbstractRe.find(ln)
                    if (m != null && m.groupValues[1] !in notMethod) {
                        syms.add(Sym(m.groupValues[1], "method", rel, i + 1, m.groupValues[2].isNotBlank()))
                    } else {
                        javaFieldRe.find(ln)?.let { fm -> syms.add(Sym(fm.groupValues[1], "field", rel, i + 1)) }
                    }
                }
            }
        }

        val resDir = File(dir, "res")
        if (resDir.isDirectory) {
            resDir.listFiles { f -> f.isDirectory }?.forEach { d ->
                val kind = d.name.substringBefore('-')
                val files = d.listFiles { f -> f.isFile } ?: return@forEach
                for (f in files) {
                    val base = f.name.substringBefore('.')
                    if (kind == "values") {
                        if (f.extension != "xml" || f.length() > 400_000) continue
                        val rel = f.relativeTo(dir).path
                        val text = try { f.readText() } catch (_: Exception) { continue }
                        valuesRe.findAll(text).forEach { m ->
                            val tag = m.groupValues[1]
                            val name = m.groupValues[2]
                            val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                            when (tag) {
                                "string" -> addRes("string", name, rel, line)
                                "color" -> addRes("color", name, rel, line)
                                "dimen" -> addRes("dimen", name, rel, line)
                                "bool" -> addRes("bool", name, rel, line)
                                "integer" -> addRes("integer", name, rel, line)
                                "style" -> addRes("style", name, rel, line)
                                "string-array", "integer-array", "array" -> addRes("array", name, rel, line)
                                "plurals" -> addRes("plurals", name, rel, line)
                                "item" -> if (m.groupValues[3].contains("\"id\"")) addRes("id", name, rel, line)
                                else -> {}
                            }
                        }
                    } else {
                        val type = when (kind) {
                            "layout", "drawable", "mipmap", "raw", "xml", "menu", "anim", "font", "color", "animator", "navigation" -> kind
                            else -> null
                        }
                        if (type != null) addRes(type, base, f.relativeTo(dir).path, 1)
                    }
                }
            }
            resDir.walkTopDown().filter { it.isFile && it.extension == "xml" && it.length() < 400_000 }.forEach { f ->
                val text = try { f.readText() } catch (_: Exception) { return@forEach }
                val rel = f.relativeTo(dir).path
                idRe.findAll(text).forEach { m ->
                    val name = m.groupValues[1]
                    val set = res.getOrPut("id") { HashSet() }
                    val isNew = m.value.startsWith("@+")
                    set.add(name)
                    set.add(name.replace('.', '_'))
                    if (isNew) {
                        val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                        syms.add(Sym(name.replace('.', '_'), "R.id", rel, line))
                    }
                }
            }
        }
        return ProjectIndex(syms, res, System.currentTimeMillis())
    }

    /** Cari file sumber dari nama kelas (sederhana atau lengkap dengan package). */
    fun fileForClass(dir: File, cls: String): String? {
        val simple = cls.substringAfterLast('.').substringBefore('$')
        val pkgPath = if (cls.contains('.')) cls.substringBeforeLast('.').replace('.', '/') else null
        val hits = sourceFiles(dir).filter { it.nameWithoutExtension == simple }
        val pick = hits.firstOrNull { pkgPath == null || it.parentFile?.path?.replace('\\', '/')?.endsWith(pkgPath) == true } ?: hits.firstOrNull()
        return pick?.relativeTo(dir)?.path
    }

    // =====================================================================
    //  DATA STATIS
    // =====================================================================

    private val ANDROID_CLASSES = mapOf(
        "Activity" to "android.app.Activity", "Bundle" to "android.os.Bundle", "Context" to "android.content.Context",
        "Intent" to "android.content.Intent", "View" to "android.view.View", "ViewGroup" to "android.view.ViewGroup",
        "LayoutInflater" to "android.view.LayoutInflater", "Gravity" to "android.view.Gravity", "MotionEvent" to "android.view.MotionEvent",
        "TextView" to "android.widget.TextView", "Button" to "android.widget.Button", "EditText" to "android.widget.EditText",
        "ImageView" to "android.widget.ImageView", "LinearLayout" to "android.widget.LinearLayout", "FrameLayout" to "android.widget.FrameLayout",
        "RelativeLayout" to "android.widget.RelativeLayout", "ScrollView" to "android.widget.ScrollView", "ListView" to "android.widget.ListView",
        "ProgressBar" to "android.widget.ProgressBar", "CheckBox" to "android.widget.CheckBox", "Switch" to "android.widget.Switch",
        "Spinner" to "android.widget.Spinner", "SeekBar" to "android.widget.SeekBar", "Toast" to "android.widget.Toast",
        "ArrayAdapter" to "android.widget.ArrayAdapter", "BaseAdapter" to "android.widget.BaseAdapter", "AdapterView" to "android.widget.AdapterView",
        "RadioButton" to "android.widget.RadioButton", "RadioGroup" to "android.widget.RadioGroup", "Toolbar" to "android.widget.Toolbar",
        "Handler" to "android.os.Handler", "Looper" to "android.os.Looper", "Build" to "android.os.Build", "Environment" to "android.os.Environment",
        "Log" to "android.util.Log", "TypedValue" to "android.util.TypedValue", "Color" to "android.graphics.Color", "Bitmap" to "android.graphics.Bitmap",
        "BitmapFactory" to "android.graphics.BitmapFactory", "Canvas" to "android.graphics.Canvas", "Paint" to "android.graphics.Paint",
        "Typeface" to "android.graphics.Typeface", "Uri" to "android.net.Uri", "TextWatcher" to "android.text.TextWatcher",
        "Editable" to "android.text.Editable", "SharedPreferences" to "android.content.SharedPreferences",
        "AlertDialog" to "android.app.AlertDialog", "Fragment" to "android.app.Fragment", "Service" to "android.app.Service",
        "BroadcastReceiver" to "android.content.BroadcastReceiver", "IntentFilter" to "android.content.IntentFilter",
        "Manifest" to "android.Manifest", "PackageManager" to "android.content.pm.PackageManager",
        "ArrayList" to "java.util.ArrayList", "List" to "java.util.List", "Map" to "java.util.Map", "HashMap" to "java.util.HashMap",
        "Set" to "java.util.Set", "HashSet" to "java.util.HashSet", "Arrays" to "java.util.Arrays", "Collections" to "java.util.Collections",
        "Locale" to "java.util.Locale", "Random" to "java.util.Random", "Date" to "java.util.Date", "File" to "java.io.File",
        "IOException" to "java.io.IOException", "InputStream" to "java.io.InputStream", "OutputStream" to "java.io.OutputStream",
        "Runnable" to "java.lang.Runnable", "Thread" to "java.lang.Thread"
    )

    private val ANDROID_NO_IMPORT = setOf("Runnable", "Thread")

    private val SNIPPETS = listOf(
        "sout" to "System.out.println(|);",
        "log" to "Log.d(\"TAG\", \"|\");",
        "toast" to "Toast.makeText(this, \"|\", Toast.LENGTH_SHORT).show();",
        "fori" to "for (int i = 0; i < |; i++) {\n    \n}",
        "foreach" to "for (Object item : |) {\n    \n}",
        "iff" to "if (|) {\n    \n}",
        "tryc" to "try {\n    |\n} catch (Exception e) {\n    e.printStackTrace();\n}",
        "onclick" to "view.setOnClickListener(new View.OnClickListener() {\n    @Override\n    public void onClick(View v) {\n        |\n    }\n});",
        "oncreate" to "@Override\nprotected void onCreate(Bundle savedInstanceState) {\n    super.onCreate(savedInstanceState);\n    |\n}",
        "findview" to "(TextView) findViewById(R.id.|);",
        "startact" to "startActivity(new Intent(this, |.class));"
    )

    private val KT_SNIPPETS = listOf(
        "log" to "Log.d(\"TAG\", \"|\")",
        "toast" to "Toast.makeText(this, \"|\", Toast.LENGTH_SHORT).show()",
        "fori" to "for (i in 0 until |) {\n    \n}",
        "iff" to "if (|) {\n    \n}",
        "tryc" to "try {\n    |\n} catch (e: Exception) {\n    e.printStackTrace()\n}",
        "oncreate" to "override fun onCreate(savedInstanceState: Bundle?) {\n    super.onCreate(savedInstanceState)\n    |\n}",
        "startact" to "startActivity(Intent(this, |::class.java))"
    )

    private val GENERIC_MEMBERS = listOf(
        "setText(|)", "getText()", "setOnClickListener(|)", "findViewById(R.id.|)", "setVisibility(View.|)", "setContentView(R.layout.|)",
        "getSystemService(|)", "startActivity(|)", "finish()", "getIntent()", "putExtra(\"|\", )", "getStringExtra(\"|\")",
        "getString(R.string.|)", "toString()", "equals(|)", "length()", "size()", "add(|)", "get(|)", "isEmpty()", "contains(|)",
        "setTextColor(|)", "setBackgroundColor(|)", "setEnabled(|)", "getId()", "setTag(|)", "post(|)", "runOnUiThread(|)",
        "getSharedPreferences(\"|\", MODE_PRIVATE)", "edit()", "apply()", "commit()", "show()", "dismiss()", "trim()", "split(\"|\")",
        "substring(|)", "indexOf(|)", "startsWith(|)", "endsWith(|)", "replace(|)", "toLowerCase()", "toUpperCase()", "parseInt(|)"
    )

    private val STATIC_MEMBERS = mapOf(
        "Toast" to listOf("makeText(this, \"|\", Toast.LENGTH_SHORT)", "LENGTH_SHORT", "LENGTH_LONG"),
        "Log" to listOf("d(\"TAG\", \"|\")", "e(\"TAG\", \"|\")", "i(\"TAG\", \"|\")", "w(\"TAG\", \"|\")", "v(\"TAG\", \"|\")"),
        "System" to listOf("out.println(|)", "currentTimeMillis()", "nanoTime()", "getProperty(\"|\")", "exit(0)"),
        "String" to listOf("valueOf(|)", "format(\"|\")", "join(\"|\", )"),
        "Math" to listOf("max(|)", "min(|)", "abs(|)", "round(|)", "random()", "sqrt(|)", "pow(|)", "floor(|)", "ceil(|)", "PI"),
        "Integer" to listOf("parseInt(|)", "valueOf(|)", "toString(|)", "MAX_VALUE", "MIN_VALUE"),
        "Color" to listOf("parseColor(\"#|\")", "rgb(|)", "argb(|)", "WHITE", "BLACK", "RED", "GREEN", "BLUE", "TRANSPARENT"),
        "View" to listOf("VISIBLE", "GONE", "INVISIBLE", "OnClickListener"),
        "Gravity" to listOf("CENTER", "CENTER_HORIZONTAL", "CENTER_VERTICAL", "START", "END", "TOP", "BOTTOM"),
        "Intent" to listOf("ACTION_VIEW", "ACTION_SEND", "ACTION_MAIN", "FLAG_ACTIVITY_NEW_TASK", "EXTRA_TEXT"),
        "Arrays" to listOf("asList(|)", "sort(|)", "toString(|)", "fill(|)"),
        "Collections" to listOf("sort(|)", "reverse(|)", "emptyList()", "unmodifiableList(|)"),
        "Build" to listOf("VERSION.SDK_INT", "VERSION_CODES.O", "MODEL", "MANUFACTURER"),
        "Context" to listOf("MODE_PRIVATE", "LAYOUT_INFLATER_SERVICE", "CLIPBOARD_SERVICE"),
        "Environment" to listOf("getExternalStorageDirectory()", "DIRECTORY_DOWNLOADS"),
        "TypedValue" to listOf("COMPLEX_UNIT_SP", "COMPLEX_UNIT_DIP", "COMPLEX_UNIT_PX")
    )

    private val R_TYPES = listOf(
        "id", "string", "color", "layout", "drawable", "dimen", "mipmap", "style", "array", "raw", "xml", "menu", "anim", "bool", "integer", "font"
    )

    private val XML_WIDGETS = listOf(
        "LinearLayout", "RelativeLayout", "FrameLayout", "ScrollView", "HorizontalScrollView", "TextView", "EditText", "Button", "ImageView",
        "ImageButton", "CheckBox", "RadioButton", "RadioGroup", "Switch", "ProgressBar", "SeekBar", "Spinner", "ListView", "View", "Space",
        "RatingBar", "Toolbar", "TableLayout", "TableRow", "GridLayout", "WebView", "include", "merge",
        "androidx.recyclerview.widget.RecyclerView", "androidx.constraintlayout.widget.ConstraintLayout", "androidx.cardview.widget.CardView",
        "androidx.core.widget.NestedScrollView", "com.google.android.material.button.MaterialButton",
        "com.google.android.material.textfield.TextInputLayout", "com.google.android.material.textfield.TextInputEditText",
        "com.google.android.material.floatingactionbutton.FloatingActionButton", "com.google.android.material.appbar.MaterialToolbar",
        "com.google.android.material.card.MaterialCardView"
    )

    private val XML_ATTRS = listOf(
        "android:id", "android:layout_width", "android:layout_height", "android:layout_weight", "android:layout_gravity",
        "android:layout_margin", "android:layout_marginTop", "android:layout_marginBottom", "android:layout_marginStart", "android:layout_marginEnd",
        "android:layout_marginLeft", "android:layout_marginRight", "android:padding", "android:paddingTop", "android:paddingBottom",
        "android:paddingStart", "android:paddingEnd", "android:paddingLeft", "android:paddingRight", "android:orientation", "android:gravity",
        "android:text", "android:textSize", "android:textColor", "android:textStyle", "android:hint", "android:textColorHint", "android:inputType",
        "android:background", "android:visibility", "android:src", "android:scaleType", "android:contentDescription", "android:onClick",
        "android:maxLines", "android:lines", "android:singleLine", "android:ellipsize", "android:enabled", "android:clickable", "android:alpha",
        "android:minHeight", "android:minWidth", "android:weightSum", "android:elevation", "android:fontFamily", "android:checked",
        "android:layout_below", "android:layout_above", "android:layout_toRightOf", "android:layout_toLeftOf", "android:layout_alignParentTop",
        "android:layout_alignParentBottom", "android:layout_alignParentStart", "android:layout_alignParentEnd", "android:layout_centerInParent",
        "android:layout_centerHorizontal", "android:layout_centerVertical", "android:progress", "android:max", "android:divider",
        "android:scrollbars", "android:fillViewport", "android:clipToPadding", "android:foreground",
        "app:layout_constraintTop_toTopOf", "app:layout_constraintTop_toBottomOf", "app:layout_constraintBottom_toBottomOf",
        "app:layout_constraintBottom_toTopOf", "app:layout_constraintStart_toStartOf", "app:layout_constraintStart_toEndOf",
        "app:layout_constraintEnd_toEndOf", "app:layout_constraintEnd_toStartOf", "app:layout_constraintHorizontal_bias",
        "app:layout_constraintVertical_bias", "app:cardCornerRadius", "app:cardElevation", "app:layoutManager", "app:title",
        "tools:text", "tools:listitem", "tools:context", "xmlns:android=\"http://schemas.android.com/apk/res/android\"",
        "xmlns:app=\"http://schemas.android.com/apk/res-auto\"", "xmlns:tools=\"http://schemas.android.com/tools\""
    )

    private val XML_VALUES = mapOf(
        "layout_width" to listOf("match_parent", "wrap_content", "0dp"),
        "layout_height" to listOf("match_parent", "wrap_content", "0dp"),
        "orientation" to listOf("vertical", "horizontal"),
        "visibility" to listOf("visible", "gone", "invisible"),
        "gravity" to listOf("center", "center_horizontal", "center_vertical", "start", "end", "top", "bottom", "left", "right"),
        "layout_gravity" to listOf("center", "center_horizontal", "center_vertical", "start", "end", "top", "bottom"),
        "textStyle" to listOf("bold", "italic", "normal", "bold|italic"),
        "scaleType" to listOf("centerCrop", "centerInside", "fitCenter", "fitXY", "center"),
        "inputType" to listOf("text", "number", "numberDecimal", "textPassword", "textEmailAddress", "phone", "textMultiLine"),
        "ellipsize" to listOf("end", "start", "middle", "marquee"),
        "singleLine" to listOf("true", "false"), "enabled" to listOf("true", "false"), "clickable" to listOf("true", "false"),
        "checked" to listOf("true", "false"),
        "layout_constraintTop_toTopOf" to listOf("parent"), "layout_constraintBottom_toBottomOf" to listOf("parent"),
        "layout_constraintStart_toStartOf" to listOf("parent"), "layout_constraintEnd_toEndOf" to listOf("parent"),
        "layout_alignParentTop" to listOf("true"), "layout_alignParentBottom" to listOf("true"), "layout_centerInParent" to listOf("true"),
        "layoutManager" to listOf("androidx.recyclerview.widget.LinearLayoutManager", "androidx.recyclerview.widget.GridLayoutManager")
    )

    private val RES_PREFIXES = listOf("string", "color", "dimen", "drawable", "mipmap", "layout", "id", "style", "bool", "integer", "array", "menu", "anim", "raw", "xml", "font")

    // =====================================================================
    //  AUTOCOMPLETE
    // =====================================================================

    fun complete(dir: File, mode: Int, text: String, caretIn: Int, fileRel: String?): Completion? {
        if (text.length > 600_000) return null
        val caret = caretIn.coerceIn(0, text.length)
        return try {
            when (mode) {
                Highlighter.XML -> completeXml(dir, text, caret)
                Highlighter.JAVA, Highlighter.KOTLIN -> completeCode(dir, mode, text, caret)
                else -> null
            }
        } catch (_: Throwable) { null }
    }

    private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun lineIndentAt(text: String, caret: Int): String {
        var s = caret
        while (s > 0 && text[s - 1] != '\n') s--
        var e = s
        while (e < text.length && (text[e] == ' ' || text[e] == '\t')) e++
        return text.substring(s, e)
    }

    private fun snip(label: String, template: String, kind: String, indent: String, imp: String? = null): Cand {
        val raw = template.replace("|", "")
        val body = raw.split("\n").mapIndexed { i, l -> if (i == 0) l else indent + l }.joinToString("\n")
        val marker = template.indexOf('|')
        val back = if (marker < 0) 0 else {
            val tail = template.substring(marker + 1)
            tail.length + tail.count { it == '\n' } * indent.length
        }
        return Cand(label, body, kind, back, imp)
    }

    private fun completeCode(dir: File, mode: Int, text: String, caret: Int): Completion? {
        var ws = caret
        while (ws > 0 && isIdent(text[ws - 1])) ws--
        val prefix = text.substring(ws, caret)
        val kotlin = mode == Highlighter.KOTLIN
        val indent = lineIndentAt(text, caret)
        val idx = index(dir)

        // setelah titik: R.xxx, anggota statis, atau anggota umum
        if (ws > 0 && text[ws - 1] == '.') {
            var rs = ws - 1
            while (rs > 0 && isIdent(text[rs - 1])) rs--
            val receiver = text.substring(rs, ws - 1)
            if (receiver == "R" && (rs == 0 || (text[rs - 1] != '.' && !isIdent(text[rs - 1])))) {
                val items = R_TYPES.filter { it.startsWith(prefix, true) }.map { Cand(it, "$it.", "R") }
                return if (items.isEmpty()) null else Completion(prefix.length, items)
            }
            if (rs >= 2 && text[rs - 1] == '.' && text.getOrNull(rs - 2) == 'R' && (rs - 2 == 0 || !isIdent(text[rs - 3]))) {
                val names = idx.res[receiver] ?: emptySet()
                val items = names.filter { !it.contains('.') && it.startsWith(prefix, true) && it != prefix }.sorted().take(40).map { Cand(it, it, "R.$receiver") }
                return if (items.isEmpty()) null else Completion(prefix.length, items)
            }
            val items = ArrayList<Cand>()
            STATIC_MEMBERS[receiver]?.forEach { t ->
                val name = t.substringBefore('(').substringBefore('|')
                if (name.startsWith(prefix, true)) {
                    val label = if (t.contains('(')) "$name(…)" else name
                    items.add(snip(label, t, "member", indent))
                }
            }
            val seen = HashSet<String>()
            idx.syms.filter { (it.kind == "method" || it.kind == "field") && it.name.startsWith(prefix, true) }.forEach { s ->
                if (seen.add(s.name)) {
                    items.add(if (s.kind == "method") Cand(s.name + "()", s.name + "()", "method", if (s.params) 1 else 0) else Cand(s.name, s.name, "field"))
                }
            }
            GENERIC_MEMBERS.forEach { t ->
                val name = t.substringBefore('(')
                if (name.startsWith(prefix, true) && seen.add(name)) items.add(snip(name + "()", t, "method", indent))
            }
            val out = items.filter { it.label != prefix }.take(14)
            return if (out.isEmpty()) null else Completion(prefix.length, out)
        }

        if (prefix.length < 2) return null
        if (prefix[0].isDigit()) return null
        val out = ArrayList<Cand>()
        val seen = HashSet<String>()

        val snippets = if (kotlin) KT_SNIPPETS else SNIPPETS
        snippets.forEach { (k, v) -> if (k.startsWith(prefix, true) && prefix != k) out.add(snip(k, v, "snippet", indent)) }

        idx.syms.filter { it.kind == "class" && it.name.startsWith(prefix, true) && it.name != prefix }.forEach { s ->
            if (seen.add(s.name)) out.add(Cand(s.name, s.name, "class"))
        }
        idx.syms.filter { (it.kind == "method" || it.kind == "field") && it.name.startsWith(prefix, true) && it.name != prefix }.forEach { s ->
            if (seen.add(s.name)) out.add(if (s.kind == "method") Cand(s.name + "()", s.name + "()", "method", if (s.params) 1 else 0) else Cand(s.name, s.name, "field"))
        }

        // kata yang sudah ada di file ini
        val wordRe = Regex("[A-Za-z_]\\w{2,}")
        val words = HashMap<String, Int>()
        for (m in wordRe.findAll(text)) {
            if (m.range.first == ws) continue
            val w = m.value
            if (w.startsWith(prefix, true) && w != prefix) words[w] = (words[w] ?: 0) + 1
        }
        words.entries.sortedByDescending { it.value }.take(8).forEach { (w, _) -> if (seen.add(w)) out.add(Cand(w, w, "kata")) }

        val kws = if (kotlin) Highlighter.KOTLIN_KEYWORDS else Highlighter.JAVA_KEYWORDS
        kws.filter { it.startsWith(prefix, true) && it != prefix }.sorted().forEach { k -> if (seen.add(k)) out.add(Cand(k, k, "kunci")) }

        ANDROID_CLASSES.filter { it.key.startsWith(prefix, true) && it.key != prefix }.keys.sorted().forEach { k ->
            if (seen.add(k)) {
                val fq = ANDROID_CLASSES[k]!!
                val need = k !in ANDROID_NO_IMPORT && !hasImport(text, fq) && !fq.startsWith("java.lang.")
                out.add(Cand(k, k, "android", 0, if (need) fq else null))
            }
        }
        val finalList = out.take(16)
        return if (finalList.isEmpty()) null else Completion(prefix.length, finalList)
    }

    private fun hasImport(text: String, fq: String): Boolean {
        if (Regex("(?m)^\\s*import\\s+" + Regex.escape(fq) + "\\s*;?\\s*$").containsMatchIn(text)) return true
        val pkg = fq.substringBeforeLast('.')
        return Regex("(?m)^\\s*import\\s+" + Regex.escape(pkg) + "\\.\\*\\s*;?\\s*$").containsMatchIn(text)
    }

    /** Posisi dan baris `import` yang harus disisipkan. Mengembalikan null jika sudah ada. */
    fun importEdit(text: String, fq: String, kotlin: Boolean): Pair<Int, String>? {
        if (hasImport(text, fq)) return null
        val line = "import $fq" + (if (kotlin) "" else ";") + "\n"
        val imports = Regex("(?m)^import\\s+[^\\n]*\\n?").findAll(text).toList()
        if (imports.isNotEmpty()) {
            val last = imports.last()
            var end = last.range.last + 1
            if (end > text.length) end = text.length
            return if (text.getOrNull(end - 1) == '\n') end to line else end to ("\n" + line.trimEnd('\n'))
        }
        val pk = Regex("(?m)^package\\s+[^\\n]*\\n?").find(text)
        if (pk != null) {
            val end = pk.range.last + 1
            return end to ("\n" + line)
        }
        return 0 to (line + "\n")
    }

    // ---------- XML ----------

    private fun openTags(text: String, upTo: Int): List<String> {
        val head = text.substring(0, upTo)
        val noComments = Regex("<!--[\\s\\S]*?-->").replace(head) { " ".repeat(it.value.length) }
        val stack = ArrayList<String>()
        Regex("<(/?)([\\w.:-]+)((?:\"[^\"]*\"|'[^']*'|[^<>\"'])*)>").findAll(noComments).forEach { m ->
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2]
            val selfClosed = m.groupValues[3].trimEnd().endsWith("/")
            if (closing) {
                val k = stack.lastIndexOf(name)
                if (k >= 0) while (stack.size > k) stack.removeAt(stack.size - 1)
            } else if (!selfClosed) stack.add(name)
        }
        return stack
    }

    private fun completeXml(dir: File, text: String, caret: Int): Completion? {
        val lt = text.lastIndexOf('<', caret - 1)
        val gt = text.lastIndexOf('>', caret - 1)
        if (lt < 0 || lt < gt) return null
        val seg = text.substring(lt, caret)
        if (seg.startsWith("<!--") || seg.startsWith("<?")) return null
        val quotes = seg.count { it == '"' }
        val idx = index(dir)

        if (quotes % 2 == 1) {
            val q = seg.lastIndexOf('"')
            val valuePrefix = seg.substring(q + 1)
            val attr = Regex("([\\w:.-]+)\\s*=\\s*\"[^\"]*$").find(seg)?.groupValues?.get(1) ?: return null
            val attrName = attr.substringAfter(':')
            if (valuePrefix.startsWith("@")) {
                if (valuePrefix.contains('/')) {
                    val type = valuePrefix.substring(1, valuePrefix.indexOf('/')).removePrefix("+")
                    if (valuePrefix.startsWith("@+")) return null
                    val p = valuePrefix.substringAfter('/')
                    val names = idx.res[type] ?: return null
                    val items = names.filter { it.startsWith(p, true) && it != p }.sorted().take(30).map { Cand(it, it, "@$type") }
                    return if (items.isEmpty()) null else Completion(p.length, items)
                }
                val items = RES_PREFIXES.filter { ("@$it/").startsWith(valuePrefix, true) }.map { Cand("@$it/", "@$it/", "resource") }
                return if (items.isEmpty()) null else Completion(valuePrefix.length, items)
            }
            val opts = XML_VALUES[attrName] ?: return null
            val items = opts.filter { it.startsWith(valuePrefix, true) && it != valuePrefix }.map { Cand(it, it, "nilai") }
            return if (items.isEmpty()) null else Completion(valuePrefix.length, items)
        }

        // sedang mengetik nama tag?
        val afterLt = seg.substring(1)
        if (!afterLt.any { it.isWhitespace() }) {
            if (afterLt.startsWith("/")) {
                val tags = openTags(text, lt)
                val top = tags.lastOrNull() ?: return null
                val p = afterLt.substring(1)
                if (!top.startsWith(p, true)) return null
                return Completion(p.length, listOf(Cand("$top>", "$top>", "tutup")))
            }
            val items = XML_WIDGETS.filter { it.substringAfterLast('.').startsWith(afterLt, true) || it.startsWith(afterLt, true) }
                .map { Cand(it.substringAfterLast('.'), it, "widget") }.take(16)
            val custom = idx.syms.filter { it.kind == "class" && it.name.startsWith(afterLt, true) && afterLt.isNotEmpty() }
                .map { Cand(it.name, it.name, "kelas") }.take(4)
            val all = (items + custom).filter { it.insert != afterLt }
            return if (all.isEmpty()) null else Completion(afterLt.length, all)
        }

        // nama atribut
        var ps = caret
        while (ps > lt && (text[ps - 1].isLetterOrDigit() || text[ps - 1] == ':' || text[ps - 1] == '_' || text[ps - 1] == '.' || text[ps - 1] == '-')) ps--
        val prefix = text.substring(ps, caret)
        if (prefix.isEmpty()) return null
        val items = XML_ATTRS.filter { it.startsWith(prefix, true) || (!prefix.contains(':') && it.substringAfter(':').startsWith(prefix, true)) }
            .filter { it != prefix }
            .map { a ->
                if (a.startsWith("xmlns")) Cand(a.substringBefore('='), a, "ns")
                else Cand(a, "$a=\"\"", "atribut", 1)
            }.take(16)
        return if (items.isEmpty()) null else Completion(prefix.length, items)
    }

    // =====================================================================
    //  DIAGNOSTIK
    // =====================================================================

    private fun starts(text: String): IntArray {
        val l = ArrayList<Int>()
        l.add(0)
        for (i in text.indices) if (text[i] == '\n') l.add(i + 1)
        return l.toIntArray()
    }

    private fun lineOf(st: IntArray, off: Int): Int {
        var lo = 0
        var hi = st.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (st[mid] <= off) lo = mid else hi = mid - 1
        }
        return lo + 1
    }

    fun diagnose(dir: File, rel: String, text: String, mode: Int): List<Diag> {
        if (text.length > 300_000) return emptyList()
        return try {
            when (mode) {
                Highlighter.JAVA, Highlighter.KOTLIN -> diagCode(dir, rel, text, mode == Highlighter.KOTLIN)
                Highlighter.XML -> diagXml(dir, rel, text)
                else -> emptyList()
            }
        } catch (_: Throwable) { emptyList() }
    }

    private fun diagCode(dir: File, rel: String, text: String, kotlin: Boolean): List<Diag> {
        val out = ArrayList<Diag>()
        val st = starts(text)
        fun add(start: Int, len: Int, msg: String, error: Boolean) {
            val s = start.coerceIn(0, text.length)
            out.add(Diag(s, len.coerceAtLeast(1).coerceAtMost((text.length - s).coerceAtLeast(1)), lineOf(st, s), msg, error))
        }

        val stack = ArrayList<IntArray>() // [karakter, posisi]
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    val e = text.indexOf('\n', i)
                    i = if (e < 0) n else e
                    continue
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val e = text.indexOf("*/", i + 2)
                    if (e < 0) { add(i, 2, "Komentar /* belum ditutup dengan */", true); i = n } else i = e + 2
                    continue
                }
                text.startsWith("\"\"\"", i) -> {
                    val e = text.indexOf("\"\"\"", i + 3)
                    if (e < 0) { add(i, 3, "String \"\"\" belum ditutup", true); i = n } else i = e + 3
                    continue
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < n && text[j] != '\n' && text[j] != '"') {
                        if (text[j] == '\\') j++
                        j++
                    }
                    if (j >= n || text[j] != '"') {
                        val le = text.indexOf('\n', i).let { if (it < 0) n else it }
                        add(i, le - i, "String belum ditutup dengan tanda kutip (\")", true)
                        i = le
                    } else i = j + 1
                    continue
                }
                c == '\'' -> {
                    var j = i + 1
                    while (j < n && text[j] != '\n' && text[j] != '\'') {
                        if (text[j] == '\\') j++
                        j++
                    }
                    if (j >= n || text[j] != '\'') {
                        val le = text.indexOf('\n', i).let { if (it < 0) n else it }
                        add(i, le - i, "Karakter literal belum ditutup dengan (')", true)
                        i = le
                    } else i = j + 1
                    continue
                }
                c == '{' || c == '(' || c == '[' -> stack.add(intArrayOf(c.code, i))
                c == '}' || c == ')' || c == ']' -> {
                    val want = when (c) { '}' -> '{'; ')' -> '('; else -> '[' }
                    if (stack.isEmpty()) add(i, 1, "'$c' tidak punya pasangan pembuka", true)
                    else {
                        val top = stack.removeAt(stack.size - 1)
                        if (top[0] != want.code) {
                            val closerNeeded = when (top[0].toChar()) { '{' -> '}'; '(' -> ')'; else -> ']' }
                            add(i, 1, "Seharusnya '$closerNeeded' untuk menutup '${top[0].toChar()}' di baris ${lineOf(st, top[1])}", true)
                        }
                    }
                }
            }
            i++
        }
        stack.forEach { top ->
            val closerNeeded = when (top[0].toChar()) { '{' -> '}'; '(' -> ')'; else -> ']' }
            add(top[1], 1, "'${top[0].toChar()}' belum ditutup (butuh '$closerNeeded')", true)
        }

        val base = rel.substringAfterLast('/').substringBeforeLast('.')
        if (!kotlin) {
            Regex("""\bpublic\s+(?:final\s+|abstract\s+|static\s+)*(?:class|interface|enum)\s+(\w+)""").find(text)?.let { m ->
                val name = m.groupValues[1]
                if (name != base) add(m.groups[1]!!.range.first, name.length, "Kelas publik '$name' harus ada di file bernama $name.java", true)
            }
        }
        Regex("""(?m)^\s*package\s+([\w.]+)""").find(text)?.let { m ->
            val parts = rel.split('/')
            if (parts.size > 2 && (parts[0] == "src" || parts[0] == "java")) {
                val expected = parts.subList(1, parts.size - 1).joinToString(".")
                val got = m.groupValues[1]
                if (got != expected) add(m.groups[1]!!.range.first, got.length, "Package '$got' tidak sama dengan letak folder ($expected)", false)
            }
        }

        // referensi R.xxx.yyy yang tidak ada di resource proyek
        val idx = index(dir)
        val maskedComments = Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/").replace(text) { " ".repeat(it.value.length) }
        Regex("""(?<![\w.])R\.(id|string|color|dimen|layout|drawable|mipmap|raw|xml|style|array|bool|integer|menu|anim|font)\.(\w+)""").findAll(maskedComments).forEach { m ->
            val type = m.groupValues[1]
            val name = m.groupValues[2]
            val names = idx.res[type]
            if (names == null || name !in names) {
                add(m.range.first, m.value.length, "R.$type.$name tidak ditemukan di resource proyek (abaikan jika berasal dari library)", false)
            }
        }
        return out.sortedBy { it.start }
    }

    private val LAYOUT_SKIP = setOf("merge", "requestFocus", "tag", "include", "fragment", "layout", "data", "variable", "import")

    private fun diagXml(dir: File, rel: String, text: String): List<Diag> {
        val out = ArrayList<Diag>()
        val st = starts(text)
        fun addAt(off: Int, len: Int, msg: String, error: Boolean) {
            val s = off.coerceIn(0, text.length)
            out.add(Diag(s, len.coerceAtLeast(1).coerceAtMost((text.length - s).coerceAtLeast(1)), lineOf(st, s), msg, error))
        }
        fun offsetOf(line: Int, col: Int): Int {
            val l = (line - 1).coerceIn(0, st.size - 1)
            return (st[l] + (col - 1).coerceAtLeast(0)).coerceIn(0, text.length)
        }

        val isLayout = rel.startsWith("res/layout")
        val ids = HashSet<String>()
        try {
            val p = XmlPullParserFactory.newInstance().newPullParser()
            p.setInput(StringReader(text))
            val nameStack = ArrayList<String>()
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) {
                    val parentName = nameStack.lastOrNull()
                    nameStack.add(p.name)
                    if (isLayout && p.name !in LAYOUT_SKIP && parentName != "TableRow" && parentName != "TableLayout") {
                        var hasW = false
                        var hasH = false
                        var id: String? = null
                        for (k in 0 until p.attributeCount) {
                            when (p.getAttributeName(k)) {
                                "android:layout_width" -> hasW = true
                                "android:layout_height" -> hasH = true
                                "android:id" -> id = p.getAttributeValue(k)
                            }
                        }
                        val endOff = offsetOf(p.lineNumber, p.columnNumber)
                        val tagStart = text.lastIndexOf("<" + p.name, endOff).let { if (it < 0) endOff else it }
                        if (!hasW || !hasH) {
                            val which = if (!hasW && !hasH) "layout_width dan layout_height" else if (!hasW) "layout_width" else "layout_height"
                            addAt(tagStart, p.name.length + 1, "<${p.name}> belum punya $which; aplikasi akan crash saat layout dibuka", false)
                        }
                        if (id != null && id.startsWith("@")) {
                            if (!ids.add(id.substringAfter('/'))) addAt(tagStart, p.name.length + 1, "ID '${id.substringAfter('/')}' dipakai dua kali di layout ini", false)
                        }
                    }
                } else if (p.eventType == XmlPullParser.END_TAG) {
                    if (nameStack.isNotEmpty()) nameStack.removeAt(nameStack.size - 1)
                }
            }
        } catch (e: XmlPullParserException) {
            val off = offsetOf(e.lineNumber.coerceAtLeast(1), e.columnNumber.coerceAtLeast(1))
            val raw = (e.message ?: "XML tidak valid").substringBefore(" (position").substringBefore(" @").take(110)
            val ls = st[(lineOf(st, off) - 1).coerceIn(0, st.size - 1)]
            val le = text.indexOf('\n', ls).let { if (it < 0) text.length else it }
            addAt(ls, (le - ls).coerceAtLeast(1), "XML: $raw", true)
        } catch (_: Exception) {}

        // referensi resource yang tidak ada
        val idx = index(dir)
        val masked = Regex("<!--[\\s\\S]*?-->").replace(text) { " ".repeat(it.value.length) }
        Regex("""@(string|color|dimen|drawable|mipmap|layout|id|style|bool|integer|array|menu|anim|raw|xml|font)/([\w.]+)""").findAll(masked).forEach { m ->
            val type = m.groupValues[1]
            val name = m.groupValues[2]
            val names = idx.res[type]
            if (names == null || name !in names) {
                addAt(m.range.first, m.value.length, "@$type/$name tidak ditemukan di resource proyek (abaikan jika dari library)", false)
            }
        }

        if (rel == "AndroidManifest.xml") {
            val pkg = Regex("package\\s*=\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
            Regex("""<(?:activity|service|receiver|provider)\b[^>]*?android:name\s*=\s*\x22([^\x22]+)\x22""").findAll(masked).forEach { m ->
                val raw = m.groupValues[1]
                if (raw.startsWith("androidx.") || raw.startsWith("android.") || raw.startsWith("com.google.") || raw.startsWith("com.android.")) return@forEach
                val full = if (raw.startsWith(".")) pkg + raw else if (!raw.contains('.')) "$pkg.$raw" else raw
                val simple = full.substringAfterLast('.')
                val found = idx.syms.any { it.kind == "class" && it.name == simple }
                val inLibs = !raw.startsWith(".") && raw.contains('.') && !raw.startsWith(pkg)
                if (!found && !inLibs) {
                    addAt(m.groups[1]!!.range.first, raw.length, "Kelas '$simple' tidak ada di src/ (Activity ini akan membuat aplikasi crash)", false)
                }
            }
        }
        return out.sortedBy { it.start }
    }

    // =====================================================================
    //  LOMPAT KE DEFINISI
    // =====================================================================

    /** Kata (identifier) di sekitar kursor. */
    fun wordAt(text: String, caret: Int): String {
        var s = caret.coerceIn(0, text.length)
        var e = s
        while (s > 0 && isIdent(text[s - 1])) s--
        while (e < text.length && isIdent(text[e])) e++
        return text.substring(s, e)
    }

    fun definitions(dir: File, text: String, caret: Int, fileRel: String?): List<Sym> {
        val idx = index(dir, true)
        val word = wordAt(text, caret)
        if (word.isEmpty()) return emptyList()
        var s = caret.coerceIn(0, text.length)
        while (s > 0 && (isIdent(text[s - 1]) || text[s - 1] == '.' || text[s - 1] == '@' || text[s - 1] == '/' || text[s - 1] == '+')) s--
        var e = caret.coerceIn(0, text.length)
        while (e < text.length && isIdent(text[e])) e++
        val around = text.substring(s, e)
        Regex("""R\.(\w+)\.(\w+)$""").find(around)?.let { m ->
            return idx.syms.filter { it.kind == "R.${m.groupValues[1]}" && it.name == m.groupValues[2] }
        }
        Regex("""@\+?(\w+)/([\w.]+)$""").find(around)?.let { m ->
            val n = m.groupValues[2].replace('.', '_')
            return idx.syms.filter { it.kind == "R.${m.groupValues[1]}" && it.name == n }
        }
        val kinds = setOf("class", "method", "field")
        val hits = idx.syms.filter { it.name == word && it.kind in kinds }
        return hits.sortedWith(compareBy({ it.rel != fileRel }, { it.kind != "class" }))
    }
}
