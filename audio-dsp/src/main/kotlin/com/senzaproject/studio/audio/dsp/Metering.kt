package com.senzaproject.studio.audio.dsp

import com.senzaproject.studio.audio.engine.SPECTRUM_BANDS
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private const val FLOOR_DB = -120.0

private fun levelToDb(x: Double): Double = if (x <= 1e-6) FLOOR_DB else 20.0 * log10(x)

/**
 * Peak and RMS level of each channel. Peaks fall at 20 dB/s (so they stay readable), RMS is a 300 ms
 * leaky mean square. Read-only on the audio; values are published once per block through volatile fields.
 */
class LevelMeter(sampleRate: Int) {
    private val peakFall = 10.0.pow(-FALL_DB_PER_SECOND / 20.0 / sampleRate)
    private val rmsCoef = 1.0 - exp(-1.0 / (sampleRate * 0.3))
    private var peakL = 0.0
    private var peakR = 0.0
    private var meanSquareL = 0.0
    private var meanSquareR = 0.0

    @Volatile
    var peakDbLeft: Double = FLOOR_DB
        private set
    @Volatile
    var peakDbRight: Double = FLOOR_DB
        private set
    @Volatile
    var rmsDbLeft: Double = FLOOR_DB
        private set
    @Volatile
    var rmsDbRight: Double = FLOOR_DB
        private set

    fun reset() {
        peakL = 0.0; peakR = 0.0; meanSquareL = 0.0; meanSquareR = 0.0
        peakDbLeft = FLOOR_DB; peakDbRight = FLOOR_DB; rmsDbLeft = FLOOR_DB; rmsDbRight = FLOOR_DB
    }

    fun process(buf: FloatArray, frames: Int) {
        var i = 0
        val end = frames * 2
        while (i < end) {
            val l = buf[i].toDouble()
            val r = buf[i + 1].toDouble()
            peakL = max(abs(l), peakL * peakFall)
            peakR = max(abs(r), peakR * peakFall)
            meanSquareL += (l * l - meanSquareL) * rmsCoef
            meanSquareR += (r * r - meanSquareR) * rmsCoef
            i += 2
        }
        peakDbLeft = levelToDb(peakL)
        peakDbRight = levelToDb(peakR)
        rmsDbLeft = levelToDb(kotlin.math.sqrt(meanSquareL))
        rmsDbRight = levelToDb(kotlin.math.sqrt(meanSquareR))
    }

    private companion object {
        const val FALL_DB_PER_SECOND = 20.0
    }
}

/**
 * Real-time spectrum of the output: mono sum, 2048-point Hann-windowed FFT every 1024 frames, grouped
 * into [SPECTRUM_BANDS] log-spaced bands from 20 Hz upwards. Each band shows its strongest bin as a
 * dBFS sine amplitude (a full-scale sine reads about 0 dB, within the 1.4 dB scalloping of the window).
 * Bands rise instantly and fall at 30 dB/s. No allocation after construction.
 */
class SpectrumAnalyzer(private val sampleRate: Int) {
    private val ring = FloatArray(FFT_SIZE)
    private var ringPos = 0
    private var sinceFft = 0
    private val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2.0 * PI * it / FFT_SIZE) }
    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)
    private val cosTable = DoubleArray(FFT_SIZE / 2) { cos(2.0 * PI * it / FFT_SIZE) }
    private val sinTable = DoubleArray(FFT_SIZE / 2) { -sin(2.0 * PI * it / FFT_SIZE) }
    private val reversed = IntArray(FFT_SIZE).also { table ->
        val bits = Integer.numberOfTrailingZeros(FFT_SIZE)
        for (i in 0 until FFT_SIZE) table[i] = Integer.reverse(i) ushr (32 - bits)
    }
    private val binHz = sampleRate.toDouble() / FFT_SIZE
    private val firstBin = IntArray(SPECTRUM_BANDS)
    private val lastBin = IntArray(SPECTRUM_BANDS)
    private val fallPerHop = FALL_DB_PER_SECOND * HOP / sampleRate
    private var levels = FloatArray(SPECTRUM_BANDS) { FLOOR_DB.toFloat() }
    @Volatile private var published = FloatArray(SPECTRUM_BANDS) { FLOOR_DB.toFloat() }
    private var spare = FloatArray(SPECTRUM_BANDS) { FLOOR_DB.toFloat() }

    init {
        val top = min(MAX_HZ, sampleRate * 0.45)
        for (k in 0 until SPECTRUM_BANDS) {
            val lo = edge(k, top)
            val hi = edge(k + 1, top)
            var a = kotlin.math.ceil(lo / binHz).toInt().coerceAtLeast(1)
            var b = kotlin.math.floor(hi / binHz).toInt().coerceAtMost(FFT_SIZE / 2 - 1)
            if (b < a) {
                a = Math.round(kotlin.math.sqrt(lo * hi) / binHz).toInt().coerceIn(1, FFT_SIZE / 2 - 1)
                b = a
            }
            firstBin[k] = a
            lastBin[k] = b
        }
    }

    private fun edge(k: Int, top: Double): Double = MIN_HZ * (top / MIN_HZ).pow(k.toDouble() / SPECTRUM_BANDS)

    /** Centre frequency (geometric) of a band, for labels. */
    fun bandCenterHz(band: Int): Double {
        val top = min(MAX_HZ, sampleRate * 0.45)
        return kotlin.math.sqrt(edge(band, top) * edge(band + 1, top))
    }

    /** Index of the band that contains [hz] (clamped to the first/last band). */
    fun bandIndexFor(hz: Double): Int {
        val top = min(MAX_HZ, sampleRate * 0.45)
        if (hz <= MIN_HZ) return 0
        if (hz >= top) return SPECTRUM_BANDS - 1
        return (kotlin.math.ln(hz / MIN_HZ) / kotlin.math.ln(top / MIN_HZ) * SPECTRUM_BANDS).toInt()
            .coerceIn(0, SPECTRUM_BANDS - 1)
    }

    fun reset() {
        ring.fill(0f)
        ringPos = 0; sinceFft = 0
        levels.fill(FLOOR_DB.toFloat())
        published = FloatArray(SPECTRUM_BANDS) { FLOOR_DB.toFloat() }
    }

    /** Copies the latest band levels (dBFS) into [out], which must hold [SPECTRUM_BANDS] values. */
    fun snapshot(out: FloatArray) {
        System.arraycopy(published, 0, out, 0, SPECTRUM_BANDS)
    }

    fun process(buf: FloatArray, frames: Int) {
        var i = 0
        while (i < frames) {
            ring[ringPos] = (buf[i * 2] + buf[i * 2 + 1]) * 0.5f
            ringPos++
            if (ringPos == FFT_SIZE) ringPos = 0
            if (++sinceFft == HOP) {
                sinceFft = 0
                analyse()
            }
            i++
        }
    }

    private fun analyse() {
        // Oldest sample sits at ringPos.
        for (n in 0 until FFT_SIZE) {
            var idx = ringPos + n
            if (idx >= FFT_SIZE) idx -= FFT_SIZE
            val slot = reversed[n]
            re[slot] = ring[idx] * window[n]
            im[slot] = 0.0
        }
        var half = 1
        while (half < FFT_SIZE) {
            val step = FFT_SIZE / (half * 2)
            var start = 0
            while (start < FFT_SIZE) {
                for (j in 0 until half) {
                    val wr = cosTable[j * step]
                    val wi = sinTable[j * step]
                    val a = start + j
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                start += half * 2
            }
            half *= 2
        }
        val scale = 4.0 / FFT_SIZE // Hann: a sine of amplitude A gives |X| = A * N / 4
        for (k in 0 until SPECTRUM_BANDS) {
            var peak = 0.0
            for (bin in firstBin[k]..lastBin[k]) {
                val mag = kotlin.math.sqrt(re[bin] * re[bin] + im[bin] * im[bin]) * scale
                if (mag > peak) peak = mag
            }
            val db = levelToDb(peak).toFloat()
            val fallen = levels[k] - fallPerHop.toFloat()
            levels[k] = if (db > fallen) db else max(fallen, FLOOR_DB.toFloat())
        }
        System.arraycopy(levels, 0, spare, 0, SPECTRUM_BANDS)
        val old = published
        published = spare
        spare = old
    }

    companion object {
        const val FFT_SIZE = 2048
        const val HOP = 1024
        const val MIN_HZ = 20.0
        const val MAX_HZ = 20_000.0
        private const val FALL_DB_PER_SECOND = 30.0
    }
}
