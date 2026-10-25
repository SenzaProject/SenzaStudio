package com.senza.studio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.EditText

/**
 * EditText kode: kolom nomor baris tetap di kiri saat digulir, penanda error/peringatan di gutter,
 * nomor baris yang tetap benar saat ada kode dilipat, dan callback saat kursor berpindah.
 */
class CodeEditText(ctx: Context) : EditText(ctx) {

    var showLineNumbers: Boolean = true
        set(v) {
            field = v
            applyPadding()
            invalidate()
        }

    /** Nomor baris (mulai 1, dalam teks asli) yang bermasalah. */
    var errorLines: Set<Int> = emptySet()
    var warnLines: Set<Int> = emptySet()

    var onSelection: ((Int, Int) -> Unit)? = null

    private val gutterW = ctx.dp(46)
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.dim
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }
    private val gutterPaint = Paint().apply { color = Pal.gutterBg }
    private val linePaint = Paint().apply { color = 0x22FFFFFF }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init { applyPadding() }

    private fun applyPadding() {
        val left = if (showLineNumbers) gutterW + context.dp(10) else context.dp(12)
        setPadding(left, context.dp(12), context.dp(12), context.dp(12))
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelection?.invoke(selStart, selEnd)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showLineNumbers) return
        val l = layout ?: return
        val x0 = scrollX.toFloat()
        val top = scrollY.toFloat()
        canvas.drawRect(x0, top, x0 + gutterW, top + height, gutterPaint)
        canvas.drawRect(x0 + gutterW - 1f, top, x0 + gutterW, top + height, linePaint)
        numPaint.textSize = textSize * 0.9f
        val first = l.getLineForVertical(scrollY)
        val last = l.getLineForVertical(scrollY + height)

        // jumlah baris tersembunyi (terlipat) sebelum tiap baris yang terlihat
        val e = text
        val folds = if (e.isNotEmpty()) e.getSpans(0, e.length, FoldSpan::class.java).sortedBy { e.getSpanStart(it) } else emptyList()

        for (i in first..last) {
            val ls = l.getLineStart(i)
            var hidden = 0
            for (f in folds) if (e.getSpanEnd(f) <= ls) hidden += f.lines
            val logical = i + 1 + hidden
            val y = (l.getLineBaseline(i) + totalPaddingTop).toFloat()
            canvas.drawText(logical.toString(), x0 + gutterW - context.dp(8), y, numPaint)
            val mark = when {
                logical in errorLines -> Pal.err
                logical in warnLines -> Pal.warn
                else -> 0
            }
            if (mark != 0) {
                dotPaint.color = mark
                canvas.drawCircle(x0 + context.dp(7).toFloat(), y - textSize * 0.3f, context.dpf(3f), dotPaint)
            }
        }
    }
}
