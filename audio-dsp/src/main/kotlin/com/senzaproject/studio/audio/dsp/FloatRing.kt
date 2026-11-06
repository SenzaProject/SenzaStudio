package com.senzaproject.studio.audio.dsp

/**
 * Single-producer / single-consumer ring buffer of floats (interleaved stereo frames use 2 floats
 * each, so keep reads and writes in whole frames).
 *
 * Lock-free and allocation-free after construction: exactly one thread may call [write] and
 * exactly one (other) thread may call [read]. The two monotonically increasing counters are
 * volatile, so the producer's data writes are visible to the consumer before it sees the new
 * write count, and the consumer's reads are finished before the producer sees the freed space.
 * [availableToRead] / [availableToWrite] are safe from their own side; from the other side they
 * are conservative (a slightly stale value never over-reports).
 */
class FloatRing(val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val data = FloatArray(capacity)

    @Volatile
    private var writeCount = 0L

    @Volatile
    private var readCount = 0L

    /** Floats ready to be read. */
    fun availableToRead(): Int = (writeCount - readCount).toInt()

    /** Floats that can be written without overwriting unread data. */
    fun availableToWrite(): Int = capacity - (writeCount - readCount).toInt()

    /** Producer side. Copies up to [count] floats and returns how many were accepted (may be 0). */
    fun write(src: FloatArray, offset: Int, count: Int): Int {
        val w = writeCount
        val n = minOf(count, capacity - (w - readCount).toInt())
        if (n <= 0) return 0
        val start = (w % capacity).toInt()
        val first = minOf(n, capacity - start)
        System.arraycopy(src, offset, data, start, first)
        if (n > first) System.arraycopy(src, offset + first, data, 0, n - first)
        writeCount = w + n
        return n
    }

    /** Consumer side. Copies up to [count] floats into [dst] and returns how many were read (may be 0). */
    fun read(dst: FloatArray, offset: Int, count: Int): Int {
        val r = readCount
        val n = minOf(count, (writeCount - r).toInt())
        if (n <= 0) return 0
        val start = (r % capacity).toInt()
        val first = minOf(n, capacity - start)
        System.arraycopy(data, start, dst, offset, first)
        if (n > first) System.arraycopy(data, 0, dst, offset + first, n - first)
        readCount = r + n
        return n
    }
}
