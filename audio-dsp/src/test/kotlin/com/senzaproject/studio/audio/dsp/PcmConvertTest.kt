package com.senzaproject.studio.audio.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmConvertTest {
    private fun shorts(vararg v: Int): ByteBuffer {
        val b = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putShort(it.toShort()) }
        b.flip()
        return b
    }

    @Test
    fun stereo16BitScalesToUnitRange() {
        val src = shorts(16384, -16384, 32767, -32768)
        val dst = FloatArray(8)
        val n = PcmConvert.pcm16ToStereoFloat(src, 2, dst, 0, 100)
        assertEquals(2, n)
        assertEquals(0.5f, dst[0], 1e-6f)
        assertEquals(-0.5f, dst[1], 1e-6f)
        assertEquals(1f, dst[2], 1e-4f)
        assertEquals(-1f, dst[3], 0f)
        assertEquals(0, src.remaining())
    }

    @Test
    fun monoIsDuplicatedToBothChannels() {
        val dst = FloatArray(6)
        val n = PcmConvert.pcm16ToStereoFloat(shorts(8192, -8192, 0), 1, dst, 0, 10)
        assertEquals(3, n)
        assertEquals(0.25f, dst[0], 1e-6f); assertEquals(0.25f, dst[1], 1e-6f)
        assertEquals(-0.25f, dst[2], 1e-6f); assertEquals(-0.25f, dst[3], 1e-6f)
    }

    @Test
    fun conversionRespectsMaxFramesAndDestinationSpace() {
        val src = shorts(1, 1, 2, 2, 3, 3, 4, 4)
        val dst = FloatArray(4) // two frames of room
        assertEquals(2, PcmConvert.pcm16ToStereoFloat(src, 2, dst, 0, 10))
        assertEquals(8, src.remaining()) // two frames (8 bytes) left untouched
        val dst2 = FloatArray(8)
        assertEquals(1, PcmConvert.pcm16ToStereoFloat(src, 2, dst2, 1, 1))
        assertEquals(0f, dst2[0], 0f) // frame 0 untouched, frame 1 written
        assertEquals(3 * PcmConvert.SHORT_SCALE, dst2[2], 1e-9f)
    }

    @Test
    fun floatPcmIsCopiedAndMonoDuplicated() {
        val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        b.putFloat(0.5f).putFloat(-0.25f); b.flip()
        val dst = FloatArray(4)
        assertEquals(2, PcmConvert.pcmFloatToStereoFloat(b, 1, dst, 0, 10))
        assertEquals(listOf(0.5f, 0.5f, -0.25f, -0.25f), dst.toList())
    }

    @Test
    fun callersByteOrderIsRestoredAndUnsupportedLayoutsAreRejected() {
        val src = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        PcmConvert.pcm16ToStereoFloat(src, 2, FloatArray(2), 0, 1)
        assertEquals(ByteOrder.BIG_ENDIAN, src.order())
        assertThrows(IllegalArgumentException::class.java) {
            PcmConvert.pcm16ToStereoFloat(src, 6, FloatArray(2), 0, 1)
        }
    }
}
