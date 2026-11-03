package com.senzaproject.studio.audio.dsp

import kotlin.math.abs
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicsTest {
    @Test
    fun staticGainComputerMatchesTheory() {
        val c = Compressor(FS).apply { configure(-20.0, 4.0, 1.0, 50.0, 0.0, 0.0) }
        assertEquals(-30.0, c.computeOutputDb(-30.0), 1e-9)       // below threshold: unity
        assertEquals(-20.0, c.computeOutputDb(-20.0), 1e-9)       // at threshold
        assertEquals(-16.5, c.computeOutputDb(-6.0), 1e-9)        // -20 + 14/4
    }

    @Test
    fun softKneeIsContinuousAtItsEdges() {
        val c = Compressor(FS).apply { configure(-20.0, 4.0, 1.0, 50.0, 6.0, 0.0) }
        // Lower edge (over = -3): unity. Upper edge (over = +3): meets the ratio line T + over/R.
        assertEquals(-23.0, c.computeOutputDb(-23.0), 1e-9)
        assertEquals(-19.25, c.computeOutputDb(-17.0), 1e-9)
        assertEquals(-20.0 + 3.0001 / 4.0, c.computeOutputDb(-16.9999), 1e-3)
    }

    @Test
    fun steadyStateReductionMatchesTheory() {
        // DC at 0.5 (-6.02 dBFS): expected output level = -20 + (-6.02 + 20)/4, so GR = -10.485 dB.
        val c = Compressor(FS).apply { configure(-20.0, 4.0, 1.0, 50.0, 0.0, 0.0) }
        val buf = FloatArray(FS * 2) { 0.5f }
        c.process(buf, FS)
        val gainDb = 20 * log10(buf[buf.size - 2] / 0.5)
        assertEquals(-10.485, gainDb, 0.05)
    }

    @Test
    fun belowThresholdSignalIsUntouched() {
        val c = Compressor(FS).apply { configure(-10.0, 4.0, 1.0, 50.0, 0.0, 0.0) }
        val buf = FloatArray(4_800 * 2) { 0.05f }
        c.process(buf, 4_800)
        assertTrue(buf.all { abs(it - 0.05f) < 1e-6f })
    }

    @Test
    fun makeupGainIsApplied() {
        val c = Compressor(FS).apply { configure(0.0, 1.0, 1.0, 50.0, 0.0, 6.0) }
        val buf = FloatArray(FS * 2) { 0.1f }
        c.process(buf, FS)
        assertEquals(6.0, 20 * log10(buf[buf.size - 2] / 0.1), 0.05)
    }

    @Test
    fun limiterNeverExceedsCeilingOnLoudSine() {
        val lim = Limiter(FS).apply { configure(-1.0, 80.0) }
        val ceiling = Math.pow(10.0, -1.0 / 20).toFloat()
        val buf = sine(440.0, 4.0, 1.0) // +12 dBFS
        lim.process(buf, buf.size / 2)
        assertTrue("peak=${peak(buf)}", peak(buf) <= ceiling + 1e-6f)
    }

    @Test
    fun limiterNeverExceedsCeilingOnBurstsAndNoise() {
        val lim = Limiter(FS).apply { configure(-0.3, 80.0) }
        val ceiling = Math.pow(10.0, -0.3 / 20).toFloat()
        val buf = noise(1.0f, FS)
        // isolated full-scale-times-ten spikes
        for (k in 0 until 20) { buf[k * 2_000 * 2] = 10f; buf[k * 2_000 * 2 + 1] = -10f }
        lim.process(buf, buf.size / 2)
        assertTrue("peak=${peak(buf)}", peak(buf) <= ceiling + 1e-6f)
    }

    @Test
    fun limiterIsTransparentBelowCeilingAfterItsLatency() {
        val lim = Limiter(FS)
        val input = noise(0.5f, 10_000)
        val out = input.copyOf()
        lim.process(out, 10_000)
        val d = lim.latencySamples
        for (i in d until 10_000) {
            assertEquals(input[(i - d) * 2], out[i * 2], 1e-6f)
            assertEquals(input[(i - d) * 2 + 1], out[i * 2 + 1], 1e-6f)
        }
    }

    @Test
    fun limiterBehavesIdenticallyWhenBlockSizeChanges() {
        val input = sine(100.0, 2.0, 0.5)
        val a = input.copyOf().also { Limiter(FS).process(it, it.size / 2) }
        val b = input.copyOf()
        val l2 = Limiter(FS)
        var f = 0; val total = b.size / 2
        val tmp = FloatArray(2 * 333)
        while (f < total) {
            val n = minOf(333, total - f)
            System.arraycopy(b, f * 2, tmp, 0, n * 2)
            l2.process(tmp, n)
            System.arraycopy(tmp, 0, b, f * 2, n * 2)
            f += n
        }
        for (i in a.indices) assertEquals(a[i], b[i], 1e-6f)
    }

    @Test
    fun limiterWorksBySmoothGainNotByHardClamping() {
        val lim = Limiter(FS).apply { configure(-1.0, 80.0) }
        val c = Math.pow(10.0, -1.0 / 20).toFloat()
        val buf = sine(440.0, 4.0, 1.0)
        lim.process(buf, buf.size / 2)
        var atClamp = 0
        val from = (FS * 0.02).toInt()
        for (i in from until buf.size / 2) if (abs(buf[i * 2]) >= c) atClamp++
        // A hard clipper would pin a large share of samples to the ceiling; look-ahead gain should not.
        assertTrue("clamped=$atClamp", atClamp < (buf.size / 2 - from) / 50)
    }
}
