package com.senzaproject.studio.audio.dsp

import com.senzaproject.studio.audio.engine.LoudnessParameters
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/** Mean square -> LUFS (ITU-R BS.1770: -0.691 + 10 log10 z). */
internal fun meanSquareToLufs(z: Double): Double =
    if (z <= 1e-12) LoudnessMeter.SILENCE_LUFS else -0.691 + 10.0 * log10(z)

/**
 * ITU-R BS.1770 K-weighting (high-shelf pre-filter + RLB high-pass), designed for any sample
 * rate with the bilinear-transform form used by libebur128. At 48 kHz the coefficients equal the
 * values published in the recommendation (verified by test).
 */
class KWeighting(sampleRate: Int) {
    private val shelfL = MonoBiquad()
    private val shelfR = MonoBiquad()
    private val highPassL = MonoBiquad()
    private val highPassR = MonoBiquad()

    init {
        val shelf = shelfCoefficients(sampleRate)
        val highPass = highPassCoefficients(sampleRate)
        shelfL.set(shelf); shelfR.set(shelf)
        highPassL.set(highPass); highPassR.set(highPass)
    }

    fun tickLeft(x: Double): Double = highPassL.tick(shelfL.tick(x))

    fun tickRight(x: Double): Double = highPassR.tick(shelfR.tick(x))

    fun reset() {
        shelfL.reset(); shelfR.reset(); highPassL.reset(); highPassR.reset()
    }

    fun flush() {
        shelfL.flush(); shelfR.flush(); highPassL.flush(); highPassR.flush()
    }

    companion object {
        fun shelfCoefficients(sampleRate: Int): BiquadCoefficients {
            val f0 = 1681.974450955533
            val gainDb = 3.999843853973347
            val q = 0.7071752369554196
            val k = tan(PI * f0 / sampleRate)
            val vh = 10.0.pow(gainDb / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1.0 + k / q + k * k
            return BiquadCoefficients(
                b0 = (vh + vb * k / q + k * k) / a0,
                b1 = 2.0 * (k * k - vh) / a0,
                b2 = (vh - vb * k / q + k * k) / a0,
                a1 = 2.0 * (k * k - 1.0) / a0,
                a2 = (1.0 - k / q + k * k) / a0,
            )
        }

        fun highPassCoefficients(sampleRate: Int): BiquadCoefficients {
            val f0 = 38.13547087602444
            val q = 0.5003270373238773
            val k = tan(PI * f0 / sampleRate)
            val a0 = 1.0 + k / q + k * k
            return BiquadCoefficients(
                b0 = 1.0,
                b1 = -2.0,
                b2 = 1.0,
                a1 = 2.0 * (k * k - 1.0) / a0,
                a2 = (1.0 - k / q + k * k) / a0,
            )
        }
    }
}

/**
 * Sample-peak and approximate true-peak meter (4x oversampling).
 *
 * The oversampler is a 49-tap Hann-windowed sinc, split into 4 polyphase branches. Branch 0 is the
 * original sample (delayed by 6 samples), so the true peak is never below the sample peak. Branches
 * 1..3 use 12 taps each, normalised to unity DC gain. Measured error vs analytic sine peaks is
 * below 0.31 dB up to 20 kHz at 48 kHz (see docs/DSP_P3.md); treat the value as an estimate,
 * not a certified BS.1770 / EBU R128 dBTP reading.
 */
class TruePeakMeter {
    private val historyL = DoubleArray(HISTORY * 2)
    private val historyR = DoubleArray(HISTORY * 2)
    private var pos = 0
    private var maxTruePeak = 0.0
    private var maxSamplePeak = 0.0

    @Volatile
    private var resetRequested = false

    @Volatile
    var truePeakDb: Double = FLOOR_DB
        private set

    @Volatile
    var samplePeakDb: Double = FLOOR_DB
        private set

    /** Clears the held peaks; safe to call from any thread (applied on the audio thread). */
    fun resetPeaks() {
        resetRequested = true
    }

    fun reset() {
        historyL.fill(0.0); historyR.fill(0.0)
        pos = 0
        clearPeaks()
    }

    private fun clearPeaks() {
        maxTruePeak = 0.0
        maxSamplePeak = 0.0
        truePeakDb = FLOOR_DB
        samplePeakDb = FLOOR_DB
    }

    fun push(l: Double, r: Double) {
        if (resetRequested) {
            resetRequested = false
            clearPeaks()
        }
        historyL[pos] = l; historyL[pos + HISTORY] = l
        historyR[pos] = r; historyR[pos + HISTORY] = r

        val samplePeak = max(abs(l), abs(r))
        if (samplePeak > maxSamplePeak) {
            maxSamplePeak = samplePeak
            samplePeakDb = toDb(samplePeak)
        }

        // x[m-k] lives at index pos + HISTORY - k (k = 0..HISTORY-1).
        val base = pos + HISTORY
        var peak = max(abs(historyL[base - 6]), abs(historyR[base - 6])) // branch 0: original sample
        for (branch in 0 until 3) {
            var sumL = 0.0
            var sumR = 0.0
            val c = branch * HISTORY
            for (k in 0 until HISTORY) {
                val coef = COEFFICIENTS[c + k]
                sumL += coef * historyL[base - k]
                sumR += coef * historyR[base - k]
            }
            peak = max(peak, max(abs(sumL), abs(sumR)))
        }
        if (peak > maxTruePeak) {
            maxTruePeak = peak
            truePeakDb = toDb(peak)
        }
        pos++
        if (pos == HISTORY) pos = 0
    }

    private fun toDb(x: Double): Double = if (x <= 1e-6) FLOOR_DB else 20.0 * log10(x)

    companion object {
        const val FLOOR_DB = -120.0
        private const val HISTORY = 12

        private val COEFFICIENTS: DoubleArray = run {
            val taps = 49
            val h = DoubleArray(taps) { n ->
                val x = (n - 24) / 4.0
                val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                sinc * (0.5 - 0.5 * cos(2.0 * PI * n / 48.0))
            }
            val out = DoubleArray(3 * HISTORY)
            for (branch in 1..3) {
                var sum = 0.0
                for (k in 0 until HISTORY) sum += h[branch + 4 * k]
                for (k in 0 until HISTORY) out[(branch - 1) * HISTORY + k] = h[branch + 4 * k] / sum
            }
            out
        }
    }
}

/**
 * Loudness meter after ITU-R BS.1770 / EBU R128 for stereo (channel weights 1.0).
 *  - momentary: 400 ms window, short-term: 3 s window, both refreshed every 100 ms;
 *  - integrated: gated (absolute -70 LUFS, relative -10 LU) over 400 ms blocks with 75 % overlap,
 *    accumulated in a 0.1 LU histogram so no memory grows over time;
 *  - sample peak and approximate true peak ([TruePeakMeter]).
 * Not implemented: loudness range (LRA). Values are published through volatile fields and may be
 * read from any thread; [process] is real-time safe (no allocation, no locks).
 */
class LoudnessMeter(private val sampleRate: Int) {
    private val kWeighting = KWeighting(sampleRate)
    private val peaks = TruePeakMeter()
    private val hop = max(1, sampleRate / 10)
    private val ring = DoubleArray(SHORT_TERM_BLOCKS)
    private val histogramCount = IntArray(BINS)
    private val histogramEnergy = DoubleArray(BINS)
    private var ringPos = 0
    private var filled = 0
    private var accumulated = 0.0
    private var accumulatedFrames = 0

    @Volatile
    var momentaryLufs: Double = SILENCE_LUFS
        private set

    @Volatile
    var shortTermLufs: Double = SILENCE_LUFS
        private set

    @Volatile
    var integratedLufs: Double = SILENCE_LUFS
        private set

    val truePeakDb: Double get() = peaks.truePeakDb
    val samplePeakDb: Double get() = peaks.samplePeakDb

    fun resetPeaks() = peaks.resetPeaks()

    fun reset() {
        kWeighting.reset()
        peaks.reset()
        ring.fill(0.0)
        histogramCount.fill(0)
        histogramEnergy.fill(0.0)
        ringPos = 0; filled = 0; accumulated = 0.0; accumulatedFrames = 0
        momentaryLufs = SILENCE_LUFS
        shortTermLufs = SILENCE_LUFS
        integratedLufs = SILENCE_LUFS
    }

    fun process(buf: FloatArray, frames: Int) {
        var i = 0
        while (i < frames) {
            val l = buf[i * 2].toDouble()
            val r = buf[i * 2 + 1].toDouble()
            val yl = kWeighting.tickLeft(l)
            val yr = kWeighting.tickRight(r)
            accumulated += yl * yl + yr * yr
            peaks.push(l, r)
            accumulatedFrames++
            if (accumulatedFrames == hop) finishBlock()
            i++
        }
        kWeighting.flush()
    }

    private fun finishBlock() {
        ring[ringPos] = accumulated / hop
        accumulated = 0.0
        accumulatedFrames = 0
        ringPos++
        if (ringPos == SHORT_TERM_BLOCKS) ringPos = 0
        if (filled < SHORT_TERM_BLOCKS) filled++

        momentaryLufs = meanSquareToLufs(meanOfLast(minOf(filled, MOMENTARY_BLOCKS)))
        shortTermLufs = meanSquareToLufs(meanOfLast(minOf(filled, SHORT_TERM_BLOCKS)))

        if (filled >= MOMENTARY_BLOCKS) {
            val z = meanOfLast(MOMENTARY_BLOCKS)
            val loudness = meanSquareToLufs(z)
            if (loudness > ABSOLUTE_GATE_LUFS) {
                val bin = ((loudness - BIN_MIN_LUFS) / BIN_STEP_LU).toInt().coerceIn(0, BINS - 1)
                histogramCount[bin]++
                histogramEnergy[bin] += z
            }
            updateIntegrated()
        }
    }

    private fun meanOfLast(n: Int): Double {
        var sum = 0.0
        var idx = ringPos
        for (j in 0 until n) {
            idx = if (idx == 0) SHORT_TERM_BLOCKS - 1 else idx - 1
            sum += ring[idx]
        }
        return sum / n
    }

    private fun updateIntegrated() {
        var energy = 0.0
        var count = 0L
        for (b in 0 until BINS) {
            energy += histogramEnergy[b]
            count += histogramCount[b]
        }
        if (count == 0L) {
            integratedLufs = SILENCE_LUFS
            return
        }
        val relativeGate = meanSquareToLufs(energy / count) - RELATIVE_GATE_LU
        var gatedEnergy = 0.0
        var gatedCount = 0L
        for (b in 0 until BINS) {
            val centre = BIN_MIN_LUFS + (b + 0.5) * BIN_STEP_LU
            if (centre > relativeGate) {
                gatedEnergy += histogramEnergy[b]
                gatedCount += histogramCount[b]
            }
        }
        integratedLufs = if (gatedCount > 0L) meanSquareToLufs(gatedEnergy / gatedCount) else SILENCE_LUFS
    }

    companion object {
        /** Reported when there is nothing above the absolute gate (or no data yet). */
        const val SILENCE_LUFS = -120.0
        private const val MOMENTARY_BLOCKS = 4
        private const val SHORT_TERM_BLOCKS = 30
        private const val ABSOLUTE_GATE_LUFS = -70.0
        private const val RELATIVE_GATE_LU = 10.0
        private const val BIN_MIN_LUFS = -70.0
        private const val BIN_STEP_LU = 0.1
        private const val BINS = 1000
    }
}

/** Immutable normalizer configuration; time constants are precomputed for the control rate. */
internal class LoudnessConfig(
    val active: Boolean,
    val targetLufs: Double,
    val maxBoostDb: Double,
    val maxCutDb: Double,
    val integratorAlpha: Double,
    val upCoef: Double,
    val downCoef: Double,
) {
    companion object {
        fun from(p: LoudnessParameters, sampleRate: Int): LoudnessConfig {
            val s = p.sanitized()
            val hopSeconds = max(1, sampleRate / 100).toDouble() / sampleRate
            val response = s.responseSeconds.toDouble()
            return LoudnessConfig(
                active = s.enabled,
                targetLufs = s.targetLufs.toDouble(),
                maxBoostDb = s.maxBoostDb.toDouble(),
                maxCutDb = s.maxCutDb.toDouble(),
                integratorAlpha = 1.0 - exp(-hopSeconds / LoudnessNormalizer.INTEGRATION_SECONDS),
                upCoef = exp(-hopSeconds / response),
                downCoef = exp(-hopSeconds / (response / DOWN_SPEEDUP)),
            )
        }

        private const val DOWN_SPEEDUP = 4.0
    }
}

/**
 * Dynamic loudness management: a slow, gated automatic gain control towards a K-weighted target.
 *
 * Every 10 ms the K-weighted power of the block is averaged into a 1 s leaky integrator (blocks
 * quieter than -65 LUFS are not integrated, so silence neither dilutes nor resets the estimate).
 * The wanted gain is `target - loudness`, limited to [-maxCut, +maxBoost]. While the integrated
 * loudness is below -50 LUFS the gain is held, so the normalizer never pumps noise up. The gain
 * moves with one pole (upward at `responseSeconds`, downward four times faster) and is applied
 * with a per-sample linear ramp, so there is no zipper noise.
 *
 * This is not a limiter: it does not guarantee any peak level. The always-on limiter does.
 */
class LoudnessNormalizer(private val sampleRate: Int) {
    private val kWeighting = KWeighting(sampleRate)
    private val hop = max(1, sampleRate / 100)

    private var active = false
    private var target = -16.0
    private var maxBoost = 6.0
    private var maxCut = 12.0
    private var integratorAlpha = 0.0
    private var upCoef = 0.0
    private var downCoef = 0.0

    private var accumulated = 0.0
    private var accumulatedFrames = 0
    private var power = 0.0
    private var updates = 0L
    private var gainDb = 0.0
    private var gain = 1.0
    private var gainStep = 0.0

    @Volatile
    var currentGainDb: Double = 0.0
        private set

    /** Integrated K-weighted loudness the controller currently acts on (SILENCE_LUFS if none). */
    @Volatile
    var controlLufs: Double = LoudnessMeter.SILENCE_LUFS
        private set

    internal fun configure(c: LoudnessConfig) {
        val wasActive = active
        active = c.active
        target = c.targetLufs
        maxBoost = c.maxBoostDb
        maxCut = c.maxCutDb
        integratorAlpha = c.integratorAlpha
        upCoef = c.upCoef
        downCoef = c.downCoef
        if (active && !wasActive) reset()
    }

    fun reset() {
        kWeighting.reset()
        accumulated = 0.0; accumulatedFrames = 0
        power = 0.0; updates = 0L
        gainDb = 0.0; gain = 1.0; gainStep = 0.0
        currentGainDb = 0.0
        controlLufs = LoudnessMeter.SILENCE_LUFS
    }

    fun process(buf: FloatArray, frames: Int) {
        if (!active) return
        var i = 0
        while (i < frames) {
            val l = buf[i * 2]
            val r = buf[i * 2 + 1]
            val yl = kWeighting.tickLeft(l.toDouble())
            val yr = kWeighting.tickRight(r.toDouble())
            accumulated += yl * yl + yr * yr
            gain += gainStep
            buf[i * 2] = (l * gain).toFloat()
            buf[i * 2 + 1] = (r * gain).toFloat()
            accumulatedFrames++
            if (accumulatedFrames == hop) updateControl()
            i++
        }
        kWeighting.flush()
    }

    private fun updateControl() {
        val hopPower = accumulated / hop
        accumulated = 0.0
        accumulatedFrames = 0

        if (meanSquareToLufs(hopPower) > SIGNAL_GATE_LUFS) {
            updates++
            val a = max(integratorAlpha, 1.0 / updates) // fast start: plain average until the window is full
            power += a * (hopPower - power)
        }
        val loudness = meanSquareToLufs(power)
        controlLufs = loudness

        var desired = gainDb // hold while gated
        if (loudness > CONTROL_GATE_LUFS) {
            desired = (target - loudness).coerceIn(-maxCut, maxBoost)
        }
        val coef = if (desired < gainDb) downCoef else upCoef
        gainDb = (desired + coef * (gainDb - desired)).coerceIn(-maxCut, maxBoost)
        currentGainDb = gainDb

        val newGain = dbToLinear(gainDb)
        gainStep = (newGain - gain) / hop
    }

    companion object {
        internal const val INTEGRATION_SECONDS = 1.0
        private const val SIGNAL_GATE_LUFS = -65.0
        private const val CONTROL_GATE_LUFS = -50.0
    }
}
