package com.senzaproject.studio.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityTest {
    private fun report(probe: ProbeResult, devices: List<OutputDevice> = emptyList(), lowLatency: Boolean = true) =
        CompatibilityEvaluator.evaluate(PlatformFacts(sdkInt = 34, lowLatencyFeature = lowLatency, dynamicsProcessing = probe, devices = devices))

    @Test
    fun systemWidePathsAreNeverClaimed() {
        val r = report(ProbeResult.Supported)
        assertTrue(r.of(ProcessingPath.GLOBAL_MIX) is Availability.Unavailable)
        assertTrue(r.of(ProcessingPath.SYSTEM_PRIVILEGED) is Availability.Unavailable)
        assertEquals(ProcessingScope.APP_LOCAL, CompatibilityEvaluator.capabilities(r).scope)
    }

    @Test
    fun sessionEffectsFollowTheProbeResult() {
        assertTrue(report(ProbeResult.Supported).of(ProcessingPath.SESSION_EFFECT) is Availability.Experimental)
        assertTrue(report(ProbeResult.NotTried).of(ProcessingPath.SESSION_EFFECT) is Availability.Unavailable)
        val failed = report(ProbeResult.Failed("no vendor support")).of(ProcessingPath.SESSION_EFFECT)
        assertTrue(failed is Availability.Unavailable && failed.reason.contains("no vendor support"))
    }

    @Test
    fun appPlayerIsExperimentalUntilDeviceQa() {
        val a = report(ProbeResult.NotTried).of(ProcessingPath.APP_PLAYER)
        assertTrue(a is Availability.Experimental)
    }

    @Test
    fun moduleCapabilitiesNeverOverstate() {
        val caps = CompatibilityEvaluator.capabilities(report(ProbeResult.Supported))
        assertEquals(DspModule.entries.size, caps.modules.size)
        assertTrue(caps.of(DspModule.CLARITY).availability is Availability.Unavailable)
        assertTrue(caps.of(DspModule.SPECTRUM).availability is Availability.Unavailable)
        for (m in listOf(DspModule.EQUALIZER, DspModule.BASS, DspModule.SPATIAL, DspModule.STEREO_IMAGING, DspModule.DYNAMICS, DspModule.PROTECTION)) {
            assertTrue("$m", caps.of(m).availability is Availability.Experimental)
        }
        assertFalse(caps.modules.any { it.availability is Availability.Available })
    }

    @Test
    fun notesReflectTheDevice() {
        val bt = OutputDevice(1, DeviceType.BLUETOOTH, "buds")
        val notes = report(ProbeResult.Supported, devices = listOf(bt), lowLatency = false).notes
        assertTrue(notes.any { it.contains("low-latency") })
        assertTrue(notes.any { it.contains("Bluetooth") })
        assertTrue(report(ProbeResult.Supported).notes.any { it.contains("No output device") })
    }
}
