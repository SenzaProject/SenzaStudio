package com.senzaproject.studio.audio.dsp

import com.senzaproject.studio.audio.engine.EngineParameters
import com.senzaproject.studio.audio.engine.EqParameters
import com.senzaproject.studio.audio.engine.FilterType
import java.util.concurrent.atomic.AtomicReference

/** Immutable, pre-computed configuration. Built on the control thread, applied on the audio thread. */
internal class ChainConfig(
    val eqEnabled: Boolean,
    val bands: Array<BiquadCoefficients?>, // null = bypassed band
    val compressorEnabled: Boolean,
    val thresholdDb: Double,
    val ratio: Double,
    val attackMs: Double,
    val releaseMs: Double,
    val kneeDb: Double,
    val makeupDb: Double,
    val masterGainDb: Double,
    val ceilingDb: Double,
    val limiterReleaseMs: Double,
    val bass: BassConfig,
    val spatial: SpatialConfig,
    val imaging: ImagingConfig,
    val loudness: LoudnessConfig,
    val bypass: Boolean,
) {
    companion object {
        fun from(p: EngineParameters, sampleRate: Int): ChainConfig {
            val s = p.sanitized()
            val bands = Array<BiquadCoefficients?>(EqParameters.MAX_BANDS) { idx ->
                val b = s.eq.bands.getOrNull(idx)
                if (b == null) {
                    null
                } else {
                    val flat = b.type != FilterType.HIGH_PASS && b.type != FilterType.LOW_PASS &&
                        kotlin.math.abs(b.gainDb) < 0.01f
                    if (flat) null else BiquadDesign.design(b.type, sampleRate, b.frequencyHz, b.gainDb, b.q)
                }
            }
            val c = s.dynamics.compressor
            return ChainConfig(
                eqEnabled = s.eq.enabled,
                bands = bands,
                compressorEnabled = c.enabled,
                thresholdDb = c.thresholdDb.toDouble(),
                ratio = c.ratio.toDouble(),
                attackMs = c.attackMs.toDouble(),
                releaseMs = c.releaseMs.toDouble(),
                kneeDb = c.kneeDb.toDouble(),
                makeupDb = c.makeupDb.toDouble(),
                masterGainDb = s.masterGainDb.toDouble(),
                ceilingDb = s.dynamics.limiter.ceilingDb.toDouble(),
                limiterReleaseMs = s.dynamics.limiter.releaseMs.toDouble(),
                bass = BassConfig.from(s.bass, sampleRate),
                spatial = SpatialConfig.from(s.spatial, sampleRate),
                imaging = ImagingConfig.from(s.imaging, sampleRate),
                loudness = LoudnessConfig.from(s.dynamics.loudness, sampleRate),
                bypass = s.bypass,
            )
        }
    }
}

/**
 * Stereo (interleaved float) processing chain:
 *   scrub -> EQ -> bass -> compressor -> spatial -> imaging -> loudness normalizer
 *         -> master gain -> limiter (always on, safe output) -> meters (read-only).
 * With [EngineParameters.bypass] everything between the scrub and the limiter is skipped (A/B).
 *
 * Order rationale: the bass stage sits before the compressor so added low energy is controlled by
 * it; spatial and imaging come after the compressor so reflections cannot trigger gain reduction,
 * and imaging last in the stereo section so "mono below f" and balance hold at the output; the
 * normalizer acts on the finished tone/stereo picture; the limiter is always last in the signal path.
 *
 * Threading: [configure] may be called from any control thread (it allocates). [process] is
 * called from exactly one audio thread, does not allocate and takes no locks; it picks up the
 * latest configuration through a lock-free hand-off at the start of each block.
 * Known limitations: coefficient, delay-tap and scene changes are applied without cross-fading
 * (small clicks are possible on abrupt changes); modules that are switched off are bypassed
 * abruptly. All stages except the limiter add no latency.
 */
class DspChain(private val sampleRate: Int) {
    private val eq = Array(EqParameters.MAX_BANDS) { StereoBiquad() }
    private val bass = BassEnhancer(sampleRate)
    private val compressor = Compressor(sampleRate)
    private val spatial = SpatialProcessor(sampleRate)
    private val imager = StereoImager(sampleRate)
    private val normalizer = LoudnessNormalizer(sampleRate)
    private val master = SmoothedGain(sampleRate)
    private val limiter = Limiter(sampleRate)
    private val pending = AtomicReference<ChainConfig?>(null)
    private var eqEnabled = false
    private var compressorEnabled = false
    private var bypass = false

    /** Loudness (momentary / short-term / integrated LUFS) and peaks of the chain OUTPUT. */
    val loudnessMeter = LoudnessMeter(sampleRate)

    /** Phase correlation and side/mid balance of the chain OUTPUT. */
    val stereoMeter = StereoMeter(sampleRate)

    /** Peak and RMS levels of the chain OUTPUT. */
    val levelMeter = LevelMeter(sampleRate)

    /** 32-band spectrum of the chain OUTPUT. */
    val spectrum = SpectrumAnalyzer(sampleRate)

    /** Fixed delay introduced by the limiter's look-ahead; constant for the lifetime of the chain. */
    val latencySamples: Int get() = limiter.latencySamples

    init {
        apply(ChainConfig.from(EngineParameters(), sampleRate))
    }

    fun configure(parameters: EngineParameters) {
        pending.set(ChainConfig.from(parameters, sampleRate))
    }

    /** Current compressor gain reduction in dB (<= 0), for metering. */
    val gainReductionDb: Double get() = compressor.gainReductionDb

    /** Gain currently applied by the loudness normalizer in dB (0 while it is off). */
    val normalizerGainDb: Double get() = normalizer.currentGainDb

    /** Clears meter history and held peaks (e.g. when a new track starts). */
    fun resetMeters() {
        loudnessMeter.reset()
        stereoMeter.reset()
        levelMeter.reset()
        spectrum.reset()
    }

    fun process(buf: FloatArray, frames: Int) {
        val n = minOf(frames, buf.size / 2)
        pending.getAndSet(null)?.let { apply(it) }

        // Scrub NaN/Inf so they can never reach the filters' feedback state.
        var i = 0
        while (i < n * 2) {
            if (!buf[i].isFinite()) buf[i] = 0f
            i++
        }

        if (!bypass) {
            if (eqEnabled) {
                for (band in eq) if (band.active) band.process(buf, n)
            }
            bass.process(buf, n)
            if (compressorEnabled) compressor.process(buf, n)
            spatial.process(buf, n)
            imager.process(buf, n)
            normalizer.process(buf, n)
            master.process(buf, n)
        }
        limiter.process(buf, n)

        loudnessMeter.process(buf, n)
        stereoMeter.process(buf, n)
        levelMeter.process(buf, n)
        spectrum.process(buf, n)
    }

    private fun apply(c: ChainConfig) {
        bypass = c.bypass
        eqEnabled = c.eqEnabled
        for (i in eq.indices) {
            val coeffs = c.bands[i]
            if (coeffs == null) eq[i].bypass() else eq[i].set(coeffs)
        }
        bass.configure(c.bass)
        if (!c.compressorEnabled && compressorEnabled) compressor.reset()
        compressorEnabled = c.compressorEnabled
        compressor.configure(c.thresholdDb, c.ratio, c.attackMs, c.releaseMs, c.kneeDb, c.makeupDb)
        spatial.configure(c.spatial)
        imager.configure(c.imaging)
        normalizer.configure(c.loudness)
        master.setDb(c.masterGainDb)
        limiter.configure(c.ceilingDb, c.limiterReleaseMs)
    }
}
