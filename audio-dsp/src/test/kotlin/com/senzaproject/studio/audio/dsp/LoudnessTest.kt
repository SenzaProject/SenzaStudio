package com.senzaproject.studio.audio.dsp

import com.senzaproject.studio.audio.engine.LoudnessParameters
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessTest {
    private fun measure(input: FloatArray, block: Int = 480): LoudnessMeter {
        val m = LoudnessMeter(FS)
        runBlocks(input, block) { buf, n -> m.process(buf, n) }
        return m
    }

    private fun amp(db: Double) = Math.pow(10.0, db / 20.0)

    @Test
    fun kWeightingMatchesTheItuCoefficientsAt48k() {
        val s = KWeighting.shelfCoefficients(48_000)
        assertEquals(1.53512485958697, s.b0, 1e-9)
        assertEquals(-2.69169618940638, s.b1, 1e-9)
        assertEquals(1.19839281085285, s.b2, 1e-9)
        assertEquals(-1.69065929318241, s.a1, 1e-9)
        assertEquals(0.73248077421585, s.a2, 1e-9)
        val h = KWeighting.highPassCoefficients(48_000)
        assertEquals(-1.99004745483398, h.a1, 1e-9)
        assertEquals(0.99007225036621, h.a2, 1e-9)
    }

    @Test
    fun ebuReferenceToneReadsMinus23Lufs() {
        val a = amp(-23.0)
        val m = measure(stereoSine(1_000.0, a, a, 5.0))
        assertEquals(-23.0, m.momentaryLufs, 0.1)
        assertEquals(-23.0, m.shortTermLufs, 0.1)
        assertEquals(-23.0, m.integratedLufs, 0.1)
    }

    @Test
    fun oneChannelIsThreeLuAboveHalfPower() {
        val a = amp(-20.0)
        assertEquals(-23.0, measure(stereoSine(1_000.0, a, 0.0, 5.0)).integratedLufs, 0.1)
    }

    @Test
    fun frequencyWeightingMatchesTheReference() {
        val a = amp(-20.0)
        assertEquals(-21.83, measure(stereoSine(100.0, a, a, 5.0)).integratedLufs, 0.1)
        assertEquals(-16.65, measure(stereoSine(10_000.0, a, a, 5.0)).integratedLufs, 0.1)
    }

    @Test
    fun relativeGateIgnoresMuchQuieterPassages() {
        val loud = stereoSine(1_000.0, amp(-20.0), amp(-20.0), 10.0)
        val quiet = stereoSine(1_000.0, amp(-45.0), amp(-45.0), 10.0)
        val m = measure(loud + quiet)
        assertEquals(-20.0, m.integratedLufs, 0.3)
        assertEquals(-45.0, m.momentaryLufs, 0.1)
        assertEquals(-45.0, m.shortTermLufs, 0.1)
    }

    @Test
    fun absoluteGateReportsSilenceBelowMinus70() {
        val a = amp(-80.0)
        assertEquals(LoudnessMeter.SILENCE_LUFS, measure(stereoSine(1_000.0, a, a, 6.0)).integratedLufs, 0.0)
    }

    @Test
    fun blockSizeDoesNotChangeTheReadings() {
        val input = stereoNoise(0.2f, FS * 4)
        val a = measure(input, 480)
        val b = measure(input, 333)
        assertEquals(a.integratedLufs, b.integratedLufs, 1e-9)
        assertEquals(a.shortTermLufs, b.shortTermLufs, 1e-9)
        assertEquals(a.truePeakDb, b.truePeakDb, 1e-9)
    }

    @Test
    fun truePeakFindsPeaksBetweenSamples() {
        // fs/4 sine at 45 degrees: every sample is +-0.707 but the waveform reaches 1.0.
        val n = 9_600
        val buf = FloatArray(n * 2)
        for (i in 0 until n) {
            val fade = if (i < 960) 0.5 - 0.5 * cos(PI * i / 960) else 1.0
            val v = (fade * sin(PI * i / 2 + PI / 4)).toFloat()
            buf[i * 2] = v; buf[i * 2 + 1] = v
        }
        val m = measure(buf)
        assertEquals(-3.01, m.samplePeakDb, 0.05)
        assertTrue("TP=${m.truePeakDb}", m.truePeakDb in -0.35..0.35)
        assertTrue(m.truePeakDb >= m.samplePeakDb)
    }

    @Test
    fun truePeakIsNeverBelowSamplePeak() {
        val m = measure(noise(0.8f, FS))
        assertTrue(m.truePeakDb >= m.samplePeakDb - 1e-9)
    }

    // ---- normalizer ----

    private fun normalizer(p: LoudnessParameters) =
        LoudnessNormalizer(FS).also { it.configure(LoudnessConfig.from(p, FS)) }

    private fun runNormalizer(n: LoudnessNormalizer, input: FloatArray) =
        runBlocks(input) { buf, f -> n.process(buf, f) }

    private val fast = LoudnessParameters(
        enabled = true, targetLufs = -20f, maxBoostDb = 12f, maxCutDb = 24f, responseSeconds = 1f,
    )

    @Test
    fun normalizerRaisesQuietMaterialToTheTarget() {
        val input = stereoNoise(0.04f, FS * 20) // about -26.6 LUFS
        val n = normalizer(fast)
        val out = runNormalizer(n, input)
        val tail = measure(out.copyOfRange(out.size - FS * 5 * 2, out.size))
        assertEquals(-20.0, tail.integratedLufs, 0.6)
        assertTrue("gain=${n.currentGainDb}", n.currentGainDb in 5.0..8.0)
    }

    @Test
    fun normalizerLowersLoudMaterialToTheTarget() {
        val input = stereoNoise(0.3f, FS * 20) // about -9 LUFS
        val out = runNormalizer(normalizer(fast), input)
        val tail = measure(out.copyOfRange(out.size - FS * 5 * 2, out.size))
        assertEquals(-20.0, tail.integratedLufs, 0.6)
    }

    @Test
    fun normalizerNeverExceedsItsBoostLimit() {
        val p = LoudnessParameters(enabled = true, targetLufs = -16f, maxBoostDb = 6f, responseSeconds = 1f)
        val n = normalizer(p)
        runNormalizer(n, stereoNoise(0.005f, FS * 15)) // about -44.7 LUFS, above the -50 gate
        assertTrue("gain=${n.currentGainDb}", n.currentGainDb in 5.5..6.0 + 1e-9)
    }

    @Test
    fun normalizerHoldsItsGainThroughSilence() {
        val n = normalizer(fast)
        runNormalizer(n, stereoNoise(0.3f, FS * 10))
        val before = n.currentGainDb
        runNormalizer(n, stereoNoise(1e-5f, FS * 5))
        assertEquals(before, n.currentGainDb, 0.3)
    }

    @Test
    fun disabledNormalizerIsIdentity() {
        val input = stereoNoise(0.3f, 10_000)
        val out = runNormalizer(normalizer(fast.copy(enabled = false)), input)
        for (i in input.indices) assertEquals(input[i], out[i], 0f)
    }

    @Test
    fun normalizerGainChangesAreSmooth() {
        val n = normalizer(fast)
        val out = runNormalizer(n, FloatArray(FS * 6 * 2) { 0.02f })
        var worst = 0f
        for (f in 1 until FS * 6) worst = maxOf(worst, abs(out[f * 2] - out[(f - 1) * 2]))
        assertTrue("max step=$worst", worst < 1e-4f)
    }
}
