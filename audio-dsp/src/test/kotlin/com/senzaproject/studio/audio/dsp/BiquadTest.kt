package com.senzaproject.studio.audio.dsp

import com.senzaproject.studio.audio.engine.FilterType
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiquadTest {
    private fun design(t: FilterType, f: Float, g: Float, q: Float) = BiquadDesign.design(t, FS, f, g, q)

    @Test
    fun peakingHitsRequestedGainAtCentre() {
        val c = design(FilterType.PEAKING, 1_000f, 6f, 1f)
        assertEquals(6.0, c.magnitudeDb(1_000.0, FS.toDouble()), 0.05)
        assertEquals(0.0, c.magnitudeDb(30.0, FS.toDouble()), 0.2)
        assertEquals(0.0, c.magnitudeDb(18_000.0, FS.toDouble()), 0.3)
    }

    @Test
    fun cutIsTheMirrorOfBoost() {
        val boost = design(FilterType.PEAKING, 2_000f, 9f, 1.4f)
        val cut = design(FilterType.PEAKING, 2_000f, -9f, 1.4f)
        for (f in listOf(200.0, 1_000.0, 2_000.0, 5_000.0)) {
            assertEquals(0.0, boost.magnitudeDb(f, FS.toDouble()) + cut.magnitudeDb(f, FS.toDouble()), 0.01)
        }
    }

    @Test
    fun shelvesReachFullGainInTheirBand() {
        val low = design(FilterType.LOW_SHELF, 200f, 6f, 0.707f)
        assertEquals(6.0, low.magnitudeDb(20.0, FS.toDouble()), 0.2)
        assertEquals(0.0, low.magnitudeDb(10_000.0, FS.toDouble()), 0.2)
        val high = design(FilterType.HIGH_SHELF, 4_000f, -6f, 0.707f)
        assertEquals(-6.0, high.magnitudeDb(18_000.0, FS.toDouble()), 0.3)
        assertEquals(0.0, high.magnitudeDb(100.0, FS.toDouble()), 0.2)
    }

    @Test
    fun butterworthPassFiltersAreMinus3dbAtCutoff() {
        val hp = design(FilterType.HIGH_PASS, 100f, 0f, 0.70710678f)
        val lp = design(FilterType.LOW_PASS, 8_000f, 0f, 0.70710678f)
        assertEquals(-3.01, hp.magnitudeDb(100.0, FS.toDouble()), 0.05)
        assertEquals(-3.01, lp.magnitudeDb(8_000.0, FS.toDouble()), 0.05)
        assertTrue(hp.magnitudeDb(20.0, FS.toDouble()) < -20.0)
        assertTrue(lp.magnitudeDb(20_000.0, FS.toDouble()) < -10.0)
    }

    @Test
    fun allDesignsAreStableAcrossTheParameterSpace() {
        for (type in FilterType.entries) {
            for (f in listOf(20f, 31.25f, 500f, 5_000f, 16_000f, 20_000f)) {
                for (g in listOf(-15f, 0f, 15f)) {
                    for (q in listOf(0.1f, 0.707f, 4f, 18f)) {
                        val c = design(type, f, g, q)
                        assertTrue("$type f=$f g=$g q=$q", c.isStable)
                        assertTrue(listOf(c.b0, c.b1, c.b2, c.a1, c.a2).all { it.isFinite() })
                    }
                }
            }
        }
    }

    @Test
    fun measuredGainMatchesAnalyticResponse() {
        val c = design(FilterType.PEAKING, 1_000f, 6f, 1f)
        val f = StereoBiquad().apply { set(c) }
        val buf = sine(1_000.0, 0.1, 1.0)
        f.process(buf, buf.size / 2)
        val measured = 20 * log10(rmsLeft(buf, FS / 2) / rmsLeft(sine(1_000.0, 0.1, 1.0), FS / 2))
        assertEquals(c.magnitudeDb(1_000.0, FS.toDouble()), measured, 0.05)
    }

    @Test
    fun stateIsKeptAcrossBlocks() {
        val c = design(FilterType.LOW_PASS, 2_000f, 0f, 0.707f)
        val whole = sine(500.0, 0.5, 0.1).also { StereoBiquad().apply { set(c) }.process(it, it.size / 2) }
        val split = sine(500.0, 0.5, 0.1)
        val f = StereoBiquad().apply { set(c) }
        val half = split.size / 4 * 2
        val a = split.copyOfRange(0, half); val b = split.copyOfRange(half, split.size)
        f.process(a, a.size / 2); f.process(b, b.size / 2)
        val joined = a + b
        for (i in whole.indices) assertEquals(whole[i], joined[i], 1e-6f)
    }
}
