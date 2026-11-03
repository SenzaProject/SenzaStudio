package com.senzaproject.studio.audio.dsp

import com.senzaproject.studio.audio.engine.BassParameters
import com.senzaproject.studio.audio.engine.FilterType
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BassTest {
    private fun bassOf(p: BassParameters) = BassEnhancer(FS).also { it.configure(BassConfig.from(p, FS)) }

    private fun run(p: BassParameters, input: FloatArray, block: Int = 480): FloatArray {
        val b = bassOf(p)
        return runBlocks(input, block) { buf, n -> b.process(buf, n) }
    }

    /** Level of [freq] relative to the fundamental [f0], in dB, over the second second of [out]. */
    private fun relativeDb(out: FloatArray, f0: Double, freq: Double): Double =
        dbfs(toneAmplitude(out, 0, freq, FS, FS) / toneAmplitude(out, 0, f0, FS, FS))

    @Test
    fun disabledOrNeutralBassIsBitTransparent() {
        val input = noise(0.5f, 10_000)
        val off = run(BassParameters(enabled = false, subBassDb = 12f, harmonics = 1f, punch = 1f), input)
        val neutral = run(BassParameters(enabled = true), input)
        for (i in input.indices) {
            assertEquals(input[i], off[i], 0f)
            assertEquals(input[i], neutral[i], 0f)
        }
    }

    @Test
    fun subBassShelfFollowsItsDesign() {
        val design = BiquadDesign.design(FilterType.LOW_SHELF, FS, BassConfig.SHELF_HZ, 6f, BUTTERWORTH_Q)
        for (freq in listOf(40.0, 1_000.0)) {
            val input = sine(freq, 0.2, 2.0)
            val out = run(BassParameters(enabled = true, subBassDb = 6f), input)
            val measured = dbfs(toneAmplitude(out, 0, freq, FS, FS) / 0.2)
            assertEquals("freq=$freq", design.magnitudeDb(freq, FS.toDouble()), measured, 0.05)
        }
    }

    @Test
    fun harmonicsAreGeneratedAndTheFundamentalIsPreserved() {
        val out = run(BassParameters(enabled = true, harmonics = 1f), sine(50.0, 0.25, 3.0))
        val fundamentalDb = dbfs(toneAmplitude(out, 0, 50.0, FS, FS) / 0.25)
        assertTrue("fundamental=$fundamentalDb", fundamentalDb in -2.0..0.5)
        val h3 = relativeDb(out, 50.0, 150.0)
        val h2 = relativeDb(out, 50.0, 100.0)
        assertTrue("H3=$h3", h3 in -10.0..-3.5)
        assertTrue("H2=$h2", h2 in -24.0..-10.0)
    }

    @Test
    fun harmonicAmountControlsHarmonicLevel() {
        val full = run(BassParameters(enabled = true, harmonics = 1f), sine(50.0, 0.25, 3.0))
        val half = run(BassParameters(enabled = true, harmonics = 0.5f), sine(50.0, 0.25, 3.0))
        assertTrue(relativeDb(full, 50.0, 150.0) > relativeDb(half, 50.0, 150.0) + 6.0)
    }

    @Test
    fun harmonicRatioDoesNotDependOnInputLevel() {
        val quiet = run(BassParameters(enabled = true, harmonics = 1f), sine(50.0, 0.05, 3.0))
        val loud = run(BassParameters(enabled = true, harmonics = 1f), sine(50.0, 0.5, 3.0))
        assertEquals(relativeDb(quiet, 50.0, 150.0), relativeDb(loud, 50.0, 150.0), 1.5)
    }

    @Test
    fun punchEmphasisesAttacksButNotASteadyTone() {
        val hits = kick()
        val plain = run(BassParameters(enabled = true), hits)
        val punchy = run(BassParameters(enabled = true, punch = 1f), hits)
        val window = FS / 2 * 2 // first half second, interleaved
        val gainDb = dbfs(peak(punchy.copyOfRange(0, window)).toDouble() / peak(plain.copyOfRange(0, window)))
        assertTrue("attack gain=$gainDb dB", gainDb in 5.0..10.0)

        val steady = sine(60.0, 0.3, 3.0)
        val out = run(BassParameters(enabled = true, punch = 1f), steady)
        val steadyDb = dbfs(toneAmplitude(out, 0, 60.0, FS, FS) / 0.3)
        assertEquals(0.0, steadyDb, 0.7)
    }

    @Test
    fun worstCaseSettingsStayFinite() {
        val p = BassParameters(enabled = true, subBassDb = 12f, harmonics = 1f, punch = 1f, crossoverHz = 200f)
        val out = run(p, noise(1.0f, FS * 2))
        assertTrue(out.all { it.isFinite() })
        assertTrue("peak=${peak(out)}", peak(out) < 100f)
    }

    @Test
    fun blockSizeDoesNotChangeTheResult() {
        val p = BassParameters(enabled = true, subBassDb = 6f, harmonics = 0.7f, punch = 0.6f)
        val input = noise(0.3f, FS)
        val a = run(p, input, 480)
        val b = run(p, input, 333)
        for (i in a.indices) assertEquals(a[i], b[i], 1e-6f)
    }

    @Test
    fun sanitizedParametersNeverReachTheDspOutOfRange() {
        val c = BassConfig.from(BassParameters(enabled = true, subBassDb = Float.NaN, harmonics = 9f, punch = -3f, crossoverHz = 5f), FS)
        assertEquals(1.0, c.harmonics, 0.0)
        assertEquals(0.0, c.punch, 0.0)
        assertTrue(c.shelf == null)
        assertTrue(abs(c.lowPass.b0) < 1.0 && c.lowPass.isStable)
    }
}
