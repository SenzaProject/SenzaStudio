package com.senzaproject.studio.audio.dsp

import com.senzaproject.studio.audio.engine.FilterType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Normalised biquad coefficients (a0 = 1): y = b0 x + b1 x1 + b2 x2 - a1 y1 - a2 y2. */
data class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    /** Stable iff both poles are inside the unit circle (Jury criterion). */
    val isStable: Boolean get() = kotlin.math.abs(a2) < 1.0 && kotlin.math.abs(a1) < 1.0 + a2

    /** Magnitude response in dB at [frequencyHz]. */
    fun magnitudeDb(frequencyHz: Double, sampleRate: Double): Double {
        val w = 2.0 * PI * frequencyHz / sampleRate
        val c1 = cos(w); val s1 = sin(w)
        val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2
        val ni = -(b1 * s1 + b2 * s2)
        val dr = 1.0 + a1 * c1 + a2 * c2
        val di = -(a1 * s1 + a2 * s2)
        val mag = sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        return 20.0 * log10(mag.coerceAtLeast(1e-12))
    }

    companion object {
        val IDENTITY = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * RBJ "Audio EQ Cookbook" designs. Q-based shelves.
 * Frequency is clamped below 0.45 * fs so filters stay well-behaved near Nyquist.
 */
object BiquadDesign {
    fun design(type: FilterType, sampleRate: Int, frequencyHz: Float, gainDb: Float, q: Float): BiquadCoefficients {
        val fs = sampleRate.toDouble()
        val f = frequencyHz.toDouble().coerceIn(1.0, fs * 0.45)
        val qq = q.toDouble().coerceAtLeast(0.05)
        val a = 10.0.pow(gainDb.toDouble() / 40.0)
        val w0 = 2.0 * PI * f / fs
        val cosw = cos(w0)
        val alpha = sin(w0) / (2.0 * qq)
        val sqrtA2alpha = 2.0 * sqrt(a) * alpha

        val b0: Double; val b1: Double; val b2: Double
        val a0: Double; val a1: Double; val a2: Double
        when (type) {
            FilterType.PEAKING -> {
                b0 = 1 + alpha * a; b1 = -2 * cosw; b2 = 1 - alpha * a
                a0 = 1 + alpha / a; a1 = -2 * cosw; a2 = 1 - alpha / a
            }
            FilterType.LOW_SHELF -> {
                b0 = a * ((a + 1) - (a - 1) * cosw + sqrtA2alpha)
                b1 = 2 * a * ((a - 1) - (a + 1) * cosw)
                b2 = a * ((a + 1) - (a - 1) * cosw - sqrtA2alpha)
                a0 = (a + 1) + (a - 1) * cosw + sqrtA2alpha
                a1 = -2 * ((a - 1) + (a + 1) * cosw)
                a2 = (a + 1) + (a - 1) * cosw - sqrtA2alpha
            }
            FilterType.HIGH_SHELF -> {
                b0 = a * ((a + 1) + (a - 1) * cosw + sqrtA2alpha)
                b1 = -2 * a * ((a - 1) + (a + 1) * cosw)
                b2 = a * ((a + 1) + (a - 1) * cosw - sqrtA2alpha)
                a0 = (a + 1) - (a - 1) * cosw + sqrtA2alpha
                a1 = 2 * ((a - 1) - (a + 1) * cosw)
                a2 = (a + 1) - (a - 1) * cosw - sqrtA2alpha
            }
            FilterType.HIGH_PASS -> {
                b0 = (1 + cosw) / 2; b1 = -(1 + cosw); b2 = (1 + cosw) / 2
                a0 = 1 + alpha; a1 = -2 * cosw; a2 = 1 - alpha
            }
            FilterType.LOW_PASS -> {
                b0 = (1 - cosw) / 2; b1 = 1 - cosw; b2 = (1 - cosw) / 2
                a0 = 1 + alpha; a1 = -2 * cosw; a2 = 1 - alpha
            }
        }
        return BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}

/**
 * Stereo (interleaved) biquad, transposed direct form II, double-precision state.
 * No allocation in [process]. Coefficient changes take effect at the next block.
 */
class StereoBiquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private var z1l = 0.0; private var z2l = 0.0
    private var z1r = 0.0; private var z2r = 0.0

    var active: Boolean = false
        private set

    fun set(c: BiquadCoefficients) {
        b0 = c.b0; b1 = c.b1; b2 = c.b2; a1 = c.a1; a2 = c.a2
        active = true
    }

    fun bypass() {
        active = false
        reset()
    }

    fun reset() {
        z1l = 0.0; z2l = 0.0; z1r = 0.0; z2r = 0.0
    }

    fun process(buf: FloatArray, frames: Int) {
        var i = 0
        val end = frames * 2
        while (i < end) {
            val xl = buf[i].toDouble()
            val yl = b0 * xl + z1l
            z1l = b1 * xl - a1 * yl + z2l
            z2l = b2 * xl - a2 * yl
            buf[i] = yl.toFloat()

            val xr = buf[i + 1].toDouble()
            val yr = b0 * xr + z1r
            z1r = b1 * xr - a1 * yr + z2r
            z2r = b2 * xr - a2 * yr
            buf[i + 1] = yr.toFloat()
            i += 2
        }
        // Flush denormals (slow on some CPUs).
        if (kotlin.math.abs(z1l) < 1e-30) z1l = 0.0
        if (kotlin.math.abs(z2l) < 1e-30) z2l = 0.0
        if (kotlin.math.abs(z1r) < 1e-30) z1r = 0.0
        if (kotlin.math.abs(z2r) < 1e-30) z2r = 0.0
    }
}

