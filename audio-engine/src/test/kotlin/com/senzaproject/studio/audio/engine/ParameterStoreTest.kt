package com.senzaproject.studio.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ParameterStoreTest {
    @Test
    fun updatesAreSanitized() {
        val store = ParameterStore()
        store.update { it.copy(masterGainDb = 50f) }
        assertEquals(EngineParameters.MAX_MASTER_GAIN_DB, store.state.value.masterGainDb, 0f)
    }

    @Test
    fun concurrentUpdatesAreAtomic() {
        val store = ParameterStore()
        val start = store.state.value.eq.bands[0].frequencyHz
        val threads = List(4) {
            Thread {
                repeat(1_000) {
                    store.update { p ->
                        val bands = p.eq.bands.toMutableList()
                        bands[0] = bands[0].copy(frequencyHz = bands[0].frequencyHz + 1f)
                        p.copy(eq = p.eq.copy(bands = bands))
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(start + 4_000f, store.state.value.eq.bands[0].frequencyHz, 0f)
    }

    @Test
    fun resetRestoresDefaults() {
        val store = ParameterStore()
        store.update { it.copy(masterGainDb = -6f) }
        store.reset()
        assertEquals(EngineParameters(), store.state.value)
    }
}
