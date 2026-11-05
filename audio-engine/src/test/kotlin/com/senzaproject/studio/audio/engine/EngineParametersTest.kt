package com.senzaproject.studio.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineParametersTest {
    @Test
    fun defaultEqIsTenFlatBands() {
        val bands = EqParameters().bands
        assertEquals(10, bands.size)
        assertTrue(bands.all { it.gainDb == 0f })
        assertEquals(31.25f, bands.first().frequencyHz, 0f)
        assertEquals(16_000f, bands.last().frequencyHz, 0f)
    }

    @Test
    fun sanitizeClampsOutOfRangeValues() {
        val wild = EngineParameters(
            masterGainDb = 99f,
            eq = EqParameters(bands = listOf(EqBand(frequencyHz = 5f, gainDb = -80f, q = 500f))),
            spatial = SpatialParameters(intensity = 3f, stereoWidth = -1f),
        ).sanitized()
        assertEquals(EngineParameters.MAX_MASTER_GAIN_DB, wild.masterGainDb, 0f)
        assertEquals(EqBand.MIN_FREQUENCY_HZ, wild.eq.bands[0].frequencyHz, 0f)
        assertEquals(-EqBand.MAX_BAND_GAIN_DB, wild.eq.bands[0].gainDb, 0f)
        assertEquals(EqBand.MAX_Q, wild.eq.bands[0].q, 0f)
        assertEquals(1f, wild.spatial.intensity, 0f)
        assertEquals(0f, wild.spatial.stereoWidth, 0f)
    }

    @Test
    fun sanitizeRemovesNanAndInfinity() {
        val p = EngineParameters(
            masterGainDb = Float.NaN,
            spatial = SpatialParameters(intensity = Float.POSITIVE_INFINITY, stereoWidth = Float.NaN),
        ).sanitized()
        assertEquals(0f, p.masterGainDb, 0f)
        assertEquals(0.5f, p.spatial.intensity, 0f)
        assertEquals(1f, p.spatial.stereoWidth, 0f)
    }

    @Test
    fun sanitizeLimitsBandCount() {
        val many = EqParameters(bands = List(40) { EqBand(frequencyHz = 1_000f) }).sanitized()
        assertEquals(EqParameters.MAX_BANDS, many.bands.size)
    }

    @Test
    fun dynamicsAreSanitizedAndLimiterCannotExceedFullScale() {
        val p = EngineParameters(
            dynamics = DynamicsParameters(
                compressor = CompressorParameters(thresholdDb = 10f, ratio = 100f, attackMs = Float.NaN),
                limiter = LimiterParameters(ceilingDb = 6f),
            ),
        ).sanitized()
        assertEquals(0f, p.dynamics.compressor.thresholdDb, 0f)
        assertEquals(20f, p.dynamics.compressor.ratio, 0f)
        assertEquals(10f, p.dynamics.compressor.attackMs, 0f)
        assertEquals(0f, p.dynamics.limiter.ceilingDb, 0f)
        assertEquals(4, p.schemaVersion)
    }

    @Test
    fun advancedModulesAreSanitized() {
        val p = EngineParameters(
            bass = BassParameters(subBassDb = 99f, harmonics = 5f, punch = Float.NaN, crossoverHz = 1f),
            imaging = ImagingParameters(midGainDb = -99f, balance = 4f, monoBelowHz = 5f),
            dynamics = DynamicsParameters(loudness = LoudnessParameters(targetLufs = 0f, maxBoostDb = 50f, responseSeconds = 0f)),
        ).sanitized()
        assertEquals(12f, p.bass.subBassDb, 0f)
        assertEquals(1f, p.bass.harmonics, 0f)
        assertEquals(0f, p.bass.punch, 0f)
        assertEquals(40f, p.bass.crossoverHz, 0f)
        assertEquals(-12f, p.imaging.midGainDb, 0f)
        assertEquals(1f, p.imaging.balance, 0f)
        assertEquals(0f, p.imaging.monoBelowHz, 0f)
        assertEquals(-8f, p.dynamics.loudness.targetLufs, 0f)
        assertEquals(12f, p.dynamics.loudness.maxBoostDb, 0f)
        assertEquals(1f, p.dynamics.loudness.responseSeconds, 0f)
    }
}
