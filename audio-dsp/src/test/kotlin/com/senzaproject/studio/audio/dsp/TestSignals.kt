package com.senzaproject.studio.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

const val FS = 48_000

/** Interleaved stereo sine. */
fun sine(freq: Double, amplitude: Double, seconds: Double, fs: Int = FS): FloatArray {
    val n = (seconds * fs).toInt()
    val out = FloatArray(n * 2)
    for (i in 0 until n) {
        val v = (amplitude * sin(2 * PI * freq * i / fs)).toFloat()
        out[i * 2] = v; out[i * 2 + 1] = v
    }
    return out
}

/** Deterministic pseudo-noise in [-amplitude, amplitude], interleaved stereo. */
fun noise(amplitude: Float, frames: Int, seed: Long = 42): FloatArray {
    val rnd = java.util.Random(seed)
    return FloatArray(frames * 2) { (rnd.nextFloat() * 2f - 1f) * amplitude }
}

fun peak(buf: FloatArray, from: Int = 0): Float {
    var p = 0f
    for (i in from until buf.size) p = maxOf(p, abs(buf[i]))
    return p
}

/** RMS of the left channel from frame [fromFrame]. */
fun rmsLeft(buf: FloatArray, fromFrame: Int): Double {
    var sum = 0.0; var n = 0
    var i = fromFrame
    while (i * 2 < buf.size) { val v = buf[i * 2].toDouble(); sum += v * v; n++; i++ }
    return sqrt(sum / n)
}
