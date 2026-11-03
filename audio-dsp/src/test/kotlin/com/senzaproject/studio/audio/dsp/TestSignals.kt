package com.senzaproject.studio.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
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

/** Interleaved stereo sine with independent channel amplitudes (a negative amplitude = inverted polarity). */
fun stereoSine(freq: Double, ampL: Double, ampR: Double, seconds: Double, fs: Int = FS): FloatArray {
    val n = (seconds * fs).toInt()
    val out = FloatArray(n * 2)
    for (i in 0 until n) {
        val v = sin(2 * PI * freq * i / fs)
        out[i * 2] = (ampL * v).toFloat()
        out[i * 2 + 1] = (ampR * v).toFloat()
    }
    return out
}

fun dbfs(amplitude: Double): Double = 20.0 * log10(amplitude)

/** Runs [process] over [input] in blocks of [block] frames and returns the processed copy. */
fun runBlocks(input: FloatArray, block: Int = 480, process: (FloatArray, Int) -> Unit): FloatArray {
    val out = input.copyOf()
    val tmp = FloatArray(block * 2)
    val total = out.size / 2
    var f = 0
    while (f < total) {
        val n = minOf(block, total - f)
        System.arraycopy(out, f * 2, tmp, 0, n * 2)
        process(tmp, n)
        System.arraycopy(tmp, 0, out, f * 2, n * 2)
        f += n
    }
    return out
}

/**
 * Peak amplitude of the component at [freq] in one channel over [count] frames from [from].
 * Exact when the window holds a whole number of cycles (use whole-second windows and integer Hz).
 */
fun toneAmplitude(buf: FloatArray, channel: Int, freq: Double, from: Int, count: Int, fs: Int = FS): Double {
    var re = 0.0
    var im = 0.0
    for (n in 0 until count) {
        val v = buf[(from + n) * 2 + channel].toDouble()
        val ph = 2.0 * PI * freq * n / fs
        re += v * cos(ph)
        im += v * sin(ph)
    }
    return 2.0 * sqrt(re * re + im * im) / count
}

/** RMS of one channel from [fromFrame]. */
fun rmsChannel(buf: FloatArray, channel: Int, fromFrame: Int = 0): Double {
    var sum = 0.0
    var n = 0
    var i = fromFrame
    while (i * 2 + channel < buf.size) {
        val v = buf[i * 2 + channel].toDouble()
        sum += v * v; n++; i++
    }
    return sqrt(sum / n)
}

/** RMS of the side signal (L-R)/2 from [fromFrame]. */
fun rmsSide(buf: FloatArray, fromFrame: Int = 0): Double {
    var sum = 0.0
    var n = 0
    var i = fromFrame
    while (i * 2 + 1 < buf.size) {
        val v = (buf[i * 2] - buf[i * 2 + 1]) * 0.5
        sum += v.toDouble() * v; n++; i++
    }
    return sqrt(sum / n)
}

/** Overall RMS of both channels. */
fun rmsAll(buf: FloatArray): Double = sqrt(buf.sumOf { it.toDouble() * it } / buf.size)

/** Stereo noise with independent channels. */
fun stereoNoise(amplitude: Float, frames: Int, seedL: Long = 1, seedR: Long = 2): FloatArray {
    val l = java.util.Random(seedL)
    val r = java.util.Random(seedR)
    return FloatArray(frames * 2) { i ->
        val rnd = if (i % 2 == 0) l else r
        (rnd.nextFloat() * 2f - 1f) * amplitude
    }
}

/** Identical noise in both channels (a mono source). */
fun monoNoise(amplitude: Float, frames: Int, seed: Long = 5): FloatArray {
    val rnd = java.util.Random(seed)
    val out = FloatArray(frames * 2)
    for (i in 0 until frames) {
        val v = (rnd.nextFloat() * 2f - 1f) * amplitude
        out[i * 2] = v; out[i * 2 + 1] = v
    }
    return out
}

/** Repeating kick drum: decaying sine bursts, stereo-identical. */
fun kick(amplitude: Double = 0.5, freq: Double = 60.0, tau: Double = 0.08, period: Double = 0.5, seconds: Double = 3.0): FloatArray {
    val n = (seconds * FS).toInt()
    val out = FloatArray(n * 2)
    val burst = (0.4 * FS).toInt()
    var start = 0
    while (start < n) {
        for (j in 0 until minOf(burst, n - start)) {
            val t = j.toDouble() / FS
            val v = (amplitude * exp(-t / tau) * sin(2 * PI * freq * t)).toFloat()
            out[(start + j) * 2] += v
            out[(start + j) * 2 + 1] += v
        }
        start += (period * FS).toInt()
    }
    return out
}
