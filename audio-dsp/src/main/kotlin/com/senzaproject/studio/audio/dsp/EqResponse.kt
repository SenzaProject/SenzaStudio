package com.senzaproject.studio.audio.dsp

import com.senzaproject.studio.audio.engine.EqParameters
import com.senzaproject.studio.audio.engine.FilterType
import kotlin.math.abs
import kotlin.math.pow

/** Magnitude response of the equalizer, used to draw the EQ graph. Same filter design as the real chain. */
object EqResponse {
    /**
     * Combined gain in dB at [points] log-spaced frequencies from [minHz] to [maxHz].
     * A disabled EQ is flat, exactly like the chain.
     */
    fun curveDb(
        eq: EqParameters,
        sampleRate: Int = 48_000,
        points: Int = 128,
        minHz: Double = 20.0,
        maxHz: Double = 20_000.0,
    ): FloatArray {
        require(points >= 2)
        val out = FloatArray(points)
        if (!eq.enabled) return out
        val filters = eq.sanitized().bands.mapNotNull { b ->
            val flat = b.type != FilterType.HIGH_PASS && b.type != FilterType.LOW_PASS && abs(b.gainDb) < 0.01f
            if (flat) null else BiquadDesign.design(b.type, sampleRate, b.frequencyHz, b.gainDb, b.q)
        }
        val ratio = (maxHz / minHz).pow(1.0 / (points - 1))
        var f = minHz
        for (i in 0 until points) {
            var sum = 0.0
            for (c in filters) sum += c.magnitudeDb(f, sampleRate.toDouble())
            out[i] = sum.toFloat()
            f *= ratio
        }
        return out
    }
}
