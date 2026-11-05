package com.senzaproject.studio.audio.dsp

import com.senzaproject.studio.audio.engine.BassParameters
import com.senzaproject.studio.audio.engine.EngineParameters
import com.senzaproject.studio.audio.engine.EqBand
import com.senzaproject.studio.audio.engine.EqParameters
import com.senzaproject.studio.audio.engine.FilterType
import com.senzaproject.studio.audio.engine.SPECTRUM_BANDS
import com.senzaproject.studio.audio.engine.SpatialMode
import com.senzaproject.studio.audio.engine.SpatialParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteringTest {
    private fun spectrumOf(input: FloatArray): FloatArray {
        val sa = SpectrumAnalyzer(FS)
        runBlocks(input) { b, n -> sa.process(b, n) }
        return FloatArray(SPECTRUM_BANDS).also { sa.snapshot(it) }
    }

    private fun argMax(a: FloatArray): Int = a.indices.maxByOrNull { a[it] }!!

    @Test
    fun levelMeterReadsPeakAndRms() {
        val m = LevelMeter(FS)
        runBlocks(stereoSine(1_000.0, 0.5, 0.25, 2.0)) { b, n -> m.process(b, n) }
        assertEquals(dbfs(0.5), m.peakDbLeft, 0.5)
        assertEquals(dbfs(0.25), m.peakDbRight, 0.5)
        assertEquals(dbfs(0.5 / Math.sqrt(2.0)), m.rmsDbLeft, 0.2)
        assertEquals(dbfs(0.25 / Math.sqrt(2.0)), m.rmsDbRight, 0.2)
    }

    @Test
    fun levelMeterPeakFallsInSilence() {
        val m = LevelMeter(FS)
        runBlocks(stereoSine(1_000.0, 0.5, 0.5, 0.5)) { b, n -> m.process(b, n) }
        val before = m.peakDbLeft
        runBlocks(FloatArray(FS * 2)) { b, n -> m.process(b, n) } // 1 s of silence
        assertEquals(before - 20.0, m.peakDbLeft, 1.0)
    }

    @Test
    fun spectrumPeaksInTheBandOfATone() {
        val sa = SpectrumAnalyzer(FS)
        for (f in listOf(100.0, 1_000.0, 8_000.0)) {
            val s = spectrumOf(stereoSine(f, 0.5, 0.5, 1.0))
            assertEquals("tone $f", sa.bandIndexFor(f), argMax(s))
        }
    }

    @Test
    fun spectrumLevelOfAToneIsCloseToItsAmplitude() {
        val s = spectrumOf(stereoSine(1_000.0, 0.5, 0.5, 1.0))
        val level = s.max().toDouble()
        assertTrue("level=$level", level in dbfs(0.5) - 1.6..dbfs(0.5) + 0.3)
    }

    @Test
    fun spectrumOfSilenceIsAtTheFloor() {
        val s = spectrumOf(FloatArray(FS * 2))
        assertTrue(s.all { it <= -110f })
    }

    @Test
    fun spectrumBandsAreOrderedAndCoverTheAudibleRange() {
        val sa = SpectrumAnalyzer(FS)
        var previous = 0.0
        for (k in 0 until SPECTRUM_BANDS) {
            val c = sa.bandCenterHz(k)
            assertTrue(c > previous)
            previous = c
        }
        assertTrue(sa.bandCenterHz(0) in 20.0..40.0)
        assertTrue(sa.bandCenterHz(SPECTRUM_BANDS - 1) in 14_000.0..21_600.0)
        assertEquals(0, sa.bandIndexFor(5.0))
        assertEquals(SPECTRUM_BANDS - 1, sa.bandIndexFor(30_000.0))
    }

    @Test
    fun spectrumWorksAtOtherSampleRates() {
        for (rate in listOf(22_050, 44_100, 96_000)) {
            val sa = SpectrumAnalyzer(rate)
            val input = stereoSine(1_000.0, 0.5, 0.5, 1.0, rate)
            runBlocks(input) { b, n -> sa.process(b, n) }
            val out = FloatArray(SPECTRUM_BANDS).also { sa.snapshot(it) }
            assertEquals("rate $rate", sa.bandIndexFor(1_000.0), argMax(out))
        }
    }

    @Test
    fun spectrumFallsAfterTheSignalStops() {
        val sa = SpectrumAnalyzer(FS)
        runBlocks(stereoSine(1_000.0, 0.5, 0.5, 1.0)) { b, n -> sa.process(b, n) }
        val loud = FloatArray(SPECTRUM_BANDS).also { sa.snapshot(it) }.max()
        runBlocks(FloatArray(FS * 2)) { b, n -> sa.process(b, n) } // 1 s silence
        val after = FloatArray(SPECTRUM_BANDS).also { sa.snapshot(it) }.max()
        assertTrue("loud=$loud after=$after", after < loud - 20f)
    }

    // ---- EQ response ----

    @Test
    fun flatOrDisabledEqCurveIsZero() {
        assertTrue(EqResponse.curveDb(EqParameters(enabled = true)).all { it == 0f })
        val boosted = EqParameters(enabled = false, bands = EqParameters.defaultBands().map { it.copy(gainDb = 9f) })
        assertTrue(EqResponse.curveDb(boosted).all { it == 0f })
    }

    @Test
    fun peakingBandShowsItsGainAtItsFrequency() {
        val eq = EqParameters(enabled = true, bands = listOf(EqBand(1_000f, 6f, 1f)))
        val curve = EqResponse.curveDb(eq, points = 128)
        val centreIndex = Math.round(127 * Math.log(50.0) / Math.log(1_000.0)).toInt() // 1 kHz on a 20 Hz..20 kHz log axis
        assertEquals(6.0, curve[centreIndex].toDouble(), 0.3)
        assertEquals(0.0, curve.first().toDouble(), 0.2)
        assertEquals(0.0, curve.last().toDouble(), 0.5)
    }

    @Test
    fun shelfAndTwoBandsAdd() {
        val eq = EqParameters(
            enabled = true,
            bands = listOf(EqBand(100f, 6f, 0.7f, FilterType.LOW_SHELF), EqBand(8_000f, -4f, 1f)),
        )
        val c = EqResponse.curveDb(eq, points = 64)
        assertTrue(c.first() > 5f)
        assertTrue(c.min() < -3f)
    }

    // ---- chain bypass ----

    @Test
    fun bypassSkipsEveryModuleButKeepsTheLimiter() {
        val busy = EngineParameters(
            masterGainDb = 6f,
            eq = EqParameters(enabled = true, bands = EqParameters.defaultBands().map { it.copy(gainDb = 12f) }),
            bass = BassParameters(true, 12f, 1f, 1f),
            spatial = SpatialParameters(true, SpatialMode.IMMERSIVE, 1f, 2f),
        )
        val input = stereoNoise(0.3f, 20_000)
        val chain = DspChain(FS).also { it.configure(busy.copy(bypass = true)) }
        val bypassed = runBlocks(input) { b, n -> chain.process(b, n) }
        val reference = DspChain(FS)
        val expected = runBlocks(input) { b, n -> reference.process(b, n) }
        for (i in expected.indices) assertEquals(expected[i], bypassed[i], 0f)
    }
}
