package com.senzaproject.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationTest {
    @Test
    fun mainNavigationHasFiveDestinationsInBriefOrder() {
        assertEquals(
            listOf("Studio", "Equalizer", "Spatial", "Presets", "Settings"),
            Destination.entries.map { it.label },
        )
    }

    @Test
    fun routesAreUnique() {
        val routes = Destination.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun startDestinationIsStudio() {
        assertEquals(Destination.STUDIO, Destination.start)
    }

    @Test
    fun routeLookupRoundTrips() {
        Destination.entries.forEach { assertEquals(it, Destination.fromRoute(it.route)) }
        assertNull(Destination.fromRoute("unknown"))
        assertNull(Destination.fromRoute(null))
    }
}
