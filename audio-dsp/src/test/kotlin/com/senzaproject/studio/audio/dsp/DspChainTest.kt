package com.senzaproject.studio.audio.dsp

import com.senzaproject.studio.audio.engine.CompressorParameters
import com.senzaproject.studio.audio.engine.DynamicsParameters
import com.senzaproject.studio.audio.engine.EngineParameters
import com.senzaproject.studio.audio.engine.EqBand
import com.senzaproject.studio.audio.engine.EqParameters
import com.senzaproject.studio.audio.engine.FilterType
import com.senzaproject.studio.audio.engine.LimiterParameters
import java.io.File
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspChainTest {
    private fun run(chain: DspChain, input: FloatArray, block: Int = 480): FloatArray {
        val out = input.copyOf()
        val tmp = FloatArray(block * 2)
        var f = 0; val total = out.size / 2
        while (f < total) {
            val n = minOf(block, total - f)
            System.arraycopy(out, f * 2, tmp, 0, n * 2)
            chain.process(tmp, n)
            System.arraycopy(tmp, 0, out, f * 2, n * 2)
            f += n
        }
        return out
    }

    @Test
    fun defaultChainIsTransparentApartFromLatency() {
        val chain = DspChain(FS)
        val input = noise(0.5f, 20_000)
        val out = run(chain, input)
        val d = chain.latencySamples
        for (i in d until 20_000) {
            assertEquals(input[(i - d) * 2], out[i * 2], 1e-6f)
            assertEquals(input[(i - d) * 2 + 1], out[i * 2 + 1], 1e-6f)
        }
    }

    @Test
    fun enabledEqChangesTheSignalAsDesigned() {
        val chain = DspChain(FS)
        val bands = EqParameters.defaultBands().toMutableList()
        bands[5] = EqBand(frequencyHz = 1_000f, gainDb = 6f, q = 1f) // 1 kHz peaking +6 dB
        chain.configure(EngineParameters(eq = EqParameters(enabled = true, bands = bands)))
        val input = sine(1_000.0, 0.1, 1.0)
        val out = run(chain, input)
        val gain = 20 * log10(rmsLeft(out, FS / 2) / rmsLeft(input, FS / 2))
        assertEquals(6.0, gain, 0.1)
    }

    @Test
    fun disabledEqIgnoresBandSettings() {
        val chain = DspChain(FS)
        val bands = EqParameters.defaultBands().map { it.copy(gainDb = 12f) }
        chain.configure(EngineParameters(eq = EqParameters(enabled = false, bands = bands)))
        val input = noise(0.3f, 10_000)
        val out = run(chain, input)
        val d = chain.latencySamples
        for (i in d until 10_000) assertEquals(input[(i - d) * 2], out[i * 2], 1e-6f)
    }

    @Test
    fun masterGainIsAppliedAfterSmoothing() {
        val chain = DspChain(FS)
        chain.configure(EngineParameters(masterGainDb = -6f))
        val out = run(chain, FloatArray(FS * 2) { 0.5f })
        assertEquals(-6.0, 20 * log10(out[out.size - 2] / 0.5), 0.05)
    }

    @Test
    fun worstCaseSettingsNeverClipOrProduceNaN() {
        // +15 dB on every band, +6 dB master, compressor makeup +24 dB, full-scale noise.
        val bands = EqParameters.defaultBands().map { it.copy(gainDb = 15f, q = 4f) }
        val params = EngineParameters(
            masterGainDb = 6f,
            eq = EqParameters(enabled = true, bands = bands),
            dynamics = DynamicsParameters(
                compressor = CompressorParameters(enabled = true, makeupDb = 24f),
                limiter = LimiterParameters(ceilingDb = -0.3f),
            ),
        )
        val chain = DspChain(FS).also { it.configure(params) }
        val out = run(chain, noise(1.0f, FS * 2))
        val ceiling = Math.pow(10.0, -0.3 / 20).toFloat()
        assertTrue(out.all { it.isFinite() })
        assertTrue("peak=${peak(out)}", peak(out) <= ceiling + 1e-6f)
    }

    @Test
    fun nanAndInfinityInputNeverPoisonTheFilters() {
        val chain = DspChain(FS)
        val bands = EqParameters.defaultBands().map { it.copy(gainDb = 6f) }
        chain.configure(EngineParameters(eq = EqParameters(enabled = true, bands = bands)))
        val bad = noise(0.2f, 4_800)
        bad[10] = Float.NaN; bad[11] = Float.POSITIVE_INFINITY; bad[100] = Float.NEGATIVE_INFINITY
        run(chain, bad)
        val out = run(chain, noise(0.2f, 4_800, seed = 7))
        assertTrue(out.all { it.isFinite() })
    }

    @Test
    fun parameterChangesDuringProcessingStayFiniteAndBounded() {
        val chain = DspChain(FS)
        val ceiling = Math.pow(10.0, -0.3 / 20).toFloat()
        val input = noise(0.9f, FS)
        val out = input.copyOf()
        val tmp = FloatArray(480 * 2)
        var f = 0; var step = 0
        while (f < FS) {
            if (step % 10 == 0) {
                val gain = if ((step / 10) % 2 == 0) 12f else -12f
                val bands = EqParameters.defaultBands().map { it.copy(gainDb = gain, type = FilterType.PEAKING) }
                chain.configure(EngineParameters(eq = EqParameters(enabled = true, bands = bands)))
            }
            val n = minOf(480, FS - f)
            System.arraycopy(out, f * 2, tmp, 0, n * 2)
            chain.process(tmp, n)
            System.arraycopy(tmp, 0, out, f * 2, n * 2)
            f += n; step++
        }
        assertTrue(out.all { it.isFinite() })
        assertTrue(peak(out) <= ceiling + 1e-6f)
    }

    @Test
    fun dspSourcesDoNotImportAndroid() {
        val offenders = File("src/main").walkTopDown().filter { it.extension == "kt" }
            .filter { f -> f.readLines().any { it.trim().startsWith("import android.") || it.trim().startsWith("import androidx.") } }
            .map { it.name }.toList()
        assertTrue("Android imports in DSP: $offenders", offenders.isEmpty())
    }
}
