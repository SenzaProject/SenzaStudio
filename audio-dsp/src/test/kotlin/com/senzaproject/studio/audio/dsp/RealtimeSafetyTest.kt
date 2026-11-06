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
import java.lang.management.ManagementFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** P6 exit criterion: no allocation in the audio callback, with every module on and while parameters change. */
class RealtimeSafetyTest {
    private val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean

    private val everything = EngineParameters(
        masterGainDb = 3f,
        eq = EqParameters(enabled = true, bands = EqParameters.defaultBands().map { EqBand(it.frequencyHz, 6f, 2f) }),
        spatial = SpatialParameters(true, SpatialMode.CONCERT_HALL, 0.8f, 1.4f),
        dynamics = DynamicsParameters(
            compressor = CompressorParameters(enabled = true, thresholdDb = -24f, ratio = 4f),
            loudness = LoudnessParameters(enabled = true, targetLufs = -14f),
        ),
        bass = BassParameters(true, 6f, 0.6f, 0.6f, 120f),
        imaging = ImagingParameters(true, 1f, 2f, 0.1f, 150f),
    )

    private fun allocatedBytes(): Long = bean!!.getCurrentThreadAllocatedBytes()

    /** Sum of the bytes allocated INSIDE process() only (configure() runs on the control thread in reality). */
    private fun allocationInProcess(chain: DspChain, blocks: Int, retune: ((Int) -> EngineParameters)?): Long {
        val input = stereoNoise(0.5f, 1024)
        val buf = FloatArray(input.size)
        var total = 0L
        for (b in 0 until blocks) {
            System.arraycopy(input, 0, buf, 0, input.size)
            if (retune != null && b % 50 == 0) chain.configure(retune(b))
            val before = allocatedBytes()
            chain.process(buf, 1024)
            total += allocatedBytes() - before
        }
        return total
    }

    @Test
    fun processAllocatesNothingWithEveryModuleEnabled() {
        assumeTrue("allocation counter not available on this JVM", bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!.isThreadAllocatedMemoryEnabled = true
        val chain = DspChain(FS).also { it.configure(everything) }
        allocationInProcess(chain, 800, null) // warm-up: JIT, class loading
        val bytes = allocationInProcess(chain, 4_000, null)
        assertTrue("process() allocated $bytes bytes over 4000 blocks", bytes <= 256)
    }

    @Test
    fun processAllocatesNothingWhileParametersChangeAndSpectrumToggles() {
        assumeTrue("allocation counter not available on this JVM", bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!.isThreadAllocatedMemoryEnabled = true
        val chain = DspChain(FS).also { it.configure(everything) }
        val retune = { b: Int ->
            everything.copy(
                masterGainDb = (b % 7).toFloat(),
                spatial = everything.spatial.copy(mode = SpatialMode.entries[b % SpatialMode.entries.size]),
                bass = everything.bass.copy(harmonics = (b % 10) / 10f),
            )
        }
        allocationInProcess(chain, 800, retune)
        chain.spectrumEnabled = false
        allocationInProcess(chain, 400, retune)
        chain.spectrumEnabled = true
        val bytes = allocationInProcess(chain, 4_000, retune)
        assertTrue("process() allocated $bytes bytes while retuning", bytes <= 256)
    }

    @Test
    fun spectrumCanBeSwitchedOffAndBarsDropToTheFloor() {
        val chain = DspChain(FS)
        val tone = sine(1_000.0, 0.5, 1.0)
        runBlocks(tone, 1024) { b, n -> chain.process(b, n) }
        val bands = FloatArray(com.senzaproject.studio.audio.engine.SPECTRUM_BANDS)
        chain.spectrum.snapshot(bands)
        assertTrue("expected a visible bar", bands.max() > -30f)

        chain.spectrumEnabled = false
        runBlocks(tone, 1024) { b, n -> chain.process(b, n) }
        chain.spectrum.snapshot(bands)
        assertTrue("bars should be cleared while off", bands.all { it <= -119f })

        chain.spectrumEnabled = true
        runBlocks(tone, 1024) { b, n -> chain.process(b, n) }
        chain.spectrum.snapshot(bands)
        assertTrue("bars should return", bands.max() > -30f)
    }

    @Test
    fun disablingTheSpectrumDoesNotChangeTheAudio() {
        val input = stereoNoise(0.7f, FS * 2)
        val on = DspChain(FS).also { it.configure(everything) }
        val off = DspChain(FS).also { it.configure(everything); it.spectrumEnabled = false }
        val a = runBlocks(input, 1024) { b, n -> on.process(b, n) }
        val c = runBlocks(input, 1024) { b, n -> off.process(b, n) }
        assertEquals(a.size, c.size)
        for (i in a.indices) assertEquals(a[i], c[i], 0f)
    }
}
