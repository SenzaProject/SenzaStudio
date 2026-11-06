package com.senzaproject.studio.audio.dsp

import com.senzaproject.studio.audio.engine.FilterType
import com.senzaproject.studio.audio.engine.ImagingParameters
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

/** Immutable imaging configuration (linear gains, designed on the control thread). */
internal class ImagingConfig(
    val active: Boolean,
    val midGain: Double,
    val sideGain: Double,
    val gainLeft: Double,
    val gainRight: Double,
    /** 4th-order high-pass applied to the side signal (as two identical sections); null = off. */
    val monoHighPass: BiquadCoefficients?,
) {
    companion object {
        fun from(p: ImagingParameters, sampleRate: Int): ImagingConfig {
            val s = p.sanitized()
            val balance = s.balance.toDouble()
            return ImagingConfig(
                active = s.enabled,
                midGain = dbToLinear(s.midGainDb.toDouble()),
                sideGain = dbToLinear(s.sideGainDb.toDouble()),
                gainLeft = min(1.0, 1.0 - balance),
                gainRight = min(1.0, 1.0 + balance),
                monoHighPass = if (s.monoBelowHz <= 0f) {
                    null
                } else {
                    BiquadDesign.design(FilterType.HIGH_PASS, sampleRate, s.monoBelowHz, 0f, BUTTERWORTH_Q)
                },
            )
        }
    }
}

/**
 * Stereo imaging in mid/side: M = (L+R)/2, S = (L-R)/2.
 * Mid gain, side gain, optional "mono below f" (4th-order high-pass on S, so content below f is
 * exactly mono) and a channel balance that only attenuates the opposite channel (never boosts).
 * Gains are smoothed (about 10 ms) to avoid zipper noise. A mono input (L = R) has S = 0 and is
 * therefore only affected by the mid gain and the balance.
 */
class StereoImager(sampleRate: Int) {
    private val smoothing = 1.0 - exp(-1.0 / (sampleRate * 0.010))
    private val hp1 = MonoBiquad()
    private val hp2 = MonoBiquad()

    private var active = false
    private var monoOn = false
    private var targetMid = 1.0
    private var targetSide = 1.0
    private var targetLeft = 1.0
    private var targetRight = 1.0
    private var curMid = 1.0
    private var curSide = 1.0
    private var curLeft = 1.0
    private var curRight = 1.0

    internal fun configure(c: ImagingConfig) {
        val wasActive = active
        active = c.active
        targetMid = c.midGain
        targetSide = c.sideGain
        targetLeft = c.gainLeft
        targetRight = c.gainRight
        val hp = c.monoHighPass
        val nowMono = hp != null
        if (hp != null) {
            hp1.set(hp)
            hp2.set(hp)
        }
        if (nowMono && !monoOn) {
            hp1.reset(); hp2.reset()
        }
        monoOn = nowMono
        if (active && !wasActive) {
            curMid = targetMid; curSide = targetSide; curLeft = targetLeft; curRight = targetRight
            hp1.reset(); hp2.reset()
        }
    }

    fun process(buf: FloatArray, frames: Int) {
        if (!active) return
        var i = 0
        val end = frames * 2
        while (i < end) {
            curMid += (targetMid - curMid) * smoothing
            curSide += (targetSide - curSide) * smoothing
            curLeft += (targetLeft - curLeft) * smoothing
            curRight += (targetRight - curRight) * smoothing

            val l = buf[i].toDouble()
            val r = buf[i + 1].toDouble()
            val mid = (l + r) * 0.5 * curMid
            var side = (l - r) * 0.5 * curSide
            if (monoOn) side = hp2.tick(hp1.tick(side))
            buf[i] = ((mid + side) * curLeft).toFloat()
            buf[i + 1] = ((mid - side) * curRight).toFloat()
            i += 2
        }
        hp1.flush(); hp2.flush()
    }
}

/**
 * Phase / image monitoring of a stereo stream (read-only, never modifies the audio).
 * Statistics are exponentially averaged (300 ms) and published once per processed block.
 *  - [correlation]: +1 = identical (mono), 0 = uncorrelated, -1 = anti-phase (cancels in mono).
 *  - [sideToMidDb]: 10 log10(E[S^2] / E[M^2]); strongly negative = near mono, positive = wide.
 */
class StereoMeter(sampleRate: Int, windowSeconds: Double = 0.3) {
    private val coef = 1.0 - exp(-1.0 / (sampleRate * windowSeconds))
    private var ll = 0.0
    private var rr = 0.0
    private var lr = 0.0
    private var mm = 0.0
    private var ss = 0.0

    @Volatile
    var correlation: Double = 0.0
        private set

    @Volatile
    var sideToMidDb: Double = MIN_RATIO_DB
        private set

    /** False while the stream is (near) silent; [correlation] is then reported as 0. */
    @Volatile
    var hasSignal: Boolean = false
        private set

    fun reset() {
        ll = 0.0; rr = 0.0; lr = 0.0; mm = 0.0; ss = 0.0
        correlation = 0.0
        sideToMidDb = MIN_RATIO_DB
        hasSignal = false
    }

    fun process(buf: FloatArray, frames: Int) {
        var i = 0
        val end = frames * 2
        while (i < end) {
            val l = buf[i].toDouble()
            val r = buf[i + 1].toDouble()
            val m = (l + r) * 0.5
            val s = (l - r) * 0.5
            ll += (l * l - ll) * coef
            rr += (r * r - rr) * coef
            lr += (l * r - lr) * coef
            mm += (m * m - mm) * coef
            ss += (s * s - ss) * coef
            i += 2
        }
        // Keep decaying states out of the denormal range during long silence.
        if (ll < 1e-30) ll = 0.0
        if (rr < 1e-30) rr = 0.0
        if (kotlin.math.abs(lr) < 1e-30) lr = 0.0
        if (mm < 1e-30) mm = 0.0
        if (ss < 1e-30) ss = 0.0
        val energy = ll * rr
        hasSignal = ll + rr > SIGNAL_FLOOR
        correlation = if (energy > 1e-18) (lr / sqrt(energy)).coerceIn(-1.0, 1.0) else 0.0
        val ratio = 10.0 * log10((ss + 1e-12) / (mm + 1e-12))
        sideToMidDb = ratio.coerceIn(MIN_RATIO_DB, -MIN_RATIO_DB)
    }

    private companion object {
        const val MIN_RATIO_DB = -60.0
        const val SIGNAL_FLOOR = 1e-10
    }
}
