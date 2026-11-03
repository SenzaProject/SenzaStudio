package com.senzaproject.studio.audio.dsp

import com.senzaproject.studio.audio.engine.SpatialMode
import com.senzaproject.studio.audio.engine.SpatialParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialTest {
    private fun run(p: SpatialParameters, input: FloatArray, block: Int = 480): FloatArray {
        val sp = SpatialProcessor(FS).also { it.configure(SpatialConfig.from(p, FS)) }
        return runBlocks(input, block) { buf, n -> sp.process(buf, n) }
    }

    private fun on(mode: SpatialMode, intensity: Float = 1f, width: Float = 1f) =
        SpatialParameters(enabled = true, mode = mode, intensity = intensity, stereoWidth = width)

    private fun sideRatio(out: FloatArray, input: FloatArray): Double = rmsSide(out) / rmsSide(input)

    @Test
    fun zeroIntensityAndUnitWidthIsTransparent() {
        val input = stereoNoise(0.5f, 10_000)
        for (mode in SpatialMode.entries) {
            val out = run(on(mode, intensity = 0f), input)
            for (i in input.indices) assertEquals("$mode", input[i], out[i], 1e-6f)
        }
    }

    @Test
    fun disabledSpatialIgnoresItsSettings() {
        val input = stereoNoise(0.5f, 10_000)
        val out = run(SpatialParameters(enabled = false, mode = SpatialMode.IMMERSIVE, intensity = 1f, stereoWidth = 2f), input)
        for (i in input.indices) assertEquals(input[i], out[i], 0f)
    }

    @Test
    fun monoSumIsPreservedInEveryScene() {
        val input = stereoNoise(0.4f, FS)
        for (mode in SpatialMode.entries) {
            val out = run(on(mode, intensity = 1f, width = 1.5f), input)
            for (f in 0 until FS) {
                val inMid = (input[f * 2] + input[f * 2 + 1]) * 0.5f
                val outMid = (out[f * 2] + out[f * 2 + 1]) * 0.5f
                assertEquals("$mode frame $f", inMid, outMid, 1e-6f)
            }
        }
    }

    @Test
    fun widthScalesTheSideSignalOnly() {
        val input = stereoNoise(0.4f, FS)
        val wide = run(on(SpatialMode.NATURAL, intensity = 0f, width = 2f), input)
        assertEquals(6.02, dbfs(sideRatio(wide, input)), 0.05)
        val narrow = run(on(SpatialMode.NATURAL, intensity = 0f, width = 0.5f), input)
        assertEquals(-6.02, dbfs(sideRatio(narrow, input)), 0.05)
        val mono = run(on(SpatialMode.NATURAL, intensity = 0f, width = 0f), input)
        for (f in 0 until FS) assertEquals(mono[f * 2], mono[f * 2 + 1], 1e-6f)
    }

    @Test
    fun crossfeedNarrowsLowFrequencySideWhileHighsStayWide() {
        val p = on(SpatialMode.STUDIO, intensity = 1f)
        val low = run(p, stereoSine(100.0, 0.3, -0.3, 2.0))
        val lowDb = dbfs(toneAmplitude(low, 0, 100.0, FS, FS) / 0.3)
        assertTrue("100 Hz side=$lowDb dB", lowDb in -5.4..-4.2)
        val high = run(p, stereoSine(5_000.0, 0.3, -0.3, 2.0))
        val highDb = dbfs(toneAmplitude(high, 0, 5_000.0, FS, FS) / 0.3)
        assertTrue("5 kHz side=$highDb dB", kotlin.math.abs(highDb) < 1.0)
    }

    @Test
    fun scenesAddAmbienceInTheDocumentedOrder() {
        val mono = monoNoise(0.3f, FS * 3)
        fun width(mode: SpatialMode): Double = rmsSide(run(on(mode), mono)) / rmsChannel(mono, 0)
        assertTrue(width(SpatialMode.STUDIO) < 1e-6)
        val ordered = listOf(
            SpatialMode.NATURAL, SpatialMode.GAMING, SpatialMode.CINEMA,
            SpatialMode.IMMERSIVE, SpatialMode.CONCERT_HALL,
        ).map { width(it) }
        for (k in 0 until ordered.size - 1) {
            assertTrue("order $ordered", ordered[k] < ordered[k + 1])
        }
    }

    @Test
    fun intensityScalesTheEffectLinearly() {
        val mono = monoNoise(0.3f, FS * 3)
        val full = rmsSide(run(on(SpatialMode.CINEMA, intensity = 1f), mono))
        val half = rmsSide(run(on(SpatialMode.CINEMA, intensity = 0.5f), mono))
        assertEquals(0.5, half / full, 0.02)
    }

    @Test
    fun overallLevelStaysWithinDocumentedBounds() {
        val input = stereoNoise(0.4f, FS * 2)
        for (mode in SpatialMode.entries) {
            val out = run(on(mode, intensity = 1f), input)
            val changeDb = dbfs(rmsAll(out) / rmsAll(input))
            assertTrue("$mode change=$changeDb dB", changeDb in -1.0..3.0)
        }
    }

    @Test
    fun reflectionsStartAtTheConfiguredDelay() {
        val impulse = FloatArray(FS * 2)
        impulse[2_000] = 0.5f; impulse[2_001] = 0.5f // frame 1000, both channels
        val out = run(on(SpatialMode.CONCERT_HALL, intensity = 1f), impulse)
        val firstTap = Math.round(11.0 * 2.0 * FS / 1000.0).toInt() // 11 ms x room scale 2.0
        for (f in 1_000 until 1_000 + firstTap) {
            assertTrue("frame $f", kotlin.math.abs(out[f * 2] - out[f * 2 + 1]) < 1e-9f)
        }
        assertTrue(kotlin.math.abs(out[(1_000 + firstTap) * 2] - out[(1_000 + firstTap) * 2 + 1]) > 1e-4f)
    }

    @Test
    fun blockSizeDoesNotChangeTheResult() {
        val input = stereoNoise(0.4f, FS)
        val p = on(SpatialMode.IMMERSIVE, intensity = 0.8f, width = 1.3f)
        val a = run(p, input, 480)
        val b = run(p, input, 333)
        for (i in a.indices) assertEquals(a[i], b[i], 1e-6f)
    }

    @Test
    fun reflectionDelaysNeverExceedTheBuffer() {
        for (rate in listOf(8_000, 22_050, 44_100, 48_000, 96_000, 192_000)) {
            val c = SpatialConfig.from(on(SpatialMode.CONCERT_HALL), rate)
            val limit = SpatialConstants.maxDelaySamples(rate)
            assertTrue(c.tapDelays.all { it in 1..limit })
            val sp = SpatialProcessor(rate).also { it.configure(c) }
            val buf = FloatArray(rate / 10 * 2) { if (it % 7 == 0) 0.5f else -0.2f }
            sp.process(buf, buf.size / 2)
            assertTrue(buf.all { it.isFinite() })
        }
    }
}
