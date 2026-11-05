package com.senzaproject.studio.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FactoryPresetsTest {
    @Test
    fun idsAndNamesAreUnique() {
        assertEquals(FactoryPresets.all.size, FactoryPresets.all.map { it.id }.toSet().size)
        assertEquals(FactoryPresets.all.size, FactoryPresets.all.map { it.name }.toSet().size)
    }

    @Test
    fun flatIsTheDefaultParameterSet() {
        assertEquals(EngineParameters().sanitized(), FactoryPresets.byId("factory.flat")?.parameters)
    }

    @Test
    fun everyPresetIsValidAndSurvivesTheCodec() {
        for (p in FactoryPresets.all) {
            assertTrue(p.factory)
            assertEquals(p.name, p.parameters, p.parameters.sanitized())
            assertFalse(p.name, p.parameters.bypass)
            val back = PresetCodec.decode(PresetCodec.encode(p.parameters, p.name))
            assertTrue(back is PresetDecodeResult.Ok)
            assertEquals(p.name, p.parameters, (back as PresetDecodeResult.Ok).parameters)
        }
    }

    @Test
    fun boostingPresetsLeaveHeadroom() {
        for (p in FactoryPresets.all) {
            val maxBoost = p.parameters.eq.bands.maxOf { it.gainDb }
            if (p.parameters.eq.enabled && maxBoost >= 3f) {
                assertTrue("${p.name} needs negative master gain", p.parameters.masterGainDb < 0f)
            }
        }
    }

    @Test
    fun lookupFindsPresetsById() {
        assertNotNull(FactoryPresets.byId("factory.night"))
        assertEquals(null, FactoryPresets.byId("factory.nope"))
    }
}
