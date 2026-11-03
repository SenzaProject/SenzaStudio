package com.senzaproject.studio.audio.dsp

import com.senzaproject.studio.audio.engine.BassParameters
import com.senzaproject.studio.audio.engine.CompressorParameters
import com.senzaproject.studio.audio.engine.DynamicsParameters
import com.senzaproject.studio.audio.engine.EngineParameters
import com.senzaproject.studio.audio.engine.EqBand
import com.senzaproject.studio.audio.engine.EqParameters
import com.senzaproject.studio.audio.engine.ImagingParameters
import com.senzaproject.studio.audio.engine.LoudnessParameters
import com.senzaproject.studio.audio.engine.SpatialMode
import com.senzaproject.studio.audio.engine.SpatialParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspChainAdvancedTest {
    private val ceiling = Math.pow(10.0, -0.3 / 20).toFloat()

    private fun run(chain: DspChain, input: FloatArray) = runBlocks(input) { b, n -> chain.process(b, n) }

    private val everything = EngineParameters(
        masterGainDb = 6f,
        eq = EqParameters(enabled = true, bands = EqParameters.defaultBands().map { EqBand(it.frequencyHz, 15f, 4f) }),
        spatial = SpatialParameters(true, SpatialMode.IMMERSIVE, 1f, 2f),
        dynamics = DynamicsParameters(
            compressor = CompressorParameters(enabled = true, makeupDb = 24f),
            loudness = LoudnessParameters(enabled = true, targetLufs = -8f, maxBoostDb = 12f, responseSeconds = 1f),
        ),
        bass = BassParameters(true, 12f, 1f, 1f, 200f),
        imaging = ImagingParameters(true, 12f, 12f, 0.5f, 300f),
    )

    @Test
    fun worstCaseWithEveryModuleNeverClipsOrProducesNaN() {
        val chain = DspChain(FS).also { it.configure(everything) }
        val out = run(chain, stereoNoise(1.0f, FS * 3))
        assertTrue(out.all { it.isFinite() })
        assertTrue("peak=${peak(out)}", peak(out) <= ceiling + 1e-6f)
    }

    @Test
    fun chainMeasuresItsOwnOutput() {
        val a = Math.pow(10.0, -23.0 / 20.0)
        val chain = DspChain(FS)
        run(chain, stereoSine(1_000.0, a, a, 5.0))
        assertEquals(-23.0, chain.loudnessMeter.integratedLufs, 0.15)
        assertTrue(chain.stereoMeter.correlation > 0.99)
        chain.resetMeters()
        assertEquals(LoudnessMeter.SILENCE_LUFS, chain.loudnessMeter.momentaryLufs, 0.0)
    }

    @Test
    fun disabledAdvancedModulesLeaveTheChainTransparent() {
        val off = everything.copy(
            eq = EqParameters(),
            spatial = everything.spatial.copy(enabled = false),
            dynamics = DynamicsParameters(),
            bass = everything.bass.copy(enabled = false),
            imaging = everything.imaging.copy(enabled = false),
            masterGainDb = 0f,
        )
        val input = stereoNoise(0.5f, 20_000)
        val ref = run(DspChain(FS), input)
        val out = run(DspChain(FS).also { it.configure(off) }, input)
        for (i in ref.indices) assertEquals(ref[i], out[i], 0f)
    }

    @Test
    fun spatialKeepsTheMonoSumThroughTheWholeChain() {
        val input = monoNoise(0.2f, FS)
        val ref = run(DspChain(FS), input)
        val chain = DspChain(FS).also {
            it.configure(EngineParameters(spatial = SpatialParameters(true, SpatialMode.CONCERT_HALL, 1f, 1.5f)))
        }
        val out = run(chain, input)
        for (f in 0 until FS) {
            assertEquals((ref[f * 2] + ref[f * 2 + 1]) * 0.5f, (out[f * 2] + out[f * 2 + 1]) * 0.5f, 1e-5f)
        }
    }

    @Test
    fun switchingModulesWhileProcessingStaysFiniteAndBounded() {
        val chain = DspChain(FS)
        val input = stereoNoise(0.9f, FS * 2)
        val out = input.copyOf()
        val tmp = FloatArray(480 * 2)
        var f = 0
        var step = 0
        while (f < FS * 2) {
            if (step % 7 == 0) {
                val k = step / 7
                chain.configure(
                    everything.copy(
                        bass = everything.bass.copy(enabled = k % 2 == 0),
                        spatial = everything.spatial.copy(enabled = k % 3 == 0),
                        imaging = everything.imaging.copy(enabled = k % 5 != 0),
                        dynamics = everything.dynamics.copy(
                            loudness = everything.dynamics.loudness.copy(enabled = k % 4 == 0),
                        ),
                    ),
                )
            }
            val n = minOf(480, FS * 2 - f)
            System.arraycopy(out, f * 2, tmp, 0, n * 2)
            chain.process(tmp, n)
            System.arraycopy(tmp, 0, out, f * 2, n * 2)
            f += n; step++
        }
        assertTrue(out.all { it.isFinite() })
        assertTrue("peak=${peak(out)}", peak(out) <= ceiling + 1e-6f)
    }
}
