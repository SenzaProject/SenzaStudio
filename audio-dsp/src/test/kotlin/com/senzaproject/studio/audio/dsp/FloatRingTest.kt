package com.senzaproject.studio.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatRingTest {
    @Test
    fun writesAndReadsInOrderAcrossTheWrapPoint() {
        val ring = FloatRing(8)
        val out = FloatArray(8)
        assertEquals(6, ring.write(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), 0, 6))
        assertEquals(4, ring.read(out, 0, 4))
        assertEquals(listOf(1f, 2f, 3f, 4f), out.take(4))
        // Wraps: 2 old + 5 new floats.
        assertEquals(5, ring.write(floatArrayOf(7f, 8f, 9f, 10f, 11f), 0, 5))
        assertEquals(7, ring.availableToRead())
        assertEquals(7, ring.read(out, 0, 7))
        assertEquals(listOf(5f, 6f, 7f, 8f, 9f, 10f, 11f), out.take(7))
    }

    @Test
    fun neverAcceptsMoreThanTheFreeSpaceAndNeverOverwritesUnreadData() {
        val ring = FloatRing(4)
        assertEquals(4, ring.write(floatArrayOf(1f, 2f, 3f, 4f, 5f), 0, 5))
        assertEquals(0, ring.availableToWrite())
        assertEquals(0, ring.write(floatArrayOf(9f), 0, 1))
        val out = FloatArray(4)
        assertEquals(4, ring.read(out, 0, 4))
        assertEquals(listOf(1f, 2f, 3f, 4f), out.toList())
        assertEquals(0, ring.read(out, 0, 4))
    }

    @Test
    fun producerAndConsumerThreadsExchangeAnExactSequence() {
        val ring = FloatRing(2_048)
        val total = 3_000_000
        var bad = -1
        val producer = Thread {
            val chunk = FloatArray(512)
            var next = 0
            while (next < total) {
                val n = minOf(chunk.size, total - next)
                for (i in 0 until n) chunk[i] = (next + i).toFloat()
                var off = 0
                while (off < n) {
                    val w = ring.write(chunk, off, n - off)
                    off += w
                    if (w == 0) Thread.yield()
                }
                next += n
            }
        }
        val consumer = Thread {
            val buf = FloatArray(700)
            var expect = 0
            while (expect < total && bad < 0) {
                val r = ring.read(buf, 0, buf.size)
                if (r == 0) { Thread.yield(); continue }
                for (i in 0 until r) {
                    if (buf[i] != (expect + i).toFloat()) { bad = expect + i; break }
                }
                expect += r
            }
        }
        producer.start(); consumer.start()
        producer.join(60_000); consumer.join(60_000)
        assertTrue("threads did not finish", !producer.isAlive && !consumer.isAlive)
        assertEquals("first corrupted index", -1, bad)
    }
}
