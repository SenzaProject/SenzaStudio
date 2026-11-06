package com.senzaproject.studio.audio.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Verbatim copies of the P5 inner loops, kept only so tests can prove the P6 versions produce the
 * same audio and so the measurement report can show the before/after cost.
 */
/** The P5 limiter: full scan of the request ring for every sample. */
internal class ReferenceLimiter(sampleRate: Int, lookaheadMs: Double = 2.0) {
    val latencySamples: Int = max(1, (sampleRate * lookaheadMs / 1000.0).toInt())
    private val size = latencySamples + 1
    private val delay = FloatArray(latencySamples * 2)
    private val rRing = FloatArray(size) { 1f }
    private val mRing = FloatArray(size) { 1f }
    private var mSum = size.toDouble()
    private var pos = 0
    private var delayPos = 0
    private var smoothed = 1.0
    private var ceiling = dbToLinear(-0.3)
    private val sampleRateD = sampleRate.toDouble()
    private var releaseCoef = exp(-1.0 / (sampleRateD * 0.080))

    fun configure(ceilingDb: Double, releaseMs: Double) {
        ceiling = dbToLinear(min(ceilingDb, 0.0))
        releaseCoef = exp(-1.0 / (sampleRateD * max(releaseMs, 1.0) / 1000.0))
    }

    fun process(buf: FloatArray, frames: Int) {
        val c = ceiling.toFloat()
        var i = 0
        while (i < frames) {
            val l = buf[i * 2]
            val r = buf[i * 2 + 1]
            val peak = max(abs(l), abs(r))
            val req = if (peak > c) c / peak else 1f
            rRing[pos] = req
            var m = 1f
            var k = 0
            while (k < size) { val v = rRing[k]; if (v < m) m = v; k++ }
            mSum += (m - mRing[pos]).toDouble()
            mRing[pos] = m
            val g = (mSum / size).toFloat()
            val rising = releaseCoef * smoothed + (1.0 - releaseCoef)
            val gOut = min(g.toDouble(), rising)
            smoothed = gOut
            val d = delayPos * 2
            val outL = delay[d] * gOut.toFloat()
            val outR = delay[d + 1] * gOut.toFloat()
            delay[d] = l; delay[d + 1] = r
            buf[i * 2] = outL.coerceIn(-c, c)
            buf[i * 2 + 1] = outR.coerceIn(-c, c)
            pos++; if (pos == size) pos = 0
            delayPos++; if (delayPos == latencySamples) delayPos = 0
            i++
        }
    }
}

/** The P5 compressor: log10 and pow on every sample. */
internal class ReferenceCompressor(private val sampleRate: Int) {
    private var threshold = -18.0
    private var ratio = 2.0
    private var knee = 6.0
    private var makeup = 0.0
    private var attackCoef = 0.0
    private var releaseCoef = 0.0
    private var grDb = 0.0

    fun configure(thresholdDb: Double, ratio: Double, attackMs: Double, releaseMs: Double, kneeDb: Double, makeupDb: Double) {
        threshold = thresholdDb
        this.ratio = max(ratio, 1.0)
        knee = max(kneeDb, 0.0)
        makeup = makeupDb
        attackCoef = exp(-1.0 / (sampleRate * max(attackMs, 0.01) / 1000.0))
        releaseCoef = exp(-1.0 / (sampleRate * max(releaseMs, 0.01) / 1000.0))
    }

    private fun computeOutputDb(levelDb: Double): Double {
        val over = levelDb - threshold
        return when {
            2.0 * over < -knee -> levelDb
            knee > 0.0 && 2.0 * abs(over) <= knee -> {
                val t = over + knee / 2.0
                levelDb + (1.0 / ratio - 1.0) * t * t / (2.0 * knee)
            }
            else -> threshold + over / ratio
        }
    }

    fun process(buf: FloatArray, frames: Int) {
        var i = 0
        val end = frames * 2
        while (i < end) {
            val l = buf[i]
            val r = buf[i + 1]
            val peak = max(abs(l), abs(r)).toDouble()
            val levelDb = linearToDb(peak)
            val targetGr = computeOutputDb(levelDb) - levelDb
            val coef = if (targetGr < grDb) attackCoef else releaseCoef
            grDb = coef * grDb + (1.0 - coef) * targetGr
            val g = dbToLinear(grDb + makeup).toFloat()
            buf[i] = l * g
            buf[i + 1] = r * g
            i += 2
        }
    }
}

