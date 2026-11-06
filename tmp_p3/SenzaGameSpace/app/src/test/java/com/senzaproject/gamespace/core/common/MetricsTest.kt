package com.senzaproject.gamespace.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricsTest {
    @Test fun percentOf_basicValues() {
        assertEquals(50, percentOf(50, 100))
        assertEquals(25, percentOf(1, 4))
        assertEquals(100, percentOf(8, 8))
    }

    @Test fun percentOf_zeroOrNegativeTotalIsZero() {
        assertEquals(0, percentOf(10, 0))
        assertEquals(0, percentOf(10, -5))
    }

    @Test fun percentOf_isClamped() {
        assertEquals(100, percentOf(300, 100))
        assertEquals(0, percentOf(-10, 100))
    }

    @Test fun percentOf_largeByteValuesDoNotOverflow() {
        val eightGb = 8L * 1024 * 1024 * 1024
        assertEquals(50, percentOf(eightGb / 2, eightGb))
    }

    @Test fun batteryPercent_validValues() {
        assertEquals(80, batteryPercent(80, 100))
        assertEquals(50, batteryPercent(127, 254))
    }

    @Test fun batteryPercent_invalidValuesAreNull() {
        assertNull(batteryPercent(-1, 100))
        assertNull(batteryPercent(50, 0))
        assertNull(batteryPercent(50, -1))
    }
}
