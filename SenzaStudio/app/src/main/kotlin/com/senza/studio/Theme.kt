package com.senza.studio

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Palet warna tema biru "Liquid Glass" Senza Studio. */
object Pal {
    val bgTop = 0xFF040B26.toInt()
    val bgBottom = 0xFF081552.toInt()
    val primary = 0xFF2F6BFF.toInt()
    val primaryLight = 0xFF4DA3FF.toInt()
    val accent = 0xFF8B5CFF.toInt()
    val text = 0xFFEAF0FF.toInt()
    val sub = 0xFF8FA3D6.toInt()
    val dim = 0xFF5C6B99.toInt()
    val glass = 0x261E40FF
    val glassStrong = 0x4D1E3FBF
    val glassStroke = 0x663A6BFF
    val field = 0x33101C5C
    val ok = 0xFF22C55E.toInt()
    val okBg = 0x3322C55E
    val err = 0xFFFF5470.toInt()
    val errBg = 0x33FF5470
    val warn = 0xFFFBBF24.toInt()
    val warnBg = 0x33FBBF24
    val editorBg = 0xFF050C2A.toInt()
    val editorFg = 0xFFD6E0FF.toInt()
    val gutterBg = 0xFF070F36.toInt()
    val navBg = 0xF0050B2C.toInt()
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.bgGradient(): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Pal.bgTop, Pal.bgBottom))

/** Panel kaca: isi transparan kebiruan + garis tepi bercahaya. */
fun Context.glass(
    radiusDp: Int = 16,
    fill: Int = Pal.glass,
    stroke: Int = Pal.glassStroke,
    strokeDp: Int = 1
): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = dp(radiusDp).toFloat()
    if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
}

fun Context.shape(fill: Int, radiusDp: Int = 12): GradientDrawable =
    GradientDrawable().apply { setColor(fill); cornerRadius = dp(radiusDp).toFloat() }

fun Context.ripple(content: Drawable, radiusDp: Int = 12): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), content, shape(Color.WHITE, radiusDp))

fun Context.glassPress(radiusDp: Int = 16, fill: Int = Pal.glass): Drawable =
    ripple(glass(radiusDp, fill), radiusDp)

fun Context.lp(
    w: Int = MATCH, h: Int = WRAP,
    l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0,
    weight: Float = 0f
) = LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

fun Context.label(text: CharSequence, sp: Float = 14f, color: Int = Pal.text, bold: Boolean = false) =
    TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun Context.mono(text: CharSequence = "", sp: Float = 12f, color: Int = Pal.text) =
    label(text, sp, color).apply { typeface = Typeface.MONOSPACE }

/** Ikon vektor yang diwarnai ulang saat dijalankan. */
fun Context.icon(res: Int, sizeDp: Int = 24, tint: Int = Pal.text): ImageView =
    ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

fun ImageView.tint(color: Int) { imageTintList = ColorStateList.valueOf(color) }

fun Context.iconButton(res: Int, sizeDp: Int = 24, tint: Int = Pal.text, onClick: () -> Unit): FrameLayout =
    FrameLayout(this).apply {
        val pad = dp(10)
        setPadding(pad, pad, pad, pad)
        background = ripple(shape(Color.TRANSPARENT, 20), 20)
        isClickable = true
        isFocusable = true
        addView(icon(res, sizeDp, tint).apply { layoutParams = FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)) })
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp) + pad * 2, dp(sizeDp) + pad * 2)
    }

/** Tombol utama bergradasi biru, dengan ikon opsional. */
fun Context.primaryButton(
    text: String, iconRes: Int? = null, sp: Float = 16f,
    colors: IntArray = intArrayOf(Pal.primary, 0xFF1F4FD8.toInt()),
    onClick: () -> Unit
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
    setPadding(dp(18), dp(14), dp(18), dp(14))
    val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
        cornerRadius = dp(26).toFloat()
        setStroke(dp(1), 0x884DA3FF.toInt())
    }
    background = ripple(g, 26)
    isClickable = true
    isFocusable = true
    if (iconRes != null) addView(icon(iconRes, 20, Color.WHITE), lp(WRAP, WRAP, r = 8))
    addView(label(text, sp, Color.WHITE, true))
    setOnClickListener { onClick() }
}

fun Context.glassButton(text: String, iconRes: Int? = null, sp: Float = 14f, tint: Int = Pal.text, onClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = glassPress(22, Pal.glassStrong)
        isClickable = true
        isFocusable = true
        if (iconRes != null) addView(icon(iconRes, 18, tint), lp(WRAP, WRAP, r = 6))
        addView(label(text, sp, tint, true))
        setOnClickListener { onClick() }
    }

fun Context.card(padDp: Int = 16, fill: Int = Pal.glass): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = glass(18, fill)
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    }

/** Kontrol segmen (mis. Debug | Release). */
fun Context.segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = glass(22, 0x33101C5C)
        setPadding(dp(3), dp(3), dp(3), dp(3))
    }
    val cells = ArrayList<TextView>()
    fun paint(sel: Int) {
        cells.forEachIndexed { i, tv ->
            if (i == sel) {
                tv.background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.primary, 0xFF1F4FD8.toInt())
                ).apply { cornerRadius = dp(20).toFloat() }
                tv.setTextColor(Color.WHITE)
            } else {
                tv.background = null
                tv.setTextColor(Pal.sub)
            }
        }
    }
    options.forEachIndexed { i, o ->
        val tv = label(o, 14f, Pal.sub, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(9), dp(8), dp(9))
            isClickable = true
            setOnClickListener { paint(i); onSelect(i) }
        }
        cells.add(tv)
        row.addView(tv, LinearLayout.LayoutParams(0, WRAP, 1f))
    }
    paint(selected)
    return row
}

fun Context.textField(hint: String = "", value: String = "", numeric: Boolean = false): EditText =
    EditText(this).apply {
        setText(value)
        this.hint = hint
        setTextColor(Pal.text)
        setHintTextColor(Pal.dim)
        textSize = 15f
        setSingleLine()
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = glass(12, Pal.field)
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
    }

fun Context.fieldLabel(text: String) = label(text, 12f, Pal.sub).apply { setPadding(dp(2), 0, 0, dp(4)) }

// Bilah atas: ikon kiri, judul, lalu ikon aksi.
fun Context.topBar(
    title: String,
    leftIcon: Int?,
    onLeft: (() -> Unit)?,
    vararg actions: Pair<Int, () -> Unit>
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(6), dp(8), dp(6), dp(8))
    if (leftIcon != null && onLeft != null) addView(iconButton(leftIcon, 24, Pal.text, onLeft))
    else addView(View(context), LinearLayout.LayoutParams(dp(12), 1))
    addView(label(title, 20f, Pal.text, true).apply { setSingleLine() }, lp(0, WRAP, weight = 1f))
    actions.forEach { (ic, cb) -> addView(iconButton(ic, 22, Pal.text, cb)) }
}

fun Context.divider(): View = View(this).apply { setBackgroundColor(0x22FFFFFF) }

fun EditText.onChanged(cb: (Editable?) -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun afterTextChanged(s: Editable?) = cb(s)
})

/** Dasar semua layar. */
abstract class Screen(val act: MainActivity) {
    abstract val view: View
    open val isTab: Boolean = false
    open val showsNav: Boolean = true
    open fun onShow() {}
    open fun onHide() {}
    /** true jika back sudah ditangani layar ini. */
    open fun onBack(): Boolean = false
}
