package com.senzaproject.studio.audio.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 changed the inner loops of the limiter, compressor, gain smoother and true-peak meter. These
 * tests keep a verbatim copy of the P5 algorithms and require the optimised versions to produce the
 * same audio, so "faster" can never silently mean "different".
 */
class OptimizationEquivalenceTest {

    /** Loud bursts, quiet passages and silence so every branch (above/in/below knee, release) runs. */
    private fun program(frames: Int): FloatArray {
        val out = stereoNoise(1f, frames, 11, 12)
        val segment = FS / 4
        for (f in 0 until frames) {
            val level = when ((f / segment) % 5) {
                0 -> 0.9f
                1 -> 0.2f
                2 -> 0.02f
                3 -> 0f
                else -> 1.6f
            }
            out[f * 2] *= level
            out[f * 2 + 1] *= level
        }
        return out
    }

    private fun blocked(input: FloatArray, block: Int, step: (FloatArray, Int) -> Unit): FloatArray =
        runBlocks(input, block, step)

    @Test
    fun limiterMatchesTheP5LimiterBitForBit() {
        val input = program(FS * 8) // well below the 2^19-sample resync interval
        val ref = ReferenceLimiter(FS).also { it.configure(-1.0, 120.0) }
        val opt = Limiter(FS).also { it.configure(-1.0, 120.0) }
        val expected = blocked(input, 477) { b, n -> ref.process(b, n) }
        val actual = blocked(input, 333) { b, n -> opt.process(b, n) }
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("sample $i", expected[i], actual[i], 0f)
    }

    @Test
    fun limiterStaysEquivalentAcrossTheLongTermResync() {
        val input = program(FS * 20) // 960k frames: crosses the resync interval at least once
        val ref = ReferenceLimiter(FS)
        val opt = Limiter(FS)
        val expected = blocked(input, 1024) { b, n -> ref.process(b, n) }
        val actual = blocked(input, 1024) { b, n -> opt.process(b, n) }
        var worst = 0f
        for (i in expected.indices) worst = max(worst, abs(expected[i] - actual[i]))
        assertTrue("worst difference $worst", worst < 1e-5f)
    }

    @Test
    fun compressorMatchesTheP5Compressor() {
        val input = program(FS * 8)
        for (knee in listOf(0.0, 6.0, 18.0)) {
            val ref = ReferenceCompressor(FS).also { it.configure(-24.0, 4.0, 5.0, 150.0, knee, 3.0) }
            val opt = Compressor(FS).also { it.configure(-24.0, 4.0, 5.0, 150.0, knee, 3.0) }
            val expected = blocked(input, 480) { b, n -> ref.process(b, n) }
            val actual = blocked(input, 480) { b, n -> opt.process(b, n) }
            var worst = 0f
            for (i in expected.indices) worst = max(worst, abs(expected[i] - actual[i]))
            assertTrue("knee=$knee worst difference $worst", worst < 1e-5f)
        }
    }

    @Test
    fun compressorReleaseEndsExactlyAtZeroReduction() {
        val c = Compressor(FS).apply { configure(-30.0, 8.0, 1.0, 50.0, 0.0, 0.0) }
        val loud = FloatArray(FS * 2) { 0.8f }
        c.process(loud, FS)
        assertTrue(c.gainReductionDb < -5.0)
        val quiet = FloatArray(FS * 4 * 2) { 0.0001f }
        c.process(quiet, FS * 4)
        assertEquals(0.0, c.gainReductionDb, 0.0)
        assertEquals(0.0001f, quiet[quiet.size - 1], 1e-9f)
    }

    @Test
    fun settledMasterGainIsExactlyUnityAtZeroDb() {
        val g = SmoothedGain(FS)
        g.setDb(0.0)
        val buf = noise(0.7f, 2_000)
        val copy = buf.copyOf()
        g.process(buf, 2_000)
        assertTrue(buf.contentEquals(copy))
    }

    @Test
    fun smoothedGainConvergesAndThenHoldsAConstantGain() {
        val g = SmoothedGain(FS)
        g.setDb(6.0)
        val warm = FloatArray(FS * 2) { 0.1f }
        g.process(warm, FS)
        val expected = (0.1 * dbToLinear(6.0)).toFloat()
        assertEquals(expected, warm[warm.size - 1], 1e-6f)
        val again = FloatArray(1_000 * 2) { 0.1f }
        g.process(again, 1_000)
        assertEquals(expected, again[0], 1e-6f)
        assertEquals(expected, again[again.size - 1], 1e-6f)
    }

    @Test
    fun truePeakStillFindsTheInterSamplePeakWithTheSkipLogic() {
        // 12 kHz at 48 kHz with a 45 degree phase: every sample sits at |sin| = 0.7071 (sample peak
        // 0.636), while the continuous waveform peaks at 0.9. The meter must still report ~0.9.
        val fs = 48_000
        val m = TruePeakMeter()
        for (i in 0 until fs) {
            val v = 0.9 * kotlin.math.sin(2 * Math.PI * 12_000.0 * i / fs + Math.PI / 4)
            m.push(v, v)
        }
        assertEquals(20 * Math.log10(0.9 * Math.sqrt(0.5)), m.samplePeakDb, 0.01)
        assertEquals(20 * Math.log10(0.9), m.truePeakDb, 0.35)
    }

    @Test
    fun truePeakIsNeverBelowTheSamplePeakAfterAReset() {
        val m = TruePeakMeter()
        val x = noise(0.8f, 20_000, seed = 3)
        for (i in 0 until 10_000) m.push(x[i * 2].toDouble(), x[i * 2 + 1].toDouble())
        m.resetPeaks()
        for (i in 10_000 until 20_000) m.push(x[i * 2].toDouble(), x[i * 2 + 1].toDouble())
        for (i in 0 until 12) m.push(0.0, 0.0) // flush the 6-sample group delay of the oversampler
        assertTrue("TP=${m.truePeakDb} SP=${m.samplePeakDb}", m.truePeakDb >= m.samplePeakDb - 1e-9)
    }
}
