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
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 exit criterion "stable soak tests": minutes of program material through the full chain while
 * parameters keep changing, at several sample rates. Fails on NaN/Inf, on any sample above the
 * limiter ceiling, or if the limiter drifts away from unity gain on quiet material afterwards.
 */
class SoakTest {
    private val ceilingDb = -0.3f
    private val ceiling = Math.pow(10.0, ceilingDb / 20.0).toFloat()

    private fun parameters(seed: Int): EngineParameters {
        val r = java.util.Random(seed.toLong())
        fun f(lo: Float, hi: Float) = lo + r.nextFloat() * (hi - lo)
        val modes = SpatialMode.entries
        return EngineParameters(
            masterGainDb = f(-6f, 6f),
            eq = EqParameters(
                enabled = r.nextInt(4) != 0,
                bands = EqParameters.defaultBands().map { EqBand(it.frequencyHz, f(-12f, 12f), f(0.4f, 6f)) },
            ),
            spatial = SpatialParameters(r.nextInt(4) != 0, modes[r.nextInt(modes.size)], f(0f, 1f), f(0f, 2f)),
            dynamics = DynamicsParameters(
                compressor = CompressorParameters(r.nextBoolean(), f(-40f, -6f), f(1f, 10f), f(1f, 50f), f(20f, 400f), f(0f, 12f), f(0f, 8f)),
                loudness = LoudnessParameters(r.nextBoolean(), f(-24f, -10f), f(0f, 12f), f(0f, 18f), f(1f, 10f)),
            ),
            bass = BassParameters(r.nextBoolean(), f(-12f, 12f), f(0f, 1f), f(0f, 1f), f(50f, 180f)),
            imaging = ImagingParameters(r.nextBoolean(), f(-6f, 6f), f(-6f, 6f), f(-1f, 1f), if (r.nextBoolean()) f(20f, 250f) else 0f),
            bypass = r.nextInt(10) == 0,
        )
    }

    /** Program-like material generated block by block: noise, a bass tone, bursts and silences. */
    private class Program(private val fs: Int, seed: Long) {
        private val rnd = java.util.Random(seed)
        private var n = 0L
        fun fill(buf: FloatArray, frames: Int) {
            for (i in 0 until frames) {
                val t = n.toDouble() / fs
                val section = ((n / (fs / 2)) % 8).toInt()
                val level = when (section) {
                    0 -> 0.9
                    1 -> 0.25
                    2 -> 0.03
                    3 -> 0.0
                    4 -> 1.8 // over full scale: the limiter has to work
                    5 -> 0.5
                    6 -> 0.002
                    else -> 0.7
                }
                val noise = (rnd.nextFloat() * 2f - 1f) * 0.5
                val tone = sin(2.0 * PI * 55.0 * t) * 0.6 + sin(2.0 * PI * 1_300.0 * t) * 0.2
                buf[i * 2] = ((noise + tone) * level).toFloat()
                buf[i * 2 + 1] = ((noise * 0.8 + tone * 0.9) * level).toFloat()
                n++
            }
        }
    }

    private fun soak(sampleRate: Int, seconds: Int, retuneEverySeconds: Int) {
        val chain = DspChain(sampleRate)
        val program = Program(sampleRate, 99)
        val block = 1024
        val buf = FloatArray(block * 2)
        val totalBlocks = (sampleRate.toLong() * seconds / block).toInt()
        val blocksPerRetune = maxOf(1, sampleRate * retuneEverySeconds / block)
        var seed = 1
        chain.configure(parameters(seed))
        var worstPeak = 0f
        for (b in 0 until totalBlocks) {
            if (b > 0 && b % blocksPerRetune == 0) {
                seed++
                chain.configure(parameters(seed))
                if (seed % 5 == 0) chain.resetMeters()
                chain.spectrumEnabled = seed % 7 != 0
            }
            program.fill(buf, block)
            chain.process(buf, block)
            for (i in buf.indices) {
                val v = buf[i]
                if (!v.isFinite()) throw AssertionError("non-finite sample at block $b index $i (seed $seed, fs $sampleRate)")
                val a = if (v < 0f) -v else v
                if (a > worstPeak) worstPeak = a
            }
        }
        assertTrue("peak $worstPeak above ceiling $ceiling (fs $sampleRate)", worstPeak <= ceiling + 1e-6f)
        assertTrue(chain.loudnessMeter.momentaryLufs.isFinite())
        assertTrue(chain.gainReductionDb <= 0.0)
        assertTrue(chain.stereoMeter.correlation in -1.0..1.0)

        // After the soak, with everything flat, quiet material must pass at unity (limiter state is sane).
        chain.configure(EngineParameters())
        val quiet = stereoNoise(0.1f, sampleRate, 5, 6)
        val out = runBlocks(quiet, block) { b, n -> chain.process(b, n) }
        val d = chain.latencySamples
        // Skip the first second: the master gain smoother and the delayed parameter hand-off settle.
        for (i in sampleRate / 2 until sampleRate - 1) {
            assertEquals(quiet[(i - d) * 2], out[i * 2], 2e-3f)
        }
    }

    @Test
    fun fiveMinutesAt48kHzWithConstantRetuning() = soak(48_000, 300, 2)

    @Test
    fun oneMinuteAt44100Hz() = soak(44_100, 60, 1)

    @Test
    fun oneMinuteAt96kHz() = soak(96_000, 60, 1)

    @Test
    fun longSilenceDoesNotLeaveStateBehind() {
        val chain = DspChain(FS).also { it.configure(parameters(3)) }
        val loud = stereoNoise(0.8f, FS * 2)
        runBlocks(loud, 1024) { b, n -> chain.process(b, n) }
        val silence = FloatArray(1024 * 2)
        repeat(FS * 60 / 1024) { silence.fill(0f); chain.process(silence, 1024) }
        // 60 s of digital silence: output silent, meters at their floor, no NaN.
        assertTrue(silence.all { it == 0f || kotlin.math.abs(it) < 1e-6f })
        assertTrue(chain.levelMeter.peakDbLeft <= -119.0)
        assertTrue(chain.levelMeter.rmsDbLeft <= -119.0)
    }
}
