package com.senzaproject.studio.audio.engine

import kotlin.math.sqrt

/** One band of the platform's graphic EQ: gain applies up to [cutoffHz] (and above the previous edge). */
data class EqEdge(val cutoffHz: Float, val gainDb: Float)

/**
 * What can honestly be sent to the platform's DynamicsProcessing effect on another app's session.
 * That effect has no biquad peaking filters, bass, spatial, imaging or loudness stages, so the plan
 * is a reduced approximation and lists what it leaves out.
 */
data class SessionEffectPlan(
    val eqBands: List<EqEdge>,
    val inputGainDb: Float,
    val limiterThresholdDb: Float,
    val limiterReleaseMs: Float,
    /** Modules the user enabled that this path cannot apply. */
    val ignored: List<String>,
)

object SessionEffectPlanner {
    private const val TOP_EDGE_HZ = 20_000f

    fun plan(parameters: EngineParameters): SessionEffectPlan {
        val p = parameters.sanitized()
        val bands = p.eq.bands.sortedBy { it.frequencyHz }
        val edges = ArrayList<EqEdge>(bands.size)
        var previous = 0f
        for (i in bands.indices) {
            val raw = if (i < bands.size - 1) {
                sqrt(bands[i].frequencyHz * bands[i + 1].frequencyHz)
            } else {
                TOP_EDGE_HZ
            }
            val edge = maxOf(raw, previous + 1f)
            previous = edge
            edges += EqEdge(edge, if (p.eq.enabled) bands[i].gainDb else 0f)
        }
        val ignored = ArrayList<String>()
        if (p.bass.enabled) ignored += DspModule.BASS.displayName
        if (p.spatial.enabled) ignored += DspModule.SPATIAL.displayName
        if (p.imaging.enabled) ignored += DspModule.STEREO_IMAGING.displayName
        if (p.dynamics.compressor.enabled) ignored += "Compressor"
        if (p.dynamics.loudness.enabled) ignored += "Loudness normalizer"
        return SessionEffectPlan(
            eqBands = edges,
            inputGainDb = p.masterGainDb,
            limiterThresholdDb = p.dynamics.limiter.ceilingDb,
            limiterReleaseMs = p.dynamics.limiter.releaseMs,
            ignored = ignored,
        )
    }
}
