package com.senzaproject.studio.audio.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)
internal fun linearToDb(x: Double): Double = 20.0 * log10(max(x, 1e-9))

/**
 * Feed-forward stereo-linked compressor with soft knee.
 * Gain computer (dB): below knee -> unity; in knee -> quadratic; above -> slope 1/ratio.
 * Gain reduction is smoothed with separate attack (more reduction) and release (less) one-poles.
 */
class Compressor(private val sampleRate: Int) {
    private var threshold = -18.0
    private var ratio = 2.0
    private var knee = 6.0
    private var makeup = 0.0
    private var attackCoef = 0.0
    private var releaseCoef = 0.0
    private var grDb = 0.0

    /** Linear input level at or below which the gain computer is exactly unity (no log needed). */
    private var unityBelowLinear = 0.0
    private var makeupLinear = 1.0

    /** Current gain reduction in dB (<= 0), for metering. */
    val gainReductionDb: Double get() = grDb

    fun configure(thresholdDb: Double, ratio: Double, attackMs: Double, releaseMs: Double, kneeDb: Double, makeupDb: Double) {
        threshold = thresholdDb
        this.ratio = max(ratio, 1.0)
        knee = max(kneeDb, 0.0)
        makeup = makeupDb
        unityBelowLinear = dbToLinear(threshold - knee / 2.0)
        makeupLinear = dbToLinear(makeupDb)
        attackCoef = exp(-1.0 / (sampleRate * max(attackMs, 0.01) / 1000.0))
        releaseCoef = exp(-1.0 / (sampleRate * max(releaseMs, 0.01) / 1000.0))
    }

    fun reset() {
        grDb = 0.0
    }

    /** Static gain computer: output level (dB) for input level (dB). */
    internal fun computeOutputDb(levelDb: Double): Double {
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

    /**
     * P6: log10 / pow are only evaluated while the signal is above the knee or the gain reduction is
     * still releasing. Below the knee the target reduction is exactly 0 dB, so the result equals the
     * full computation; once the reduction has decayed below 1e-12 dB it is snapped to 0 (this also
     * keeps the state out of the denormal range) and the cached make-up gain is used.
     */
    fun process(buf: FloatArray, frames: Int) {
        var i = 0
        val end = frames * 2
        while (i < end) {
            val l = buf[i]
            val r = buf[i + 1]
            val peak = max(abs(l), abs(r)).toDouble()
            val targetGr = if (peak <= unityBelowLinear) {
                0.0
            } else {
                val levelDb = linearToDb(peak)
                computeOutputDb(levelDb) - levelDb // <= 0
            }
            val coef = if (targetGr < grDb) attackCoef else releaseCoef
            grDb = coef * grDb + (1.0 - coef) * targetGr
            if (targetGr == 0.0 && grDb > -SNAP_DB) grDb = 0.0
            val g = if (grDb == 0.0) makeupLinear.toFloat() else dbToLinear(grDb + makeup).toFloat()
            buf[i] = l * g
            buf[i + 1] = r * g
            i += 2
        }
    }

    private companion object {
        const val SNAP_DB = 1e-12
    }
}

/** Smoothed linear gain (one-pole, ~10 ms) to avoid zipper noise on parameter changes. */
class SmoothedGain(sampleRate: Int) {
    private var current = 1.0
    private var target = 1.0
    private val coef = 1.0 - exp(-1.0 / (sampleRate * 0.010))

    fun setDb(db: Double) {
        target = dbToLinear(db)
    }

    fun process(buf: FloatArray, frames: Int) {
        if (current == target) {
            // Settled: constant gain, and nothing to do at all for 0 dB (the common case).
            if (target == 1.0) return
            val g = current.toFloat()
            var k = 0
            val n = frames * 2
            while (k < n) {
                buf[k] *= g
                k++
            }
            return
        }
        var i = 0
        val end = frames * 2
        while (i < end) {
            current += (target - current) * coef
            val g = current.toFloat()
            buf[i] *= g
            buf[i + 1] *= g
            i += 2
        }
        if (abs(target - current) <= SETTLE_EPS * target) current = target
    }

    private companion object {
        const val SETTLE_EPS = 1e-9
    }
}

/**
 * Look-ahead brick-wall peak limiter (stereo-linked).
 *
 * Guarantee: with L = latencySamples, gain g[j] is the average of m[j..j+L] where
 * m[t] = min(r[t-L..t]) and r[k] = min(1, ceiling/peak[k]). Since m[j+i] <= r[j] for i in 0..L,
 * g[j] <= r[j], so the output never exceeds the ceiling. A release one-pole only ever lowers
 * the gain relative to g, preserving the guarantee; a final clamp covers float rounding and
 * ceiling changes while samples are in flight.
 */
class Limiter(sampleRate: Int, lookaheadMs: Double = 2.0) {
    val latencySamples: Int = max(1, (sampleRate * lookaheadMs / 1000.0).toInt())
    private val size = latencySamples + 1

    private val delay = FloatArray(latencySamples * 2) // exactly L frames of delay

    // P6: sliding minimum of the last `size` requested gains as a monotonic deque (amortised O(1)
    // per sample instead of scanning `size` values). Window contents and results are identical to
    // the former full scan; entries older than the window are dropped from the front.
    private val dequeCapacity = size + 1
    private val dequeValue = FloatArray(dequeCapacity)
    private val dequeTick = LongArray(dequeCapacity)
    private var dequeHead = 0
    private var dequeCount = 0
    private var tick = 0L
    private var sinceResync = 0

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

            // Drop entries that can no longer be the minimum, then append the new request.
            while (dequeCount > 0) {
                var last = dequeHead + dequeCount - 1
                if (last >= dequeCapacity) last -= dequeCapacity
                if (dequeValue[last] >= req) dequeCount-- else break
            }
            var tail = dequeHead + dequeCount
            if (tail >= dequeCapacity) tail -= dequeCapacity
            dequeValue[tail] = req
            dequeTick[tail] = tick
            dequeCount++
            // Drop entries that left the window of the last `size` samples.
            while (dequeTick[dequeHead] <= tick - size) {
                dequeHead++
                if (dequeHead == dequeCapacity) dequeHead = 0
                dequeCount--
            }
            val m = dequeValue[dequeHead]
            tick++

            mSum += (m - mRing[pos]).toDouble()
            mRing[pos] = m
            if (++sinceResync >= RESYNC_INTERVAL) {
                // Re-sum the window so rounding error of the running sum cannot accumulate over hours.
                sinceResync = 0
                var sum = 0.0
                var k = 0
                while (k < size) {
                    sum += mRing[k]
                    k++
                }
                mSum = sum
            }
            val g = (mSum / size).toFloat()

            val rising = releaseCoef * smoothed + (1.0 - releaseCoef)
            val gOut = min(g.toDouble(), rising)
            smoothed = gOut

            val d = delayPos * 2
            val outL = delay[d] * gOut.toFloat()
            val outR = delay[d + 1] * gOut.toFloat()
            delay[d] = l
            delay[d + 1] = r
            buf[i * 2] = outL.coerceIn(-c, c)
            buf[i * 2 + 1] = outR.coerceIn(-c, c)

            pos++
            if (pos == size) pos = 0
            delayPos++
            if (delayPos == latencySamples) delayPos = 0
            i++
        }
    }

    private companion object {
        /** Samples between exact re-summations of the running mean (about 10 s at 48 kHz in blocks). */
        const val RESYNC_INTERVAL = 1 shl 19
    }
}
