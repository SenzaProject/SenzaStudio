package com.senza.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Impor aset (gambar, font, audio) dan pembuat ikon aplikasi. */
object AssetTools {

    const val DEST_AUTO = 0
    const val DEST_DRAWABLE = 1
    const val DEST_RAW = 2
    const val DEST_FONT = 3
    const val DEST_ASSETS = 4

    val DEST_LABELS = listOf(
        "Otomatis (sesuai jenis file)",
        "res/drawable (gambar)",
        "res/raw (audio / data mentah)",
        "res/font (font)",
        "assets (file apa adanya)"
    )

    private val IMAGE = setOf("png", "jpg", "jpeg", "webp", "gif")
    private val FONT = setOf("ttf", "otf")
    private val AUDIO = setOf("mp3", "wav", "ogg", "m4a", "aac", "mp4", "3gp", "flac")

    fun displayName(ctx: Context, uri: Uri): String {
        var n = "file"
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) n = c.getString(0) ?: n
            }
        } catch (_: Exception) {}
        return n
    }

    /** Nama resource Android: huruf kecil, angka, garis bawah; diawali huruf. */
    fun resName(base: String): String {
        var s = base.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }.joinToString("")
        s = s.replace(Regex("_+"), "_").trim('_')
        if (s.isEmpty()) s = "file"
        if (!s[0].isLetter()) s = "f_$s"
        return s
    }

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var i = 2
        while (f.exists()) f = File(dir, "${base}_${i++}.$ext")
        return f
    }

    /** Salin file-file ke proyek. Mengembalikan ringkasan per file (nama -> lokasi). */
    fun importFiles(ctx: Context, proj: File, uris: List<Uri>, dest: Int): List<String> {
        val done = ArrayList<String>()
        for (u in uris) {
            val name = displayName(ctx, u)
            val ext = name.substringAfterLast('.', "").lowercase()
            val base = name.substringBeforeLast('.', name)
            val target = when (dest) {
                DEST_DRAWABLE -> "res/drawable"
                DEST_RAW -> "res/raw"
                DEST_FONT -> "res/font"
                DEST_ASSETS -> "assets"
                else -> when {
                    ext in IMAGE -> "res/drawable"
                    ext in FONT -> "res/font"
                    ext in AUDIO -> "res/raw"
                    else -> "assets"
                }
            }
            val dir = File(proj, target).apply { mkdirs() }
            val f = if (target.startsWith("res/")) {
                require(ext.isNotEmpty()) { "File '$name' tidak punya ekstensi." }
                uniqueFile(dir, resName(base), ext)
            } else {
                var out = File(dir, name.replace('/', '_'))
                var i = 2
                while (out.exists()) out = File(dir, "${i++}_" + name.replace('/', '_'))
                out
            }
            ctx.contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            done.add("${f.name} → $target/")
        }
        return done
    }

    // =====================================================================
    //  PEMBUAT IKON
    // =====================================================================

    const val BG_AUTO = 0
    const val BG_WHITE = 1
    const val BG_BLACK = 2
    const val BG_BLUE = 3

    val BG_LABELS = listOf("Otomatis (dari warna gambar)", "Putih", "Hitam", "Biru Senza")

    fun decode(ctx: Context, uri: Uri): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (opts.outWidth / sample > 2048 || opts.outHeight / sample > 2048) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o2) }
            ?: throw IllegalArgumentException("Gambar tidak bisa dibaca. Pakai PNG, JPG, atau WEBP.")
    }

    private fun autoBackground(b: Bitmap): Int {
        val pts = listOf(0 to 0, b.width - 1 to 0, 0 to b.height - 1, b.width - 1 to b.height - 1)
        var r = 0; var g = 0; var bl = 0; var opaque = 0
        for ((x, y) in pts) {
            val c = b.getPixel(x, y)
            if (Color.alpha(c) > 200) { r += Color.red(c); g += Color.green(c); bl += Color.blue(c); opaque++ }
        }
        if (opaque < 3) return Color.WHITE
        return Color.rgb(r / opaque, g / opaque, bl / opaque)
    }

    /** Gambar [src] ke kotak [size] x [size], diperkecil menjadi [scale] dari sisi dan diletakkan di tengah. */
    private fun fitSquare(src: Bitmap, size: Int, scale: Float, bg: Int?, shape: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (bg != null) {
            paint.color = bg
            when (shape) {
                1 -> c.drawCircle(size / 2f, size / 2f, size / 2f, paint)
                2 -> c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.18f, size * 0.18f, paint)
                else -> c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            }
        }
        val box = size * scale
        val ratio = minOf(box / src.width, box / src.height)
        val w = src.width * ratio
        val h = src.height * ratio
        val l = (size - w) / 2f
        val t = (size - h) / 2f
        if (bg != null && shape != 0) {
            // potong gambar mengikuti bentuk ikon
            val clip = Path()
            if (shape == 1) clip.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
            else clip.addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.18f, size * 0.18f, Path.Direction.CW)
            c.clipPath(clip)
        }
        c.drawBitmap(src, null, RectF(l, t, l + w, t + h), paint)
        return out
    }

    private fun savePng(b: Bitmap, f: File) {
        f.parentFile?.mkdirs()
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Buat semua ikon dari satu gambar: mipmap mdpi–xxxhdpi (biasa dan bulat), adaptive icon (API 26+),
     * dan warna latar. Atribut icon di manifest ikut disetel.
     */
    fun generateIcons(proj: File, src: Bitmap, bgMode: Int): String {
        val bg = when (bgMode) {
            BG_WHITE -> Color.WHITE
            BG_BLACK -> Color.BLACK
            BG_BLUE -> 0xFF2F6BFF.toInt()
            else -> autoBackground(src)
        }
        val res = File(proj, "res")
        // hapus ikon lama bernama sama (format lain) supaya tidak bentrok
        res.listFiles { f -> f.isDirectory && f.name.startsWith("mipmap") }?.forEach { d ->
            d.listFiles { f -> f.isFile && f.nameWithoutExtension in setOf("ic_launcher", "ic_launcher_round", "ic_launcher_foreground") }
                ?.forEach { it.delete() }
        }
        val sizes = listOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)
        for ((d, px) in sizes) {
            val dir = File(res, "mipmap-$d")
            val sq = fitSquare(src, px, 0.82f, bg, 2)
            savePng(sq, File(dir, "ic_launcher.png"))
            val rd = fitSquare(src, px, 0.78f, bg, 1)
            savePng(rd, File(dir, "ic_launcher_round.png"))
            sq.recycle(); rd.recycle()
        }
        // lapisan depan adaptive icon: 108dp * 4 = 432px, isi di zona aman (sekitar 61%)
        val fg = fitSquare(src, 432, 0.61f, null, 0)
        savePng(fg, File(res, "mipmap-xxxhdpi/ic_launcher_foreground.png"))
        fg.recycle()

        val any = File(res, "mipmap-anydpi-v26").apply { mkdirs() }
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
            "    <background android:drawable=\"@color/ic_launcher_background\"/>\n" +
            "    <foreground android:drawable=\"@mipmap/ic_launcher_foreground\"/>\n" +
            "</adaptive-icon>\n"
        File(any, "ic_launcher.xml").writeText(xml)
        File(any, "ic_launcher_round.xml").writeText(xml)

        val hex = String.format("#%06X", bg and 0xFFFFFF)
        val values = File(res, "values").apply { mkdirs() }
        val colorFile = File(values, "ic_launcher_background.xml")
        // jangan bentrok jika sudah ada nama warna yang sama di file lain
        val clash = values.listFiles { f -> f.isFile && f.extension == "xml" && f.name != colorFile.name }
            ?.any { runCatching { it.readText().contains("name=\"ic_launcher_background\"") }.getOrDefault(false) } == true
        if (!clash) {
            colorFile.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <color name=\"ic_launcher_background\">$hex</color>\n</resources>\n")
        } else {
            values.listFiles { f -> f.isFile && f.extension == "xml" }?.forEach { f ->
                val t = f.readText()
                val n = Regex("(<color\\s+name=\"ic_launcher_background\"[^>]*>)[^<]*(</color>)").replace(t) { m -> m.groupValues[1] + hex + m.groupValues[2] }
                if (n != t) f.writeText(n)
            }
        }
        val manifestNote = setIconAttributes(File(proj, "AndroidManifest.xml"))
        return "Ikon dibuat (5 ukuran + adaptive icon, latar $hex). $manifestNote"
    }

    private fun setIconAttributes(manifest: File): String {
        if (!manifest.isFile) return "AndroidManifest.xml tidak ditemukan; setel android:icon sendiri."
        var t = manifest.readText()
        val m = Regex("<application\\b[^>]*>").find(t) ?: return "Tag <application> tidak ditemukan di manifest."
        var tag = m.value
        fun setAttr(name: String, value: String) {
            val re = Regex("\\s$name\\s*=\\s*\"[^\"]*\"")
            tag = if (re.containsMatchIn(tag)) re.replace(tag, " $name=\"$value\"")
            else tag.replaceFirst("<application", "<application $name=\"$value\"")
        }
        setAttr("android:icon", "@mipmap/ic_launcher")
        setAttr("android:roundIcon", "@mipmap/ic_launcher_round")
        if (tag == m.value) return "Manifest sudah memakai ikon ini."
        t = t.substring(0, m.range.first) + tag + t.substring(m.range.last + 1)
        manifest.writeText(t)
        return "Manifest disetel ke @mipmap/ic_launcher."
    }
}
