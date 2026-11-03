package com.senzaproject.studio.audio.dsp

import com.senzaproject.studio.audio.engine.SpatialMode
import com.senzaproject.studio.audio.engine.SpatialParameters
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scene tuning at intensity 1. Every effect scales linearly with the user's intensity.
 *  - [crossfeed]: depth of low-passed, slightly delayed side feed-back (headphone naturalness).
 *  - [widthLift]: extra side gain, multiplied with the user's stereo width.
 *  - [ambience]: level of the decorrelated side-only reflections (virtual room / surround).
 *  - [roomScale]: scales the reflection delays (bigger = larger room).
 */
internal class SceneTuning(
    val crossfeed: Double,
    val widthLift: Double,
    val ambience: Double,
    val roomScale: Double,
) {
    companion object {
        fun of(mode: SpatialMode): SceneTuning = when (mode) {
            SpatialMode.NATURAL -> SceneTuning(0.25, 1.00, 0.10, 1.0)
            SpatialMode.STUDIO -> SceneTuning(0.45, 1.00, 0.00, 1.0)
            SpatialMode.CINEMA -> SceneTuning(0.15, 1.25, 0.35, 1.4)
            SpatialMode.CONCERT_HALL -> SceneTuning(0.20, 1.10, 0.50, 2.0)
            SpatialMode.GAMING -> SceneTuning(0.10, 1.35, 0.20, 0.8)
            SpatialMode.IMMERSIVE -> SceneTuning(0.10, 1.50, 0.45, 1.6)
        }
    }
}

internal object SpatialConstants {
    const val TAP_COUNT = 4
    val TAP_BASE_MS = doubleArrayOf(11.0, 17.0, 23.0, 31.0)
    val TAP_GAINS = doubleArrayOf(0.5, 0.4, 0.3, 0.2)
    const val AMBIENCE_MID_FEED = 0.3
    const val AMBIENCE_LOWPASS_HZ = 6_000.0
    const val CROSSFEED_LOWPASS_HZ = 700.0
    const val CROSSFEED_DELAY_MS = 0.15
    /** Longest reflection the ring buffer can hold. */
    const val MAX_DELAY_SECONDS = 0.1

    fun maxDelaySamples(sampleRate: Int): Int = (sampleRate * MAX_DELAY_SECONDS).toInt()
}

/** Immutable spatial configuration (all scene maths done on the control thread). */
internal class SpatialConfig(
    val active: Boolean,
    /** Total side gain: user width x scene lift (scaled by intensity). */
    val sideGain: Double,
    val ambienceGain: Double,
    val crossfeedGain: Double,
    val tapDelays: IntArray,
) {
    companion object {
        fun from(p: SpatialParameters, sampleRate: Int): SpatialConfig {
            val s = p.sanitized()
            val tuning = SceneTuning.of(s.mode)
            val intensity = s.intensity.toDouble()
            val maxDelay = SpatialConstants.maxDelaySamples(sampleRate)
            return SpatialConfig(
                active = s.enabled,
                sideGain = s.stereoWidth * (1.0 + (tuning.widthLift - 1.0) * intensity),
                ambienceGain = tuning.ambience * intensity,
                crossfeedGain = tuning.crossfeed * intensity,
                tapDelays = IntArray(SpatialConstants.TAP_COUNT) { k ->
                    min(
                        (SpatialConstants.TAP_BASE_MS[k] * tuning.roomScale * sampleRate / 1000.0).roundToInt(),
                        maxDelay,
                    )
                },
            )
        }
    }
}

/**
 * Spatial 360: widening, headphone crossfeed and a virtual room, all implemented on the SIDE
 * signal only (M = (L+R)/2, S = (L-R)/2):
 *
 *   S' = width * S  +  ambience * sum_k g_k * lowpass6k(S + 0.3 M)[t - d_k]  -  crossfeed * delay(lowpass700(S))
 *
 * Output L = M + S', R = M - S'. Consequences, all verified by tests:
 *  - the mid signal is untouched, so the mono sum is exactly the input's mono sum (full mono compatibility);
 *  - a mono input stays centred in level but gains width from the decorrelated reflections;
 *  - the crossfeed term narrows the low-frequency image (the way loudspeaker listening does),
 *    its short delay gives the fed signal an inter-aural time offset.
 * Reflection delays and scene changes are not cross-faded (P3 limitation: a mode switch can click).
 * No allocation and no locks in [process]; all buffers are allocated at construction.
 */
class SpatialProcessor(private val sampleRate: Int) {
    private val smoothing = 1.0 - exp(-1.0 / (sampleRate * 0.010))
    private val ambienceLowPass = 1.0 - exp(-2.0 * PI * SpatialConstants.AMBIENCE_LOWPASS_HZ / sampleRate)
    private val crossfeedLowPass = 1.0 - exp(-2.0 * PI * SpatialConstants.CROSSFEED_LOWPASS_HZ / sampleRate)

    private val ambienceBuffer = DoubleArray(SpatialConstants.maxDelaySamples(sampleRate) + 2)
    private val crossfeedDelay = maxOf(1, (SpatialConstants.CROSSFEED_DELAY_MS * sampleRate / 1000.0).roundToInt())
    private val crossfeedBuffer = DoubleArray(crossfeedDelay + 2)
    private val delays = IntArray(SpatialConstants.TAP_COUNT)

    private var ambienceWrite = 0
    private var crossfeedWrite = 0
    private var ambienceState = 0.0
    private var crossfeedState = 0.0

    private var active = false
    private var targetSide = 1.0
    private var targetAmbience = 0.0
    private var targetCrossfeed = 0.0
    private var curSide = 1.0
    private var curAmbience = 0.0
    private var curCrossfeed = 0.0

    internal fun configure(c: SpatialConfig) {
        val wasActive = active
        active = c.active
        targetSide = c.sideGain
        targetAmbience = c.ambienceGain
        targetCrossfeed = c.crossfeedGain
        for (k in 0 until SpatialConstants.TAP_COUNT) delays[k] = c.tapDelays[k]
        if (active && !wasActive) {
            curSide = targetSide; curAmbience = targetAmbience; curCrossfeed = targetCrossfeed
            reset()
        }
    }

    fun reset() {
        ambienceBuffer.fill(0.0)
        crossfeedBuffer.fill(0.0)
        ambienceWrite = 0
        crossfeedWrite = 0
        ambienceState = 0.0
        crossfeedState = 0.0
    }

    fun process(buf: FloatArray, frames: Int) {
        if (!active) return
        val ambSize = ambienceBuffer.size
        val cfSize = crossfeedBuffer.size
        var i = 0
        val end = frames * 2
        while (i < end) {
            curSide += (targetSide - curSide) * smoothing
            curAmbience += (targetAmbience - curAmbience) * smoothing
            curCrossfeed += (targetCrossfeed - curCrossfeed) * smoothing

            val l = buf[i].toDouble()
            val r = buf[i + 1].toDouble()
            val mid = (l + r) * 0.5
            val side = (l - r) * 0.5

            // Virtual room: decorrelated reflections of (side + a little mid), side-only.
            ambienceState += ambienceLowPass * ((side + SpatialConstants.AMBIENCE_MID_FEED * mid) - ambienceState)
            ambienceBuffer[ambienceWrite] = ambienceState
            var reflections = 0.0
            for (k in 0 until SpatialConstants.TAP_COUNT) {
                var idx = ambienceWrite - delays[k]
                if (idx < 0) idx += ambSize
                reflections += SpatialConstants.TAP_GAINS[k] * ambienceBuffer[idx]
            }
            ambienceWrite++
            if (ambienceWrite == ambSize) ambienceWrite = 0

            // Crossfeed: low-passed, delayed copy of the side signal subtracted from it.
            crossfeedState += crossfeedLowPass * (side - crossfeedState)
            crossfeedBuffer[crossfeedWrite] = crossfeedState
            var ci = crossfeedWrite - crossfeedDelay
            if (ci < 0) ci += cfSize
            val fed = crossfeedBuffer[ci]
            crossfeedWrite++
            if (crossfeedWrite == cfSize) crossfeedWrite = 0

            val newSide = curSide * side + curAmbience * reflections - curCrossfeed * fed
            buf[i] = (mid + newSide).toFloat()
            buf[i + 1] = (mid - newSide).toFloat()
            i += 2
        }
    }
}
