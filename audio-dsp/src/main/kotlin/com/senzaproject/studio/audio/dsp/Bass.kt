package com.senzaproject.studio.audio.dsp

import com.senzaproject.studio.audio.engine.BassParameters
import com.senzaproject.studio.audio.engine.FilterType
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/** Immutable bass configuration, designed on the control thread and applied on the audio thread. */
internal class BassConfig(
    val active: Boolean,
    /** 60 Hz low shelf; null = bypassed. */
    val shelf: BiquadCoefficients?,
    /** Splits the low band that feeds the harmonic generator and the punch detector. */
    val lowPass: BiquadCoefficients,
    /** Removes the fundamental from the generated harmonics. */
    val harmonicHighPass: BiquadCoefficients,
    /** Limits the generated harmonics to 4x the crossover. */
    val harmonicLowPass: BiquadCoefficients,
    val harmonics: Double,
    val punch: Double,
) {
    companion object {
        const val SHELF_HZ = 60f

        fun from(p: BassParameters, sampleRate: Int): BassConfig {
            val s = p.sanitized()
            val xo = s.crossoverHz
            return BassConfig(
                active = s.enabled,
                shelf = if (abs(s.subBassDb) < 0.05f) {
                    null
                } else {
                    BiquadDesign.design(FilterType.LOW_SHELF, sampleRate, SHELF_HZ, s.subBassDb, BUTTERWORTH_Q)
                },
                lowPass = BiquadDesign.design(FilterType.LOW_PASS, sampleRate, xo, 0f, BUTTERWORTH_Q),
                harmonicHighPass = BiquadDesign.design(FilterType.HIGH_PASS, sampleRate, xo, 0f, BUTTERWORTH_Q),
                harmonicLowPass = BiquadDesign.design(FilterType.LOW_PASS, sampleRate, xo * 4f, 0f, BUTTERWORTH_Q),
                harmonics = s.harmonics.toDouble(),
                punch = s.punch.toDouble(),
            )
        }
    }
}

/**
 * Bass enhancement, stereo in / stereo out (interleaved float).
 *
 *  1. Sub-bass: 60 Hz low shelf (+-12 dB).
 *  2. Harmonics (psychoacoustic bass): the mono low band (below the crossover) is normalised by a
 *     peak envelope, soft-saturated with an asymmetric tanh (odd + even harmonics), restored to its
 *     level, high-passed (4th order at the crossover, removes the fundamental) and low-passed at
 *     4x the crossover, then added to both channels. Because of the normalisation the harmonic-to-
 *     fundamental ratio is independent of input level above about -34 dBFS and fades out below it.
 *     tanh bounds the generated signal; the always-on limiter protects the output.
 *  3. Punch: a transient shaper on each low-band channel. Fast and slow envelopes are compared;
 *     where fast exceeds slow (an attack), the low band is added back with extra gain
 *     (at most 2 x punch, i.e. up to +9.5 dB on the attack of the low band).
 *
 * Real-time safe: no allocation, no locks. Configuration arrives through [configure] on the audio thread.
 */
class BassEnhancer(private val sampleRate: Int) {
    private val shelf = StereoBiquad()
    private val lowL = MonoBiquad()
    private val lowR = MonoBiquad()
    private val harmHp1 = MonoBiquad()
    private val harmHp2 = MonoBiquad()
    private val harmLp = MonoBiquad()

    private var active = false
    private var useHarmonics = false
    private var usePunch = false
    private var harmonics = 0.0
    private var punch = 0.0
    private var drive = DRIVE_BASE

    private var harmEnv = 0.0
    private var fast = 0.0
    private var slow = 0.0

    private val harmAttack = timeCoef(2.0)
    private val harmRelease = timeCoef(80.0)
    private val fastAttack = timeCoef(1.0)
    private val fastRelease = timeCoef(20.0)
    private val slowAttack = timeCoef(20.0)
    private val slowRelease = timeCoef(100.0)

    private fun timeCoef(ms: Double): Double = exp(-1.0 / (sampleRate * ms / 1000.0))

    internal fun configure(c: BassConfig) {
        val wasActive = active
        active = c.active
        val shelfCoefficients = c.shelf
        if (shelfCoefficients == null) shelf.bypass() else shelf.set(shelfCoefficients)
        lowL.set(c.lowPass)
        lowR.set(c.lowPass)
        harmHp1.set(c.harmonicHighPass)
        harmHp2.set(c.harmonicHighPass)
        harmLp.set(c.harmonicLowPass)
        harmonics = c.harmonics
        punch = c.punch
        drive = DRIVE_BASE + DRIVE_SLOPE * harmonics
        useHarmonics = harmonics > 0.0
        usePunch = punch > 0.0
        if (active && !wasActive) reset()
    }

    fun reset() {
        lowL.reset(); lowR.reset()
        harmHp1.reset(); harmHp2.reset(); harmLp.reset()
        harmEnv = 0.0; fast = 0.0; slow = 0.0
    }

    fun process(buf: FloatArray, frames: Int) {
        if (!active) return
        if (shelf.active) shelf.process(buf, frames)
        if (!useHarmonics && !usePunch) return

        var i = 0
        val end = frames * 2
        while (i < end) {
            val l = buf[i].toDouble()
            val r = buf[i + 1].toDouble()
            val lowOfL = lowL.tick(l)
            val lowOfR = lowR.tick(r)
            var addL = 0.0
            var addR = 0.0

            if (useHarmonics) {
                val mid = (lowOfL + lowOfR) * 0.5
                val a = abs(mid)
                harmEnv = if (a > harmEnv) {
                    harmAttack * harmEnv + (1.0 - harmAttack) * a
                } else {
                    harmRelease * harmEnv + (1.0 - harmRelease) * a
                }
                val den = max(harmEnv, ENV_FLOOR)
                val shaped = (tanh(drive * mid / den + BIAS) - TANH_BIAS) * den
                val h = harmLp.tick(harmHp2.tick(harmHp1.tick(shaped)))
                val g = HARMONIC_GAIN * harmonics * h
                addL += g
                addR += g
            }

            if (usePunch) {
                val x = max(abs(lowOfL), abs(lowOfR))
                fast = if (x > fast) {
                    fastAttack * fast + (1.0 - fastAttack) * x
                } else {
                    fastRelease * fast + (1.0 - fastRelease) * x
                }
                slow = if (x > slow) {
                    slowAttack * slow + (1.0 - slowAttack) * x
                } else {
                    slowRelease * slow + (1.0 - slowRelease) * x
                }
                if (slow > SLOW_FLOOR) {
                    val extra = min(max(fast / slow - 1.0, 0.0), MAX_PUNCH_RATIO) * punch
                    addL += extra * lowOfL
                    addR += extra * lowOfR
                }
            }

            buf[i] = (l + addL).toFloat()
            buf[i + 1] = (r + addR).toFloat()
            i += 2
        }
        lowL.flush(); lowR.flush()
        harmHp1.flush(); harmHp2.flush(); harmLp.flush()
    }

    private companion object {
        const val DRIVE_BASE = 0.5
        const val DRIVE_SLOPE = 5.0
        const val BIAS = 0.8
        val TANH_BIAS = tanh(BIAS)
        const val HARMONIC_GAIN = 1.5
        /** About -34 dBFS: below this the normaliser stops lifting the low band into the shaper. */
        const val ENV_FLOOR = 0.02
        const val SLOW_FLOOR = 1e-5
        const val MAX_PUNCH_RATIO = 2.0
    }
}
