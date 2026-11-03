package com.senzaproject.studio.audio.dsp

import com.senzaproject.studio.audio.engine.ImagingParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoImagingTest {
    private fun run(p: ImagingParameters, input: FloatArray, block: Int = 480): FloatArray {
        val imager = StereoImager(FS).also { it.configure(ImagingConfig.from(p, FS)) }
        return runBlocks(input, block) { buf, n -> imager.process(buf, n) }
    }

    private fun toneDb(out: FloatArray, channel: Int, freq: Double, reference: Double): Double =
        dbfs(toneAmplitude(out, channel, freq, FS, FS) / reference)

    @Test
    fun neutralImagerIsTransparent() {
        val input = stereoNoise(0.5f, 10_000)
        val out = run(ImagingParameters(enabled = true), input)
        for (i in input.indices) assertEquals(input[i], out[i], 1e-6f)
    }

    @Test
    fun disabledImagerIgnoresItsSettings() {
        val input = stereoNoise(0.5f, 10_000)
        val out = run(ImagingParameters(enabled = false, midGainDb = 12f, sideGainDb = -12f, balance = 1f, monoBelowHz = 300f), input)
        for (i in input.indices) assertEquals(input[i], out[i], 0f)
    }

    @Test
    fun midGainScalesAMonoSignalAndLeavesSideAlone() {
        val mono = stereoSine(1_000.0, 0.2, 0.2, 2.0)
        val out = run(ImagingParameters(enabled = true, midGainDb = 6f), mono)
        assertEquals(6.0, toneDb(out, 0, 1_000.0, 0.2), 0.05)

        val side = stereoSine(1_000.0, 0.2, -0.2, 2.0)
        val sideOut = run(ImagingParameters(enabled = true, midGainDb = 6f), side)
        assertEquals(0.0, toneDb(sideOut, 0, 1_000.0, 0.2), 0.05)
    }

    @Test
    fun sideGainScalesOnlyTheSideSignal() {
        val side = stereoSine(1_000.0, 0.2, -0.2, 2.0)
        val out = run(ImagingParameters(enabled = true, sideGainDb = -6f), side)
        assertEquals(-6.0, toneDb(out, 0, 1_000.0, 0.2), 0.05)

        val mono = stereoSine(1_000.0, 0.2, 0.2, 2.0)
        val monoOut = run(ImagingParameters(enabled = true, sideGainDb = -6f), mono)
        assertEquals(0.0, toneDb(monoOut, 0, 1_000.0, 0.2), 0.05)
    }

    @Test
    fun balanceOnlyAttenuatesTheOppositeChannel() {
        val input = stereoSine(1_000.0, 0.4, 0.4, 2.0)
        val left = run(ImagingParameters(enabled = true, balance = -0.5f), input)
        assertEquals(0.0, toneDb(left, 0, 1_000.0, 0.4), 0.01)
        assertEquals(-6.02, toneDb(left, 1, 1_000.0, 0.4), 0.02)

        val hardRight = run(ImagingParameters(enabled = true, balance = 1f), input)
        assertTrue(peak(hardRight.filterIndexed { i, _ -> i % 2 == 0 }.toFloatArray()) < 1e-6f)
        assertEquals(0.0, toneDb(hardRight, 1, 1_000.0, 0.4), 0.01)
    }

    @Test
    fun monoBelowRemovesLowFrequencySideOnly() {
        val p = ImagingParameters(enabled = true, monoBelowHz = 120f)
        val lowSide = run(p, stereoSine(30.0, 0.3, -0.3, 2.0))
        assertTrue("30 Hz side=${toneDb(lowSide, 0, 30.0, 0.3)}", toneDb(lowSide, 0, 30.0, 0.3) < -30.0)

        val highSide = run(p, stereoSine(500.0, 0.3, -0.3, 2.0))
        assertEquals(0.0, toneDb(highSide, 0, 500.0, 0.3), 0.1)

        val lowMid = run(p, stereoSine(30.0, 0.3, 0.3, 2.0))
        assertEquals(0.0, toneDb(lowMid, 0, 30.0, 0.3), 0.01)
    }

    @Test
    fun monoInputIsUnaffectedBySideGainAndMonoBelow() {
        val input = monoNoise(0.4f, 10_000)
        val out = run(ImagingParameters(enabled = true, sideGainDb = -12f, monoBelowHz = 200f), input)
        for (i in input.indices) assertEquals(input[i], out[i], 1e-6f)
    }

    @Test
    fun blockSizeDoesNotChangeTheResult() {
        val p = ImagingParameters(enabled = true, midGainDb = 3f, sideGainDb = 4f, balance = 0.3f, monoBelowHz = 150f)
        val input = stereoNoise(0.4f, FS)
        val a = run(p, input, 480)
        val b = run(p, input, 333)
        for (i in a.indices) assertEquals(a[i], b[i], 1e-6f)
    }

    @Test
    fun parametersAreSanitizedBeforeUse() {
        val c = ImagingConfig.from(ImagingParameters(enabled = true, midGainDb = 99f, balance = Float.NaN, monoBelowHz = 5f), FS)
        assertEquals(Math.pow(10.0, 12.0 / 20.0), c.midGain, 1e-9)
        assertEquals(1.0, c.gainLeft, 0.0)
        assertEquals(1.0, c.gainRight, 0.0)
        assertTrue(c.monoHighPass == null)
    }
}

class StereoMeterTest {
    private fun meterAfter(input: FloatArray): StereoMeter {
        val m = StereoMeter(FS)
        runBlocks(input) { buf, n -> m.process(buf, n) }
        return m
    }

    @Test
    fun identicalChannelsReadAsMono() {
        val m = meterAfter(monoNoise(0.3f, FS * 2))
        assertTrue(m.hasSignal)
        assertTrue("corr=${m.correlation}", m.correlation > 0.99)
        assertTrue("S/M=${m.sideToMidDb}", m.sideToMidDb < -40.0)
    }

    @Test
    fun invertedChannelsReadAsAntiPhase() {
        val mono = monoNoise(0.3f, FS * 2)
        val inverted = FloatArray(mono.size) { i -> if (i % 2 == 0) mono[i] else -mono[i] }
        val m = meterAfter(inverted)
        assertTrue("corr=${m.correlation}", m.correlation < -0.99)
        assertTrue("S/M=${m.sideToMidDb}", m.sideToMidDb > 40.0)
    }

    @Test
    fun independentChannelsReadAsUncorrelated() {
        val m = meterAfter(stereoNoise(0.3f, FS * 2))
        assertTrue("corr=${m.correlation}", kotlin.math.abs(m.correlation) < 0.1)
        assertEquals(0.0, m.sideToMidDb, 1.0)
    }

    @Test
    fun silenceIsReportedAsNoSignal() {
        val m = meterAfter(FloatArray(FS * 2))
        assertFalse(m.hasSignal)
        assertEquals(0.0, m.correlation, 0.0)
    }
}
