package com.senzaproject.studio.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoOpAudioEngineTest {
    @Test
    fun startsStopped() {
        assertEquals(EngineStatus.STOPPED, NoOpAudioEngine().state.value.status)
    }

    @Test
    fun runningNeverClaimsToProcessAudio() {
        val engine = NoOpAudioEngine()
        engine.start()
        assertEquals(EngineStatus.RUNNING, engine.state.value.status)
        assertFalse(engine.state.value.isProcessingAudio)
        engine.stop()
        assertEquals(EngineStatus.STOPPED, engine.state.value.status)
    }

    @Test
    fun noModuleIsReportedAvailable() {
        val caps = NoOpAudioEngine().capabilities
        assertEquals(ProcessingScope.NONE, caps.scope)
        assertEquals(DspModule.entries.size, caps.modules.size)
        assertTrue(caps.modules.all { it.availability is Availability.Unavailable })
    }

    @Test
    fun parameterUpdatesFlowThroughEngine() {
        val engine = NoOpAudioEngine()
        engine.updateParameters { it.copy(masterGainDb = -3f) }
        assertEquals(-3f, engine.parameters.value.masterGainDb, 0f)
    }
}
