package com.senzaproject.studio.audio.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decoder output (16-bit or float PCM, mono or stereo) -> interleaved stereo float for [DspChain].
 * No allocation. Reads from [src] at its current position and advances it by what was consumed.
 */
object PcmConvert {
    const val SHORT_SCALE = 1.0f / 32768f

    /**
     * Converts up to [maxFrames] frames of 16-bit PCM. [channels] is 1 or 2 (mono is duplicated).
     * Returns the number of frames written to [dst] starting at frame [dstFrame].
     */
    fun pcm16ToStereoFloat(src: ByteBuffer, channels: Int, dst: FloatArray, dstFrame: Int, maxFrames: Int): Int {
        require(channels == 1 || channels == 2) { "Only mono and stereo are supported" }
        val order = src.order()
        src.order(ByteOrder.LITTLE_ENDIAN)
        val available = src.remaining() / (2 * channels)
        val frames = minOf(available, maxFrames, dst.size / 2 - dstFrame)
        var o = dstFrame * 2
        for (i in 0 until frames) {
            val l = src.short * SHORT_SCALE
            val r = if (channels == 2) src.short * SHORT_SCALE else l
            dst[o] = l
            dst[o + 1] = r
            o += 2
        }
        src.order(order)
        return frames
    }

    /** Same for 32-bit float PCM. */
    fun pcmFloatToStereoFloat(src: ByteBuffer, channels: Int, dst: FloatArray, dstFrame: Int, maxFrames: Int): Int {
        require(channels == 1 || channels == 2) { "Only mono and stereo are supported" }
        val order = src.order()
        src.order(ByteOrder.LITTLE_ENDIAN)
        val available = src.remaining() / (4 * channels)
        val frames = minOf(available, maxFrames, dst.size / 2 - dstFrame)
        var o = dstFrame * 2
        for (i in 0 until frames) {
            val l = src.float
            val r = if (channels == 2) src.float else l
            dst[o] = l
            dst[o + 1] = r
            o += 2
        }
        src.order(order)
        return frames
    }
}
